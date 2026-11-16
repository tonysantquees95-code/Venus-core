// Marketing da loja no Windows: os mesmos recursos de "Publicações" do celular que dependem só de texto e de IA.
// Lógica pura (pedidos para a IA e leitura das respostas), testável sem navegador.
import type { Empresa } from "./logica";
import type { Conversa } from "../tipos";

const semAcento = (t: string) => t.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim();

const nomeDaLoja = (e: Empresa) => e.loja.trim() || "a loja";
const ramoDaLoja = (e: Empresa) => e.ramo.trim() || "varejo online";

/** Pega o primeiro objeto JSON de uma resposta de IA (que pode vir cercado de texto ou de ```). */
export function extrairJson(texto: string): any | null {
  const i = texto.indexOf("{");
  const f = texto.lastIndexOf("}");
  if (i < 0 || f < i) return null;
  try { return JSON.parse(texto.slice(i, f + 1)); } catch { return null; }
}

const str = (v: unknown, max: number) => (typeof v === "string" ? v.replace(/\s+\n/g, "\n").trim().slice(0, max) : "");
const lista = (v: unknown, max: number, tam: number) => (Array.isArray(v) ? v.map((x) => str(x, tam)).filter(Boolean).slice(0, max) : []);

const REGRAS_DE_CONTEUDO =
  "Estética elegante, sem nudez nem linguagem vulgar (regras do Instagram), sem promessas de saúde e sem inventar preços, prazos ou características que não foram informados.";

// ---------------------------------------------------------------- ideias de post
/** Mesma ideia do celular: o `id` é o título sem acento nem caixa, e é por ele que os aparelhos se entendem. */
export interface IdeiaDePost {
  id: string; titulo: string; formato: "feed" | "story" | "reels"; legenda: string; quando: string; usada: boolean;
  origem?: "celular" | "windows"; removida?: boolean; ts?: number;
}
export const chaveDeTitulo = (t: string) => semAcento(t).replace(/[^a-z0-9]+/g, " ").trim();

export function pedidoDeIdeiasDePost(e: Empresa, jaFeitas: string[], quantidade: number, hojeISO: string): string {
  return (
    `Você é a social media de ${nomeDaLoja(e)}, loja de ${ramoDaLoja(e)}${e.sobre ? ` (${e.sobre.slice(0, 300)})` : ""}. Hoje é ${hojeISO}. ` +
    `Crie ${quantidade} ideias de post para o Instagram da loja nas próximas semanas, aproveitando datas e hábitos de venda do ramo. ${REGRAS_DE_CONTEUDO}` +
    (jaFeitas.length ? ` Não repita estas: ${jaFeitas.slice(0, 20).join("; ")}.` : "") +
    ` Responda só com JSON: {"ideias":[{"titulo":"até 50 caracteres","formato":"feed" ou "story","legenda":"até 400 caracteres, com chamada para ação e 5 hashtags","data":"AAAA-MM-DD sugerida"}]}`
  );
}

export function lerIdeiasDePost(texto: string): IdeiaDePost[] {
  const a = extrairJson(texto)?.ideias;
  if (!Array.isArray(a)) return [];
  return a.flatMap((x: any, k: number) => {
    const titulo = str(x?.titulo, 70), legenda = str(x?.legenda, 600);
    if (!titulo || !legenda) return [];
    return [{ id: chaveDeTitulo(titulo) || `i${k}`, titulo, legenda, formato: x?.formato === "story" ? ("story" as const) : ("feed" as const), origem: "windows" as const, quando: /^\d{4}-\d{2}-\d{2}$/.test(str(x?.data, 10)) ? str(x.data, 10) : "", usada: false }];
  });
}

// ---------------------------------------------------------------- ideias de vídeo (Reels)
export interface IdeiaDeVideo { id: string; titulo: string; roteiro: string[]; pedidoIA: string; legenda: string; hashtags: string }

export function pedidoDeIdeiasDeVideo(e: Empresa, quantidade: number, foco: string): string {
  return (
    `Você é a social media de ${nomeDaLoja(e)}, loja online de ${ramoDaLoja(e)}. Crie ${quantidade} ideias de vídeo curto (Reels de 8 a 15 segundos)` +
    (foco.trim() ? ` sobre: ${foco.trim().slice(0, 200)}` : " sobre os produtos e o dia a dia da loja") +
    `. ${REGRAS_DE_CONTEUDO}\n` +
    `Responda só com JSON: {"ideias":[{"titulo":"até 50 caracteres","roteiro":["cena 1","cena 2","cena 3"],"pedido_ia":"descrição da cena em inglês, pronta para um gerador de vídeo por IA: câmera, luz, cores, movimento, sem texto na tela","legenda":"até 220 caracteres, com chamada para ver no site","hashtags":"5 hashtags"}]}`
  );
}

export function lerIdeiasDeVideo(texto: string): IdeiaDeVideo[] {
  const a = extrairJson(texto)?.ideias;
  if (!Array.isArray(a)) return [];
  return a.flatMap((x: any, k: number) => {
    const roteiro = lista(x?.roteiro, 6, 200), pedidoIA = str(x?.pedido_ia, 600);
    if (!roteiro.length || !pedidoIA) return [];
    return [{ id: `${Date.now().toString(36)}-${k}`, titulo: str(x?.titulo, 70) || "Ideia de vídeo", roteiro, pedidoIA, legenda: str(x?.legenda, 260), hashtags: str(x?.hashtags, 160) }];
  });
}

/** Buscas prontas para ver como outras marcas fazem vídeos parecidos (mesmos links do celular). */
export function inspiracao(tema: string): { nome: string; url: string }[] {
  const t = tema.replace(/\s+/g, " ").trim().slice(0, 60);
  const q = encodeURIComponent(t);
  return [
    { nome: "Pinterest", url: `https://br.pinterest.com/search/videos/?q=${q}` },
    { nome: "YouTube Shorts", url: `https://www.youtube.com/results?search_query=${q}+shorts` },
    { nome: "Vídeos feitos por IA", url: `https://www.youtube.com/results?search_query=${encodeURIComponent(`${t} vídeo feito com IA`)}` },
    { nome: "Na internet", url: `https://www.google.com/search?tbm=vid&q=${q}` }
  ];
}

// ---------------------------------------------------------------- stories do dia
export interface StoryDoDia { periodo: "manha" | "tarde"; titulo: string; texto: string; enquete: string }

export function pedidoDeStories(e: Empresa, hojeISO: string): string {
  return (
    `Você é a social media de ${nomeDaLoja(e)}, loja de ${ramoDaLoja(e)}. Hoje é ${hojeISO}. Crie os 2 stories do dia: um de manhã (bom dia, novidade ou dica) e um à tarde com uma enquete de duas opções. ${REGRAS_DE_CONTEUDO}\n` +
    `Responda só com JSON: {"stories":[{"periodo":"manha" ou "tarde","titulo":"até 40 caracteres","texto":"texto curto que vai escrito no story","enquete":"pergunta da enquete (só à tarde; vazio de manhã)"}]}`
  );
}

export function lerStories(texto: string): StoryDoDia[] {
  const a = extrairJson(texto)?.stories;
  if (!Array.isArray(a)) return [];
  return a.flatMap((x: any) => {
    const t = str(x?.texto, 280);
    if (!t) return [];
    return [{ periodo: x?.periodo === "tarde" ? ("tarde" as const) : ("manha" as const), titulo: str(x?.titulo, 60), texto: t, enquete: str(x?.enquete, 120) }];
  });
}

// ---------------------------------------------------------------- respostas do Instagram (Robô)
export interface ModeloDeResposta { id: string; atalho: string; pergunta: string; faq: boolean }
/** Os mesmos 12 modelos do celular; "faq" = entram nas 4 perguntas frequentes do Direct. */
export const MODELOS_DE_RESPOSTA: ModeloDeResposta[] = [
  { id: "discreto", atalho: "discreto", pergunta: "A entrega é discreta?", faq: true },
  { id: "frete", atalho: "frete", pergunta: "Qual o prazo e o valor do frete?", faq: true },
  { id: "pagamento", atalho: "pagamento", pergunta: "Quais as formas de pagamento?", faq: true },
  { id: "iniciante", atalho: "iniciante", pergunta: "O que vocês indicam para quem está começando?", faq: true },
  { id: "oi", atalho: "oi", pergunta: "Oi, boa tarde! Queria tirar uma dúvida", faq: false },
  { id: "troca", atalho: "troca", pergunta: "Posso trocar ou devolver?", faq: false },
  { id: "retirada", atalho: "retirada", pergunta: "Dá para retirar pessoalmente? Onde vocês ficam?", faq: false },
  { id: "casal", atalho: "casal", pergunta: "O que é bom para usar a dois?", faq: false },
  { id: "original", atalho: "original", pergunta: "Os produtos são originais e seguros?", faq: false },
  { id: "pedido", atalho: "pedido", pergunta: "Como faço o pedido pelo site?", faq: false },
  { id: "rastreio", atalho: "rastreio", pergunta: "Como acompanho meu pedido?", faq: false },
  { id: "horario", atalho: "horario", pergunta: "Qual o horário de atendimento?", faq: false }
];
/** O Instagram corta respostas muito longas; deixa uma margem. */
export const MAXIMO_RESPOSTA = 900;

/** [contexto] = o texto do cadastro da loja (contextoDaEmpresa); vazio se a loja ainda não foi cadastrada. */
export function pedidoDeResposta(e: Empresa, m: ModeloDeResposta, contexto: string): string {
  return (
    `${contexto || `Você é a Vênus, atendente de ${nomeDaLoja(e)}.`}\n` +
    `Escreva a resposta pronta, no tom da loja (simpática, curta, em português do Brasil), para esta pergunta de cliente no Direct do Instagram: "${m.pergunta}". ` +
    `Use só o que está nas regras acima; se faltar informação, peça para a cliente chamar no WhatsApp em vez de inventar. Até ${MAXIMO_RESPOSTA} caracteres, sem aspas, só o texto da resposta.`
  );
}

export const limparResposta = (t: string) => t.replace(/^["“]|["”]$/g, "").trim().slice(0, MAXIMO_RESPOSTA);

// ---------------------------------------------------------------- primeiros passos
export function primeirosPassos(e: Empresa, logado: boolean): { id: string; texto: string; feito: boolean }[] {
  return [
    { id: "id", texto: "Entrar com o Vetorial ID (a mesma conta do celular)", feito: logado },
    { id: "identidade", texto: "Criar a identidade da loja (logo e cores)", feito: !!(e.logo || e.cor) },
    { id: "dados", texto: "Preencher nome, loja, ramo e cidade", feito: !!(e.dono && e.loja && e.ramo && e.cidade) },
    { id: "sobre", texto: "Contar sobre a empresa em poucas linhas", feito: e.sobre.length >= 20 },
    { id: "site", texto: "Informar o site da loja", feito: !!e.site },
    { id: "regras", texto: "Cadastrar as regras de venda (no celular: Menu → Regras de venda)", feito: e.regrasConfiguradas }
  ];
}

// ---------------------------------------------------------------- histórico de conversas
export interface ResumoDeConversa { id: string; titulo: string; resumo: string; mensagens: number; atualizada: number }

export function resumirConversa(c: Conversa): ResumoDeConversa {
  const ultima = [...c.mensagens].reverse().find((m) => m.texto.trim());
  return { id: c.id, titulo: c.titulo || "Conversa", resumo: ultima ? ultima.texto.replace(/\s+/g, " ").slice(0, 140) : "Conversa em branco", mensagens: c.mensagens.length, atualizada: c.atualizada };
}

/** Busca por assunto em todas as conversas (título e mensagens), sem acento nem caixa. */
export function buscarConversas(conversas: Conversa[], termo: string): Conversa[] {
  const palavras = semAcento(termo).split(/\s+/).filter(Boolean);
  const ordenadas = [...conversas].sort((a, b) => b.atualizada - a.atualizada);
  if (!palavras.length) return ordenadas;
  return ordenadas.filter((c) => {
    const palheiro = semAcento(`${c.titulo} ${c.mensagens.map((m) => m.texto).join(" ")}`);
    return palavras.every((p) => palheiro.includes(p));
  });
}


/** Lê o documento "ideiaspost" que vem da conta (celular ou outro computador); ignora o que não faz sentido. */
export function lerDocIdeias(doc: any): IdeiaDePost[] {
  const a = doc && Array.isArray(doc.itens) ? doc.itens : [];
  return a.flatMap((x: any) => {
    const titulo = str(x?.titulo, 80);
    const id = chaveDeTitulo(titulo);
    if (!id) return [];
    if (x?.removida === true) return [{ id, titulo, formato: "feed" as const, legenda: "", quando: "", usada: false, removida: true, ts: Number(x.ts) || Date.now() }];
    const legenda = str(x?.legenda, 600);
    if (!legenda) return [];
    const formato = x?.formato === "story" ? "story" : x?.formato === "reels" ? "reels" : "feed";
    return [{ id, titulo, formato: formato as IdeiaDePost["formato"], legenda, quando: /^\d{4}-\d{2}-\d{2}$/.test(str(x?.quando, 10)) ? str(x.quando, 10) : "", usada: x?.usada === true, origem: x?.origem === "windows" ? ("windows" as const) : ("celular" as const) }];
  });
}

export interface PostProgramado { id: number; quando: number; tipo: "feed" | "story"; legenda: string; link: string }
export function lerDocPosts(doc: any): PostProgramado[] {
  const a = doc && Array.isArray(doc.itens) ? doc.itens : [];
  return a.flatMap((x: any) => (Number.isFinite(Number(x?.quando)) && Number(x.quando) > 0
    ? [{ id: Number(x.id) || 0, quando: Number(x.quando), tipo: x.tipo === "story" ? ("story" as const) : ("feed" as const), legenda: str(x.legenda, 600), link: str(x.link, 300) }] : []));
}

export interface StoriesDoCelular {
  ligado: boolean; manha: string; tarde: string; tardeInterativa: boolean; ultimo: string;
  fila: { tipo: string; titulo: string; texto: string; enquete: string }[]; usados: string[];
}
export function lerDocStories(doc: any): StoriesDoCelular | null {
  if (!doc || typeof doc !== "object" || !Array.isArray(doc.fila)) return null;
  return {
    ligado: doc.ligado !== false, manha: str(doc.manha, 8), tarde: str(doc.tarde, 8), tardeInterativa: doc.tardeInterativa === true, ultimo: str(doc.ultimo, 200),
    fila: doc.fila.flatMap((x: any) => (str(x?.texto, 300) ? [{ tipo: str(x.tipo, 20), titulo: str(x.titulo, 80), texto: str(x.texto, 300), enquete: str(x.enquete, 80) }] : [])),
    usados: Array.isArray(doc.usados) ? doc.usados.map((u: unknown) => str(u, 80)).filter(Boolean).slice(-20) : []
  };
}

/** O que vai para a conta: sem o `id` (o outro aparelho recalcula pelo título). */
export function docDeIdeias(l: IdeiaDePost[]) {
  return { itens: l.map(({ id: _id, ...resto }) => resto) };
}

/** Resumo curto do que já existe nas publicações (celular e computador), para a Vênus responder "como está o Instagram?". */
export function contextoDeMarketing(ideias: IdeiaDePost[], posts: PostProgramado[], stories: StoriesDoCelular | null, agora = Date.now(), resumoInstagram = ""): string {
  const vivas = ideias.filter((i) => !i.removida);
  const futuros = posts.filter((p) => p.quando > agora).sort((a, b) => a.quando - b.quando).slice(0, 8);
  const dia = (ms: number) => new Date(ms).toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
  const linhas = [
    resumoInstagram ? `Desempenho atual do Instagram (enviado pelo celular): ${resumoInstagram}` : "",
    vivas.length ? `Ideias de post (${vivas.length}, ${vivas.filter((i) => i.usada).length} já postadas): ${vivas.slice(0, 10).map((i) => `${i.titulo}${i.usada ? " (postada)" : ""}`).join("; ")}.` : "",
    futuros.length ? `Posts programados no celular: ${futuros.map((p) => `${dia(p.quando)} ${p.tipo} "${p.legenda.slice(0, 50)}"`).join("; ")}.` : posts.length ? "Nenhum post programado pela frente." : "",
    stories ? `Stories automáticos ${stories.ligado ? "ligados" : "desligados"}${stories.manha ? `, manhã ${stories.manha}` : ""}${stories.tarde ? `, tarde ${stories.tarde}` : ""}; ${stories.fila.length} dicas na fila${stories.ultimo ? `; último: ${stories.ultimo}` : ""}.` : ""
  ].filter(Boolean);
  return linhas.length ? `Situação das publicações no Instagram (dados sincronizados do celular; não invente nada além disto):\n${linhas.join("\n")}` : "";
}
