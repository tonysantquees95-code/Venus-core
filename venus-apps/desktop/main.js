// Vênus para Windows: janela do dashboard + bandeja do sistema + atalho global + notificações nativas
// + monitor de conexão com os Workers. A interface é o mesmo app React (Dois em Um) dos outros aparelhos.
const { app, BrowserWindow, Tray, Menu, Notification, globalShortcut, ipcMain, nativeImage, protocol, net, shell, dialog, session } = require("electron");
const fs = require("node:fs");
const crypto = require("node:crypto");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { pathToFileURL } = require("node:url");

const SERVIDOR = (process.env.VENUS_SERVIDOR || "https://venus-servidor.tonysantquees95.workers.dev").replace(/\/+$/, "");
const MAC = process.platform === "darwin";
const ATALHO = "CommandOrControl+Alt+V";
const NOME_ATALHO = ATALHO.replace("CommandOrControl", MAC ? "Cmd" : "Ctrl");
const INTERVALO_MS = 15_000;
const RAIZ_APP = path.join(__dirname, "app");

protocol.registerSchemesAsPrivileged([{ scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true } }]);

let janela = null;
let bandeja = null;
let saindo = false;
let ultimoStatus = { online: false, ms: null, quando: 0 };
let statusModulo = null;
let computador = null;
let pendente = null; // ação perigosa esperando o "sim" (vale 30 s)
let atualizador = null;
let novaVersao = null; // { numero, ... } quando o servidor tem versão mais nova
let instalando = false;
let jaPerguntou = 0;

const trava = app.requestSingleInstanceLock();
if (!trava) app.quit();
app.on("second-instance", () => mostrar());

function mostrar() {
  if (!janela) return criarJanela(false);
  if (janela.isMinimized()) janela.restore();
  janela.show();
  janela.focus();
}

function criarJanela(oculta) {
  janela = new BrowserWindow({
    width: 1280, height: 820, minWidth: 420, minHeight: 600,
    show: !oculta, backgroundColor: "#08080F", title: "Vênus", autoHideMenuBar: true,
    ...(MAC ? {} : { icon: path.join(__dirname, "build", "icon.ico") }),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true },
  });
  // só o microfone (para falar com a Vênus) e as notificações; o resto é negado
  janela.webContents.session.setPermissionRequestHandler((_wc, permissao, ok) => ok(permissao === "media" || permissao === "notifications"));
  janela.webContents.session.setPermissionCheckHandler((_wc, permissao) => permissao === "media" || permissao === "notifications");
  janela.loadURL("app://venus/index.html");
  // links de fora abrem no navegador; o app nunca navega para outro site
  janela.webContents.setWindowOpenHandler(({ url }) => { if (statusModulo.linkExterno(url)) shell.openExternal(url); return { action: "deny" }; });
  janela.webContents.on("will-navigate", (e, url) => { if (!statusModulo.ehDoApp(url)) { e.preventDefault(); if (statusModulo.linkExterno(url)) shell.openExternal(url); } });
  // fechar a janela esconde na bandeja: a Vênus continua em segundo plano
  janela.on("close", (e) => { if (!saindo) { e.preventDefault(); janela.hide(); } });
  janela.on("closed", () => { janela = null; });
}

function montarBandeja() {
  bandeja = new Tray(nativeImage.createFromPath(path.join(__dirname, "build", "tray.png")));
  bandeja.setToolTip("Vênus");
  bandeja.on("click", () => (janela && janela.isVisible() && janela.isFocused() ? janela.hide() : mostrar()));
  atualizarMenu();
}

function atualizarMenu() {
  if (!bandeja) return;
  const iniciar = app.getLoginItemSettings().openAtLogin;
  bandeja.setContextMenu(Menu.buildFromTemplate([
    { label: "Abrir a Vênus", click: mostrar },
    { label: `Chamar de qualquer tela: ${NOME_ATALHO}`, enabled: false },
    { type: "separator" },
    { label: statusModulo.textoDoStatus(ultimoStatus), enabled: false },
    ...(novaVersao ? [{ label: `Atualizar a Vênus para a versão 1.${novaVersao.numero}`, click: () => void instalarAtualizacao() }] : []),
    { label: "Verificar atualizações", click: () => void conferirAtualizacao(true) },
    { label: MAC ? "Iniciar com o Mac" : "Iniciar com o Windows", type: "checkbox", checked: iniciar, click: (i) => definirIniciar(i.checked) },
    { type: "separator" },
    { label: "Sair", click: () => { saindo = true; app.quit(); } },
  ]));
  bandeja.setToolTip(statusModulo.textoDoStatus(ultimoStatus));
}

function definirIniciar(ligado) {
  app.setLoginItemSettings({ openAtLogin: ligado, args: ligado ? ["--oculto"] : [] });
  atualizarMenu();
  return app.getLoginItemSettings().openAtLogin;
}

function notificar(titulo, texto) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title: titulo, body: texto, icon: path.join(__dirname, "build", "icon.png") });
  n.on("click", mostrar);
  n.show();
}

async function medir(monitor) {
  const t0 = Date.now();
  let ok = false;
  try {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 6000);
    const r = await fetch(`${SERVIDOR}/saude`, { signal: ctl.signal, cache: "no-store" });
    clearTimeout(timer);
    ok = r.ok;
  } catch { ok = false; }
  const r = monitor.registrar(ok, ok ? Date.now() - t0 : null);
  ultimoStatus = { online: r.online, ms: r.ms, quando: Date.now() };
  if (r.mudou) atualizarMenu();
  if (janela) janela.webContents.send("venus:status", ultimoStatus);
  if (r.aviso === "caiu") notificar("Vênus sem conexão", "Não consegui falar com o servidor. Vou avisar quando voltar.");
  if (r.aviso === "voltou") notificar("Vênus conectada de novo", "A conexão com o servidor voltou.");
}

app.whenReady().then(async () => {
  statusModulo = await import(pathToFileURL(path.join(__dirname, "status.mjs")).href);
  computador = await import(pathToFileURL(path.join(__dirname, "computador.mjs")).href);
  atualizador = await import(pathToFileURL(path.join(__dirname, "atualizador.mjs")).href);
  app.setAppUserModelId("tech.vetorial.venus"); // necessário para as notificações do Windows

  // app://venus/... serve só a pasta do app (sem sair dela)
  protocol.handle("app", (req) => {
    const rel = decodeURIComponent(new URL(req.url).pathname).replace(/^\/+/, "") || "index.html";
    const arquivo = path.normalize(path.join(RAIZ_APP, rel));
    if (!arquivo.startsWith(RAIZ_APP + path.sep)) return new Response("Não encontrado", { status: 404 });
    return net.fetch(pathToFileURL(arquivo).href);
  });

  const oculta = process.argv.includes("--oculto");
  criarJanela(oculta);
  montarBandeja();

  if (!globalShortcut.register(ATALHO, () => { mostrar(); if (janela) janela.webContents.send("venus:atalho"); })) {
    notificar("Atalho indisponível", `${NOME_ATALHO} já é usado por outro programa.`);
  }

  const monitor = statusModulo.criarMonitor();
  void medir(monitor);
  setInterval(() => void medir(monitor), INTERVALO_MS);

  // atualização sozinha: confere pouco depois de abrir e a cada 6 horas (só no app instalado)
  if (app.isPackaged) {
    setTimeout(() => void conferirAtualizacao(false), 30_000);
    setInterval(() => void conferirAtualizacao(false), 6 * 3600_000);
  }
});

// ------------------------------------------------------------------ atualização
async function infoDoServidor() {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15_000);
  try {
    const r = await fetch(`${SERVIDOR}/atualizacao?app=${MAC ? "mac" : "windows"}`, { signal: ctl.signal, cache: "no-store" });
    if (!r.ok) return null;
    return atualizador.lerInfo(await r.json());
  } finally { clearTimeout(timer); }
}

/** Pergunta ao servidor. Devolve { atual, disponivel, nova, notas } ou { erro }. */
async function verificarAtualizacao() {
  try {
    const info = await infoDoServidor();
    const atual = app.getVersion();
    if (!info) return { atual, disponivel: false, erro: "Não consegui consultar as atualizações agora." };
    const disponivel = atualizador.temNovaVersao(atual, info.numero);
    novaVersao = disponivel ? info : null;
    atualizarMenu();
    return { atual, disponivel, nova: `1.${info.numero}`, notas: info.notas };
  } catch {
    return { atual: app.getVersion(), disponivel: false, erro: "Sem conexão para consultar as atualizações." };
  }
}

/** Baixa o instalador (conferindo tamanho e hash), roda em modo silencioso e fecha a Vênus para ela ser trocada. */
async function instalarAtualizacao() {
  if (instalando) return { ok: false, erro: "Já estou atualizando." };
  if (MAC) { // sem assinatura da Apple não dá para trocar o app sozinha: abre o download e a pessoa arrasta para Aplicativos
    void shell.openExternal(`${SERVIDOR}/venus/mac`);
    return { ok: true };
  }
  instalando = true;
  const avisar = (p) => { if (janela) janela.webContents.send("venus:atualizacao-progresso", p); };
  try {
    const info = await infoDoServidor(); // o link do GitHub vale poucos minutos: pede de novo na hora de baixar
    if (!info || !atualizador.temNovaVersao(app.getVersion(), info.numero)) return { ok: false, erro: "A Vênus já está na versão mais nova." };
    const arquivo = path.join(os.tmpdir(), `VenusSetup-${info.numero}.exe`);
    const r = await net.fetch(info.url);
    if (!r.ok || !r.body) return { ok: false, erro: "O download não começou. Tente de novo em instantes." };
    const total = Number(r.headers.get("content-length")) || info.tamanho || 0;
    const hash = crypto.createHash("sha256");
    const saida = fs.createWriteStream(arquivo);
    let baixado = 0;
    const leitor = r.body.getReader();
    for (;;) {
      const { done, value } = await leitor.read();
      if (done) break;
      baixado += value.length;
      if (baixado > atualizador.LIMITE_DE_BYTES) { saida.destroy(); fs.rmSync(arquivo, { force: true }); return { ok: false, erro: "Arquivo grande demais." }; }
      hash.update(value);
      if (!saida.write(value)) await new Promise((ok) => saida.once("drain", ok));
      avisar({ baixado, total });
    }
    await new Promise((ok, falha) => { saida.on("error", falha); saida.end(ok); });
    if (!atualizador.arquivoConfere(info, baixado, hash.digest("hex"))) {
      fs.rmSync(arquivo, { force: true });
      return { ok: false, erro: "O arquivo baixado não confere. Não instalei, por segurança." };
    }
    const filho = spawn(arquivo, atualizador.argsDoInstalador(), { detached: true, stdio: "ignore" });
    filho.on("error", () => {});
    filho.unref();
    saindo = true;
    setTimeout(() => app.quit(), 800); // solta os arquivos para o instalador trocar
    return { ok: true };
  } catch {
    return { ok: false, erro: "Não consegui atualizar agora. Tente de novo mais tarde." };
  } finally {
    instalando = false;
  }
}

/** [manual]: o botão "Verificar atualizações" (sempre responde). Sozinha: pergunta uma vez por versão. */
async function conferirAtualizacao(manual) {
  const r = await verificarAtualizacao();
  if (!r.disponivel) {
    if (manual) void dialog.showMessageBox({ type: r.erro ? "warning" : "info", title: "Vênus", message: r.erro || `A Vênus já está na versão mais nova (${r.atual}).`, buttons: ["OK"] });
    return;
  }
  if (!manual && jaPerguntou === novaVersao.numero) return;
  jaPerguntou = novaVersao.numero;
  const alvo = janela && janela.isVisible() ? janela : undefined;
  const { response } = await dialog.showMessageBox(alvo, {
    type: "question", title: "Atualização da Vênus", buttons: [MAC ? "Baixar agora" : "Atualizar agora", "Depois"], defaultId: 0, cancelId: 1,
    message: `Tem uma versão nova da Vênus (${r.nova}).`,
    detail: (r.notas ? r.notas + "\n\n" : "") + (MAC ? "Vou abrir o download. Depois de baixar, abra o arquivo e arraste a Vênus para a pasta Aplicativos, trocando a antiga." : "A Vênus fecha, atualiza sozinha e abre de novo em alguns segundos."),
  });
  if (response === 0) {
    const i = await instalarAtualizacao();
    if (!i.ok) void dialog.showMessageBox({ type: "warning", title: "Vênus", message: i.erro, buttons: ["OK"] });
  }
}

// Catálogo público da loja: só https, só o caminho /_api/catalog, com limite de tempo e de tamanho.
ipcMain.handle("venus:catalogo", async (_e, site) => {
  let alvo;
  try { alvo = new URL(String(site)); } catch { throw new Error("site inválido"); }
  if (alvo.protocol !== "https:") throw new Error("site precisa ser https");
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15000);
  try {
    const r = await net.fetch(`${alvo.origin}/_api/catalog`, { signal: ctl.signal });
    if (!r.ok) throw new Error(`catalogo ${r.status}`);
    const corpo = await r.text();
    if (corpo.length > 3_000_000) throw new Error("catalogo grande demais");
    return corpo;
  } finally { clearTimeout(timer); }
});
ipcMain.handle("venus:atualizacao-verificar", () => verificarAtualizacao());
ipcMain.handle("venus:atualizacao-instalar", () => instalarAtualizacao());

/** Executa uma ação já validada pelo computador.mjs (sempre de uma lista fixa, sem shell com texto do usuário). */
function executar(exec) {
  if (!exec) return false;
  if (exec.tipo === "url") { void shell.openExternal(exec.url); return true; }
  try {
    const filho = spawn(exec.arquivo, exec.args, { detached: true, stdio: "ignore", windowsHide: true });
    filho.on("error", () => {});
    filho.unref();
    return true;
  } catch { return false; }
}

/** "abrir calculadora", "aumentar volume", "desligar"… Devolve { tratado:false } quando é conversa comum. */
ipcMain.handle("venus:comando", (_e, texto) => {
  if (MAC || !computador || typeof texto !== "string") return { tratado: false }; // os comandos do computador (programas, volume, bloquear) ainda são só do Windows
  if (pendente && Date.now() > pendente.expira) pendente = null;
  if (pendente) {
    const p = pendente;
    if (computador.ehConfirmacao(texto)) {
      pendente = null;
      const ok = executar(computador.montarExecucao(p));
      return { tratado: true, texto: ok ? computador.respostaDa(p) : "Não consegui fazer isso agora." };
    }
    if (computador.ehNegacao(texto)) { pendente = null; return { tratado: true, texto: "Tudo bem, não fiz nada." }; }
    pendente = null; // falou de outra coisa: a pergunta caduca
  }
  const a = computador.interpretar(texto);
  if (!a) return { tratado: false };
  if (a.acao === "perigosa") {
    pendente = { ...a, expira: Date.now() + 30_000 };
    return { tratado: true, texto: computador.perguntaDeConfirmacao(a.tipo) };
  }
  const exec = computador.montarExecucao(a);
  if (!exec) return { tratado: true, texto: computador.respostaDa(a) };
  return { tratado: true, texto: executar(exec) ? computador.respostaDa(a) : "Não consegui fazer isso agora." };
});

/** Identificador fixo do computador (sobrevive a desinstalar e limpar dados): é o que impede teste grátis repetido. Só o hash sai daqui. */
function idDoComputador() {
  const crypto = require("node:crypto");
  let base = "";
  try {
    if (MAC) {
      const out = require("node:child_process").execFileSync("ioreg", ["-rd1", "-c", "IOPlatformExpertDevice"], { encoding: "utf8", timeout: 4000 });
      base = (/"IOPlatformUUID"\s*=\s*"([^"]+)"/.exec(out) || [])[1] || "";
    } else {
    const out = require("node:child_process").execFileSync("reg", ["query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"], { encoding: "utf8", windowsHide: true, timeout: 4000 });
    base = (/MachineGuid\s+REG_SZ\s+(\S+)/.exec(out) || [])[1] || "";
    }
  } catch { /* fora do Windows ou sem permissão */ }
  if (!base) base = `${require("node:os").hostname()}|${(require("node:os").cpus()[0] || {}).model || ""}`;
  return "pc-" + crypto.createHash("sha256").update(`venus-pc:${base}`).digest("hex").slice(0, 40);
}
const ID_DO_COMPUTADOR = idDoComputador();
ipcMain.on("venus:id-do-computador", (e) => { e.returnValue = ID_DO_COMPUTADOR; });

/** Limpeza: apaga tudo o que a Vênus guardou neste computador (login, conversas, cache) e abre limpa. */
ipcMain.handle("venus:limpar-dados", async () => {
  const { response } = await dialog.showMessageBox({ type: "warning", title: "Vênus", message: "Limpar os dados da Vênus neste computador?", detail: "Você sai da conta e as conversas guardadas aqui são apagadas. O que está na sua conta Vetorial ID continua lá.", buttons: ["Limpar e reiniciar", "Cancelar"], defaultId: 1, cancelId: 1 });
  if (response !== 0) return { ok: false };
  try {
    await session.defaultSession.clearStorageData();
    await session.defaultSession.clearCache();
  } catch (e) { return { ok: false, erro: String(e && e.message || e) }; }
  saindo = true;
  app.relaunch();
  app.exit(0);
  return { ok: true };
});

/** Desinstalar: abre o desinstalador do Windows da própria Vênus (o de "Aplicativos instalados") e fecha o app. */
ipcMain.handle("venus:desinstalar", async () => {
  const { response } = await dialog.showMessageBox({ type: "warning", title: "Vênus", message: "Desinstalar a Vênus deste computador?", detail: "Para instalar de novo depois, baixe o instalador no Painel Dev ou no site. Sua conta e seus dados na nuvem não são apagados.", buttons: ["Desinstalar", "Cancelar"], defaultId: 1, cancelId: 1 });
  if (response !== 0) return { ok: false };
  if (MAC) return { ok: false, erro: "No Mac, feche a Vênus e arraste o app da pasta Aplicativos para a Lixeira." };
  const pasta = require("node:path").dirname(process.execPath);
  const alvo = require("node:fs").readdirSync(pasta).find((f) => /^Uninstall .*\.exe$/i.test(f));
  if (!alvo) return { ok: false, erro: "Não achei o desinstalador. Use Configurações do Windows → Aplicativos → Vênus → Desinstalar." };
  try {
    const f = spawn(require("node:path").join(pasta, alvo), [], { detached: true, stdio: "ignore" });
    f.on("error", () => {});
    f.unref();
  } catch (e) { return { ok: false, erro: String(e && e.message || e) }; }
  saindo = true;
  setTimeout(() => app.quit(), 400);
  return { ok: true };
});

ipcMain.on("venus:versao", (e) => { e.returnValue = app.getVersion(); });
ipcMain.on("venus:notificar", (_e, titulo, texto) => notificar(titulo, texto));
ipcMain.on("venus:pedir-status", (e) => e.sender.send("venus:status", ultimoStatus));
ipcMain.handle("venus:iniciar-com-sistema", (_e, ligado) => definirIniciar(ligado));
ipcMain.handle("venus:ler-iniciar-com-sistema", () => app.getLoginItemSettings().openAtLogin);
ipcMain.on("venus:estado", (_e, estado) => {
  if (bandeja && estado && typeof estado.pendencias === "number") bandeja.setToolTip(`${statusModulo.textoDoStatus(ultimoStatus)} · ${estado.pendencias} pendência(s)`);
});

app.on("before-quit", () => { saindo = true; });
app.on("will-quit", () => globalShortcut.unregisterAll());
// com a janela escondida na bandeja o app continua vivo: não sai quando todas as janelas fecham
app.on("window-all-closed", () => {});
app.on("activate", () => mostrar()); // clique no ícone do Dock
