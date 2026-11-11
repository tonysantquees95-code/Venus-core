export interface RGB {
  r: number;
  g: number;
  b: number;
}

export type OrbeId = "aurora" | "aneis" | "prisma";
export type PacoteIconesId = "linha" | "duotone" | "tecnico";
export type AlvoCor = "destaque" | "secundaria";

export interface Cores {
  destaque: RGB;
  secundaria: RGB;
}

export interface Tema {
  orbe: OrbeId;
  icones: PacoteIconesId;
  cores: Cores;
}

/** Variáveis CSS que cada pacote de ícones controla. */
export interface VarsIcones {
  "--icone-traco": string;
  "--icone-ponta": "round" | "square" | "butt";
  "--icone-junta": "round" | "miter" | "bevel";
  "--icone-preench": string;
}
