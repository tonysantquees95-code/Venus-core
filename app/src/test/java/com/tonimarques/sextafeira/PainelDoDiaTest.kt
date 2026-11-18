package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PainelDoDiaTest {
    private fun dia(ano: Int, mes: Int, d: Int, h: Int) = Calendar.getInstance().apply { clear(); set(ano, mes - 1, d, h, 0, 0) }

    @Test fun mostraUmaVezPorDia() {
        val manha = dia(2026, 11, 18, 9)
        assertTrue(PainelDoDia.deveMostrar(true, null, manha))
        assertTrue(PainelDoDia.deveMostrar(true, "2026-11-17", manha))
        assertFalse(PainelDoDia.deveMostrar(true, PainelDoDia.chaveDoDia(manha), manha))
    }

    @Test fun respeitaInterruptorEHorario() {
        assertFalse(PainelDoDia.deveMostrar(false, null, dia(2026, 11, 18, 9)))
        assertFalse(PainelDoDia.deveMostrar(true, null, dia(2026, 11, 18, 3)))
        assertFalse(PainelDoDia.deveMostrar(true, null, dia(2026, 11, 18, 23)))
        assertTrue(PainelDoDia.deveMostrar(true, null, dia(2026, 11, 18, 21)))
    }

    @Test fun saudacaoPeloHorario() {
        assertEquals("Bom dia", PainelDoDia.saudacao(dia(2026, 11, 18, 8)))
        assertEquals("Boa tarde", PainelDoDia.saudacao(dia(2026, 11, 18, 15)))
        assertEquals("Boa noite", PainelDoDia.saudacao(dia(2026, 11, 18, 20)))
    }

    @Test fun montaSoOQueTem() {
        val agora = dia(2026, 11, 18, 9)
        val vazio = PainelDoDia.montar(null, emptyList(), null, null, agora)
        assertTrue(vazio, vazio.startsWith("Bom dia! Seu painel de hoje:"))
        assertTrue(vazio, vazio.contains("Nada marcado para hoje."))
        assertTrue(vazio, vazio.contains("Dica: "))
        val cheio = PainelDoDia.montar("Toni", listOf("às 9h, separar pedidos"), "Na loja, 2 vendas.", "Black Friday chegando.", agora)
        assertTrue(cheio, cheio.startsWith("Bom dia, Toni!"))
        assertTrue(cheio, cheio.contains("Lembretes: às 9h, separar pedidos."))
        assertTrue(cheio, cheio.contains("Na loja, 2 vendas."))
        assertTrue(cheio, cheio.contains("Black Friday chegando."))
        assertFalse(cheio, cheio.contains("Nada marcado"))
    }

    @Test fun lembretesDemaisViramContagem() {
        val t = PainelDoDia.montar(null, (1..7).map { "item $it" }, null, null, dia(2026, 11, 18, 9))
        assertTrue(t, t.contains("e mais 2."))
        assertFalse(t, t.contains("item 6"))
    }

    @Test fun dicaGiraPelosDias() {
        val a = PainelDoDia.dica(dia(2026, 11, 18, 9)); val b = PainelDoDia.dica(dia(2026, 11, 19, 9))
        assertTrue(a != b)
        assertTrue(PainelDoDia.DICAS.size >= 5)
    }
}
