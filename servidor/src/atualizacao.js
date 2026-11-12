// Qual é a versão mais nova de cada app, a partir da lista de publicações do GitHub.
// Vênus (privado, Venus-core): tags 1.N (APK), win-1.N (instalador Windows), robo-1.N.
// Vindex (público, Vindex-): tags 1.N com Vindex.apk e VindexSetup.exe.

export const REPO_DA_VENUS = "tonysantquees95-code/Venus-core";
export const REPO_DO_VINDEX = "tonysantquees95-code/Vindex-";
export const REPO_DO_VORTEX = "tonysantquees95-code/Vortex";

const APPS = {
  venus: { repo: REPO_DA_VENUS, tag: /^v?1\.(\d+)$/, arquivo: (n) => /\.apk$/i.test(n) && !/robo/i.test(n) },
  windows: { repo: REPO_DA_VENUS, tag: /^win-1\.(\d+)$/, arquivo: (n) => /\.exe$/i.test(n) },
  robo: { repo: REPO_DA_VENUS, tag: /^robo-1\.(\d+)$/, arquivo: (n) => /^venusrobo\.apk$/i.test(n) },
  "vindex-android": { repo: REPO_DO_VINDEX, tag: /^v?1\.(\d+)$/, arquivo: (n) => /\.apk$/i.test(n), publico: true },
  "vindex-windows": { repo: REPO_DO_VINDEX, tag: /^v?1\.(\d+)$/, arquivo: (n) => /\.exe$/i.test(n), publico: true },
  "vortex-android": { repo: REPO_DO_VORTEX, tag: /^v?1\.(\d+)$/, arquivo: (n) => /\.apk$/i.test(n), publico: true },
  "vortex-windows": { repo: REPO_DO_VORTEX, tag: /^v?1\.(\d+)$/, arquivo: (n) => /\.exe$/i.test(n), publico: true },
};

export const appConhecido = (app) => Object.prototype.hasOwnProperty.call(APPS, app) ? APPS[app] : null;

/** Maior número de versão que tenha o arquivo certo. Devolve { numero, r, apk } ou null. */
export function escolherVersao(lista, app) {
  const cfg = appConhecido(app);
  if (!cfg) return null;
  let melhor = null;
  for (const r of lista) {
    if (r.draft) continue;
    const m = cfg.tag.exec(r.tag_name || "");
    if (!m) continue;
    const apk = (r.assets || []).find((a) => cfg.arquivo(a.name));
    if (!apk) continue;
    const numero = Number(m[1]);
    if (!melhor || numero > melhor.numero) melhor = { numero, r, apk };
  }
  return melhor;
}
