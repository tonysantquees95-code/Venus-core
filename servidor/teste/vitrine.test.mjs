import test from "node:test";
import assert from "node:assert/strict";
import { bancoDeTeste } from "./d1.mjs";
import { registrarLoja } from "../src/loja/lojas.js";
import { tratarLoja } from "../src/loja/rotas.js";
import { esc, validarCodigo, visiveis, corValida, paginaDaVitrine } from "../src/loja/vitrine.js";

process.removeAllListeners("warning");
const mk = () => ({ DB: bancoDeTeste(), CHAVE_COFRE: "chave-de-teste-bem-longa-123" });
const chamar = async (env, metodo, caminho, token, corpo, host = "https://venus.test") => {
  const q = new Request(host + caminho, { method: metodo, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: corpo ? JSON.stringify(corpo) : undefined });
  const u = new URL(q.url);
  return tratarLoja(q, env, {}, u, u.pathname);
};
async function lojaComProdutos(env) {
  const l = await registrarLoja(env, { nome: "Sensações" });
  const ins = (sku, nome, preco, est, oculto = 0, promo = null) =>
    env.DB.prepare("INSERT INTO produtos (loja, sku, nome, preco_centavos, estoque, oculto, atualizado, promocao_centavos, imagem) VALUES (?,?,?,?,?,?,?,?, 'https://img.exemplo.com/a.jpg')").bind(l.id, sku, nome, preco, est, oculto, Date.now(), promo).run();
  await ins("A1", "Vela <script>alert(1)</script>", 4990, 3, 0, 3990);
  await ins("A2", "Escondido", 1000, 3, 1);
  await ins("A3", "Sem preço", null, 3);
  await ins("A4", "Esgotado", 1000, 0);
  return l;
}

test("vitrine mostra só o que dá para vender e escapa HTML", async () => {
  const env = mk();
  const l = await lojaComProdutos(env);
  const r = await chamar(env, "GET", `/v/${l.slug}`);
  assert.equal(r.status, 200);
  const h = await r.text();
  assert.ok(h.includes("R$") && h.includes("39,90"));
  assert.ok(!h.includes("<script>alert"));
  assert.ok(!h.includes("Escondido") && !h.includes("Sem preço") && !h.includes("Esgotado"));
  assert.ok(h.includes("VETORIAL TECH"));
});

test("página do produto e produto escondido dá 404", async () => {
  const env = mk();
  const l = await lojaComProdutos(env);
  assert.equal((await chamar(env, "GET", `/v/${l.slug}/A1`)).status, 200);
  assert.equal((await chamar(env, "GET", `/v/${l.slug}/A2`)).status, 404);
  assert.equal((await chamar(env, "GET", `/v/nao-existe`)).status, 404);
});

test("domínio curto: vetorial.tech/sualoja e /l/codigo", async () => {
  const env = { ...mk(), DOMINIO_CURTO: "vetorial.tech" };
  const l = await lojaComProdutos(env);
  assert.equal((await chamar(env, "GET", `/${l.slug}`, null, null, "https://vetorial.tech")).status, 200);
  assert.equal((await chamar(env, "GET", `/${l.slug}/A1`, null, null, "https://vetorial.tech")).status, 200);
  const c = await chamar(env, "POST", "/links", l.token, { codigo: "promo", sku: "A1" }, "https://vetorial.tech");
  const dados = await c.json();
  assert.equal(c.status, 201);
  assert.equal(dados.curto, "https://vetorial.tech/l/promo");
  const ir = await chamar(env, "GET", "/l/promo", null, null, "https://vetorial.tech");
  assert.equal(ir.status, 302);
  assert.equal(ir.headers.get("location"), `https://vetorial.tech/${l.slug}/A1`);
  const lista = await (await chamar(env, "GET", "/links", l.token)).json();
  assert.equal(lista.links[0].cliques, 1);
});

test("links: código repetido, reservado e destino inseguro são recusados", async () => {
  const env = mk();
  const l = await lojaComProdutos(env);
  assert.equal((await chamar(env, "POST", "/links", l.token, { codigo: "meu-link", destino: "https://exemplo.com" })).status, 201);
  assert.equal((await chamar(env, "POST", "/links", l.token, { codigo: "meu-link", destino: "https://exemplo.com" })).status, 409);
  assert.equal((await chamar(env, "POST", "/links", l.token, { codigo: "admin", destino: "https://exemplo.com" })).status, 400);
  assert.equal((await chamar(env, "POST", "/links", l.token, { destino: "javascript:alert(1)" })).status, 400);
  assert.equal((await chamar(env, "POST", "/links", l.token, { destino: "http://exemplo.com" })).status, 400);
  const auto = await chamar(env, "POST", "/links", l.token, { vitrine: true });
  assert.equal(auto.status, 201);
  assert.match((await auto.json()).codigo, /^[a-f0-9]{6}$/);
});

test("personalizar a vitrine: cor, whatsapp e logo", async () => {
  const env = mk();
  const l = await lojaComProdutos(env);
  assert.equal((await chamar(env, "PUT", "/loja/vitrine", l.token, { titulo: "Minha", cor: "#36d6ff", whatsapp: "(51) 99999-0000", logo: "http://x.com/a.png" })).status, 400);
  assert.equal((await chamar(env, "PUT", "/loja/vitrine", l.token, { titulo: "Minha", cor: "#36d6ff", whatsapp: "(51) 99999-0000" })).status, 200);
  const h = await (await chamar(env, "GET", `/v/${l.slug}/A1`)).text();
  assert.ok(h.includes("#36D6FF"));
  assert.ok(h.includes("wa.me/5551999990000"));
});

test("pequenos validadores", () => {
  assert.equal(esc(`<a href="x">&'`), "&lt;a href=&quot;x&quot;&gt;&amp;&#39;");
  assert.equal(corValida("vermelho"), "#E36BD0");
  assert.equal(validarCodigo("Abc-1"), "abc-1");
  assert.throws(() => validarCodigo("a"));
  assert.equal(visiveis([{ oculto: false, preco_centavos: 1, estoque: 1 }, { oculto: false, preco_centavos: null, estoque: 1 }]).length, 1);
  assert.ok(paginaDaVitrine({ nome: "X", cor: "#fff" }, [], "https://a").includes("Em breve"));
});

test("vitrine com teste vencido mostra aviso claro; a do dono (admin) nunca sai do ar", async () => {
  const env = mk();
  const l = await lojaComProdutos(env);
  await env.DB.prepare("UPDATE lojas SET trial_fim = 1, ativo_ate = 0 WHERE id = ?").bind(l.id).run();
  const r = await chamar(env, "GET", `/v/${l.slug}`);
  assert.equal(r.status, 404);
  assert.match(await r.text(), /fora do ar/);
  const antes = await env.DB.prepare("SELECT COUNT(*) AS n FROM usuarios").first();
  assert.equal(antes.n, 0);
  await env.DB.prepare("INSERT INTO usuarios (id, email, nome, senha_hash, salt, loja, criado) VALUES ('u1', 'vetorialtech.dev@gmail.com', 'Dono', 'x', 'y', ?, 1)").bind(l.id).run();
  const ok = await chamar(env, "GET", `/v/${l.slug}`);
  assert.equal(ok.status, 200);
});

test("vitrine abre mesmo com atributos quebrados e nunca devolve JSON cru em erro", async () => {
  const env = mk();
  const l = await registrarLoja(env, { nome: "Quebrada" });
  await env.DB.prepare("INSERT INTO produtos (loja, sku, nome, preco_centavos, estoque, oculto, atualizado, atributos) VALUES (?,?,?,?,?,?,?,?)").bind(l.id, "Z1", "Produto", 1000, 2, 0, Date.now(), "{quebrado").run();
  const r = await chamar(env, "GET", `/v/${l.slug}`);
  assert.equal(r.status, 200);
  assert.ok((await r.text()).includes("Produto"));
  const p = await chamar(env, "GET", `/v/${l.slug}/Z1`);
  assert.equal(p.status, 200);
  const falha = await chamar({ ...env, DB: { prepare() { throw new Error("banco fora"); } } }, "GET", `/v/${l.slug}`);
  assert.equal(falha.status, 500);
  assert.match(falha.headers.get("content-type"), /text\/html/);
});
