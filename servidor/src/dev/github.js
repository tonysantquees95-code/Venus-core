// Dados dos repositórios para o Painel Dev: métricas de código, estado dos builds e APKs publicados.
// Usa o GITHUB_TOKEN (só leitura) do Worker. Tudo guardado em cache por alguns minutos.
export const REPO_VENUS = "tonysantquees95-code/Venus-core";
export const REPO_DEV_CENTER = "tonysantquees95-code/vetorial-dev-center";

/** Projetos do ecossistema. Vindex e Vortex ganham repositório pelo painel (dev_config "repo:<id>") ou pela variável do Worker. */
export const PROJETOS = [
  {
    id: "venus", nome: "Vênus", repo: REPO_VENUS,
    descricao: "Assistente e plataforma do lojista: app Android, web/PWA, Windows, iOS e servidor.",
    apks: [
      { app: "venus", rotulo: "Vênus (Android)", tag: /^v?1\.(\d+)$/, arquivo: (n) => /\.apk$/i.test(n) && !/robo|dev/i.test(n) },
      { app: "robo", rotulo: "Vênus Robô", tag: /^robo-1\.(\d+)$/, arquivo: (n) => /^venusrobo\.apk$/i.test(n) },
    ],
  },
  {
    id: "devcenter", nome: "Vetorial Dev Center", repo: REPO_DEV_CENTER,
    descricao: "Este painel: site (PWA) e APK Dev do administrador.",
    apks: [{ app: "dev", rotulo: "Vetorial Dev (este painel)", tag: /^dev-1\.(\d+)$/, arquivo: (n) => /\.apk$/i.test(n) }],
  },
  { id: "vindex", nome: "Vindex", repo: "", descricao: "Ainda sem repositório ligado ao painel.", apks: [{ app: "vindex", rotulo: "Vindex (Android)", tag: /^v?\d+\.(\d+)(?:\.\d+)?$/, arquivo: (n) => /\.apk$/i.test(n) }] },
  { id: "vortex", nome: "Vortex", repo: "", descricao: "Ainda sem repositório ligado ao painel.", apks: [{ app: "vortex", rotulo: "Vortex (Android)", tag: /^v?\d+\.(\d+)(?:\.\d+)?$/, arquivo: (n) => /\.apk$/i.test(n) }] },
];

export const REPO_VALIDO = /^[A-Za-z0-9_.-]{1,60}\/[A-Za-z0-9_.-]{1,100}$/;

export function gh(env, caminho, extra = {}) {
  return fetch(`https://api.github.com${caminho}`, {
    ...extra,
    headers: { Authorization: `Bearer ${env.GITHUB_TOKEN}`, "User-Agent": "venus-servidor", "X-GitHub-Api-Version": "2022-11-28", Accept: "application/vnd.github+json", ...(extra.headers || {}) },
  });
}

/** Cache de poucos minutos na borda (some em testes, onde não há caches.default). */
export async function comCache(chave, ttl, buscar) {
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const req = new Request(`https://cache.venus/dev/${encodeURIComponent(chave)}`);
  if (cache) {
    const g = await cache.match(req);
    if (g) return g.json();
  }
  const v = await buscar();
  if (cache && v !== undefined) await cache.put(req, new Response(JSON.stringify(v), { headers: { "cache-control": `max-age=${ttl}` } }));
  return v;
}

const TEXTO = /\.(kt|java|xml|json|m?[jt]sx?|html|css|sql|ya?ml|toml|md|mjs|cjs|sh|gradle|kts|py)$/i;
const IGNORADOS = /(^|\/)(node_modules|dist|dist-desktop|build|android|\.gradle)\/|package-lock\.json$|\.map$/;

/** Estimativa quando ainda não existe o arquivo exato do CI: arquivos de texto do repositório e linhas por tamanho (~34 bytes/linha). */
export function estimarPelaArvore(arvore) {
  const blobs = (arvore || []).filter((x) => x.type === "blob" && TEXTO.test(x.path) && !IGNORADOS.test(x.path));
  const porLinguagem = {};
  let linhas = 0;
  for (const b of blobs) {
    const l = Math.max(1, Math.round((b.size || 0) / 34));
    const ext = (b.path.match(/\.([A-Za-z]+)$/) || [, "outros"])[1].toLowerCase();
    const p = (porLinguagem[ext] ||= { nome: ext, arquivos: 0, linhas: 0 });
    p.arquivos++; p.linhas += l; linhas += l;
  }
  return { arquivos: blobs.length, linhas, linguagens: Object.values(porLinguagem).sort((a, b) => b.linhas - a.linhas).slice(0, 8), fonte: "estimado", atualizado: 0 };
}

async function baixarAsset(env, repo, asset) {
  const r = await gh(env, `/repos/${repo}/releases/assets/${asset.id}`, { headers: { Accept: "application/octet-stream" }, redirect: "manual" });
  const link = r.headers.get("location");
  return link ? fetch(link) : r;
}

/** Métricas de código: o CI publica o número exato (release "metricas"); sem ele, estimativa pela árvore do Git. */
export async function metricasDoRepo(env, repo) {
  return comCache(`metricas:${repo}`, 600, async () => {
    try {
      const rel = await gh(env, `/repos/${repo}/releases/tags/metricas`);
      if (rel.ok) {
        const a = ((await rel.json()).assets || []).find((x) => x.name === "metricas.json");
        if (a) {
          const d = await (await baixarAsset(env, repo, a)).json();
          if (d && typeof d.linhas === "number") return { arquivos: d.arquivos, linhas: d.linhas, linguagens: (d.linguagens || []).slice(0, 8), pastas: (d.pastas || []).slice(0, 8), commit: d.commit || "", fonte: "ci", atualizado: Date.parse(d.quando) || 0 };
        }
      }
      const t = await gh(env, `/repos/${repo}/git/trees/HEAD?recursive=1`);
      if (t.ok) return estimarPelaArvore((await t.json()).tree);
    } catch (e) {
      console.error("metricas", repo, e && e.message);
    }
    return null;
  });
}

/** Último resultado de cada workflow (build do APK, servidor, web). */
export async function buildsDoRepo(env, repo) {
  return comCache(`builds:${repo}`, 120, async () => {
    try {
      const r = await gh(env, `/repos/${repo}/actions/runs?per_page=30`);
      if (!r.ok) return null;
      const vistos = new Map();
      for (const x of (await r.json()).workflow_runs || []) {
        if (!vistos.has(x.name)) vistos.set(x.name, { workflow: x.name, estado: x.status, resultado: x.conclusion, numero: x.run_number, commit: String(x.head_sha || "").slice(0, 7), quando: Date.parse(x.updated_at) || 0, url: x.html_url });
      }
      return [...vistos.values()];
    } catch (e) {
      console.error("builds", repo, e && e.message);
      return null;
    }
  });
}

/** Resume a lista de builds num único estado: falhou > rodando > ok. */
export function estadoGeral(builds) {
  if (!builds || !builds.length) return "desconhecido";
  if (builds.some((b) => b.estado === "completed" && ["failure", "timed_out", "startup_failure"].includes(b.resultado))) return "falhou";
  if (builds.some((b) => b.estado !== "completed")) return "rodando";
  return "ok";
}

export async function listaDeVersoes(env, repo) {
  return comCache(`releases:${repo}`, 300, async () => {
    const r = await gh(env, `/repos/${repo}/releases?per_page=100`);
    return r.ok ? await r.json() : null;
  });
}

/** Versão mais nova de cada APK do projeto (número, página da versão e tamanho). */
export function apksDoProjeto(projeto, lista) {
  return projeto.apks.map((a) => {
    let melhor = null;
    for (const r of lista || []) {
      if (r.draft) continue;
      const m = a.tag.exec(r.tag_name || "");
      if (!m) continue;
      const arq = (r.assets || []).find((x) => a.arquivo(x.name));
      if (arq && (!melhor || Number(m[1]) > melhor.numero)) melhor = { numero: Number(m[1]), tag: r.tag_name, pagina: r.html_url, tamanho: arq.size || 0, publicado: Date.parse(r.published_at) || 0, arquivoId: arq.id };
    }
    return { app: a.app, rotulo: a.rotulo, versao: melhor ? { numero: melhor.numero, tag: melhor.tag, pagina: melhor.pagina, tamanho: melhor.tamanho, publicado: melhor.publicado } : null, _id: melhor && melhor.arquivoId };
  });
}

/** Link temporário (poucos minutos) para baixar o APK direto do GitHub. */
export async function linkDoApk(env, repo, assetId) {
  const d = await gh(env, `/repos/${repo}/releases/assets/${assetId}`, { headers: { Accept: "application/octet-stream" }, redirect: "manual" });
  return d.headers.get("location") || "";
}
