import { createContext, useCallback, useContext, useMemo, type ReactNode } from "react";
import { usePersistido } from "../hooks/usePersistido";
import { useLojas } from "./LojasProvider";
import { EMPRESA_VAZIA, limparEmpresa, normalizarSite, type Empresa } from "./logica";

interface Ctx {
  empresa: Empresa;
  /** Mudança feita aqui (tela "Minha empresa"): o Sincronizador manda para a conta. */
  salvar: (parcial: Partial<Empresa>) => void;
  /** Cadastro que chegou da conta (celular ou outro computador). */
  definirTudo: (e: Empresa) => void;
}
const C = createContext<Ctx | null>(null);

export function EmpresaProvider({ children }: { children: ReactNode }) {
  const { sufixo } = useLojas(); // cada loja da conta guarda o seu cadastro
  const [bruto, setBruto] = usePersistido<unknown>(`venus:empresa${sufixo}`, EMPRESA_VAZIA);
  const empresa = useMemo(() => limparEmpresa(bruto), [bruto]);
  const salvar = useCallback((p: Partial<Empresa>) => {
    const novo = { ...empresa, ...p };
    if (p.site !== undefined) novo.site = normalizarSite(p.site);
    setBruto(limparEmpresa(novo));
  }, [empresa, setBruto]);
  const definirTudo = useCallback((e: Empresa) => setBruto(e), [setBruto]);
  const v = useMemo(() => ({ empresa, salvar, definirTudo }), [empresa, salvar, definirTudo]);
  return <C.Provider value={v}>{children}</C.Provider>;
}

export function useEmpresa(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useEmpresa precisa estar dentro de <EmpresaProvider>");
  return c;
}
