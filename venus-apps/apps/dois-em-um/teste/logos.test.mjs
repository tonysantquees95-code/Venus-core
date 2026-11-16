import test from "node:test";
import assert from "node:assert/strict";
import { FONTES, MODELOS, gerarSvg, escapar } from "../src/empresa/logos.ts";

test("há 24 modelos de logo, todos com id único", () => {
  assert.equal(MODELOS.length, 24);
  assert.equal(new Set(MODELOS.map((m) => m.id)).size, 24);
  assert.ok(FONTES.some((f) => f.id === "cursiva"));
});

test("o SVG leva o nome e as cores escolhidas", () => {
  for (const m of MODELOS) {
    const svg = gerarSvg(m, "Desejo", "#112233", "#ffeedd");
    assert.match(svg, /^<svg /);
    assert.match(svg, /#112233/);
    assert.match(svg, /Desejo|DESEJO/);
  }
});

test("nome com símbolos é escapado e cor inválida cai na padrão", () => {
  const svg = gerarSvg(MODELOS[0], `<b>"Loja" & Cia</b>`, "vermelho", "x");
  assert.ok(!svg.includes("<b>"));
  assert.match(svg, /&lt;b&gt;/);
  assert.match(svg, /#7c3aed/);
  assert.equal(escapar("a&b"), "a&amp;b");
  assert.match(gerarSvg(MODELOS[0], "   ", "#000000", "#ffffff"), /Minha loja/);
});
