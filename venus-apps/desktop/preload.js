// Ponte segura entre a janela (React) e o Windows: só expõe funções, nunca o Node inteiro.
const { contextBridge, ipcRenderer } = require("electron");

const ouvir = (canal, cb) => {
  const f = (_e, dado) => cb(dado);
  ipcRenderer.on(canal, f);
  return () => ipcRenderer.removeListener(canal, f);
};

contextBridge.exposeInMainWorld("venusDesktop", {
  versao: ipcRenderer.sendSync("venus:versao"),
  notificar: (titulo, texto) => ipcRenderer.send("venus:notificar", String(titulo).slice(0, 80), String(texto).slice(0, 240)),
  aoStatusDoServidor: (cb) => {
    const off = ouvir("venus:status", cb);
    ipcRenderer.send("venus:pedir-status");
    return off;
  },
  aoAtalhoGlobal: (cb) => ouvir("venus:atalho", () => cb()),
  definirIniciarComOSistema: (ligado) => ipcRenderer.invoke("venus:iniciar-com-sistema", !!ligado),
  lerIniciarComOSistema: () => ipcRenderer.invoke("venus:ler-iniciar-com-sistema"),
  comando: (texto) => ipcRenderer.invoke("venus:comando", String(texto).slice(0, 300)),
  verificarAtualizacao: () => ipcRenderer.invoke("venus:atualizacao-verificar"),
  instalarAtualizacao: () => ipcRenderer.invoke("venus:atualizacao-instalar"),
  aoProgressoDaAtualizacao: (cb) => ouvir("venus:atualizacao-progresso", cb),
  buscarCatalogo: (site) => ipcRenderer.invoke("venus:catalogo", String(site).slice(0, 200)),
  loja: {
    estado: () => ipcRenderer.invoke("venus:loja-estado"),
    entrar: (site, email, senha) => ipcRenderer.invoke("venus:loja-entrar", String(site), String(email), String(senha)),
    sair: () => ipcRenderer.invoke("venus:loja-sair"),
    produtos: () => ipcRenderer.invoke("venus:loja-produtos"),
    salvar: (corpo) => ipcRenderer.invoke("venus:loja-salvar", corpo),
    estoque: (id, qtd) => ipcRenderer.invoke("venus:loja-estoque", Number(id), Number(qtd)),
    foto: (base64) => ipcRenderer.invoke("venus:loja-foto", String(base64)),
  },
  idDoComputador: ipcRenderer.sendSync("venus:id-do-computador"),
  limparDados: () => ipcRenderer.invoke("venus:limpar-dados"),
  desinstalar: () => ipcRenderer.invoke("venus:desinstalar"),
  avisarEstado: (estado) => ipcRenderer.send("venus:estado", estado),
});
