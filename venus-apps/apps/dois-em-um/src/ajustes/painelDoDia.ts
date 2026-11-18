// Painel do dia (igual ao do app Android): na primeira vez que a pessoa abre a Vênus em cada dia, aparece uma mensagem curta no chat.
// Lógica pura, sem tela, para ser testada.

export const CHAVE_PAINEL = "venus.painelDoDia.ultimo";

export const DICAS = [
  "Pergunte: o que está acabando na loja?",
  "Peça: uma ideia de post para hoje.",
  "Pergunte: quanto custa um produto da loja?",
  "Peça: um resumo da loja.",
  "Pergunte: como está o dólar hoje?",
  "Peça: uma legenda para o Instagram.",
  "Pergunte: vai chover amanhã?",
];

export function chaveDoDia(agora: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${agora.getFullYear()}-${p(agora.getMonth() + 1)}-${p(agora.getDate())}`;
}

/** Mostra se está ligado, se ainda não mostrou hoje e se é um horário razoável (das 5h às 22h). */
export function deveMostrar(ligado: boolean, ultimoDia: string | null | undefined, agora: Date): boolean {
  return ligado && ultimoDia !== chaveDoDia(agora) && agora.getHours() >= 5 && agora.getHours() <= 21;
}

export function saudacao(agora: Date): string {
  const h = agora.getHours();
  return h >= 5 && h <= 11 ? "Bom dia" : h >= 12 && h <= 17 ? "Boa tarde" : "Boa noite";
}

export function diaDoAno(agora: Date): number {
  return Math.floor((Date.UTC(agora.getFullYear(), agora.getMonth(), agora.getDate()) - Date.UTC(agora.getFullYear(), 0, 0)) / 86400000);
}

export function dica(agora: Date): string {
  return DICAS[diaDoAno(agora) % DICAS.length];
}

/** [esgotados]: nomes dos produtos sem estoque (vem do catálogo do site); [apelido]: como a pessoa gosta de ser chamada. */
export function montarPainel(apelido: string | null | undefined, esgotados: string[], lojaConectada: boolean, agora: Date): string {
  const partes = [`${saudacao(agora)}${apelido ? `, ${apelido}` : ""}! Seu painel de hoje:`];
  if (lojaConectada) {
    partes.push(
      esgotados.length === 0
        ? "Na loja, nenhum produto esgotado."
        : `Na loja, ${esgotados.length} ${esgotados.length === 1 ? "produto esgotado" : "produtos esgotados"}: ${esgotados.slice(0, 3).join(", ")}${esgotados.length > 3 ? " e mais " + (esgotados.length - 3) : ""}.`
    );
  } else {
    partes.push("Conecte a sua loja para eu mostrar aqui as vendas e o estoque.");
  }
  partes.push(`Dica: ${dica(agora)}`);
  return partes.join("\n");
}
