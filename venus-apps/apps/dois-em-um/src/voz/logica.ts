// Voz: lógica pura (testável sem navegador).

const PREFERIDOS = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4"];

/** Primeiro formato de gravação que o navegador/Electron sabe fazer. */
export function escolherMime(suporta: (mime: string) => boolean): string | undefined {
  return PREFERIDOS.find((m) => suporta(m));
}

/** Tira o que fica estranho quando lido em voz alta (markdown, links, emojis) e limita o tamanho. */
export function limparParaFalar(texto: string, max = 700): string {
  let t = texto
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/https?:\/\/\S+/g, " link ")
    .replace(/[`*_#>~|]/g, "")
    .replace(/^\s*[-•]\s+/gm, "")
    .replace(/\p{Extended_Pictographic}/gu, "")
    .replace(/\s+/g, " ")
    .trim();
  if (t.length > max) {
    const corte = t.slice(0, max);
    const fim = Math.max(corte.lastIndexOf(". "), corte.lastIndexOf("? "), corte.lastIndexOf("! "));
    t = fim > max / 2 ? corte.slice(0, fim + 1) : corte.trimEnd() + "…";
  }
  return t;
}

/** A Vênus só responde em voz alta quando a pessoa falou (e deixou a voz ligada); quem digita quer ler. */
export const deveFalar = (porVoz: boolean, vozLigada: boolean): boolean => porVoz && vozLigada;

/** Nome do arquivo ao guardar uma resposta: "2026-11-15 1430 - como fazer um bolo.txt". */
export function nomeDoArquivo(texto: string, quando: Date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  const data = `${quando.getFullYear()}-${p(quando.getMonth() + 1)}-${p(quando.getDate())} ${p(quando.getHours())}${p(quando.getMinutes())}`;
  const inicio = texto.replace(/[^\p{L}\p{N} ]/gu, " ").trim().split(/\s+/).filter(Boolean).slice(0, 6).join(" ").toLowerCase().slice(0, 40).trim();
  return inicio ? `${data} - ${inicio}.txt` : `${data}.txt`;
}

/** Só as respostas de verdade ganham os botões (ouvir, compartilhar, guardar). */
export const merecemAcoes = (texto: string): boolean => texto.trim().length >= 20;
