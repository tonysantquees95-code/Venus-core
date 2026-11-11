// Plano Fundadores: R$ 49,90 por mês, 7 dias grátis. Pagamento por InfinitePay ou Mercado Pago, confirmado pelo webhook
// (e conferido na própria API do gateway). O acesso pago só é liberado depois da verificação em duas etapas (2FA).
import { aleatorio, cifrar, decifrar, hmacHex, iguais } from "./cripto.js";
import { ErroLoja } from "./lojas.js";
import { conferirTotp, novoSegredo, uriOtp } from "./totp.js";

export const PRECO_CENTAVOS = 4990;
export const DIAS_DO_PLANO = 30;
const DIA = 86_400_000;
const titulo = "Vênus — Plano Fundadores (30 dias)";

async function http(url, op) {
  const r = await fetch(url, { ...op, signal: AbortSignal.timeout(15000) });
  const t = await r.text();
  let d = {};
  try { d = t ? JSON.parse(t) : {}; } catch (e) {}
  if (!r.ok) throw new ErroLoja(502, "gateway_erro", `O gateway respondeu ${r.status}.`);
  return d;
}

export async function iniciarCobranca(env, loja, gateway, origem, agora = Date.now()) {
  if (!["infinitepay", "mercadopago"].includes(gateway)) throw new ErroLoja(400, "gateway_invalido", "Use infinitepay ou mercadopago.");
  const id = `ass_${loja.id}_${aleatorio(4)}`;
  let url;
  if (gateway === "infinitepay") {
    if (!env.INFINITEPAY_HANDLE) throw new ErroLoja(503, "gateway_desligado", "A InfinitePay ainda não foi configurada neste servidor.");
    const d = await http("https://api.checkout.infinitepay.io/links", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ handle: String(env.INFINITEPAY_HANDLE).replace(/^\$/, ""), order_nsu: id, items: [{ quantity: 1, price: PRECO_CENTAVOS, description: titulo }], webhook_url: `${origem}/assinatura/webhook/infinitepay` }),
    });
    url = d.url || d.checkout_url || d.link;
  } else {
    if (!env.MERCADOPAGO_ACCESS_TOKEN) throw new ErroLoja(503, "gateway_desligado", "O Mercado Pago ainda não foi configurado neste servidor.");
    const d = await http("https://api.mercadopago.com/checkout/preferences", {
      method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${env.MERCADOPAGO_ACCESS_TOKEN}` },
      body: JSON.stringify({ items: [{ title: titulo, quantity: 1, currency_id: "BRL", unit_price: PRECO_CENTAVOS / 100 }], external_reference: id, notification_url: `${origem}/assinatura/webhook/mercadopago` }),
    });
    url = d.init_point;
  }
  if (!url) throw new ErroLoja(502, "gateway_sem_link", "O gateway não devolveu o link de pagamento.");
  await env.DB.prepare("INSERT INTO pagamentos (id, loja, gateway, valor_centavos, status, criado, atualizado) VALUES (?, ?, ?, ?, 'aguardando', ?, ?)").bind(id, loja.id, gateway, PRECO_CENTAVOS, agora, agora).run();
  return { id, url, valorCentavos: PRECO_CENTAVOS };
}

/** Marca o pagamento como pago (uma vez só) e deixa a liberação esperando o 2FA. */
export async function confirmarPagamento(env, id, valorCentavos, refGateway, agora = Date.now()) {
  const p = await env.DB.prepare("SELECT * FROM pagamentos WHERE id = ?").bind(id).first();
  if (!p || valorCentavos < p.valor_centavos) return false;
  const r = await env.DB.prepare("INSERT OR IGNORE INTO avisos_gateway (chave, criado) VALUES (?, ?)").bind(`${p.gateway}:${refGateway || id}`, agora).run();
  if (r.meta.changes !== 1) return true; // já processado
  await env.DB.prepare("UPDATE pagamentos SET status = 'pago', ref_gateway = ?, atualizado = ? WHERE id = ? AND status = 'aguardando'").bind(String(refGateway || ""), agora, id).run();
  return true;
}

export async function webhookInfinitePay(env, corpo) {
  const id = String(corpo.order_nsu || "");
  const p = await env.DB.prepare("SELECT * FROM pagamentos WHERE id = ? AND gateway = 'infinitepay'").bind(id).first();
  if (!p || !corpo.transaction_nsu || !(corpo.invoice_slug || corpo.slug)) return { ignorado: true };
  // não confia no corpo do webhook: pergunta à InfinitePay se o pagamento existe
  const d = await http("https://api.checkout.infinitepay.io/payment_check", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ handle: String(env.INFINITEPAY_HANDLE || "").replace(/^\$/, ""), order_nsu: id, transaction_nsu: corpo.transaction_nsu, slug: corpo.invoice_slug || corpo.slug }),
  });
  if (d.paid !== true) return { pago: false };
  const valor = Number(d.paid_amount ?? d.amount);
  const ok = await confirmarPagamento(env, id, Number.isFinite(valor) ? valor : 0, corpo.transaction_nsu);
  return { pago: ok };
}

export async function assinaturaMercadoPagoValida(env, req, dataId) {
  if (!env.MERCADOPAGO_WEBHOOK_SECRET) return true; // sem segredo configurado, a conferência na API (abaixo) é a garantia
  const partes = Object.fromEntries((req.headers.get("x-signature") || "").split(",").map((s) => s.trim().split("=")));
  if (!partes.ts || !partes.v1) return false;
  const manifesto = `id:${String(dataId).toLowerCase()};request-id:${req.headers.get("x-request-id") || ""};ts:${partes.ts};`;
  return iguais(partes.v1, await hmacHex(env.MERCADOPAGO_WEBHOOK_SECRET, manifesto));
}

export async function webhookMercadoPago(env, req, corpo, url) {
  const dataId = corpo?.data?.id || url.searchParams.get("data.id") || url.searchParams.get("id");
  const tipo = corpo?.type || corpo?.topic || url.searchParams.get("type") || url.searchParams.get("topic");
  if (!dataId || tipo !== "payment") return { ignorado: true };
  if (!(await assinaturaMercadoPagoValida(env, req, dataId))) throw new ErroLoja(401, "assinatura", "Assinatura inválida.");
  const d = await http(`https://api.mercadopago.com/v1/payments/${encodeURIComponent(dataId)}`, { headers: { authorization: `Bearer ${env.MERCADOPAGO_ACCESS_TOKEN}` } });
  if (d.status !== "approved" || !d.external_reference) return { pago: false };
  const ok = await confirmarPagamento(env, String(d.external_reference), Math.round(Number(d.transaction_amount) * 100), String(d.id));
  return { pago: ok };
}

// ------------------------------------------------------------------ 2FA e liberação
export async function iniciar2fa(env, loja) {
  if (loja.totp_ativo) throw new ErroLoja(409, "2fa_ja_ativo", "A verificação em duas etapas já está ativa.");
  const segredo = loja.totp_segredo ? (await decifrar(env, loja.totp_segredo)).s : novoSegredo();
  if (!loja.totp_segredo) await env.DB.prepare("UPDATE lojas SET totp_segredo = ? WHERE id = ?").bind(await cifrar(env, { s: segredo }), loja.id).run();
  return { segredo, otpauth: uriOtp(segredo, loja.nome || loja.slug || loja.id) };
}

async function conferirCodigo(env, loja, codigo, agora) {
  if (!loja.totp_segredo) throw new ErroLoja(409, "sem_2fa", "Ative a verificação em duas etapas primeiro.");
  const { s } = await decifrar(env, loja.totp_segredo);
  const n = await conferirTotp(s, codigo, agora, loja.totp_ultimo);
  if (n === null) throw new ErroLoja(401, "codigo_invalido", "Código incorreto ou já usado. Espere o próximo código do aplicativo.");
  await env.DB.prepare("UPDATE lojas SET totp_ultimo = ? WHERE id = ?").bind(n, loja.id).run();
}

export async function confirmar2fa(env, loja, codigo, agora = Date.now()) {
  await conferirCodigo(env, loja, codigo, agora);
  await env.DB.prepare("UPDATE lojas SET totp_ativo = 1 WHERE id = ?").bind(loja.id).run();
}

/** Com um pagamento confirmado e o código 2FA certo, o acesso de 30 dias é liberado (soma ao tempo que ainda restava). */
export async function liberarAcesso(env, loja, codigo, agora = Date.now()) {
  const pago = await env.DB.prepare("SELECT * FROM pagamentos WHERE loja = ? AND status = 'pago' ORDER BY criado LIMIT 1").bind(loja.id).first();
  if (!pago) throw new ErroLoja(402, "sem_pagamento", "Nenhum pagamento confirmado para liberar.");
  if (!loja.totp_ativo) throw new ErroLoja(409, "sem_2fa", "Ative a verificação em duas etapas para liberar o acesso.");
  await conferirCodigo(env, loja, codigo, agora);
  const base = Math.max(agora, loja.ativo_ate || 0);
  const ate = base + DIAS_DO_PLANO * DIA;
  await env.DB.batch([
    env.DB.prepare("UPDATE pagamentos SET status = 'liberado', atualizado = ? WHERE id = ? AND status = 'pago'").bind(agora, pago.id),
    env.DB.prepare("UPDATE lojas SET ativo_ate = ?, plano = 'fundadores' WHERE id = ?").bind(ate, loja.id),
  ]);
  return { ativoAte: ate };
}

export async function estadoDaAssinatura(env, loja, agora = Date.now()) {
  const pend = await env.DB.prepare("SELECT COUNT(*) AS n FROM pagamentos WHERE loja = ? AND status = 'pago'").bind(loja.id).first();
  const trialRestante = Math.max(0, Math.ceil(((loja.trial_fim || 0) - agora) / DIA));
  const pagoRestante = Math.max(0, Math.ceil(((loja.ativo_ate || 0) - agora) / DIA));
  return {
    plano: loja.plano, precoCentavos: PRECO_CENTAVOS, diasDeTeste: 7,
    emTeste: agora < loja.trial_fim && agora >= (loja.ativo_ate || 0), diasDeTesteRestantes: trialRestante, diasPagosRestantes: pagoRestante,
    acesso: agora < Math.max(loja.trial_fim || 0, loja.ativo_ate || 0),
    pagamentoAguardandoLiberacao: pend.n > 0, doisFatores: !!loja.totp_ativo,
  };
}
