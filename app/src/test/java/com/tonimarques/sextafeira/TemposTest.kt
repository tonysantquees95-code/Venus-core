package com.tonimarques.sextafeira

import org.junit.Assert.assertTrue
import org.junit.Test

class TemposTest {
    @Test
    fun anotaEtapasQuemRespondeuETotal() {
        Tempos.comecar()
        Tempos.marcar("memória")
        Tempos.marcar("memória") // só a primeira vez conta
        Tempos.quem("Groq/gpt-oss-120b")
        Tempos.terminar("como funciona um pulsador?")
        val r = Tempos.relatorio()
        assertTrue(r, r.contains("memória"))
        assertTrue(r, r.contains("Groq/gpt-oss-120b"))
        assertTrue(r, r.contains("total"))
    }

    @Test
    fun semPedidoEmAndamentoNaoFazNada() {
        Tempos.terminar("nada") // não quebra
        Tempos.marcar("x")
    }
}
