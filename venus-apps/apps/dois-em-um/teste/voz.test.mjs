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

import { limparConfigVoz, configParaEnviar, configRecebida, neuralAtiva, ssml, ESTILOS, CONFIG_VOZ_PADRAO, resumoNeural, LIMITE_SEGURO } from "../src/voz/config.ts";

test("config de voz: descarta lixo e limita velocidade e tom", () => {
  const c = limparConfigVoz({ velocidade: 9, tom: -3, neural: { voz: "inventada", estilo: "agil", regiao: " Brazil South ", chave: " abc " } });
  assert.equal(c.velocidade, 1.5);
  assert.equal(c.tom, 0.5);
  assert.equal(c.neural.voz, "pt-BR-FranciscaNeural");
  assert.equal(c.neural.estilo, "agil");
  assert.equal(c.neural.regiao, "brazilsouth");
  assert.equal(c.neural.chave, "abc");
  assert.deepEqual(limparConfigVoz(null), CONFIG_VOZ_PADRAO);
});

test("a voz do sistema não vai para a conta e a conta não a troca", () => {
  const local = { ...CONFIG_VOZ_PADRAO, vozSistema: "Microsoft Maria" };
  assert.equal("vozSistema" in configParaEnviar(local), false);
  assert.equal(configRecebida(local, { velocidade: 1.2 }).vozSistema, "Microsoft Maria");
  assert.equal(configRecebida(local, { velocidade: 1.2 }).velocidade, 1.2);
});

test("conta sem chave não apaga a chave deste computador", () => {
  const local = limparConfigVoz({ neural: { chave: "segredo" } });
  assert.equal(configRecebida(local, { neural: { chave: "" } }).neural.chave, "segredo");
  assert.equal(configRecebida(local, { neural: { chave: "outra" } }).neural.chave, "outra");
});

test("voz neural só quando ligada, com chave e dentro do limite", () => {
  const c = limparConfigVoz({ neural: { chave: "k" } });
  assert.equal(neuralAtiva(c, 0), true);
  assert.equal(neuralAtiva(c, LIMITE_SEGURO), false);
  assert.equal(neuralAtiva(limparConfigVoz({ neural: { chave: "k", ligada: false } }), 0), false);
  assert.equal(neuralAtiva(CONFIG_VOZ_PADRAO, 0), false);
});

test("SSML escapa o texto e soma velocidade e tom ao jeito de falar", () => {
  const x = ssml("a < b & \"c\"", "pt-BR-FranciscaNeural", ESTILOS[0], 1.2, 0.9);
  assert.match(x, /a &lt; b &amp; &quot;c&quot;/);
  assert.match(x, /rate='\+14%'/);
  assert.match(x, /pitch='-16%'/);
  assert.match(ssml("oi", "v", ESTILOS[1]), /rate='\+0%'/);
});

test("resumo da voz neural", () => {
  assert.match(resumoNeural(CONFIG_VOZ_PADRAO, 0), /Desligada/);
  assert.match(resumoNeural(limparConfigVoz({ neural: { chave: "k" } }), 250_000), /Ligada: Francisca, assistente.*50%/);
});
