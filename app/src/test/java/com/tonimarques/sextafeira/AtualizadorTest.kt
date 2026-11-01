package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
