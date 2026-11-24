package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PesquisaDeAssinaturaTest {
    private fun dia(d: Int, h: Int = 10) = Calendar.getInstance().apply { clear(); set(2026, 10, d, h, 0, 0) }

    @Test fun soAPartirDoSegundoDiaDoTeste() {
        assertFalse(PesquisaDeAssinatura.deveMostrar("teste", 1, null, 0L, dia(24)))
        assertTrue(PesquisaDeAssinatura.deveMostrar("teste", 2, null, 0L, dia(24)))
    }

    @Test fun quemPagaOuNaoSabeNaoVe() {
        assertFalse(PesquisaDeAssinatura.deveMostrar("pago", -1, null, 0L, dia(24)))
        assertFalse(PesquisaDeAssinatura.deveMostrar("", -1, null, 0L, dia(24)))
        assertTrue(PesquisaDeAssinatura.deveMostrar("vencido", -1, null, 0L, dia(24)))
    }

    @Test fun fechouSemResponderVoltaNoDiaSeguinte() {
        val hoje = dia(24)
        assertFalse(PesquisaDeAssinatura.deveMostrar("teste", 3, PesquisaDeAssinatura.chaveDoDia(hoje), 0L, hoje))
        assertTrue(PesquisaDeAssinatura.deveMostrar("teste", 3, PesquisaDeAssinatura.chaveDoDia(hoje), 0L, dia(25)))
    }

    @Test fun respondeuEsperaUmaSemana() {
        val resp = dia(24).timeInMillis
        assertFalse(PesquisaDeAssinatura.deveMostrar("vencido", -1, null, resp, dia(30)))
        assertTrue(PesquisaDeAssinatura.deveMostrar("vencido", -1, null, resp, Calendar.getInstance().apply { clear(); set(2026, 11, 1, 10, 0, 0) }))
    }

    @Test fun respeitaOHorario() {
        assertFalse(PesquisaDeAssinatura.deveMostrar("teste", 3, null, 0L, dia(24, 6)))
        assertFalse(PesquisaDeAssinatura.deveMostrar("teste", 3, null, 0L, dia(24, 22)))
    }

    @Test fun leAFaseELicencaDoServidor() {
        assertEquals("teste", PesquisaDeAssinatura.faseDaLicenca(JSONObject("""{"acesso":true,"emTeste":true,"diasDeTesteRestantes":5}""")))
        assertEquals(2, PesquisaDeAssinatura.diasUsados(JSONObject("""{"acesso":true,"emTeste":true,"diasDeTesteRestantes":5}""")))
        assertEquals("pago", PesquisaDeAssinatura.faseDaLicenca(JSONObject("""{"acesso":true,"emTeste":false}""")))
        assertEquals("vencido", PesquisaDeAssinatura.faseDaLicenca(JSONObject("""{"acesso":false}""")))
        assertEquals("", PesquisaDeAssinatura.faseDaLicenca(null))
        assertEquals(-1, PesquisaDeAssinatura.diasUsados(JSONObject("""{"acesso":true,"emTeste":false}""")))
    }

    @Test fun perguntasEMotivosPorDecisao() {
        assertTrue(PesquisaDeAssinatura.perguntaDoMotivo("vai").contains("trouxe"))
        assertTrue(PesquisaDeAssinatura.perguntaDoMotivo("nao").contains("Por que"))
        assertTrue(PesquisaDeAssinatura.motivos("vai").isEmpty())
        assertEquals("preco", PesquisaDeAssinatura.motivos("nao").first().first)
        assertEquals("testar_mais", PesquisaDeAssinatura.motivos("talvez").first().first)
    }

    @Test fun corpoVaiComOsCamposDoServidor() {
        val c = PesquisaDeAssinatura.corpo("nao", "preco", "  caro  ", "teste", 3, "1.250")
        assertEquals("assinatura", c.getString("tipo"))
        assertEquals("caro", c.getString("texto"))
        assertEquals("preco", c.getJSONObject("respostas").getString("motivo"))
        assertEquals(3, c.getJSONObject("respostas").getInt("dias_de_uso"))
        assertFalse(PesquisaDeAssinatura.corpo("vai", "", "", "vencido", -1, "1").getJSONObject("respostas").has("dias_de_uso"))
    }
}
