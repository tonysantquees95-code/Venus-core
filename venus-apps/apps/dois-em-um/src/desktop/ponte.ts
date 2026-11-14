/** Ponte com o app Windows (Electron). No navegador/APK/iPhone ela não existe e tudo cai no padrão da web. */
export interface PonteDesktop {
  versao: string;
  notificar(titulo: string, texto: string): void;
  aoStatusDoServidor(cb: (s: { online: boolean; ms: number | null; quando: number }) => void): () => void;
  aoAtalhoGlobal(cb: () => void): () => void;
  definirIniciarComOSistema(ligado: boolean): Promise<boolean>;
  lerIniciarComOSistema(): Promise<boolean>;
  /** Pede ao Windows para executar um comando do computador (abrir programa, volume, bloquear…). */
  comando(texto: string): Promise<{ tratado: boolean; texto?: string }>;
  /** Pergunta ao servidor se tem versão nova da Vênus para Windows. */
  verificarAtualizacao(): Promise<{ atual: string; disponivel: boolean; nova?: string; notas?: string; erro?: string }>;
  /** Baixa, confere e instala a versão nova (a Vênus fecha e abre de novo). */
  instalarAtualizacao(): Promise<{ ok: boolean; erro?: string }>;
  aoProgressoDaAtualizacao(cb: (p: { baixado: number; total: number }) => void): () => void;
  /** Busca o catálogo público da loja (`/_api/catalog`) pelo Windows, sem depender de CORS. Devolve o texto da resposta. */
  buscarCatalogo?(site: string): Promise<string>;
  /** Administração da loja (só no Windows): a senha fica cifrada no computador. Toda resposta vem como { ok, dado } ou { ok:false, erro }. */
  loja?: {
    estado(): Promise<RespostaLoja<{ conectada: boolean; email: string; site: string }>>;
    entrar(site: string, email: string, senha: string): Promise<RespostaLoja<{ conectada: boolean; email: string; site: string }>>;
    sair(): Promise<RespostaLoja<unknown>>;
    produtos(): Promise<RespostaLoja<Record<string, unknown>[]>>;
    salvar(corpo: Record<string, unknown>): Promise<RespostaLoja<unknown>>;
    estoque(id: number, quantidade: number): Promise<RespostaLoja<unknown>>;
    foto(base64: string): Promise<RespostaLoja<string>>;
  };
  /** Identificador fixo do computador (hash): continua o mesmo depois de desinstalar ou limpar. */
  idDoComputador: string;
  limparDados(): Promise<{ ok: boolean; erro?: string }>;
  desinstalar(): Promise<{ ok: boolean; erro?: string }>;
  avisarEstado(estado: { escutando?: boolean; pendencias?: number }): void;
}

export type RespostaLoja<T> = { ok: true; dado: T } | { ok: false; erro: string };

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
