package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentidadeDoSiteTest {
    private val base = "https://loja.exemplo.com/"

    @Test
    fun lePelaMetaTemaLogoDaPaginaEFonteDoGoogle() {
        val html = """<head><meta name="theme-color" content="#C2185B">
            <link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@400;700&display=swap" rel="stylesheet">
            <link rel="icon" href="/favicon.ico"></head><body><img class="site-logo" src="/img/logo.png"></body>"""
        val i = IdentidadeDoSite.extrair(html, base)
        assertEquals(0xC2185B, i.cor)
        assertEquals("https://loja.exemplo.com/img/logo.png", i.logo)
        assertEquals("Playfair Display", i.fonte)
        assertEquals("serifa", i.estilo)
    }

    @Test
    fun usaVariavelDeCorEDepoisAMaisFrequenteViva() {
        assertEquals(0x7B1FA2, IdentidadeDoSite.extrair("<style>:root{--cor-primary:#7B1FA2;--fundo:#ffffff}</style>", base).cor)
        val css = "a{color:#e91e63} b{color:#e91e63} c{color:#222222} d{color:#00bcd4} body{background:#ffffff}"
        assertEquals(0xE91E63, IdentidadeDoSite.extrair("<p>oi</p>", base, css).cor)
    }

    @Test
    fun ignoraCinzaELogoNaoHttpsEFontesGenericas() {
        val i = IdentidadeDoSite.extrair("""<meta name="theme-color" content="#888888"><img alt="logo" src="http://x.com/a.png"><style>body{font-family:-apple-system, 'Montserrat', sans-serif}</style>""", base)
        assertNull(i.cor)
        assertNull(i.logo)
        assertEquals("Montserrat", i.fonte)
        assertEquals("moderna", i.estilo)
    }

    @Test
    fun folhasSoDoMesmoSite() {
        val html = """<link rel="stylesheet" href="/a.css"><link rel="stylesheet" href="https://cdn.outro.com/b.css">"""
        assertEquals(listOf("https://loja.exemplo.com/a.css"), IdentidadeDoSite.folhas(html, base))
    }
}
