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
