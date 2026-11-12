// Rotas do lojista: cadastro, canais (conexão, sincronização), catálogo central, pedidos e webhooks de estoque.
import { conector } from "../canais/index.js";
import { ErroCanal } from "../canais/comum.js";
import { hmacHex, iguais, segredoDoCanal } from "./cripto.js";
import { acessoLiberado, comPrivilegios, ErroLoja, lojaDoPedido, registrarLoja } from "./lojas.js";
import { endereco, esc, html, corValida, lerAtributos, paginaDaVitrine, paginaDoProduto, RESERVADAS, urlHttps, validarCodigo, soDigitos } from "./vitrine.js";
import { aleatorio } from "./cripto.js";
import { ehServico, tratarServico } from "./servicos.js";
import { PAINEL } from "./painel.js";
import { webhookInfinitePay, webhookMercadoPago } from "./assinatura.js";
import { groq } from "../ia/provedores.js";
import { conectarCanal, listarCanais, publico, removerCanal, sincronizarPedidos, sincronizarProdutos, tratarWebhook } from "./sincronia.js";

const json = (d, status = 200) =>
  new Response(JSON.stringify(d), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } });
const pagina = (titulo, texto) =>
  new Response(`<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>${titulo}</title><body style="font-family:sans-serif;background:#05060f;color:#fff;padding:40px 24px;text-align:center"><h2>${titulo}</h2><p>${texto}</p>`,
    { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });

async function lerCorpo(req, max = 200_000) {
  const t = await req.text();
  if (t.length > max) throw new ErroLoja(413, "corpo_grande", "Pedido grande demais.");
  return t;
}
const corpoJson = async (req) => {
  try {
    const d = JSON.parse((await lerCorpo(req)) || "{}");
    if (!d || typeof d !== "object" || Array.isArray(d)) throw 0;
    return d;
  } catch (e) {
    if (e instanceof ErroLoja) throw e;
    throw new ErroLoja(400, "json_invalido", "O corpo precisa ser um JSON válido.");
  }
};

const exigeAcesso = (loja) => {
  if (!acessoLiberado(loja)) throw new ErroLoja(402, "acesso_expirado", "O teste grátis acabou. Assine o Plano Fundadores para continuar.");
};

async function estadoOAuth(env, lojaId, plataforma, agora = Date.now()) {
  const venc = agora + 15 * 60_000;
  const base = `${lojaId}.${plataforma}.${venc}`;
  return `${base}.${(await hmacHex(`oauth:${env.CHAVE_COFRE || ""}`, base)).slice(0, 32)}`;
}
async function lerEstadoOAuth(env, state, plataforma, agora = Date.now()) {
  const [loja, plat, venc, sig] = String(state || "").split(".");
  const ok = loja && plat === plataforma && Number(venc) > agora &&
    iguais(sig, (await hmacHex(`oauth:${env.CHAVE_COFRE || ""}`, `${loja}.${plat}.${venc}`)).slice(0, 32));
  if (!ok) throw new ErroLoja(400, "estado_invalido", "O pedido de conexão expirou. Tente de novo pelo app.");
  return loja;
}

export const ehRotaDaLoja = (c) =>
  c.startsWith("/loja/") || c === "/canais" || c.startsWith("/canais/") || c === "/produtos" || c === "/pedidos" ||
  c.startsWith("/oauth/") || c.startsWith("/webhook/") || c === "/estoque/eventos" || c.startsWith("/v/") || c.startsWith("/l/") ||
  c === "/links" || c.startsWith("/links/") || ehServico(c);

/** Páginas públicas: vitrine e link curto (também no domínio curto, onde a primeira parte do caminho é a loja). */
export async function tratarPublico(req, env, url, caminho) {
  if (req.method !== "GET") return null;
  try {
    return await paginaPublica(req, env, url, caminho);
  } catch (e) {
    // quem abre o link nunca deve ver JSON cru: mostra uma página e deixa o motivo no log
    console.error("publico", caminho, e && e.stack ? e.stack : e);
    return html("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;background:#070a1c;color:#fff;text-align:center;padding:60px 20px'><h2>Não consegui abrir agora</h2><p style='color:#8b95c0'>Tente de novo em instantes.</p>", 500, "no-store");
  }
}

async function paginaPublica(req, env, url, caminho) {
  let m = /^\/l\/([a-z0-9-]{3,32})$/.exec(caminho);
  if (m) {
    const l = await env.DB.prepare("SELECT destino FROM links WHERE codigo = ?").bind(m[1]).first();
    if (!l) return html("<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;background:#070a1c;color:#fff;text-align:center;padding:60px'><h2>Link não encontrado</h2>", 404, "no-store");
    await env.DB.prepare("UPDATE links SET cliques = cliques + 1 WHERE codigo = ?").bind(m[1]).run();
    return new Response(null, { status: 302, headers: { location: l.destino, "cache-control": "no-store", "referrer-policy": "no-referrer" } });
  }
  const curto = env.DOMINIO_CURTO && url.hostname === env.DOMINIO_CURTO;
  m = curto ? /^\/([a-z0-9-]{3,32})(?:\/([^/]+))?$/.exec(caminho) : /^\/v\/([a-z0-9-]{3,32})(?:\/([^/]+))?$/.exec(caminho);
  if (!m || (curto && RESERVADAS.has(m[1]))) return null;
  const loja = await comPrivilegios(env, await env.DB.prepare("SELECT * FROM lojas WHERE slug = ?").bind(m[1]).first());
  const aviso = (t, s) => html(`<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><body style='font-family:sans-serif;background:#070a1c;color:#fff;text-align:center;padding:60px 20px'><h2>${t}</h2><p style='color:#8b95c0'>${s}</p>`, 404, "no-store");
  if (!loja) return aviso("Loja não encontrada", "Confira o endereço do link.");
  if (!acessoLiberado(loja)) return aviso("Loja temporariamente fora do ar", "O responsável por esta loja está renovando a assinatura. Volte em breve.");
  const { results } = await env.DB.prepare("SELECT * FROM produtos WHERE loja = ? ORDER BY nome LIMIT 500").bind(loja.id).all();
  const produtos = results.map((p) => ({ ...p, atributos: lerAtributos(p.atributos), oculto: !!p.oculto }));
  const base = endereco(env, req, loja.slug);
  if (m[2]) {
    const sku = decodeURIComponent(m[2]);
    const p = produtos.find((x) => x.sku === sku && !x.oculto && x.preco_centavos !== null);
    return p ? html(paginaDoProduto(loja, p, base)) : html("<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;background:#070a1c;color:#fff;text-align:center;padding:60px'><h2>Produto não encontrado</h2>", 404, "no-store");
  }
  return html(paginaDaVitrine(loja, produtos, base));
}

export async function tratarLoja(req, env, ctx, url, caminho) {
  try {
    const origem = url.origin;
    // ---------- públicas
    if (caminho === "/loja/registrar" && req.method === "POST") {
      const d = await corpoJson(req);
      if (env.LIMITADOR) {
        const ip = req.headers.get("cf-connecting-ip") || "x";
        if (!(await env.LIMITADOR.limit({ key: `reg:${ip}` })).success) throw new ErroLoja(429, "muitas_tentativas", "Muitas tentativas. Espere um minuto.");
      }
      return json(await registrarLoja(env, { nome: d.nome, slug: d.slug, dispositivo: d.dispositivo }), 201);
    }
    let m = /^\/webhook\/([a-z]+)\/([a-f0-9]{16})\/([a-f0-9]{32})$/.exec(caminho);
    if (m && req.method === "POST") {
      const canal = await env.DB.prepare("SELECT * FROM canais WHERE id = ? AND plataforma = ?").bind(m[2], m[1]).first();
      if (!canal || !iguais(m[3], await segredoDoCanal(env, canal.id))) return json({ error: "não encontrado" }, 404);
      const loja = await comPrivilegios(env, await env.DB.prepare("SELECT * FROM lojas WHERE id = ?").bind(canal.loja).first());
      if (!loja || !acessoLiberado(loja)) return json({ ok: true, ignorado: "acesso_expirado" });
      try {
        const r = await tratarWebhook(env, canal, req.headers, await lerCorpo(req, 500_000));
        return json({ ok: true, ...r });
      } catch (e) {
        if (e instanceof ErroCanal && e.codigo === "assinatura") return json({ error: "assinatura inválida" }, 401);
        console.error("webhook", m[1], e && e.stack ? e.stack : e);
        return json({ ok: false }, 500); // a plataforma tenta de novo
      }
    }
    m = /^\/oauth\/(nuvemshop|mercadolivre)\/retorno$/.exec(caminho);
    if (m && req.method === "GET") {
      try {
        const lojaId = await lerEstadoOAuth(env, url.searchParams.get("state"), m[1]);
        const loja = await env.DB.prepare("SELECT * FROM lojas WHERE id = ?").bind(lojaId).first();
        if (!loja) throw new ErroLoja(400, "estado_invalido", "Loja não encontrada.");
        const code = url.searchParams.get("code");
        if (!code) throw new ErroLoja(400, "sem_codigo", "A plataforma não enviou o código de acesso.");
        const r = await conector(m[1]).trocarCodigo(env, { code, redirect: `${origem}/oauth/${m[1]}/retorno` });
        const { canal } = await conectarCanal(env, loja, m[1], r, origem);
        ctx?.waitUntil?.(sincronizarProdutos(env, canal).catch(() => {}));
        return pagina("Conectado!", "Pode voltar para a Vênus. Seus produtos estão sendo importados.");
      } catch (e) {
        return pagina("Não deu certo", e.message || "Tente de novo pelo app.");
      }
    }

    if (caminho === "/painel" && req.method === "GET") {
      return new Response(PAINEL, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer", "x-content-type-options": "nosniff",
        "content-security-policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:" } });
    }
    m = /^\/assinatura\/webhook\/(infinitepay|mercadopago)$/.exec(caminho);
    if (m && req.method === "POST") {
      try {
        const corpo = JSON.parse((await lerCorpo(req, 100_000)) || "{}");
        const r = m[1] === "infinitepay" ? await webhookInfinitePay(env, corpo) : await webhookMercadoPago(env, req, corpo, url);
        return json({ ok: true, ...r });
      } catch (e) {
        if (e instanceof ErroLoja) return json({ error: e.message }, e.status);
        console.error("webhook assinatura", m[1], e && e.stack ? e.stack : e);
        return json({ ok: false }, 500);
      }
    }
    if (req.method === "GET") {
      const pub = await tratarPublico(req, env, url, caminho);
      if (pub) return pub;
    }

    // ---------- do lojista (token da loja)
    const loja = await lojaDoPedido(env, req);
    if (caminho === "/loja/eu" && req.method === "GET") {
      return json({ id: loja.id, nome: loja.nome, slug: loja.slug, plano: loja.plano, trialFim: loja.trial_fim, ativoAte: loja.ativo_ate, acesso: acessoLiberado(loja) });
    }
    if (ehServico(caminho) && caminho !== "/painel") {
      const r = await tratarServico(req, env, loja, url, caminho, { corpoJson, exigeAcesso, gerar: env.GERADOR_DE_TESTE || groq });
      if (r) return r;
    }
    if (caminho === "/loja/vitrine" && req.method === "PUT") {
      exigeAcesso(loja);
      const d = await corpoJson(req);
      const logo = d.logo ? urlHttps(d.logo) : "";
      if (d.logo && !logo) throw new ErroLoja(400, "logo_invalida", "O logo precisa ser um endereço https.");
      await env.DB.prepare("UPDATE lojas SET titulo = ?, descricao = ?, cor = ?, logo = ?, whatsapp = ? WHERE id = ?")
        .bind(String(d.titulo ?? loja.titulo).slice(0, 80), String(d.descricao ?? loja.descricao).slice(0, 300), corValida(d.cor ?? loja.cor), logo ?? loja.logo, soDigitos(d.whatsapp ?? loja.whatsapp).slice(0, 15), loja.id).run();
      return json({ ok: true, url: endereco(env, req, loja.slug) });
    }
    if (caminho === "/loja/vitrine" && req.method === "GET") {
      return json({ url: endereco(env, req, loja.slug), titulo: loja.titulo, descricao: loja.descricao, cor: loja.cor, logo: loja.logo, whatsapp: loja.whatsapp });
    }
    if (caminho === "/links" && req.method === "GET") {
      const { results } = await env.DB.prepare("SELECT codigo, destino, rotulo, cliques, criado FROM links WHERE loja = ? ORDER BY criado DESC LIMIT 200").bind(loja.id).all();
      const base = new URL(endereco(env, req, "x")).origin;
      return json({ links: results.map((l) => ({ ...l, curto: env.DOMINIO_CURTO ? `https://${env.DOMINIO_CURTO}/l/${l.codigo}` : `${base}/l/${l.codigo}` })) });
    }
    if (caminho === "/links" && req.method === "POST") {
      exigeAcesso(loja);
      const d = await corpoJson(req);
      let destino = d.destino ? urlHttps(d.destino) : null;
      if (!destino && d.sku) {
        const p = await env.DB.prepare("SELECT sku FROM produtos WHERE loja = ? AND sku = ?").bind(loja.id, String(d.sku)).first();
        if (!p) throw new ErroLoja(404, "produto_inexistente", "Produto não encontrado.");
        destino = endereco(env, req, loja.slug, p.sku);
      } else if (!destino && d.vitrine) destino = endereco(env, req, loja.slug);
      if (!destino) throw new ErroLoja(400, "destino_invalido", "Informe um endereço https, um sku de produto ou vitrine: true.");
      const total = await env.DB.prepare("SELECT COUNT(*) AS n FROM links WHERE loja = ?").bind(loja.id).first();
      if (total.n >= 200) throw new ErroLoja(409, "limite_de_links", "Limite de 200 links por loja.");
      const codigo = d.codigo ? validarCodigo(d.codigo) : `${aleatorio(3)}`;
      try {
        await env.DB.prepare("INSERT INTO links (codigo, loja, destino, rotulo, criado, cliques) VALUES (?, ?, ?, ?, ?, 0)").bind(codigo, loja.id, destino, String(d.rotulo || "").slice(0, 80), Date.now()).run();
      } catch (e) {
        throw new ErroLoja(409, "codigo_em_uso", "Esse código já está em uso. Escolha outro.");
      }
      const base = new URL(endereco(env, req, "x")).origin;
      return json({ codigo, destino, curto: env.DOMINIO_CURTO ? `https://${env.DOMINIO_CURTO}/l/${codigo}` : `${base}/l/${codigo}` }, 201);
    }
    m = /^\/links\/([a-z0-9-]{3,32})$/.exec(caminho);
    if (m && req.method === "DELETE") {
      await env.DB.prepare("DELETE FROM links WHERE codigo = ? AND loja = ?").bind(m[1], loja.id).run();
      return json({ ok: true });
    }
    if (caminho === "/canais" && req.method === "GET") return json({ canais: (await listarCanais(env, loja)).map(publico) });
    if (caminho === "/canais" && req.method === "POST") {
      exigeAcesso(loja);
      const d = await corpoJson(req);
      const con = conector(String(d.plataforma || ""));
      if (!con.conectarDireto) throw new ErroLoja(400, "use_oauth", `Conecte ${con.nome} pelo botão de autorizar.`);
      const r = await con.conectarDireto(env, d);
      const { canal, aviso } = await conectarCanal(env, loja, con.id, r, origem, String(d.nome || "").slice(0, 60));
      const sync = await sincronizarProdutos(env, canal).catch((e) => ({ erro: e.message }));
      return json({ canal: publico(canal), aviso, sincronizacao: sync }, 201);
    }
    m = /^\/canais\/oauth\/(nuvemshop|mercadolivre)\/iniciar$/.exec(caminho);
    if (m && req.method === "GET") {
      exigeAcesso(loja);
      if ((await listarCanais(env, loja)).length >= 3) throw new ErroLoja(409, "limite_de_canais", "Você já tem 3 canais conectados.");
      const state = await estadoOAuth(env, loja.id, m[1]);
      return json({ url: conector(m[1]).urlDeAutorizacao(env, state, `${origem}/oauth/${m[1]}/retorno`) });
    }
    m = /^\/canais\/([a-f0-9]{16})(\/sincronizar)?$/.exec(caminho);
    if (m) {
      const canal = await env.DB.prepare("SELECT * FROM canais WHERE id = ? AND loja = ?").bind(m[1], loja.id).first();
      if (!canal) throw new ErroLoja(404, "canal_inexistente", "Canal não encontrado.");
      if (m[2] && req.method === "POST") {
        exigeAcesso(loja);
        // primeiro os pedidos (baixas do que o webhook perdeu), depois os produtos (saldo de quem vende em um canal só)
        const pedidos = await sincronizarPedidos(env, canal);
        const produtos = await sincronizarProdutos(env, canal);
        return json({ produtos, pedidos });
      }
      if (!m[2] && req.method === "DELETE") {
        await removerCanal(env, loja, canal.id, origem);
        return json({ ok: true });
      }
    }
    if (caminho === "/produtos" && req.method === "GET") {
      const { results } = await env.DB.prepare("SELECT * FROM produtos WHERE loja = ? ORDER BY nome LIMIT 2000").bind(loja.id).all();
      return json({ produtos: results.map((p) => ({ ...p, atributos: lerAtributos(p.atributos), oculto: !!p.oculto })) });
    }
    if (caminho === "/pedidos" && req.method === "GET") {
      const { results } = await env.DB.prepare("SELECT id, canal, ref, status, total_centavos, taxa_centavos, criado FROM pedidos WHERE loja = ? ORDER BY criado DESC LIMIT 200").bind(loja.id).all();
      return json({ pedidos: results });
    }
    if (caminho === "/estoque/eventos" && req.method === "GET") {
      const { results } = await env.DB.prepare("SELECT tipo, produto, quantidade, canal, criado FROM eventos_estoque WHERE loja = ? ORDER BY criado DESC LIMIT 100").bind(loja.id).all();
      return json({ eventos: results });
    }
    return json({ error: "não encontrado" }, 404);
  } catch (e) {
    if (e instanceof ErroLoja || e instanceof ErroCanal) return json({ error: e.message, codigo: e.codigo, ...(e.dados || {}) }, e.status);
    console.error("loja", caminho, e && e.stack ? e.stack : e);
    return json({ error: "erro interno" }, 500);
  }
}
