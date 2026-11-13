export interface RGB {
  r: number;
  g: number;
  b: number;
}

/** Movimentos do orbe: os mesmos cinco do celular. */
export type OrbeId = "respirar" | "giro" | "batida" | "onda" | "flutuar";
/** Pacotes de ícones: os mesmos cinco do celular. */
export type PacoteIconesId = "venus" | "linha" | "tecnico" | "neon" | "duotone";
export type AlvoCor = "destaque" | "secundaria";
export type EstadoOrbe = "ocioso" | "ouvindo" | "pensando" | "falando";

export interface Cores {
  destaque: RGB;
  secundaria: RGB;
}

export interface Tema {
  orbe: OrbeId;
  icones: PacoteIconesId;
  cores: Cores;
  /** Paleta "Automática": o orbe muda de cor devagar, nos tons da Vênus (como no celular). */
  auto: boolean;
}

/** Variáveis CSS que cada pacote de ícones controla. */
export interface VarsIcones {
  "--icone-traco": string;
  "--icone-ponta": "round" | "square" | "butt";
  "--icone-junta": "round" | "miter" | "bevel";
  "--icone-preench": string;
  "--icone-brilho": string;
}
