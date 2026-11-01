package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversasTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    @Test fun pedidosParaVoltarAUmaConversa() {
        val a = acao("Lembra do que falamos sobre investir em conjunto de Langerie, acho que vou investir mesmo")!!
        assertEquals("buscar_conversa", a.nome)
        assertEquals("investir em conjunto de Langerie", a.args["assunto"])
        assertEquals("o site da loja", acao("você lembra o que a gente conversou sobre o site da loja?")!!.args["assunto"])
        assertEquals("buscar_conversa", acao("volta na conversa sobre o Instagram")!!.nome)
        assertEquals("buscar_conversa", acao("o que combinamos sobre a Black Friday")!!.nome)
        assertEquals("buscar_conversa", acao("procura a conversa sobre fornecedor")!!.nome)
        // continua sendo memória ou lembrete
        assertTrue(ComandosMemoria.interpretar("lembra que eu gosto de rock") != null)
        assertNull(ComandosMemoria.interpretar("lembra do que falamos sobre investir em lingerie"))
        val d = Cerebro().responder("lembra do que falamos sobre investir em conjunto de lingerie, acho que vou investir mesmo")
        assertTrue(d.toString(), d is Cerebro.Decisao.Executar && d.nome == "buscar_conversa")
    }

    @Test fun buscaTolerante() {
        val consulta = BuscaDeConversas.palavras("investir em conjunto de Langerie")
        assertEquals(listOf("investir", "conjunto", "langerie"), consulta)
        val conversa = listOf(
            "Vale a pena investir em conjuntos de lingerie agora?",
            "Vale sim: os conjuntos de renda têm margem de uns 50% e vendem bem antes do Dia dos Namorados.",
        )
        val outra = listOf("Qual a previsão do tempo para amanhã?", "Amanhã faz 25 graus em Porto Alegre.")
        assertEquals(1.0, BuscaDeConversas.nota(consulta, conversa), 0.001)
        assertTrue(BuscaDeConversas.nota(consulta, outra) < 0.5)
        assertTrue(BuscaDeConversas.casa("langerie", "lingerie"))
        assertTrue(BuscaDeConversas.casa("conjunto", "conjuntos"))
        assertTrue(BuscaDeConversas.casa("promocoes", "promocao"))
        assertFalse(BuscaDeConversas.casa("gel", "mel"))
        assertFalse(BuscaDeConversas.casa("investir", "inverno"))
        assertEquals("Vale a pena investir em conjuntos de lingerie agora?", BuscaDeConversas.melhorTrecho(consulta, conversa))
        // o resumo da IA também conta
        assertTrue(BuscaDeConversas.nota(listOf("renda"), listOf("oi"), "Investimento em lingerie", "Decidimos comprar conjuntos de renda") >= 1.0)
    }

    @Test fun titulo() {
        assertEquals("Vale a pena investir em conjuntos de lingerie", BuscaDeConversas.tituloProvisorio("vale a pena investir em conjuntos de lingerie?"))
        assertTrue(BuscaDeConversas.tituloProvisorio("a".repeat(10) + " " + "b".repeat(60)).endsWith("…"))
    }

    @Test fun historicoParaAIA() {
        val h = Gemini.montarHistorico(
            listOf(
                false to "Olá! Em que posso ajudar?",
                true to "Vale a pena investir em lingerie?",
                false to "Fontes: x",
                false to "Vale sim.",
                false to "Quer que eu calcule?",
                true to "lembra do que falamos sobre lingerie",
            )
        )
        assertEquals(2, h.size)
        assertEquals("user", h[0].getString("role"))
        assertEquals("model", h[1].getString("role"))
        assertEquals("Vale sim.\nQuer que eu calcule?", h[1].getJSONArray("parts").getJSONObject(0).getString("text"))
    }
}
