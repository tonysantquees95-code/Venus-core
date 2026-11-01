package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostagensTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    @Test fun comandos() {
        val a = acao("programa um post do gel kgel amanhã às 19h")!!
        assertEquals("programar_post", a.nome)
        assertEquals("feed", a.args["tipo"])
        assertEquals("Gel kgel", a.args["produto"])
        assertTrue(a.args["quando"] is Long)
        val s = acao("cria um story")!!
        assertEquals("story", s.args["tipo"])
        assertNull(s.args["quando"])
        assertNull(s.args["produto"])
        assertEquals("programar_post", acao("faz um post no instagram sobre o conjunto renda noir")!!.nome)
        assertEquals("listar_posts", acao("quais os posts programados")!!.nome)
        assertEquals("listar_posts", acao("meus posts programados")!!.nome)
    }
}
