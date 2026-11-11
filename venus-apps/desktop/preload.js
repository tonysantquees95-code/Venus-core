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
  avisarEstado: (estado) => ipcRenderer.send("venus:estado", estado),
});
