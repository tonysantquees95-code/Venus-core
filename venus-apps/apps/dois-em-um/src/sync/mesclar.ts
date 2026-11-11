// Sincronização entre Windows, Android e iPhone: lógica pura (testável sem navegador).

export interface Versoes {
  [chave: string]: number;
}

/** Decide o que fazer com um documento que chegou do servidor. */
export function decidirRemoto(doc: { chave: string; versao: number; deEsteAparelho: boolean }, conhecidas: Versoes): "aplicar" | "ignorar" {
  if (doc.deEsteAparelho && (conhecidas[doc.chave] ?? 0) >= doc.versao) return "ignorar";
  return doc.versao > (conhecidas[doc.chave] ?? 0) ? "aplicar" : "ignorar";
}

/** O tema sincronizado é só dado: valida antes de aplicar. */
export function temaValido(v: any): v is { orbe: string; icones: string; cores: { destaque: any; secundaria: any } } {
  const rgb = (c: any) => c && [c.r, c.g, c.b].every((n: any) => Number.isInteger(n) && n >= 0 && n <= 255);
  return !!v && typeof v.orbe === "string" && typeof v.icones === "string" && rgb(v.cores?.destaque) && rgb(v.cores?.secundaria);
}
