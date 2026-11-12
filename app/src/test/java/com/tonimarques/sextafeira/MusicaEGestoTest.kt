package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comandos curtos de música (sem "Vênus, ativar") e o gesto de torcer o pulso. */
class MusicaEGestoTest {

    @Test fun comandosCurtos() {
        assertEquals("pausar", Cerebro.comandoDeMusica("pausar música"))
        assertEquals("pausar", Cerebro.comandoDeMusica("[unk] para a música"))
        assertEquals("proxima", Cerebro.comandoDeMusica("próxima música"))
        assertEquals("proxima", Cerebro.comandoDeMusica("próxima"))
        assertEquals("anterior", Cerebro.comandoDeMusica("música anterior"))
        assertEquals("tocar", Cerebro.comandoDeMusica("continuar música"))
        assertEquals("aumentar_volume", Cerebro.comandoDeMusica("aumenta o volume"))
        assertEquals("diminuir_volume", Cerebro.comandoDeMusica("abaixa o volume"))
    }

    @Test fun naoDisparaComFrasesLongasOuLetras() {
        assertNull(Cerebro.comandoDeMusica("eu não quero parar de dançar essa música"))
        assertNull(Cerebro.comandoDeMusica("[unk] [unk]"))
        assertNull(Cerebro.comandoDeMusica("para"))
        assertNull(Cerebro.comandoDeMusica(""))
    }

    @Test fun falaLivreDeMusica() {
        assertEquals("proxima", Cerebro.comandoDeMusica("pula essa"))
        assertEquals("proxima", Cerebro.comandoDeMusica("passa para a próxima"))
        assertEquals("anterior", Cerebro.comandoDeMusica("volta a música"))
        assertEquals("pausar", Cerebro.comandoDeMusica("para de tocar"))
        assertEquals("tocar", Cerebro.comandoDeMusica("volta a tocar"))
        assertEquals("volume_nivel:10", Cerebro.comandoDeMusica("diminui o volume para dez por cento"))
        assertEquals("volume_nivel:50", Cerebro.comandoDeMusica("volume em 50"))
        assertEquals("volume_rel:50", Cerebro.comandoDeMusica("aumenta o volume em cinquenta por cento"))
        assertEquals("volume_rel:-20", Cerebro.comandoDeMusica("abaixa o volume em vinte por cento"))
        assertEquals("volume_nivel:25", Cerebro.comandoDeMusica("volume para vinte e cinco por cento"))
        assertEquals("aumentar_volume", Cerebro.comandoDeMusica("aumenta um pouco o volume"))
        assertEquals("diminuir_volume", Cerebro.comandoDeMusica("mais baixo"))
    }

    @Test fun comOsNomeDaVenusComoNaAlexa() {
        assertEquals("proxima", Cerebro.comandoDeMusica("vênus passa essa", exigirNome = true))
        assertEquals("proxima", Cerebro.comandoDeMusica("vênus próxima", exigirNome = true))
        assertEquals("anterior", Cerebro.comandoDeMusica("vênus volta a música", exigirNome = true))
        assertEquals("aumentar_volume", Cerebro.comandoDeMusica("vênus aumentar o volume", exigirNome = true))
        assertEquals("diminuir_volume", Cerebro.comandoDeMusica("vênus abaixa o volume", exigirNome = true))
        assertEquals("volume_nivel:50", Cerebro.comandoDeMusica("vênus coloca o volume em cinquenta por cento", exigirNome = true))
        assertEquals("pausar", Cerebro.comandoDeMusica("ei vênus para a música", exigirNome = true))
    }

    @Test fun venusTocaGeneroOuArtista() {
        assertTrue(Cerebro.pedeTocarAlgo("vênus toca [unk]"))
        assertTrue(Cerebro.pedeTocarAlgo("venus coloca"))
        assertFalse(Cerebro.pedeTocarAlgo("vênus coloca o volume em [unk]"))
        assertFalse(Cerebro.pedeTocarAlgo("toca pagode"))
        assertEquals("tocar_busca:pagode", Cerebro.pedidoDeMusica("vênus toca pagode"))
        assertEquals("tocar_busca:rock", Cerebro.pedidoDeMusica("vênus toca música de rock"))
        assertEquals("tocar_busca:evidencias chitaozinho e xororo", Cerebro.pedidoDeMusica("vênus coloca evidências chitãozinho e xororó"))
        assertEquals("tocar", Cerebro.pedidoDeMusica("vênus toca"))
        assertEquals("volume_nivel:50", Cerebro.pedidoDeMusica("vênus coloca o volume em cinquenta por cento"))
        assertNull(Cerebro.pedidoDeMusica("toca pagode"))
    }

    @Test fun semONomeNaoObedeceComMusica() {
        assertNull(Cerebro.comandoDeMusica("passa essa", exigirNome = true))
        assertNull(Cerebro.comandoDeMusica("próxima", exigirNome = true))
        assertNull(Cerebro.comandoDeMusica("aumenta o volume", exigirNome = true))
    }

    @Test fun venusAtivarNaoEComandoDeMusica() {
        assertNull(Cerebro.comandoDeMusica("vênus ativar", exigirNome = true))
        assertNull(Cerebro.comandoDeMusica("vênus", exigirNome = true))
    }

    @Test fun foraDaMusicaNaoEComando() {
        assertNull(Cerebro.comandoDeMusica("qual é a previsão do tempo"))
        assertNull(Cerebro.comandoDeMusica("manda mensagem para a Maria"))
        assertNull(Cerebro.comandoDeMusica("[unk] volume [unk]"))
    }

    @Test fun vocabularioTemOsNumerosEOsComandos() {
        for (w in listOf("volume", "próxima", "pula", "dez", "cinquenta", "cem", "cento", "vênus")) {
            assertTrue("\"$w\" falta na lista", w in Despertador.FRASES_MUSICA)
        }
    }

    @Test fun torcerOPulsoDuasVezes() {
        val d = GestoTorcer.Detector()
        var t = 1000L
        // ida e volta, duas vezes
        val giros = listOf(6f, -6f, 6f, -6f)
        var disparou = false
        for (y in giros) {
            repeat(3) { disparou = d.amostra(0.5f, y, 0.3f, t) || disparou; t += 20 }
            repeat(5) { d.amostra(0f, 0f, 0f, t); t += 20 }
        }
        assertTrue(disparou)
    }

    @Test fun naoDisparaAndandoOuComUmGiroSo() {
        val d = GestoTorcer.Detector()
        var t = 1000L
        // um giro só, ida e volta
        assertFalse(d.amostra(0f, 6f, 0f, t))
        t += 300
        assertFalse(d.amostra(0f, -6f, 0f, t))
        // movimentos fracos ou em outro eixo (balançar a mão andando)
        t += 300
        for (i in 0 until 50) {
            assertFalse(d.amostra(if (i % 2 == 0) 7f else -7f, 2f, 1f, t))
            assertFalse(d.amostra(0f, if (i % 2 == 0) 3f else -3f, 0f, t + 10))
            t += 100
        }
        // giros muito espaçados (mais de 1,5 s) não contam juntos
        val e = GestoTorcer.Detector()
        var u = 0L
        for (y in listOf(6f, -6f, 6f, -6f)) {
            assertFalse(e.amostra(0f, y, 0f, u))
            u += 800
        }
    }

    @Test fun volumePorPorcentagemEPlayer() {
        assertEquals(15, Midia.indiceDeVolume(100, 15))
        assertEquals(0, Midia.indiceDeVolume(0, 15))
        assertEquals(8, Midia.indiceDeVolume(50, 15))
        assertEquals(50, Midia.nivelFalado("50 por cento"))
        assertEquals(null, Midia.nivelFalado("musica"))
        assertEquals("b", Midia.escolherPlayer(null, "b", listOf("a", "b")))
        assertEquals("a", Midia.escolherPlayer(null, "x", listOf("a", "b")))
        assertEquals("z", Midia.escolherPlayer("z", "b", listOf("a", "b")))
        assertEquals(null, Midia.escolherPlayer(null, null, emptyList()))
    }

    @Test fun nomeSozinhoSoComOInterruptorLigado() {
        assertTrue(Cerebro.pareceAtivacao("vênus", soNome = true))
        assertFalse(Cerebro.pareceAtivacao("vênus"))
    }
}
