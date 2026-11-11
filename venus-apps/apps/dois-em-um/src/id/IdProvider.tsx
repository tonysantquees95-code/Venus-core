import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { criarApi, ErroId, type Aparelho, type ApiId, type Licenca, type Perfil, type Sessao } from "./api";
import { detectarPlataforma, NOMES_DE_PLATAFORMA } from "../ajustes/logica";
import { noDesktop } from "../desktop/ponte";

const CHAVE_SESSAO = "venus:id";
const CHAVE_APARELHO = "venus:aparelho";
const PING_MS = 20_000;
const CANAL = "venus:conta";

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
  /** Perfil e assinatura da sessão ativa, atualizados sozinhos (outro app mudou a foto? aparece aqui em segundos). */
  conta: { usuario: Perfil; licenca: Licenca } | null;
  recarregarConta: () => Promise<void>;
  salvarPerfil: (p: { nome?: string; foto?: string }) => Promise<void>;
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
  const [conta, setConta] = useState<{ usuario: Perfil; licenca: Licenca } | null>(null);
  const ref = useRef(sessao);
  const versaoDoPerfil = useRef(0);
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

  const recarregarConta = useCallback(async () => {
    const t = ref.current?.token;
    if (!t) return setConta(null);
    try {
      const c = await api.conta(t);
      versaoDoPerfil.current = c.usuario.atualizado;
      setConta({ usuario: c.usuario, licenca: c.licenca });
    } catch {
      /* sem conexão: mantém o que já tinha */
    }
  }, [api]);

  const salvarPerfil = useCallback<Ctx["salvarPerfil"]>(async (p) => {
    const t = ref.current?.token;
    if (!t) return;
    const r = await api.salvarPerfil(t, p);
    versaoDoPerfil.current = r.usuario.atualizado;
    setConta((c) => (c ? { ...c, usuario: r.usuario } : c));
    try { new BroadcastChannel(CANAL).postMessage("perfil"); } catch { /* sem suporte: os outros apps pegam no próximo batimento */ }
  }, [api]);

  // Entrada vinda de outro app da suíte: #vid=<token do app>
  useEffect(() => {
    const m = /[#&]vid=([^&]+)/.exec(location.hash);
    if (!m || ref.current) return;
    const token = decodeURIComponent(m[1]!);
    history.replaceState(null, "", location.pathname + location.search);
    api.conta(token).then((c) => guardar({ token, sessao: c.sessao, usuario: { id: c.usuario.id, email: c.usuario.email, nome: c.usuario.nome } })).catch(() => setAviso("Não consegui entrar com o Vetorial ID. Tente de novo."));
  }, [api, guardar]);

  // Conta em tempo real: ao entrar, quando o ping avisa que o perfil mudou, e quando outra aba/app do mesmo navegador salva.
  useEffect(() => {
    if (!sessao) { setConta(null); return; }
    void recarregarConta();
    let canal: BroadcastChannel | null = null;
    try { canal = new BroadcastChannel(CANAL); canal.onmessage = () => void recarregarConta(); } catch { /* sem suporte */ }
    const t = setInterval(() => void recarregarConta(), 5 * 60_000); // assinatura (dias restantes)
    return () => { canal?.close(); clearInterval(t); };
  }, [sessao?.sessao, recarregarConta]);

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
        const p = await api.ping(atual.token);
        setConflito(null);
        if (p.perfil !== versaoDoPerfil.current) void recarregarConta();
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
  }, [api, sessao?.token, guardar, recarregarConta]);

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

  const valor = useMemo<Ctx>(() => ({ api, sessao, conflito, aviso, entrar, cadastrar, sair, conta, recarregarConta, salvarPerfil, resolverConflito, limparAviso: () => setAviso("") }),
    [api, sessao, conflito, aviso, entrar, cadastrar, sair, conta, recarregarConta, salvarPerfil, resolverConflito]);
  return <C.Provider value={valor}>{children}</C.Provider>;
}

export function useId(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useId precisa estar dentro de <IdProvider>");
  return c;
}
