import test from "node:test";
import assert from "node:assert/strict";
import { escolherMime, limparParaFalar, deveFalar, nomeDoArquivo, merecemAcoes } from "../src/voz/logica.ts";

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

test("só fala em voz alta quando a pessoa falou", () => {
  assert.equal(deveFalar(true, true), true);
  assert.equal(deveFalar(false, true), false); // digitou: só texto
  assert.equal(deveFalar(true, false), false); // voz desligada nos ajustes
});

test("nome do arquivo guardado e quando mostrar os botões", () => {
  const n = nomeDoArquivo("Como fazer um bolo de cenoura: misture tudo!", new Date(2026, 10, 15, 14, 30));
  assert.equal(n, "2026-11-15 1430 - como fazer um bolo de cenoura.txt");
  assert.equal(nomeDoArquivo("???", new Date(2026, 10, 15, 9, 5)), "2026-11-15 0905.txt");
  assert.equal(merecemAcoes("Oi!"), false);
  assert.equal(merecemAcoes("Aqui está o resumo das suas vendas da semana."), true);
});
