// Cadastrar e atualizar produtos da loja (mesmas regras do celular, Loja.kt). Lógica pura, testável sem navegador.

export const CATEGORIAS: [string, string][] = [
  ["geis_lubrificantes", "Géis e lubrificantes"],
  ["excitantes_retardantes", "Excitantes e retardantes"],
  ["vibradores", "Vibradores"],
  ["masturbadores", "Masturbadores"],
  ["fantasias_lingerie", "Fantasias e lingerie"],
  ["acessorios_bdsm", "Acessórios BDSM"],
  ["kits_casais", "Kits para casais"]
];

const semAcento = (t: string) => t.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

/** Adivinha a categoria pelo nome (o formulário deixa trocar). */
export function categoriaProvavel(nome: string): string {
  const n = semAcento(nome);
  const tem = (r: RegExp) => r.test(n);
  if (tem(/\b(gel|geis|lubrificante|oleo|massagem|hidratante)/)) return "geis_lubrificantes";
  if (tem(/\b(excitante|retardante|afrodisiaco|spray|esquenta|esfria|vibrante)/)) return "excitantes_retardantes";
  if (tem(/\b(vibrador|bullet|protese|estimulador|sugador|varinha|ovo)/)) return "vibradores";
  if (tem(/\b(masturbador|anel|egg|lanterna)/)) return "masturbadores";
  if (tem(/\b(lingerie|calcinha|sutia|body|camisola|fantasia|conjunto|cinta|meia|baby ?doll|corset|espartilho)/)) return "fantasias_lingerie";
  if (tem(/\b(algema|chicote|venda|mordaca|coleira|plug|bdsm|corda|palmatoria)/)) return "acessorios_bdsm";
  if (tem(/\b(kit|casal|casais|presente|jogo|dado|baralho)/)) return "kits_casais";
  return "geis_lubrificantes";
}

/** "29,90", "R$ 1.299,00", "29.9" → centavos. Vazio → null (sem valor); inválido → NaN. */
export function centavosDe(texto: string): number | null {
  const t = texto.replace(/R\$|\s/g, "");
  if (!t) return null;
  let n = t;
  if (/,/.test(n)) n = n.replace(/\./g, "").replace(",", ".");
  else if (/^\d{1,3}(\.\d{3})+$/.test(n)) n = n.replace(/\./g, "");
  if (!/^\d+(\.\d{1,2})?$/.test(n)) return NaN;
  return Math.round(parseFloat(n) * 100);
}

export const dinheiro = (c: number | null | undefined) => (typeof c === "number" ? `R$ ${(c / 100).toFixed(2).replace(".", ",").replace(/\B(?=(\d{3})+(?!\d))/g, ".")}` : "—");

export interface NovoProduto {
  nome: string; categoria: string; descricao: string; precoCentavos: number | null; estoque: number | null;
  imagemUrl: string | null; ativo: boolean; custoCentavos: number | null;
}

/** Corpo do product_save para um produto novo (sem id). */
export function corpoDoNovo(p: NovoProduto): Record<string, unknown> {
  const corpo: Record<string, unknown> = {
    category: p.categoria, name: p.nome.trim().slice(0, 120), description: p.descricao.trim().slice(0, 300) || null,
    priceCents: p.precoCentavos, compareAtCents: null, specifications: null, stockQuantity: p.estoque == null ? null : Math.max(0, p.estoque),
    weightGrams: null, widthCm: null, heightCm: null, lengthCm: null, imageUrl: p.imagemUrl, active: p.ativo
  };
  if (p.custoCentavos != null) corpo.costCents = p.custoCentavos;
  return corpo;
}

/** Campos que o painel exige para salvar (o site troca o produto inteiro). */
const CAMPOS_PRODUTO = ["id", "category", "name", "description", "priceCents", "compareAtCents", "specifications", "stockQuantity", "weightGrams", "widthCm", "heightCm", "lengthCm", "imageUrl", "active"];

export interface Mudancas {
  nome?: string; descricao?: string; precoCentavos?: number | null; custoCentavos?: number | null; imagemUrl?: string | null; ativo?: boolean;
}

/**
 * Monta o product_save a partir do produto como veio do site, trocando só o que mudou.
 * Se faltar algum campo do produto, devolve null: melhor não salvar do que apagar algo sem querer.
 * `semCampoDeCusto`: o site ainda não guarda custo (a mudança de custo não pode ser feita).
 */
export function corpoDeAtualizacao(bruto: Record<string, unknown> | null | undefined, m: Mudancas): { corpo: Record<string, unknown> | null; semCampoDeCusto: boolean } {
  if (!bruto || CAMPOS_PRODUTO.some((k) => !(k in bruto))) return { corpo: null, semCampoDeCusto: false };
  const corpo: Record<string, unknown> = {};
  for (const k of CAMPOS_PRODUTO) corpo[k] = bruto[k];
  if ("costCents" in bruto) corpo.costCents = bruto.costCents; // não apaga o custo ao mudar outra coisa
  let semCampoDeCusto = false;
  if (m.nome !== undefined) corpo.name = m.nome.trim().slice(0, 120);
  if (m.descricao !== undefined) corpo.description = m.descricao.trim().slice(0, 300) || null;
  if (m.precoCentavos !== undefined) {
    corpo.priceCents = m.precoCentavos;
    // mudou o preço normal: se estava em promoção, a promoção acaba (o "de" antigo deixaria de fazer sentido)
    if (corpo.compareAtCents != null) corpo.compareAtCents = null;
  }
  if (m.imagemUrl !== undefined) corpo.imageUrl = m.imagemUrl;
  if (m.ativo !== undefined) corpo.active = m.ativo;
  if (m.custoCentavos !== undefined) {
    if (!("costCents" in bruto)) semCampoDeCusto = true;
    else corpo.costCents = m.custoCentavos;
  }
  return { corpo, semCampoDeCusto };
}

export type IntencaoDaLoja = "cadastrar" | "atualizar" | null;

/** Frases que abrem a janelinha em vez de virar conversa: "cadastrar produto", "atualizar produto", "alterar o estoque"... */
export function intencaoDaLoja(texto: string): IntencaoDaLoja {
  const t = semAcento(texto).replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
  if (t.split(" ").length > 8) return null; // frase longa é conversa (ex.: pergunta sobre como cadastrar)
  if (/^(venus )?(quero |preciso |pode |vamos |vou )?(cadastr\w+|adicion\w+|cri\w+|inclu\w+)( um| o| outro)?( novo)? produto\b/.test(t) || /^(venus )?(novo produto|cadastro de produto)\b/.test(t)) return "cadastrar";
  if (/^(venus )?(quero |preciso |pode |vamos |vou )?(atualiz\w+|alter\w+|edit\w+|mud\w+|troc\w+)( um| o| os| a| as)? (produtos?|estoque|preco|precos|valor|foto|descricao|custo)\b/.test(t)) return "atualizar";
  return null;
}
