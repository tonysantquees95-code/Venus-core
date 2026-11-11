// Monitor de conexão com os Workers (lógica pura): precisa de 2 falhas seguidas para dar o aviso de queda.
const FALHAS_PARA_CAIR = 2;

export function criarMonitor() {
  let online = null; // desconhecido até a primeira medição
  let falhas = 0;
  return {
    /** Registra uma medição e diz se o estado mudou e se deve avisar: "caiu" | "voltou" | null. */
    registrar(ok, ms = null) {
      if (ok) {
        const voltou = online === false;
        falhas = 0;
        const mudou = online !== true;
        online = true;
        return { online: true, ms, mudou, aviso: voltou ? "voltou" : null };
      }
      falhas++;
      if (falhas >= FALHAS_PARA_CAIR && online !== false) {
        const tinhaSido = online === true;
        online = false;
        return { online: false, ms: null, mudou: true, aviso: tinhaSido ? "caiu" : null };
      }
      return { online: online === true, ms: null, mudou: false, aviso: null };
    },
    estado: () => online,
  };
}

export const textoDoStatus = (s) => (s.online ? `Vênus conectada${s.ms != null ? ` (${s.ms} ms)` : ""}` : "Vênus sem conexão com o servidor");

/** Só deixa abrir dentro do app o que é do próprio app; o resto vai para o navegador. */
export const ehDoApp = (url) => url.startsWith("app://venus/");
export const linkExterno = (url) => /^https:\/\//i.test(url);
