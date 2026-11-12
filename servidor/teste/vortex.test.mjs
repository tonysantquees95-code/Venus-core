import test from "node:test";
import assert from "node:assert/strict";
import { bancoDeTeste } from "./d1.mjs";
import { cadastrar } from "../src/id/conta.js";
import { tratarVortex } from "../src/vortex/saves.js";

process.removeAllListeners("warning");
const T0 = Date.now();
const disp = { id: "aparelho-1-xxxxxxxx", nome: "Celular", plataforma: "android" };

function r2() {
  const m = new Map();
  return {
    async put(k, v, o) { m.set(k, { dados: new Uint8Array(v), meta: o?.customMetadata || {}, uploaded: new Date() }); },
    async get(k) { const o = m.get(k); return o ? { body: o.dados, customMetadata: o.meta } : null; },
    async head(k) { const o = m.get(k); return o ? { customMetadata: o.meta, uploaded: o.uploaded, size: o.dados.length } : null; },
    async delete(k) { m.delete(k); },
    async list({ prefix }) { return { truncated: false, objects: [...m].filter(([k]) => k.startsWith(prefix)).map(([key, o]) => ({ key, size: o.dados.length, uploaded: o.uploaded, customMetadata: o.meta })) }; },
  };
}
async function mundo() {
  const env = { DB: bancoDeTeste(), CHAVE_COFRE: "chave-de-teste-bem-longa-123", SAVES: r2() };
  const c = await cadastrar(env, { email: "jogador@exemplo.com", senha: "senha-forte-1", nome: "J", dispositivo: disp }, T0);
  return { env, token: c.token };
}
const chamar = (env, token, metodo, caminho, corpo, cab = {}) => {
  const q = new Request("https://v.test" + caminho, { method: metodo, headers: { authorization: `Bearer ${token}`, ...cab }, body: corpo });
  return tratarVortex(q, env, new URL(q.url), new URL(q.url).pathname, T0 + 1000);
};

test("conta em teste grátis tem o Vortex liberado; envia, lista e baixa um save", async () => {
  const { env, token } = await mundo();
  assert.equal((await (await chamar(env, token, "GET", "/vortex/licenca")).json()).vortex, true);
  const p = await chamar(env, token, "PUT", "/vortex/saves/zelda/jogo.srm", new Uint8Array([1, 2, 3]), { "x-base": "0" });
  assert.equal((await p.json()).versao, 1);
  const l = await (await chamar(env, token, "GET", "/vortex/saves")).json();
  assert.equal(l.saves.length, 1);
  assert.equal(l.saves[0].jogo, "zelda");
  const g = await chamar(env, token, "GET", "/vortex/saves/zelda/jogo.srm");
  assert.deepEqual([...new Uint8Array(await g.arrayBuffer())], [1, 2, 3]);
});

test("conflito: aparelho com versão velha recebe 409 e a nuvem não muda", async () => {
  const { env, token } = await mundo();
  await chamar(env, token, "PUT", "/vortex/saves/a/x.srm", new Uint8Array([1]), { "x-base": "0" });
  await chamar(env, token, "PUT", "/vortex/saves/a/x.srm", new Uint8Array([2]), { "x-base": "1" });
  const c = await chamar(env, token, "PUT", "/vortex/saves/a/x.srm", new Uint8Array([9]), { "x-base": "1" });
  assert.equal(c.status, 409);
  assert.equal((await c.json()).versao, 2);
});

test("nomes perigosos, tamanho e sem sessão são recusados", async () => {
  const { env, token } = await mundo();
  assert.equal((await chamar(env, token, "PUT", "/vortex/saves/a/..%2Fx", new Uint8Array([1]))).status, 400);
  assert.equal((await chamar(env, token, "PUT", "/vortex/saves/a/vazio.srm", new Uint8Array([]))).status, 413);
  assert.notEqual((await chamar(env, "token-falso", "GET", "/vortex/saves")).status, 200);
});

test("sem liberação do Vortex (teste vencido) devolve 402", async () => {
  const { env, token } = await mundo();
  await env.DB.prepare("UPDATE lojas SET trial_fim = 1, ativo_ate = 0, plano = 'fundadores'").run();
  const r = await chamar(env, token, "GET", "/vortex/saves");
  assert.equal(r.status, 402);
  assert.equal((await (await chamar(env, token, "GET", "/vortex/licenca")).json()).vortex, false);
});

test("saves de uma conta não aparecem para outra", async () => {
  const { env, token } = await mundo();
  const outra = await cadastrar(env, { email: "outro@exemplo.com", senha: "senha-forte-1", nome: "O", dispositivo: { id: "aparelho-2-xxxxxxxx", nome: "B", plataforma: "android" } }, T0);
  await chamar(env, token, "PUT", "/vortex/saves/a/x.srm", new Uint8Array([1]), { "x-base": "0" });
  assert.equal((await (await chamar(env, outra.token, "GET", "/vortex/saves")).json()).saves.length, 0);
});
