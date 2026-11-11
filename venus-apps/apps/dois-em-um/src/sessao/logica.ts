// Lógica pura das conversas e do isolamento de sessão (sem React: testável em Node).
//
// REGRA DE ISOLAMENTO: o que fica salvo é só o HISTÓRICO. A conversa ativa nunca é gravada:
// todo estado inicial nasce com uma conversa nova e vazia, e é ela que fica ativa.
import type { Conversa, Mensagem } from "../tipos";

export const CHAVE_CONVERSAS = "dois-em-um:conversas:v1";
export const CHAVE_LEGADO = "dois-em-um:chat"; // formato antigo (uma conversa só)
export const MAX_CONVERSAS = 100;
export const MAX_MENSAGENS = 200;
/** Fora do app por pelo menos isto = reaberto = conversa nova (ignora piscadas: permissão, compartilhar). */
export const REABRIR_APOS_MS = 5000;
export const TITULO_PADRAO = "Nova conversa";

export interface Estado {
  lista: Conversa[];
  ativaId: string;
}
export type Armazenamento = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function novaVazia(agora: number = Date.now(), id: string = crypto.randomUUID()): Conversa {
  return { id, titulo: TITULO_PADRAO, criada: agora, atualizada: agora, mensagens: [] };
}

export function mensagemValida(m: unknown): m is Mensagem {
  if (typeof m !== "object" || m === null) return false;
  const x = m as Record<string, unknown>;
  return typeof x.id === "string" && (x.autor === "eu" || x.autor === "venus") && typeof x.texto === "string" && typeof x.quando === "number";
}

export const tituloDe = (texto: string) => {
  const limpo = texto.replace(/\s+/g, " ").trim();
  return limpo.length > 42 ? `${limpo.slice(0, 40).trimEnd()}…` : limpo || TITULO_PADRAO;
};

function conversaValida(c: unknown): c is Conversa {
  if (typeof c !== "object" || c === null) return false;
  const x = c as Record<string, unknown>;
  return (
    typeof x.id === "string" && typeof x.titulo === "string" && typeof x.criada === "number" &&
    typeof x.atualizada === "number" && Array.isArray(x.mensagens) && x.mensagens.every(mensagemValida)
  );
}

/** Lê o histórico salvo (só conversas com mensagem) e migra o formato antigo, se houver. */
export function lerHistorico(arm: Armazenamento | null, agora: number = Date.now()): Conversa[] {
  if (!arm) return [];
  let lista: Conversa[] = [];
  try {
    const bruto = arm.getItem(CHAVE_CONVERSAS);
    if (bruto) {
      const dados: unknown = JSON.parse(bruto);
      if (Array.isArray(dados)) lista = dados.filter(conversaValida).filter((c) => c.mensagens.length > 0);
    }
  } catch {
    lista = [];
  }
  try {
    const antigo = arm.getItem(CHAVE_LEGADO);
    if (antigo) {
      const msgs = (JSON.parse(antigo) as unknown[]).filter(mensagemValida).filter((m) => m.id !== "boas-vindas");
      const primeira = msgs.find((m) => m.autor === "eu");
      if (msgs.length > 0 && primeira) {
        const idLegado = `legado-${msgs[0]!.quando}`;
        if (!lista.some((c) => c.id === idLegado)) lista.push({ id: idLegado, titulo: tituloDe(primeira.texto), criada: msgs[0]!.quando || agora, atualizada: msgs[msgs.length - 1]!.quando || agora, mensagens: msgs });
      }
    }
  } catch {
    /* formato antigo ilegível: ignora */
  }
  return lista;
}

/** Estado de abertura do app: histórico + UMA conversa nova e vazia, que é a ativa. Nunca reabre a anterior. */
export function estadoInicial(arm: Armazenamento | null, agora: number = Date.now()): Estado {
  const nova = novaVazia(agora);
  return { lista: [...lerHistorico(arm, agora), nova], ativaId: nova.id };
}

const comMensagens = (l: Conversa[]) => l.filter((c) => c.mensagens.length > 0);

export const conversaAtiva = (e: Estado): Conversa => e.lista.find((c) => c.id === e.ativaId) ?? e.lista[e.lista.length - 1] ?? novaVazia();

/** Nova conversa limpa. Se a ativa já está vazia, ela mesma serve (não acumula rascunhos). */
export function iniciarNova(e: Estado, agora: number = Date.now()): Estado {
  if (conversaAtiva(e).mensagens.length === 0 && e.lista.some((c) => c.id === e.ativaId)) return e;
  const nova = novaVazia(agora);
  return { lista: [...comMensagens(e.lista), nova], ativaId: nova.id };
}

export function abrirConversa(e: Estado, id: string): Estado {
  if (!e.lista.some((c) => c.id === id)) return e;
  return { lista: e.lista.filter((c) => c.mensagens.length > 0 || c.id === id), ativaId: id };
}

export function excluirConversa(e: Estado, id: string, agora: number = Date.now()): Estado {
  const lista = e.lista.filter((c) => c.id !== id);
  if (id !== e.ativaId) return { ...e, lista };
  const nova = novaVazia(agora);
  return { lista: [...comMensagens(lista), nova], ativaId: nova.id };
}

export function apagarTudo(agora: number = Date.now()): Estado {
  const nova = novaVazia(agora);
  return { lista: [nova], ativaId: nova.id };
}

/** Acrescenta a mensagem à conversa `id` (e não à ativa: respostas atrasadas voltam para a conversa certa). */
export function anexarMensagem(e: Estado, id: string, m: Mensagem): Estado {
  return {
    ...e,
    lista: e.lista.map((c) => {
      if (c.id !== id) return c;
      const primeira = c.mensagens.length === 0 && m.autor === "eu";
      return { ...c, titulo: primeira ? tituloDe(m.texto) : c.titulo, atualizada: m.quando, mensagens: [...c.mensagens, m] };
    })
  };
}

/** O que vai para o armazenamento: só conversas com mensagem, as mais recentes primeiro. A ativa vazia nunca é gravada. */
export function paraSalvar(e: Estado): Conversa[] {
  return comMensagens(e.lista)
    .sort((a, b) => b.atualizada - a.atualizada)
    .slice(0, MAX_CONVERSAS)
    .map((c) => ({ ...c, mensagens: c.mensagens.slice(-MAX_MENSAGENS) }));
}

export function salvar(arm: Armazenamento | null, e: Estado): void {
  if (!arm) return;
  try {
    arm.setItem(CHAVE_CONVERSAS, JSON.stringify(paraSalvar(e)));
    arm.removeItem(CHAVE_LEGADO); // o formato antigo já foi migrado para o histórico
  } catch {
    /* sem espaço ou bloqueado: o histórico vale só nesta sessão */
  }
}

export function agrupar(conversas: Conversa[], agora: number = Date.now()): { rotulo: string; itens: Conversa[] }[] {
  const hoje = new Date(agora);
  hoje.setHours(0, 0, 0, 0);
  const d0 = hoje.getTime();
  const DIA = 86_400_000;
  const grupos: { rotulo: string; desde: number; itens: Conversa[] }[] = [
    { rotulo: "Hoje", desde: d0, itens: [] },
    { rotulo: "Ontem", desde: d0 - DIA, itens: [] },
    { rotulo: "Últimos 7 dias", desde: d0 - 6 * DIA, itens: [] },
    { rotulo: "Mais antigas", desde: -Infinity, itens: [] }
  ];
  for (const c of [...conversas].sort((a, b) => b.atualizada - a.atualizada)) {
    grupos.find((g) => c.atualizada >= g.desde)!.itens.push(c);
  }
  return grupos.filter((g) => g.itens.length > 0).map(({ rotulo, itens }) => ({ rotulo, itens }));
}
