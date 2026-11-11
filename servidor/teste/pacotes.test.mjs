import test from "node:test";
import assert from "node:assert/strict";
import { bancoDeTeste } from "./d1.mjs";
import { registrarLoja } from "../src/loja/lojas.js";
import { PACOTES, appsLiberados, estadoDaAssinatura, iniciarCobranca, confirmarPagamento, pacoteDe } from "../src/loja/assinatura.js";

process.removeAllListeners("warning");
const DIA = 86_400_000;
const mk = () => ({ DB: bancoDeTeste(), CHAVE_COFRE: "chave-de-teste-bem-longa-123", MERCADOPAGO_ACCESS_TOKEN: "TEST-x" });

test("preços dos pacotes e pacote desconhecido vira Vênus", () => {
  assert.equal(PACOTES.venus.centavos, 4990);
  assert.equal(PACOTES.venus_vindex.centavos, 7000);
  assert.equal(PACOTES.venus_vortex.centavos, 7000);
  assert.equal(PACOTES.completo.centavos, 10000);
  assert.equal(pacoteDe("inventado"), "venus");
  assert.equal(pacoteDe("__proto__"), "venus");
});

test("teste grátis libera Vindex e Vortex; depois vale só o pacote pago", () => {
  const agora = 1_800_000_000_000;
  assert.deepEqual(appsLiberados({ trial_fim: agora + DIA, ativo_ate: 0 }, agora), { vindex: true, vortex: true });
  assert.deepEqual(appsLiberados({ trial_fim: agora - DIA, ativo_ate: 0 }, agora), { vindex: false, vortex: false });
  assert.deepEqual(appsLiberados({ trial_fim: 0, ativo_ate: agora + DIA, pacote: "venus" }, agora), { vindex: false, vortex: false });
  assert.deepEqual(appsLiberados({ trial_fim: 0, ativo_ate: agora + DIA, pacote: "venus_vindex" }, agora), { vindex: true, vortex: false });
  assert.deepEqual(appsLiberados({ trial_fim: 0, ativo_ate: agora + DIA, pacote: "completo" }, agora), { vindex: true, vortex: true });
  assert.deepEqual(appsLiberados({ plano: "dono", trial_fim: 0, ativo_ate: 0 }, agora), { vindex: true, vortex: true });
});

test("cobrança recorrente usa o valor do pacote e guarda o pacote no pagamento", async () => {
  const env = mk();
  const l = await registrarLoja(env, { nome: "Loja" });
  const antes = globalThis.fetch;
  const corpos = [];
  globalThis.fetch = async (url, op) => { corpos.push(JSON.parse(op.body)); return new Response(JSON.stringify({ init_point: "https://mp.exemplo/assinar" }), { status: 201 }); };
  try {
    const loja = await env.DB.prepare("SELECT * FROM lojas WHERE id = ?").bind(l.id).first();
    const c = await iniciarCobranca(env, loja, "mercadopago", "https://v.test", Date.now(), "completo");
    assert.equal(corpos[0].auto_recurring.transaction_amount, 100);
    assert.equal(c.valorCentavos, 10000);
    assert.equal(c.pacote, "completo");
    const p = await env.DB.prepare("SELECT pacote, valor_centavos FROM pagamentos WHERE id = ?").bind(c.id).first();
    assert.equal(p.pacote, "completo");
    // pagamento menor que o do pacote não vale
    assert.equal(await confirmarPagamento(env, c.id, 4990, "x1"), false);
    const padrao = await iniciarCobranca(env, loja, "mercadopago", "https://v.test");
    assert.equal(padrao.pacote, "venus");
    assert.equal(corpos[1].auto_recurring.transaction_amount, 49.9);
  } finally { globalThis.fetch = antes; }
  const e = await estadoDaAssinatura(env, await env.DB.prepare("SELECT * FROM lojas WHERE id = ?").bind(l.id).first());
  assert.equal(e.pacotes.length, 4);
  assert.deepEqual(e.apps, { vindex: true, vortex: true });
});
