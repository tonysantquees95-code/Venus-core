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
        assertEquals("responder_cliente", acao("responde uma cliente")!!.nome)
        assertEquals("responder_cliente", acao("me ajuda a responder a cliente")!!.nome)
        val catalogo = Loja.catalogoParaIA(listOf(Loja.Produto(7, "Gel KGEL", true, 3, 2990), Loja.Produto(8, "Escondido", false, 1, 100)))
        assertEquals("Gel KGEL | R$ 29,90 | R$ 28,40 | 3 un. | https://desejo-de-intimidade.tonysantquees95.workers.dev/produto/7", catalogo)
    }
}
