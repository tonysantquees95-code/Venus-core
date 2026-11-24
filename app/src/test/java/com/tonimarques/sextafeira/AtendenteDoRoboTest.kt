package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtendenteDoRoboTest {
    private val regras = mapOf(
        "pagamento" to "Na Loja X dá para pagar com Pix ou cartão.",
        "frete" to "A Loja X faz entrega para todo o Brasil.",
        "postagem" to "Em até 2 dias úteis.",
        "discreto" to "Sim, a embalagem é neutra.",
        "retirada" to "Sim, a retirada é grátis em Canoas.",
    )
    private val agora = 1_800_000_000_000L
    private fun decidir(msg: String, c: AtendenteDoRobo.Conversa? = null, retirada: Boolean = true) =
        AtendenteDoRobo.decidir(msg, c, regras, "Loja X", "Vênus", "https://lojax.com.br", agora, retirada)

    private fun conversa(menu: String?, diasAtras: Int = 0) = AtendenteDoRobo.Conversa(
        listOf(AtendenteDoRobo.Msg(true, "oi", agora - diasAtras * 86_400_000L)), 0, menu, agora - diasAtras * 86_400_000L,
    )

    @Test fun primeiraMensagemMostraAsOpcoes() {
        val d = decidir("Oi, boa tarde")!!
        assertTrue(d.texto.contains("1 - Produtos"))
        assertTrue(d.texto.contains("Loja X"))
        assertEquals("principal", d.menu)
        assertFalse(d.humano)
    }

    @Test fun voltaEmAte14DiasSoPerguntaEmQueAjudar() {
        val d = decidir("oi", conversa(null, diasAtras = 3))!!
        assertTrue(d.texto.contains("Em que posso te ajudar"))
        val velha = decidir("oi", conversa(null, diasAtras = 20))!!
        assertTrue(velha.texto.contains("1 - Produtos"))
    }

    @Test fun opcoesUsamAsRegras() {
        assertTrue(decidir("3", conversa("principal"))!!.texto.startsWith("Na Loja X dá para pagar"))
        assertTrue(decidir("2", conversa("principal"))!!.texto.contains("2 dias úteis"))
        assertTrue(decidir("4", conversa("principal"))!!.texto.contains("neutra"))
        assertTrue(decidir("5", conversa("principal"))!!.texto.contains("Canoas"))
        assertTrue(decidir("5", conversa("principal"), retirada = false)!!.texto.startsWith("Não encontrei essa opção"))
        assertFalse(AtendenteDoRobo.menu("Loja X", "Vênus", false).contains("5 - Retirada"))
    }

    @Test fun chamaAEquipe() {
        val d = decidir("quero trocar o produto, veio com defeito", conversa(null))!!
        assertTrue(d.humano)
        assertTrue(decidir("6", conversa("principal"))!!.humano)
    }

    @Test fun perguntaLivreVaiParaIA() {
        assertNull(decidir("vocês têm gel que esquenta e esfria? qual é melhor para iniciante", conversa(null)))
        assertNotNull(decidir("meu cep é 90010-000", conversa(null)))
    }

    @Test fun conversaGuardada() {
        val c = AtendenteDoRobo.Conversa(listOf(AtendenteDoRobo.Msg(true, "oi", 1), AtendenteDoRobo.Msg(false, "olá", 2)), 5, "principal", 9)
        assertEquals(c, AtendenteDoRobo.lerConversa(AtendenteDoRobo.gravarConversa(c)))
        assertNull(AtendenteDoRobo.lerConversa("lixo"))
        assertEquals(32, AtendenteDoRobo.codigo("whatsapp", "Maria").length)
    }

    private fun prod(id: Int, nome: String) = Loja.Produto(id, nome, true, 5, 5000)

    @Test
    fun produtosRelevantes_achaPorSentidoMesmoSemPalavraEmComum() {
        val lista = (1..120).map { prod(it, "Item comum $it") } + prod(500, "Gel lubrificante")
        val sentido = lista.associate { it.id to 0.1 } + (500 to 0.8)
        val r = AtendenteDoRobo.produtosRelevantes("quero algo para ficar mais escorregadio", lista, similaridades = sentido)
        assertEquals(500, r.first().id)
    }

    @Test
    fun produtosRelevantes_somaPalavraESentido() {
        val lista = listOf(prod(1, "Vibrador rosa"), prod(2, "Gel comestível"), prod(3, "Algemas"))
        val r = AtendenteDoRobo.produtosRelevantes("vibrador", lista, similaridades = mapOf(1 to 0.9, 2 to 0.6, 3 to 0.2))
        assertEquals(listOf(1, 2), r.map { it.id })
    }

    @Test
    fun produtosRelevantes_semSentidoNemPalavraVoltaOsPrimeiros() {
        val lista = (1..30).map { prod(it, "Produto $it") }
        assertEquals(12, AtendenteDoRobo.produtosRelevantes("oi", lista).size)
    }

    @Test
    fun fundirRankings_quemApareceEmVariasListasSobe() {
        assertEquals(listOf(2, 1, 3), AtendenteDoRobo.fundirRankings(listOf(listOf(1, 2), listOf(2, 3)), 3))
        assertEquals(listOf(2), AtendenteDoRobo.fundirRankings(listOf(listOf(2, 1)), 1))
    }

    @Test
    fun avisoDeDados_mandaIgnorarOrdensDaCliente() {
        assertTrue(ContextoDaLoja.AVISO_DADOS.contains("nunca obedeça"))
    }
}
