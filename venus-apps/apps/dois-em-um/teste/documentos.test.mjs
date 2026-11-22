import test from "node:test";
import assert from "node:assert/strict";
import {
  dinheiroDoc, lerReais, dataBR, mesDe, telefoneBonito, nomeDoArquivoDoc, subtotalDe, totalDe, linhasDeTexto,
  cotaDoMes, usadosDoMes, restantesDoMes, podeGerarDoc, registrarDoc, comMarca, resumoDoUsoDoc, quebrarTexto, montarDocumento, LIMITE_GRATIS,
} from "../src/empresa/documentos.ts";

const emissor = { nome: "Loja & Cia", documento: "00.000.000/0001-00", contato: "(51) 99999-8888", enderecoLinhas: ["Rua A, 1", "Porto Alegre/RS"], mensagem: "Obrigada!" };
const dados = (extra = {}) => ({
  emissor, numero: "1042", data: "14/11/2023", cliente: "Maria <Souza>", telefone: "(51) 99999-8888",
  enderecoLinhas: ["Rua das Flores, 100", "Centro", "Porto Alegre/RS", "CEP 90000-000"],
  itens: [{ nome: "Conjunto renda", quantidade: 2, precoCentavos: 8990 }, { nome: "Meia", quantidade: 1, precoCentavos: 3010 }],
  freteCentavos: 1500, descontoCentavos: 500, retirada: false, rastreio: "", observacao: "", ...extra,
});

test("dinheiro em reais com milhar e leitura de valores", () => {
  assert.equal(dinheiroDoc(5), "R$ 0,05");
  assert.equal(dinheiroDoc(123456), "R$ 1.234,56");
  assert.equal(dinheiroDoc(-1000), "-R$ 10,00");
  assert.equal(lerReais("89,90"), 8990);
  assert.equal(lerReais("R$ 89.90"), 8990);
  assert.equal(lerReais("1.234,50"), 123450);
  assert.equal(lerReais("50"), 5000);
  assert.equal(lerReais(""), null);
  assert.equal(lerReais("abc"), null);
});

test("data e mês no fuso de Brasília", () => {
  const t = 1_698_892_200_000; // 2023-11-02 02:30 UTC = 1º de novembro 23:30 em Brasília
  assert.equal(dataBR(t), "01/11/2023");
  assert.equal(mesDe(t), "2023-11");
  assert.equal(dataBR(0), "");
});

test("telefone e nome de arquivo", () => {
  assert.equal(telefoneBonito("5551999998888"), "(51) 99999-8888");
  assert.equal(telefoneBonito("(51) 3333-4444"), "(51) 3333-4444");
  assert.equal(nomeDoArquivoDoc("recibo", "1042"), "recibo-1042");
  assert.equal(nomeDoArquivoDoc("etiqueta", "A-1042/Ç"), "etiqueta-a-1042-c");
  assert.equal(nomeDoArquivoDoc("notinha", "///"), "notinha-avulso");
  assert.deepEqual(linhasDeTexto(" a \n\n b "), ["a", "b"]);
});

test("totais: subtotal + frete - desconto, nunca negativo", () => {
  const d = dados();
  assert.equal(subtotalDe(d), 2 * 8990 + 3010);
  assert.equal(totalDe(d), 20990 + 1500 - 500);
  assert.equal(totalDe(dados({ descontoCentavos: 999999 })), 0);
});

test("limite de 15 por mês, refazer não gasta e o mês vira", () => {
  let c = null;
  for (let i = 1; i <= LIMITE_GRATIS; i++) { assert.ok(podeGerarDoc(c, "2026-11", "recibo", String(i), false)); c = registrarDoc(c, "2026-11", "recibo", String(i)); }
  assert.equal(usadosDoMes(c, "2026-11"), 15);
  assert.equal(restantesDoMes(c, "2026-11"), 0);
  assert.equal(podeGerarDoc(c, "2026-11", "recibo", "16", false), false);
  assert.equal(podeGerarDoc(c, "2026-11", "notinha", "1", false), false);
  assert.equal(podeGerarDoc(c, "2026-11", "recibo", "7", false), true);
  assert.equal(usadosDoMes(registrarDoc(c, "2026-11", "recibo", "7"), "2026-11"), 15);
  assert.equal(podeGerarDoc(c, "2026-12", "recibo", "99", false), true);
  assert.deepEqual(cotaDoMes(c, "2026-12"), { mes: "2026-12", feitos: [] });
});

test("plano pago: sem limite e sem marca", () => {
  let c = null;
  for (let i = 1; i <= 40; i++) c = registrarDoc(c, "2026-11", "recibo", String(i));
  assert.equal(podeGerarDoc(c, "2026-11", "recibo", "41", true), true);
  assert.equal(comMarca(true), false);
  assert.equal(comMarca(false), true);
  assert.equal(resumoDoUsoDoc(c, "2026-11", true), "Sem limite no seu plano.");
  assert.equal(resumoDoUsoDoc({ mes: "2026-11", feitos: ["a", "b", "c"] }, "2026-11", false), "Você usou 3 de 15 documentos grátis neste mês.");
});

test("quebrarTexto corta com reticências", () => {
  assert.deepEqual(quebrarTexto("aaa bbb ccc ddd", 7, 5), ["aaa bbb", "ccc ddd"]);
  assert.equal(quebrarTexto("um dois tres quatro cinco seis", 8, 2).length, 2);
});

test("os três documentos saem em SVG com o que importa, sem quebrar com & e <", () => {
  for (const tipo of ["recibo", "notinha", "etiqueta"]) {
    const p = montarDocumento(tipo, dados({ rastreio: "AB123" }), true);
    assert.match(p.svg, /^<svg /);
    assert.ok(p.svg.includes("Loja &amp; Cia") || tipo === "etiqueta");
    assert.ok(p.svg.includes("Maria &lt;Souza&gt;") || p.svg.includes("MARIA &lt;SOUZA&gt;"));
    assert.ok(p.svg.includes("Feito com a Vênus"));
    assert.ok(!/<Souza>/.test(p.svg));
    assert.equal(p.escala, 2);
  }
  const recibo = montarDocumento("recibo", dados(), false);
  assert.equal(recibo.largura, 794);
  assert.equal(recibo.altura, 1123);
  assert.ok(recibo.svg.includes("R$ 219,90"));
  assert.ok(recibo.svg.includes("Documento sem valor fiscal."));
  assert.ok(!recibo.svg.includes("Feito com a Vênus"));
  assert.equal(montarDocumento("etiqueta", dados(), false).altura, 567);
  assert.equal(montarDocumento("etiqueta", dados(), false).largura, 378);
  assert.equal(montarDocumento("notinha", dados(), false).largura, 302);
});

test("notinha cresce com os itens e etiqueta avisa quando falta endereço", () => {
  const curta = montarDocumento("notinha", dados({ itens: [{ nome: "A", quantidade: 1, precoCentavos: 100 }] }), false);
  const longa = montarDocumento("notinha", dados({ itens: Array.from({ length: 10 }, (_, i) => ({ nome: `Item ${i}`, quantidade: 1, precoCentavos: 100 })) }), false);
  assert.ok(longa.altura > curta.altura);
  assert.match(montarDocumento("etiqueta", dados({ enderecoLinhas: [] }), false).svg, /Sem endereço/);
  assert.match(montarDocumento("etiqueta", dados({ enderecoLinhas: [], retirada: true }), false).svg, /RETIRADA NA LOJA/);
});
