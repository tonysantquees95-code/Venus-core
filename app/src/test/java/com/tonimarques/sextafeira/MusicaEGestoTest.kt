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

    @Test fun todaFraseDaListaFazAlgo() {
        for (f in Despertador.FRASES_MUSICA) {
            assertTrue("\"$f\" não leva a nenhum comando", Cerebro.comandoDeMusica(f) != null || Cerebro.pareceAtivacao(f))
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
}
