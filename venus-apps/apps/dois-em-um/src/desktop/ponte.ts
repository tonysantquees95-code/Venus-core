/** Ponte com o app Windows (Electron). No navegador/APK/iPhone ela não existe e tudo cai no padrão da web. */
export interface PonteDesktop {
  versao: string;
  notificar(titulo: string, texto: string): void;
  aoStatusDoServidor(cb: (s: { online: boolean; ms: number | null; quando: number }) => void): () => void;
  aoAtalhoGlobal(cb: () => void): () => void;
  definirIniciarComOSistema(ligado: boolean): Promise<boolean>;
  lerIniciarComOSistema(): Promise<boolean>;
  avisarEstado(estado: { escutando?: boolean; pendencias?: number }): void;
}

export const ponteDesktop = (): PonteDesktop | undefined => (globalThis as { venusDesktop?: PonteDesktop }).venusDesktop;
export const noDesktop = () => !!ponteDesktop();

/** Notificação nativa: Windows pelo app, senão a API da web (se o usuário permitiu). */
export function notificar(titulo: string, texto: string) {
  const p = ponteDesktop();
  if (p) return p.notificar(titulo, texto);
  try {
    if (typeof Notification !== "undefined" && Notification.permission === "granted") new Notification(titulo, { body: texto });
  } catch {
    /* alguns navegadores só aceitam via service worker */
  }
}
