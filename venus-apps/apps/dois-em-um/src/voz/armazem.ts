// Guarda a configuração de voz no aparelho e avisa quem estiver olhando (tela de ajustes, sincronização).
import { useSyncExternalStore } from "react";
import { CONFIG_VOZ_PADRAO, limparConfigVoz, mesAtual, type ConfigVoz } from "./config";

const CHAVE = "venus:voz";
const CHAVE_USO = "venus:voz:uso";
let atual: ConfigVoz | null = null;
const ouvintes = new Set<() => void>();

function ler(): ConfigVoz {
  if (atual) return atual;
  try { const b = localStorage.getItem(CHAVE); atual = b ? limparConfigVoz(JSON.parse(b)) : CONFIG_VOZ_PADRAO; } catch { atual = CONFIG_VOZ_PADRAO; }
  return atual;
}

export const lerConfigVoz = (): ConfigVoz => ler();

export function gravarConfigVoz(c: ConfigVoz): void {
  atual = limparConfigVoz(c);
  try { localStorage.setItem(CHAVE, JSON.stringify(atual)); } catch { /* sem armazenamento: vale só nesta sessão */ }
  ouvintes.forEach((f) => f());
}

export function useConfigVoz(): [ConfigVoz, (c: ConfigVoz) => void] {
  const c = useSyncExternalStore((f) => { ouvintes.add(f); return () => { ouvintes.delete(f); }; }, ler);
  return [c, gravarConfigVoz];
}

/** Caracteres da voz neural usados no mês (neste computador). */
export function usoDoMes(): number {
  try { const u = JSON.parse(localStorage.getItem(CHAVE_USO) ?? "{}") as { mes?: string; usados?: number }; return u.mes === mesAtual() && typeof u.usados === "number" ? u.usados : 0; } catch { return 0; }
}
export function somarUso(n: number): void {
  try { localStorage.setItem(CHAVE_USO, JSON.stringify({ mes: mesAtual(), usados: usoDoMes() + n })); } catch { /* ok */ }
  ouvintes.forEach((f) => f());
}
export function useUsoDoMes() { return useSyncExternalStore((f) => { ouvintes.add(f); return () => { ouvintes.delete(f); }; }, usoDoMes); }
