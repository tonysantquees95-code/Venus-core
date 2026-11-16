// PDF de uma página só com a imagem da peça (panfleto ou cartão) e áreas clicáveis (links). Sem biblioteca: o PDF é simples.

export interface LinkPdf { x: number; y: number; w: number; h: number; url: string }

const enc = new TextEncoder();
const str = (t: string) => enc.encode(t);
const num = (n: number) => (Math.round(n * 100) / 100).toString();
/** Endereço seguro dentro de uma string de PDF: só ASCII, sem parênteses nem barra invertida. */
const urlPdf = (u: string) => encodeURI(u).replace(/[()\\]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());

/** Monta o PDF. As medidas dos links são em pixels da imagem (origem no canto de cima, à esquerda). */
export function pdfComImagem(jpeg: Uint8Array, larg: number, alt: number, links: LinkPdf[] = []): Uint8Array {
  const pw = larg * 0.75, ph = alt * 0.75; // 96 dpi → pontos
  const uteis = links.filter((l) => /^https?:\/\//i.test(l.url) && l.w > 0 && l.h > 0);
  const primeiroLink = 6;
  const annots = uteis.map((_, i) => `${primeiroLink + i} 0 R`).join(" ");
  const partes: Uint8Array[] = [];
  const offsets: number[] = [];
  let tamanho = 0;
  const gravar = (b: Uint8Array) => { partes.push(b); tamanho += b.length; };
  const objeto = (n: number, corpo: Uint8Array[]) => { offsets[n] = tamanho; gravar(str(`${n} 0 obj\n`)); corpo.forEach(gravar); gravar(str("\nendobj\n")); };

  gravar(str("%PDF-1.4\n%\xE2\xE3\xCF\xD3\n"));
  objeto(1, [str("<< /Type /Catalog /Pages 2 0 R >>")]);
  objeto(2, [str("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")]);
  objeto(3, [str(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(pw)} ${num(ph)}] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R${annots ? ` /Annots [${annots}]` : ""} >>`)]);
  objeto(4, [str(`<< /Type /XObject /Subtype /Image /Width ${larg} /Height ${alt} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`), jpeg, str("\nendstream")]);
  const conteudo = `q ${num(pw)} 0 0 ${num(ph)} 0 0 cm /Im0 Do Q`;
  objeto(5, [str(`<< /Length ${conteudo.length} >>\nstream\n${conteudo}\nendstream`)]);
  uteis.forEach((l, i) => {
    const x1 = l.x * 0.75, x2 = (l.x + l.w) * 0.75, y1 = ph - (l.y + l.h) * 0.75, y2 = ph - l.y * 0.75;
    objeto(primeiroLink + i, [str(`<< /Type /Annot /Subtype /Link /Rect [${num(x1)} ${num(y1)} ${num(x2)} ${num(y2)}] /Border [0 0 0] /A << /S /URI /URI (${urlPdf(l.url)}) >> >>`)]);
  });
  const total = primeiroLink + uteis.length;
  const xref = tamanho;
  let tabela = `xref\n0 ${total}\n0000000000 65535 f \n`;
  for (let n = 1; n < total; n++) tabela += `${String(offsets[n]).padStart(10, "0")} 00000 n \n`;
  gravar(str(`${tabela}trailer\n<< /Size ${total} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`));
  const saida = new Uint8Array(tamanho);
  let p = 0;
  for (const b of partes) { saida.set(b, p); p += b.length; }
  return saida;
}
