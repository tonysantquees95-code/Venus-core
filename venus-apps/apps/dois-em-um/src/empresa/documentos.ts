// Recibo, notinha e etiqueta de envio (sem valor fiscal) — a mesma ideia do app Android (Documentos.kt).
// Lógica pura: monta os dados, controla o limite do plano grátis (15 por mês) e devolve o SVG de cada documento.
// O navegador só desenha esse SVG, gera o PDF e imprime. Dinheiro sempre em centavos inteiros.
import type { Peca } from "./peca";

export type TipoDoc = "recibo" | "notinha" | "etiqueta";
export const TIPOS_DE_DOC: { id: TipoDoc; rotulo: string; dica: string }[] = [
  { id: "recibo", rotulo: "Recibo", dica: "Folha A4, para guardar ou mandar" },
  { id: "notinha", rotulo: "Notinha", dica: "Cupom estreito de 80 mm" },
  { id: "etiqueta", rotulo: "Etiqueta de envio", dica: "10 x 15 cm, destinatário grande" },
];
export const LIMITE_GRATIS = 15;
export const AVISO_SEM_VALOR_FISCAL = "Documento sem valor fiscal.";
const MARCA = "Feito com a Vênus";

export interface EmissorDoc { nome: string; documento: string; contato: string; enderecoLinhas: string[]; mensagem: string; logo?: string }
export interface ItemDoc { nome: string; quantidade: number; precoCentavos: number }
export interface DadosDoc {
  emissor: EmissorDoc; numero: string; data: string; cliente: string; telefone: string; enderecoLinhas: string[];
  itens: ItemDoc[]; freteCentavos: number; descontoCentavos: number; retirada: boolean; rastreio: string; observacao: string;
}

export const subtotalDe = (d: DadosDoc) => d.itens.reduce((s, i) => s + i.quantidade * i.precoCentavos, 0);
export const totalDe = (d: DadosDoc) => Math.max(0, subtotalDe(d) + d.freteCentavos - d.descontoCentavos);

// ------------------------------------------------------------------ formatos
export function dinheiroDoc(centavos: number): string {
  const neg = centavos < 0;
  const c = Math.abs(Math.round(centavos));
  const reais = String(Math.floor(c / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return `${neg ? "-" : ""}R$ ${reais},${String(c % 100).padStart(2, "0")}`;
}

/** "89,90", "89.90", "R$ 1.234,50" → centavos; null se não for número. */
export function lerReais(texto: string): number | null {
  let t = texto.trim().replace(/R\$/g, "").replace(/\s/g, "");
  if (!t) return null;
  if (t.includes(",")) t = t.replace(/\./g, "").replace(",", ".");
  if (!/^\d+(\.\d+)?$/.test(t)) return null;
  return Math.round(parseFloat(t) * 100);
}

const brasilia = (ms: number) => new Date(ms - 3 * 3600_000); // lê com getUTC*: é a hora de Brasília
export function dataBR(ms: number): string {
  if (!(ms > 0)) return "";
  const d = brasilia(ms);
  return `${String(d.getUTCDate()).padStart(2, "0")}/${String(d.getUTCMonth() + 1).padStart(2, "0")}/${d.getUTCFullYear()}`;
}
/** "2026-11" no fuso de Brasília: o limite mensal vira no começo do mês de lá. */
export function mesDe(ms: number): string {
  const d = brasilia(ms);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

export function telefoneBonito(bruto: string): string {
  let d = bruto.replace(/\D/g, "");
  if (d.length >= 12 && d.startsWith("55")) d = d.slice(2);
  if (d.length === 11) return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
  if (d.length === 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return bruto.trim();
}

export const linhasDeTexto = (t: string) => t.split("\n").map((l) => l.trim()).filter(Boolean);

export function nomeDoArquivoDoc(tipo: TipoDoc, numero: string): string {
  const limpo = numero.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "avulso";
  return `${tipo}-${limpo}`;
}

// ------------------------------------------------------------------ limite do plano grátis
export interface Cota { mes: string; feitos: string[] }
const chave = (tipo: TipoDoc, numero: string) => `${tipo}-${numero}`;
export const cotaDoMes = (g: Cota | null | undefined, mesAtual: string): Cota => (!g || g.mes !== mesAtual ? { mes: mesAtual, feitos: [] } : g);
export const usadosDoMes = (g: Cota | null | undefined, mesAtual: string) => new Set(cotaDoMes(g, mesAtual).feitos).size;
export const restantesDoMes = (g: Cota | null | undefined, mesAtual: string) => Math.max(0, LIMITE_GRATIS - usadosDoMes(g, mesAtual));
/** Refazer o mesmo documento (mesmo número e tipo) no mesmo mês não gasta outro. */
export function podeGerarDoc(g: Cota | null | undefined, mesAtual: string, tipo: TipoDoc, numero: string, semLimite: boolean): boolean {
  if (semLimite) return true;
  const c = cotaDoMes(g, mesAtual);
  return c.feitos.includes(chave(tipo, numero)) || new Set(c.feitos).size < LIMITE_GRATIS;
}
export function registrarDoc(g: Cota | null | undefined, mesAtual: string, tipo: TipoDoc, numero: string): Cota {
  const c = cotaDoMes(g, mesAtual);
  const k = chave(tipo, numero);
  return c.feitos.includes(k) ? c : { mes: c.mes, feitos: [...c.feitos, k] };
}
export const comMarca = (semLimite: boolean) => !semLimite;
export const resumoDoUsoDoc = (g: Cota | null | undefined, mesAtual: string, semLimite: boolean) =>
  semLimite ? "Sem limite no seu plano." : `Você usou ${usadosDoMes(g, mesAtual)} de ${LIMITE_GRATIS} documentos grátis neste mês.`;

// ------------------------------------------------------------------ desenho (SVG)
const esc = (t: string) => t.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[c]!);
const FONTE = "system-ui, 'Segoe UI', Roboto, Arial, sans-serif";
const DESTAQUE = "#c2185b";
const CINZA = "#555555";
const txt = (x: number, y: number, t: string, tam: number, cor = "#000000", peso = 400, ancora = "start") =>
  `<text x="${x}" y="${y}" text-anchor="${ancora}" font-family="${FONTE}" font-size="${tam}" font-weight="${peso}" fill="${cor}">${esc(t)}</text>`;
const reta = (x1: number, x2: number, y: number, cor = "#cccccc", w = 1, tracejada = false) =>
  `<line x1="${x1}" y1="${y}" x2="${x2}" y2="${y}" stroke="${cor}" stroke-width="${w}"${tracejada ? ' stroke-dasharray="4 3"' : ""}/>`;

/** Quebra em linhas de até `max` letras (no máximo `linhas`; o resto vira reticências). */
export function quebrarTexto(texto: string, max: number, linhas: number): string[] {
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

const nomeCliente = (d: DadosDoc) => d.cliente.trim() || "Cliente";
const embrulhar = (partes: string[], L: number, A: number): Peca => ({
  svg: `<svg xmlns="http://www.w3.org/2000/svg" width="${L}" height="${A}" viewBox="0 0 ${L} ${A}"><rect width="${L}" height="${A}" fill="#ffffff"/>${partes.join("")}</svg>`,
  largura: L, altura: A, links: [], escala: 2,
});

export function montarDocumento(tipo: TipoDoc, d: DadosDoc, marca: boolean): Peca {
  return tipo === "recibo" ? montarRecibo(d, marca) : tipo === "notinha" ? montarNotinha(d, marca) : montarEtiqueta(d, marca);
}

function montarRecibo(d: DadosDoc, marca: boolean): Peca {
  const L = 794, A = 1123, m = 56, W = L - 2 * m;
  const p: string[] = [];
  let y = m;
  const lg = d.emissor.logo;
  if (lg) p.push(`<image href="${lg}" x="${m}" y="${y}" width="76" height="76" preserveAspectRatio="xMinYMin meet"/>`);
  const xe = lg ? m + 92 : m;
  let ye = y + 22;
  quebrarTexto(d.emissor.nome || "Minha loja", 26, 2).forEach((l) => { p.push(txt(xe, ye, l, 26, "#000000", 700)); ye += 30; });
  for (const l of [d.emissor.documento, d.emissor.contato, ...d.emissor.enderecoLinhas.slice(0, 3)].filter(Boolean)) { p.push(txt(xe, ye, l, 13, CINZA)); ye += 18; }
  p.push(txt(L - m, y + 30, "RECIBO", 32, DESTAQUE, 800, "end"));
  p.push(txt(L - m, y + 56, `Nº ${d.numero}`, 16, "#000000", 700, "end"));
  if (d.data) p.push(txt(L - m, y + 78, d.data, 14, CINZA, 400, "end"));
  y = Math.max(ye, y + 90) + 12;
  p.push(reta(m, L - m, y, DESTAQUE, 2.4));
  y += 30;
  p.push(txt(m, y, "CLIENTE", 11, CINZA, 700)); y += 20;
  p.push(txt(m, y, nomeCliente(d).slice(0, 60), 18, "#000000", 700)); y += 22;
  if (d.telefone) { p.push(txt(m, y, d.telefone, 13, CINZA)); y += 18; }
  if (d.enderecoLinhas.length && !d.retirada) { quebrarTexto(d.enderecoLinhas.join(" · "), 95, 2).forEach((l) => { p.push(txt(m, y, l, 13, CINZA)); y += 18; }); }
  y += 14;
  p.push(`<rect x="${m}" y="${y}" width="${W}" height="28" fill="#f4f4f4"/>`);
  const xQtd = m + W * 0.62, xUni = m + W * 0.8, xTot = m + W - 8;
  p.push(txt(m + 8, y + 19, "ITEM", 11, CINZA, 700), txt(xQtd, y + 19, "QTD", 11, CINZA, 700, "end"), txt(xUni, y + 19, "UNITÁRIO", 11, CINZA, 700, "end"), txt(xTot, y + 19, "TOTAL", 11, CINZA, 700, "end"));
  y += 28 + 8;
  const limite = A - 290;
  let cortados = 0;
  for (let i = 0; i < d.itens.length; i++) {
    const it = d.itens[i]!;
    const nome = quebrarTexto(it.nome || "Item", 46, 2);
    const h = nome.length * 19 + 10;
    if (y + h > limite) { cortados = d.itens.length - i; break; }
    nome.forEach((l, k) => p.push(txt(m + 8, y + 15 + k * 19, l, 14)));
    p.push(txt(xQtd, y + 15, String(it.quantidade), 14, "#000000", 400, "end"), txt(xUni, y + 15, dinheiroDoc(it.precoCentavos), 14, "#000000", 400, "end"), txt(xTot, y + 15, dinheiroDoc(it.quantidade * it.precoCentavos), 14, "#000000", 400, "end"));
    y += h;
    p.push(reta(m, L - m, y - 3, "#e6e6e6"));
  }
  if (cortados) { p.push(txt(m + 8, y + 14, `… e mais ${cortados} item(ns) deste pedido.`, 12, CINZA)); y += 22; }
  y += 14;
  const xr = L - m;
  const linhaTotal = (rot: string, valor: string, forte = false) => {
    p.push(txt(xr - 250, y, rot, forte ? 20 : 14, forte ? DESTAQUE : "#000000", forte ? 800 : 400), txt(xr, y, valor, forte ? 20 : 14, forte ? DESTAQUE : "#000000", forte ? 800 : 400, "end"));
    y += forte ? 30 : 22;
  };
  linhaTotal("Subtotal", dinheiroDoc(subtotalDe(d)));
  if (d.freteCentavos > 0) linhaTotal("Frete", dinheiroDoc(d.freteCentavos));
  if (d.descontoCentavos > 0) linhaTotal("Desconto", "-" + dinheiroDoc(d.descontoCentavos));
  p.push(reta(xr - 250, xr, y - 14, "#000000", 1)); y += 6;
  linhaTotal("TOTAL", dinheiroDoc(totalDe(d)), true);
  if (d.rastreio) { p.push(txt(m, y + 8, `Código de rastreio: ${d.rastreio}`, 13, CINZA)); y += 26; }
  if (d.observacao) { quebrarTexto(d.observacao, 100, 2).forEach((l) => { p.push(txt(m, y + 8, l, 13, CINZA)); y += 18; }); }
  if (d.emissor.mensagem) quebrarTexto(d.emissor.mensagem, 70, 2).forEach((l, i) => p.push(txt(m, Math.max(y + 40, A - 150) + i * 22, l, 16, DESTAQUE, 700)));
  p.push(reta(m, L - m, A - 60, "#cccccc"));
  p.push(txt(m, A - 36, AVISO_SEM_VALOR_FISCAL, 11, CINZA));
  if (marca) p.push(txt(L - m, A - 36, MARCA, 11, CINZA, 400, "end"));
  return embrulhar(p, L, A);
}

function montarNotinha(d: DadosDoc, marca: boolean): Peca {
  const L = 302, m = 16, W = L - 2 * m, c = L / 2;
  const p: string[] = [];
  let y = 18;
  if (d.emissor.logo) { p.push(`<image href="${d.emissor.logo}" x="${c - 28}" y="${y}" width="56" height="56" preserveAspectRatio="xMidYMid meet"/>`); y += 66; }
  quebrarTexto(d.emissor.nome || "Minha loja", 24, 2).forEach((l) => { y += 18; p.push(txt(c, y, l, 17, "#000000", 700, "middle")); });
  for (const l of [d.emissor.documento, d.emissor.contato, ...d.emissor.enderecoLinhas.slice(0, 3)].filter(Boolean)) { y += 14; p.push(txt(c, y, l.slice(0, 44), 11, CINZA, 400, "middle")); }
  y += 12; p.push(reta(m, L - m, y, "#000000", 1, true)); y += 20;
  p.push(txt(c, y, `NOTINHA Nº ${d.numero}`, 14, "#000000", 700, "middle")); y += 16;
  if (d.data) { p.push(txt(c, y, d.data, 11, CINZA, 400, "middle")); y += 16; }
  quebrarTexto(`Cliente: ${nomeCliente(d)}`, 38, 2).forEach((l) => { p.push(txt(m, y + 4, l, 12)); y += 16; });
  y += 6; p.push(reta(m, L - m, y, "#000000", 1, true)); y += 6;
  for (const it of d.itens) {
    quebrarTexto(it.nome || "Item", 38, 3).forEach((l) => { y += 16; p.push(txt(m, y, l, 12)); });
    y += 15;
    p.push(txt(m, y, `${it.quantidade} x ${dinheiroDoc(it.precoCentavos)}`, 11, CINZA), txt(L - m, y, dinheiroDoc(it.quantidade * it.precoCentavos), 12, "#000000", 400, "end"));
    y += 4;
  }
  y += 10; p.push(reta(m, L - m, y, "#000000", 1, true)); y += 6;
  const par = (rot: string, valor: string, tam = 12, peso = 400) => { y += tam + 6; p.push(txt(m, y, rot, tam, "#000000", peso), txt(L - m, y, valor, tam, "#000000", peso, "end")); };
  par("Subtotal", dinheiroDoc(subtotalDe(d)));
  if (d.freteCentavos > 0) par("Frete", dinheiroDoc(d.freteCentavos));
  if (d.descontoCentavos > 0) par("Desconto", "-" + dinheiroDoc(d.descontoCentavos));
  par("TOTAL", dinheiroDoc(totalDe(d)), 17, 800);
  if (d.rastreio) { y += 18; p.push(txt(m, y, `Rastreio: ${d.rastreio}`, 11, CINZA)); }
  y += 14; p.push(reta(m, L - m, y, "#000000", 1, true)); y += 6;
  if (d.emissor.mensagem) quebrarTexto(d.emissor.mensagem, 36, 4).forEach((l) => { y += 16; p.push(txt(c, y, l, 12, "#000000", 700, "middle")); });
  y += 18; p.push(txt(c, y, AVISO_SEM_VALOR_FISCAL, 10, CINZA, 400, "middle"));
  if (marca) { y += 14; p.push(txt(c, y, MARCA, 10, CINZA, 400, "middle")); }
  y += 20;
  void W;
  return embrulhar(p, L, Math.max(260, y));
}

function montarEtiqueta(d: DadosDoc, marca: boolean): Peca {
  const L = 378, A = 567, m = 18, W = L - 2 * m;
  const p: string[] = [`<rect x="5" y="5" width="${L - 10}" height="${A - 10}" fill="none" stroke="#000000" stroke-width="2"/>`];
  let y = m;
  const lg = d.emissor.logo;
  if (lg) p.push(`<image href="${lg}" x="${m}" y="${y}" width="40" height="40" preserveAspectRatio="xMinYMin meet"/>`);
  const xr = lg ? m + 50 : m;
  let yr = y + 9;
  p.push(txt(xr, yr, "REMETENTE", 9, CINZA, 700)); yr += 15;
  p.push(txt(xr, yr, (d.emissor.nome || "Minha loja").slice(0, 34), 13, "#000000", 700)); yr += 15;
  for (const l of d.emissor.enderecoLinhas.slice(0, 3)) { p.push(txt(xr, yr, l.slice(0, 44), 11)); yr += 14; }
  y = Math.max(yr, y + 44) + 6;
  p.push(reta(m, L - m, y, "#000000", 1.6)); y += 12;
  p.push(`<rect x="${m}" y="${y}" width="86" height="18" fill="#000000"/>`, txt(m + 6, y + 13, "DESTINATÁRIO", 9, "#ffffff", 700));
  y += 44;
  quebrarTexto(nomeCliente(d).toUpperCase(), 20, 2).forEach((l) => { p.push(txt(m, y, l, 24, "#000000", 800)); y += 28; });
  y += 4;
  if (d.retirada && d.enderecoLinhas.length === 0) { p.push(txt(m, y + 6, "RETIRADA NA LOJA", 20, DESTAQUE, 800)); y += 34; }
  else if (d.enderecoLinhas.length === 0) { quebrarTexto("Sem endereço: preencha o endereço de entrega.", 36, 2).forEach((l) => { p.push(txt(m, y, l, 13, CINZA)); y += 18; }); }
  else for (const l of d.enderecoLinhas.slice(0, 6)) { quebrarTexto(l, 32, 2).forEach((s) => { p.push(txt(m, y, s, 17)); y += 23; }); }
  if (d.telefone) { p.push(txt(m, y + 10, `Tel.: ${d.telefone}`, 14, CINZA)); y += 28; }
  let yb = A - 92;
  if (d.rastreio) yb -= 24;
  if (d.observacao) yb -= 26;
  yb = Math.max(yb, y + 10);
  p.push(reta(m, L - m, yb, "#000000", 1.6)); yb += 28;
  p.push(txt(m, yb, `PEDIDO Nº ${d.numero}`, 20, "#000000", 800));
  if (d.data) p.push(txt(L - m, yb, d.data, 12, CINZA, 400, "end"));
  if (d.rastreio) { yb += 24; p.push(txt(m, yb, `Rastreio: ${d.rastreio}`, 13)); }
  if (d.observacao) { yb += 22; p.push(txt(m, yb, d.observacao.slice(0, 48), 12, CINZA)); }
  if (marca) p.push(txt(L - m, A - 18, MARCA, 9, CINZA, 400, "end"));
  void W;
  return embrulhar(p, L, A);
}
