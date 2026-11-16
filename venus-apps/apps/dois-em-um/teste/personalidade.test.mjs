import test from "node:test";
import assert from "node:assert/strict";
import { PERSONALIDADE, sistemaDaConversa } from "../src/ia/personalidade.ts";

test("personalidade: sempre na frente, mesmo sem cadastro da loja", () => {
  assert.equal(sistemaDaConversa(), PERSONALIDADE);
  assert.equal(sistemaDaConversa("", undefined), PERSONALIDADE);
  const s = sistemaDaConversa("Loja: X", "Catálogo: Y");
  assert.ok(s.startsWith(PERSONALIDADE));
  assert.ok(s.endsWith("Catálogo: Y"));
});

test("personalidade: curta, com uma pergunta específica e sem inventar", () => {
  assert.match(PERSONALIDADE, /2 a 3 frases/);
  assert.match(PERSONALIDADE, /UMA pergunta/);
  assert.match(PERSONALIDADE, /Não invente/);
  assert.ok(PERSONALIDADE.length < 1200, "tem que caber folgado nos 3900 caracteres da rota");
});
