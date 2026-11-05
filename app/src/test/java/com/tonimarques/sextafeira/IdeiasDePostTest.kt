package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class IdeiasDePostTest {
    private fun item(texto: String, legenda: String) = Memoria.Item(1, "ideia_post", texto, legenda, null, null, null)

    @Test fun leAIdeia() {
        val i = IdeiasDePost.ler(item("Fantasia e Diversão (2026-12-14, feed)", "Transforme a noite com a Mini Fantasia Enfermeira do Amor"))
        assertEquals("Fantasia e Diversão", i.titulo)
        assertEquals("feed", i.formato)
        assertEquals(14, i.data!!.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.DECEMBER, i.data!!.get(Calendar.MONTH))
        assertEquals("story", IdeiasDePost.ler(item("Detalhes (10/12, stories)", "x")).formato)
        assertNull(IdeiasDePost.ler(item("Sem data", "x")).data)
    }

    @Test fun achaOProduto() {
        val i = IdeiasDePost.ler(item("Fantasia (2026-12-14, feed)", "Com a Mini Fantasia Enfermeira do Amor, detalhes que encantam."))
        val produtos = listOf(
            Loja.Produto(1, "Mini Fantasia Enfermeira do Amor", true, 3, 8990),
            Loja.Produto(2, "Gel Volumão", true, 5, 2790),
        )
        assertEquals(1, IdeiasDePost.produtoDaIdeia(i, produtos)?.id)
        assertNull(IdeiasDePost.produtoDaIdeia(IdeiasDePost.ler(item("Autoestima (feed)", "Cuide de você.")), produtos))
    }

    @Test fun horarioSugerido() {
        val agora = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 5, 13, 0) }.timeInMillis
        val i = IdeiasDePost.ler(item("X (2026-12-14, feed)", "y"))
        val c = Calendar.getInstance().apply { timeInMillis = IdeiasDePost.quandoSugerido(i, "feed", agora) }
        assertEquals(14, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, c.get(Calendar.HOUR_OF_DAY))
        val passada = IdeiasDePost.ler(item("X (2026-10-01, story)", "y"))
        val d = Calendar.getInstance().apply { timeInMillis = IdeiasDePost.quandoSugerido(passada, "story", agora) }
        assertEquals(6, d.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, d.get(Calendar.HOUR_OF_DAY))
        assertTrue(d.timeInMillis > agora)
    }
}
