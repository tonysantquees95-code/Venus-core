import test from "node:test";
import assert from "node:assert/strict";
import { categoriaProvavel, centavosDe, corpoDeAtualizacao, corpoDoNovo, dinheiro, intencaoDaLoja } from "../src/empresa/lojaAdmin.ts";

test("preços em vários formatos viram centavos", () => {
  assert.equal(centavosDe("29,90"), 2990);
  assert.equal(centavosDe("R$ 1.299,00"), 129900);
  assert.equal(centavosDe("29.9"), 2990);
  assert.equal(centavosDe("1.299"), 129900);
  assert.equal(centavosDe(""), null);
  assert.ok(Number.isNaN(centavosDe("abc")));
  assert.equal(dinheiro(129900), "R$ 1.299,00");
});
test("categoria provável pelo nome", () => {
  assert.equal(categoriaProvavel("Gel Excitante Masculino"), "geis_lubrificantes");
  assert.equal(categoriaProvavel("Vibrador Bullet"), "vibradores");
  assert.equal(categoriaProvavel("Kit Casal Surpresa"), "kits_casais");
  assert.equal(categoriaProvavel("Algema de pelúcia"), "acessorios_bdsm");
});
test("corpo do produto novo", () => {
  const c = corpoDoNovo({ nome: " Gel X ", categoria: "geis_lubrificantes", descricao: "  ", precoCentavos: 2990, estoque: -3, imagemUrl: null, ativo: false, custoCentavos: 1500 });
  assert.equal(c.name, "Gel X"); assert.equal(c.description, null); assert.equal(c.stockQuantity, 0); assert.equal(c.costCents, 1500); assert.equal(c.active, false);
});
const bruto = { id: 5, category: "vibradores", name: "V", description: "d", priceCents: 10000, compareAtCents: 12000, specifications: null, stockQuantity: 3, weightGrams: null, widthCm: null, heightCm: null, lengthCm: null, imageUrl: "u", active: true, costCents: 4000 };
test("atualização troca só o que mudou e mantém o custo", () => {
  const { corpo } = corpoDeAtualizacao(bruto, { descricao: "nova" });
  assert.equal(corpo.description, "nova"); assert.equal(corpo.priceCents, 10000); assert.equal(corpo.costCents, 4000); assert.equal(corpo.id, 5);
  const p = corpoDeAtualizacao(bruto, { precoCentavos: 9000 }).corpo;
  assert.equal(p.priceCents, 9000); assert.equal(p.compareAtCents, null);
});
test("sem campo de custo no site avisa; produto incompleto não salva", () => {
  const { costCents: _c, ...semCusto } = bruto;
  assert.equal(corpoDeAtualizacao(semCusto, { custoCentavos: 100 }).semCampoDeCusto, true);
  const { id: _i, ...semId } = bruto;
  assert.equal(corpoDeAtualizacao(semId, { descricao: "x" }).corpo, null);
});
test("frases que abrem a janelinha", () => {
  assert.equal(intencaoDaLoja("cadastrar produto"), "cadastrar");
  assert.equal(intencaoDaLoja("Vênus, quero cadastrar um novo produto"), "cadastrar");
  assert.equal(intencaoDaLoja("novo produto"), "cadastrar");
  assert.equal(intencaoDaLoja("atualizar produto"), "atualizar");
  assert.equal(intencaoDaLoja("quero alterar o estoque"), "atualizar");
  assert.equal(intencaoDaLoja("como eu faço para cadastrar um produto novo no site da loja"), null);
  assert.equal(intencaoDaLoja("qual o valor do volumão"), null);
});
