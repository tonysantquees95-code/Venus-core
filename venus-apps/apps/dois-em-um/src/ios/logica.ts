// Instalação na Tela de Início do iPhone: quando mostrar o tutorial (lógica pura).
export const CHAVE_DISPENSADO = "venus:ios:dispensado";
const SEMANA = 7 * 86_400_000;

export function ehIOS(ua: string, maxToque = 0): boolean {
  return /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && maxToque > 1); // iPadOS se apresenta como Mac
}
/** No iOS, Chrome (CriOS), Firefox (FxiOS) e Edge (EdgiOS) também instalam, pelo botão Compartilhar. */
export function navegadorIOS(ua: string): "safari" | "chrome" | "outro" {
  if (/CriOS/i.test(ua)) return "chrome";
  if (/FxiOS|EdgiOS|OPiOS/i.test(ua)) return "outro";
  return "safari";
}
export function mostrarTutorialIOS(o: { ua: string; maxToque?: number; standalone: boolean; dispensadoEm?: number; agora?: number }): boolean {
  if (!ehIOS(o.ua, o.maxToque) || o.standalone) return false;
  return (o.agora ?? Date.now()) - (o.dispensadoEm ?? 0) > SEMANA;
}
export const PASSOS_IOS = (navegador: "safari" | "chrome" | "outro") => [
  { titulo: "Toque em Compartilhar", texto: navegador === "chrome" ? "No Chrome, é o ícone de quadrado com seta para cima, na barra de endereço (no topo ou no menu ⋯)." : "É o ícone de quadrado com seta para cima, na barra inferior do Safari.", icone: "⬆️" },
  { titulo: "Role e escolha “Adicionar à Tela de Início”", texto: "Role a lista de opções para baixo até achar “Adicionar à Tela de Início” (ícone de um + dentro de um quadrado).", icone: "➕" },
  { titulo: "Toque em Adicionar", texto: "Confirme no canto superior direito. A Vênus aparece na tela inicial e abre em tela cheia, sem a barra do navegador.", icone: "✅" }
];
