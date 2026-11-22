import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { criarApi, ErroId, type Aparelho, type DadosDeRecuperacao, type ApiId, type Licenca, type Perfil, type Sessao } from "./api";
import { detectarPlataforma, NOMES_DE_PLATAFORMA } from "../ajustes/logica";
import { noDesktop, ponteDesktop } from "../desktop/ponte";
import { avaliar, type Estado } from "./trava";

const CHAVE_SESSAO = "venus:id";
const CHAVE_APARELHO = "venus:aparelho";
const PING_MS = 20_000;
const CANAL = "venus:conta";
const CHAVE_SELO = "venus:selo";
const CHAVE_VISTO = "venus:selo:visto";

/** Identificador fixo deste aparelho (gerado uma vez) + plataforma detectada. */
export function meuAparelho(): Aparelho {
  let id = ponteDesktop()?.idDoComputador ?? ""; // no Windows o id é do computador, não do app (reinstalar não gera teste novo)
  try {
    if (!id) id = localStorage.getItem(CHAVE_APARELHO) ?? "";
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
  cadastrar: (email: string, senha: string, nome: string, telefone: string, recuperacao?: DadosDeRecuperacao) => Promise<void>;
  /** Trava de segurança: o que o selo de licença assinado autoriza agora. */
  estado: Estado;
  validarAgora: () => Promise<void>;
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

function lerLocal(chave: string): string {
  try { return localStorage.getItem(chave) ?? ""; } catch { return ""; }
}
function gravarLocal(chave: string, v: string) {
  try { localStorage.setItem(chave, v); } catch { /* sem armazenamento: vale só nesta sessão */ }
}

export function IdProvider({ children, api: apiFora }: { children: ReactNode; api?: ApiId }) {
  const api = useMemo(() => apiFora ?? criarApi(), [apiFora]);
  const [sessao, setSessao] = useState<Sessao | null>(lerSessao);
  const [conflito, setConflito] = useState<Conflito | null>(null);
  const [aviso, setAviso] = useState("");
  const [conta, setConta] = useState<{ usuario: Perfil; licenca: Licenca } | null>(null);
  const [selo, setSelo] = useState(() => lerLocal(CHAVE_SELO));
  const [agora, setAgora] = useState(() => Date.now());
  const visto = useRef(Number(lerLocal(CHAVE_VISTO)) || 0);
  const ref = useRef(sessao);
  const versaoDoPerfil = useRef(0);
  ref.current = sessao;

  const aceitarSelo = useCallback((novo?: string) => {
    if (!novo) return;
    setSelo(novo);
    gravarLocal(CHAVE_SELO, novo);
  }, []);

  const guardar = useCallback((s: Sessao | null) => {
    if (s?.selo) { const { selo: novo, ...resto } = s; aceitarSelo(novo); s = resto; }
    if (!s) { setSelo(""); gravarLocal(CHAVE_SELO, ""); }
    setSessao(s);
    try {
      if (s) localStorage.setItem(CHAVE_SESSAO, JSON.stringify(s));
      else localStorage.removeItem(CHAVE_SESSAO);
    } catch {
      /* sem armazenamento: vale só nesta sessão */
    }
  }, [aceitarSelo]);

  const recarregarConta = useCallback(async () => {
    const t = ref.current?.token;
    if (!t) return setConta(null);
    try {
      const c = await api.conta(t);
      versaoDoPerfil.current = c.usuario.atualizado;
      aceitarSelo(c.licenca.selo);
      setConta({ usuario: c.usuario, licenca: c.licenca });
    } catch {
      /* sem conexão: mantém o que já tinha */
    }
  }, [api, aceitarSelo]);

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
  const cadastrar = useCallback<Ctx["cadastrar"]>(async (e, s, n, t, r) => guardar(await api.cadastrar(e, s, n, t, meuAparelho(), r)), [api, guardar]);
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
        aceitarSelo(p.selo);
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
  }, [api, sessao?.token, guardar, recarregarConta, aceitarSelo]);

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

  // Relógio da trava: confere o selo a cada 20 s e lembra o maior horário visto (para pegar quem volta o relógio).
  useEffect(() => {
    const t = setInterval(() => setAgora(Date.now()), 20_000);
    return () => clearInterval(t);
  }, []);
  const estado = useMemo<Estado>(() => avaliar(selo, agora, visto.current, sessao?.usuario.id), [selo, agora, sessao?.usuario.id]);
  useEffect(() => {
    if (estado.tipo === "validar_online" && estado.motivo === "relogio") return; // não grava um horário falso
    if (agora > visto.current) { visto.current = agora; gravarLocal(CHAVE_VISTO, String(agora)); }
  }, [agora, estado]);
  const validarAgora = useCallback(async () => {
    const t = ref.current?.token;
    if (!t) return;
    try {
      const l = await api.licenca(t);
      aceitarSelo(l.selo);
      // o servidor confirmou a hora certa: o relógio deste aparelho volta a valer
      if (Date.now() < visto.current) { visto.current = Date.now(); gravarLocal(CHAVE_VISTO, String(visto.current)); }
      setAgora(Date.now());
    } catch (e) {
      if (e instanceof ErroId && e.status === 401) { guardar(null); setAviso("Entre de novo com o seu Vetorial ID."); }
    }
  }, [api, aceitarSelo, guardar]);
  useEffect(() => {
    if (!sessao || estado.tipo !== "validar_online") return;
    void validarAgora();
    const t = setInterval(() => void validarAgora(), 15_000);
    return () => clearInterval(t);
  }, [sessao?.sessao, estado.tipo, validarAgora]);

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

  const valor = useMemo<Ctx>(() => ({ api, sessao, conflito, aviso, entrar, cadastrar, estado, validarAgora, sair, conta, recarregarConta, salvarPerfil, resolverConflito, limparAviso: () => setAviso("") }),
    [api, sessao, conflito, aviso, entrar, cadastrar, estado, validarAgora, sair, conta, recarregarConta, salvarPerfil, resolverConflito]);
  return <C.Provider value={valor}>{children}</C.Provider>;
}

export function useId(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useId precisa estar dentro de <IdProvider>");
  return c;
}

/**
 * SÓ para a demonstração do site (build com VITE_DEMO=1, que o build normal nunca usa): uma conta de mentira, sem servidor,
 * para a Vênus de verdade abrir dentro do celular da página inicial. O app publicado continua exigindo o selo de licença assinado.
 */
export function IdDemoProvider({ children }: { children: ReactNode }) {
  const valor = useMemo<Ctx>(() => {
    const usuario = { id: "demo", email: "demo@exemplo.com", nome: "Visitante" };
    const nada = async () => undefined;
    return {
      api: {} as ApiId, sessao: { token: "demo", sessao: "demo", usuario }, conflito: null, aviso: "",
      entrar: nada, cadastrar: nada, validarAgora: nada, sair: nada, recarregarConta: nada, salvarPerfil: nada, resolverConflito: nada, limparAviso: () => undefined,
      estado: { tipo: "livre", teste: false, fim: Date.now() + 365 * 86_400_000, diasRestantes: 365 },
      conta: { usuario: { ...usuario, foto: "", atualizado: 1 }, licenca: { acesso: true, plano: "completo", pacote: "completo" } }
    };
  }, []);
  return <C.Provider value={valor}>{children}</C.Provider>;
}
