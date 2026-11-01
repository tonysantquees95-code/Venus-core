package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GestaoTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    private fun prod(id: Int, nome: String, preco: Int, estoque: Int?, custo: Int? = null): Loja.Produto {
        val b = JSONObject().put("id", id).put("category", "geis_lubrificantes").put("name", nome).put("description", JSONObject.NULL)
            .put("priceCents", preco).put("compareAtCents", JSONObject.NULL).put("specifications", JSONObject.NULL).put("stockQuantity", estoque ?: JSONObject.NULL)
            .put("weightGrams", JSONObject.NULL).put("widthCm", JSONObject.NULL).put("heightCm", JSONObject.NULL).put("lengthCm", JSONObject.NULL)
            .put("imageUrl", JSONObject.NULL).put("active", true).put(Loja.CAMPO_CUSTO, custo ?: JSONObject.NULL)
        return Loja.Produto(id, nome, true, estoque, preco, null, b)
    }

    private val agora = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 1, 10, 0) }
    private val t = agora.timeInMillis
    private val dia = 86_400_000L

    private fun pedido(id: Int, diasAtras: Int, itens: List<Loja.Item>, cliente: String = "Ana Souza", tel: String = "51999990000") =
        Loja.Pedido(id, "paid", itens.sumOf { it.precoCentavos * it.quantidade }, cliente, "pac", t - diasAtras * dia, t - diasAtras * dia, itens, telefone = tel)

    @Test fun comandos() {
        assertEquals("loja_curva_abc", acao("me mostra a curva ABC")!!.nome)
        assertEquals("loja_curva_abc", acao("quais produtos trazem mais dinheiro")!!.nome)
        assertEquals("loja_compras", acao("o que eu preciso comprar do fornecedor")!!.nome)
        assertEquals("loja_compras", acao("sugestão de compra")!!.nome)
        val p = acao("o fornecedor entrega em 10 dias")!!
        assertEquals("gestao_prazo", p.nome)
        assertEquals(10, p.args["dias"])
        assertEquals("loja_recompra", acao("quais clientes posso chamar de volta")!!.nome)
        assertEquals("loja_recompra", acao("clientes sumidos")!!.nome)
        val c = acao("chama de volta a Ana")!!
        assertEquals("loja_chamar_cliente", c.nome)
        assertEquals("Ana", c.args["nome"])
        assertEquals("loja_datas", acao("quais as próximas datas comerciais")!!.nome)
        assertEquals(365, acao("quanto falta para a black friday")!!.args["dias"])
        val promo = acao("coloca em promoção os produtos parados há 60 dias com 20% de desconto")!!
        assertEquals("loja_promo_parados", promo.nome)
        assertEquals(20, promo.args["pct"])
        assertEquals(60, promo.args["dias"])
        assertEquals(15, acao("faz uma promoção nos produtos parados")!!.args["pct"])
        // o que já existia continua igual
        assertEquals("loja_parados", acao("quais produtos estão parados")?.nome)
        assertEquals("loja_reposicao", acao("quais produtos eu preciso repor")?.nome)
    }

    @Test fun curvaABC() {
        val pedidos = listOf(
            pedido(1, 5, listOf(Loja.Item("Vibrador", 8, 10000, 1))),
            pedido(2, 10, listOf(Loja.Item("Gel", 10, 1500, 2), Loja.Item("Vela", 2, 1000, 3))),
            pedido(3, 200, listOf(Loja.Item("Antigo", 50, 10000, 4))),
        )
        val abc = Gestao.curvaABC(pedidos, agora)
        assertEquals(listOf("Vibrador", "Gel", "Vela"), abc.map { it.nome })
        assertEquals(listOf('A', 'B', 'C'), abc.map { it.classe })
        val texto = Gestao.textoABC(abc)
        assertTrue(texto, texto.contains("A, os que sustentam a loja (82% do faturamento): Vibrador"))
        assertTrue(Gestao.textoABC(emptyList()).startsWith("Ainda não tem vendas"))
    }

    @Test fun sugestaoDeCompra() {
        val produtos = listOf(prod(1, "Gel", 3000, 4, 1000), prod(2, "Vela", 2000, 50, 500), prod(3, "Óleo", 4000, 0, 1500))
        // Gel: 45 vendidos em 45 dias = 1 por dia; Vela: 9 em 45 dias; Óleo: 9 em 45 dias e esgotado
        val pedidos = (1..45).map { pedido(it, it, listOf(Loja.Item("Gel", 1, 3000, 1))) } +
            (1..9).map { pedido(100 + it, it * 5, listOf(Loja.Item("Vela", 1, 2000, 2), Loja.Item("Óleo", 1, 4000, 3))) }
        val lista = Gestao.sugestaoDeCompra(pedidos, produtos, agora, prazoDias = 7)
        assertEquals(listOf("Óleo", "Gel"), lista.map { it.produto.nome })
        val gel = lista.first { it.produto.nome == "Gel" }
        assertEquals(11, gel.pontoDePedido) // 7 de venda no prazo + 4 de segurança
        assertEquals(37 + 4 - 4, gel.comprar)
        val texto = Gestao.textoCompra(lista, 7)
        assertTrue(texto, texto.contains("• Óleo: comprar"))
        assertTrue(texto, texto.contains("Investimento estimado"))
        assertTrue(Gestao.textoCompra(emptyList(), 7).startsWith("Nenhum produto chegou no ponto de pedido"))
    }

    @Test fun recompra() {
        val pedidos = listOf(
            pedido(1, 60, listOf(Loja.Item("Gel KGEL", 1, 3000, 1)), "ana souza", "(51) 99999-0000"),
            pedido(2, 90, listOf(Loja.Item("Vela", 1, 2000, 2)), "ana souza", "51999990000"),
            pedido(3, 5, listOf(Loja.Item("Gel", 1, 3000, 1)), "Bia Lima", "51988887777"),
            pedido(4, 400, listOf(Loja.Item("Gel", 1, 3000, 1)), "Carla", "51977776666"),
        )
        val lista = Gestao.clientesParaRecompra(pedidos, t)
        assertEquals(1, lista.size)
        val ana = lista[0]
        assertEquals(2, ana.compras)
        assertEquals("Gel KGEL", ana.produtos.first())
        val msg = Gestao.mensagemRecompra(ana)
        assertTrue(msg, msg.startsWith("Oi, Ana!"))
        assertTrue(Gestao.linkWhatsapp(ana).startsWith("https://wa.me/5551999990000?text="))
        assertEquals(ana, Gestao.acharCliente(lista, "a Ana"))
        assertNull(Gestao.acharCliente(lista, "Bia"))
        assertTrue(Gestao.textoRecompra(lista, t).contains("última compra há 60 dias, 2 compras"))
    }

    @Test fun datas() {
        assertEquals(27, Gestao.blackFriday(2026).get(Calendar.DAY_OF_MONTH))
        assertEquals(28, Gestao.blackFriday(2025).get(Calendar.DAY_OF_MONTH))
        val p = Gestao.pascoa(2027)
        assertEquals(Calendar.MARCH, p.get(Calendar.MONTH))
        assertEquals(28, p.get(Calendar.DAY_OF_MONTH))
        val proximas = Gestao.proximasDatas(agora, 60)
        assertEquals(listOf("Black Friday", "Natal", "Réveillon"), proximas.map { it.first.nome })
        assertEquals(26, proximas[0].second)
        val texto = Gestao.textoDatas(proximas)
        assertTrue(texto, texto.contains("Black Friday: faltam 26 dias (27/11)"))
        val antes = (agora.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 13) }
        assertTrue(Gestao.avisoDeData(antes)!!.startsWith("Faltam 14 dias para Black Friday"))
        assertNull(Gestao.avisoDeData(agora))
    }

    @Test fun promocaoNosParados() {
        val produtos = listOf(prod(1, "Gel", 3000, 5, 1000), prod(2, "Vela", 2000, 3, 1900), prod(3, "Óleo", 4000, 2, 1500), prod(4, "Esgotado", 1000, 0))
        val pedidos = listOf(pedido(1, 3, listOf(Loja.Item("Gel", 1, 3000, 1))))
        val preparo = Gestao.promocaoNosParados(pedidos, produtos, agora, 30, 20)
        val alt = preparo.alteracao!!
        assertEquals("lote", alt.tipo)
        assertEquals(1, alt.lote.size)
        assertEquals("Óleo", alt.lote[0].produto!!.nome)
        assertEquals(3200, alt.lote[0].valor)
        assertTrue(preparo.texto, preparo.texto.contains("Vela (ficaria abaixo do custo)"))
    }

    @Test fun interromper() {
        val validas = Interruptor.PALAVRAS.toSet()
        assertEquals("chega", Interruptor.acharPalavra("chega", validas))
        assertEquals("venus", Interruptor.acharPalavra("[unk] vênus", validas))
        assertNull(Interruptor.acharPalavra("[unk] [unk]", validas))
        assertNull(Interruptor.acharPalavra("", validas))
        assertTrue("espera" in Interruptor.palavrasDa("Espera, vou ver."))
    }
}
