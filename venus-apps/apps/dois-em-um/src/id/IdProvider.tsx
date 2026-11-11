import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { criarApi, ErroId, type Aparelho, type ApiId, type Sessao } from "./api";
import { detectarPlataforma, NOMES_DE_PLATAFORMA } from "../ajustes/logica";
import { noDesktop } from "../desktop/ponte";

const CHAVE_SESSAO = "venus:id";
const CHAVE_APARELHO = "venus:aparelho";
const PING_MS = 60_000;

/** Identificador fixo deste aparelho (gerado uma vez) + plataforma detectada. */
export function meuAparelho(): Aparelho {
  let id = "";
  try {
    id = localStorage.getItem(CHAVE_APARELHO) ?? "";
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem(CHAVE_APARELHO, id);
    }
  } catch {
    id = crypto.randomUUID();
  }
  const plataforma = detectarPlataforma(navigator.userAgent, { desktop: noDesktop(), nativoAndroid: !!(globalThis as any).Capacitor?.isNativePlatform?.() });
  return { id, plataforma, nome: NOMES_DE_PLATAFORMA[plataforma] };
}

export interface Conflito {
  ativas: { sessao: string; nome: string; plataforma: string }[];
}

interface Ctx {
  api: ApiId;
  sessao: Sessao | null;
  conflito: Conflito | null;
  aviso: string;
  entrar: (email: string, senha: string, extra?: { encerrar?: string; remover?: string }) => Promise<void>;
  cadastrar: (email: string, senha: string, nome: string) => Promise<void>;
  sair: () => Promise<void>;
  /** Resposta ao aviso de 2 aparelhos ativos: encerrar uma das sessões, ou cancelar (sai deste aparelho). */
  resolverConflito: (encerrar?: string) => Promise<void>;
  limparAviso: () => void;
}
const C = createContext<Ctx | null>(null);

function lerSessao(): Sessao | null {
  try {
    const b = localStorage.getItem(CHAVE_SESSAO);
    return b ? (JSON.parse(b) as Sessao) : null;
  } catch {
    return null;
  }
}

export function IdProvider({ children, api: apiFora }: { children: ReactNode; api?: ApiId }) {
  const api = useMemo(() => apiFora ?? criarApi(), [apiFora]);
  const [sessao, setSessao] = useState<Sessao | null>(lerSessao);
  const [conflito, setConflito] = useState<Conflito | null>(null);
  const [aviso, setAviso] = useState("");
  const ref = useRef(sessao);
  ref.current = sessao;

  const guardar = useCallback((s: Sessao | null) => {
    setSessao(s);
    try {
      if (s) localStorage.setItem(CHAVE_SESSAO, JSON.stringify(s));
      else localStorage.removeItem(CHAVE_SESSAO);
    } catch {
      /* sem armazenamento: vale só nesta sessão */
    }
  }, []);

  const entrar = useCallback<Ctx["entrar"]>(async (email, senha, extra) => guardar(await api.entrar(email, senha, meuAparelho(), extra)), [api, guardar]);
  const cadastrar = useCallback<Ctx["cadastrar"]>(async (e, s, n) => guardar(await api.cadastrar(e, s, n, meuAparelho())), [api, guardar]);
  const sair = useCallback(async () => {
    const t = ref.current?.token;
    guardar(null);
    if (t) await api.sair(t).catch(() => undefined);
  }, [api, guardar]);

  // Batimento: mantém a sessão "viva" e descobre na hora se ela foi encerrada em outro aparelho.
  useEffect(() => {
    if (!sessao) return;
    let parar = false;
    const bater = async () => {
      const atual = ref.current;
      if (!atual || parar) return;
      try {
        await api.ping(atual.token);
        setConflito(null);
      } catch (e) {
        if (!(e instanceof ErroId)) return;
        if (e.codigo === "limite_simultaneo") setConflito({ ativas: e.dados.ativas ?? [] });
        else if (e.status === 401) {
          guardar(null);
          setAviso(e.codigo === "sessao_encerrada" ? "Esta sessão foi encerrada em outro aparelho. Entre de novo." : "Entre de novo com o seu Vetorial ID.");
        }
      }
    };
    void bater();
    const t = setInterval(bater, PING_MS);
    const aoVoltar = () => document.visibilityState === "visible" && void bater();
    document.addEventListener("visibilitychange", aoVoltar);
    return () => {
      parar = true;
      clearInterval(t);
      document.removeEventListener("visibilitychange", aoVoltar);
    };
  }, [api, sessao?.token, guardar]);

  // Renovação do token (30 dias de validade): uma vez por dia de uso.
  useEffect(() => {
    if (!sessao) return;
    const chave = "venus:id:renovado";
    try {
      if (Date.now() - Number(localStorage.getItem(chave) ?? 0) < 86_400_000) return;
    } catch {
      return;
    }
    api.renovar(sessao.token).then((r) => {
      guardar({ ...sessao, token: r.token });
      try { localStorage.setItem(chave, String(Date.now())); } catch { /* ok */ }
    }).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, sessao?.sessao]);

  const resolverConflito = useCallback(async (encerrar?: string) => {
    const t = ref.current?.token;
    if (!t) return;
    if (!encerrar) {
      await sair();
      setConflito(null);
      return;
    }
    await api.reativar(t, encerrar);
    setConflito(null);
  }, [api, sair]);

  const valor = useMemo<Ctx>(() => ({ api, sessao, conflito, aviso, entrar, cadastrar, sair, resolverConflito, limparAviso: () => setAviso("") }),
    [api, sessao, conflito, aviso, entrar, cadastrar, sair, resolverConflito]);
  return <C.Provider value={valor}>{children}</C.Provider>;
}

export function useId(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useId precisa estar dentro de <IdProvider>");
  return c;
}
