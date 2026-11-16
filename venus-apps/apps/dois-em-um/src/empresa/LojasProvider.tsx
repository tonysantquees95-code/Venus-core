import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { definirLojaAberta, ErroId, type LojaDaConta } from "../id/api";
import { useId } from "../id/IdProvider";

const CHAVE = "venus:loja-aberta";
const lerAberta = () => { try { return localStorage.getItem(CHAVE) ?? ""; } catch { return ""; } };
const gravarAberta = (v: string) => { try { if (v) localStorage.setItem(CHAVE, v); else localStorage.removeItem(CHAVE); } catch { /* vale só nesta sessão */ } };

interface Ctx {
  lojas: LojaDaConta[];
  max: number;
  /** Id da loja aberta; "" é a loja principal da conta. */
  abertaId: string;
  aberta: LojaDaConta | null;
  /** Sufixo das chaves guardadas no aparelho e nome do cadastro na conta: cada loja tem o seu. */
  sufixo: string;
  chaveDaEmpresa: string;
  trocar: (id: string) => void;
  criar: (nome: string) => Promise<LojaDaConta>;
  renomear: (nome: string) => Promise<void>;
  recarregar: () => Promise<void>;
  erro: string;
}
const C = createContext<Ctx | null>(null);

/** Até 3 lojas por conta (outro site, outro nicho), cada uma com cadastro, identidade, produtos, vitrine e painel próprios. */
export function LojasProvider({ children }: { children: ReactNode }) {
  const { api, sessao } = useId();
  const token = sessao?.token;
  const [abertaId, setAbertaId] = useState(() => { const v = lerAberta(); definirLojaAberta(v); return v; });
  const [lojas, setLojas] = useState<LojaDaConta[]>([]);
  const [max, setMax] = useState(3);
  const [erro, setErro] = useState("");
  const ref = useRef(abertaId);
  ref.current = abertaId;

  const trocar = useCallback((id: string) => {
    definirLojaAberta(id); gravarAberta(id); setAbertaId(id);
  }, []);

  const recarregar = useCallback(async () => {
    if (!token) { setLojas([]); return; }
    for (let tentativa = 0; tentativa < 2; tentativa++) {
      try {
        const r = await api.lojas(token);
        setLojas(r.lojas); setMax(r.max); setErro("");
        const principal = r.lojas.find((l) => l.principal)?.id ?? "";
        if (ref.current && (ref.current === principal || !r.lojas.some((l) => l.id === ref.current))) trocar("");
        return;
      } catch (e) {
        if (e instanceof ErroId && e.codigo === "loja_invalida" && ref.current) { trocar(""); continue; } // a loja guardada não é desta conta
        setErro(e instanceof Error ? e.message : "Não consegui ler as suas lojas.");
        return;
      }
    }
  }, [api, token, trocar]);
  useEffect(() => { void recarregar(); }, [recarregar]);

  const criar = useCallback(async (nome: string) => {
    if (!token) throw new Error("Entre com o seu Vetorial ID.");
    const n = await api.criarLoja(token, { nome });
    await recarregar();
    trocar(n.id);
    return { id: n.id, nome: n.nome, slug: n.slug, titulo: "", cor: "", principal: false, ativa: true };
  }, [api, token, recarregar, trocar]);
  const renomear = useCallback(async (nome: string) => { if (token) { await api.renomearLoja(token, nome); await recarregar(); } }, [api, token, recarregar]);

  const v = useMemo<Ctx>(() => ({
    lojas, max, abertaId, aberta: lojas.find((l) => (abertaId ? l.id === abertaId : l.principal)) ?? null,
    sufixo: abertaId ? `:${abertaId}` : "", chaveDaEmpresa: abertaId ? `empresa-${abertaId}` : "empresa",
    trocar, criar, renomear, recarregar, erro
  }), [lojas, max, abertaId, trocar, criar, renomear, recarregar, erro]);
  return <C.Provider value={v}>{children}</C.Provider>;
}

export function useLojas(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useLojas precisa estar dentro de <LojasProvider>");
  return c;
}
