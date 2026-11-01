import { venusLoja as V } from "./venusLoja.ts";
import type { VLPedido, VLProduto } from "./venusLoja.ts";
import assert from "node:assert/strict";

let falhas = 0, ok = 0;
function t(nome: string, f: () => void) { try { f(); ok++; } catch (e: any) { falhas++; console.log("FALHOU", nome, "\n ", e.message.split("\n").slice(0,6).join("\n  ")); } }
const i = (f: string) => V.interpretar(f) as any;

const bruto = (id: number, name: string, priceCents: number | null, stockQuantity: number | null, active = true, compareAtCents: number | null = null) => ({
  id, category: "geis_lubrificantes", name, description: null, priceCents, compareAtCents, specifications: null, stockQuantity,
  weightGrams: null, widthCm: null, heightCm: null, lengthCm: null, imageUrl: "x", active });
const prod = (id: number, name: string, preco: number | null, est: number | null, active = true, promo: number | null = null): VLProduto =>
  ({ id, name, active, stockQuantity: est, priceCents: preco, compareAtCents: promo, bruto: bruto(id, name, preco, est, active, promo) });
const produtos = [prod(1, "Gel KGEL Morango 60ml", 2990, 3), prod(2, "Vibrador Rosa Bullet", 9990, 0, true, 14990),
  prod(3, "Óleo de Massagem Canela", 3990, null, false), prod(4, "Kit Dois Corações", 5990, 5)];
const agora = new Date(2026, 9, 31, 18, 0);
const h = (n: number) => new Date(agora.getTime() - n * 3600e3);
const pedidos: VLPedido[] = [
  { id: 10, status: "paid", totalCents: 8990, customerName: "Maria Souza", customerPhone: "", deliveryMethod: "sedex", createdAt: h(3), paidAt: h(3), trackingCode: null, items: [{ name: "Gel KGEL", quantity: 2, unitPriceCents: 2990 }] },
  { id: 11, status: "preparing", totalCents: 4990, customerName: "João Lima", customerPhone: "(51) 99876-5432", deliveryMethod: "pickup", createdAt: h(2), paidAt: h(2), trackingCode: null, items: [{ name: "Gel KGEL", quantity: 1, unitPriceCents: 4990 }] },
  { id: 12, status: "awaiting_payment", totalCents: 1990, customerName: "Ana", customerPhone: "", deliveryMethod: "sedex", createdAt: h(1), paidAt: null, trackingCode: null, items: [] },
  { id: 9, status: "delivered", totalCents: 15000, customerName: "Carla", customerPhone: "51999990000", deliveryMethod: "sedex", createdAt: h(30), paidAt: h(30), trackingCode: "AB123BR", items: [] },
];

t("somar", () => { const a = i("Vênus, coloca mais 5 unidades do gel KGEL"); assert.equal(a.tipo, "alterar"); assert.equal(a.mudanca, "estoque_somar"); assert.equal(a.valor, 5); assert.equal(a.produto, "gel kgel"); });
t("somar extenso", () => assert.equal(i("adiciona três unidades no estoque do óleo de canela").valor, 3));
t("chegaram", () => assert.equal(i("chegaram 10 unidades do vibrador rosa").mudanca, "estoque_somar"));
t("tirar", () => { const a = i("tira 2 unidades do estoque do gel KGEL"); assert.equal(a.mudanca, "estoque_tirar"); assert.equal(a.valor, 2); });
t("vendi", () => assert.equal(i("vendi 1 gel kgel na mão").mudanca, "estoque_tirar"));
t("definir", () => { const a = i("muda o estoque do vibrador rosa para 8"); assert.equal(a.mudanca, "estoque_definir"); assert.equal(a.valor, 8); assert.equal(a.produto, "vibrador rosa"); });
t("definir agora é", () => assert.equal(i("o estoque do gel KGEL agora é 12").valor, 12));
t("nome com numero", () => { const a = i("muda o estoque do kit dois corações para 7"); assert.equal(a.produto, "kit dois coracoes"); assert.equal(a.valor, 7); });
t("preco", () => { const a = i("muda o preço do vibrador rosa para 129,90"); assert.equal(a.mudanca, "preco"); assert.equal(a.valor, 12990); });
t("preco reais", () => assert.equal(i("altera o valor do gel kgel para 50 reais").valor, 5000));
t("preco e 90", () => assert.equal(i("o preço do gel kgel agora é 45 e 90").valor, 4590));
t("preco custa", () => assert.equal(i("o óleo de canela agora custa R$ 39,90").valor, 3990));
t("esconder", () => { assert.equal(i("esconde o vibrador rosa do site").mudanca, "desativar"); assert.equal(i("desativa o produto gel kgel").mudanca, "desativar"); });
t("mostrar", () => { assert.equal(i("volta a mostrar o óleo de canela no site").mudanca, "ativar"); assert.equal(i("reativa o produto óleo de canela").mudanca, "ativar"); });
t("retiradas", () => { assert.equal(i("quais retiradas estão para agendar?").tipo, "retiradas"); assert.equal(i("tem alguma retirada").tipo, "retiradas"); });
t("agendar", () => { const a = i("agendar retirada do pedido 14"); assert.equal(a.tipo, "agendar"); assert.equal(a.pedido, 14); assert.equal(i("chama o cliente do pedido 22").tipo, "agendar"); });
t("entregue", () => { assert.equal(i("marca o pedido 14 como entregue").tipo, "entregue"); assert.equal(i("o cliente do pedido 9 já retirou").tipo, "entregue"); });
t("pedido detalhe", () => { assert.equal(i("como está o pedido 11?").tipo, "pedido"); assert.equal(i("pedido 11").id, 11); assert.equal(i("quem comprou o pedido 11").tipo, "pedido"); });
t("geral", () => { for (const f of ["Como está o meu estoque ?", "Quantos produtos tenho em estoque ?", "como tá o estoque", "me dá um resumo do estoque", "quantos itens eu tenho na loja", "o que tem no estoque", "meu estoque", "quantos produtos diferentes eu tenho", "e o estoque, como anda?", "estoque"]) assert.equal(i(f)?.tipo, "estoque_geral", f); });
t("estoque produto", () => { assert.equal(i("quantos géis KGEL eu tenho no estoque").tipo, "estoque"); assert.equal(i("qual o estoque do vibrador rosa").produto, "vibrador rosa"); });
t("vendas", () => { assert.equal(i("como foram as vendas hoje").periodo, "hoje"); assert.equal(i("quanto vendemos ontem").periodo, "ontem"); assert.equal(i("vendas do mês").periodo, "mes"); assert.equal(i("faturamento da semana").periodo, "semana"); });
t("pedidos", () => { assert.equal(i("tem pedido novo?").tipo, "pedidos"); assert.equal(i("o que precisamos separar").tipo, "pedidos"); assert.equal(i("pedidos").tipo, "pedidos"); });
t("acabando", () => { assert.equal(i("o que está acabando").tipo, "acabando"); assert.equal(i("quais produtos estão esgotados").tipo, "acabando"); });
t("outros", () => { assert.equal(i("ajuda").tipo, "ajuda"); assert.equal(i("oi").tipo, "saudacao"); assert.equal(i("obrigada").tipo, "obrigado"); assert.equal(i("novidades").tipo, "novidades"); assert.equal(V.interpretar("qual a capital da França"), null); });

const r = (f: string) => V.responder(V.interpretar(f)!, pedidos, produtos, agora);
t("resp somar", () => { const x = r("coloca mais 5 unidades do gel kgel"); assert.match(x.texto, /Fica com 8/); assert.equal((x.alteracao as any).valor, 8); });
t("resp tirar demais", () => { const x = r("tira 5 unidades do gel kgel"); assert.equal(x.alteracao, undefined); assert.match(x.texto, /só tem 3/); });
t("resp sem controle", () => assert.equal(r("coloca mais 2 unidades do oleo canela").alteracao, undefined));
t("resp definir sem controle", () => assert.equal((r("o estoque do oleo canela é 6").alteracao as any).valor, 6));
t("resp preco promo", () => { const x = r("muda o preço do vibrador rosa para 159,90"); assert.match(x.texto, /promoção/); });
t("resp preco diferente", () => assert.match(r("muda o preço do gel kgel para 9,90").texto, /bem diferente/));
t("resp ambiguo", () => { const dois = [prod(1, "Gel Morango", 1000, 2), prod(2, "Gel Menta", 1000, 2)]; assert.equal(V.responder(V.interpretar("muda o preço do gel para 20")!, pedidos, dois).alteracao, undefined); });
t("resp inexistente", () => assert.equal(r("muda o preço da algema de pelúcia para 20").alteracao, undefined));
t("corpo", () => { const c = V.corpoDoProduto(produtos[0].bruto, "preco", 3490)!; assert.equal(c.priceCents, 3490); assert.equal(c.name, "Gel KGEL Morango 60ml"); assert.equal(c.description, null); assert.equal(V.corpoDoProduto(produtos[0].bruto, "desativar")!.active, false); const inc = { ...produtos[0].bruto }; delete (inc as any).specifications; assert.equal(V.corpoDoProduto(inc, "preco", 1), null); });
t("sim/nao", () => { assert.equal(V.simOuNao("sim"), true); assert.equal(V.simOuNao("Pode confirmar"), true); assert.equal(V.simOuNao("pode cancelar"), false); assert.equal(V.simOuNao("não"), false); assert.equal(V.simOuNao("deixa pra lá"), false); assert.equal(V.simOuNao("como foram as vendas"), null); });
t("resp vendas", () => { const x = r("como foram as vendas hoje"); assert.match(x.texto, /^Hoje: 2 vendas pagas, total de R\$ 139,80\./); assert.match(x.texto, /Mais vendido: Gel KGEL \(3 un\.\)/); assert.match(x.texto, /1 pedido aguardando/); });
t("resp pendentes", () => { const x = r("tem pedido novo"); assert.match(x.texto, /^2 pedidos para separar/); assert.equal(x.acoes![0].url, "/admin/pedidos"); });
t("resp retiradas", () => { const x = r("quais retiradas"); assert.match(x.texto, /^1 retirada para combinar/); assert.match(x.acoes![0].url, /^https:\/\/wa\.me\/5551998765432\?text=/); });
t("resp agendar", () => { assert.equal(r("agendar retirada do pedido 11").acoes!.length, 1); assert.match(r("agendar retirada do pedido 10").texto, /Correios/); });
t("resp entregue", () => { assert.ok(r("marca o pedido 11 como entregue").alteracao); assert.equal(r("marca o pedido 12 como entregue").alteracao, undefined); assert.equal(r("marca o pedido 9 como entregue").alteracao, undefined); });
t("resp pedido", () => { const x = r("pedido 9"); assert.match(x.texto, /Carla: entregue/); assert.match(x.texto, /AB123BR/); assert.match(r("pedido 11").acoes![0].rotulo, /Agendar/); });
t("resp geral", () => { const x = r("como está o estoque"); assert.match(x.texto, /^São 3 produtos no site e 1 escondido\./); assert.match(x.texto, /8 unidades/); assert.match(x.texto, /1 esgotado: Vibrador Rosa Bullet/); });
t("resp estoque", () => assert.equal(r("quanto tem de estoque do gel kgel").texto, "Gel KGEL Morango 60ml: 3 unidades."));
t("resp acabando", () => assert.equal(r("o que está acabando").texto, "Esgotados: Vibrador Rosa Bullet."));
t("novidades", () => { const x = V.novidades(pedidos, produtos, agora); assert.match(x.texto, /Para fazer: 2 pedidos para separar, 1 retirada para agendar, 1 produto esgotado\./); });
t("confirmado", () => assert.equal(V.confirmado({ tipo: "preco", produto: produtos[0], valor: 3490 }), "Pronto: Gel KGEL Morango 60ml agora custa R$ 34,90 no site."));
t("status voz", () => { const a = i("pedido 15 enviado, rastreio AB123456789BR"); assert.equal(a.tipo, "status"); assert.equal(a.status, "shipped"); assert.equal(a.rastreio, "ab123456789br"); assert.equal(i("o pedido 15 foi enviado").rastreio, null); assert.equal(i("estou separando o pedido 15").status, "preparing"); assert.equal(i("marca o pedido 12 como pago").status, "paid"); assert.equal(i("o rastreio do pedido 15 é AB123456789BR").status, "shipped"); assert.equal(i("pedido quinze enviado").pedido, 15); });
t("status resp", () => { const x = r("pedido 11 enviado, rastreio ab 123 456 789 br"); assert.equal((x.alteracao as any).rastreio, "AB123456789BR"); assert.match(x.texto, /WhatsApp/); assert.equal(r("pedido 12 enviado").alteracao, undefined); assert.ok(r("marca o pedido 12 como pago").alteracao); const link = V.linkAviso(x.alteracao!)!; assert.match(link, /^https:\/\/wa\.me\/5551998765432\?text=/); assert.match(decodeURIComponent(link), /AB123456789BR/); });
t("promo voz", () => { const a = i("coloca o gel kgel em promoção por 24,90"); assert.equal(a.mudanca, "promocao"); assert.equal(a.valor, 2490); assert.equal(i("coloca o gel kgel em promoção de 29,90 por 24,90").valor, 2490); assert.equal(i("dá 10% de desconto no gel kgel").mudanca, "promocao_pct"); assert.equal(i("dá 15 por cento de desconto no vibrador").valor, 15); assert.equal(i("tira o vibrador rosa da promoção").mudanca, "fim_promocao"); assert.equal(i("encerra a promoção do vibrador rosa").produto, "vibrador rosa"); assert.equal(i("muda o preço do gel kgel para 30").mudanca, "preco"); });
t("promo resp", () => { const x = r("coloca o gel kgel em promoção por 24,90"); assert.match(x.texto, /de R\$ 29,90 por R\$ 24,90 \(17% a menos\)/); const c = V.corpoDoProduto(produtos[0].bruto, "promocao", 2490)!; assert.equal(c.priceCents, 2490); assert.equal(c.compareAtCents, 2990); assert.equal((r("dá 10% de desconto no gel kgel").alteracao as any).valor, 2691); assert.match(r("tira o vibrador rosa da promoção").texto, /volta a custar R\$ 149,90/); const f = V.corpoDoProduto(produtos[1].bruto, "fim_promocao")!; assert.equal(f.priceCents, 14990); assert.equal(f.compareAtCents, null); assert.equal(r("tira o gel kgel da promoção").alteracao, undefined); });
t("relatorios voz", () => { assert.equal(i("o que preciso repor").tipo, "reposicao"); assert.equal(i("Quais produtos eu preciso repor").tipo, "reposicao"); assert.equal(i("o que foi vendido hoje").periodo, "hoje"); assert.equal(i("quais os produtos mais vendidos").periodo, "mes"); assert.equal(i("o que mais vendeu essa semana").periodo, "semana"); assert.equal(i("compara as vendas com o mês passado").tipo, "comparar"); assert.equal(i("quais produtos estão parados").tipo, "parados"); assert.equal(i("o que está acabando").tipo, "acabando"); assert.equal(i("como foram as vendas hoje").tipo, "vendas"); });
t("relatorios resp", () => { const x = r("o que preciso repor"); assert.match(x.texto, /^Vendidos nos últimos 7 dias: 3 itens em 3 pedidos\./); assert.match(x.texto, /• Gel KGEL: 3/); assert.match(r("mais vendidos de hoje").texto, /^Mais vendidos hoje:\n1\. Gel KGEL: 3 unidades/); });
console.log(`${ok} ok, ${falhas} falharam`);
