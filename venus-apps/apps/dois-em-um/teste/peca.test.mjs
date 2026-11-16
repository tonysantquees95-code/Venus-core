import test from "node:test";
import assert from "node:assert/strict";
import { montarPanfleto, montarCartao, quebrar, esc } from "../src/empresa/peca.ts";
import { pdfComImagem } from "../src/empresa/pdf.ts";

const base = { loja: "Desejo & Cia", cor: "#b4235a", chamada: "Frete grátis neste fim de semana em tudo", oferta: "10% no Pix", telefone: "(51) 99999-9999", gerarQr: () => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" shape-rendering="crispEdges"><path d="M0,0h1v1h-1z"/></svg>`, link: "https://vetorial.tech/v/desejo", siteTexto: "desejo.com.br", qr: true };

test("quebrar divide por palavras e corta com reticências", () => {
  assert.deepEqual(quebrar("uma frase bem curta", 30, 3), ["uma frase bem curta"]);
  assert.deepEqual(quebrar("aaa bbb ccc ddd", 7, 5), ["aaa bbb", "ccc ddd"]);
  const q = quebrar("um dois tres quatro cinco seis sete oito", 8, 2);
  assert.equal(q.length, 2);
  assert.ok(q[1].endsWith("…"));
});

test("panfleto digital: vertical, escapa texto, tem QR e links clicáveis", () => {
  const p = montarPanfleto({ ...base, produtos: [{ nome: "Gel <b>x</b>", preco: "R$ 29,90", url: "https://vetorial.tech/v/desejo/1" }, { nome: "Vela", preco: "R$ 10,00", url: "https://vetorial.tech/v/desejo/2" }] });
  assert.equal(p.largura, 1080); assert.equal(p.altura, 1350);
  assert.match(p.svg, /^<svg /);
  assert.ok(!p.svg.includes("<b>x</b>"));
  assert.ok(p.svg.includes("Desejo &amp; Cia"));
  assert.ok(p.svg.includes('shape-rendering="crispEdges"'), "QR dentro do panfleto");
  const urls = p.links.map((l) => l.url);
  assert.ok(urls.includes("https://vetorial.tech/v/desejo/1") && urls.includes("https://vetorial.tech/v/desejo/2"));
  assert.ok(urls.includes("https://vetorial.tech/v/desejo") && urls.includes("https://wa.me/5551999999999"));
  for (const l of p.links) assert.ok(l.x >= 0 && l.y >= 0 && l.x + l.w <= 1080 && l.y + l.h <= 1350, JSON.stringify(l));
});

test("sem QR não desenha o código; sem link não tem botão", () => {
  assert.ok(!montarPanfleto({ ...base, qr: false }).svg.includes("crispEdges"));
  const p = montarPanfleto({ ...base, link: "", qr: true });
  assert.ok(!p.svg.includes("Ver a loja e comprar"));
  assert.ok(!p.links.some((l) => l.url.includes("vetorial.tech")));
});

test("cartão digital: com e sem QR", () => {
  const c = montarCartao(base);
  assert.equal(c.largura, 1000); assert.equal(c.altura, 560);
  assert.ok(c.svg.includes("crispEdges"));
  const s = montarCartao({ ...base, qr: false });
  assert.ok(s.svg.includes("Ver a loja") && !s.svg.includes("crispEdges"));
  assert.ok(esc("a<b") === "a&lt;b");
});

test("PDF: cabeçalho, imagem, links clicáveis e xref coerente", () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 1, 2, 3, 0xff, 0xd9]);
  const pdf = pdfComImagem(jpeg, 1080, 1350, [{ x: 10, y: 20, w: 100, h: 50, url: "https://a.com/x(1)" }, { x: 0, y: 0, w: 5, h: 5, url: "javascript:alert(1)" }]);
  const t = Buffer.from(pdf).toString("latin1");
  assert.ok(t.startsWith("%PDF-1.4"));
  assert.ok(t.includes("/DCTDecode") && t.includes("/Subtype /Link"));
  assert.ok(t.includes("https://a.com/x%281%29"), "parênteses escapados");
  assert.ok(!t.includes("javascript:"), "só http(s)");
  assert.equal((t.match(/\/Subtype \/Link/g) || []).length, 1);
  const inicio = Number(/startxref\n(\d+)/.exec(t)[1]);
  assert.ok(t.slice(inicio).startsWith("xref"));
  assert.ok(t.trimEnd().endsWith("%%EOF"));
});
