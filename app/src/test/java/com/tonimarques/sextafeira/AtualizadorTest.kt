package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtualizadorTest {
    @Test fun numeroDaVersao() {
        assertEquals(38, Atualizador.numeroDaVersao("1.38"))
        assertEquals(40, Atualizador.numeroDaVersao("v1.40"))
        assertNull(Atualizador.numeroDaVersao("voz-pt"))
    }

    @Test fun lePublicacao() {
        val json = JSONObject(
            """{"tag_name":"v1.41","body":"Loja: status por voz\n\nCo-Authored-By: X <x@y>\nClaude-Session: https://a\n\nBaixe o arquivo Venus.apk abaixo",
            "assets":[{"name":"Venus.apk","url":"https://api.github.com/repos/a/b/releases/assets/1","browser_download_url":"https://github.com/a/b/releases/download/v1.41/Venus.apk","size":123}]}"""
        )
        val v = Atualizador.lerVersao(json)!!
        assertEquals(41, v.numero)
        assertEquals(123L, v.tamanho)
        assertEquals("Loja: status por voz", v.notas)
        assertNull(Atualizador.lerVersao(JSONObject("""{"tag_name":"voz-pt","assets":[]}""")))
    }

    @Test fun achaORoboMaisNovo() {
        assertEquals(112, Atualizador.numeroDoRobo("robo-1.112"))
        assertNull(Atualizador.numeroDoRobo("v1.112"))
        val lista = JSONArray(
            """[{"tag_name":"v1.113","assets":[{"name":"Venus.apk","url":"u0"}]},
            {"tag_name":"robo-1.112","body":"Ícone novo","assets":[{"name":"VenusRobo.apk","url":"u1","browser_download_url":"b1","size":9}]},
            {"tag_name":"robo-1.111","assets":[{"name":"VenusRobo.apk","url":"u2"}]},
            {"tag_name":"robo-1.200","draft":true,"assets":[{"name":"VenusRobo.apk","url":"u3"}]}]"""
        )
        val r = Atualizador.ultimoRobo(lista)!!
        assertEquals(112, r.numero)
        assertEquals("u1", r.urlApi)
        assertTrue(r.ehRobo)
        assertEquals("Ícone novo", r.notas)
        assertNull(Atualizador.ultimoRobo(JSONArray("""[{"tag_name":"v1.1","assets":[]}]""")))
    }
}
