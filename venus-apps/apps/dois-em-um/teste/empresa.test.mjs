import test from "node:test";
import assert from "node:assert/strict";
import { contextoDaEmpresa, EMPRESA_VAZIA, empresaPreenchida, limparEmpresa, normalizarSite, regrasEmLinhas } from "../src/empresa/logica.ts";

const DO_CELULAR = {
  dono: "Toni", loja: "Desejo de Intimidade", cidade: "Porto Alegre", uf: "rs", sobre: "Loja adulta discreta.", site: "https://desejo.com.br", ramo: "sex shop",
  regras: {
    versao: 1,
    pagamento: { pix_desconto_pct: 5, pix_em_promocao: false, cartao_parcelas_max: 6, parcelas_sem_juros: 3 },
    frete: { metodos: ["Correios"], calculo: "cep", gratis_acima_de_centavos: 25000, prazo_postagem_dias: 2 },
    envio: { embalagem_discreta: true, abrangencia: "Brasil" },
    trocas: { arrependimento_dias: 7, garantia: "30 dias" },
    atendimento: { canais: ["WhatsApp"], horario: "9h às 18h" }
  },
  regrasConfiguradas: true
};

test("cadastro inválido vira vazio e o válido é limpo", () => {
  assert.deepEqual(limparEmpresa(null), EMPRESA_VAZIA);
  assert.deepEqual(limparEmpresa([1, 2]), EMPRESA_VAZIA);
  const e = limparEmpresa(DO_CELULAR);
  assert.equal(e.uf, "RS");
  assert.equal(e.loja, "Desejo de Intimidade");
  assert.deepEqual(e.regras, DO_CELULAR.regras); // as regras do celular passam inteiras
  assert.equal(limparEmpresa({ loja: 42 }).loja, "");
});

test("só se envia para a conta depois de preencher algo", () => {
  assert.equal(empresaPreenchida(EMPRESA_VAZIA), false);
  assert.equal(empresaPreenchida(limparEmpresa({ loja: "X" })), true);
  assert.equal(empresaPreenchida(limparEmpresa(DO_CELULAR)), true);
});

test("site é normalizado como no celular", () => {
  assert.equal(normalizarSite("minhaloja.com.br/"), "https://minhaloja.com.br");
  assert.equal(normalizarSite("http://a.com"), "http://a.com");
  assert.equal(normalizarSite("  "), "");
});

test("regras viram linhas legíveis", () => {
  const l = regrasEmLinhas(DO_CELULAR.regras);
  assert.ok(l.includes("Pix com 5% de desconto"));
  assert.ok(l.includes("Cartão em até 6x (3x sem juros)"));
  assert.ok(l.includes("Frete grátis acima de R$ 250,00"));
  assert.ok(l.includes("Embalagem discreta"));
  assert.deepEqual(regrasEmLinhas(null), []);
});

test("contexto da conversa: vazio sem cadastro, com regras ou com aviso de que faltam", () => {
  assert.equal(contextoDaEmpresa(EMPRESA_VAZIA), "");
  const c = contextoDaEmpresa(limparEmpresa(DO_CELULAR));
  assert.match(c, /Desejo de Intimidade, Porto Alegre\/RS/);
  assert.match(c, /Pix com 5% de desconto/);
  const sem = contextoDaEmpresa(limparEmpresa({ loja: "Nova", dono: "Ana" }));
  assert.match(sem, /não invente/);
  assert.ok(c.length <= 3900);
});

test("identidade da loja: logo, cores e modo de venda são validados", async () => {
  const { limparEmpresa, iniciaisDaLoja, paletasDoNicho, textoSobre, lojaJaConfigurada, EMPRESA_VAZIA } = await import("../src/empresa/logica.ts");
  const e = limparEmpresa({ loja: "Desejo de Intimidade", cor: "#7C3AED", corApoio: "azul", modo: "interno", logo: "data:image/webp;base64,AAAA" });
  assert.equal(e.cor, "#7c3aed");
  assert.equal(e.corApoio, "");
  assert.equal(e.modo, "interno");
  assert.equal(e.logo, "data:image/webp;base64,AAAA");
  assert.equal(limparEmpresa({ logo: "http://x.com/a.png" }).logo, "");
  assert.equal(limparEmpresa({ logo: "data:text/html;base64,AAAA" }).logo, "");
  assert.equal(limparEmpresa({ modo: "qualquer" }).modo, "");
  assert.equal(iniciaisDaLoja("Desejo de Intimidade"), "DD");
  assert.equal(iniciaisDaLoja(""), "V");
  assert.equal(paletasDoNicho("Moda feminina")[0][0], "#d946ef");
  assert.ok(paletasDoNicho("coisa que ninguém conhece").length >= 3);
  assert.equal(textoSobre("#ffffff"), "#111111");
  assert.equal(textoSobre("#111827"), "#ffffff");
  // só passa pelo passo a passo quem ainda não tem loja, não escolheu o modo e não pulou
  assert.equal(lojaJaConfigurada(EMPRESA_VAZIA, false), false);
  assert.equal(lojaJaConfigurada(EMPRESA_VAZIA, true), true);
  assert.equal(lojaJaConfigurada({ ...EMPRESA_VAZIA, loja: "Minha" }, false), true);
  assert.equal(lojaJaConfigurada({ ...EMPRESA_VAZIA, modo: "site" }, false), true);
});
