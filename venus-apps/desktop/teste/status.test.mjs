import test from "node:test";
import assert from "node:assert/strict";
import { criarMonitor, ehDoApp, linkExterno, textoDoStatus } from "../status.mjs";

test("avisa a queda só depois de 2 falhas seguidas e avisa a volta", () => {
  const m = criarMonitor();
  assert.equal(m.registrar(true, 80).aviso, null);          // primeira medição: sem aviso
  assert.equal(m.registrar(false).aviso, null);              // 1 falha: pode ser instabilidade
  assert.equal(m.registrar(true, 90).aviso, null);           // voltou antes de cair: nada
  m.registrar(false);
  const caiu = m.registrar(false);
  assert.deepEqual([caiu.online, caiu.mudou, caiu.aviso], [false, true, "caiu"]);
  assert.equal(m.registrar(false).aviso, null);              // já avisou: não repete
  const voltou = m.registrar(true, 120);
  assert.deepEqual([voltou.online, voltou.mudou, voltou.aviso], [true, true, "voltou"]);
});

test("começar offline não gera aviso de queda", () => {
  const m = criarMonitor();
  m.registrar(false);
  const r = m.registrar(false);
  assert.equal(r.online, false);
  assert.equal(r.aviso, null);
  assert.equal(m.registrar(true, 50).aviso, "voltou");
});

test("textos e regras de navegação", () => {
  assert.equal(textoDoStatus({ online: true, ms: 42 }), "Vênus conectada (42 ms)");
  assert.equal(textoDoStatus({ online: false }), "Vênus sem conexão com o servidor");
  assert.equal(ehDoApp("app://venus/index.html"), true);
  assert.equal(ehDoApp("https://evil.com/"), false);
  assert.equal(linkExterno("https://vetorial.tech"), true);
  assert.equal(linkExterno("file:///etc/passwd"), false);
  assert.equal(linkExterno("javascript:alert(1)"), false);
});
