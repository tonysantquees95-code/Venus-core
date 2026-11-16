import test from "node:test";
import assert from "node:assert/strict";
import { identidadeParaEmpresa } from "../src/empresa/identidadeDoSite.ts";

const PNG = "data:image/png;base64,iVBORw0KGgo=";
const api = (r) => ({ identidadeDoSite: async () => r });
const vazia = { dono: "", loja: "", cidade: "", uf: "", sobre: "", site: "https://x.com.br", ramo: "", logo: "", cor: "", corApoio: "", modo: "", regras: null, regrasConfiguradas: false };

test("pega cor, logo, nome e descrição do site quando a loja ainda não tem", async () => {
  const m = await identidadeParaEmpresa(api({ site: "https://x.com.br", nome: "Loja X", descricao: "Tudo de X", cor: "#112233", logo: PNG }), "t", vazia);
  assert.deepEqual(m, { cor: "#112233", logo: PNG, loja: "Loja X", sobre: "Tudo de X" });
});

test("não apaga o que a pessoa já escolheu, a não ser que peça para refazer", async () => {
  const feita = { ...vazia, cor: "#aa0000", logo: PNG, loja: "Minha", sobre: "Meu texto" };
  const r = { site: "", nome: "Outra", descricao: "d", cor: "#112233", logo: "data:image/png;base64,AAAA" };
  assert.deepEqual(await identidadeParaEmpresa(api(r), "t", feita), {});
  const forcado = await identidadeParaEmpresa(api(r), "t", feita, true);
  assert.equal(forcado.cor, "#112233");
  assert.equal(forcado.logo, "data:image/png;base64,AAAA");
  assert.equal(forcado.loja, undefined);
});

test("logo fora do formato aceito é ignorado; sem site dá erro", async () => {
  const m = await identidadeParaEmpresa(api({ site: "", nome: "", descricao: "", cor: "", logo: "data:image/svg+xml;base64,AAA" }), "t", vazia);
  assert.deepEqual(m, {});
  await assert.rejects(() => identidadeParaEmpresa(api({}), "t", { ...vazia, site: "" }), /site/i);
});
