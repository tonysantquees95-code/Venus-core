// Vetorial Drive: espaço de arquivos por conta (documentos e ROMs), no mesmo R2 dos saves do Vortex (binding SAVES, prefixo "drive/").
//   GET    /drive/arquivos[?pasta=roms/gba]       → { arquivos:[{pasta,nome,bytes,atualizado}], usado, cota, maxArquivo }
//   POST   /drive/enviar    {pasta,nome,tamanho}  → abre um envio em partes: { envio, parte }
//   PUT    /drive/parte?pasta=&nome=&envio=&n=    → corpo = bytes da parte n (1, 2, 3…): { n, etag }
//   POST   /drive/concluir  {pasta,nome,envio,partes:[{n,etag}]} → junta as partes: { ok, bytes }
//   POST   /drive/cancelar  {pasta,nome,envio}    → descarta um envio pela metade
//   GET    /drive/arquivo/<pasta>/<nome>          → baixa (aceita Range)
//   DELETE /drive/arquivo/<pasta>/<nome>
// Cota e tamanho máximo vêm do ambiente (DRIVE_COTA_BYTES, DRIVE_ARQUIVO_MAX); o padrão é baixo de propósito.
import { ErroId, validarSessao } from "../id/conta.js";
import { appsLiberados } from "../loja/assinatura.js";

export const ehRotaDrive = (c) => c.startsWith("/drive/");
export const COTA_PADRAO = 3 * 1024 ** 3;
export const ARQUIVO_MAX_PADRAO = 2 * 1024 ** 3;
export const PARTE = 32 * 1024 * 1024;
const PARTE_MAX = 64 * 1024 * 1024; // o Worker aceita até 100 MB por pedido
const CORS = { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization,content-type,range", "access-control-allow-methods": "GET,PUT,POST,DELETE,OPTIONS", "access-control-expose-headers": "content-range,accept-ranges,content-length" };
const json = (d, status = 200) => new Response(JSON.stringify(d), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...CORS } });

const PASTA = /^[a-z0-9][a-z0-9_-]{0,31}(\/[a-z0-9][a-z0-9_-]{0,31})?$/;
export function nomeValido(n) {
  const s = String(n ?? "");
  return s.length >= 1 && s.length <= 120 && !/[\/\\\u0000-\u001f]/.test(s) && !s.startsWith(".") && s.trim() === s;
}
export function pastaValida(p) { return PASTA.test(String(p ?? "")); }
const limites = (env) => ({
  cota: Number(env.DRIVE_COTA_BYTES) > 0 ? Number(env.DRIVE_COTA_BYTES) : COTA_PADRAO,
  maxArquivo: Number(env.DRIVE_ARQUIVO_MAX) > 0 ? Number(env.DRIVE_ARQUIVO_MAX) : ARQUIVO_MAX_PADRAO,
});

async function liberado(env, usuario, agora) {
  if (!usuario.loja) return false;
  const loja = await env.DB.prepare("SELECT * FROM lojas WHERE id = ?").bind(usuario.loja).first();
  const a = !!loja && appsLiberados(loja, agora);
  return !!a && !!(a.vortex || a.vindex);
}

async function listar(env, raiz) {
  const itens = [];
  let cursor;
  do {
    const l = await env.SAVES.list({ prefix: raiz, cursor });
    for (const o of l.objects) {
      const resto = o.key.slice(raiz.length);
      const i = resto.lastIndexOf("/");
      if (i < 0) continue;
      itens.push({ pasta: resto.slice(0, i), nome: resto.slice(i + 1), bytes: o.size, atualizado: o.uploaded ? new Date(o.uploaded).getTime() : 0 });
    }
    cursor = l.truncated ? l.cursor : undefined;
  } while (cursor);
  return itens;
}

/** "bytes=10-99", "bytes=10-" ou "bytes=-500" → { offset, length } dentro de um arquivo de `total` bytes (ou null se não valer). */
export function lerRange(cabecalho, total) {
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(cabecalho || "").trim());
  if (!m || (m[1] === "" && m[2] === "")) return null;
  let ini, fim;
  if (m[1] === "") { const n = Number(m[2]); if (!n) return null; ini = Math.max(0, total - n); fim = total - 1; }
  else { ini = Number(m[1]); fim = m[2] === "" ? total - 1 : Math.min(Number(m[2]), total - 1); }
  if (ini >= total || fim < ini) return "invalido";
  return { offset: ini, length: fim - ini + 1 };
}

export async function tratarDrive(req, env, url, caminho, agora = Date.now()) {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  try {
    const ctx = await validarSessao(env, req, { agora });
    if (!(await liberado(env, ctx.usuario, agora))) throw new ErroId(402, "drive_nao_liberado", "O Vetorial Drive vem com o Vortex e o Vindex, e nenhum deles está liberado para esta conta.");
    if (!env.SAVES) throw new ErroId(503, "nuvem_indisponivel", "O armazenamento na nuvem ainda não está ligado no servidor.");
    const raiz = `drive/${ctx.usuario.id}/`;
    const { cota, maxArquivo } = limites(env);

    if (caminho === "/drive/arquivos" && req.method === "GET") {
      const pasta = url.searchParams.get("pasta");
      const todos = await listar(env, raiz);
      const usado = todos.reduce((a, i) => a + i.bytes, 0);
      const arquivos = pasta ? todos.filter((i) => i.pasta === pasta || i.pasta.startsWith(pasta + "/")) : todos;
      arquivos.sort((a, b) => a.pasta.localeCompare(b.pasta) || a.nome.localeCompare(b.nome));
      return json({ arquivos, usado, cota, maxArquivo });
    }

    if (caminho === "/drive/enviar" && req.method === "POST") {
      const d = await req.json().catch(() => ({}));
      const tamanho = Number(d.tamanho);
      if (!pastaValida(d.pasta)) throw new ErroId(400, "pasta_invalida", "Pasta inválida. Use letras minúsculas, números, - ou _ (até 2 níveis).");
      if (!nomeValido(d.nome)) throw new ErroId(400, "nome_invalido", "Nome de arquivo inválido.");
      if (!Number.isFinite(tamanho) || tamanho < 1) throw new ErroId(400, "tamanho_invalido", "Tamanho do arquivo inválido.");
      if (tamanho > maxArquivo) throw new ErroId(413, "arquivo_grande", `O limite por arquivo é ${Math.round(maxArquivo / 1024 ** 3)} GB.`);
      const chave = `${raiz}${d.pasta}/${d.nome}`;
      const todos = await listar(env, raiz);
      const mesmo = todos.find((i) => i.pasta === d.pasta && i.nome === d.nome);
      const usado = todos.reduce((a, i) => a + i.bytes, 0) - (mesmo ? mesmo.bytes : 0);
      if (usado + tamanho > cota) throw new ErroId(413, "sem_espaco", `O espaço do Vetorial Drive (${(cota / 1024 ** 3).toFixed(0)} GB) não comporta este arquivo.`, { usado, cota });
      const up = await env.SAVES.createMultipartUpload(chave, { customMetadata: { tamanho: String(tamanho) } });
      return json({ envio: up.uploadId, parte: PARTE }, 201);
    }

    if (caminho === "/drive/parte" && req.method === "PUT") {
      const pasta = url.searchParams.get("pasta"), nome = url.searchParams.get("nome"), envio = url.searchParams.get("envio");
      const n = Number(url.searchParams.get("n"));
      if (!pastaValida(pasta) || !nomeValido(nome) || !envio || !Number.isInteger(n) || n < 1 || n > 10000) throw new ErroId(400, "pedido_invalido", "Parte inválida.");
      const dados = await req.arrayBuffer();
      if (dados.byteLength < 1 || dados.byteLength > PARTE_MAX) throw new ErroId(413, "parte_invalida", "Cada parte precisa ter entre 1 byte e 64 MB.");
      const up = env.SAVES.resumeMultipartUpload(`${raiz}${pasta}/${nome}`, envio);
      const p = await up.uploadPart(n, dados);
      return json({ n: p.partNumber, etag: p.etag });
    }

    if (caminho === "/drive/concluir" && req.method === "POST") {
      const d = await req.json().catch(() => ({}));
      if (!pastaValida(d.pasta) || !nomeValido(d.nome) || !d.envio || !Array.isArray(d.partes) || d.partes.length < 1) throw new ErroId(400, "pedido_invalido", "Pedido de conclusão inválido.");
      const chave = `${raiz}${d.pasta}/${d.nome}`;
      const up = env.SAVES.resumeMultipartUpload(chave, String(d.envio));
      const obj = await up.complete(d.partes.map((p) => ({ partNumber: Number(p.n), etag: String(p.etag) })));
      // confere o que realmente ficou guardado: tamanho máximo e cota (outros envios podem ter acontecido no meio)
      const todos = await listar(env, raiz);
      const usado = todos.reduce((a, i) => a + i.bytes, 0);
      if (obj.size > maxArquivo || usado > cota) {
        await env.SAVES.delete(chave);
        throw new ErroId(413, "sem_espaco", "O arquivo passou do limite do Vetorial Drive e foi descartado.");
      }
      return json({ ok: true, bytes: obj.size });
    }

    if (caminho === "/drive/cancelar" && req.method === "POST") {
      const d = await req.json().catch(() => ({}));
      if (pastaValida(d.pasta) && nomeValido(d.nome) && d.envio) {
        try { await env.SAVES.resumeMultipartUpload(`${raiz}${d.pasta}/${d.nome}`, String(d.envio)).abort(); } catch { /* já não existe */ }
      }
      return json({ ok: true });
    }

    const m = /^\/drive\/arquivo\/(.+)$/.exec(caminho);
    if (m) {
      let rel;
      try { rel = decodeURIComponent(m[1]); } catch { throw new ErroId(400, "nome_invalido", "Endereço inválido."); }
      const i = rel.lastIndexOf("/");
      const pasta = rel.slice(0, i), nome = rel.slice(i + 1);
      if (i < 0 || !pastaValida(pasta) || !nomeValido(nome)) throw new ErroId(400, "nome_invalido", "Pasta ou arquivo inválido.");
      const chave = `${raiz}${pasta}/${nome}`;
      if (req.method === "GET") {
        const cab = await env.SAVES.head(chave);
        if (!cab) throw new ErroId(404, "sem_arquivo", "Arquivo não encontrado no Vetorial Drive.");
        const total = cab.size;
        const r = lerRange(req.headers.get("range"), total);
        const base = { "content-type": "application/octet-stream", "cache-control": "no-store", "accept-ranges": "bytes", ...CORS };
        if (r === "invalido") return new Response(null, { status: 416, headers: { ...base, "content-range": `bytes */${total}` } });
        if (r) {
          const o = await env.SAVES.get(chave, { range: { offset: r.offset, length: r.length } });
          return new Response(o.body, { status: 206, headers: { ...base, "content-range": `bytes ${r.offset}-${r.offset + r.length - 1}/${total}`, "content-length": String(r.length) } });
        }
        const o = await env.SAVES.get(chave);
        return new Response(o.body, { headers: { ...base, "content-length": String(total) } });
      }
      if (req.method === "DELETE") {
        await env.SAVES.delete(chave);
        return json({ ok: true });
      }
    }
    return json({ error: "não encontrado" }, 404);
  } catch (e) {
    if (e && e.status && e.codigo) return json({ error: e.message, codigo: e.codigo, ...(e.dados || {}) }, e.status);
    console.error("drive", caminho, e && e.stack ? e.stack : e);
    return json({ error: "erro interno" }, 500);
  }
}
