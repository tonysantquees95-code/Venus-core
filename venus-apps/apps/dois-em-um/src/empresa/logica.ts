// Cadastro da empresa: o mesmo do app do celular, que viaja pelo Vetorial ID (doc "empresa"). Lógica pura, testável sem navegador.

export interface Empresa {
  dono: string;
  loja: string;
  cidade: string;
  uf: string;
  sobre: string;
  site: string;
  ramo: string;
  /** Regras de venda (pagamento, frete, trocas…): cadastradas no celular e guardadas aqui sem perder nada. */
  regras: Record<string, any> | null;
  regrasConfiguradas: boolean;
}

export const EMPRESA_VAZIA: Empresa = { dono: "", loja: "", cidade: "", uf: "", sobre: "", site: "", ramo: "", regras: null, regrasConfiguradas: false };

const texto = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");
const objeto = (v: unknown): Record<string, any> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, any>) : null);

/** Aceita qualquer coisa vinda da nuvem ou do disco e devolve um cadastro válido. */
export function limparEmpresa(v: unknown): Empresa {
  const o = objeto(v);
  if (!o) return { ...EMPRESA_VAZIA };
  return {
    dono: texto(o.dono, 80),
    loja: texto(o.loja, 120),
    cidade: texto(o.cidade, 80),
    uf: texto(o.uf, 2).toUpperCase(),
    sobre: texto(o.sobre, 1500),
    site: texto(o.site, 200),
    ramo: texto(o.ramo, 120),
    regras: objeto(o.regras),
    regrasConfiguradas: o.regrasConfiguradas === true
  };
}

/** Só há o que mandar para a nuvem depois que o nome da loja foi preenchido (não sobrescreve o cadastro do celular com um vazio). */
export function empresaPreenchida(e: Empresa): boolean {
  return e.loja.length > 0 || e.regras !== null;
}

/** Normaliza o endereço do site como o app do celular faz: "minhaloja.com.br/" → "https://minhaloja.com.br". */
export function normalizarSite(t: string): string {
  const s = t.trim().replace(/\/+$/, "");
  if (!s) return "";
  return /^https?:\/\//i.test(s) ? s : `https://${s}`;
}

const reais = (centavos: unknown) => (typeof centavos === "number" ? `R$ ${(centavos / 100).toFixed(2).replace(".", ",")}` : "");

/** Resumo das regras de venda em linhas curtas (usado na tela e no contexto da conversa). */
export function regrasEmLinhas(r: Record<string, any> | null): string[] {
  if (!r) return [];
  const l: string[] = [];
  const p = objeto(r.pagamento);
  if (p) {
    if (typeof p.pix_desconto_pct === "number" && p.pix_desconto_pct > 0) l.push(`Pix com ${p.pix_desconto_pct}% de desconto${p.pix_em_promocao ? " (vale também em promoção)" : ""}`);
    if (typeof p.cartao_parcelas_max === "number" && p.cartao_parcelas_max > 0) l.push(`Cartão em até ${p.cartao_parcelas_max}x${typeof p.parcelas_sem_juros === "number" && p.parcelas_sem_juros > 0 ? ` (${p.parcelas_sem_juros}x sem juros)` : ""}`);
  }
  const f = objeto(r.frete);
  if (f) {
    if (Array.isArray(f.metodos) && f.metodos.length) l.push(`Frete: ${f.metodos.join(", ")}`);
    const gratis = reais(f.gratis_acima_de_centavos);
    if (gratis) l.push(`Frete grátis acima de ${gratis}`);
    if (typeof f.prazo_postagem_dias === "number") l.push(`Postagem em até ${f.prazo_postagem_dias} dia(s)`);
  }
  const rt = objeto(r.retirada);
  if (rt?.disponivel) l.push(`Retirada em ${rt.cidade || "loja"}${rt.como ? ` (${rt.como})` : ""}`);
  const e = objeto(r.envio);
  if (e?.embalagem_discreta) l.push("Embalagem discreta");
  const t = objeto(r.trocas);
  if (t) {
    if (typeof t.arrependimento_dias === "number") l.push(`Arrependimento em ${t.arrependimento_dias} dias`);
    if (typeof t.garantia === "string" && t.garantia.trim()) l.push(`Garantia: ${t.garantia.trim()}`);
  }
  const a = objeto(r.atendimento);
  if (a) {
    if (Array.isArray(a.canais) && a.canais.length) l.push(`Atendimento: ${a.canais.join(", ")}${typeof a.horario === "string" && a.horario ? ` — ${a.horario}` : ""}`);
  }
  if (typeof r.observacoes === "string" && r.observacoes.trim()) l.push(r.observacoes.trim());
  return l;
}

/** Texto que acompanha cada conversa para a Vênus saber de quem está falando. Vazio se não há cadastro. */
export function contextoDaEmpresa(e: Empresa): string {
  if (!empresaPreenchida(e)) return "";
  const linhas = [
    `Você é a Vênus, assistente pessoal e comercial${e.dono ? ` de ${e.dono}` : ""}. Fale em português do Brasil, de forma direta.`,
    e.loja ? `Loja: ${e.loja}${e.cidade ? `, ${e.cidade}${e.uf ? `/${e.uf}` : ""}` : ""}.` : "",
    e.ramo ? `Ramo: ${e.ramo}.` : "",
    e.site ? `Site: ${e.site}.` : "",
    e.sobre ? `Sobre a empresa: ${e.sobre}` : "",
    ...(e.regrasConfiguradas ? regrasEmLinhas(e.regras).map((x) => `- ${x}`) : ["As regras de venda ainda não foram cadastradas: não invente prazos, descontos nem políticas."])
  ].filter(Boolean);
  return linhas.join("\n").slice(0, 3900);
}
