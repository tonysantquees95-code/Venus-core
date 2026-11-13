import test from "node:test";
import assert from "node:assert/strict";
import { buscarConversas, extrairJson, inspiracao, lerIdeiasDePost, lerIdeiasDeVideo, lerStories, limparResposta, MODELOS_DE_RESPOSTA, pedidoDeIdeiasDePost, pedidoDeResposta, primeirosPassos, resumirConversa } from "../src/empresa/marketing.ts";
import { EMPRESA_VAZIA } from "../src/empresa/logica.ts";
import { hsvParaRgb, paraSecundaria, rgbParaHsv, trio } from "../src/theming/cor.ts";

const loja = { ...EMPRESA_VAZIA, loja: "Desejo", ramo: "sex shop", dono: "Toni", cidade: "POA", sobre: "Loja adulta discreta e elegante." };

test("a IA pode cercar o JSON de texto e de crases", () => {
  assert.deepEqual(extrairJson('claro!\n```json\n{"a":1}\n```'), { a: 1 });
  assert.equal(extrairJson("sem json"), null);
  assert.equal(extrairJson("{quebrado"), null);
});

test("ideias de post: lê as válidas, descarta as sem legenda e corrige formato e data", () => {
  const r = lerIdeiasDePost('{"ideias":[{"titulo":"Dia dos Namorados","formato":"story","legenda":"Surpreenda!","data":"2026-06-12"},{"titulo":"sem legenda"},{"titulo":"X","legenda":"Y","formato":"qualquer","data":"amanhã"}]}');
  assert.equal(r.length, 2);
  assert.equal(r[0].formato, "story");
  assert.equal(r[0].quando, "2026-06-12");
  assert.equal(r[1].formato, "feed");
  assert.equal(r[1].quando, "");
  assert.deepEqual(lerIdeiasDePost("nada"), []);
  assert.ok(pedidoDeIdeiasDePost(loja, ["Antiga"], 3, "2026-11-13").includes("Antiga"));
});

test("ideias de vídeo exigem roteiro e pedido para a IA; stories separam manhã e tarde", () => {
  const v = lerIdeiasDeVideo('{"ideias":[{"titulo":"A","roteiro":["c1","c2"],"pedido_ia":"close up","legenda":"l","hashtags":"#a"},{"titulo":"B","roteiro":[],"pedido_ia":"x"}]}');
  assert.equal(v.length, 1);
  assert.equal(v[0].roteiro.length, 2);
  const s = lerStories('{"stories":[{"periodo":"manha","titulo":"Bom dia","texto":"Oi"},{"periodo":"tarde","texto":"Qual prefere?","enquete":"A ou B?"},{"periodo":"tarde"}]}');
  assert.equal(s.length, 2);
  assert.equal(s[1].periodo, "tarde");
  assert.equal(s[1].enquete, "A ou B?");
});

test("respostas do Instagram: 12 modelos (4 perguntas frequentes), texto limpo e pedido com o cadastro", () => {
  assert.equal(MODELOS_DE_RESPOSTA.length, 12);
  assert.equal(MODELOS_DE_RESPOSTA.filter((m) => m.faq).length, 4);
  assert.equal(limparResposta('"Olá!"'), "Olá!");
  assert.equal(limparResposta("a".repeat(2000)).length, 900);
  const p = pedidoDeResposta(loja, MODELOS_DE_RESPOSTA[0], "Loja: Desejo.");
  assert.ok(p.includes("A entrega é discreta?") && p.includes("Loja: Desejo."));
  assert.ok(inspiracao("gel íntimo")[0].url.includes("%C3%ADntimo"));
});

test("primeiros passos acompanham o cadastro", () => {
  assert.equal(primeirosPassos(EMPRESA_VAZIA, false).filter((p) => p.feito).length, 0);
  const p = primeirosPassos({ ...loja, site: "https://d.com", regrasConfiguradas: true }, true);
  assert.ok(p.every((x) => x.feito));
});

test("histórico: busca por assunto sem acento, mais recente primeiro", () => {
  const m = (texto) => ({ id: texto, autor: "eu", texto, quando: 1 });
  const cs = [
    { id: "1", titulo: "Frete", criada: 1, atualizada: 10, mensagens: [m("qual o prazo de entrega?")] },
    { id: "2", titulo: "Promoção", criada: 1, atualizada: 20, mensagens: [m("desconto no Pix")] }
  ];
  assert.deepEqual(buscarConversas(cs, "").map((c) => c.id), ["2", "1"]);
  assert.deepEqual(buscarConversas(cs, "PRAZO entrega").map((c) => c.id), ["1"]);
  assert.deepEqual(buscarConversas(cs, "promocao").map((c) => c.id), ["2"]);
  assert.equal(resumirConversa(cs[0]).mensagens, 1);
  assert.equal(resumirConversa({ ...cs[0], mensagens: [] }).resumo, "Conversa em branco");
});

test("tema igual ao do celular: trio e cor par", () => {
  const rosa = { r: 0xe3, g: 0x6b, b: 0xd0 };
  const [h, s, v] = rgbParaHsv(rosa);
  assert.ok(Math.abs(h - 309) < 3 && s > 0.5 && v > 0.85);
  const [a, b, c] = trio(rosa);
  assert.deepEqual(a, hsvParaRgb(h, s, v));
  assert.notDeepEqual(a, b);
  assert.notDeepEqual(b, c);
  // cor escura ganha brilho mínimo de 0,7
  assert.ok(Math.max(...Object.values(trio({ r: 20, g: 5, b: 5 })[0])) >= 178);
  assert.notDeepEqual(paraSecundaria(rosa), rosa);
});

import { lerDocIdeias, lerDocPosts, lerDocStories, docDeIdeias, chaveDeTitulo } from "../src/empresa/marketing.ts";

test("ideias do celular: id vem do título, removidas viram marca", () => {
  const l = lerDocIdeias({ itens: [
    { titulo: "Dica de Carregador", formato: "feed", legenda: "Texto", quando: "2026-11-20", usada: true, origem: "celular" },
    { titulo: "Velha", removida: true, ts: 5 },
    { titulo: "", legenda: "x" }
  ] });
  assert.equal(l.length, 2);
  assert.equal(l[0].id, chaveDeTitulo("Dica de Carregador"));
  assert.equal(l[0].usada, true);
  assert.equal(l[1].removida, true);
  assert.deepEqual(lerDocIdeias(docDeIdeias(l)).map((x) => x.id), l.map((x) => x.id));
});

test("posts e stories do celular", () => {
  assert.equal(lerDocPosts({ itens: [{ id: 1, quando: 1000, tipo: "story", legenda: "a", link: "" }, { quando: 0 }] }).length, 1);
  const s = lerDocStories({ ligado: true, manha: "09:00", fila: [{ tipo: "dica", titulo: "T", texto: "x", enquete: "" }, { texto: "" }], usados: ["a"] });
  assert.equal(s.fila.length, 1);
  assert.equal(lerDocStories({}), null);
});
