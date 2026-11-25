package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ResultadoTest {
    private fun dia(ano: Int, mes: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { clear(); set(ano, mes - 1, d, h, 0, 0) }
    private fun pedido(id: Int, status: String, total: Int, quando: Calendar) =
        Loja.Pedido(id, status, total, "Ana", "shipping", quando.timeInMillis, quando.timeInMillis, emptyList())

    @Test fun contaClientesDiferentesNoDia() {
        var d = emptyMap<String, Resultado.Dia>()
        d = Resultado.registrar(d, "2026-11-25", "a", false)
        d = Resultado.registrar(d, "2026-11-25", "a", false)
        d = Resultado.registrar(d, "2026-11-25", "b", true)
        val x = d.getValue("2026-11-25")
        assertEquals(2, x.clientes.size)
        assertEquals(3, x.respostas)
        assertEquals(1, x.passadas)
    }

    @Test fun cliente_que_volta_em_outro_dia_conta_uma_vez_na_semana() {
        var d = Resultado.registrar(emptyMap(), "2026-11-24", "a", false)
        d = Resultado.registrar(d, "2026-11-25", "a", false)
        val s = Resultado.somar(d, listOf("2026-11-24", "2026-11-25", "2026-11-23"))
        assertEquals(1, s.clientes.size)
        assertEquals(2, s.respostas)
    }

    @Test fun ultimosDiasAtravessaMes() {
        assertEquals(listOf("2026-12-01", "2026-11-30", "2026-11-29"), Resultado.ultimosDias(dia(2026, 12, 1), 3))
        assertEquals("2026-11-30", Resultado.ontem(dia(2026, 12, 1)))
    }

    @Test fun podaGuardaSo35Dias() {
        val agora = dia(2026, 11, 25)
        val d = mapOf("2026-11-25" to Resultado.Dia(setOf("a"), 1, 0), "2026-09-01" to Resultado.Dia(setOf("b"), 1, 0))
        assertEquals(setOf("2026-11-25"), Resultado.podar(d, agora).keys)
    }

    @Test fun gravaELeSemPerder() {
        val d = mapOf("2026-11-25" to Resultado.Dia(setOf("a", "b"), 5, 2))
        assertEquals(d, Resultado.ler(Resultado.gravar(d)))
        assertTrue(Resultado.ler("lixo{").isEmpty())
        assertTrue(Resultado.ler(null).isEmpty())
    }

    @Test fun vendasSoContamPagasNoPeriodo() {
        val agora = dia(2026, 11, 25, 15)
        val pedidos = listOf(
            pedido(1, "paid", 10000, dia(2026, 11, 25, 9)),
            pedido(2, "delivered", 5050, dia(2026, 11, 24, 10)),
            pedido(3, "pending", 9999, dia(2026, 11, 25, 10)),
            pedido(4, "cancelled", 7000, dia(2026, 11, 25, 10)),
            pedido(5, "paid", 3000, dia(2026, 11, 10, 10)),
        )
        val hoje = Resultado.vendas(pedidos, agora, 1)
        assertEquals(1, hoje.quantidade)
        assertEquals(10000L, hoje.centavos)
        val semana = Resultado.vendas(pedidos, agora, 7)
        assertEquals(2, semana.quantidade)
        assertEquals(15050L, semana.centavos)
    }

    @Test fun frasesClaras() {
        assertEquals("A Vênus ainda não respondeu nenhum cliente.", Resultado.fraseDeAtendimento(Resultado.Dia()))
        assertEquals("A Vênus atendeu 1 cliente (1 resposta).", Resultado.fraseDeAtendimento(Resultado.Dia(setOf("a"), 1, 0)))
        assertTrue(Resultado.fraseDeAtendimento(Resultado.Dia(setOf("a", "b"), 4, 2)).endsWith("e passou 2 conversas para você."))
        assertEquals("Nenhuma venda paga.", Resultado.fraseDeVendas(Resultado.Vendas(0, 0)))
        assertTrue(Resultado.fraseDeVendas(Resultado.Vendas(2, 15050)).startsWith("2 vendas pagas, "))
        assertEquals("", Resultado.ontemParaOAviso(Resultado.Dia()))
        assertTrue(Resultado.ontemParaOAviso(Resultado.Dia(setOf("a"), 1, 0)).startsWith("Ontem a Vênus atendeu"))
    }

    @Test fun avisoDaManhaUmaVezPorDiaNaHora() {
        assertFalse(Resultado.deveAvisarDeManha(true, 8, null, dia(2026, 11, 25, 7)))
        assertTrue(Resultado.deveAvisarDeManha(true, 8, null, dia(2026, 11, 25, 8)))
        assertTrue(Resultado.deveAvisarDeManha(true, 8, "2026-11-24", dia(2026, 11, 25, 11)))
        assertFalse(Resultado.deveAvisarDeManha(true, 8, Resultado.chaveDoDia(dia(2026, 11, 25)), dia(2026, 11, 25, 9)))
        assertFalse(Resultado.deveAvisarDeManha(false, 8, null, dia(2026, 11, 25, 9)))
        assertFalse("de tarde não avisa mais", Resultado.deveAvisarDeManha(true, 8, null, dia(2026, 11, 25, 15)))
        assertFalse("de madrugada não avisa", Resultado.deveAvisarDeManha(true, 8, null, dia(2026, 11, 25, 3)))
    }

    @Test fun horaInvalidaVoltaAoPadrao() {
        assertEquals(8, Resultado.horaValida(3))
        assertEquals(8, Resultado.horaValida(20))
        assertEquals(10, Resultado.horaValida(10))
    }
}
