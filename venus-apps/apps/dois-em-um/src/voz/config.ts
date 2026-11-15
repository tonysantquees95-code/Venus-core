// Configuração da voz da Vênus: lógica pura (testável sem navegador). Espelha o que o app Android já oferece.

export interface VozNeuralOpcao { id: string; nome: string; descricao: string }
export const VOZES_NEURAIS: VozNeuralOpcao[] = [
  { id: "pt-BR-FranciscaNeural", nome: "Francisca", descricao: "Firme e madura (recomendada para o estilo assistente)" },
  { id: "pt-BR-ThalitaMultilingualNeural", nome: "Thalita", descricao: "Natural e expressiva" },
  { id: "pt-BR-ElzaNeural", nome: "Elza", descricao: "Serena" },
  { id: "pt-BR-BrendaNeural", nome: "Brenda", descricao: "Clara e jovem" },
  { id: "pt-BR-GiovannaNeural", nome: "Giovanna", descricao: "Suave" },
  { id: "pt-BR-LeilaNeural", nome: "Leila", descricao: "Calorosa" },
  { id: "pt-BR-LeticiaNeural", nome: "Leticia", descricao: "Leve" },
  { id: "pt-BR-ManuelaNeural", nome: "Manuela", descricao: "Delicada" },
  { id: "pt-BR-YaraNeural", nome: "Yara", descricao: "Simpática" },
];

export interface EstiloDeFala { id: string; nome: string; velocidade: string; tom: string }
export const ESTILOS: EstiloDeFala[] = [
  { id: "assistente", nome: "Assistente de laboratório (calma, grave e confiante)", velocidade: "-6%", tom: "-6%" },
  { id: "natural", nome: "Natural", velocidade: "0%", tom: "0%" },
  { id: "agil", nome: "Ágil (mais rápida)", velocidade: "+8%", tom: "-2%" },
];

export const LIMITE_GRATIS = 500_000;
export const LIMITE_SEGURO = 470_000;

export interface ConfigVoz {
  /** Nome da voz do sistema (vale só neste computador: cada aparelho tem as suas). */
  vozSistema: string;
  /** 0,5 a 1,5 (igual ao Android). */
  velocidade: number;
  tom: number;
  neural: { ligada: boolean; chave: string; regiao: string; voz: string; estilo: string };
}

export const CONFIG_VOZ_PADRAO: ConfigVoz = {
  vozSistema: "",
  velocidade: 1,
  tom: 1,
  neural: { ligada: true, chave: "", regiao: "brazilsouth", voz: VOZES_NEURAIS[0]!.id, estilo: ESTILOS[0]!.id },
};

const entre = (n: unknown, min: number, max: number, padrao: number) => (typeof n === "number" && Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : padrao);
const texto = (v: unknown, padrao: string, max = 200) => (typeof v === "string" ? v.trim().slice(0, max) : padrao);

/** Lê a configuração de qualquer origem (armazenamento ou conta), descartando o que não faz sentido. */
export function limparConfigVoz(bruto: unknown): ConfigVoz {
  const b = (bruto && typeof bruto === "object" ? bruto : {}) as Record<string, any>;
  const n = (b.neural && typeof b.neural === "object" ? b.neural : {}) as Record<string, unknown>;
  const p = CONFIG_VOZ_PADRAO;
  return {
    vozSistema: texto(b.vozSistema, p.vozSistema),
    velocidade: entre(b.velocidade, 0.5, 1.5, p.velocidade),
    tom: entre(b.tom, 0.5, 1.5, p.tom),
    neural: {
      ligada: typeof n.ligada === "boolean" ? n.ligada : p.neural.ligada,
      chave: texto(n.chave, "", 200),
      regiao: texto(n.regiao, p.neural.regiao, 40).toLowerCase().replace(/\s+/g, "") || p.neural.regiao,
      voz: VOZES_NEURAIS.some((v) => v.id === n.voz) ? (n.voz as string) : p.neural.voz,
      estilo: ESTILOS.some((e) => e.id === n.estilo) ? (n.estilo as string) : p.neural.estilo,
    },
  };
}

/** O que segue a conta (a voz do sistema não: cada aparelho tem as suas). */
export function configParaEnviar(c: ConfigVoz): Omit<ConfigVoz, "vozSistema"> {
  const { vozSistema: _ignorada, ...resto } = c;
  return resto;
}

export function configRecebida(local: ConfigVoz, remoto: unknown): ConfigVoz {
  const r = limparConfigVoz({ ...local, ...(remoto as object) });
  // uma conta sem chave não apaga a chave que já está aqui
  return { ...r, vozSistema: local.vozSistema, neural: { ...r.neural, chave: r.neural.chave || local.neural.chave } };
}

export const neuralAtiva = (c: ConfigVoz, usados: number): boolean => c.neural.ligada && c.neural.chave !== "" && usados < LIMITE_SEGURO;

/** SSML igual ao do Android; velocidade e tom do controle somam ao "jeito de falar" escolhido. */
export function ssml(texto: string, voz: string, estilo: EstiloDeFala, velocidade = 1, tom = 1): string {
  const seguro = texto.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");
  const soma = (base: string, fator: number) => {
    const n = parseInt(base, 10) + Math.round((fator - 1) * 100);
    return `${n >= 0 ? "+" : ""}${Math.max(-50, Math.min(50, n))}%`;
  };
  return `<speak version='1.0' xml:lang='pt-BR' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='https://www.w3.org/2001/mstts'>` +
    `<voice name='${voz}'><prosody rate='${soma(estilo.velocidade, velocidade)}' pitch='${soma(estilo.tom, tom)}'>${seguro}</prosody></voice></speak>`;
}

export const mesAtual = (d: Date = new Date()): string => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;

/** Resumo do estado da voz neural, para a tela. */
export function resumoNeural(c: ConfigVoz, usados: number): string {
  if (!c.neural.chave) return "Desligada. Com uma chave grátis da Microsoft (Azure Speech), a voz fica bem mais natural.";
  const voz = VOZES_NEURAIS.find((v) => v.id === c.neural.voz)?.nome ?? "";
  const estilo = (ESTILOS.find((e) => e.id === c.neural.estilo)?.nome ?? "").split(" (")[0]!.toLowerCase();
  const pct = Math.floor((usados * 100) / LIMITE_GRATIS);
  const base = c.neural.ligada ? `Ligada: ${voz}, ${estilo}.` : "Desligada.";
  return `${base} Usou ${pct}% do limite grátis deste mês neste computador${usados >= LIMITE_SEGURO ? " (voltou para a voz do Windows até o mês virar)" : ""}.`;
}
