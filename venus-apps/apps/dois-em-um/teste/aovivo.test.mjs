import test from "node:test";
import assert from "node:assert/strict";
import { moedaDaPergunta, montarCotacao, pedidoDeClima, montarPrevisao, precisaPesquisar, responderAoVivo } from "../src/ia/aoVivo.ts";

test("reconhece pergunta de dólar e de clima", () => {
  assert.equal(moedaDaPergunta("qual o valor do dólar hoje?")?.codigo, "USD");
  assert.equal(moedaDaPergunta("quanto está o euro").codigo, "EUR");
  assert.equal(moedaDaPergunta("oi tudo bem"), null);
  assert.deepEqual(pedidoDeClima("vai chover amanhã em Porto Alegre?"), { dia: "amanha", cidade: "Porto Alegre" });
  assert.equal(pedidoDeClima("previsão do tempo hoje").cidade, "");
  assert.equal(pedidoDeClima("quero cadastrar um produto"), null);
  assert.ok(precisaPesquisar("quais as últimas notícias?"));
});

test("monta cotação e previsão", () => {
  assert.match(montarCotacao({ USDBRL: { bid: "5.43", pctChange: "-0.8" } }, "USD", "dólar"), /dólar está em R\$\s?5,43, queda de 0,80% hoje/);
  const j = { daily: { time: ["2026-11-16", "2026-11-17"], temperature_2m_max: [28, 25], temperature_2m_min: [18, 17], precipitation_probability_max: [10, 80], weather_code: [1, 63] } };
  assert.match(montarPrevisao(j, "Porto Alegre, Rio Grande do Sul", "amanha"), /amanhã: chuva, máxima de 25 e mínima de 17 graus, com 80%/);
});

test("responde com dados ao vivo e cai na pesquisa se a API falha", async () => {
  const f = async (u) => u.includes("awesomeapi") ? new Response(JSON.stringify({ USDBRL: { bid: "5.5", pctChange: "0" } })) : new Response("{}", { status: 500 });
  const r = await responderAoVivo("valor do dólar", { urlApi: "x", historico: [] }, f);
  assert.match(r.texto, /estável hoje/);
  const g = async (u) => u.includes("awesomeapi") ? new Response("x", { status: 500 }) : new Response(JSON.stringify({ reply: "Dólar a 5,50", sources: [{ url: "https://a.b" }] }));
  assert.equal((await responderAoVivo("valor do dólar", { urlApi: "x", historico: [{ papel: "user", texto: "valor do dólar" }] }, g)).texto, "Dólar a 5,50");
  assert.equal(await responderAoVivo("bom dia", { urlApi: "x", historico: [] }, g), undefined);
});
