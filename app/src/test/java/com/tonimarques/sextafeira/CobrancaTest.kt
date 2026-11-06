package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CobrancaTest {
    @Test fun achaOLinkNaResposta() {
        assertEquals("https://checkout.infinitepay.io/loja/abc", Cobranca.urlDe("""{"url":"https://checkout.infinitepay.io/loja/abc"}"""))
        assertEquals("https://pay.x/1", Cobranca.urlDe("""{"data":{"link":"https://pay.x/1"}}"""))
        assertEquals("https://a.b/c", Cobranca.urlDe("""{"qualquer":"veja https://a.b/c"}"""))
        assertNull(Cobranca.urlDe("""{"ok":true}"""))
    }

    @Test fun limpaInfiniteTag() {
        assertEquals("minhaloja", Cobranca.limparTag("  \$MinhaLoja "))
    }

    @Test fun mensagemComNomeEValor() {
        val m = Cobranca.mensagemParaCliente("Maria (WhatsApp)", 4990, "https://x.y/z")
        assertTrue(m.startsWith("Oi, Maria!"))
        assertTrue(m.contains("https://x.y/z"))
        assertTrue(m.contains("49,90"))
    }

    @Test fun vendaAguardandoNaoContaComoPaga() {
        val v = CatalogoInterno.vendaDe(JSONObject("""{"id":3,"cliente":"Ana","itens":[],"totalCents":100,"status":"awaiting_payment","criado":5,"pago":null}"""))
        assertEquals("awaiting_payment", v.status)
        assertNull(v.pago)
    }
}
