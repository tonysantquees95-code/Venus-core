package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTest {
    private fun ocorrencia(detalhe: String, versao: String) = Registro.Ocorrencia("ia", detalhe, "oi", 2, 0, 0, versao)

    @Test fun errosConsertadosSaemDoLog() {
        val consertos = Registro.lerConsertos(
            """
            # comentário
            ia:a inteligencia artificial esta sobrecarregada | 108 | Gemini sobrecarregado
            erro:loja_confirmar → | v1.120 | outro
            linha sem versao
            """.trimIndent()
        )
        assertEquals(2, consertos.size)
        assertEquals(108, consertos[0].versao)
        assertEquals(120, consertos[1].versao)
        assertEquals(107, Registro.numeroDaVersao("1.107"))
        assertEquals(0, Registro.numeroDaVersao("?"))

        val antigo = ocorrencia("A inteligência artificial está sobrecarregada agora (erro 503).", "1.107")
        val voltou = ocorrencia("A inteligência artificial está sobrecarregada agora (erro 503).", "1.108")
        val outro = ocorrencia("Não consegui falar com a IA", "1.100")
        val r = Registro.resolvidas(listOf(antigo, voltou, outro), consertos)
        assertEquals(1, r.size)
        assertEquals(listOf(antigo), r.values.first())
    }

    @Test fun resumoDoDiario() {
        val lista = listOf(
            Diario.Ajuste(2, 2000, "loja", "Preço do gel mudou para R$ 29,90", "voz", true),
            Diario.Ajuste(1, 1000, "instagram", "Story não publicado (sem internet)", "automatico", false),
        )
        val t = Diario.resumo(lista, "hoje")
        assertTrue(t, t.startsWith("2 registros hoje (1 loja, 1 instagram), 1 com falha"))
        assertTrue(t.endsWith("Preço do gel mudou para R$ 29,90"))
        assertEquals("Nenhum ajuste hoje.", Diario.resumo(emptyList(), "hoje"))
    }

    @Test fun comandos() {
        fun acao(f: String) = (ComandosLocais.interpretar(f) as? Acao)
        assertEquals("diario_resumo", acao("o que você mudou na loja hoje?")?.nome)
        assertEquals("semana", acao("o que foi alterado essa semana")?.args?.get("periodo"))
        assertEquals("diario_resumo", acao("log de ajustes")?.nome)
        assertEquals("log_erros", acao("quais os erros em aberto")?.nome)
        assertEquals("log_erros", acao("mostra o log de erros")?.nome)
        assertEquals("limpar_erros", acao("limpa o log de erros")?.nome)
        assertEquals("limpar_erros", acao("apagar os erros")?.nome)
    }
}
