// Lógica pura do avatar e da conta (sem React): iniciais, cor, texto da assinatura e comandos de conversa.
import type { Licenca } from "./api";

export function iniciais(nome: string, email = ""): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length >= 2) return (partes[0]![0]! + partes[partes.length - 1]![0]!).toUpperCase();
  if (partes.length === 1) return partes[0]!.slice(0, 2).toUpperCase();
  return (email.trim()[0] ?? "?").toUpperCase();
}

/** Cor estável por pessoa (mesma cor em todos os aparelhos). */
export function corDoAvatar(semente: string): string {
  let h = 0;
  for (const c of semente) h = (h * 31 + c.charCodeAt(0)) % 360;
  return `hsl(${h} 62% 42%)`;
}

export function textoDaAssinatura(l: Pick<Licenca, "acesso" | "emTeste" | "diasDeTesteRestantes" | "diasPagosRestantes"> | null | undefined): string {
  if (!l) return "Carregando assinatura…";
  const dias = (n = 0) => `${n} dia${n === 1 ? "" : "s"} restante${n === 1 ? "" : "s"}`;
  if (!l.acesso) return "Acesso vencido · renove o Plano Fundadores";
  return l.emTeste ? `Plano Fundadores · teste grátis, ${dias(l.diasDeTesteRestantes)}` : `Plano Fundadores · ${dias(l.diasPagosRestantes)}`;
}

export type ComandoConta = "status" | "conectar" | "sair" | "dispositivos";
const sem = (t: string) => t.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();

/** Entende "Vênus, qual é o status da minha conta?", "sair da minha conta aqui", "conectar minha conta no novo dispositivo"… */
export function interpretarComandoConta(texto: string): ComandoConta | null {
  const t = sem(texto).replace(/^(venus|vênus) /, "");
  if (/\b(sair|deslogar|desconectar|encerrar|fazer logout|logout)\b.*\b(conta|login|sessao)\b/.test(t) || /^(sair|logout|deslogar)$/.test(t)) return "sair";
  if (/\b(conectar|entrar|logar|vincular|fazer login|login)\b.*\b(conta|vetorial id)\b/.test(t) || /\bconta\b.*\b(novo dispositivo|novo aparelho|outro aparelho|outro celular)\b/.test(t)) return "conectar";
  if (/\b(quais|ver|mostrar|listar|meus|onde)\b.*\b(dispositivos|aparelhos|conectad[oa]s?)\b/.test(t) && /\b(dispositivos|aparelhos|conta)\b/.test(t)) return "dispositivos";
  if (/\b(status|situacao|como esta)\b.*\b(conta|assinatura|plano|licenca)\b/.test(t)) return "status";
  if (/\bquantos dias\b.*\b(teste|trial|plano|assinatura|licenca|restam|faltam|sobram)\b/.test(t)) return "status";
  if (/\b(vence|vencimento|expira|validade)\b.*\b(conta|assinatura|plano|licenca)\b/.test(t) || /\b(conta|assinatura|plano|licenca)\b.*\b(vence|vencimento|valida|expira)\b/.test(t) || /\bdias restantes\b/.test(t)) return "status";
  return null;
}
