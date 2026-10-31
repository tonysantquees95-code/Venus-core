package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosMemoria.Pedido
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MemoriaEWebTest {

    @Test fun pedidosDeMemoria() {
        assertEquals(Pedido.Lembrar("eu gosto de rock", "fato"), ComandosMemoria.interpretar("lembra que eu gosto de rock"))
        assertEquals(Pedido.Definir("apelido", "Carlos"), ComandosMemoria.interpretar("meu nome é carlos"))
        assertEquals(Pedido.Definir("apelido", "Patrão"), ComandosMemoria.interpretar("Sexta-feira, me chama de patrão"))
        assertEquals(Pedido.Definir("cidade", "Porto Alegre"), ComandosMemoria.interpretar("minha cidade é porto alegre"))
        assertEquals(Pedido.Definir("cidade", "Caxias do Sul"), ComandosMemoria.interpretar("eu moro em caxias do sul"))
        assertEquals(
            Pedido.Definir("favorito:musica", "Evidências"),
            ComandosMemoria.interpretar("minha música favorita é Evidências")
        )
        assertEquals(Pedido.Listar, ComandosMemoria.interpretar("o que você sabe sobre mim?"))
        assertEquals(Pedido.Esquecer("eu gosto de rock"), ComandosMemoria.interpretar("esquece que eu gosto de rock"))
        assertEquals(Pedido.ApagarTudo, ComandosMemoria.interpretar("apaga todas as memórias"))
        assertEquals(Pedido.Lembrar("eu adoro pizza", "gosto"), ComandosMemoria.interpretar("eu adoro pizza"))
        assertNull(ComandosMemoria.interpretar("eu amo você"))
        assertNull(ComandosMemoria.interpretar("liga a lanterna"))
        assertNull(ComandosMemoria.interpretar("esquece tudo"))
    }

    @Test fun clima() {
        fun clima(frase: String): ComandosLocais.Resultado.Acao {
            val r = ComandosLocais.interpretar(frase)
            assertTrue("\"$frase\" deveria pedir a previsão, veio $r", r is ComandosLocais.Resultado.Acao)
            r as ComandosLocais.Resultado.Acao
            assertEquals("previsao_do_tempo", r.nome)
            return r
        }
        assertEquals(mapOf("cidade" to "", "dia" to "hoje"), clima("quantos graus faz hoje?").args)
        assertEquals(mapOf("cidade" to "Porto Alegre", "dia" to "amanha"), clima("vai chover amanhã em Porto Alegre").args)
        assertEquals("Gramado", clima("previsão do tempo de Gramado").args["cidade"])
        assertEquals("", clima("como está o tempo lá fora").args["cidade"])
        assertEquals("", clima("está frio hoje?").args["cidade"])
    }

    @Test fun previsaoMontada() {
        val json = JSONObject(
            """{"current":{"temperature_2m":16.4,"apparent_temperature":15.2,"weather_code":2},
               "daily":{"temperature_2m_max":[22.1,24],"temperature_2m_min":[12.6,14],
               "precipitation_probability_max":[30,80],"weather_code":[3,61]}}"""
        )
        assertEquals(
            "Em Porto Alegre agora faz 16 graus, sensação de 15, parcialmente nublado. " +
                "Hoje a máxima é de 22 e a mínima de 13, com 30% de chance de chuva.",
            Web.montarPrevisao(json, "Porto Alegre", "hoje")
        )
        assertEquals(
            "Aí onde você está, amanhã: com chuva fraca, máxima de 24 e mínima de 14 graus, com 80% de chance de chuva.",
            Web.montarPrevisao(json, "", "amanha")
        )
    }

    @Test fun perguntasEnciclopedicas() {
        assertEquals("buraco negro", Web.assuntoEnciclopedico("o que é um buraco negro"))
        assertEquals("Santos Dumont", Web.assuntoEnciclopedico("quem foi Santos Dumont?"))
        assertEquals("Revolução Farroupilha", Web.assuntoEnciclopedico("me fala sobre a Revolução Farroupilha"))
        assertNull(Web.assuntoEnciclopedico("abre o WhatsApp"))
        assertEquals("Primeira frase. Segunda frase.", Web.primeirasFrases("Primeira frase. Segunda frase. Terceira.", 2))
    }

    @Test fun topicos() {
        assertEquals("Compras", Topicos.classificar("essa loja é confiável?"))
        assertEquals("Música", Topicos.classificar("toca rock", "tocar_musica"))
        assertEquals("Clima", Topicos.classificar("vai chover?", "previsao_do_tempo"))
        assertEquals("Conversa", Topicos.classificar("me conta uma piada"))
    }

    @Test fun pesquisaPedeResposta() {
        val r = ComandosLocais.interpretar("pesquisa se a loja Exemplo é boa")
        assertTrue(r is ComandosLocais.Resultado.Acao && r.nome == "pesquisar_na_web")
    }

    @Test fun musicaFavoritaUsaAMemoria() {
        assertNull(ComandosLocais.interpretar("toca minha música favorita"))
        val c = Cerebro(Random(3))
        val d = c.responder("toca minha música favorita")
        assertTrue("veio $d", d is Cerebro.Decisao.Especial && d.tipo == "musica_favorita")
    }

    @Test fun aprendeComAConfirmacao() {
        val aprendidas = mutableListOf<Pair<String, String>>()
        val c = Cerebro(Random(3))
        c.aoAprender = { frase, id -> aprendidas += frase to id }
        val frase = "preciso enxergar melhor no escuro com uma luz amarela"
        assertEquals(Cerebro.Decisao.Incerto(true), c.responder(frase))
        c.usarPalpite()
        assertTrue(c.responder("sim") is Cerebro.Decisao.Executar)
        assertEquals("lanterna_ligar", aprendidas.single().second)

        // Da próxima vez, entende direto.
        val d = c.responder(frase)
        assertTrue("veio $d", d is Cerebro.Decisao.Executar && d.nome == "lanterna")

        // E um cérebro novo, carregando o que foi salvo, também.
        val outro = Cerebro(Random(3))
        outro.carregarAprendidas(aprendidas)
        assertTrue(outro.responder(frase) is Cerebro.Decisao.Executar)
    }

    @Test fun apelidoNasRespostas() {
        val c = Cerebro(Random(1))
        c.apelido = "Carlos"
        val r = c.responder("quem te criou")
        assertTrue("veio $r", r is Cerebro.Decisao.Responder && !r.texto.contains("chefe"))
    }
}
