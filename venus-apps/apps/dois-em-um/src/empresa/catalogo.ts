// Catálogo público da loja (nome, preço, estoque) para a Vênus do computador responder sem mandar ninguém "olhar no site".
// Lógica pura: ler a resposta do site, achar o produto pela pergunta e montar a resposta/contexto. Testável sem navegador.

export interface ProdutoDaLoja {
  id: number; nome: string; categoria: string; descricao: string;
  precoCentavos: number | null; antigoCentavos: number | null; esgotado: boolean;
}

const semAcento = (t: string) => t.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
const palavras = (t: string) => semAcento(t).replace(/[^a-z0-9]+/g, " ").trim().split(" ").filter(Boolean);
const texto = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");
const centavos = (v: unknown) => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.round(v) : null);

export const reais = (c: number | null) => (c == null ? "" : `R$ ${(c / 100).toFixed(2).replace(".", ",")}`);

/** Lê o corpo de /_api/catalog (o site embrulha em superjson: { json, meta }). */
export function lerCatalogo(corpo: string): ProdutoDaLoja[] {
  let j: any;
  try { j = JSON.parse(corpo); } catch { return []; }
  const base = j && typeof j === "object" && "json" in j ? j.json : j;
  const lista = base?.products;
  if (!Array.isArray(lista)) return [];
  return lista.flatMap((p: any) => {
    const nome = texto(p?.name, 120);
    if (!nome || typeof p?.id !== "number") return [];
    return [{
      id: p.id, nome, categoria: texto(p.category, 60), descricao: texto(p.description, 300),
      precoCentavos: centavos(p.priceCents), antigoCentavos: centavos(p.compareAtCents), esgotado: p.soldOut === true
    }];
  });
}

const SOLTAS = new Set(["de", "da", "do", "das", "dos", "o", "a", "os", "as", "um", "uma", "e", "com", "para", "pra", "no", "na", "qual", "quanto", "custa", "valor", "preco", "esta", "tem", "voces", "voce", "me", "diz", "fala", "produto", "sai", "ainda", "disponivel", "estoque", "por", "que", "e", "eh", "r"]);

/** Produtos que a pergunta cita, do mais para o menos parecido (cada palavra do nome que aparece na pergunta vale um ponto). */
export function acharProdutos(produtos: ProdutoDaLoja[], pergunta: string): ProdutoDaLoja[] {
  const q = palavras(pergunta).filter((w) => !SOLTAS.has(w));
  if (!q.length) return [];
  const casa = (a: string, b: string) => a === b || (Math.min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)));
  const pontuados = produtos.map((p) => {
    const nome = palavras(p.nome).filter((w) => w.length >= 3 && !SOLTAS.has(w));
    const acertos = nome.filter((w) => q.some((x) => casa(w, x))).length;
    // acertar todas as palavras do nome pesa mais que acertar uma de um nome longo
    return { p, nota: acertos + (acertos > 0 && acertos === nome.length ? 1 : 0) };
  }).filter((x) => x.nota > 0);
  pontuados.sort((a, b) => b.nota - a.nota);
  const topo = pontuados[0]?.nota ?? 0;
  return pontuados.filter((x) => x.nota >= Math.max(1, topo - 0)).map((x) => x.p);
}

export const perguntaDeProduto = (t: string) => /\b(valor|pre[cç]o|quanto|custa|custando|sai por|dispon[ií]vel|tem em estoque|em estoque|esgotad)/i.test(t);

const linha = (p: ProdutoDaLoja) =>
  `${p.nome}: ${p.precoCentavos == null ? "preço não informado" : reais(p.precoCentavos)}` +
  (p.antigoCentavos && p.precoCentavos != null && p.antigoCentavos > p.precoCentavos ? ` (em promoção, de ${reais(p.antigoCentavos)})` : "") +
  (p.esgotado ? " — esgotado no momento" : "");

/** Resposta direta (sem IA, instantânea) para "qual o valor do X?". Null quando não é pergunta de preço ou o produto não está no catálogo. */
export function respostaDeProduto(produtos: ProdutoDaLoja[], pergunta: string): string | null {
  if (!produtos.length || !perguntaDeProduto(pergunta)) return null;
  const achados = acharProdutos(produtos, pergunta).slice(0, 4);
  if (!achados.length) return null;
  if (achados.length === 1) return linha(achados[0]!) + ".";
  return `Encontrei ${achados.length} produtos parecidos:\n${achados.map((p) => `• ${linha(p)}`).join("\n")}`;
}

/** Trecho para a IA: produtos citados com detalhe e uma lista curta dos demais (sempre limitado). */
export function contextoDeCatalogo(produtos: ProdutoDaLoja[], pergunta: string, max = 1500): string {
  if (!produtos.length) return "";
  const citados = acharProdutos(produtos, pergunta).slice(0, 5);
  const partes = [
    "Catálogo atual da loja (dados do site, atualizados há pouco; use estes preços e não invente outros):",
    ...citados.map((p) => `- ${linha(p)}${p.descricao ? `. ${p.descricao.slice(0, 160)}` : ""}`)
  ];
  const ids = new Set(citados.map((p) => p.id));
  const resto = produtos.filter((p) => !ids.has(p.id));
  let saida = partes.join("\n");
  if (resto.length) {
    const lista = resto.map((p) => `${p.nome} ${reais(p.precoCentavos)}${p.esgotado ? " (esgotado)" : ""}`);
    let extra = "\nOutros produtos: ";
    for (const l of lista) { if ((saida + extra + l).length > max) break; extra += l + "; "; }
    if (extra.length > 20) saida += extra;
  }
  return saida.slice(0, max);
}
