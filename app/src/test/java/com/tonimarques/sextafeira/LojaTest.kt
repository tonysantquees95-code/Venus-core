package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Primeira leva da loja: vendas, pedidos, estoque e o que está acabando. */
class LojaTest {

    private val agora: Calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 31, 18, 0) }
    private fun horasAtras(h: Int) = agora.timeInMillis - h * 3600_000L

    private val pedidos = listOf(
        Loja.Pedido(10, "paid", 8990, "Maria Souza", "sedex", horasAtras(3), horasAtras(3), listOf(Loja.Item("Gel KGEL", 2, 2990), Loja.Item("Óleo", 1, 3010))),
        Loja.Pedido(11, "preparing", 4990, "João Lima", "pickup", horasAtras(2), horasAtras(2), listOf(Loja.Item("Gel KGEL", 1, 4990))),
        Loja.Pedido(12, "awaiting_payment", 1990, "Ana", "sedex", horasAtras(1), null, listOf(Loja.Item("Retarden", 1, 1990))),
        Loja.Pedido(9, "delivered", 15000, "Carla", "sedex", horasAtras(30), horasAtras(30), listOf(Loja.Item("Vibrador", 1, 15000))),
        Loja.Pedido(8, "canceled", 5000, "Bia", "sedex", horasAtras(4), null, emptyList()),
    )

    @Test fun leOFormatoDoSite() {
        val texto = """{"json":{"orders":[{"id":10,"status":"paid","totalCents":8990,"customerName":"Maria Souza","deliveryMethod":"sedex",
            "createdAt":"2026-10-31T20:00:00.000Z","paidAt":"2026-10-31T20:05:00.000Z","items":[{"name":"Gel KGEL","quantity":2,"unitPriceCents":2990}]}]},
            "meta":{"values":{"orders.0.createdAt":["Date"]}}}"""
        val lista = Loja.lerPedidos(Loja.dados(texto).getJSONArray("orders"))
        assertEquals(1, lista.size)
        assertEquals(10, lista[0].id)
        assertEquals(2, lista[0].itens[0].quantidade)
        assertTrue(lista[0].pago!! > lista[0].criado)
        val produtos = Loja.lerProdutos(JSONArray("""[{"id":1,"name":"Gel KGEL 60ml","active":true,"stockQuantity":5,"priceCents":2990},
            {"id":2,"name":"Vibrador Rosa","active":true,"stockQuantity":null,"priceCents":null}]"""))
        assertEquals(5, produtos[0].estoque)
        assertEquals(null, produtos[1].estoque)
    }

    @Test fun resumoDoDia() {
        val r = Loja.montarResumo(pedidos, "hoje", agora)
        assertTrue(r, r.startsWith("Hoje: 2 vendas pagas, total de R$ 139,80."))
        assertTrue(r, r.contains("Mais vendido: Gel KGEL (3 un.)"))
        assertTrue(r, r.contains("1 pedido aguardando pagamento"))
        val ontem = Loja.montarResumo(pedidos, "ontem", agora)
        assertTrue(ontem, ontem.startsWith("Ontem: 1 venda paga, total de R$ 150,00."))
        assertTrue(Loja.montarResumo(emptyList(), "hoje", agora).startsWith("Hoje ainda não teve venda paga"))
    }

    @Test fun pedidosParaSeparar() {
        val r = Loja.montarPendentes(pedidos)
        assertTrue(r, r.startsWith("Você tem 2 pedidos para separar."))
        assertTrue(r, r.contains("Pedido 10 de Maria: 3 itens, R$ 89,90, pago, envio."))
        assertTrue(r, r.contains("Pedido 11 de João: 1 item, R$ 49,90, em separação, retirada."))
    }

    @Test fun estoque() {
        val produtos = listOf(
            Loja.Produto(1, "Gel Lubrificante KGEL 60ml", true, 5, 2990),
            Loja.Produto(2, "Vibrador Rosa Recarregável", true, 0, 15000),
            Loja.Produto(3, "Plug Coração", true, 2, 5990),
            Loja.Produto(4, "Fantasia Enfermeira", false, 1, 8990),
            Loja.Produto(5, "Óleo de Massagem", true, null, 3990),
        )
        assertEquals("Gel Lubrificante KGEL 60ml: 5 unidades.", Loja.montarEstoque(produtos, "kgel"))
        assertEquals("Vibrador Rosa Recarregável: esgotado.", Loja.montarEstoque(produtos, "vibrador rosa"))
        assertEquals("Óleo de Massagem: sem controle de estoque.", Loja.montarEstoque(produtos, "óleo de massagem"))
        assertTrue(Loja.montarEstoque(produtos, "algema").startsWith("Não achei"))
        val a = Loja.montarAcabando(produtos)
        assertEquals("Esgotados: Vibrador Rosa Recarregável. Acabando: Plug Coração (2).", a)
    }

    private fun acao(frase: String): Acao? = ComandosLocais.interpretar(frase) as? Acao

    @Test fun frasesDaLoja() {
        assertEquals("loja_resumo", acao("como foram as vendas hoje")!!.nome)
        assertEquals("ontem", acao("quanto eu vendi ontem")!!.args["periodo"])
        assertEquals("mes", acao("Vênus, quanto a loja faturou esse mês")!!.args["periodo"])
        assertEquals("loja_pedidos", acao("tem pedido novo?")!!.nome)
        assertEquals("loja_pedidos", acao("quais pedidos eu tenho que enviar")!!.nome)
        assertEquals("loja_acabando", acao("quais produtos estão acabando")!!.nome)
        assertEquals("loja_acabando", acao("o que está esgotado na loja")!!.nome)
        val e = acao("quanto tem de estoque do gel KGEL")!!
        assertEquals("loja_estoque", e.nome)
        assertEquals("gel KGEL", e.args["produto"])
        assertEquals("plug coração", acao("quantos plug coração eu tenho no estoque")!!.args["produto"])
    }
}
