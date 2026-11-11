import test from "node:test";
import assert from "node:assert/strict";
import {
  abrirConversa, agrupar, anexarMensagem, apagarTudo, CHAVE_CONVERSAS, CHAVE_LEGADO, conversaAtiva, estadoInicial,
  excluirConversa, iniciarNova, paraSalvar, salvar, tituloDe
} from "../src/sessao/logica.ts";

const armazem = (ini = {}) => {
  const m = new Map(Object.entries(ini));
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, String(v)), removeItem: (k) => void m.delete(k), m };
};
const msg = (autor, texto, quando = Date.now()) => ({ id: crypto.randomUUID(), autor, texto, quando });

/** Simula "usar o app": abre, conversa, fecha (grava no armazenamento). */
function sessao(arm, textos) {
  let e = estadoInicial(arm);
  const id = e.ativaId;
  for (const t of textos) e = anexarMensagem(e, id, msg("eu", t));
  salvar(arm, e);
  return { e, id };
}

test("toda abertura começa numa conversa nova e vazia, nunca na anterior", () => {
  const arm = armazem();
  const s1 = sessao(arm, ["primeira pergunta"]);
  for (let i = 0; i < 3; i++) {
    const e = estadoInicial(arm); // "reiniciar o app"
    assert.equal(conversaAtiva(e).mensagens.length, 0);
    assert.notEqual(e.ativaId, s1.id, "não reabre a conversa anterior");
    assert.equal(e.lista.filter((c) => c.mensagens.length > 0).length, 1, "histórico preservado");
  }
});

test("o id da conversa ativa nunca é gravado; conversa vazia não vira histórico", () => {
  const arm = armazem();
  const e = estadoInicial(arm);
  salvar(arm, e);
  assert.deepEqual(JSON.parse(arm.m.get(CHAVE_CONVERSAS)), []);
  assert.ok(![...arm.m.keys()].some((k) => /ativ/i.test(k)));
});

test("Nova conversa: isola a atual, não acumula rascunhos vazios", () => {
  const arm = armazem();
  let { e, id } = sessao(arm, ["a"]);
  e = iniciarNova(e);
  assert.notEqual(e.ativaId, id);
  assert.equal(conversaAtiva(e).mensagens.length, 0);
  const n = e.lista.length;
  e = iniciarNova(e); // já está limpa
  assert.equal(e.lista.length, n);
  assert.equal(e.lista.find((c) => c.id === id).mensagens.length, 1, "a anterior segue intacta no histórico");
});

test("abrir do histórico continua aquela conversa; só nela as mensagens novas entram", () => {
  const arm = armazem();
  const { id: antiga } = sessao(arm, ["conversa antiga"]);
  let e = estadoInicial(arm);
  const rascunho = e.ativaId;
  e = abrirConversa(e, antiga);
  assert.equal(e.ativaId, antiga);
  assert.ok(!e.lista.some((c) => c.id === rascunho), "rascunho vazio descartado");
  e = anexarMensagem(e, antiga, msg("venus", "resposta"));
  assert.equal(conversaAtiva(e).mensagens.length, 2);
});

test("resposta atrasada volta para a conversa que a pediu", () => {
  const arm = armazem();
  let e = estadoInicial(arm);
  const pedida = e.ativaId;
  e = anexarMensagem(e, pedida, msg("eu", "pergunta longa"));
  e = iniciarNova(e); // usuário troca de conversa enquanto espera
  e = anexarMensagem(e, pedida, msg("venus", "resposta chegou"));
  assert.equal(conversaAtiva(e).mensagens.length, 0, "a conversa ativa continua limpa");
  assert.equal(e.lista.find((c) => c.id === pedida).mensagens.length, 2);
});

test("excluir: apaga uma; excluir a ativa abre nova limpa; apagar tudo zera", () => {
  const arm = armazem();
  let { e, id } = sessao(arm, ["x"]);
  e = abrirConversa(e, id);
  e = excluirConversa(e, id);
  assert.ok(!e.lista.some((c) => c.id === id));
  assert.equal(conversaAtiva(e).mensagens.length, 0);
  const t = apagarTudo();
  assert.equal(t.lista.length, 1);
  assert.equal(paraSalvar(t).length, 0);
});

test("título vem da primeira pergunta; limites de armazenamento", () => {
  assert.equal(tituloDe("  oi   tudo  bem?  "), "oi tudo bem?");
  assert.ok(tituloDe("a".repeat(100)).length <= 41);
  let e = estadoInicial(null);
  e = anexarMensagem(e, e.ativaId, msg("eu", "Qual o estoque de velas?"));
  assert.equal(conversaAtiva(e).titulo, "Qual o estoque de velas?");
  for (let i = 0; i < 250; i++) e = anexarMensagem(e, e.ativaId, msg("venus", "m" + i));
  assert.equal(paraSalvar(e)[0].mensagens.length, 200);
});

test("migra o formato antigo para o histórico (sem reabri-lo) e é idempotente", () => {
  const antigo = [msg("venus", "oi", 0), { id: "boas-vindas", autor: "venus", texto: "Oi!", quando: 0 }, msg("eu", "pergunta velha", 1000), msg("venus", "resp", 2000)];
  const arm = armazem({ [CHAVE_LEGADO]: JSON.stringify(antigo) });
  const a = estadoInicial(arm), b = estadoInicial(arm); // StrictMode chama duas vezes
  for (const e of [a, b]) {
    assert.equal(conversaAtiva(e).mensagens.length, 0);
    const velha = e.lista.filter((c) => c.mensagens.length > 0);
    assert.equal(velha.length, 1);
    assert.equal(velha[0].titulo, "pergunta velha");
  }
  salvar(arm, a);
  assert.equal(arm.getItem(CHAVE_LEGADO), null);
  assert.equal(JSON.parse(arm.getItem(CHAVE_CONVERSAS)).length, 1);
});

test("armazenamento corrompido ou indisponível não quebra a abertura", () => {
  assert.equal(conversaAtiva(estadoInicial(armazem({ [CHAVE_CONVERSAS]: "{lixo" }))).mensagens.length, 0);
  assert.equal(conversaAtiva(estadoInicial(armazem({ [CHAVE_CONVERSAS]: JSON.stringify([{ id: 1 }, null]) }))).mensagens.length, 0);
  assert.equal(conversaAtiva(estadoInicial(null)).mensagens.length, 0);
  salvar({ getItem: () => null, setItem: () => { throw new Error("cheio"); }, removeItem() {} }, estadoInicial(null));
});

test("agrupa por Hoje / Ontem / 7 dias / antigas", () => {
  const agora = new Date(2026, 10, 11, 12).getTime();
  const DIA = 86_400_000;
  const c = (t, d) => ({ id: t, titulo: t, criada: 0, atualizada: agora - d * DIA, mensagens: [msg("eu", t)] });
  const g = agrupar([c("antiga", 30), c("hoje", 0), c("ontem", 1), c("semana", 4)], agora);
  assert.deepEqual(g.map((x) => x.rotulo), ["Hoje", "Ontem", "Últimos 7 dias", "Mais antigas"]);
});
