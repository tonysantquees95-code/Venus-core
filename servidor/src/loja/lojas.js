// Cadastro e autenticação dos lojistas. O app guarda o token da loja; aqui só fica o hash.
import { aleatorio, sha256Hex } from "./cripto.js";

export const DIAS_DE_TESTE = 7;
export const MAX_CANAIS = 3;
const DIA = 86_400_000;

export class ErroLoja extends Error {
  constructor(status, codigo, mensagem) {
    super(mensagem);
    this.status = status;
    this.codigo = codigo;
  }
}

export function slugDe(texto) {
  return String(texto || "")
    .normalize("NFD").replace(/[̀-ͯ]/g, "")
    .toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 32);
}

/** Um teste grátis por celular: o app manda um identificador do aparelho (só o hash fica guardado). */
export async function registrarLoja(env, { nome, slug, dispositivo }, agora = Date.now()) {
  const id = aleatorio(8);
  const token = `vl_${aleatorio(24)}`;
  let slugFinal = slugDe(slug || nome) || `loja-${id.slice(0, 6)}`;
  const existe = await env.DB.prepare("SELECT 1 AS x FROM lojas WHERE slug = ?").bind(slugFinal).first();
  if (existe) slugFinal = `${slugFinal.slice(0, 25)}-${id.slice(0, 5)}`;
  const disp = dispositivo ? await sha256Hex(`dispositivo:${String(dispositivo).slice(0, 200)}`) : "";
  const jaTeve = disp ? await env.DB.prepare("SELECT 1 AS x FROM lojas WHERE dispositivo = ?").bind(disp).first() : null;
  const trialFim = jaTeve ? agora : agora + DIAS_DE_TESTE * DIA;
  await env.DB.prepare("INSERT INTO lojas (id, token_hash, nome, slug, criado, trial_fim, plano, ativo_ate, dispositivo) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)")
    .bind(id, await sha256Hex(token), String(nome || "").slice(0, 80), slugFinal, agora, trialFim, jaTeve ? "sem_teste" : "trial", disp).run();
  return { id, token, slug: slugFinal, trialFim, testeGratisUsado: !!jaTeve };
}

/** O dono (administrador master) nunca perde o acesso: a loja ligada ao e-mail dele vale para sempre. */
export const emailAdmin = (env) => String(env.ADMIN_EMAIL || "").trim().toLowerCase() || "vetorialtech.dev@gmail.com";
const SEM_FIM = 4102444800000; // 2100-01-01
export async function comPrivilegios(env, loja) {
  if (!loja) return loja;
  try {
    const u = await env.DB.prepare("SELECT email FROM usuarios WHERE loja = ?").bind(loja.id).first();
    if (u && String(u.email || "").trim().toLowerCase() === emailAdmin(env)) return { ...loja, ativo_ate: SEM_FIM, plano: "dono" };
  } catch { /* sem tabela de contas: segue sem privilégio */ }
  return loja;
}

export async function lojaDoPedido(env, req) {
  return comPrivilegios(env, await lojaDoPedidoBruta(env, req));
}

async function lojaDoPedidoBruta(env, req) {
  // Vetorial ID: o mesmo login (JWT) vale para a loja, com sessão e batimento conferidos
  const bruto = (/^Bearer\s+(\S+)$/.exec(req.headers.get("authorization") || "") || [])[1] || "";
  if (bruto.startsWith("eyJ")) {
    const { validarSessao } = await import("../id/conta.js");
    const ctx = await validarSessao(env, req);
    const l = ctx.usuario.loja ? await env.DB.prepare("SELECT * FROM lojas WHERE id = ?").bind(ctx.usuario.loja).first() : null;
    if (!l) throw new ErroLoja(404, "sem_loja", "Essa conta ainda não tem loja.");
    return l;
  }
  const m = /^Bearer\s+(vl_[a-f0-9]{48})$/.exec(req.headers.get("authorization") || "");
  if (!m) throw new ErroLoja(401, "sem_token", "Faltou o token da loja.");
  const loja = await env.DB.prepare("SELECT * FROM lojas WHERE token_hash = ?").bind(await sha256Hex(m[1])).first();
  if (!loja) throw new ErroLoja(401, "token_invalido", "Token da loja inválido.");
  // loja ligada a uma conta Vetorial ID: só o login da conta vale (assim 3 aparelhos / 2 ativos também vale para a loja)
  if (await env.DB.prepare("SELECT 1 AS x FROM usuarios WHERE loja = ?").bind(loja.id).first()) {
    throw new ErroLoja(403, "exige_vetorial_id", "Entre com o seu Vetorial ID (Menu › Conta) para acessar a loja.");
  }
  return loja;
}

/** O acesso vale durante o teste grátis ou enquanto a assinatura estiver paga. */
export function acessoLiberado(loja, agora = Date.now()) {
  return agora < Math.max(loja.trial_fim || 0, loja.ativo_ate || 0);
}
