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

/** [matiz 0-360, saturação 0-1, brilho 0-1] */
export function rgbParaHsv(c: RGB): [number, number, number] {
  const r = c.r / 255, g = c.g / 255, b = c.b / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  let h = 0;
  if (d > 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return [h, max === 0 ? 0 : d / max, max];
}

export function hsvParaRgb(h: number, s: number, v: number): RGB {
  const hh = (((h % 360) + 360) % 360) / 60;
  const c = v * s, x = c * (1 - Math.abs((hh % 2) - 1)), m = v - c;
  const [r, g, b] = hh < 1 ? [c, x, 0] : hh < 2 ? [x, c, 0] : hh < 3 ? [0, c, x] : hh < 4 ? [0, x, c] : hh < 5 ? [x, 0, c] : [c, 0, x];
  return { r: limitar((r + m) * 255), g: limitar((g + m) * 255), b: limitar((b + m) * 255) };
}

/** Mesmo `Tema.trio` do celular: três tons vizinhos da cor escolhida. */
export function trio(c: RGB): [RGB, RGB, RGB] {
  const [h, s, v] = rgbParaHsv(c);
  const sat = Math.max(s, 0.35), br = Math.max(v, 0.7);
  return [hsvParaRgb(h, sat, br), hsvParaRgb(h + 28, sat, br), hsvParaRgb(h + 56, sat * 0.9, br)];
}

/** Cor "par" do app do celular (Aparencia.par): matiz +40, saturação ×0,9. */
export function paraSecundaria(c: RGB): RGB {
  const [h, s, v] = rgbParaHsv(c);
  return hsvParaRgb(h + 40, s * 0.9, v);
}

/** Mistura com branco (a mesma ideia do `clarear` do celular). */
export const clarear = (c: RGB, f: number): RGB => ({ r: limitar(c.r + (255 - c.r) * f), g: limitar(c.g + (255 - c.g) * f), b: limitar(c.b + (255 - c.b) * f) });
