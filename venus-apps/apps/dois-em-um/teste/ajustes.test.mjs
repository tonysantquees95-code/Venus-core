import test from "node:test";
import assert from "node:assert/strict";
import { buscar, CATALOGO, detectarPlataforma, haQuantoTempo, limparPreferencias, PREFERENCIAS_PADRAO, prefsParaEnviar, prefsRecebidas } from "../src/ajustes/logica.ts";
import { decidirRemoto, temaValido } from "../src/sync/mesclar.ts";
import { ehIOS, mostrarTutorialIOS, navegadorIOS, PASSOS_IOS } from "../src/ios/logica.ts";

const IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
const IPHONE_CHROME = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0 Mobile/15E148 Safari/604.1";

test("busca de ajustes ignora acento e caixa e exige todas as palavras", () => {
  assert.equal(buscar("escuta continua")[0].id, "escutaContinua");
  assert.equal(buscar("ESCUTA CONTÍNUA")[0].id, "escutaContinua");
  assert.ok(buscar("notificações").some((i) => i.id === "notificacoes"));
  assert.ok(buscar("tema").some((i) => i.id === "tema"));
  assert.ok(buscar("encerrar sessao").some((i) => i.id === "dispositivos"));
  assert.equal(buscar("zzzz").length, 0);
  assert.equal(buscar("").length, CATALOGO.length);
});

test("preferências inválidas voltam ao padrão e valores certos são mantidos", () => {
  assert.deepEqual(limparPreferencias(null), PREFERENCIAS_PADRAO);
  const p = limparPreferencias({ modo: "bolha", escutaContinua: "sim", falarRespostas: false });
  assert.equal(p.modo, "bolha");
  assert.equal(p.escutaContinua, false);
  assert.equal(p.falarRespostas, false);
  assert.equal(limparPreferencias({ modo: "hacker" }).modo, "conversacional");
});

test("plataforma é detectada para cada aparelho", () => {
  assert.equal(detectarPlataforma(IPHONE), "ios");
  assert.equal(detectarPlataforma("Mozilla/5.0 (Linux; Android 14)"), "android");
  assert.equal(detectarPlataforma("Mozilla/5.0 (Windows NT 10.0)"), "windows");
  assert.equal(detectarPlataforma("qualquer", { desktop: true }), "windows");
  assert.equal(detectarPlataforma("Mozilla/5.0 (X11; Linux x86_64)"), "web");
  assert.equal(haQuantoTempo(1000, 1000 + 5 * 60_000), "há 5 min");
  assert.equal(haQuantoTempo(0, 10_000), "agora");
});

test("sincronização: só aplica versão mais nova e ignora o eco do próprio aparelho", () => {
  assert.equal(decidirRemoto({ chave: "tema", versao: 3, deEsteAparelho: false }, { tema: 2 }), "aplicar");
  assert.equal(decidirRemoto({ chave: "tema", versao: 2, deEsteAparelho: false }, { tema: 2 }), "ignorar");
  assert.equal(decidirRemoto({ chave: "tema", versao: 2, deEsteAparelho: true }, { tema: 2 }), "ignorar");
  assert.equal(decidirRemoto({ chave: "tema", versao: 1, deEsteAparelho: false }, {}), "aplicar");
  assert.equal(temaValido({ orbe: "aurora", icones: "linha", cores: { destaque: { r: 1, g: 2, b: 3 }, secundaria: { r: 255, g: 0, b: 0 } } }), true);
  assert.equal(temaValido({ orbe: "aurora", icones: "linha", cores: { destaque: { r: 999, g: 2, b: 3 }, secundaria: { r: 1, g: 1, b: 1 } } }), false);
  assert.equal(temaValido(null), false);
});

test("escuta contínua e iniciar com o sistema não seguem a conta", () => {
  const local = { ...PREFERENCIAS_PADRAO, escutaContinua: true };
  assert.equal("escutaContinua" in prefsParaEnviar(local), false);
  const r = prefsRecebidas(local, { modo: "orbe", escutaContinua: false, iniciarComOSistema: true });
  assert.equal(r.modo, "orbe");
  assert.equal(r.escutaContinua, true);
  assert.equal(r.iniciarComOSistema, false);
});

test("tutorial do iPhone: só no iOS, fora do app instalado e uma vez por semana", () => {
  assert.equal(ehIOS(IPHONE), true);
  assert.equal(ehIOS("Mozilla/5.0 (Macintosh; Intel Mac OS X)", 5), true); // iPadOS
  assert.equal(ehIOS("Mozilla/5.0 (Macintosh; Intel Mac OS X)", 0), false);
  assert.equal(navegadorIOS(IPHONE), "safari");
  assert.equal(navegadorIOS(IPHONE_CHROME), "chrome");
  assert.equal(mostrarTutorialIOS({ ua: IPHONE, standalone: false }), true);
  assert.equal(mostrarTutorialIOS({ ua: IPHONE, standalone: true }), false);
  assert.equal(mostrarTutorialIOS({ ua: "Android", standalone: false }), false);
  assert.equal(mostrarTutorialIOS({ ua: IPHONE, standalone: false, dispensadoEm: 1000, agora: 1000 + 86_400_000 }), false);
  assert.equal(mostrarTutorialIOS({ ua: IPHONE, standalone: false, dispensadoEm: 1000, agora: 1000 + 8 * 86_400_000 }), true);
  assert.equal(PASSOS_IOS("safari").length, 3);
  assert.match(PASSOS_IOS("safari")[1].titulo, /Adicionar à Tela de Início/);
});

import { iniciais, corDoAvatar, textoDaAssinatura, interpretarComandoConta } from "../src/id/perfil.ts";

test("avatar: iniciais, cor estável e texto da assinatura", () => {
  assert.equal(iniciais("Toni Marques"), "TM");
  assert.equal(iniciais("toni"), "TO");
  assert.equal(iniciais("", "ana@x.com"), "A");
  assert.equal(iniciais("  ", ""), "?");
  assert.equal(corDoAvatar("a@b.com"), corDoAvatar("a@b.com"));
  assert.notEqual(corDoAvatar("a@b.com"), corDoAvatar("c@d.com"));
  assert.equal(textoDaAssinatura({ acesso: true, emTeste: true, diasDeTesteRestantes: 5 }), "Plano Fundadores · teste grátis, 5 dias restantes");
  assert.equal(textoDaAssinatura({ acesso: true, emTeste: true, diasDeTesteRestantes: 1 }), "Plano Fundadores · teste grátis, 1 dia restante");
  assert.equal(textoDaAssinatura({ acesso: true, emTeste: false, diasPagosRestantes: 20 }), "Plano Fundadores · 20 dias restantes");
  assert.match(textoDaAssinatura({ acesso: false }), /vencido/);
});

test("comandos de conta pela conversa", () => {
  const c = interpretarComandoConta;
  assert.equal(c("Vênus, qual é o status da minha conta?"), "status");
  assert.equal(c("quantos dias de teste ainda tenho?"), "status");
  assert.equal(c("quando vence minha assinatura"), "status");
  assert.equal(c("Vênus, conectar minha conta no novo dispositivo"), "conectar");
  assert.equal(c("fazer login na minha conta"), "conectar");
  assert.equal(c("Vênus, sair da minha conta aqui"), "sair");
  assert.equal(c("deslogar da conta"), "sair");
  assert.equal(c("quais são meus dispositivos conectados"), "dispositivos");
  assert.equal(c("qual a previsão do tempo"), null);
  assert.equal(c("estou conectado na internet?"), null);
  assert.equal(c("status do pedido 123"), null);
});
