import type { Cores, OrbeId, PacoteIconesId, Tema, VarsIcones } from "./tipos";

export const ORBES: { id: OrbeId; nome: string; descricao: string }[] = [
  { id: "aurora", nome: "Aurora", descricao: "Névoa de luz girando" },
  { id: "aneis", nome: "Anéis", descricao: "Órbitas em 3D" },
  { id: "prisma", nome: "Prisma", descricao: "Cristal iridescente" }
];

export const PACOTES_ICONES: Record<PacoteIconesId, { nome: string; descricao: string; vars: VarsIcones }> = {
  linha: {
    nome: "Linha",
    descricao: "Traço fino e arredondado",
    vars: { "--icone-traco": "1.6", "--icone-ponta": "round", "--icone-junta": "round", "--icone-preench": "0" }
  },
  duotone: {
    nome: "Duotone",
    descricao: "Traço com miolo translúcido",
    vars: { "--icone-traco": "1.8", "--icone-ponta": "round", "--icone-junta": "round", "--icone-preench": "0.24" }
  },
  tecnico: {
    nome: "Técnico",
    descricao: "Traço grosso e quinas retas",
    vars: { "--icone-traco": "2.4", "--icone-ponta": "square", "--icone-junta": "miter", "--icone-preench": "0" }
  }
};

export const PRESETS_CORES: { id: string; nome: string; cores: Cores }[] = [
  { id: "venus", nome: "Vênus", cores: { destaque: { r: 236, g: 111, b: 214 }, secundaria: { r: 185, g: 140, b: 255 } } },
  { id: "ciano", nome: "Ciano", cores: { destaque: { r: 34, g: 211, b: 238 }, secundaria: { r: 129, g: 140, b: 248 } } },
  { id: "ambar", nome: "Âmbar", cores: { destaque: { r: 251, g: 191, b: 36 }, secundaria: { r: 251, g: 113, b: 133 } } },
  { id: "esmeralda", nome: "Esmeralda", cores: { destaque: { r: 52, g: 211, b: 153 }, secundaria: { r: 56, g: 189, b: 248 } } },
  { id: "rubi", nome: "Rubi", cores: { destaque: { r: 244, g: 63, b: 94 }, secundaria: { r: 251, g: 146, b: 60 } } }
];

export const TEMA_PADRAO: Tema = {
  orbe: "aurora",
  icones: "linha",
  cores: PRESETS_CORES[0]!.cores
};
