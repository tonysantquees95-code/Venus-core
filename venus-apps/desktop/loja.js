// Administração da loja (site no Floot) pelo Windows: entra com o e-mail/senha de administrador, guarda a senha cifrada
// pelo Windows (safeStorage) e fala só com os endereços permitidos abaixo. O que a janela vê nunca inclui a senha nem o cookie.
const COOKIE = "floot_built_app_session";
const GET_OK = new Set(["/_api/admin/products"]);
const POST_OK = new Set(["/_api/admin/product_save", "/_api/admin/stock_update", "/_api/admin/upload"]);

class ErroDaLoja extends Error {}

const dados = (texto) => { try { const r = JSON.parse(texto); return r && typeof r === "object" && r.json && typeof r.json === "object" ? r.json : r; } catch { return {}; } };

/** Só https e só a origem (sem caminho): é para onde o login e as chamadas do painel vão. */
function origemDoSite(site) {
  let u;
  try { u = new URL(/^https?:\/\//i.test(String(site)) ? String(site) : `https://${site}`); } catch { throw new ErroDaLoja("Endereço do site inválido."); }
  if (u.protocol !== "https:") throw new ErroDaLoja("O site precisa começar com https://.");
  return u.origin;
}

/**
 * @param {{ fetchFn: Function, ler: () => (string|null), gravar: (s: string|null) => void, cifrar: (t: string) => string, decifrar: (s: string) => string }} d
 */
function criarLoja({ fetchFn, ler, gravar, cifrar, decifrar }) {
  let cookie = "";

  function credenciais() {
    try { const bruto = ler(); if (!bruto) return null; const c = JSON.parse(decifrar(bruto)); return c && c.site && c.email && c.senha ? c : null; } catch { return null; }
  }

  async function entrarCom(site, email, senha) {
    const origem = origemDoSite(site);
    let r;
    try {
      r = await fetchFn(`${origem}/_api/auth/login_with_password`, {
        method: "POST", headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ json: { identifier: String(email).trim(), password: String(senha) } }), redirect: "manual",
      });
    } catch { throw new ErroDaLoja("Não consegui falar com o site agora. Confira a internet."); }
    const texto = await r.text();
    if (r.status === 401 || r.status === 429) throw new ErroDaLoja(dados(texto).message || "E-mail ou senha incorretos.");
    if (r.status < 200 || r.status > 299) throw new ErroDaLoja(`O site respondeu com erro ${r.status}.`);
    const cabecalhos = typeof r.headers.getSetCookie === "function" ? r.headers.getSetCookie() : [r.headers.get("set-cookie") || ""];
    const achado = cabecalhos.flatMap((h) => String(h).split(/,(?=\s*[A-Za-z0-9_]+=)/)).map((h) => h.trim()).find((h) => h.startsWith(`${COOKIE}=`));
    if (!achado) throw new ErroDaLoja("O site não abriu a sessão.");
    cookie = achado.split(";")[0];
    const papel = (dados(texto).user || {}).role;
    return { origem, papel };
  }

  /** Testa o login e, se for administrador, guarda (cifrado). */
  async function conectar(site, email, senha) {
    const { origem, papel } = await entrarCom(site, email, senha);
    if (papel !== "admin") { cookie = ""; throw new ErroDaLoja("Essa conta não é a de administrador da loja."); }
    gravar(cifrar(JSON.stringify({ site: origem, email: String(email).trim(), senha: String(senha) })));
    return estado();
  }

  function sair() { cookie = ""; gravar(null); return estado(); }

  function estado() { const c = credenciais(); return { conectada: !!c, email: c ? c.email : "", site: c ? c.site : "" }; }

  async function pedir(metodo, caminho, corpo) {
    if (!(metodo === "GET" ? GET_OK : POST_OK).has(caminho)) throw new ErroDaLoja("Esse endereço do painel não é permitido.");
    const c = credenciais();
    if (!c) throw new ErroDaLoja("A loja ainda não está conectada.");
    for (let tentativa = 0; tentativa < 2; tentativa++) {
      if (!cookie || tentativa === 1) await entrarCom(c.site, c.email, c.senha);
      let r;
      try {
        r = await fetchFn(`${c.site}${caminho}`, {
          method: metodo, redirect: "manual",
          headers: { accept: "application/json", cookie, ...(metodo === "POST" ? { "content-type": "application/json" } : {}) },
          body: metodo === "POST" ? JSON.stringify({ json: corpo }) : undefined,
        });
      } catch { throw new ErroDaLoja("Não consegui falar com a loja agora. Confira a internet."); }
      const texto = await r.text();
      if (r.status === 401 || r.status === 403) { if (tentativa === 0) continue; throw new ErroDaLoja(r.status === 403 ? "A conta salva não é de administrador." : "Não consegui entrar na loja. Confira o login."); }
      if (r.status < 200 || r.status > 299) {
        const d = dados(texto);
        const motivo = d.error || d.message;
        throw new ErroDaLoja(motivo ? `O site recusou: ${motivo}` : `O site respondeu com erro ${r.status}. Nada foi mudado.`);
      }
      return dados(texto);
    }
    throw new ErroDaLoja("Não consegui entrar na loja.");
  }

  const produtos = async () => { const r = await pedir("GET", "/_api/admin/products"); return Array.isArray(r.products) ? r.products : []; };
  const salvarProduto = (corpo) => pedir("POST", "/_api/admin/product_save", corpo);
  const definirEstoque = (id, quantidade) => pedir("POST", "/_api/admin/stock_update", { productId: Number(id), quantity: Number(quantidade), mode: "set" });

  /** Sobe a foto (JPEG em base64) pelo endereço assinado do site e devolve o endereço final. */
  async function enviarFoto(base64) {
    const bytes = Buffer.from(String(base64), "base64");
    if (!bytes.length || bytes.length > 8_000_000) throw new ErroDaLoja("A foto está vazia ou grande demais.");
    const r = await pedir("POST", "/_api/admin/upload", { contentType: "image/jpeg", sizeBytes: bytes.length });
    if (!r.presignedUrl || !r.url) throw new ErroDaLoja("O site não aceitou a foto.");
    const destino = new URL(r.presignedUrl);
    if (destino.protocol !== "https:") throw new ErroDaLoja("Destino da foto inseguro.");
    const cab = { "content-type": "image/jpeg", ...(r.headers && typeof r.headers === "object" ? r.headers : {}) };
    let up;
    try { up = await fetchFn(destino.href, { method: "PUT", headers: cab, body: bytes }); } catch { throw new ErroDaLoja("A foto não subiu. Confira a internet."); }
    if (!up.ok) throw new ErroDaLoja(`A foto não subiu (erro ${up.status}).`);
    const c = credenciais();
    return new URL(r.url, c ? c.site : undefined).href;
  }

  return { conectar, sair, estado, produtos, salvarProduto, definirEstoque, enviarFoto };
}

module.exports = { criarLoja, ErroDaLoja, origemDoSite };
