// Lógica pura dos ajustes (sem React): catálogo para a busca, modos de uso, plataforma e mesclagem da sincronização.

export type Categoria = "aparencia" | "comportamento" | "conexoes" | "seguranca" | "dispositivos";
export const CATEGORIAS: { id: Categoria; nome: string; icone: string }[] = [
  { id: "aparencia", nome: "Aparência & Tema", icone: "🎨" },
  { id: "comportamento", nome: "Comportamento & Voz", icone: "🎙️" },
  { id: "conexoes", nome: "Conexões & E-commerce", icone: "🛒" },
  { id: "seguranca", nome: "Segurança & Vetorial ID", icone: "🔐" },
  { id: "dispositivos", nome: "Meus dispositivos", icone: "📱" }
];

export type ModoDeUso = "conversacional" | "orbe" | "bolha" | "silencioso";
export const MODOS: { id: ModoDeUso; nome: string; descricao: string }[] = [
  { id: "conversacional", nome: "Conversa direta", descricao: "Chat tradicional: você escreve, a Vênus responde por texto." },
  { id: "orbe", nome: "Orbe por voz", descricao: "Chame pelo nome ou toque no orbe. Escuta contínua ou só quando chamada." },
  { id: "bolha", nome: "Bolha flutuante", descricao: "Uma bolinha sobre os outros apps, sem ocupar a tela." },
  { id: "silencioso", nome: "Silencioso", descricao: "Só automações, webhooks e alertas da loja, sem abrir o chat." }
];

export interface Preferencias {
  modo: ModoDeUso;
  escutaContinua: boolean;
  acionarPeloNome: boolean;
  falarRespostas: boolean;
  notificacoes: boolean;
  alertasDaLoja: boolean;
  iniciarComOSistema: boolean;
}
export const PREFERENCIAS_PADRAO: Preferencias = {
  modo: "conversacional",
  escutaContinua: false,
  acionarPeloNome: true,
  falarRespostas: true,
  notificacoes: true,
  alertasDaLoja: true,
  iniciarComOSistema: false
};

/** Lê preferências de qualquer origem (armazenamento ou servidor), descartando o que não faz sentido. */
export function limparPreferencias(bruto: unknown): Preferencias {
  const b = (bruto && typeof bruto === "object" ? bruto : {}) as Record<string, unknown>;
  const p = { ...PREFERENCIAS_PADRAO };
  if (MODOS.some((m) => m.id === b.modo)) p.modo = b.modo as ModoDeUso;
  for (const k of ["escutaContinua", "acionarPeloNome", "falarRespostas", "notificacoes", "alertasDaLoja", "iniciarComOSistema"] as const) {
    if (typeof b[k] === "boolean") p[k] = b[k] as boolean;
  }
  return p;
}

export interface ItemDeAjuste {
  id: string;
  categoria: Categoria;
  titulo: string;
  descricao: string;
  palavras: string[];
}
/** Tudo o que dá para achar na busca. O id aponta para o cartão na tela. */
export const CATALOGO: ItemDeAjuste[] = [
  { id: "tema", categoria: "aparencia", titulo: "Tema, cores e orbe", descricao: "Cor de destaque, modelo do orbe e pacote de ícones.", palavras: ["cor", "cores", "orbe", "icone", "aparencia", "visual", "estilo"] },
  { id: "modo", categoria: "comportamento", titulo: "Como usar a Vênus", descricao: "Conversa direta, orbe por voz, bolha flutuante ou silencioso.", palavras: ["modo", "bolha", "widget", "silencioso", "segundo plano", "inicializacao", "iniciar"] },
  { id: "escutaContinua", categoria: "comportamento", titulo: "Escuta contínua", descricao: "A Vênus fica ouvindo o seu chamado (mostra o ícone de microfone do Android).", palavras: ["escuta", "microfone", "ouvir", "continua", "voz", "sempre"] },
  { id: "acionarPeloNome", categoria: "comportamento", titulo: "Acionar só dizendo o nome", descricao: "Dizer \"Vênus\" já abre o orbe, sem precisar de \"ativar\".", palavras: ["nome", "chamar", "ativar", "orbe", "voz"] },
  { id: "falarRespostas", categoria: "comportamento", titulo: "Falar as respostas", descricao: "Lê em voz alta o que a Vênus responde.", palavras: ["falar", "voz", "audio", "som", "leitura"] },
  { id: "notificacoes", categoria: "comportamento", titulo: "Notificações", descricao: "Avisos do sistema para pedidos, estoque e lembretes.", palavras: ["notificacao", "aviso", "alerta", "push"] },
  { id: "alertasDaLoja", categoria: "comportamento", titulo: "Alertas da loja", descricao: "Pedido novo, estoque baixo e pagamentos.", palavras: ["loja", "pedido", "estoque", "pagamento", "alerta"] },
  { id: "iniciarComOSistema", categoria: "comportamento", titulo: "Iniciar com o sistema", descricao: "No Windows, abre junto com o computador, na bandeja.", palavras: ["windows", "bandeja", "iniciar", "boot", "tray"] },
  { id: "servidor", categoria: "conexoes", titulo: "Conexão com o servidor", descricao: "Estado do servidor da Vênus (Workers).", palavras: ["servidor", "worker", "conexao", "status", "online", "internet"] },
  { id: "canais", categoria: "conexoes", titulo: "Canais de venda", descricao: "Nuvemshop, Mercado Livre e site próprio (até 3) são conectados na Central da loja do app.", palavras: ["nuvemshop", "mercado livre", "site", "canal", "loja", "ecommerce", "estoque"] },
  { id: "conta", categoria: "seguranca", titulo: "Conta Vetorial ID", descricao: "Entrar com e-mail e senha em todos os seus aparelhos.", palavras: ["conta", "login", "entrar", "sair", "senha", "email", "vetorial id"] },
  { id: "licenca", categoria: "seguranca", titulo: "Plano Fundadores", descricao: "Teste grátis, validade e renovação da licença.", palavras: ["plano", "licenca", "assinatura", "pagamento", "renovar", "fundadores", "teste"] },
  { id: "dispositivos", categoria: "dispositivos", titulo: "Meus dispositivos", descricao: "Até 3 aparelhos vinculados, 2 em uso ao mesmo tempo. Encerre sessões à distância.", palavras: ["dispositivo", "aparelho", "sessao", "encerrar", "celular", "windows", "iphone"] }
];

export const semAcento = (t: string) => t.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim();

/** Busca rápida: todas as palavras digitadas precisam aparecer no título, descrição ou palavras-chave. */
export function buscar(termo: string, itens: ItemDeAjuste[] = CATALOGO): ItemDeAjuste[] {
  const palavras = semAcento(termo).split(/\s+/).filter(Boolean);
  if (!palavras.length) return itens;
  return itens.filter((i) => {
    const palheiro = semAcento(`${i.titulo} ${i.descricao} ${i.palavras.join(" ")} ${CATEGORIAS.find((c) => c.id === i.categoria)?.nome ?? ""}`);
    return palavras.every((p) => palheiro.includes(p));
  });
}

export type Plataforma = "android" | "ios" | "windows" | "web";
export function detectarPlataforma(ua: string, opcoes: { desktop?: boolean; nativoAndroid?: boolean } = {}): Plataforma {
  if (opcoes.desktop) return "windows";
  if (opcoes.nativoAndroid || /Android/i.test(ua)) return "android";
  if (/iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && /Mobile/i.test(ua))) return "ios";
  if (/Windows/i.test(ua)) return "windows";
  return "web";
}
export const NOMES_DE_PLATAFORMA: Record<Plataforma, string> = { android: "Android", ios: "iPhone", windows: "Windows", web: "Navegador" };

/** Em quanto tempo o aparelho "sumiu" (texto curto para a lista de dispositivos). */
export function haQuantoTempo(quando: number, agora = Date.now()): string {
  const s = Math.max(0, Math.round((agora - quando) / 1000));
  if (s < 90) return "agora";
  if (s < 3600) return `há ${Math.round(s / 60)} min`;
  if (s < 86_400) return `há ${Math.round(s / 3600)} h`;
  return `há ${Math.round(s / 86_400)} d`;
}

/** Preferências que não devem seguir a conta (dependem do aparelho). */
export const SO_NESTE_APARELHO: (keyof Preferencias)[] = ["iniciarComOSistema", "escutaContinua"];

export function prefsParaEnviar(p: Preferencias): Partial<Preferencias> {
  const copia: Partial<Preferencias> = { ...p };
  for (const k of SO_NESTE_APARELHO) delete copia[k];
  return copia;
}

export function prefsRecebidas(local: Preferencias, remoto: unknown): Preferencias {
  const r = limparPreferencias({ ...local, ...(remoto as object) });
  for (const k of SO_NESTE_APARELHO) (r as any)[k] = local[k];
  return r;
}
