// Peças digitais da loja (panfleto e cartão de visitas): nascem como imagem para o WhatsApp, o Instagram e o e-commerce,
// com áreas clicáveis que viram links no PDF. Lógica pura: devolve o SVG e a lista de links; o navegador só desenha e baixa.
import type { LinkPdf } from "./pdf";

export interface ProdutoDaPeca { nome: string; preco: string; url: string }
export interface DadosDaPeca {
  loja: string; ramo?: string; logo?: string; cor: string; chamada?: string; oferta?: string; telefone?: string;
  /** Para onde a peça leva: a vitrine da loja ou o site dela. */
  link?: string; siteTexto?: string; cidade?: string;
  /** Mostrar o QR Code (para quem recebe a peça impressa ou na tela de outro aparelho). */
  qr: boolean;
  /** Gera o SVG do QR Code (injetado: svgDoQr, de ./qr). Sem ele, o QR não aparece. */
  gerarQr?: (conteudo: string) => string;
  produtos?: ProdutoDaPeca[];
}
export interface Peca { svg: string; largura: number; altura: number; links: LinkPdf[]; /** Pixels por unidade do SVG ao desenhar (padrão 1): documentos para imprimir usam 2. */ escala?: number }

export const esc = (t: string) => t.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[c]!);
const corOk = (c: string | undefined, p: string) => (c && /^#[0-9a-f]{6}$/i.test(c) ? c : p);
const FONTE = "system-ui, 'Segoe UI', Roboto, Arial, sans-serif";

/** Quebra o texto em linhas de até `max` letras (no máximo `linhas` linhas; o resto vira reticências). */
export function quebrar(texto: string, max: number, linhas: number): string[] {
  const palavras = texto.trim().split(/\s+/).filter(Boolean);
  const saida: string[] = [];
  let atual = "";
  for (const p of palavras) {
    if (atual && (atual + " " + p).length > max) { saida.push(atual); atual = p; } else atual = atual ? atual + " " + p : p;
  }
  if (atual) saida.push(atual);
  if (saida.length > linhas) { const corte = saida.slice(0, linhas); corte[linhas - 1] = (corte[linhas - 1] ?? "").replace(/[\s.,;:!?-]*$/, "") + "…"; return corte; }
  return saida;
}

const texto = (x: number, y: number, t: string, tam: number, cor: string, peso = 400, ancora = "middle") =>
  `<text x="${x}" y="${y}" text-anchor="${ancora}" font-family="${FONTE}" font-size="${tam}" font-weight="${peso}" fill="${cor}">${esc(t)}</text>`;

function qrNoSvg(gerar: ((c: string) => string) | undefined, conteudo: string, x: number, y: number, lado: number): string {
  try {
    if (!gerar) return "";
    const q = gerar(conteudo);
    return `<rect x="${x - 12}" y="${y - 12}" width="${lado + 24}" height="${lado + 24}" rx="18" fill="#ffffff"/>` + q.replace("<svg ", `<svg x="${x}" y="${y}" width="${lado}" height="${lado}" `);
  } catch { return ""; }
}

const alvoHttp = (u: string | undefined) => (u && /^https?:\/\//i.test(u) ? u : "");
const whatsapp = (tel: string | undefined) => { const d = (tel || "").replace(/\D/g, ""); return d.length >= 10 ? `https://wa.me/${d.length <= 11 ? "55" + d : d}` : ""; };

/** Panfleto digital vertical (1080 × 1350), no formato do feed do Instagram e do WhatsApp. */
export function montarPanfleto(d: DadosDaPeca): Peca {
  const L = 1080, A = 1350, cor = corOk(d.cor, "#7c3aed");
  const links: LinkPdf[] = [];
  const partes: string[] = [`<rect width="${L}" height="${A}" fill="#ffffff"/>`, `<rect width="${L}" height="28" fill="${cor}"/>`];
  let y = 70;
  if (d.logo) { partes.push(`<image href="${d.logo}" x="${L / 2 - 90}" y="${y}" width="180" height="180" preserveAspectRatio="xMidYMid meet"/>`); y += 200; }
  const nome = quebrar(d.loja || "Nome da loja", 22, 2);
  y += 56; nome.forEach((l, i) => partes.push(texto(L / 2, y + i * 70, l, 66, "#111827", 800))); y += (nome.length - 1) * 70 + 24;
  const chamada = quebrar(d.chamada || "Escreva aqui a chamada do panfleto", 30, 4);
  chamada.forEach((l, i) => partes.push(texto(L / 2, y + 44 + i * 58, l, 46, "#374151", 500))); y += chamada.length * 58 + 40;
  if (d.oferta) {
    const o = quebrar(d.oferta, 30, 2);
    const h = 40 + o.length * 56;
    partes.push(`<rect x="90" y="${y}" width="${L - 180}" height="${h}" rx="${h / 2 > 60 ? 40 : h / 2}" fill="${cor}"/>`);
    o.forEach((l, i) => partes.push(texto(L / 2, y + 20 + 44 + i * 56 - 6, l, 46, "#ffffff", 800)));
    y += h + 34;
  }
  const alvo = alvoHttp(d.link);
  const prods = (d.produtos || []).slice(0, 4);
  const rodape = 1350 - 330;
  if (prods.length && y + 110 < rodape) {
    const colunas = 2, w = 450, h = 150, vao = 30, x0 = (L - (colunas * w + vao)) / 2;
    const linhas = Math.ceil(prods.length / colunas);
    const cabe = Math.max(1, Math.floor((rodape - y - 10 + vao) / (h + vao)));
    prods.slice(0, Math.min(prods.length, cabe * colunas)).forEach((p, i) => {
      const cx = x0 + (i % colunas) * (w + vao), cy = y + Math.floor(i / colunas) * (h + vao);
      partes.push(`<rect x="${cx}" y="${cy}" width="${w}" height="${h}" rx="22" fill="#f3f4f6" stroke="${cor}" stroke-width="3"/>`);
      quebrar(p.nome, 24, 2).forEach((l, k) => partes.push(texto(cx + 24, cy + 52 + k * 36, l, 30, "#111827", 700, "start")));
      partes.push(texto(cx + 24, cy + h - 26, p.preco, 34, cor, 800, "start"));
      if (/^https?:\/\//i.test(p.url)) links.push({ x: cx, y: cy, w, h, url: p.url });
    });
    y += Math.min(linhas, cabe) * (h + vao);
  }
  // rodapé: botão da loja, contatos e QR
  const baseY = A - 300;
  partes.push(`<rect x="0" y="${baseY - 20}" width="${L}" height="${A - baseY + 20}" fill="#f3f4f6"/>`);
  if (alvo) {
    partes.push(`<rect x="70" y="${baseY + 20}" width="${d.qr ? 560 : 940}" height="96" rx="48" fill="${cor}"/>`);
    partes.push(texto(70 + (d.qr ? 280 : 470), baseY + 82, "Ver a loja e comprar", 44, "#ffffff", 800));
    links.push({ x: 70, y: baseY + 20, w: d.qr ? 560 : 940, h: 96, url: alvo });
  }
  const contatos = [d.telefone, d.siteTexto, d.cidade].filter(Boolean) as string[];
  contatos.slice(0, 3).forEach((c, i) => partes.push(texto(70, baseY + 170 + i * 44, c, 34, i === 0 ? "#111827" : "#4b5563", i === 0 ? 700 : 500, "start")));
  const zap = whatsapp(d.telefone);
  if (zap && d.telefone) links.push({ x: 70, y: baseY + 135, w: 520, h: 44, url: zap });
  if (d.qr && alvo) { partes.push(qrNoSvg(d.gerarQr, alvo, L - 70 - 210, baseY - 4, 210)); links.push({ x: L - 70 - 222, y: baseY - 16, w: 234, h: 234, url: alvo }); }
  return { svg: `<svg xmlns="http://www.w3.org/2000/svg" width="${L}" height="${A}" viewBox="0 0 ${L} ${A}">${partes.join("")}</svg>`, largura: L, altura: A, links };
}

/** Cartão de visitas digital (1000 × 560), para mandar por WhatsApp ou deixar no perfil. */
export function montarCartao(d: DadosDaPeca): Peca {
  const L = 1000, A = 560, cor = corOk(d.cor, "#7c3aed");
  const links: LinkPdf[] = [];
  const partes: string[] = [`<rect width="${L}" height="${A}" fill="#0b0b14"/>`, `<rect width="${L}" height="14" fill="${cor}"/>`];
  let x = 60, y = 90;
  if (d.logo) { partes.push(`<image href="${d.logo}" x="${x}" y="${y - 20}" width="110" height="110" preserveAspectRatio="xMidYMid meet"/>`); y += 130; } else y += 20;
  const nome = quebrar(d.loja || "Nome da loja", d.qr ? 17 : 26, 2);
  nome.forEach((l, i) => partes.push(texto(x, y + i * 60, l, 54, "#ffffff", 800, "start"))); y += (nome.length - 1) * 60 + 44;
  if (d.ramo) { partes.push(texto(x, y, d.ramo.slice(0, 34), 30, "#9ca3af", 500, "start")); y += 54; }
  const linhas = [d.telefone, d.siteTexto, d.cidade].filter(Boolean) as string[];
  linhas.slice(0, 3).forEach((c, i) => { partes.push(texto(x, y + i * 42, c, 32, i === 0 ? "#ffffff" : "#d1d5db", i === 0 ? 700 : 500, "start")); });
  const zap = whatsapp(d.telefone), alvo = alvoHttp(d.link);
  if (zap && d.telefone) links.push({ x, y: y - 32, w: 440, h: 44, url: zap });
  if (d.qr && alvo) { partes.push(qrNoSvg(d.gerarQr, alvo, L - 60 - 250, 150, 250)); links.push({ x: L - 60 - 262, y: 138, w: 274, h: 274, url: alvo }); partes.push(texto(L - 60 - 125, 470, "Aponte a câmera", 28, "#d1d5db", 500)); }
  else if (alvo) { partes.push(`<rect x="${L - 60 - 280}" y="${A - 130}" width="280" height="76" rx="38" fill="${cor}"/>`); partes.push(texto(L - 60 - 140, A - 80, "Ver a loja", 36, "#ffffff", 800)); links.push({ x: L - 340, y: A - 130, w: 280, h: 76, url: alvo }); }
  return { svg: `<svg xmlns="http://www.w3.org/2000/svg" width="${L}" height="${A}" viewBox="0 0 ${L} ${A}">${partes.join("")}</svg>`, largura: L, altura: A, links };
}
