package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MensagensDoSiteTest {
    private fun pedido(id: Int, status: String, entrega: String) = Loja.Pedido(id, status, 10000, "Ana", entrega, 0L, null, emptyList())

    @Test fun reconheceSolicitacaoDeTroca() {
        val d = AtendenteDoRobo.especial("Olá! Abri a solicitação de troca/devolução nº 12 no site da Desejo de Intimidade.\n\nPedido: #34", { null }, 2)!!
        assertTrue(d.texto.contains("nº 12"))
        assertTrue(d.humano)
    }

    @Test fun compraPagaComEnvio() {
        val d = AtendenteDoRobo.especial("Olá! Fiz o pedido #57 no site da Desejo de Intimidade e o pagamento já foi confirmado.", { pedido(it, "paid", "sedex") }, 2)!!
        assertTrue(d.texto.contains("#57"))
        assertTrue(d.texto.contains("2 dias úteis"))
        assertEquals(false, d.humano)
    }

    @Test fun compraComRetiradaChamaAEquipe() {
        val d = AtendenteDoRobo.especial("Olá! Fiz o pedido #8 no site da Desejo de Intimidade", { pedido(it, "paid", "pickup") }, 2)!!
        assertTrue(d.humano)
    }

    @Test fun pedidoQueNaoAchaChamaAEquipe() {
        val d = AtendenteDoRobo.especial("Olá! Fiz o pedido #9 no site", { null }, 2)!!
        assertTrue(d.humano)
    }

    @Test fun mensagemComumNaoEEspecial() {
        assertNull(AtendenteDoRobo.especial("vocês têm lubrificante de morango?", { null }, 2))
    }
}
