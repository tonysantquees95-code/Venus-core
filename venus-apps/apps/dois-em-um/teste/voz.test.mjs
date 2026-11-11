import test from "node:test";
import assert from "node:assert/strict";
import { escolherMime, limparParaFalar } from "../src/voz/logica.ts";

test("escolhe o primeiro formato de gravação suportado", () => {
  assert.equal(escolherMime(() => true), "audio/webm;codecs=opus");
  assert.equal(escolherMime((m) => m === "audio/mp4"), "audio/mp4");
  assert.equal(escolherMime(() => false), undefined);
});

test("limpa markdown, links e emojis antes de falar", () => {
  assert.equal(limparParaFalar("**Pedido** pago ✅ veja [aqui](https://x.com/a) ou https://y.com/b"), "Pedido pago veja aqui ou link");
  assert.equal(limparParaFalar("```js\nconsole.log(1)\n```\nPronto."), "Pronto.");
  assert.equal(limparParaFalar("- um\n- dois"), "um dois");
});

test("texto longo é cortado no fim de uma frase", () => {
  const longo = "Primeira frase bem comprida. ".repeat(60);
  const t = limparParaFalar(longo, 200);
  assert.ok(t.length <= 200 && t.endsWith("."));
});
