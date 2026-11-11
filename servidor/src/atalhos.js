// Atalhos curtos e fáceis de digitar: /venus (página de download), /venus/android, /venus/windows e /paineldev.
export const PAINEL_DEV = "https://tonysantquees95-code.github.io/vetorial-dev-center/";

export const VINDEX_BAIXAR = "https://github.com/tonysantquees95-code/Vindex-/releases/latest/download";

export function ehAtalho(caminho) {
  return /^\/(venus|vindex)(\/(android|windows))?$|^\/paineldev$/i.test(caminho.toLowerCase());
}

const pagina = (origem) => `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Vênus · Vetorial Tech</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#070B14;color:#e8ecf4;font:16px system-ui,sans-serif}main{max-width:420px;padding:24px;text-align:center}h1{margin:0 0 6px}p{color:#9aa6bd}a{display:block;margin:12px 0;padding:14px;border-radius:14px;background:#7c3aed;color:#fff;text-decoration:none;font-weight:600}a.s{background:#1b2436}</style></head>
<body><main><h1>Vênus</h1><p>Assistente e central da sua loja. Baixe o app:</p>
<a href="${origem}/venus/android">Baixar para Android</a><a href="${origem}/venus/windows">Baixar para Windows</a><a class="s" href="${PAINEL_DEV}">Painel Dev (administrador)</a></main></body></html>`;

const paginaVindex = (origem) => pagina(origem).replace("<h1>Vênus</h1><p>Assistente e central da sua loja. Baixe o app:</p>", "<h1>Vindex</h1><p>Otimizador e organizador de arquivos. Baixe o app:</p>").replace(`${origem}/venus/android`, `${origem}/vindex/android`).replace(`${origem}/venus/windows`, `${origem}/vindex/windows`).replace("<title>Vênus", "<title>Vindex");

/** Resposta do atalho: a página /venus, ou um redirecionamento para o download / painel. */
export function responderAtalho(url, caminho) {
  const c = caminho.toLowerCase();
  const origem = url.origin;
  if (c === "/vindex/android") return Response.redirect(`${VINDEX_BAIXAR}/Vindex.apk`, 302);
  if (c === "/vindex/windows") return Response.redirect(`${VINDEX_BAIXAR}/VindexSetup.exe`, 302);
  if (c === "/vindex") return new Response(paginaVindex(origem), { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" } });
  if (c === "/paineldev") return Response.redirect(PAINEL_DEV, 302);
  if (c === "/venus") return new Response(pagina(origem), { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" } });
  const app = c.endsWith("/windows") ? "windows" : "venus";
  return Response.redirect(`${origem}/atualizacao?app=${app}&baixar=1`, 302);
}
