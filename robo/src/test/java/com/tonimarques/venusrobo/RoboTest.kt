package com.tonimarques.venusrobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoboTest {
    @Test fun respostaDoSite() {
        val r = Robo.lerResposta("""{"replies":[{"message":"Oi! Como posso ajudar?","delay":3,"delayMax":8}],"humano":false}""")!!
        assertEquals(listOf("Oi! Como posso ajudar?"), r.mensagens)
        assertEquals(3, r.atrasoMin)
        assertEquals(8, r.atrasoMax)
        assertFalse(r.humano)
        assertTrue(Robo.lerResposta("""{"replies":[],"humano":true}""")!!.let { it.mensagens.isEmpty() && it.humano })
        assertNull(Robo.lerResposta("<html>erro</html>"))
    }

    @Test fun pedidoAoSite() {
        val c = Robo.corpo(Robo.WHATSAPP_BUSINESS, "Ana", "tem gel de morango?", false)
        assertEquals("com.whatsapp.w4b", c.getString("messengerPackageName"))
        assertEquals("Ana", c.getJSONObject("query").getString("sender"))
        assertFalse(c.getJSONObject("query").getBoolean("isGroup"))
    }

    @Test fun oQueEhMensagemDeCliente() {
        assertEquals("tem gel de morango?", Robo.textoDaCliente("  tem gel de morango? "))
        assertNull(Robo.textoDaCliente("📷 Foto"))
        assertNull(Robo.textoDaCliente("🎤 Áudio (0:12)"))
        assertNull(Robo.textoDaCliente("3 novas mensagens"))
        assertNull(Robo.textoDaCliente("curtiu sua mensagem"))
        assertNull(Robo.textoDaCliente(""))
        assertEquals("você curtiu o conjunto?", Robo.textoDaCliente("você curtiu o conjunto?"))
    }
}
