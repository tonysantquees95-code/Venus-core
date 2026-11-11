package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Test

class DadosDaLojaTest {
    @Test fun linkPadraoEhOSiteConectado() {
        assertEquals("https://loja.com", DadosDaLoja.escolherLink("site", "https://loja.com", "https://venus.app/v/1"))
        assertEquals("https://venus.app/v/1", DadosDaLoja.escolherLink("vitrine", "https://loja.com", "https://venus.app/v/1"))
    }

    @Test fun semSiteUsaAVitrineDaVenus() {
        assertEquals("https://venus.app/v/1", DadosDaLoja.escolherLink("site", "", "https://venus.app/v/1"))
        assertEquals("https://loja.com", DadosDaLoja.escolherLink("vitrine", "https://loja.com", ""))
    }

    @Test fun arrobaDoInstagram() {
        assertEquals("@sualoja", DadosDaLoja.arroba("sualoja"))
        assertEquals("@sualoja", DadosDaLoja.arroba("@sualoja"))
        assertEquals("@sualoja", DadosDaLoja.arroba("https://www.instagram.com/sualoja/?hl=pt"))
        assertEquals("", DadosDaLoja.arroba(null))
        assertEquals("https://instagram.com/sualoja", DadosDaLoja.enderecoDoInstagram("@sualoja"))
    }

    @Test fun telefoneEmRodape() {
        assertEquals("(51) 99999-8888", DadosDaLoja.telefoneBonito("51999998888"))
        assertEquals("(51) 99999-8888", DadosDaLoja.telefoneBonito("+55 (51) 99999-8888"))
        assertEquals("(51) 3333-4444", DadosDaLoja.telefoneBonito("5133334444"))
        assertEquals("123", DadosDaLoja.telefoneBonito("123"))
    }

    @Test fun rodapeSoComOQueExiste() {
        assertEquals("(51) 99999-8888  ·  @sualoja  ·  loja.com", DadosDaLoja.rodape("51999998888", "sualoja", "https://loja.com/"))
        assertEquals("loja.com", DadosDaLoja.rodape("", null, "https://loja.com"))
        assertEquals("", DadosDaLoja.rodape("", null, ""))
    }
}
