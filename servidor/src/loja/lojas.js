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

export async function registrarLoja(env, { nome, slug }, agora = Date.now()) {
  const id = aleatorio(8);
  const token = `vl_${aleatorio(24)}`;
  let slugFinal = slugDe(slug || nome) || `loja-${id.slice(0, 6)}`;
  const existe = await env.DB.prepare("SELECT 1 AS x FROM lojas WHERE slug = ?").bind(slugFinal).first();
  if (existe) slugFinal = `${slugFinal.slice(0, 25)}-${id.slice(0, 5)}`;
  await env.DB.prepare("INSERT INTO lojas (id, token_hash, nome, slug, criado, trial_fim, plano, ativo_ate) VALUES (?, ?, ?, ?, ?, ?, 'trial', 0)")
    .bind(id, await sha256Hex(token), String(nome || "").slice(0, 80), slugFinal, agora, agora + DIAS_DE_TESTE * DIA).run();
  return { id, token, slug: slugFinal, trialFim: agora + DIAS_DE_TESTE * DIA };
}

export async function lojaDoPedido(env, req) {
  const m = /^Bearer\s+(vl_[a-f0-9]{48})$/.exec(req.headers.get("authorization") || "");
  if (!m) throw new ErroLoja(401, "sem_token", "Faltou o token da loja.");
  const loja = await env.DB.prepare("SELECT * FROM lojas WHERE token_hash = ?").bind(await sha256Hex(m[1])).first();
  if (!loja) throw new ErroLoja(401, "token_invalido", "Token da loja inválido.");
  return loja;
}

/** O acesso vale durante o teste grátis ou enquanto a assinatura estiver paga. */
export function acessoLiberado(loja, agora = Date.now()) {
  return agora < Math.max(loja.trial_fim || 0, loja.ativo_ate || 0);
}
