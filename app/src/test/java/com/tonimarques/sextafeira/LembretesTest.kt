package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LembretesTest {
    /** Sábado, 31/10/2026, 21:30. */
    private fun agora() = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 31, 21, 30) }

    private fun em(dia: Int, mes: Int, hora: Int, minuto: Int = 0) =
        Calendar.getInstance().apply { clear(); set(2026, mes, dia, hora, minuto) }.timeInMillis

    private fun l(frase: String) = Lembretes.interpretar(frase, agora())

    @Test fun diaEHora() {
        val p = l("me lembra amanhã às 10 de ligar para o fornecedor")!!
        assertEquals(em(1, Calendar.NOVEMBER, 10), p.quando)
        assertEquals("Ligar para o fornecedor", p.texto)
        assertEquals(em(1, Calendar.NOVEMBER, 15), l("me lembra às 3 da tarde de buscar a Natália")!!.quando)
        assertEquals("Buscar a Natália", l("me lembra às 3 da tarde de buscar a Natália")!!.texto)
        assertEquals(em(1, Calendar.NOVEMBER, 8), l("me lembra às 8 de tomar o remédio")!!.quando)
        assertEquals(em(1, Calendar.NOVEMBER, 10, 30), l("me lembra às 10 e meia de ligar")!!.quando)
        assertEquals(em(1, Calendar.NOVEMBER, 10, 30), l("me lembra às 10h30 de ligar")!!.quando)
    }

    @Test fun daquiA() {
        val p = l("me avisa daqui a 20 minutos de tirar o bolo")!!
        assertEquals(agora().timeInMillis + 20 * 60_000L, p.quando)
        assertEquals("Tirar o bolo", p.texto)
        assertEquals(agora().timeInMillis + 30 * 60_000L, l("me lembra daqui a meia hora de olhar o forno")!!.quando)
        assertEquals(agora().timeInMillis + 120 * 60_000L, l("me lembra daqui a 2 horas de desligar a máquina")!!.quando)
    }

    @Test fun diaDaSemanaEDoMes() {
        val p = l("lembrete segunda às 9 reunião com a Natália")!!
        assertEquals(em(2, Calendar.NOVEMBER, 9), p.quando)
        assertEquals("Reunião com a Natália", p.texto)
        val b = l("me lembra de pagar o boleto no dia 5")!!
        assertEquals(em(5, Calendar.NOVEMBER, 9), b.quando)
        assertEquals("Pagar o boleto", b.texto)
        assertEquals(em(1, Calendar.NOVEMBER, 9), l("me lembra amanhã de manhã de levar o lixo")!!.quando)
        assertEquals(em(7, Calendar.NOVEMBER, 20), l("me lembra no próximo sábado à noite de ver o filme")!!.quando)
    }

    @Test fun naoEhLembrete() {
        assertNull(l("lembra que eu gosto de rock"))
        assertNull(l("como foram as vendas hoje"))
        assertNull(l("lembra de mim"))
    }

    @Test fun falado() {
        val a = agora()
        assertEquals("amanhã às 10:00", Lembretes.quandoFalado(em(1, Calendar.NOVEMBER, 10), a))
        assertEquals("daqui a 20 minutos (às 21:50)", Lembretes.quandoFalado(a.timeInMillis + 20 * 60_000L, a))
        assertEquals("segunda, 2/11, às 9:00", Lembretes.quandoFalado(em(2, Calendar.NOVEMBER, 9), a))
    }

    @Test fun comandos() {
        assertEquals("listar_lembretes", (ComandosLocais.interpretar("quais são meus lembretes") as ComandosLocais.Resultado.Acao).nome)
        val c = ComandosLocais.interpretar("cancela o lembrete do boleto") as ComandosLocais.Resultado.Acao
        assertEquals("cancelar_lembrete", c.nome)
        assertEquals("boleto", c.args["trecho"])
    }
}
