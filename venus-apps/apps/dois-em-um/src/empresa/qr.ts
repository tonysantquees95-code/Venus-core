import qrcode from "../vendor/qrcode.js";

let pronto = false;
/** SVG de um QR Code (nível M, UTF-8). Lança erro se o texto for vazio ou grande demais. */
export function svgDoQr(texto: string, { cor = "#000000", fundo = "#ffffff", margem = 4 } = {}): string {
  const t = texto.trim();
  if (!t || t.length > 1000) throw new Error("O conteúdo do QR Code deve ter de 1 a 1000 caracteres.");
  if (!pronto) { (qrcode as any).stringToBytes = (qrcode as any).stringToBytesFuncs["UTF-8"]; pronto = true; }
  const q = qrcode(0, "M");
  q.addData(t);
  q.make();
  const n = q.getModuleCount();
  const total = n + margem * 2;
  let d = "";
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (q.isDark(y, x)) d += `M${x + margem},${y + margem}h1v1h-1z`;
  const c = /^#[0-9a-f]{6}$/i.test(cor) ? cor : "#000000";
  const f = /^#[0-9a-f]{6}$/i.test(fundo) ? fundo : "#ffffff";
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" shape-rendering="crispEdges"><rect width="${total}" height="${total}" fill="${f}"/><path d="${d}" fill="${c}"/></svg>`;
}

/** Endereço que o QR deve levar: o que a pessoa digitou, ou o site da loja; garante https:// quando parece um site. */
export function enderecoParaQr(digitado: string, site: string): string {
  const t = (digitado.trim() || site.trim());
  if (!t) return "";
  return /^[a-z][a-z0-9+.-]*:/i.test(t) || /\s/.test(t) ? t : `https://${t}`;
}
