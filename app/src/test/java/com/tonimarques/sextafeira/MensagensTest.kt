package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MensagensTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    @Test fun comandos() {
        assertEquals("ler_mensagens", acao("tem mensagem nova?")!!.nome)
        assertEquals("ler_mensagens", acao("lê minhas mensagens")!!.nome)
        val de = acao("o que a Natália mandou?")!!
        assertEquals("ler_mensagens", de.nome)
        assertEquals("Natália", de.args["de"])
        val r = acao("responde para a Natália que chego às 8")!!
        assertEquals("responder_mensagem", r.nome)
        assertEquals("Natália", r.args["para"])
        assertEquals("chego às 8", r.args["texto"])
        val r2 = acao("responde que já estou chegando")!!
        assertEquals("", r2.args["para"])
        assertEquals("já estou chegando", r2.args["texto"])
        assertNotEquals("ler_mensagens", acao("manda uma mensagem nova para a Natália que estou chegando")?.nome)
    }

    @Test fun leitura() {
        val conversas = listOf(
            LeitorDeMensagens.Conversa("Natália", listOf("Chego às 8", "Compra pão"), false, null),
            LeitorDeMensagens.Conversa("Família", listOf("Ana: Domingo tem almoço"), true, null),
        )
        val t = LeitorDeMensagens.montarLeitura(conversas, "")
        assertTrue(t, t.startsWith("2 conversas com mensagens novas. Natália: Chego às 8. Compra pão. Família (grupo): Ana: Domingo tem almoço."))
        assertTrue(LeitorDeMensagens.montarLeitura(conversas, "natalia").startsWith("Natália mandou: Chego às 8. Compra pão."))
        assertEquals("Família", LeitorDeMensagens.achar(conversas, "a família")!!.nome)
        assertEquals("Natália", LeitorDeMensagens.limparNome("Natália (3 mensagens)"))
        assertEquals("Nenhuma mensagem nova no WhatsApp.", LeitorDeMensagens.montarLeitura(emptyList(), ""))
    }
}
