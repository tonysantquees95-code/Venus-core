package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.Cerebro.Decisao
import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

/** Conversa que a Vênus entendeu como clima: "cover" virava "chover" e o relato virava pedido de previsão. */
class RelatoNaoEClimaTest {
    private val relato1 = "Muito bom obrigado acabei de chegar do show no Rodrigo teazer o cover do Michael Jackson"
    private val relato2 = "O Rodrigo teasser ele é cover do Michael Jackson"

    private fun novo() = Cerebro(Random(7)) { Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 31, 21, 30) } }

    private fun naoEPrevisao(frase: String) {
        val r = ComandosLocais.interpretar(frase)
        assertFalse("\"$frase\" é conversa, não pedido de previsão (veio $r)", r is Acao && r.nome == "previsao_do_tempo")
    }

    @Test fun coverNaoViraChover() {
        assertEquals("o cover do Michael Jackson", Corretor.corrigir("o cover do Michael Jackson"))
        assertEquals("teaser do show", Corretor.corrigir("teaser do show"))
    }

    @Test fun relatoNaoPedePrevisao() {
        naoEPrevisao(relato1)
        naoEPrevisao(relato2)
        // mesmo se o reconhecimento de voz escrever "chover", relato não é previsão
        naoEPrevisao("Muito bom obrigado acabei de chegar do show no Rodrigo teazer o chover do Michael Jackson")
        naoEPrevisao("O Rodrigo teasser ele é chover do Michael Jackson")
    }

    @Test fun conversaNaoPedeLocalizacaoNemCidade() {
        for (frase in listOf(relato1, relato2)) {
            val d = novo().responder(frase)
            assertFalse("\"$frase\" não é pedido de clima, veio $d", d is Decisao.Executar && d.nome == "previsao_do_tempo")
        }
    }

    @Test fun pedidoDeClimaCurtoContinuaFuncionando() {
        val r = ComandosLocais.interpretar("vai chover amanhã em Porto Alegre")
        assertTrue("veio $r", r is Acao && r.nome == "previsao_do_tempo")
        assertEquals("Porto Alegre", (r as Acao).args["cidade"])
    }

    @Test fun correcaoDeVozContinuaFuncionando() {
        assertEquals("liga a lanterna", Corretor.corrigir("liga a lanterma"))
        assertTrue(Corretor.corrigir("qual a previzao do tempo").contains("previsao"))
        assertTrue(Corretor.corrigir("vai xover amanha").contains("chover"))
    }

    @Test fun gostoNaoViraBuscaDeProduto() {
        for (f in listOf("Adora a human Nature", "Adoro a música Human Nature do MJ", "curto muito esse cantor", "Eu amo o Michael Jackson")) {
            assertTrue("\"$f\" é comentário de gosto", Roteador.ehComentarioDeGosto(f))
        }
        for (f in listOf("quanto custa o gel", "tem estoque do gel lubrificante", "quanto vendi hoje", "adiciona 10 unidades do gel")) {
            assertFalse("\"$f\" não é comentário de gosto", Roteador.ehComentarioDeGosto(f))
        }
        assertTrue("loja_estoque" in Roteador.ACOES_DE_PRODUTO)
    }
}
