import test from "node:test";
import assert from "node:assert/strict";
import { acharProdutos, contextoDeCatalogo, lerCatalogo, respostaDeProduto } from "../src/empresa/catalogo.ts";

const corpo = JSON.stringify({ json: { whatsappNumber: "1", reviews: [], products: [
  { id: 1, name: "Vibrador Volumão Premium", category: "Vibradores", description: "Grande e silencioso", priceCents: 18990, compareAtCents: 24990, soldOut: false },
  { id: 2, name: "Gel Beijável Morango", category: "Géis", description: "", priceCents: 2990, compareAtCents: null, soldOut: true },
  { id: 3, name: "Kit Casal Surpresa", category: "Kits", description: "", priceCents: 9900, compareAtCents: null, soldOut: false }
] }, meta: {} });
const lista = lerCatalogo(corpo);

test("lê o catálogo embrulhado em superjson e também puro", () => {
  assert.equal(lista.length, 3);
  assert.equal(lerCatalogo(JSON.stringify({ products: [{ id: 9, name: "X", priceCents: 100 }] })).length, 1);
  assert.deepEqual(lerCatalogo("lixo"), []);
});
test("acha o produto pela pergunta, com acento e plural", () => {
  assert.equal(acharProdutos(lista, "qual o valor do volumão")[0].id, 1);
  assert.equal(acharProdutos(lista, "quanto custa o gel de morango")[0].id, 2);
  assert.deepEqual(acharProdutos(lista, "qual o valor do caminhão"), []);
});
test("responde preço direto, com promoção e esgotado", () => {
  assert.equal(respostaDeProduto(lista, "qual o valor do volumão"), "Vibrador Volumão Premium: R$ 189,90 (em promoção, de R$ 249,90).");
  assert.match(respostaDeProduto(lista, "preço do gel morango"), /esgotado/);
  assert.equal(respostaDeProduto(lista, "oi tudo bem"), null);
  assert.equal(respostaDeProduto(lista, "qual o preço do caminhão"), null);
});
test("contexto para a IA traz o citado e respeita o limite", () => {
  const c = contextoDeCatalogo(lista, "me fala do volumão", 400);
  assert.match(c, /Volumão Premium: R\$ 189,90/);
  assert.ok(c.length <= 400);
  assert.equal(contextoDeCatalogo([], "x"), "");
});
