import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Conversa, Mensagem } from "../tipos";
import {
  abrirConversa, anexarMensagem, apagarTudo, conversaAtiva, estadoInicial, excluirConversa, iniciarNova,
  paraSalvar, REABRIR_APOS_MS, salvar, type Armazenamento, type Estado
} from "./logica";

function armazenamento(): Armazenamento | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

interface Contexto {
  /** Conversa em uso agora. Ao abrir o app é SEMPRE uma conversa nova e vazia. */
  ativa: Conversa;
  /** Histórico salvo (só conversas com mensagem), da mais recente para a mais antiga. */
  historico: Conversa[];
  novaConversa: () => void;
  abrir: (id: string) => void;
  excluir: (id: string) => void;
  apagarHistorico: () => void;
  anexar: (id: string, m: Mensagem) => void;
  estaEnviando: (id: string) => boolean;
  marcarEnviando: (id: string, sim: boolean) => void;
}

const Ctx = createContext<Contexto | null>(null);

export function ConversasProvider({ children }: { children: ReactNode }) {
  // Lazy: roda uma vez por abertura do app e cria a conversa limpa. O id da ativa nunca é lido do disco.
  const [estado, setEstado] = useState<Estado>(() => estadoInicial(armazenamento()));
  const [enviando, setEnviando] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => salvar(armazenamento(), estado), [estado]);

  // Reabertura: voltou ao app depois de ficar fora (ou restaurado do cache de páginas) -> conversa nova.
  const novaConversa = useCallback(() => setEstado((e) => iniciarNova(e)), []);
  useEffect(() => {
    let saiuEm: number | null = null;
    const aoMudarVisibilidade = () => {
      if (document.visibilityState === "hidden") {
        saiuEm = Date.now();
      } else if (saiuEm !== null) {
        const fora = Date.now() - saiuEm;
        saiuEm = null;
        if (fora >= REABRIR_APOS_MS) novaConversa();
      }
    };
    const aoRestaurar = (ev: PageTransitionEvent) => {
      if (ev.persisted) novaConversa();
    };
    document.addEventListener("visibilitychange", aoMudarVisibilidade);
    window.addEventListener("pageshow", aoRestaurar);
    return () => {
      document.removeEventListener("visibilitychange", aoMudarVisibilidade);
      window.removeEventListener("pageshow", aoRestaurar);
    };
  }, [novaConversa]);

  const abrir = useCallback((id: string) => setEstado((e) => abrirConversa(e, id)), []);
  const excluir = useCallback((id: string) => setEstado((e) => excluirConversa(e, id)), []);
  const apagarHistorico = useCallback(() => setEstado(apagarTudo()), []);
  const anexar = useCallback((id: string, m: Mensagem) => setEstado((e) => anexarMensagem(e, id, m)), []);
  const marcarEnviando = useCallback(
    (id: string, sim: boolean) =>
      setEnviando((atual) => {
        const novo = new Set(atual);
        if (sim) novo.add(id);
        else novo.delete(id);
        return novo;
      }),
    []
  );
  const estaEnviando = useCallback((id: string) => enviando.has(id), [enviando]);

  const valor = useMemo<Contexto>(
    () => ({
      ativa: conversaAtiva(estado),
      historico: paraSalvar(estado),
      novaConversa, abrir, excluir, apagarHistorico, anexar, estaEnviando, marcarEnviando
    }),
    [estado, novaConversa, abrir, excluir, apagarHistorico, anexar, estaEnviando, marcarEnviando]
  );
  return <Ctx.Provider value={valor}>{children}</Ctx.Provider>;
}

export function useConversas(): Contexto {
  const c = useContext(Ctx);
  if (!c) throw new Error("useConversas precisa estar dentro de <ConversasProvider>");
  return c;
}
