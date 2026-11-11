// Conta arquivos e linhas de código do repositório (arquivos versionados) e escreve metricas.json para o Painel Dev.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const TEXTO = /\.(kt|java|xml|json|m?[jt]sx?|html|css|sql|ya?ml|toml|md|mjs|cjs|sh|gradle|kts|py|txt|properties)$/i;
const FORA = /(^|\/)(node_modules|dist|dist-desktop|build|android|\.gradle)\/|package-lock\.json$|\.map$/;
const NOMES = { kt: "Kotlin", java: "Java", ts: "TypeScript", tsx: "TypeScript", js: "JavaScript", jsx: "JavaScript", mjs: "JavaScript", cjs: "JavaScript", json: "JSON", xml: "XML", html: "HTML", css: "CSS", sql: "SQL", yml: "YAML", yaml: "YAML", toml: "TOML", md: "Markdown", sh: "Shell", gradle: "Gradle", kts: "Gradle", py: "Python", txt: "Texto", properties: "Propriedades" };

const arquivos = execFileSync("git", ["ls-files", "-z"], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }).split("\0").filter((f) => f && TEXTO.test(f) && !FORA.test(f));
const porLing = {}, porPasta = {};
let total = 0;
for (const f of arquivos) {
  let linhas;
  try { const t = readFileSync(f, "utf8"); linhas = t.length ? t.split("\n").length - (t.endsWith("\n") ? 1 : 0) : 0; } catch { continue; }
  const ext = (/\.([A-Za-z]+)$/.exec(f) || [, "outros"])[1].toLowerCase();
  const nome = NOMES[ext] || ext;
  const l = (porLing[nome] ||= { nome, arquivos: 0, linhas: 0 });
  l.arquivos++; l.linhas += linhas;
  const pasta = f.includes("/") ? f.split("/")[0] : "(raiz)";
  const p = (porPasta[pasta] ||= { nome: pasta, arquivos: 0, linhas: 0 });
  p.arquivos++; p.linhas += linhas;
  total += linhas;
}
const ordenar = (o) => Object.values(o).sort((a, b) => b.linhas - a.linhas);
writeFileSync("metricas.json", JSON.stringify({ quando: new Date().toISOString(), commit: (process.env.GITHUB_SHA || "").slice(0, 7), arquivos: arquivos.length, linhas: total, linguagens: ordenar(porLing), pastas: ordenar(porPasta) }));
console.log(`${arquivos.length} arquivos, ${total} linhas`);
