import test from "node:test";
import assert from "node:assert/strict";
import { numeroDaVersao, temNovaVersao, urlSegura, lerInfo, arquivoConfere, argsDoInstalador } from "../atualizador.mjs";

test("número da versão vem do build do CI", () => {
  assert.equal(numeroDaVersao("1.204.0"), 204);
  assert.equal(numeroDaVersao("1.7"), 7);
  assert.equal(numeroDaVersao("1.0.0"), 0);
  assert.equal(numeroDaVersao("2.5.0"), 0);
  assert.equal(numeroDaVersao(undefined), 0);
});

test("só avisa quando a do servidor é maior", () => {
  assert.equal(temNovaVersao("1.204.0", 205), true);
  assert.equal(temNovaVersao("1.204.0", 204), false);
  assert.equal(temNovaVersao("1.204.0", 100), false);
  assert.equal(temNovaVersao("1.204.0", NaN), false);
  assert.equal(temNovaVersao("1.204.0", 0), false);
});

test("só baixa de https do GitHub", () => {
  assert.equal(urlSegura("https://github.com/a/b/releases/download/win-1.5/VenusSetup.exe"), true);
  assert.equal(urlSegura("https://release-assets.githubusercontent.com/x"), true);
  assert.equal(urlSegura("https://objects.githubusercontent.com/x"), true);
  assert.equal(urlSegura("http://github.com/x"), false);
  assert.equal(urlSegura("https://github.com.mal.com/x"), false);
  assert.equal(urlSegura("https://evilgithubusercontent.com/x"), false);
  assert.equal(urlSegura("file:///c:/x.exe"), false);
  assert.equal(urlSegura("lixo"), false);
});

test("lerInfo valida a resposta do servidor", () => {
  const ok = lerInfo({ numero: 210, tamanho: 90_000_000, sha256: "A".repeat(64), url: "https://github.com/x/y.exe", notas: "novidades" });
  assert.equal(ok.numero, 210);
  assert.equal(ok.sha256, "a".repeat(64));
  assert.equal(lerInfo({ numero: 210, url: "https://evil.com/x.exe" }), null);
  assert.equal(lerInfo({ numero: "x", url: "https://github.com/x" }), null);
  assert.equal(lerInfo(null), null);
  assert.equal(lerInfo({ numero: 5, url: "https://github.com/x", tamanho: 9e12 }), null);
  assert.equal(lerInfo({ numero: 5, url: "https://github.com/x", sha256: "curto" }).sha256, "");
});

test("confere tamanho e hash do arquivo baixado", () => {
  const info = { tamanho: 5_000_000, sha256: "b".repeat(64) };
  assert.equal(arquivoConfere(info, 5_000_000, "B".repeat(64)), true);
  assert.equal(arquivoConfere(info, 4_999_999, "b".repeat(64)), false);
  assert.equal(arquivoConfere(info, 5_000_000, "c".repeat(64)), false);
  assert.equal(arquivoConfere({ tamanho: 0, sha256: "" }, 2_000_000, "qualquer"), true);
  assert.equal(arquivoConfere({ tamanho: 0, sha256: "" }, 100, "x"), false);
});

test("instalador silencioso reabre a Vênus", () => {
  assert.deepEqual(argsDoInstalador(), ["/S", "--updated", "--force-run"]);
});
