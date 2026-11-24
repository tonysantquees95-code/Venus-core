// A pesquisa "vai assinar?": aparece ao abrir o app para quem está no teste grátis (a partir do 2º dia) ou já ficou sem acesso.
// Respondeu: some por 7 dias. Fechou sem responder: volta no dia seguinte. Lógica pura (sem React) para ser testada.
export type Decisao = "vai" | "talvez" | "nao";
export type Fase = "teste" | "pago" | "vencido" | "";

const CHAVE_RESPONDIDA = "venus.pesquisa.assinatura.respondida";
const CHAVE_ULTIMO_DIA = "venus.pesquisa.assinatura.dia";
export const DIAS_DE_TESTE = 7;
export const PRIMEIRO_DIA = 2;
export const ESPERA_DEPOIS_DE_RESPONDER_MS = 7 * 86_400_000;

export interface LicencaMinima { acesso?: boolean; emTeste?: boolean; diasDeTesteRestantes?: number }

export function faseDaLicenca(l: LicencaMinima | null | undefined): Fase {
  if (!l) return "";
  if (!l.acesso) return "vencido";
  return l.emTeste ? "teste" : "pago";
}

/** Dias de teste já usados (0 a 7); -1 quando não está em teste. */
export function diasUsados(l: LicencaMinima | null | undefined): number {
  if (!l || !l.acesso || !l.emTeste) return -1;
  return Math.min(DIAS_DE_TESTE, Math.max(0, DIAS_DE_TESTE - (l.diasDeTesteRestantes ?? DIAS_DE_TESTE)));
}

export const chaveDoDia = (d: Date): string => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export function deveMostrar(fase: Fase, dias: number, ultimoDia: string | null, respondidaEm: number, agora: Date): boolean {
  const elegivel = (fase === "teste" && dias >= PRIMEIRO_DIA) || fase === "vencido";
  if (!elegivel) return false;
  if (ultimoDia === chaveDoDia(agora)) return false;
  if (respondidaEm > 0 && agora.getTime() - respondidaEm < ESPERA_DEPOIS_DE_RESPONDER_MS) return false;
  const h = agora.getHours();
  return h >= 8 && h <= 21;
}

export const perguntaDoMotivo = (d: Decisao): string => (d === "vai" ? "O que a Vênus trouxe para você querer assinar?" : d === "nao" ? "Por que você não vai assinar?" : "O que falta para você decidir?");

export const DECISOES: { id: Decisao; nome: string }[] = [{ id: "vai", nome: "Vou assinar" }, { id: "talvez", nome: "Ainda não sei" }, { id: "nao", nome: "Não vou assinar" }];

export function motivos(d: Decisao): { id: string; nome: string }[] {
  if (d === "nao") return [{ id: "preco", nome: "O preço" }, { id: "falta", nome: "Falta alguma função" }, { id: "nao_uso", nome: "Não uso o bastante" }, { id: "outro", nome: "Outro motivo" }];
  if (d === "talvez") return [{ id: "testar_mais", nome: "Preciso testar mais" }, { id: "preco", nome: "O preço" }, { id: "falta", nome: "Falta alguma função" }, { id: "outro", nome: "Outro motivo" }];
  return [];
}

export function corpo(d: Decisao, motivo: string, texto: string, fase: Fase, dias: number, plataforma: string, versao: string) {
  const respostas: Record<string, unknown> = { decisao: d, motivo, plano: fase };
  if (dias >= 0) respostas.dias_de_uso = dias;
  return { app: "venus", tipo: "assinatura", plataforma, versao, texto: texto.trim().slice(0, 600), respostas };
}

// guardado no aparelho (se o navegador não deixar, simplesmente pergunta de novo depois)
export function lerEstado(): { ultimoDia: string | null; respondidaEm: number } {
  try { return { ultimoDia: localStorage.getItem(CHAVE_ULTIMO_DIA), respondidaEm: Number(localStorage.getItem(CHAVE_RESPONDIDA)) || 0 }; } catch { return { ultimoDia: null, respondidaEm: 0 }; }
}
export function marcarMostrada(agora = new Date()): void { try { localStorage.setItem(CHAVE_ULTIMO_DIA, chaveDoDia(agora)); } catch { /* ok */ } }
export function marcarRespondida(agora = Date.now()): void { try { localStorage.setItem(CHAVE_RESPONDIDA, String(agora)); } catch { /* ok */ } }
