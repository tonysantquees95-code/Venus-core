// Cadastro da empresa: o mesmo do app do celular, que viaja pelo Vetorial ID (doc "empresa"). Lógica pura, testável sem navegador.

export interface Empresa {
  dono: string;
  loja: string;
  cidade: string;
  uf: string;
  sobre: string;
  site: string;
  ramo: string;
  /** Identidade visual criada no passo a passo da loja: logo (imagem pequena em data URL), cor principal e cor de apoio (#RRGGBB). */
  logo: string;
  cor: string;
  corApoio: string;
  /** Como vende: com site, sem site (catálogo na Vênus) ou ainda não decidiu. */
  modo: "" | "site" | "interno";
  /** Regras de venda (pagamento, frete, trocas…): cadastradas no celular e guardadas aqui sem perder nada. */
  regras: Record<string, any> | null;
  regrasConfiguradas: boolean;
}

export const EMPRESA_VAZIA: Empresa = { dono: "", loja: "", cidade: "", uf: "", sobre: "", site: "", ramo: "", logo: "", cor: "", corApoio: "", modo: "", regras: null, regrasConfiguradas: false };

const texto = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");
const objeto = (v: unknown): Record<string, any> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, any>) : null);

const corValida = (v: unknown) => (typeof v === "string" && /^#[0-9a-fA-F]{6}$/.test(v) ? v.toLowerCase() : "");

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
    logo: typeof o.logo === "string" && /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(o.logo) && o.logo.length <= 150_000 ? o.logo : "",
    cor: corValida(o.cor),
    corApoio: corValida(o.corApoio),
    modo: o.modo === "site" || o.modo === "interno" ? o.modo : "",
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

/** Ideias de cores por nicho (principal, apoio): ponto de partida para a loja; dá para escolher qualquer outra. */
export const NICHOS: { id: string; rotulo: string; paletas: [string, string][] }[] = [
  { id: "moda-feminina", rotulo: "Moda feminina", paletas: [["#d946ef", "#fdf4ff"], ["#e11d48", "#fff1f2"], ["#7c3aed", "#f5f3ff"], ["#0f172a", "#f5d0fe"]] },
  { id: "moda-masculina", rotulo: "Moda masculina", paletas: [["#1e3a8a", "#e0e7ff"], ["#111827", "#f59e0b"], ["#166534", "#dcfce7"], ["#374151", "#f3f4f6"]] },
  { id: "beleza", rotulo: "Cosméticos e beleza", paletas: [["#ec4899", "#fdf2f8"], ["#be185d", "#fce7f3"], ["#9d174d", "#fbcfe8"], ["#a16207", "#fef9c3"]] },
  { id: "intimidade", rotulo: "Sex shop e intimidade", paletas: [["#be123c", "#18181b"], ["#7c3aed", "#18181b"], ["#db2777", "#fdf2f8"], ["#111827", "#fb7185"]] },
  { id: "eletronicos", rotulo: "Eletrônicos e games", paletas: [["#0ea5e9", "#0b1220"], ["#22c55e", "#0b1220"], ["#ef4444", "#111827"], ["#6366f1", "#e0e7ff"]] },
  { id: "alimentos", rotulo: "Alimentos e bebidas", paletas: [["#ea580c", "#fff7ed"], ["#b91c1c", "#fef2f2"], ["#65a30d", "#f7fee7"], ["#78350f", "#fef3c7"]] },
  { id: "casa", rotulo: "Casa e decoração", paletas: [["#0d9488", "#f0fdfa"], ["#a16207", "#fefce8"], ["#57534e", "#fafaf9"], ["#be185d", "#fdf2f8"]] },
  { id: "pet", rotulo: "Pet shop", paletas: [["#f97316", "#fff7ed"], ["#0ea5e9", "#f0f9ff"], ["#16a34a", "#f0fdf4"], ["#7c3aed", "#f5f3ff"]] },
  { id: "esportes", rotulo: "Esportes e fitness", paletas: [["#16a34a", "#052e16"], ["#ef4444", "#111827"], ["#2563eb", "#eff6ff"], ["#eab308", "#1c1917"]] },
  { id: "infantil", rotulo: "Infantil", paletas: [["#f472b6", "#fdf2f8"], ["#38bdf8", "#f0f9ff"], ["#facc15", "#fefce8"], ["#a78bfa", "#f5f3ff"]] },
  { id: "papelaria", rotulo: "Papelaria e presentes", paletas: [["#8b5cf6", "#f5f3ff"], ["#f43f5e", "#fff1f2"], ["#14b8a6", "#f0fdfa"], ["#f59e0b", "#fffbeb"]] },
  { id: "outro", rotulo: "Outro", paletas: [["#7c3aed", "#f5f3ff"], ["#2563eb", "#eff6ff"], ["#059669", "#ecfdf5"], ["#dc2626", "#fef2f2"]] }
];

export const paletasDoNicho = (ramo: string): [string, string][] => {
  const t = ramo.toLowerCase();
  const achou = NICHOS.find((n) => n.rotulo.toLowerCase() === t) ?? NICHOS.find((n) => t && n.rotulo.toLowerCase().split(/ e |, /).some((w) => t.includes(w.trim().slice(0, 6))));
  return (achou ?? NICHOS[NICHOS.length - 1]!).paletas;
};

/** Primeira letra de cada uma das duas primeiras palavras: o "logo" provisório de quem ainda não tem um. */
export const iniciaisDaLoja = (nome: string): string => nome.trim().split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join("") || "V";

/** Cor do texto (preto ou branco) que fica legível sobre a cor escolhida. */
export function textoSobre(hex: string): string {
  const h = /^#[0-9a-f]{6}$/i.test(hex) ? hex : "#7c3aed";
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  return (0.299 * r! + 0.587 * g! + 0.114 * b!) > 150 ? "#111111" : "#ffffff";
}

/** A pessoa já passou pela criação da loja ou já tem a loja cadastrada (inclusive pelo celular)? */
export const lojaJaConfigurada = (e: Empresa, pulou: boolean): boolean => pulou || empresaPreenchida(e) || e.modo !== "";
