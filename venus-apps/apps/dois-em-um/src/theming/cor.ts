import type { RGB } from "./tipos";

export const limitar = (n: number) => Math.min(255, Math.max(0, Math.round(Number.isFinite(n) ? n : 0)));

export const rgbValido = (c: RGB): RGB => ({ r: limitar(c.r), g: limitar(c.g), b: limitar(c.b) });
export const rgbCss = (c: RGB) => `rgb(${c.r} ${c.g} ${c.b})`;
/** "236 111 214": serve para rgb(var(--x) / .5) */
export const rgbLista = (c: RGB) => `${c.r} ${c.g} ${c.b}`;

const hex2 = (n: number) => limitar(n).toString(16).padStart(2, "0");
export const rgbParaHex = (c: RGB) => `#${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}`;

export function hexParaRgb(hex: string): RGB | null {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const n = parseInt(m[1]!, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function canal(v: number) {
  const s = v / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}
const luminancia = (c: RGB) => 0.2126 * canal(c.r) + 0.7152 * canal(c.g) + 0.0722 * canal(c.b);

/** Cor de texto legível sobre o fundo `c` (escuro em fundos claros, branco em escuros). */
export const corSobre = (c: RGB) => (luminancia(c) > 0.35 ? "#14061a" : "#ffffff");
