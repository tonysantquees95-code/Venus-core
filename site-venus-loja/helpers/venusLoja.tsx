/**
 * Cérebro da "Vênus da Loja": entende frases sobre a loja e monta as respostas.
 * É a mesma lógica do app Android da Vênus (Loja.kt e ComandosLocais.kt), em TypeScript,
 * para funcionar no navegador de qualquer celular (inclusive iPhone) dentro do painel.
 * Não faz chamadas de rede: recebe pedidos e produtos já carregados.
 */

// ------------------------------------------------------------------ tipos
export type VLItem = { name: string; quantity: number; unitPriceCents: number };
export type VLPedido = {
  id: number;
  status: string;
  totalCents: number;
  customerName: string;
  customerPhone: string;
  deliveryMethod: string;
  createdAt: Date;
  paidAt: Date | null;
  trackingCode: string | null;
  items: VLItem[];
};
export type VLProduto = {
  id: number;
  name: string;
  active: boolean;
  stockQuantity: number | null;
  priceCents: number | null;
  compareAtCents: number | null;
  /** O produto como veio do painel (todos os campos), para salvar sem perder nada. */
  bruto: Record<string, unknown>;
};

export type VLIntencao =
  | { tipo: "vendas"; periodo: "hoje" | "ontem" | "semana" | "mes" }
  | { tipo: "pedidos" }
  | { tipo: "pedido"; id: number }
  | { tipo: "acabando" }
  | { tipo: "estoque_geral" }
  | { tipo: "estoque"; produto: string }
  | { tipo: "retiradas" }
  | { tipo: "agendar"; pedido: number }
  | { tipo: "entregue"; pedido: number }
  | { tipo: "alterar"; mudanca: VLMudanca; produto: string; valor: number | null }
  | { tipo: "novidades" }
  | { tipo: "ajuda" }
  | { tipo: "saudacao" }
  | { tipo: "obrigado" };

export type VLMudanca = "estoque_somar" | "estoque_tirar" | "estoque_definir" | "preco" | "ativar" | "desativar";

/** Uma mudança esperando o "sim". valor: quantidade final do estoque ou preço em centavos. */
export type VLAlteracao =
  | { tipo: "estoque"; produto: VLProduto; valor: number }
  | { tipo: "preco"; produto: VLProduto; valor: number }
  | { tipo: "ativar" | "desativar"; produto: VLProduto }
  | { tipo: "entregue"; pedido: VLPedido };

export type VLAcao = { rotulo: string; url: string };
export type VLResposta = { texto: string; alteracao?: VLAlteracao; acoes?: VLAcao[] };

// ------------------------------------------------------------------ texto
function normalizar(s: string): string {
  return s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
}

function limpar(frase: string): string {
  return normalizar(frase)
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^(?:ola |oi |ei |hey )?(?:venus|vênus)[,!.]?\s+/, "")
    .replace(/^(?:por favor|por gentileza)[,]?\s+/, "")
    .replace(/[\s,]+(?:por favor|pra mim|para mim)$/, "")
    .replace(/[\s.!?;:,]+$/, "")
    .replace(/^[\s.!?;:,-]+/, "");
}

const UNIDADES: Record<string, number> = {
  zero: 0, um: 1, uma: 1, dois: 2, duas: 2, tres: 3, quatro: 4, cinco: 5, seis: 6, sete: 7, oito: 8, nove: 9,
  dez: 10, onze: 11, doze: 12, treze: 13, catorze: 14, quatorze: 14, quinze: 15, dezesseis: 16, dezessete: 17,
  dezoito: 18, dezenove: 19, vinte: 20, trinta: 30, quarenta: 40, cinquenta: 50, sessenta: 60, setenta: 70,
  oitenta: 80, noventa: 90, cem: 100, cento: 100, duzentos: 200, duzentas: 200, trezentos: 300, quatrocentos: 400,
  quinhentos: 500, seiscentos: 600, setecentos: 700, oitocentos: 800, novecentos: 900,
};

/** "duzentos e cinquenta" → "250"; números já em dígitos ficam como estão. */
function numerosPorExtenso(texto: string): string {
  const palavras = texto.split(" ").filter(Boolean);
  const saida: string[] = [];
  let total = 0;
  let parcial = 0;
  let dentro = false;
  const fechar = () => {
    if (dentro) saida.push(String(total + parcial));
    total = 0;
    parcial = 0;
    dentro = false;
  };
  for (let k = 0; k < palavras.length; k++) {
    const p = palavras[k];
    if (p in UNIDADES) {
      parcial += UNIDADES[p];
      dentro = true;
    } else if (p === "mil") {
      total += (parcial === 0 ? 1 : parcial) * 1000;
      parcial = 0;
      dentro = true;
    } else if (p === "e" && dentro && k + 1 < palavras.length && (palavras[k + 1] in UNIDADES || palavras[k + 1] === "mil")) {
      // "cento e vinte": continua o mesmo número
    } else {
      fechar();
      saida.push(p);
    }
  }
  fechar();
  return saida.join(" ");
}

/** "129,90" → 12990; "129" + "90" (de "129 e 90") → 12990; "50" → 5000. */
function centavos(valor: string, extra = ""): number | null {
  const v = valor.replace(",", ".");
  const reais = parseInt(v.split(".")[0], 10);
  if (Number.isNaN(reais) || reais > 100000) return null;
  let cents = 0;
  if (v.includes(".")) {
    cents = parseInt((v.split(".")[1] + "0").slice(0, 2), 10);
    if (Number.isNaN(cents)) return null;
  } else if (extra) {
    cents = parseInt(extra.padStart(2, "0").slice(-2), 10) || 0;
  }
  return reais * 100 + cents;
}

// ------------------------------------------------------------------ frases
const DE = "(?:de|do|da|dos|das|no|na|nos|nas|ao|a|o)";
const QTD = "(\\d{1,4})(?: (?:unidades?|pecas?|itens?))?";
const VALOR = "(?:r\\$ ?)?(\\d{1,6}(?:[.,]\\d{1,2})?)(?: ?reais| ?real)?(?: (?:e|com) (\\d{1,2})(?: centavos?)?)?";
const PEDIDO_N = "pedido (?:numero |n |#)?(\\d{1,6})";
const VERBO_MUDAR =
  "(?:muda|mude|altera|altere|atualiza|atualize|ajusta|ajuste|coloca|coloque|deixa|deixe|define|defina|corrige|corrija|poe|bota|troca|troque|passa|passe)";

const R = {
  somar: new RegExp(
    `^(?:coloca|coloque|bota|bote|poe|adiciona|adicione|acrescenta|acrescente|soma|some|entrou|entraram|chegou|chegaram|` +
      `repoe|reponha|repor|aumenta|aumente|inclui|inclua)(?: mais)? ${QTD}(?: a mais)?(?: (?:no|ao|em) estoque)? ${DE} (.+?)(?: (?:no|ao|em) estoque)?$`,
  ),
  tirar: new RegExp(
    `^(?:tira|tire|retira|retire|remove|remova|diminui|diminua|baixa|baixe|desconta|desconte|subtrai|subtraia)(?: mais)? ${QTD}` +
      `(?: (?:do|no) estoque)? ${DE} (.+?)(?: (?:do|no) estoque)?$`,
  ),
  vendi: new RegExp(`^(?:eu )?vendi ${QTD}(?: ${DE})? (.+?)(?: (?:por fora|na mao|pessoalmente|fora do site|na rua|no whatsapp|no zap))?$`),
  definirA: new RegExp(
    `^(?:${VERBO_MUDAR} )?o estoque (?:de|do|da|dos|das) (.+?) (?:para|pra|em|como|agora e|e|eh|ficou|ficou com|esta|ta|passa a ser) (?:de )?(\\d{1,4})(?: unidades?)?$`,
  ),
  definirB: new RegExp(
    `^(?:agora )?(?:tenho|fiquei com|ficaram|sobraram|restam|sobrou|restou|tem) (?:so )?(\\d{1,4}) (?:unidades?|pecas?) (?:de|do|da|dos|das) (.+?)(?: (?:no|em) estoque)?$`,
  ),
  preco: [
    new RegExp(`^${VERBO_MUDAR} o (?:preco|valor) (?:de|do|da|dos|das) (.+?) (?:para|pra|em|por|a) ${VALOR}$`),
    new RegExp(`^o (?:preco|valor) (?:de|do|da|dos|das) (.+?) (?:agora )?(?:e|eh|vai ser|passa a ser|fica|vai ficar) (?:de )?${VALOR}$`),
    new RegExp(`^(?:o |a |os |as )?(.+?) agora (?:custa|vai custar|passa a custar|sai por|fica por) ${VALOR}$`),
  ],
  desativar: [
    /^(?:desativa|desative|desativar|esconde|esconda|oculta|oculte|pausa|pause|tira|tire|remove|remova) (?:o |a |os |as )?(?:produto |anuncio )?(.+?) (?:do site|da loja|do catalogo|das vendas|da venda)$/,
    /^(?:desativa|desative|desativar|esconde|esconda|oculta|oculte|pausa|pause) o (?:produto|anuncio) (.+)$/,
  ],
  ativar: [
    /^(?:ativa|ative|ativar|reativa|reative|mostra|mostre|volta|volte|coloca|coloque|bota|poe|publica|publique) (?:a mostrar |a vender |de volta )?(?:o |a |os |as )?(?:produto |anuncio )?(.+?) (?:de volta )?(?:no site|na loja|no catalogo|a venda)$/,
    /^(?:ativa|ative|ativar|reativa|reative) o (?:produto|anuncio) (.+)$/,
  ],
  agendar: [
    new RegExp(`^(?:agenda|agende|agendar|marca|marque|combina|combine)(?: a)? retirada (?:do|no|para o|pro) ${PEDIDO_N}$`),
    new RegExp(
      `^(?:chama|chame|manda mensagem|mande mensagem|manda uma mensagem|mande uma mensagem|fala|fale|escreve|escreva|chama no whatsapp|chame no whatsapp)` +
        `(?: (?:para|pra|com|a|o))? (?:o |a )?(?:cliente|comprador|compradora) do ${PEDIDO_N}$`,
    ),
  ],
  entregue: [
    new RegExp(`^(?:marca|marque|coloca|coloque|muda|mude|poe|bota) (?:o )?${PEDIDO_N} (?:como|para|pra) (?:entregue|retirado)$`),
    new RegExp(`^(?:o )?${PEDIDO_N} (?:ja )?(?:foi|esta|ta) (?:entregue|retirado)$`),
    /^(?:o |a )?(?:cliente|compradora?) do pedido (?:numero )?(\d{1,6}) (?:ja )?(?:retirou|buscou|pegou|recebeu)$/,
  ],
  pedido: [
    new RegExp(`^(?:como (?:esta|ta|anda) |me (?:fala|mostra|diz) |mostra |mostre |abre |abra |ver |detalhes do |e o |o )?(?:o )?${PEDIDO_N}$`),
    new RegExp(`^(?:o que (?:tem|veio|foi) no|quem (?:fez|comprou) o|de quem e o|qual o status do|status do) ${PEDIDO_N}$`),
  ],
  retiradas:
    /\b(?:retiradas|retirada pendente|retiradas? (?:para|pra|a) agendar|pedidos? (?:de|para|pra) retirada|pedidos? (?:para|pra) retirar|tem (?:alguma )?retirada|quem (?:vai|precisa) retirar)\b/,
  vendas:
    /\b(?:como (?:foram|estao|tao|vao|anda|andam) as vendas|quanto (?:eu |nos |a gente )?(?:vendi|vendemos|faturei|faturamos|a loja vendeu|a loja faturou)|vendas (?:de |d[ao] )?(?:hoje|ontem|semana|mes|essa semana|esse mes|desta semana|deste mes)|resumo (?:da loja|das vendas|do dia)|como (?:esta|ta|foi) a loja|faturamento (?:de |d[ao] )?(?:hoje|ontem|semana|mes)|quantas vendas|vendas)\b/,
  pedidos:
    /\b(?:tem (?:algum )?pedidos? novos?|tem pedido|chegou (?:algum )?pedido|pedidos? (?:novos?|pendentes?|para (?:separar|enviar|mandar)|pra (?:separar|enviar|mandar)|de hoje|em aberto)|(?:quais|que) pedidos (?:eu |nos |a gente )?(?:tenho|temos|preciso|precisamos|tenho que|falta)|o que (?:eu |nos |a gente )?(?:tenho|temos|preciso|precisamos) (?:que |para |pra )?(?:enviar|separar|mandar)|^pedidos$)\b/,
  acabando:
    /\b(?:(?:quais|que) produtos (?:estao|tao|que estao|que tao) (?:acabando|esgotados?|em falta|com (?:o )?estoque baixo|no fim)|o que (?:esta|ta) (?:acabando|esgotado|em falta)(?: na loja| no estoque)?|produtos? (?:esgotados?|acabando|em falta)|estoque baixo|o que (?:eu |nos )?(?:preciso|precisamos|tenho que|temos que) (?:repor|comprar)(?: para a loja| pra loja)?|esgotados|acabando)\b/,
  estoqueGeral: new RegExp(
    "^(?:(?:e |e ai |me (?:diz|fala|conta) )?como (?:esta|ta|anda|vai|estao|tao|ficou|que esta|que ta) (?:o |a )?(?:meu |minha |nosso |o meu |o nosso )?estoque(?: (?:da|na) loja)?(?: hoje| agora)?|" +
      "(?:me (?:da|de|passa|passe|faz|faca) )?(?:um |o )?(?:resumo|situacao|relatorio|panorama|balanco|visao geral|levantamento) (?:do |de |da )?(?:meu |nosso |o )?estoque(?: (?:da|na) loja)?|" +
      "estoque geral|(?:o )?(?:meu|nosso) estoque|(?:o )?estoque(?: da loja)?|" +
      "(?:quantos|quantas) (?:produtos|itens|unidades|pecas|coisas|mercadorias)(?: diferentes)? (?:eu |nos )?(?:tenho|tem|ha|temos|existem|estao)" +
      "(?: (?:no|em) estoque| na loja| cadastrad[oa]s| no site| ao todo| no total| a venda)*|" +
      "o que (?:eu |nos )?(?:tenho|tem|temos|ha) (?:no|em) estoque|" +
      "(?:me )?(?:mostra|mostre|fala|diz|ve|veja|confere|confira|checa|cheque) (?:o |como (?:esta|ta) o )?(?:meu |nosso )?estoque(?: (?:da|na) loja)?)$",
  ),
  estoque: [
    /^(?:quanto|quantos|quantas) (?:tem|tenho|temos|ha|ainda tem|ainda tenho|sobrou|sobraram|resta|restam) (?:de |do |da |no |em )?estoque (?:de |do |da |dos |das )(.+)$/,
    /^(?:qual (?:e |eh )?o )?estoque (?:de |do |da |dos |das )(.+)$/,
    /^(?:quantos|quantas) (.+?) (?:eu |nos )?(?:tem|tenho|temos|ainda tenho|ainda tem|sobraram|restam|ha)(?: (?:no|em) estoque| na loja)?$/,
    /^(?:tem|ainda tem|tenho|ainda tenho|temos) (.+?) (?:no|em) estoque$/,
  ],
  novidades: /^(?:novidades|o que (?:tem de novo|ha de novo|mudou)|resumo|bom dia|boa tarde|boa noite|como estao as coisas|e ai)$/,
  ajuda: /\b(?:ajuda|o que (?:voce|vc) (?:faz|sabe fazer|pode fazer)|como (?:eu )?(?:uso|funciona)|comandos|exemplos)\b/,
  saudacao: /^(?:oi|ola|opa|hey|ei|oi venus|ola venus)$/,
  obrigado: /^(?:obrigad[oa]|valeu|brigad[oa]|obrigad[oa] venus|valeu venus|perfeito|otimo|show)$/,
};

const PALAVRAS_GERAIS = new Set(["produtos", "produto", "itens", "item", "unidades", "pecas", "coisas", "mercadorias", "mercadoria"]);

function nomeProduto(s: string): string {
  return s.trim().replace(/^(?:o produto|o|a|os|as|do|da|dos|das|de|produto)\s+/, "").trim();
}

function alteracaoEm(c: string): VLIntencao | null {
  const alterar = (mudanca: VLMudanca, prod: string, valor: number | null = null): VLIntencao | null => {
    const produto = nomeProduto(prod);
    return produto.length < 3 ? null : { tipo: "alterar", mudanca, produto, valor };
  };
  for (const r of R.agendar) {
    const m = c.match(r);
    if (m) return { tipo: "agendar", pedido: parseInt(m[1], 10) };
  }
  for (const r of R.entregue) {
    const m = c.match(r);
    if (m) return { tipo: "entregue", pedido: parseInt(m[1], 10) };
  }
  if (/\b(?:unidades?|estoque|pecas?)\b/.test(c)) {
    let m = c.match(R.somar);
    if (m) return alterar("estoque_somar", m[2], parseInt(m[1], 10));
    m = c.match(R.tirar);
    if (m) return alterar("estoque_tirar", m[2], parseInt(m[1], 10));
  }
  let m = c.match(R.vendi);
  if (m) return alterar("estoque_tirar", m[2], parseInt(m[1], 10));
  m = c.match(R.definirA);
  if (m) return alterar("estoque_definir", m[1], parseInt(m[2], 10));
  m = c.match(R.definirB);
  if (m) return alterar("estoque_definir", m[2], parseInt(m[1], 10));
  for (const r of R.preco) {
    const p = c.match(r);
    if (!p) continue;
    const v = centavos(p[2], p[3] ?? "");
    if (v !== null) return alterar("preco", p[1], v);
  }
  for (const r of R.desativar) {
    const p = c.match(r);
    if (p) return alterar("desativar", p[1]);
  }
  for (const r of R.ativar) {
    const p = c.match(r);
    if (p) return alterar("ativar", p[1]);
  }
  return null;
}

function consultaEm(c: string): VLIntencao | null {
  if (R.saudacao.test(c)) return { tipo: "saudacao" };
  if (R.obrigado.test(c)) return { tipo: "obrigado" };
  if (R.novidades.test(c)) return { tipo: "novidades" };
  for (const r of R.pedido) {
    const m = c.match(r);
    if (m) return { tipo: "pedido", id: parseInt(m[1], 10) };
  }
  if (R.retiradas.test(c)) return { tipo: "retiradas" };
  if (R.estoqueGeral.test(c)) return { tipo: "estoque_geral" };
  if (R.acabando.test(c)) return { tipo: "acabando" };
  if (R.pedidos.test(c)) return { tipo: "pedidos" };
  for (const r of R.estoque) {
    const m = c.match(r);
    if (!m) continue;
    const produto = nomeProduto(m[1]);
    const geral = produto.replace(/\b(?:diferentes|no total|ao todo|cadastrad[oa]s)\b/g, "").trim();
    if (PALAVRAS_GERAIS.has(geral)) return { tipo: "estoque_geral" };
    if (produto.length >= 3) return { tipo: "estoque", produto };
  }
  if (R.vendas.test(c)) {
    const periodo = /\bontem\b/.test(c) ? "ontem" : /\b(?:semana|7 dias|sete dias)\b/.test(c) ? "semana" : /\b(?:mes|mensal)\b/.test(c) ? "mes" : "hoje";
    return { tipo: "vendas", periodo };
  }
  if (/\bestoque\b/.test(c)) return { tipo: "estoque_geral" };
  if (R.ajuda.test(c)) return { tipo: "ajuda" };
  return null;
}

function interpretar(frase: string): VLIntencao | null {
  const c = limpar(frase);
  if (!c) return null;
  // primeiro como foi dito (nomes como "Kit Dois Corações" ficam intactos), depois com os números por extenso convertidos
  const convertida = numerosPorExtenso(c);
  return (
    alteracaoEm(c) ??
    (convertida !== c ? alteracaoEm(convertida) : null) ??
    consultaEm(c) ??
    (convertida !== c ? consultaEm(convertida) : null)
  );
}

/** "sim", "pode", "confirma" → true; "não", "cancela" → false; outra coisa → null. */
function simOuNao(fala: string): boolean | null {
  const c = normalizar(fala).replace(/[^a-z ]/g, " ").replace(/\s+/g, " ").trim();
  if (/\b(?:nao|cancela|cancelar|cancele|esquece|negativo)\b/.test(c) || /^(?:no|para|pare|deixa|deixa pra la|nem|errado)\b/.test(c)) return false;
  if (
    /^(?:sim|s|pode|pode sim|pode ser|confirma|confirmo|confirmar|isso|isso mesmo|ok|okay|beleza|claro|com certeza|certo|manda|faz|faca|bora|exato|positivo|correto|ta|ta bom|esta bom|aham|uhum|quero)\b/.test(c)
  )
    return true;
  return null;
}

// ------------------------------------------------------------------ respostas
const BR = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const dinheiro = (cents: number) => BR.format(cents / 100).replace(/ /g, " ");
const plural = (n: number, um: string, varios: string) => `${n} ${n === 1 ? um : varios}`;
const primeiroNome = (nome: string) => nome.trim().split(/\s+/)[0] ?? "";

export const ESTOQUE_BAIXO = 2;
const PAGOS = new Set(["paid", "preparing", "shipped", "delivered"]);
const STATUS: Record<string, string> = {
  awaiting_payment: "aguardando pagamento",
  paid: "pago",
  preparing: "em separação",
  shipped: "enviado",
  delivered: "entregue",
  canceled: "cancelado",
};
const CONFIRMA = " Toque em Confirmar ou diga sim. Para desistir, diga não.";

function intervalo(periodo: string, agora: Date): [number, number] {
  const hoje = new Date(agora);
  hoje.setHours(0, 0, 0, 0);
  const dia = 24 * 3600 * 1000;
  const h = hoje.getTime();
  if (periodo === "ontem") return [h - dia, h];
  if (periodo === "semana") return [h - 6 * dia, Infinity];
  if (periodo === "mes") return [new Date(hoje.getFullYear(), hoje.getMonth(), 1).getTime(), Infinity];
  return [h, Infinity];
}

function resumoVendas(pedidos: VLPedido[], periodo: string, agora = new Date()): string {
  const [ini, fim] = intervalo(periodo, agora);
  const dentro = (d: Date) => d.getTime() >= ini && d.getTime() < fim;
  const pagos = pedidos.filter((p) => PAGOS.has(p.status) && dentro(p.paidAt ?? p.createdAt));
  const aguardando = pedidos.filter((p) => p.status === "awaiting_payment" && dentro(p.createdAt));
  const quando = { ontem: "Ontem", semana: "Nos últimos 7 dias", mes: "Neste mês" }[periodo] ?? "Hoje";
  if (pagos.length === 0) {
    const extra = aguardando.length > 0 ? ` ${plural(aguardando.length, "pedido aguardando", "pedidos aguardando")} pagamento.` : "";
    return `${quando} ${periodo === "ontem" ? "não teve" : "ainda não teve"} venda paga.${extra}`;
  }
  const total = pagos.reduce((s, p) => s + p.totalCents, 0);
  const contagem = new Map<string, number>();
  for (const p of pagos) for (const i of p.items) contagem.set(i.name, (contagem.get(i.name) ?? 0) + i.quantity);
  const mais = [...contagem.entries()].sort((a, b) => b[1] - a[1])[0];
  let texto = `${quando}: ${plural(pagos.length, "venda paga", "vendas pagas")}, total de ${dinheiro(total)}.`;
  if (pagos.length > 1) texto += ` Ticket médio de ${dinheiro(Math.round(total / pagos.length))}.`;
  if (mais && pagos.length > 1) texto += ` Mais vendido: ${mais[0]} (${mais[1]} un.).`;
  if (aguardando.length > 0) texto += ` E ${plural(aguardando.length, "pedido aguardando", "pedidos aguardando")} pagamento.`;
  return texto;
}

function pendentes(pedidos: VLPedido[]): VLResposta {
  const separar = pedidos.filter((p) => p.status === "paid" || p.status === "preparing").sort((a, b) => a.id - b.id);
  const aguardando = pedidos.filter((p) => p.status === "awaiting_payment").length;
  if (separar.length === 0) {
    return {
      texto:
        "Nenhum pedido para separar agora." +
        (aguardando > 0 ? ` Há ${plural(aguardando, "pedido aguardando", "pedidos aguardando")} pagamento.` : ""),
    };
  }
  const linhas = separar
    .slice(0, 5)
    .map((p) => {
      const itens = p.items.reduce((s, i) => s + i.quantity, 0);
      const nome = primeiroNome(p.customerName);
      return `Pedido ${p.id}${nome ? ` de ${nome}` : ""}: ${plural(itens, "item", "itens")}, ${dinheiro(p.totalCents)}, ${STATUS[p.status] ?? p.status}, ${p.deliveryMethod === "pickup" ? "retirada" : "envio"}.`;
    })
    .join(" ");
  const mais = separar.length > 5 ? ` E mais ${separar.length - 5}.` : "";
  return {
    texto: `${plural(separar.length, "pedido para separar", "pedidos para separar")}. ${linhas}${mais}`,
    acoes: [{ rotulo: "Abrir pedidos no painel", url: "/admin/pedidos" }],
  };
}

function detalhePedido(pedidos: VLPedido[], id: number): VLResposta {
  const p = pedidos.find((x) => x.id === id);
  if (!p) return { texto: `Não achei o pedido ${id}.` };
  const itens = p.items.map((i) => `${i.quantity}x ${i.name}`).join(", ");
  const entrega = p.deliveryMethod === "pickup" ? "retirada em ponto de retirada" : "envio pelos Correios";
  let texto = `Pedido ${p.id} de ${p.customerName.trim() || "cliente sem nome"}: ${STATUS[p.status] ?? p.status}, ${dinheiro(p.totalCents)}, ${entrega}. Itens: ${itens || "nenhum"}.`;
  if (p.trackingCode) texto += ` Rastreio: ${p.trackingCode}.`;
  const acoes: VLAcao[] = [];
  const zap = linkAgendar(p);
  if (p.deliveryMethod === "pickup" && zap && ["paid", "preparing", "shipped"].includes(p.status)) {
    acoes.push({ rotulo: "Agendar retirada no WhatsApp", url: zap });
  } else {
    const tel = whatsappDoCliente(p);
    if (tel) acoes.push({ rotulo: "Falar com a cliente no WhatsApp", url: `https://wa.me/${tel}` });
  }
  return { texto, acoes };
}

function acharProdutos(produtos: VLProduto[], falado: string): VLProduto[] {
  const ignorar = new Set(["estoque", "unidades", "produto", "tem", "quantos", "quantas"]);
  const raiz = (p: string) => {
    for (const s of ["ores", "eres", "oes", "aes", "es", "s"]) if (p.endsWith(s) && p.length - s.length >= 4) return p.slice(0, -s.length);
    return p;
  };
  const chaves = normalizar(falado)
    .replace(/[^a-z0-9 ]/g, " ")
    .split(/\s+/)
    .filter((w) => w.length >= 3 && !ignorar.has(w));
  if (chaves.length === 0) return [];
  const notas = produtos.map((p) => {
    const nome = normalizar(p.name);
    const n = chaves.filter((k) => new RegExp(`\\b${raiz(k).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`).test(nome)).length;
    return { p, n };
  });
  const melhor = Math.max(0, ...notas.map((x) => x.n));
  if (melhor === 0) return [];
  // com várias palavras, exige a maioria delas (evita "gel de morango" casar com qualquer "gel")
  if (chaves.length >= 3 && melhor < Math.ceil(chaves.length / 2)) return [];
  return notas.filter((x) => x.n === melhor).map((x) => x.p);
}

function linhaEstoque(p: VLProduto): string {
  const base =
    p.stockQuantity === null
      ? `${p.name}: sem controle de estoque`
      : p.stockQuantity <= 0
        ? `${p.name}: esgotado`
        : `${p.name}: ${plural(p.stockQuantity, "unidade", "unidades")}`;
  return base + (p.active ? "" : " (escondido do site)");
}

function estoqueDe(produtos: VLProduto[], falado: string): string {
  const achados = acharProdutos(produtos, falado);
  if (achados.length === 0) return `Não achei nenhum produto com "${falado}" na loja.`;
  if (achados.length === 1) return linhaEstoque(achados[0]) + ".";
  const mais = achados.length > 4 ? ` E mais ${achados.length - 4} parecidos.` : "";
  return `Achei ${achados.length} produtos: ${achados.slice(0, 4).map(linhaEstoque).join("; ")}.${mais}`;
}

function acabando(produtos: VLProduto[]): string {
  const ativos = produtos.filter((p) => p.active && p.stockQuantity !== null);
  const esgotados = ativos.filter((p) => (p.stockQuantity ?? 0) <= 0);
  const baixos = ativos.filter((p) => (p.stockQuantity ?? 0) >= 1 && (p.stockQuantity ?? 0) <= ESTOQUE_BAIXO);
  if (esgotados.length === 0 && baixos.length === 0) return "Nenhum produto acabando. O estoque está em dia.";
  const partes: string[] = [];
  if (esgotados.length)
    partes.push("Esgotados: " + esgotados.slice(0, 6).map((p) => p.name).join(", ") + (esgotados.length > 6 ? ` e mais ${esgotados.length - 6}` : ""));
  if (baixos.length)
    partes.push(
      "Acabando: " + baixos.slice(0, 6).map((p) => `${p.name} (${p.stockQuantity})`).join(", ") + (baixos.length > 6 ? ` e mais ${baixos.length - 6}` : ""),
    );
  return partes.join(". ") + ".";
}

function estoqueGeral(produtos: VLProduto[]): string {
  if (produtos.length === 0) return "Ainda não há produtos cadastrados na loja.";
  const ativos = produtos.filter((p) => p.active);
  const escondidos = produtos.length - ativos.length;
  const controlados = ativos.filter((p) => p.stockQuantity !== null);
  const unidades = controlados.reduce((s, p) => s + Math.max(0, p.stockQuantity ?? 0), 0);
  const valor = controlados.reduce((s, p) => s + Math.max(0, p.stockQuantity ?? 0) * (p.priceCents ?? 0), 0);
  const esgotados = controlados.filter((p) => (p.stockQuantity ?? 0) <= 0);
  const baixos = controlados.filter((p) => (p.stockQuantity ?? 0) >= 1 && (p.stockQuantity ?? 0) <= ESTOQUE_BAIXO);
  const semControle = ativos.length - controlados.length;
  let texto = `São ${plural(ativos.length, "produto", "produtos")} no site` + (escondidos > 0 ? ` e ${plural(escondidos, "escondido", "escondidos")}.` : ".");
  if (controlados.length) texto += ` No estoque: ${plural(unidades, "unidade", "unidades")}` + (valor > 0 ? `, que valem ${dinheiro(valor)} pelo preço de venda.` : ".");
  if (esgotados.length === 0 && baixos.length === 0) texto += " Nada esgotado nem acabando.";
  else {
    const partes: string[] = [];
    if (esgotados.length)
      partes.push(`${plural(esgotados.length, "esgotado", "esgotados")}: ` + esgotados.slice(0, 4).map((p) => p.name).join(", ") + (esgotados.length > 4 ? ` e mais ${esgotados.length - 4}` : ""));
    if (baixos.length)
      partes.push(
        `${baixos.length} acabando: ` + baixos.slice(0, 4).map((p) => `${p.name} (${p.stockQuantity})`).join(", ") + (baixos.length > 4 ? ` e mais ${baixos.length - 4}` : ""),
      );
    texto += " " + partes.join(". ") + ".";
  }
  if (semControle > 0) texto += ` ${plural(semControle, "produto está", "produtos estão")} sem controle de estoque.`;
  return texto;
}

function aguardandoRetirada(pedidos: VLPedido[]) {
  return pedidos.filter((p) => p.deliveryMethod === "pickup" && ["paid", "preparing", "shipped"].includes(p.status)).sort((a, b) => a.id - b.id);
}

function retiradas(pedidos: VLPedido[]): VLResposta {
  const lista = aguardandoRetirada(pedidos);
  const aguardando = pedidos.filter((p) => p.deliveryMethod === "pickup" && p.status === "awaiting_payment").length;
  if (lista.length === 0) {
    return {
      texto:
        "Nenhuma retirada para combinar agora." +
        (aguardando > 0 ? ` Há ${plural(aguardando, "pedido de retirada aguardando", "pedidos de retirada aguardando")} pagamento.` : ""),
    };
  }
  const linhas = lista
    .slice(0, 5)
    .map((p) => {
      const nome = primeiroNome(p.customerName);
      const itens = p.items.reduce((s, i) => s + i.quantity, 0);
      return `Pedido ${p.id}${nome ? ` de ${nome}` : ""}: ${plural(itens, "item", "itens")}, ${dinheiro(p.totalCents)}, ${STATUS[p.status] ?? p.status}.`;
    })
    .join(" ");
  const mais = lista.length > 5 ? ` E mais ${lista.length - 5}.` : "";
  const acoes: VLAcao[] = [];
  for (const p of lista.slice(0, 5)) {
    const link = linkAgendar(p);
    if (link) acoes.push({ rotulo: `Agendar pedido ${p.id} no WhatsApp`, url: link });
  }
  return { texto: `${plural(lista.length, "retirada para combinar", "retiradas para combinar")}. ${linhas}${mais}`, acoes };
}

function whatsappDoCliente(p: VLPedido): string | null {
  const tel = (p.customerPhone ?? "").replace(/\D/g, "");
  if (tel.length < 10) return null;
  return tel.startsWith("55") && tel.length > 11 ? tel : `55${tel}`;
}

/** Link do WhatsApp da cliente com a mensagem de agendamento pronta. */
function linkAgendar(p: VLPedido): string | null {
  const numero = whatsappDoCliente(p);
  if (!numero) return null;
  const nome = primeiroNome(p.customerName);
  const texto =
    `Olá${nome ? `, ${nome}` : ""}! Aqui é da Desejo de Intimidade. ` +
    `Seu pedido #${p.id} já está separado para retirada em Porto Alegre. ` +
    "Qual dia e horário ficam melhor para você? Assim que combinarmos, te passo o local certinho. 💖";
  return `https://wa.me/${numero}?text=${encodeURIComponent(texto)}`;
}

function agendar(pedidos: VLPedido[], id: number): VLResposta {
  const p = pedidos.find((x) => x.id === id);
  if (!p) return { texto: `Não achei o pedido ${id}.` };
  if (p.deliveryMethod !== "pickup") return { texto: `O pedido ${id} é de envio pelos Correios, não de retirada.` };
  if (p.status === "canceled") return { texto: `O pedido ${id} está cancelado.` };
  if (p.status === "delivered") return { texto: `O pedido ${id} já foi entregue.` };
  const link = linkAgendar(p);
  if (!link) return { texto: `O pedido ${id} não tem telefone da cliente cadastrado.` };
  const aviso = p.status === "awaiting_payment" ? " Atenção: esse pedido ainda está aguardando pagamento." : "";
  return {
    texto: `A mensagem de agendamento do pedido ${id} está pronta. Toque no botão para abrir o WhatsApp${primeiroNome(p.customerName) ? ` de ${primeiroNome(p.customerName)}` : ""} e enviar.${aviso}`,
    acoes: [{ rotulo: "Abrir WhatsApp", url: link }],
  };
}

const CAMPOS_PRODUTO = [
  "id", "category", "name", "description", "priceCents", "compareAtCents", "specifications", "stockQuantity",
  "weightGrams", "widthCm", "heightCm", "lengthCm", "imageUrl", "active",
];

/**
 * Corpo do product_save a partir do produto como veio do painel, mudando só preço ou ativo.
 * Se faltar algum campo, devolve null: melhor não salvar do que apagar algo sem querer.
 */
function corpoDoProduto(bruto: Record<string, unknown>, mudanca: "preco" | "ativar" | "desativar", valor?: number): Record<string, unknown> | null {
  if (!bruto || CAMPOS_PRODUTO.some((k) => !(k in bruto))) return null;
  const corpo: Record<string, unknown> = {};
  for (const k of CAMPOS_PRODUTO) corpo[k] = bruto[k] === undefined ? null : bruto[k];
  if (mudanca === "preco") {
    if (valor === undefined) return null;
    corpo.priceCents = valor;
  } else corpo.active = mudanca === "ativar";
  return corpo;
}

function prepararAlteracao(produtos: VLProduto[], mudanca: VLMudanca, falado: string, valor: number | null): VLResposta {
  const achados = acharProdutos(produtos, falado);
  if (achados.length === 0) return { texto: `Não achei nenhum produto com "${falado}" na loja. Nada foi mudado.` };
  if (achados.length > 1) {
    const nomes = achados.slice(0, 4).map((p) => p.name).join(", ");
    return { texto: `Achei ${achados.length} produtos parecidos: ${nomes}. Repita com o nome mais completo, para eu não mudar o errado.` };
  }
  const p = achados[0];
  const atual = p.stockQuantity;
  switch (mudanca) {
    case "estoque_somar":
    case "estoque_tirar": {
      if (valor === null || valor <= 0) return { texto: "Me diga uma quantidade maior que zero." };
      if (atual === null) return { texto: `${p.name} está sem controle de estoque. Para começar a controlar, diga: o estoque de ${p.name} é, e a quantidade.` };
      if (mudanca === "estoque_tirar" && valor > atual)
        return { texto: `${p.name} só tem ${plural(atual, "unidade", "unidades")}, não dá para tirar ${valor}. Nada foi mudado.` };
      const novo = mudanca === "estoque_somar" ? atual + valor : atual - valor;
      const verbo =
        mudanca === "estoque_somar" ? `Somar ${plural(valor, "unidade", "unidades")} ao estoque de` : `Tirar ${plural(valor, "unidade", "unidades")} do estoque de`;
      return { texto: `${verbo} ${p.name}? Fica com ${novo} (hoje tem ${atual}).${CONFIRMA}`, alteracao: { tipo: "estoque", produto: p, valor: novo } };
    }
    case "estoque_definir": {
      if (valor === null || valor < 0) return { texto: "Qual a quantidade?" };
      if (atual === valor) return { texto: `${p.name} já está com ${plural(valor, "unidade", "unidades")}. Nada para mudar.` };
      const de = atual === null ? "(hoje sem controle de estoque)" : `de ${atual}`;
      return { texto: `Mudar o estoque de ${p.name} ${de} para ${plural(valor, "unidade", "unidades")}?${CONFIRMA}`, alteracao: { tipo: "estoque", produto: p, valor } };
    }
    case "preco": {
      if (valor === null || valor <= 0) return { texto: "O preço precisa ser maior que zero." };
      if (!corpoDoProduto(p.bruto, "preco", valor)) return { texto: `Não consegui ler todos os dados de ${p.name}. Mude o preço pelo painel.` };
      const antes = p.priceCents;
      if (antes === valor) return { texto: `${p.name} já custa ${dinheiro(valor)}.` };
      let texto = antes === null ? `Definir o preço de ${p.name} em ${dinheiro(valor)}?` : `Mudar o preço de ${p.name} de ${dinheiro(antes)} para ${dinheiro(valor)}?`;
      if (antes !== null && (valor * 2 < antes || valor > antes * 2)) texto += " É bem diferente do preço atual, confira.";
      if (p.compareAtCents !== null && antes !== null && p.compareAtCents > antes && valor >= p.compareAtCents)
        texto += ` Ele está em promoção (de ${dinheiro(p.compareAtCents)}); com esse preço a promoção deixa de aparecer.`;
      return { texto: texto + CONFIRMA, alteracao: { tipo: "preco", produto: p, valor } };
    }
    case "desativar":
      if (!p.active) return { texto: `${p.name} já está escondido do site.` };
      if (!corpoDoProduto(p.bruto, "desativar")) return { texto: `Não consegui ler todos os dados de ${p.name}. Esconda pelo painel.` };
      return { texto: `Esconder ${p.name} do site? Ele deixa de aparecer para os clientes.${CONFIRMA}`, alteracao: { tipo: "desativar", produto: p } };
    case "ativar": {
      if (p.active) return { texto: `${p.name} já está aparecendo no site.` };
      if (!corpoDoProduto(p.bruto, "ativar")) return { texto: `Não consegui ler todos os dados de ${p.name}. Ative pelo painel.` };
      const aviso =
        p.priceCents === null
          ? " Atenção: ele está sem preço."
          : p.stockQuantity !== null && p.stockQuantity <= 0
            ? " Atenção: ele está sem estoque e vai aparecer como esgotado."
            : "";
      return { texto: `Mostrar ${p.name} no site de novo?${aviso}${CONFIRMA}`, alteracao: { tipo: "ativar", produto: p } };
    }
  }
}

function prepararEntregue(pedidos: VLPedido[], id: number): VLResposta {
  const p = pedidos.find((x) => x.id === id);
  if (!p) return { texto: `Não achei o pedido ${id}.` };
  if (p.status === "delivered") return { texto: `O pedido ${id} já está como entregue.` };
  if (p.status === "canceled") return { texto: `O pedido ${id} está cancelado. Nada foi mudado.` };
  if (p.status === "awaiting_payment") return { texto: `O pedido ${id} ainda está aguardando pagamento. Nada foi mudado.` };
  const nome = primeiroNome(p.customerName);
  return {
    texto: `Marcar o pedido ${id}${nome ? ` de ${nome}` : ""} (${p.deliveryMethod === "pickup" ? "retirada" : "envio"}, ${dinheiro(p.totalCents)}) como entregue?${CONFIRMA}`,
    alteracao: { tipo: "entregue", pedido: p },
  };
}

/** O que fazer agora: separar, agendar, repor. Mostrado ao abrir a tela e quando ela pede "novidades". */
function novidades(pedidos: VLPedido[], produtos: VLProduto[], agora = new Date()): VLResposta {
  const vendas = resumoVendas(pedidos, "hoje", agora);
  const separar = pedidos.filter((p) => p.status === "paid" || p.status === "preparing").length;
  const retirar = aguardandoRetirada(pedidos).length;
  const ativos = produtos.filter((p) => p.active && p.stockQuantity !== null);
  const esgotados = ativos.filter((p) => (p.stockQuantity ?? 0) <= 0).length;
  const baixos = ativos.filter((p) => (p.stockQuantity ?? 0) >= 1 && (p.stockQuantity ?? 0) <= ESTOQUE_BAIXO).length;
  const tarefas: string[] = [];
  if (separar) tarefas.push(plural(separar, "pedido para separar", "pedidos para separar"));
  if (retirar) tarefas.push(plural(retirar, "retirada para agendar", "retiradas para agendar"));
  if (esgotados) tarefas.push(plural(esgotados, "produto esgotado", "produtos esgotados"));
  if (baixos) tarefas.push(plural(baixos, "produto acabando", "produtos acabando"));
  const fazer = tarefas.length ? ` Para fazer: ${tarefas.join(", ")}.` : " Nada pendente por enquanto.";
  return { texto: vendas + fazer };
}

const AJUDA =
  "Pode falar ou escrever. Por exemplo: como foram as vendas hoje; tem pedido novo; como está o estoque; quanto tem do gel de morango; " +
  "coloca mais 5 unidades do gel; muda o preço do vibrador para 129,90; esconde o kit do site; quais retiradas estão para agendar; " +
  "agendar retirada do pedido 15; marca o pedido 15 como entregue. Antes de mudar qualquer coisa eu sempre peço confirmação.";

/** Responde a uma intenção já entendida (sem rede: recebe a loja já carregada). */
function responder(i: VLIntencao, pedidos: VLPedido[], produtos: VLProduto[], agora = new Date()): VLResposta {
  switch (i.tipo) {
    case "vendas":
      return { texto: resumoVendas(pedidos, i.periodo, agora) };
    case "pedidos":
      return pendentes(pedidos);
    case "pedido":
      return detalhePedido(pedidos, i.id);
    case "acabando":
      return { texto: acabando(produtos) };
    case "estoque_geral":
      return { texto: estoqueGeral(produtos) };
    case "estoque":
      return { texto: estoqueDe(produtos, i.produto) };
    case "retiradas":
      return retiradas(pedidos);
    case "agendar":
      return agendar(pedidos, i.pedido);
    case "entregue":
      return prepararEntregue(pedidos, i.pedido);
    case "alterar":
      return prepararAlteracao(produtos, i.mudanca, i.produto, i.valor);
    case "novidades":
      return novidades(pedidos, produtos, agora);
    case "ajuda":
      return { texto: AJUDA };
    case "saudacao":
      return { texto: "Oi! Me diga o que precisa na loja. Se quiser exemplos, diga: ajuda." };
    case "obrigado":
      return { texto: "Por nada! 💗" };
  }
}

/** Mensagem curta depois que a alteração deu certo. */
function confirmado(a: VLAlteracao): string {
  switch (a.tipo) {
    case "estoque":
      return `Pronto: ${a.produto.name} agora tem ${plural(a.valor, "unidade", "unidades")} no estoque.`;
    case "preco":
      return `Pronto: ${a.produto.name} agora custa ${dinheiro(a.valor)} no site.`;
    case "ativar":
      return `Pronto: ${a.produto.name} voltou a aparecer no site.`;
    case "desativar":
      return `Pronto: ${a.produto.name} foi escondido do site.`;
    case "entregue":
      return `Pronto: o pedido ${a.pedido.id} está marcado como entregue.`;
  }
}

const NAO_ENTENDI =
  "Não entendi. Eu cuido de vendas, pedidos, estoque, preços e retiradas. Diga ajuda para ver exemplos.";

export const venusLoja = {
  interpretar,
  responder,
  simOuNao,
  corpoDoProduto,
  confirmado,
  linkAgendar,
  novidades,
  centavos,
  NAO_ENTENDI,
  AJUDA,
};
