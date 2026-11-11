package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletaECriativosTest {
    @Test
    fun extraiAsCoresDominantesDeUmaImagem() {
        // 70% vermelho, 30% azul
        val px = IntArray(100) { if (it < 70) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val cores = Paleta.extrair(px, 3)
        assertEquals(listOf(0xFF0000, 0x0000FF), cores) // a mais presente primeiro; sem repetir tons
    }

    @Test
    fun ignoraPixelsTransparentes() {
        val px = IntArray(50) { 0x00123456 } + IntArray(10) { 0xFF00FF00.toInt() }
        assertEquals(listOf(0x00FF00), Paleta.extrair(px, 4))
        assertTrue(Paleta.extrair(IntArray(10) { 0 }, 4).isEmpty())
    }

    @Test
    fun hexESempreMaiusculoComSeisDigitos() {
        assertEquals("#00000F", Paleta.hex(0x00000F))
        assertEquals("#E36BD0", Paleta.hex(0xFFE36BD0.toInt()))
    }

    @Test
    fun contrasteEscolheTextoLegivel() {
        assertEquals(21.0, Paleta.contraste(0x000000, 0xFFFFFF), 0.01)
        assertEquals(0xFFFFFF, Paleta.textoSobre(0x101030))
        assertEquals(0x06081A, Paleta.textoSobre(0xFFEE88))
    }

    @Test
    fun harmoniaDaQuatroCoresComFundoEscuro() {
        val h = Paleta.harmonia(0xE36BD0)
        assertEquals(4, h.size)
        assertTrue(Tema.hsv(h[3])[2] < 0.2f)
        assertEquals(Paleta.corDoNome("Vênus"), Paleta.corDoNome("vênus")) // estável
    }

    @Test
    fun leOsConceitosDaIaEDescartaOInvalido() {
        val texto = "Aqui estão:\n[{\"slogan\":\"Desejo e cuidado\",\"forma\":\"hexagono\",\"cor\":\"#E36BD0\",\"estilo\":\"serifa\"}," +
            "{\"forma\":\"estrela\",\"cor\":\"verde\"},{\"forma\":\"estrela\",\"cor\":\"#36D6FF\",\"estilo\":\"x\"}]"
        val c = lerConceitos(texto, "Minha Loja", "")
        assertEquals(2, c.size) // o do meio tem cor inválida
        assertEquals("hexagono", c[0].forma)
        assertEquals("serifa", c[0].estilo)
        assertTrue(c[1].forma in Criativos.FORMAS) // forma desconhecida é trocada
        assertTrue(lerConceitos("sem json", "x", "").isEmpty())
    }

    @Test
    fun conceitosDeReservaSaoQuatroDiferentesEEstaveis() {
        val a = conceitosDeReserva("Desejo", "")
        assertEquals(4, a.size)
        assertEquals(4, a.map { it.cor }.toSet().size)
        assertEquals(a.map { it.cor }, conceitosDeReserva("Desejo", "").map { it.cor })
    }
}
