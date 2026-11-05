package com.tonimarques.venusrobo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespostasTest {
    @Test fun modelos() {
        assertEquals(4, Respostas.MODELOS.count { it.faq })
        assertEquals(Respostas.MODELOS.size, Respostas.MODELOS.map { it.id }.toSet().size)
        assertTrue(Respostas.MODELOS.all { it.atalho.matches(Regex("[a-z]+")) })
        assertTrue(Respostas.MODELOS.filter { it.faq }.all { it.pergunta.length <= 80 })
    }

    @Test fun juntaAsMensagens() {
        assertEquals("Oi! Somos discretas.\n\nA embalagem não diz o que tem dentro.", Respostas.juntar(listOf(" Oi! Somos **discretas**. ", "", "A embalagem não diz o que tem dentro.")))
        assertNull(Respostas.juntar(listOf(" ", "")))
        val longa = Respostas.juntar(listOf("palavra ".repeat(300)))!!
        assertTrue(longa.length <= Respostas.MAXIMO + 1)
        assertTrue(longa.endsWith("…"))
    }

    @Test fun leAsSalvas() {
        val s = Respostas.lerSalvas("""{"frete":{"texto":"Calculamos no site.","editada":true},"oi":{"texto":"","editada":false},"troca":{"texto":"7 dias.","conferir":true}}""")
        assertEquals(setOf("frete", "troca"), s.keys)
        assertTrue(s["frete"]!!.editada)
        assertTrue(s["troca"]!!.conferir)
        assertTrue(Respostas.lerSalvas("quebrado").isEmpty())
    }

    @Test fun tudoNumTexto() {
        val faq = Respostas.MODELOS.first { it.faq }
        val salva = Respostas.MODELOS.first { !it.faq }
        val t = Respostas.tudoEmTexto(listOf(
            Respostas.Pronta(faq, "Sim, embalagem discreta.", false, false),
            Respostas.Pronta(salva, "Oi! Como posso ajudar?", false, false),
            Respostas.Pronta(Respostas.MODELOS.last(), "", false, false),
        ))
        assertTrue(t.contains("Pergunta: ${faq.pergunta}\nResposta: Sim, embalagem discreta."))
        assertTrue(t.contains("${salva.atalho} →\nOi! Como posso ajudar?"))
        assertTrue(!t.contains(Respostas.MODELOS.last().atalho + " →"))
    }

    @Test fun regrasDaVenus() {
        val m = RegrasDaVenus.lerRespostas("""[{"id":"frete","pergunta":"Como funciona o frete?","resposta":"Por PAC ou SEDEX."},{"id":"x","resposta":""}]""")
        assertEquals(mapOf("frete" to "Por PAC ou SEDEX."), m)
        assertTrue(RegrasDaVenus.lerRespostas("lixo").isEmpty())
        assertTrue(Respostas.MODELOS.map { it.id }.containsAll(listOf("pagamento", "frete", "retirada", "discreto", "troca", "horario")))
    }

    @Test fun reconheceAPropriaResposta() {
        val minha = "Temos gel excitante masculino por R$ 26,50 no Pix (Volumão). Quer o link de algum?"
        assertTrue(Robo.mesmoTexto(minha, "Temos gel excitante masculino por R$ 26,50 no Pix (Volumão). Quer o link de algum?"))
        assertTrue(Robo.mesmoTexto(minha, "Temos gel excitante masculino por R$ 26,50…"))
        assertTrue(Robo.mesmoTexto("Oi, tudo bem! Em que posso ajudar? 🌸", "oi tudo bem em que posso ajudar"))
        assertTrue(!Robo.mesmoTexto(minha, "Promoções"))
        assertTrue(!Robo.mesmoTexto("Oi", "Oi, quero o link"))
    }
}
