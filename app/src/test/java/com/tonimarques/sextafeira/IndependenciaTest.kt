package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.Cerebro.Decisao
import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

/** Correção de frases, pesquisa própria e notícias: o que deixa a Vênus menos dependente do Gemini. */
class IndependenciaTest {

    private fun acao(frase: String, nome: String): Acao {
        val r = ComandosLocais.interpretar(frase)
        assertTrue("Esperava $nome para \"$frase\", veio $r", r is Acao && r.nome == nome)
        return r as Acao
    }

    // ------------------------------------------------------------- corretor
    @Test fun corrigePalavrasErradas() {
        assertEquals("liga a lanterna", Corretor.corrigir("liga a lanterma"))
        assertTrue(Corretor.corrigir("qual a previzao do tempo").contains("previsao"))
    }

    @Test fun naoMexeNoQueEstaCerto() {
        assertEquals("liga a lanterna", Corretor.corrigir("liga a lanterna"))
        assertEquals("toca Anitta", Corretor.corrigir("toca Anitta"))
        assertEquals("abre o app 99", Corretor.corrigir("abre o app 99"))
    }

    @Test fun chaveFonetica() {
        assertEquals(Corretor.chaveFonetica("chuva"), Corretor.chaveFonetica("xuva"))
        assertEquals(Corretor.chaveFonetica("gente"), Corretor.chaveFonetica("jente"))
    }

    @Test fun distancia() {
        assertEquals(1, Corretor.distancia("lanterma", "lanterna"))
        assertEquals(1, Corretor.distancia("muscia", "musica"))
        assertEquals(0, Corretor.distancia("bateria", "bateria"))
    }

    @Test fun cerebroEntendeFraseCorrigida() {
        val c = Cerebro(Random(7)) { Calendar.getInstance() }
        val d = c.responder("acende a lanterma")
        assertTrue("veio $d", d is Decisao.Executar && d.nome == "lanterna")
        assertNotNull(c.ultimaCorrecao)
        c.responder("liga a lanterna")
        assertEquals(null, c.ultimaCorrecao)
    }

    // ------------------------------------------------------------- comandos novos
    @Test fun cotacao() {
        assertEquals("dolar", acao("quanto está o dólar hoje", "cotacao_moeda").args["moeda"])
        assertEquals("euro", acao("cotação do euro", "cotacao_moeda").args["moeda"])
    }

    @Test fun noticias() {
        assertEquals("tecnologia", acao("notícias de tecnologia", "noticias").args["tema"])
        assertEquals("Brasil", acao("quais são as últimas notícias", "noticias").args["tema"])
        assertEquals("celulares", acao("quais celulares foram lançados recentemente", "noticias").args["tema"])
    }

    @Test fun acompanharAssuntos() {
        val a = acao("acompanhe lançamentos de carros", "acompanhar_tema")
        assertEquals("seguir", a.args["acao"])
        assertEquals("lançamentos de carros", a.args["tema"])
        assertEquals("parar", acao("para de acompanhar tecnologia", "acompanhar_tema").args["acao"])
        acao("atualiza as notícias", "atualizar_noticias")
    }

    // ------------------------------------------------------------- pesquisa
    @Test fun leRssDoGoogleNoticias() {
        val xml = """
            <rss><channel><title>Canal</title>
            <item><title>Galaxy S26 chega ao Brasil - Tecnoblog</title><link>https://news.google.com/a1</link>
            <pubDate>Fri, 30 Oct 2026 12:00:00 GMT</pubDate><source url="https://tecnoblog.net">Tecnoblog</source></item>
            <item><title><![CDATA[iPhone 18 &amp; mais]]></title><link>https://news.google.com/a2</link>
            <source url="https://g1.globo.com">g1</source></item>
            </channel></rss>
        """.trimIndent()
        val lista = Pesquisa.lerRss(xml)
        assertEquals(2, lista.size)
        assertEquals("Galaxy S26 chega ao Brasil", lista[0].titulo)
        assertEquals("Tecnoblog", lista[0].fonte)
        assertTrue(lista[0].data > 0)
        assertEquals("iPhone 18 & mais", lista[1].titulo)
        val r = Pesquisa.formatarNoticias(lista, "Novidades:")
        assertTrue(r.texto.startsWith("Novidades: 1. Galaxy S26"))
        assertEquals(2, r.fontes.size)
    }

    @Test fun montaCotacao() {
        val json = JSONObject("""{"USDBRL":{"bid":"5.4321","pctChange":"0.35","name":"Dólar Americano/Real Brasileiro"}}""")
        val texto = Pesquisa.montarCotacao(json, "USD")
        assertTrue(texto, texto.startsWith("O Dólar Americano está em R$"))
        assertTrue(texto, texto.contains("5,43"))
        assertTrue(texto, texto.contains("alta de 0,35%"))
    }

    @Test fun palavrasChaveEPontuacao() {
        val chaves = Pesquisa.palavrasChave("quais celulares foram lançados recentemente?")
        assertTrue("$chaves", "celulares" in chaves)
        assertFalse("$chaves", "quais" in chaves || "recentemente" in chaves)
        assertEquals(6, Pesquisa.pontuar(listOf("celulares", "lancados"), "Samsung lança novos celulares", "lançamentos de celulares"))
        assertEquals(0, Pesquisa.pontuar(listOf("geladeira"), "Samsung lança novos celulares", "tecnologia"))
    }

    @Test fun oQueDaParaPesquisarSozinha() {
        assertTrue(Pesquisa.ehPesquisavel("o que é fotossíntese"))
        assertTrue(Pesquisa.ehPesquisavel("quais as novidades do iPhone"))
        assertTrue(Pesquisa.ehPesquisavel("a loja Magazine Luiza é confiável"))
        assertFalse(Pesquisa.ehPesquisavel("me conta uma história"))
        assertEquals("USD", Pesquisa.codigoDaMoeda("dólar"))
        assertEquals("EUR", Pesquisa.codigoDaMoeda("euro"))
    }
}
