/** Logos prontos: 24 modelos (6 letras × 4 formatos). A pessoa escreve o nome e troca as cores; o resultado vira PNG. */

export interface Fonte { id: string; nome: string; familia: string; peso: number; estilo: "normal" | "italic"; caixa: "normal" | "alta"; espaco: number }
export const FONTES: Fonte[] = [
  { id: "moderna", nome: "Moderna", familia: "'Helvetica Neue', Arial, sans-serif", peso: 800, estilo: "normal", caixa: "normal", espaco: 0 },
  { id: "elegante", nome: "Elegante", familia: "Georgia, 'Times New Roman', serif", peso: 700, estilo: "italic", caixa: "normal", espaco: 0 },
  { id: "cursiva", nome: "Cursiva", familia: "'Brush Script MT', 'Segoe Script', 'Snell Roundhand', cursive", peso: 400, estilo: "italic", caixa: "normal", espaco: 0 },
  { id: "forte", nome: "Forte", familia: "Impact, 'Arial Black', sans-serif", peso: 900, estilo: "normal", caixa: "alta", espaco: 1 },
  { id: "classica", nome: "Clássica", familia: "'Times New Roman', Times, serif", peso: 700, estilo: "normal", caixa: "alta", espaco: 4 },
  { id: "redonda", nome: "Redonda", familia: "'Trebuchet MS', 'Comic Sans MS', sans-serif", peso: 700, estilo: "normal", caixa: "normal", espaco: 0 },
];
export const FORMATOS = ["selo", "faixa", "circulo", "linha"] as const;
export type Formato = (typeof FORMATOS)[number];
export interface ModeloLogo { id: string; fonte: Fonte; formato: Formato }
export const MODELOS: ModeloLogo[] = FONTES.flatMap((fonte) => FORMATOS.map((formato) => ({ id: `${fonte.id}-${formato}`, fonte, formato })));

export const escapar = (t: string) => t.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[c]!);
const corOk = (c: string, padrao: string) => (/^#[0-9a-f]{6}$/i.test(c) ? c : padrao);

/** SVG de 256×256 do modelo com o nome e as cores dadas. */
export function gerarSvg(modelo: ModeloLogo, nome: string, cor: string, apoio: string): string {
  const principal = corOk(cor, "#7c3aed"), claro = corOk(apoio, "#ffffff");
  const f = modelo.fonte;
  let texto = (nome.trim() || "Minha loja").slice(0, 28);
  if (f.caixa === "alta") texto = texto.toUpperCase();
  const largura = modelo.formato === "circulo" ? 160 : 208;
  // nomes longos com espaço quebram em duas linhas, para as letras ficarem grandes
  let linhas = [texto];
  if (texto.length > 10 && texto.includes(" ")) {
    const meio = texto.length / 2;
    let corte = -1;
    for (let i = 0; i < texto.length; i++) if (texto[i] === " " && (corte < 0 || Math.abs(i - meio) < Math.abs(corte - meio))) corte = i;
    linhas = [texto.slice(0, corte), texto.slice(corte + 1)];
  }
  const maior = Math.max(...linhas.map((l) => l.length), 3);
  const tam = Math.max(16, Math.min(f.id === "cursiva" ? 84 : 66, largura / (maior * (f.id === "forte" ? 0.5 : 0.56 + f.espaco / 40))));
  const t = (x: number, y: number, preench: string) => linhas.map((l, i) => {
    const yy = y + (i - (linhas.length - 1) / 2) * tam * 1.05;
    const estimada = l.length * (tam * 0.56 + f.espaco);
    const ajuste = estimada > largura ? ` textLength="${largura}" lengthAdjust="spacingAndGlyphs"` : "";
    return `<text x="${x}" y="${yy.toFixed(1)}" text-anchor="middle" dominant-baseline="central" fill="${preench}" font-family="${escapar(f.familia)}" font-weight="${f.peso}" font-style="${f.estilo}" letter-spacing="${f.espaco}" font-size="${tam.toFixed(1)}"${ajuste}>${escapar(l)}</text>`;
  }).join("");
  const alto = linhas.length * tam * 1.05;
  let corpo = "";
  switch (modelo.formato) {
    case "selo": corpo = `<rect width="256" height="256" rx="52" fill="${principal}"/>${t(128, 128, claro)}`; break;
    case "faixa": corpo = `<rect width="256" height="256" rx="52" fill="${claro}"/><rect x="0" y="${(128 - alto / 2 - 14).toFixed(1)}" width="256" height="${(alto + 28).toFixed(1)}" fill="${principal}"/>${t(128, 128, claro)}`; break;
    case "circulo": corpo = `<rect width="256" height="256" rx="52" fill="${claro}"/><circle cx="128" cy="128" r="120" fill="${principal}"/><circle cx="128" cy="128" r="106" fill="none" stroke="${claro}" stroke-width="4"/>${t(128, 128, claro)}`; break;
    case "linha": corpo = `<rect width="256" height="256" rx="52" fill="${claro}"/>${t(128, 116, principal)}<rect x="68" y="${(116 + alto / 2 + 14).toFixed(1)}" width="120" height="6" rx="3" fill="${principal}"/>`; break;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256">${corpo}</svg>`;
}

/** Desenha o SVG num canvas e devolve um PNG (data URL) pequeno o bastante para a loja guardar. */
export async function rasterizar(svg: string, lado = 256): Promise<string> {
  const img = new Image();
  const url = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
  await new Promise<void>((ok, falha) => { img.onload = () => ok(); img.onerror = () => falha(new Error("Não consegui montar o logo.")); img.src = url; });
  const c = document.createElement("canvas"); c.width = lado; c.height = lado;
  const g = c.getContext("2d"); if (!g) throw new Error("Não consegui montar o logo.");
  g.drawImage(img, 0, 0, lado, lado);
  const png = c.toDataURL("image/png");
  if (png.length > 140_000) return c.toDataURL("image/webp", 0.9);
  return png;
}

export const urlDoSvg = (svg: string) => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
