package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaixaDoVyntemTest {
    private fun pedido(id: Int, status: String, total: Int = 18990, pago: Long? = 2_000L, criado: Long = 1_000L, cliente: String = "Maria Souza") =
        Loja.Pedido(id, status, total, cliente, "shipping", criado, pago, emptyList())

    @Test fun soVendaPagaEntra_aguardandoPagamentoNao() {
        val lote = CaixaDoVyntem.montarLote(
            listOf(pedido(1, "paid"), pedido(2, "awaiting_payment", pago = null), pedido(3, "delivered")),
            emptySet(), emptySet(), 0L, "principal", 9_000L,
        )
        assertEquals(listOf(1, 3), lote.itens.map { it.pedido.toInt() })
        assertEquals(setOf(1, 3), lote.enviados)
        assertTrue(lote.itens.all { it.tipo == "entrada" })
    }

    @Test fun levaPedidoENomeDoCliente() {
        val i = CaixaDoVyntem.montarLote(listOf(pedido(1042, "paid", 18990, cliente = " Maria Souza ")), emptySet(), emptySet(), 0L, "principal", 9_000L).itens.single()
        assertEquals("venus:principal-1042", i.ref)
        assertEquals("1042", i.pedido)
        assertEquals("Maria Souza", i.cliente)
        assertEquals(18990, i.valorCentavos)
        assertEquals(2_000L, i.quando)
    }

    @Test fun naoReenviaOQueJaFoi() {
        val lote = CaixaDoVyntem.montarLote(listOf(pedido(1, "paid"), pedido(2, "paid")), setOf(1), emptySet(), 0L, "principal", 9_000L)
        assertEquals(listOf(2), lote.itens.map { it.pedido.toInt() })
    }

    @Test fun naoImportaVendaAnteriorAoMomentoQueLigou() {
        val lote = CaixaDoVyntem.montarLote(listOf(pedido(1, "paid", pago = 500L, criado = 400L), pedido(2, "paid", pago = 5_000L)), emptySet(), emptySet(), 1_000L, "principal", 9_000L)
        assertEquals(listOf(2), lote.itens.map { it.pedido.toInt() })
    }

    @Test fun cancelamentoDepoisDeEnviadoViraEstornoUmaVezSo() {
        val a = CaixaDoVyntem.montarLote(listOf(pedido(1, "canceled")), setOf(1), emptySet(), 0L, "principal", 9_000L)
        assertEquals("estorno", a.itens.single().tipo)
        assertEquals(9_000L, a.itens.single().quando)
        assertEquals(setOf(1), a.estornados)
        val b = CaixaDoVyntem.montarLote(listOf(pedido(1, "canceled")), setOf(1), setOf(1), 0L, "principal", 9_500L)
        assertTrue(b.itens.isEmpty())
    }

    @Test fun cancelamentoDePedidoQueNuncaEntrouNaoGeraEstorno() {
        val lote = CaixaDoVyntem.montarLote(listOf(pedido(7, "canceled")), emptySet(), emptySet(), 0L, "principal", 9_000L)
        assertTrue(lote.itens.isEmpty())
    }

    @Test fun pedidoSemValorEIgnorado() {
        assertTrue(CaixaDoVyntem.montarLote(listOf(pedido(1, "paid", total = 0)), emptySet(), emptySet(), 0L, "principal", 9_000L).itens.isEmpty())
    }
}
