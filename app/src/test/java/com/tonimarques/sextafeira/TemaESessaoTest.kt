package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemaESessaoTest {
    @Test
    fun lerHexAceitaAsFormasComunsERecusaLixo() {
        assertEquals(0xE36BD0, Tema.lerHex("#E36BD0"))
        assertEquals(0xE36BD0, Tema.lerHex(" e36bd0 "))
        assertEquals(0xAABBCC, Tema.lerHex("#ABC"))
        assertNull(Tema.lerHex("#GG0000"))
        assertNull(Tema.lerHex("12345"))
        assertNull(Tema.lerHex(""))
    }

    @Test
    fun hexVoltaParaOMesmoValor() {
        assertEquals("#36D6FF", Tema.hex(0x36D6FF))
        assertEquals("#00000F", Tema.hex(0x00000F))
        for (c in listOf(0x000000, 0xFFFFFF, 0xE36BD0, 0x123456)) assertEquals(c, Tema.lerHex(Tema.hex(c)))
    }

    @Test
    fun hsvEDeHsvSaoInversos() {
        for (c in Tema.PREDEFINIDAS.map { it.cor }.filter { it != 0 } + listOf(0xFF0000, 0x00FF00, 0x0000FF, 0x808080)) {
            val h = Tema.hsv(c)
            val volta = Tema.deHsv(h[0], h[1], h[2])
            assertTrue("$c → $volta", Math.abs(Tema.vermelho(c) - Tema.vermelho(volta)) <= 1 &&
                Math.abs(Tema.verde(c) - Tema.verde(volta)) <= 1 && Math.abs(Tema.azul(c) - Tema.azul(volta)) <= 1)
        }
        assertEquals(0f, Tema.hsv(0xFF0000)[0], 0.01f)
        assertEquals(120f, Tema.hsv(0x00FF00)[0], 0.01f)
        assertEquals(240f, Tema.hsv(0x0000FF)[0], 0.01f)
    }

    @Test
    fun trioFicaClaroOBastanteMesmoParaCoresEscuras() {
        val trio = Tema.trio(0x101010)
        assertEquals(3, trio.size)
        assertTrue(trio.all { Tema.hsv(it)[2] >= 0.69f })
        val vermelho = Tema.trio(0xFF0000)
        assertEquals(0xFF0000, vermelho[0])
        assertTrue(vermelho[1] != vermelho[0])
    }

    @Test
    fun corPredefinidaAutomaticaEAPrimeira() {
        assertEquals(0, Tema.PREDEFINIDAS.first().cor)
        assertEquals(7, Tema.PREDEFINIDAS.size)
        assertTrue(Tema.escuraDemais(0x101010))
        assertFalse(Tema.escuraDemais(0xE36BD0))
    }

    @Test
    fun reabrirSoComecaConversaNovaQuandoFazSentido() {
        val longe = Sessao.AUSENCIA_MINIMA_MS
        assertTrue(Sessao.deveComecarNova(longe, conversaTemMensagens = true, ocupado = false, esperandoVoltar = false))
        assertFalse("saiu por pouco tempo", Sessao.deveComecarNova(longe - 1, true, false, false))
        assertFalse("conversa vazia", Sessao.deveComecarNova(longe, false, false, false))
        assertFalse("respondendo", Sessao.deveComecarNova(longe, true, true, false))
        assertFalse("esperando voltar de outro app", Sessao.deveComecarNova(longe, true, false, true))
    }
}
