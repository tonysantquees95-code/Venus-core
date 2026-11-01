package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramTest {
    private fun acao(f: String) = (ComandosLocais.interpretar(f) as? Acao)?.nome

    @Test fun comandos() {
        assertEquals("instagram_conectar", acao("conecta o Instagram"))
        assertEquals("instagram_conectar", acao("conectar o insta"))
        assertEquals("instagram_conectar", acao("trocar o token do Instagram"))
        assertEquals("instagram_resumo", acao("como está o Instagram?"))
        assertEquals("instagram_resumo", acao("como tá nosso insta"))
        assertEquals("instagram_resumo", acao("quantos seguidores tem no Instagram"))
        assertEquals("instagram_resumo", acao("quantos seguidores"))
        assertEquals("instagram_resumo", acao("desempenho do Instagram"))
        assertEquals("instagram_resumo", acao("como foram os últimos posts"))
        assertEquals("instagram_comentarios", acao("comentários do Instagram"))
        assertEquals("instagram_comentarios", acao("tem comentário novo?"))
        assertEquals("instagram_comentarios", acao("responde os comentários"))
        // o que já existia continua igual
        assertEquals("programar_post", acao("programa um post do gel amanhã às 19h"))
        assertEquals("listar_posts", acao("quais os posts programados"))
        assertNotEquals("instagram_conectar", acao("abre o Instagram"))
    }

    @Test fun seguimentoDoPost() {
        assertEquals("postar_agora", acao("Pode postar como está"))
        assertEquals("postar_agora", acao("posta agora"))
        assertEquals("postar_agora", acao("pode publicar"))
        assertEquals("postar_agora", acao("publica já"))
        assertNotEquals("postar_agora", acao("posta um story do gel"))
        val m = ComandosLocais.REPROGRAMAR_POST.matchEntire("mudar para hoje as 19h")!!
        assertEquals("hoje as 19h", m.groupValues[1])
        assertEquals("amanha as 10h", ComandosLocais.REPROGRAMAR_POST.matchEntire("muda o post para amanha as 10h")!!.groupValues[1])
        assertEquals("feed", ComandosLocais.TIPO_POST.find("poste no feed se nao consegue no story")!!.groupValues[1])
        assertEquals("story", ComandosLocais.TIPO_POST.find("troca para story")!!.groupValues[1])
        assertEquals("https://desejo-de-intimidade.floot.app/_cdn/x.jpg", Loja.enderecoCompleto("/_cdn/x.jpg"))
        assertEquals("https://a.com/x.jpg", Loja.enderecoCompleto("https://a.com/x.jpg"))
    }

    @Test fun tipoDeToken() {
        assertTrue(Instagram.ehFacebook(Instagram.limparToken(" EAAGm0PX4ZCpsBAxyz ")))
        assertTrue(!Instagram.ehFacebook("IGAAabc123"))
    }

    @Test fun token() {
        assertEquals("IGAAabc123", Instagram.limparToken("  \"IGAAabc123\" "))
        assertEquals("IGAAabc123", Instagram.limparToken("https://x?access_token=IGAAabc123&expires_in=5183944"))
        assertEquals("IGAAabc123", Instagram.limparToken("IGAA abc\n123"))
    }

    @Test fun conta() {
        val c = Instagram.conta(JSONObject("""{"user_id":"1789","username":"desejodeintimidade","account_type":"BUSINESS","id":"999"}"""))
        assertEquals("1789", c.id)
        assertEquals("desejodeintimidade", c.usuario)
        assertEquals("999", Instagram.conta(JSONObject("""{"id":"999","username":"x"}""")).id)
    }

    private val posts = JSONArray(
        """[
        {"id":"1","caption":"Conjunto renda noir 🖤 #lingerie #moda","media_type":"IMAGE","timestamp":"2026-10-30T22:00:00+0000","like_count":40,"comments_count":5,"permalink":"https://instagram.com/p/1"},
        {"id":"2","caption":"Gel beijável","media_type":"IMAGE","timestamp":"2026-10-28T22:00:00+0000","like_count":12,"comments_count":0,"permalink":"https://instagram.com/p/2"},
        {"id":"3","media_type":"IMAGE","timestamp":"2026-10-10T22:00:00+0000","like_count":10,"comments_count":1,"permalink":"https://instagram.com/p/3"}
        ]"""
    )
    private val agora = Instagram.data("2026-11-01T12:00:00+0000")

    @Test fun resumo() {
        val lista = Instagram.lerPosts(posts)
        assertEquals(3, lista.size)
        assertEquals("", lista[2].legenda)
        val perfil = JSONObject("""{"username":"desejodeintimidade","followers_count":1234,"media_count":87}""")
        val t = Instagram.montarResumo(perfil, lista, 1200, agora)
        assertTrue(t, t.contains("1.234 seguidores (34 a mais"))
        assertTrue(t, t.contains("87 publicações"))
        assertTrue(t, t.contains("Nos últimos 7 dias foram 2 posts, com 52 curtidas e 5 comentários"))
        assertTrue(t, t.contains("“Conjunto renda noir 🖤”"))
        val semPost = Instagram.montarResumo(perfil, lista.drop(2), -1, agora)
        assertTrue(semPost, semPost.contains("Não teve post nos últimos 7 dias (o último foi há 21 dias)"))
        assertTrue(Instagram.montarResumo(perfil, emptyList(), -1, agora).contains("Ainda não tem posts"))
    }

    @Test fun comentariosSemResposta() {
        val post = Instagram.lerPosts(posts)[0]
        val comentarios = JSONArray(
            """[
            {"id":"c1","text":"Qual o preço?","username":"ana","timestamp":"2026-10-31T10:00:00+0000"},
            {"id":"c2","text":"Lindo!","username":"bia","timestamp":"2026-10-31T11:00:00+0000","replies":{"data":[{"username":"desejodeintimidade"}]}},
            {"id":"c3","text":"Obrigada pelo carinho","username":"desejodeintimidade","timestamp":"2026-10-31T12:00:00+0000"},
            {"id":"c4","text":"Tem M?","username":"carla","timestamp":"2026-10-31T13:00:00+0000","replies":{"data":[{"username":"outra"}]}}
            ]"""
        )
        val r = Instagram.semResposta(comentarios, post, "DesejoDeIntimidade")
        assertEquals(listOf("c1", "c4"), r.map { it.id })
        assertEquals("ana", r[0].usuario)
        assertEquals(post.legenda, r[0].legendaDoPost)
    }

    @Test fun erros() {
        fun e(json: String, codigo: Int = 400) = Instagram.erroAmigavel(codigo, JSONObject(json))
        assertTrue(e("""{"code":190,"message":"Error validating access token"}""").contains("venceu"))
        assertTrue(e("""{"code":10,"message":"permission"}""").contains("permissão"))
        assertTrue(e("""{"code":4,"message":"limit"}""").contains("esperar"))
        assertTrue(e("""{"code":36003,"error_subcode":2207009,"message":"aspect ratio"}""").contains("proporção"))
        assertTrue(e("""{"code":1,"message":"Algo estranho"}""").contains("Algo estranho"))
        assertEquals("O Instagram respondeu com erro 500.", Instagram.erroAmigavel(500, null))
    }

    @Test fun corteDaFoto() {
        assertNull(Postagens.corteParaFeed(1080, 1080))
        assertNull(Postagens.corteParaFeed(1080, 1350))
        assertEquals(listOf(0, 90, 1080, 1350), Postagens.corteParaFeed(1080, 1530)) // 3:4 vira 4:5
        assertEquals(listOf(0, 285, 1080, 1350), Postagens.corteParaFeed(1080, 1920)) // 9:16 vira 4:5
        val largo = Postagens.corteParaFeed(4000, 1000)!!
        assertEquals(1910, largo[2])
        assertEquals((4000 - 1910) / 2, largo[0])
    }

    @Test fun legenda() {
        assertEquals("post sem legenda", Instagram.resumirLegenda("#a #b"))
        assertTrue(Instagram.resumirLegenda("x ".repeat(80)).endsWith("…"))
    }
}
