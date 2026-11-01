package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MargemTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    private fun prod(id: Int, nome: String, preco: Int, custo: Int?, comCampo: Boolean = true): Loja.Produto {
        val b = JSONObject().put("id", id).put("category", "geis_lubrificantes").put("name", nome).put("description", JSONObject.NULL)
            .put("priceCents", preco).put("compareAtCents", JSONObject.NULL).put("specifications", JSONObject.NULL).put("stockQuantity", 5)
            .put("weightGrams", JSONObject.NULL).put("widthCm", JSONObject.NULL).put("heightCm", JSONObject.NULL).put("lengthCm", JSONObject.NULL)
            .put("imageUrl", JSONObject.NULL).put("active", true)
        if (comCampo) b.put(Loja.CAMPO_CUSTO, custo ?: JSONObject.NULL)
        return Loja.Produto(id, nome, true, 5, preco, null, b)
    }

    private val produtos = listOf(prod(1, "Conjunto Renda Noir", 8990, 4500), prod(2, "Gel KGEL Morango", 2990, 260), prod(3, "Vibrador Rosa", 9990, null))

    @Test fun comandos() {
        val c = acao("o custo do Conjunto Renda Noir é 45 reais")!!
        assertEquals("custo", c.args["tipo"])
        assertEquals(4500, c.args["valor"])
        assertEquals("conjunto renda noir", c.args["produto"])
        assertEquals(260, acao("paguei 2,60 no gel kgel")!!.args["valor"])
        assertEquals("gel kgel", acao("paguei 2,60 no gel kgel")!!.args["produto"])
        assertEquals("custo", acao("o vibrador rosa me custou 40 reais")!!.args["tipo"])
        assertEquals("preco", acao("muda o preço do gel kgel para 30")!!.args["tipo"])
        assertEquals("loja_margem", acao("qual a margem do conjunto renda noir")!!.nome)
        assertEquals("conjunto", acao("lucro neste conjunto que eu vendi")!!.args["produto"])
        assertEquals("loja_margens", acao("Qual a minha margem de lucro média nos produtos")!!.nome)
        assertEquals("loja_margens", acao("quais produtos dão menos lucro")!!.nome)
        assertEquals("loja_lucro", acao("qual foi meu lucro este mês")!!.nome)
        assertEquals("semana", acao("quanto eu lucrei essa semana")!!.args["periodo"])
        assertEquals("loja_lucro", acao("lucro de hoje")!!.nome)
        assertEquals("editar_fundo", acao("troca o fundo da foto")!!.nome)
        assertEquals("ler_nota", acao("lança a nota de compra")!!.nome)
    }

    @Test fun margem() {
        val m = Loja.montarMargem(produtos, "conjunto renda noir")
        assertTrue(m, m.contains("vende por R$ 89,90, custou R$ 45,00. Lucro de R$ 44,90 por unidade, 50% do preço"))
        assertTrue(m, m.contains("No Pix"))
        assertTrue(Loja.montarMargem(produtos, "vibrador rosa").contains("ainda não tem custo"))
        assertEquals(Loja.SEM_CAMPO_CUSTO, Loja.montarMargem(listOf(prod(1, "Gel X", 1000, null, comCampo = false)), "gel x"))
        val ms = Loja.montarMargens(produtos)
        assertTrue(ms, ms.startsWith("Margem média de 70% do preço, em 2 produtos"))
        assertTrue(ms, ms.contains("Faltam 1 produto sem custo: Vibrador Rosa"))
    }

    @Test fun lucro() {
        val agora = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 31, 22, 0) }
        val t = agora.timeInMillis
        val pedidos = listOf(
            Loja.Pedido(1, "paid", 8540, "A", "sedex", t, t, listOf(Loja.Item("Conjunto Renda Noir", 1, 8990, 1)), descontoCentavos = 450, freteCentavos = 2240),
            Loja.Pedido(2, "paid", 9990, "B", "sedex", t, t, listOf(Loja.Item("Vibrador Rosa", 1, 9990, 3))),
        )
        val l = Loja.montarLucro(pedidos, produtos, "mes", agora)
        assertTrue(l, l.startsWith("Lucro neste mês: R$ 40,40 em 2 pedidos (vendido R$ 85,40, custo R$ 45,00"))
        assertTrue(l, l.contains("Frete cobrado dos clientes: R$ 22,40"))
        assertTrue(l, l.contains("1 unidade sem custo cadastrado: Vibrador Rosa"))
    }

    @Test fun custoConfirma() {
        val p = Loja.montarAlteracao(produtos, "custo", "vibrador rosa", 4000)
        assertEquals(4000, p.alteracao!!.valor)
        assertTrue(p.texto, p.texto.contains("lucro fica R$ 59,90 por unidade"))
        assertEquals(4000, Loja.corpoDoProduto(produtos[2].bruto, "custo", 4000)!!.getInt(Loja.CAMPO_CUSTO))
        // mudar o preço não apaga o custo
        assertEquals(4500, Loja.corpoDoProduto(produtos[0].bruto, "preco", 9990)!!.getInt(Loja.CAMPO_CUSTO))
        assertNull(Loja.montarAlteracao(listOf(prod(1, "Gel X", 1000, null, comCampo = false)), "custo", "gel x", 100).alteracao)
    }

    @Test fun nota() {
        val itens = Gemini.lerItensDaNota("""```json
            {"itens":[{"descricao":"GEL KGEL MORANGO 60ML","quantidade":5,"valor_total":13.00},
            {"descricao":"CONJ RENDA NOIR P","quantidade":2,"valor_unitario":45.5},{"descricao":"","quantidade":1,"valor_total":3}]}
            ```""")!!
        assertEquals(2, itens.size)
        assertEquals(Triple("GEL KGEL MORANGO 60ML", 5, 260), itens[0])
        assertEquals(4550, itens[1].third)
        val casados = Loja.casarCompra(produtos, itens)
        assertEquals(2, casados[0].produto!!.id)
        assertNull(Gemini.lerItensDaNota("não consegui ler"))
    }
}
