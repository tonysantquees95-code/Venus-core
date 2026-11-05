package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfilESegurancaTest {
    @Test fun cidadeEEstado() {
        assertEquals("Porto Alegre" to "RS", Perfil.separarCidade("Porto Alegre, RS"))
        assertEquals("Caxias do Sul" to "RS", Perfil.separarCidade("Caxias do Sul - rs"))
        assertEquals("Curitiba" to "", Perfil.separarCidade("Curitiba"))
    }

    @Test fun perfilPadraoEhODaPrimeiraLoja() {
        assertEquals("Toni", Perfil.dono)
        assertTrue(Marca.personalidade.contains(Perfil.loja))
    }

    @Test fun impressaoDigitalDoApk() {
        val asset = JSONObject().put("digest", "sha256:ABC123")
        assertEquals("abc123", Atualizador.sha256De(asset))
        assertEquals("", Atualizador.sha256De(JSONObject()))
        assertTrue(Atualizador.conferir("", "qualquer"))
        assertTrue(Atualizador.conferir("abc123", "ABC123"))
        assertFalse(Atualizador.conferir("abc123", "abc124"))
    }

    @Test fun cofreSemAndroidGuardaEmTexto() {
        // nos testes não existe o chip de segurança: o cofre não quebra, só não cifra
        assertEquals("segredo", Cofre.selar("segredo"))
        assertEquals("segredo", Cofre.abrir("segredo"))
        assertEquals("", Cofre.abrir(Cofre.MARCA + "lixo"))
    }
}
