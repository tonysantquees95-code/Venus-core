import { createContext, useCallback, useContext, useLayoutEffect, useMemo, useState, type ReactNode } from "react";
import { corSobre, rgbCss, rgbLista, rgbValido } from "./cor";
import { ORBES, PACOTES_ICONES, TEMA_PADRAO } from "./presets";
import type { AlvoCor, Cores, OrbeId, PacoteIconesId, RGB, Tema } from "./tipos";

const CHAVE = "venus:tema";

/** Escreve o tema nas variáveis CSS globais (<html>): vale para o app inteiro, na hora. */
export function aplicarTema(t: Tema) {
  const raiz = document.documentElement;
  const { destaque, secundaria } = t.cores;
  raiz.style.setProperty("--color-venus", rgbCss(destaque));
  raiz.style.setProperty("--color-robo", rgbCss(secundaria));
  raiz.style.setProperty("--venus-rgb", rgbLista(destaque));
  raiz.style.setProperty("--robo-rgb", rgbLista(secundaria));
  raiz.style.setProperty("--color-sobre-venus", corSobre(destaque));
  for (const [nome, valor] of Object.entries(PACOTES_ICONES[t.icones].vars)) raiz.style.setProperty(nome, valor);
  raiz.dataset.orbe = t.orbe;
  raiz.dataset.icones = t.icones;
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", rgbCss(destaque));
}

function lerSalvo(): Tema {
  try {
    const bruto = localStorage.getItem(CHAVE);
    return bruto ? validar(JSON.parse(bruto)) : TEMA_PADRAO;
  } catch {
    return TEMA_PADRAO;
  }
}

export function validar(v: unknown): Tema {
  try {
    const t = (v ?? {}) as Partial<Tema>;
    const orbeOk = ORBES.some((o) => o.id === t.orbe);
    const iconesOk = t.icones !== undefined && t.icones in PACOTES_ICONES;
    return {
      auto: typeof t.auto === "boolean" ? t.auto : TEMA_PADRAO.auto,
      orbe: orbeOk ? (t.orbe as OrbeId) : TEMA_PADRAO.orbe,
      icones: iconesOk ? (t.icones as PacoteIconesId) : TEMA_PADRAO.icones,
      cores: {
        destaque: rgbValido(t.cores?.destaque ?? TEMA_PADRAO.cores.destaque),
        secundaria: rgbValido(t.cores?.secundaria ?? TEMA_PADRAO.cores.secundaria)
      }
    };
  } catch {
    return TEMA_PADRAO;
  }
}

interface ContextoTema {
  tema: Tema;
  definirOrbe: (id: OrbeId) => void;
  definirIcones: (id: PacoteIconesId) => void;
  definirCor: (alvo: AlvoCor, cor: RGB) => void;
  aplicarPreset: (cores: Cores, auto?: boolean) => void;
  /** Tema inteiro vindo da conta (valida cada campo). */
  definirTudo: (t: unknown) => void;
  restaurar: () => void;
}

const Ctx = createContext<ContextoTema | null>(null);

export function ThemingProvider({ children }: { children: ReactNode }) {
  const [tema, setTema] = useState<Tema>(lerSalvo);

  // useLayoutEffect: as variáveis mudam antes da pintura, sem piscar.
  useLayoutEffect(() => {
    aplicarTema(tema);
    try {
      localStorage.setItem(CHAVE, JSON.stringify(tema));
    } catch {
      /* sem armazenamento: vale só nesta sessão */
    }
  }, [tema]);

  const definirOrbe = useCallback((orbe: OrbeId) => setTema((t) => ({ ...t, orbe })), []);
  const definirIcones = useCallback((icones: PacoteIconesId) => setTema((t) => ({ ...t, icones })), []);
  const definirCor = useCallback(
    (alvo: AlvoCor, cor: RGB) => setTema((t) => ({ ...t, cores: { ...t.cores, [alvo]: rgbValido(cor) } })),
    []
  );
  const aplicarPreset = useCallback((cores: Cores, auto = false) => setTema((t) => ({ ...t, cores, auto })), []);
  const definirTudo = useCallback((v: unknown) => setTema(validar(v)), []);
  const restaurar = useCallback(() => setTema(TEMA_PADRAO), []);

  const valor = useMemo(
    () => ({ tema, definirOrbe, definirIcones, definirCor, aplicarPreset, definirTudo, restaurar }),
    [tema, definirOrbe, definirIcones, definirCor, aplicarPreset, definirTudo, restaurar]
  );
  return <Ctx.Provider value={valor}>{children}</Ctx.Provider>;
}

export function useTema(): ContextoTema {
  const c = useContext(Ctx);
  if (!c) throw new Error("useTema precisa estar dentro de <ThemingProvider>");
  return c;
}
