package com.tonimarques.sextafeira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VozNeuralTest {
    @Test fun ssmlSeguro() {
        val s = VozNeural.ssml("Preço <R$ 10> & \"promo\"", "pt-BR-FranciscaNeural", VozNeural.ESTILOS.first())
        assertTrue(s.contains("<voice name='pt-BR-FranciscaNeural'>"))
        assertTrue(s.contains("rate='-6%' pitch='-6%'"))
        assertTrue(s.contains("Preço &lt;R$ 10&gt; &amp; &quot;promo&quot;"))
        assertFalse(s.contains("<R$"))
    }

    @Test fun vozesBrasileiras() {
        assertTrue(VozNeural.VOZES.all { it.id.startsWith("pt-BR-") && it.id.endsWith("Neural") })
        assertTrue(VozNeural.VOZES.first().nome == "Francisca")
    }
}

class FontesSalvasTest {
    @Test fun leFontesGuardadas() {
        val f = Web.lerFontesSalvas("Fontes: GZH (https://gauchazh.com/a); Pesquisar \"grêmio\" no Google (https://www.google.com/search?q=gremio)")
        org.junit.Assert.assertEquals(2, f.size)
        org.junit.Assert.assertEquals("GZH", f[0].titulo)
        org.junit.Assert.assertEquals("https://www.google.com/search?q=gremio", f[1].url)
    }
}
