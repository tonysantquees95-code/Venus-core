package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import com.tonimarques.sextafeira.ComandosLocais.Resultado.Resposta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class VocabularioTest {
    companion object {
        @JvmStatic @BeforeClass fun carregar() {
            if (!Lexico.pronto) File("src/main/assets/palavras.txt.gz").inputStream().use { Lexico.carregar(GZIPInputStream(it)) }
        }
    }

    @Test fun dicionarioCompleto() {
        assertTrue(Lexico.tamanho > 300_000)
        for (p in listOf("charada", "Charada", "melancia", "lingerie", "conjuntos", "máquinas", "adivinhação", "vibradores", "fizéssemos", "pão")) {
            assertTrue(p, Lexico.existe(p))
        }
        for (p in listOf("dacueba", "langerie", "lanterma", "xuva")) assertFalse(p, Lexico.existe(p))
        assertTrue(Lexico.sugestoes("langerie").contains("lingerie"))
        assertTrue(Lexico.frequencia("semana") >= 6)
        assertTrue(Lexico.responderExiste("dacueba").startsWith("Não encontrei \"dacueba\""))
        assertTrue(Lexico.responderExiste("charada").startsWith("Existe sim"))
    }

    @Test fun naoCorrigePalavraQueExiste() {
        assertEquals("Me conte uma charada", Corretor.corrigir("Me conte uma charada"))
        assertEquals("liga a lanterna", Corretor.corrigir("liga a lanterma"))
    }

    @Test fun perguntaSeAPalavraExiste() {
        val r = ComandosLocais.interpretar("a palavra dacueba existe?") as Resposta
        assertTrue(r.texto, r.texto.startsWith("Não encontrei"))
        assertTrue((ComandosLocais.interpretar("charada é uma palavra?") as Resposta).texto.startsWith("Existe sim"))
        assertTrue((ComandosLocais.interpretar("langerie existe?") as Resposta).texto.contains("lingerie"))
        val deus = ComandosLocais.interpretar("Deus existe?")
        assertFalse("$deus", deus is Resposta && deus.texto.startsWith("Existe sim"))
        assertTrue(Raciocinio.precisaPensar("O que é o que é, verde por fora e vermelho por dentro?"))
    }

    @Test fun climaPorPeriodo() {
        val a = ComandosLocais.interpretar("Qual a previsão para semana que vem?") as Acao
        assertEquals("previsao_do_tempo", a.nome)
        assertEquals("semana", a.args["dia"])
        assertEquals("", a.args["cidade"])
        assertEquals("horas", (ComandosLocais.interpretar("Qual horário tem mais probabilidade de chuva?") as Acao).args["dia"])
        assertEquals("horas_amanha", (ComandosLocais.interpretar("a que horas vai chover amanhã") as Acao).args["dia"])
        val g = ComandosLocais.interpretar("vai chover no fim de semana em Gramado") as Acao
        assertEquals("fim_de_semana", g.args["dia"])
        assertEquals("Gramado", g.args["cidade"])
        assertEquals("amanha", (ComandosLocais.interpretar("previsão do tempo para amanhã") as Acao).args["dia"])
    }

    @Test fun montaSemanaEHoras() {
        val diario = JSONObject(
            """{"time":["2026-11-01","2026-11-02","2026-11-03","2026-11-04","2026-11-05","2026-11-06","2026-11-07","2026-11-08"],
            "temperature_2m_max":[28,27,25,22,24,26,29,30],"temperature_2m_min":[15,16,17,15,14,16,18,19],
            "precipitation_probability_max":[24,10,80,65,20,0,45,5],"weather_code":[0,1,63,61,2,0,80,0]}"""
        )
        val s = Web.montarSemana(diario, "Em Porto Alegre", false)
        assertTrue(s, s.startsWith("Em Porto Alegre nos próximos dias: segunda: 16 a 27 graus, céu quase limpo; terça: 17 a 25 graus, chuva moderada, 80% de chuva"))
        assertTrue(s, s.endsWith("Dias com mais chance de chuva: terça e quarta."))
        val fds = Web.montarSemana(diario, "Aí onde você está", true)
        assertTrue(fds, fds.contains("sábado: 18 a 29 graus") && fds.contains("domingo: 19 a 30 graus"))

        val horas = JSONObject().put("time", org.json.JSONArray((0..47).map { i -> "2026-11-0${1 + i / 24}T%02d:00".format(i % 24) }))
            .put("precipitation_probability", org.json.JSONArray((0..47).map { i -> if (i in 15..18) 60 + i else 10 }))
        val h = Web.montarHoras(horas, "Aí onde você está", false, "2026-11-01T04:15")
        assertEquals("Aí onde você está, hoje a maior chance de chuva é por volta das 18h (78%). Entre 15h e 19h a chance fica acima de 40%.", h)
        val seco = JSONObject().put("time", horas.getJSONArray("time")).put("precipitation_probability", org.json.JSONArray((0..47).map { 5 }))
        assertTrue(Web.montarHoras(seco, "Em X", true, "2026-11-01T04:15").startsWith("Em X, amanhã a chance de chuva é baixa"))
    }
}
