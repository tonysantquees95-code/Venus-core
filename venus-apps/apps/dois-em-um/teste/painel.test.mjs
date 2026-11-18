import test from "node:test";
import assert from "node:assert/strict";
import { chaveDoDia, deveMostrar, dica, DICAS, montarPainel, saudacao } from "../src/ajustes/painelDoDia.ts";
import { limparPreferencias, PREFERENCIAS_PADRAO, CATALOGO } from "../src/ajustes/logica.ts";

const d = (a, m, dia, h) => new Date(a, m - 1, dia, h, 0, 0);

test("painel do dia: uma vez por dia, respeitando o interruptor e o horário", () => {
  const manha = d(2026, 11, 18, 9);
  assert.equal(deveMostrar(true, null, manha), true);
  assert.equal(deveMostrar(true, "2026-11-17", manha), true);
  assert.equal(deveMostrar(true, chaveDoDia(manha), manha), false);
  assert.equal(deveMostrar(false, null, manha), false);
  assert.equal(deveMostrar(true, null, d(2026, 11, 18, 3)), false);
  assert.equal(deveMostrar(true, null, d(2026, 11, 18, 23)), false);
});

test("painel do dia: saudação, texto e dica", () => {
  assert.equal(saudacao(d(2026, 11, 18, 8)), "Bom dia");
  assert.equal(saudacao(d(2026, 11, 18, 15)), "Boa tarde");
  assert.equal(saudacao(d(2026, 11, 18, 20)), "Boa noite");
  const sem = montarPainel(null, [], false, d(2026, 11, 18, 9));
  assert.match(sem, /^Bom dia! Seu painel de hoje:/);
  assert.match(sem, /Conecte a sua loja/);
  const com = montarPainel("Toni", ["A", "B", "C", "D"], true, d(2026, 11, 18, 9));
  assert.match(com, /4 produtos esgotados: A, B, C e mais 1\./);
  assert.match(montarPainel(null, [], true, d(2026, 11, 18, 9)), /nenhum produto esgotado/);
  assert.notEqual(dica(d(2026, 11, 18, 9)), dica(d(2026, 11, 19, 9)));
  assert.ok(DICAS.length >= 5);
});

test("painel do dia: vem ligado e aparece na busca de ajustes", () => {
  assert.equal(PREFERENCIAS_PADRAO.painelDoDia, true);
  assert.equal(limparPreferencias({ painelDoDia: false }).painelDoDia, false);
  assert.ok(CATALOGO.some((i) => i.id === "painelDoDia"));
});
