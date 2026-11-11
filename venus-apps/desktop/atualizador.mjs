// Atualização da Vênus para Windows: só a lógica pura (testável). O download e a instalação ficam no main.js.
// O servidor da Vênus diz qual é a versão mais nova em /atualizacao?app=windows e dá o link do instalador.

const HOSTS = [/^github\.com$/i, /(^|\.)githubusercontent\.com$/i];
export const LIMITE_DE_BYTES = 500 * 1024 * 1024;

/** "1.204.0" ou "1.204" → 204 (o número do build do CI). Qualquer outra coisa → 0. */
export function numeroDaVersao(v) {
  const m = /^1\.(\d{1,6})(?:\.\d+)?$/.exec(String(v ?? "").trim());
  return m ? Number(m[1]) : 0;
}

export const temNovaVersao = (atual, nova) => Number.isInteger(nova) && nova > 0 && nova > numeroDaVersao(atual);

/** Só baixa de https e só do GitHub (onde o servidor publica os instaladores). */
export function urlSegura(u) {
  try {
    const x = new URL(String(u));
    return x.protocol === "https:" && HOSTS.some((r) => r.test(x.hostname));
  } catch {
    return false;
  }
}

/** Valida a resposta do servidor. Devolve { numero, notas, tamanho, sha256, url } ou null. */
export function lerInfo(j) {
  if (!j || typeof j !== "object") return null;
  const numero = Number(j.numero);
  if (!Number.isInteger(numero) || numero <= 0) return null;
  if (!urlSegura(j.url)) return null;
  const sha256 = typeof j.sha256 === "string" && /^[0-9a-f]{64}$/i.test(j.sha256) ? j.sha256.toLowerCase() : "";
  const tamanho = Number.isFinite(Number(j.tamanho)) ? Math.max(0, Number(j.tamanho)) : 0;
  if (tamanho > LIMITE_DE_BYTES) return null;
  return { numero, notas: typeof j.notas === "string" ? j.notas.slice(0, 600) : "", tamanho, sha256, url: String(j.url) };
}

/** O arquivo baixado confere com o que o servidor disse? (hash só é exigido quando o servidor informou.) */
export function arquivoConfere(info, tamanhoBaixado, sha256Baixado) {
  if (info.tamanho && tamanhoBaixado !== info.tamanho) return false;
  if (info.sha256 && String(sha256Baixado).toLowerCase() !== info.sha256) return false;
  return tamanhoBaixado > 1_000_000; // um instalador de verdade tem megabytes
}

/** Instalação silenciosa do NSIS: atualiza por cima e abre a Vênus de novo. */
export const argsDoInstalador = () => ["/S", "--updated", "--force-run"];
