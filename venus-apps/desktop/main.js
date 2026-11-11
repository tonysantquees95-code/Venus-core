// Vênus para Windows: janela do dashboard + bandeja do sistema + atalho global + notificações nativas
// + monitor de conexão com os Workers. A interface é o mesmo app React (Dois em Um) dos outros aparelhos.
const { app, BrowserWindow, Tray, Menu, Notification, globalShortcut, ipcMain, nativeImage, protocol, net, shell } = require("electron");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { pathToFileURL } = require("node:url");

const SERVIDOR = (process.env.VENUS_SERVIDOR || "https://venus-servidor.tonysantquees95.workers.dev").replace(/\/+$/, "");
const ATALHO = "CommandOrControl+Alt+V";
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
    icon: path.join(__dirname, "build", "icon.ico"),
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
    { label: `Chamar de qualquer tela: ${ATALHO.replace("CommandOrControl", "Ctrl")}`, enabled: false },
    { type: "separator" },
    { label: statusModulo.textoDoStatus(ultimoStatus), enabled: false },
    { label: "Iniciar com o Windows", type: "checkbox", checked: iniciar, click: (i) => definirIniciar(i.checked) },
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
    notificar("Atalho indisponível", `${ATALHO.replace("CommandOrControl", "Ctrl")} já é usado por outro programa.`);
  }

  const monitor = statusModulo.criarMonitor();
  void medir(monitor);
  setInterval(() => void medir(monitor), INTERVALO_MS);
});

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
  if (!computador || typeof texto !== "string") return { tratado: false };
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
