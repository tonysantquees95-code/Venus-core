// Falha se qualquer arquivo do monorepo referenciar pastas ou endereços de loja/site.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const raiz = new URL("..", import.meta.url).pathname;
const PASTAS_PROIBIDAS = ["loja", "site", "floot"];
const TERMOS = [/desejo-de-intimidade/i, /\bfloot\b/i, /\.\.\/loja/i, /workers\.dev\/_api/i];
const IGNORAR = new Set(["node_modules", ".git", "dist", "android", ".wrangler", "verificar-isolamento.mjs"]);
const problemas = [];

function varrer(dir) {
  for (const nome of readdirSync(dir)) {
    if (IGNORAR.has(nome)) continue;
    const caminho = join(dir, nome);
    const rel = relative(raiz, caminho);
    if (statSync(caminho).isDirectory()) {
      if (PASTAS_PROIBIDAS.includes(nome.toLowerCase())) problemas.push(`pasta proibida: ${rel}`);
      varrer(caminho);
    } else if (/\.(json|m?[jt]s|css|html|md|ya?ml|toml)$/.test(nome)) {
      const texto = readFileSync(caminho, "utf8");
      for (const t of TERMOS) if (t.test(texto)) problemas.push(`${rel}: referência a loja/site (${t})`);
    }
  }
}
varrer(raiz);
if (problemas.length) { console.error(problemas.join("\n")); process.exit(1); }
console.log("Isolamento OK: nenhuma referência a loja/site.");
