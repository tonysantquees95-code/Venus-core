import test from "node:test";
import assert from "node:assert/strict";
import { ehConfirmacao, ehNegacao, interpretar, montarExecucao, respostaDa, urlSegura } from "../computador.mjs";

test("abre programas conhecidos, com ou sem educação", () => {
  assert.deepEqual(interpretar("Vênus, abre a calculadora"), { acao: "programa", chave: "calculadora" });
  assert.deepEqual(interpretar("pode abrir o bloco de notas por favor?"), { acao: "programa", chave: "bloco de notas" });
  assert.deepEqual(interpretar("abrir excel"), { acao: "programa", chave: "excel" });
  assert.deepEqual(montarExecucao({ acao: "programa", chave: "calculadora" }), { tipo: "processo", arquivo: "calc.exe", args: [] });
  assert.deepEqual(montarExecucao({ acao: "programa", chave: "word" }).args, ["/c", "start", "", "winword"]);
});

test("configurações do Windows por assunto; ajustes da Vênus não são do computador", () => {
  assert.deepEqual(interpretar("abrir configurações de wi-fi"), { acao: "configuracoes", chave: "wi-fi" });
  assert.deepEqual(interpretar("abrir as configurações"), { acao: "configuracoes", chave: "geral" });
  assert.equal(montarExecucao(interpretar("abre as configurações de bluetooth")).url, "ms-settings:bluetooth");
  assert.equal(interpretar("abrir configurações da vênus"), null);
});

test("sites, pastas e buscas", () => {
  assert.deepEqual(interpretar("abrir o youtube"), { acao: "site", chave: "youtube" });
  assert.equal(montarExecucao(interpretar("abrir desejodeintimidade.com.br")).url, "https://desejodeintimidade.com.br/");
  assert.deepEqual(interpretar("abrir a pasta downloads"), { acao: "pasta", chave: "downloads" });
  assert.equal(montarExecucao(interpretar("abrir downloads")).args[0], "shell:Downloads");
  const b = interpretar("pesquisar frete grátis correios & calc");
  assert.equal(b.acao, "busca");
  assert.equal(montarExecucao(b).url, "https://www.google.com/search?q=frete%20gr%C3%A1tis%20correios%20%26%20calc");
});

test("volume e bloqueio de tela", () => {
  assert.deepEqual(interpretar("aumentar o volume"), { acao: "volume", direcao: "mais" });
  assert.deepEqual(interpretar("diminui o volume"), { acao: "volume", direcao: "menos" });
  assert.deepEqual(interpretar("silenciar"), { acao: "volume", direcao: "mudo" });
  assert.deepEqual(interpretar("bloquear a tela"), { acao: "bloquear" });
  assert.deepEqual(montarExecucao({ acao: "bloquear" }), { tipo: "processo", arquivo: "rundll32.exe", args: ["user32.dll,LockWorkStation"] });
  assert.match(montarExecucao({ acao: "volume", direcao: "mais" }).args.join(" "), /\[char\]175/);
});

test("desligar, reiniciar e suspender pedem confirmação; cancelar desfaz", () => {
  assert.deepEqual(interpretar("desligar o computador"), { acao: "perigosa", tipo: "desligar" });
  assert.deepEqual(interpretar("reiniciar o pc"), { acao: "perigosa", tipo: "reiniciar" });
  assert.deepEqual(interpretar("suspender o computador"), { acao: "perigosa", tipo: "suspender" });
  assert.deepEqual(interpretar("cancelar desligamento"), { acao: "cancelar-desligamento" });
  assert.deepEqual(montarExecucao({ acao: "perigosa", tipo: "desligar" }).args, ["/s", "/t", "30"]);
  assert.ok(ehConfirmacao("Sim!") && ehConfirmacao("confirmo") && !ehConfirmacao("talvez"));
  assert.ok(ehNegacao("não") && ehNegacao("cancela"));
});

test("conversa comum NÃO vira comando", () => {
  for (const t of ["qual o prazo de entrega do pedido 123?", "como abrir uma loja online", "me ajuda a escrever uma descrição", "o que é um pix", "sair da minha conta vetorial", "oi tudo bem"]) {
    assert.equal(interpretar(t), null, t);
  }
});

test("programa desconhecido responde sem executar; só http(s) é aberto", () => {
  const a = interpretar("abrir o photoshop");
  assert.equal(a.acao, "desconhecido");
  assert.equal(montarExecucao(a), null);
  assert.match(respostaDa(a), /photoshop/);
  assert.equal(urlSegura("file:///c:/windows/system32/cmd.exe"), null);
  assert.equal(urlSegura("javascript:alert(1)"), null);
  assert.equal(urlSegura("https://exemplo.com.br/a"), "https://exemplo.com.br/a");
  assert.equal(montarExecucao({ acao: "site", url: "file:///c:/x.exe" }), null);
});
