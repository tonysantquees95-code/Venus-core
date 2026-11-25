package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EiVenusTest {
    @Test fun eiVenusSempreAtiva() {
        for (f in listOf("ei vênus", "Ei Vênus", "ei venus", "oi vênus", "hey venus", "ei, Vênus")) {
            assertTrue(f, Cerebro.pareceAtivacao(f))
        }
    }

    @Test fun eiMalOuvidoAindaChama() {
        for (f in listOf("e vênus", "a venus", "hei vênus", "eh venus")) {
            assertTrue(f, Cerebro.pareceAtivacao(f))
        }
        // só vale com duas palavras: frase de vídeo não acorda
        for (f in listOf("marte e vênus são planetas", "e vênus é quente", "a vênus de milo", "vênus")) {
            assertFalse(f, Cerebro.pareceAtivacao(f))
        }
    }

    @Test fun soNomeDependeDoInterruptor() {
        assertFalse(Cerebro.pareceAtivacao("vênus aumentar o volume"))
        assertTrue(Cerebro.pareceAtivacao("vênus aumentar o volume", soNome = true))
        assertTrue(Cerebro.pareceAtivacao("vênus quanto está o dólar", soNome = true))
    }

    @Test fun conversaSemChamadaNaoAtiva() {
        for (f in listOf("o planeta vênus é quente", "eu vi vênus no céu", "toca uma música", "")) {
            assertFalse(f, Cerebro.pareceAtivacao(f, soNome = true))
        }
    }

    @Test fun pedidoVemJuntoDaChamada() {
        assertEquals("", Cerebro.pedidoPorNome("ei vênus"))
        assertEquals("coloca a música tal", Cerebro.pedidoPorNome("ei vênus coloca a música tal"))
        assertEquals("coloca a música tal", Cerebro.pedidoPorNome("Ei, Vênus, coloca a música tal"))
        assertEquals("aumentar o volume", Cerebro.pedidoPorNome("vênus aumentar o volume"))
        assertEquals("aumentar o volume", Cerebro.pedidoPorNome("ei sexta feira aumentar o volume"))
        assertNull(Cerebro.pedidoPorNome("vênus aumentar o volume", exigirChamada = true))
        assertNull(Cerebro.pedidoPorNome("o planeta vênus"))
        assertNotNull(Cerebro.pedidoPorNome("hey vênus"))
    }

    @Test fun fraseAntigaContinuaFuncionando() {
        assertTrue(Cerebro.pareceAtivacao("vênus ativar"))
        assertEquals("toca pagode", Cerebro.restoDepoisDaAtivacao("vênus ativar toca pagode"))
    }

    @Test fun nomeDoArquivoGuardado() {
        val nome = Guardados.nomeDoArquivo("Como fazer um bolo de cenoura: misture tudo!", 0L)
        assertTrue(nome, nome.endsWith(" - como fazer um bolo de cenoura.txt"))
        assertTrue(Guardados.nomeDoArquivo("???", 0L).endsWith(".txt"))
    }
}
