import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const { criarLoja, origemDoSite } = createRequire(import.meta.url)("../painel-comercio.js");

function mundo(respostas) {
  const chamadas = [];
  let guardado = null;
  const fetchFn = async (url, init = {}) => {
    chamadas.push({ url, ...init });
    const f = respostas.find((r) => url.includes(r.em) && (!r.metodo || r.metodo === (init.method || "GET")));
    if (!f) throw new Error("sem resposta para " + url);
    const corpo = typeof f.corpo === "string" ? f.corpo : JSON.stringify(f.corpo ?? {});
    const cab = new Map(Object.entries(f.cab || {}));
    return { status: f.status ?? 200, ok: (f.status ?? 200) < 300, text: async () => corpo, headers: { get: (k) => cab.get(k.toLowerCase()) ?? null, getSetCookie: () => f.cookies || [] } };
  };
  const loja = criarLoja({ fetchFn, ler: () => guardado, gravar: (v) => { guardado = v; }, cifrar: (t) => "x:" + Buffer.from(t).toString("base64"), decifrar: (s) => Buffer.from(s.slice(2), "base64").toString() });
  return { loja, chamadas, guardado: () => guardado };
}
const LOGIN = { em: "/_api/auth/login_with_password", metodo: "POST", cookies: ["app_session=abc123; Path=/; HttpOnly"], corpo: { json: { user: { role: "admin" } } } };

test("origem do site: só https, só a origem", () => {
  assert.equal(origemDoSite("minhaloja.com.br/loja?x=1"), "https://minhaloja.com.br");
  assert.throws(() => origemDoSite("http://minhaloja.com.br"));
});
test("conectar exige administrador e guarda a senha cifrada", async () => {
  const m = mundo([LOGIN]);
  const e = await m.loja.conectar("https://loja.test", "a@b.com", "segredo!");
  assert.deepEqual(e, { conectada: true, email: "a@b.com", site: "https://loja.test" });
  assert.ok(!m.guardado().includes("segredo!"));
  const cliente = mundo([{ ...LOGIN, corpo: { json: { user: { role: "user" } } } }]);
  await assert.rejects(cliente.loja.conectar("https://loja.test", "a@b.com", "x"), /administrador/);
  assert.equal(cliente.loja.estado().conectada, false);
});
test("lê produtos com o cookie e entra de novo quando a sessão vence", async () => {
  let vez = 0;
  const m = mundo([LOGIN, { em: "/_api/admin/products", get status() { return ++vez === 1 ? 401 : 200; }, corpo: { json: { products: [{ id: 1, name: "Gel" }] } } }]);
  await m.loja.conectar("https://loja.test", "a@b.com", "s");
  const lista = await m.loja.produtos();
  assert.equal(lista[0].name, "Gel");
  assert.equal(m.chamadas.filter((c) => c.url.includes("login_with_password")).length, 2);
  assert.equal(m.chamadas.at(-1).headers.cookie, "app_session=abc123");
});
test("só endereços permitidos e salvar manda o corpo em { json }", async () => {
  const m = mundo([LOGIN, { em: "/_api/admin/product_save", metodo: "POST", corpo: { json: { ok: true } } }, { em: "/_api/admin/stock_update", metodo: "POST", corpo: {} }]);
  await m.loja.conectar("https://loja.test", "a@b.com", "s");
  await m.loja.salvarProduto({ name: "X" });
  assert.deepEqual(JSON.parse(m.chamadas.at(-1).body), { json: { name: "X" } });
  await m.loja.definirEstoque(3, 7);
  assert.deepEqual(JSON.parse(m.chamadas.at(-1).body).json, { productId: 3, quantity: 7, mode: "set" });
});
test("foto: pede o endereço assinado e envia os bytes", async () => {
  const m = mundo([LOGIN, { em: "/_api/admin/upload", metodo: "POST", corpo: { json: { presignedUrl: "https://nuvem.test/up?a=1", url: "https://cdn.test/f.jpg", headers: { "x-extra": "1" } } } }, { em: "nuvem.test", metodo: "PUT", corpo: "" }]);
  await m.loja.conectar("https://loja.test", "a@b.com", "s");
  const url = await m.loja.enviarFoto(Buffer.from("jpegfake").toString("base64"));
  assert.equal(url, "https://cdn.test/f.jpg");
  const put = m.chamadas.at(-1);
  assert.equal(put.method, "PUT");
  assert.equal(put.headers["x-extra"], "1");
});
