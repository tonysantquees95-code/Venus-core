import { paraSecundaria } from "./cor";
import type { Cores, OrbeId, PacoteIconesId, RGB, Tema, VarsIcones } from "./tipos";

/** Mesmos nomes e textos do celular (Tema.kt). */
export const ORBES: { id: OrbeId; nome: string; descricao: string }[] = [
  { id: "respirar", nome: "Respirar", descricao: "Cresce e encolhe devagar" },
  { id: "giro", nome: "Giro", descricao: "Gira de lado sem parar" },
  { id: "batida", nome: "Batida", descricao: "Pulso forte, como um coração" },
  { id: "onda", nome: "Onda", descricao: "Ondas atravessando a esfera" },
  { id: "flutuar", nome: "Flutuar", descricao: "Balança e inclina com leveza" }
];

const SEM_BRILHO = "none";
export const PACOTES_ICONES: Record<PacoteIconesId, { nome: string; descricao: string; vars: VarsIcones }> = {
  venus: {
    nome: "Vênus",
    descricao: "Identidade da Vênus",
    vars: { "--icone-traco": "1.8", "--icone-ponta": "round", "--icone-junta": "round", "--icone-preench": "0.1", "--icone-brilho": "drop-shadow(0 0 3px rgb(var(--venus-rgb) / .45))" }
  },
  linha: {
    nome: "Linha",
    descricao: "Traço fino e arredondado",
    vars: { "--icone-traco": "1.6", "--icone-ponta": "round", "--icone-junta": "round", "--icone-preench": "0", "--icone-brilho": SEM_BRILHO }
  },
  tecnico: {
    nome: "Técnico",
    descricao: "Bloco sólido e firme",
    vars: { "--icone-traco": "2.4", "--icone-ponta": "square", "--icone-junta": "miter", "--icone-preench": "0.55", "--icone-brilho": SEM_BRILHO }
  },
  neon: {
    nome: "Neon",
    descricao: "Contorno luminoso",
    vars: { "--icone-traco": "1.7", "--icone-ponta": "round", "--icone-junta": "round", "--icone-preench": "0", "--icone-brilho": "drop-shadow(0 0 2px currentColor) drop-shadow(0 0 6px currentColor)" }
  },
  duotone: {
    nome: "Duotone",
    descricao: "Miolo translúcido, vidro fosco",
    vars: { "--icone-traco": "1.8", "--icone-ponta": "round", "--icone-junta": "round", "--icone-preench": "0.24", "--icone-brilho": SEM_BRILHO }
  }
};

const rgb = (hex: number): RGB => ({ r: (hex >> 16) & 255, g: (hex >> 8) & 255, b: hex & 255 });
const par = (hex: number): Cores => ({ destaque: rgb(hex), secundaria: paraSecundaria(rgb(hex)) });

/** Mesma paleta do celular (Tema.PREDEFINIDAS). "auto" = Automática. */
export const PRESETS_CORES: { id: string; nome: string; auto?: boolean; cores: Cores }[] = [
  { id: "auto", nome: "Automática", auto: true, cores: par(0xe36bd0) },
  { id: "venus", nome: "Vênus", cores: par(0xe36bd0) },
  { id: "ciano", nome: "Ciano", cores: par(0x36d6ff) },
  { id: "ambar", nome: "Âmbar", cores: par(0xf5b93a) },
  { id: "esmeralda", nome: "Esmeralda", cores: par(0x2ecc8f) },
  { id: "rubi", nome: "Rubi", cores: par(0xe5384f) },
  { id: "violeta", nome: "Violeta", cores: par(0x8f6bff) }
];

export const TEMA_PADRAO: Tema = {
  orbe: "respirar",
  icones: "venus",
  cores: PRESETS_CORES[0]!.cores,
  auto: true
};
