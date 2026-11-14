import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { usePersistido } from "../hooks/usePersistido";
import { ponteDesktop } from "../desktop/ponte";
import { useEmpresa } from "./EmpresaProvider";
import { normalizarSite } from "./logica";
import { lerCatalogo, type ProdutoDaLoja } from "./catalogo";

type Estado = "parado" | "atualizando" | "ok" | "erro";
interface Guardado { site: string; t: number; itens: ProdutoDaLoja[] }
interface Ctx {
  produtos: ProdutoDaLoja[];
  estado: Estado;
  /** Devolve o catálogo; se o guardado for mais velho que `idadeMaxMs` (ou não existir), busca no site antes. */
  garantir: (idadeMaxMs?: number) => Promise<ProdutoDaLoja[]>;
}
const C = createContext<Ctx | null>(null);
const RENOVAR_MS = 15 * 60_000;

async function buscar(site: string): Promise<ProdutoDaLoja[]> {
  const ponte = ponteDesktop();
  const corpo = ponte?.buscarCatalogo
    ? await ponte.buscarCatalogo(site)
    : await (await fetch(`${site}/_api/catalog`, { signal: AbortSignal.timeout(15_000) })).text();
  const itens = lerCatalogo(corpo);
  if (!itens.length) throw new Error("catalogo vazio");
  return itens;
}

/** Mantém o catálogo da loja no computador: busca ao abrir, renova sozinho e deixa a última cópia para quando estiver sem internet. */
export function CatalogoProvider({ children }: { children: ReactNode }) {
  const { empresa } = useEmpresa();
  const site = normalizarSite(empresa.site);
  const [guardado, setGuardado] = usePersistido<Guardado | null>("venus:catalogo-loja", null);
  const [estado, setEstado] = useState<Estado>("parado");
  const voo = useRef<Promise<ProdutoDaLoja[]> | null>(null);
  const atual = useRef<Guardado | null>(null);
  atual.current = guardado && guardado.site === site ? guardado : null;

  const atualizar = useCallback((): Promise<ProdutoDaLoja[]> => {
    if (!site) return Promise.resolve([]);
    if (voo.current) return voo.current;
    setEstado("atualizando");
    const p = buscar(site)
      .then((itens) => { setGuardado({ site, t: Date.now(), itens }); setEstado("ok"); return itens; })
      .catch(() => { setEstado("erro"); return atual.current?.itens ?? []; })
      .finally(() => { voo.current = null; });
    voo.current = p;
    return p;
  }, [site, setGuardado]);

  const garantir = useCallback((idadeMaxMs = RENOVAR_MS) => {
    const g = atual.current;
    if (g && Date.now() - g.t <= idadeMaxMs) return Promise.resolve(g.itens);
    return atualizar();
  }, [atualizar]);

  useEffect(() => {
    if (!site) return;
    if (!atual.current || Date.now() - atual.current.t > RENOVAR_MS) void atualizar();
    const t = setInterval(() => void atualizar(), RENOVAR_MS);
    const aoVoltar = () => document.visibilityState === "visible" && atual.current && Date.now() - atual.current.t > RENOVAR_MS && void atualizar();
    document.addEventListener("visibilitychange", aoVoltar);
    return () => { clearInterval(t); document.removeEventListener("visibilitychange", aoVoltar); };
  }, [site, atualizar]);

  const produtos = atual.current?.itens ?? [];
  const v = useMemo(() => ({ produtos, estado, garantir }), [guardado, site, estado, garantir]); // eslint-disable-line react-hooks/exhaustive-deps
  return <C.Provider value={v}>{children}</C.Provider>;
}

export function useCatalogo(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useCatalogo precisa estar dentro de <CatalogoProvider>");
  return c;
}
