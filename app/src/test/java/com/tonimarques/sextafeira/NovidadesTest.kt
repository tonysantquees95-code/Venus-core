package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovidadesTest {
    @Test fun idsUnicosCrescentesTiposValidosETextos() {
        val ids = Novidades.LISTA.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(ids.sorted(), ids)
        for (n in Novidades.LISTA) {
            assertTrue("tipo inválido ${n.id}", n.tipo in listOf("novo", "melhoria", "correcao"))
            assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(n.data))
            assertTrue(n.titulo.trim().length >= 4 && n.texto.trim().length >= 10)
        }
    }

    @Test fun mostraSoOQueAindaNaoViu() {
        val l = (1..4).map { Novidades.Item(it, "2026-11-14", "novo", "titulo $it", "texto longo $it") }
        assertEquals(listOf(4, 3), Novidades.novasDesde(2, l).map { it.id })
        assertTrue(Novidades.novasDesde(4, l).isEmpty())
        assertEquals(4, Novidades.ultimoId(l))
        assertEquals(0, Novidades.ultimoId(emptyList()))
    }
}
