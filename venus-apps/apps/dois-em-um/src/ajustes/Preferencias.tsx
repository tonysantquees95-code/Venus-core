import { createContext, useCallback, useContext, useMemo, type ReactNode } from "react";
import { usePersistido } from "../hooks/usePersistido";
import { limparPreferencias, PREFERENCIAS_PADRAO, type Preferencias } from "./logica";
import { ponteDesktop } from "../desktop/ponte";

interface Ctx {
  prefs: Preferencias;
  definir: <K extends keyof Preferencias>(chave: K, valor: Preferencias[K]) => void;
  definirTodas: (p: Preferencias) => void;
}
const C = createContext<Ctx | null>(null);

export function PreferenciasProvider({ children }: { children: ReactNode }) {
  const [bruto, setBruto] = usePersistido<unknown>("venus:prefs", PREFERENCIAS_PADRAO);
  const prefs = useMemo(() => limparPreferencias(bruto), [bruto]);
  const definir = useCallback<Ctx["definir"]>((chave, valor) => {
    setBruto({ ...prefs, [chave]: valor });
    if (chave === "iniciarComOSistema") void ponteDesktop()?.definirIniciarComOSistema(valor as boolean);
  }, [prefs, setBruto]);
  const definirTodas = useCallback((p: Preferencias) => setBruto(p), [setBruto]);
  const v = useMemo(() => ({ prefs, definir, definirTodas }), [prefs, definir, definirTodas]);
  return <C.Provider value={v}>{children}</C.Provider>;
}

export function usePreferencias(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("usePreferencias precisa estar dentro de <PreferenciasProvider>");
  return c;
}
