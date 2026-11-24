import test from "node:test";
import assert from "node:assert/strict";
import { chaveDoDia, corpo, deveMostrar, diasUsados, faseDaLicenca, motivos, perguntaDoMotivo } from "../src/apresentacao/pesquisaAssinatura.ts";

const dia = (d, h = 10) => new Date(2026, 10, d, h, 0, 0);

test("pesquisa de assinatura: só do 2º dia do teste em diante, ou depois que venceu", () => {
  assert.equal(deveMostrar("teste", 1, null, 0, dia(24)), false);
  assert.equal(deveMostrar("teste", 2, null, 0, dia(24)), true);
  assert.equal(deveMostrar("pago", -1, null, 0, dia(24)), false);
  assert.equal(deveMostrar("", -1, null, 0, dia(24)), false);
  assert.equal(deveMostrar("vencido", -1, null, 0, dia(24)), true);
});

test("pesquisa de assinatura: fechou sem responder volta amanhã; respondeu espera 7 dias; respeita o horário", () => {
  assert.equal(deveMostrar("teste", 3, chaveDoDia(dia(24)), 0, dia(24)), false);
  assert.equal(deveMostrar("teste", 3, chaveDoDia(dia(24)), 0, dia(25)), true);
  const resp = dia(24).getTime();
  assert.equal(deveMostrar("vencido", -1, null, resp, dia(30)), false);
  assert.equal(deveMostrar("vencido", -1, null, resp, new Date(2026, 11, 1, 10)), true);
  assert.equal(deveMostrar("teste", 3, null, 0, dia(24, 6)), false);
  assert.equal(deveMostrar("teste", 3, null, 0, dia(24, 22)), false);
});

test("pesquisa de assinatura: lê a licença do servidor", () => {
  assert.equal(faseDaLicenca({ acesso: true, emTeste: true, diasDeTesteRestantes: 5 }), "teste");
  assert.equal(diasUsados({ acesso: true, emTeste: true, diasDeTesteRestantes: 5 }), 2);
  assert.equal(faseDaLicenca({ acesso: true, emTeste: false }), "pago");
  assert.equal(faseDaLicenca({ acesso: false }), "vencido");
  assert.equal(faseDaLicenca(null), "");
  assert.equal(diasUsados({ acesso: true, emTeste: false }), -1);
});

test("pesquisa de assinatura: perguntas, motivos e corpo no formato do servidor", () => {
  assert.match(perguntaDoMotivo("vai"), /trouxe/);
  assert.match(perguntaDoMotivo("nao"), /Por que/);
  assert.equal(motivos("vai").length, 0);
  assert.equal(motivos("nao")[0].id, "preco");
  assert.equal(motivos("talvez")[0].id, "testar_mais");
  const c = corpo("nao", "preco", "  caro  ", "teste", 3, "web", "1.2");
  assert.equal(c.tipo, "assinatura");
  assert.equal(c.texto, "caro");
  assert.equal(c.respostas.dias_de_uso, 3);
  assert.equal("dias_de_uso" in corpo("vai", "", "", "vencido", -1, "web", "").respostas, false);
});
