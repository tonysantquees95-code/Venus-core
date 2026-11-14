import test from "node:test";
import assert from "node:assert/strict";
import { perfilJogo, validarAjustes } from "../src/vortex/varredura.js";

test("filtra chaves e valores fora da faixa", () => {
  assert.deepEqual(validarAjustes({ "GFX.Settings.InternalResolution": 3, "GFX.Settings.MSAA": 3, "Dolphin.Core.CPUThread": true, "Inventada.Chave": 1, "GFX.Hardware.VSync": "sim" }), { "GFX.Settings.InternalResolution": 3, "Dolphin.Core.CPUThread": true });
});
test("só GameCube e Wii", async () => {
  let chamou = false;
  const r = await perfilJogo({}, { titulo: "Zelda", plataforma: "ps1" }, async () => { chamou = true; return { texto: "{}" }; });
  assert.equal(chamou, false);
  assert.deepEqual(r.ajustes, {});
});
test("usa a resposta da IA validada", async () => {
  const r = await perfilJogo({}, { titulo: "Zelda", plataforma: "gamecube", aparelho: { cpu: "x", nucleos: 4, ram_gb: 8 } }, async () => ({ provedor: "groq", texto: '{"ajustes":{"GFX.Settings.InternalResolution":2,"X":1},"motivo":"fraco"}' }));
  assert.deepEqual(r.ajustes, { "GFX.Settings.InternalResolution": 2 });
});
