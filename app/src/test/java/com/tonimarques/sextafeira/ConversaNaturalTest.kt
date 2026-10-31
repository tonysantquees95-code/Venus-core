package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casos do teste no celular: conversa natural, cidades, idade de lugares e distância. */
class ConversaNaturalTest {

    private fun c(s: String) = ComandosLocais.normalizar(s)

    @Test fun tiraOQueESoConversa() {
        assertEquals("qual é a menor cidade da Itália", Cerebro.limparFala("muito interessante Vênus e qual é a menor cidade da Itália"))
        assertEquals("qual é a menor cidade da Itália", Cerebro.limparFala("não eu quis perguntar qual é a menor cidade da Itália"))
        assertEquals("abre o WhatsApp", Cerebro.limparFala("abre o WhatsApp, Vênus"))
        assertEquals("abre o WhatsApp", Cerebro.limparFala("beleza, abre o WhatsApp"))
        assertEquals("quanto é 2 mais 2", Cerebro.limparFala("legal e quanto é 2 mais 2"))
        for (f in listOf("boa noite", "obrigado", "legal", "certo", "o que é o planeta Vênus", "show do Gusttavo Lima quando é")) {
            assertEquals(f, Cerebro.limparFala(f))
        }
    }

    @Test fun cidades() {
        assertTrue(Saber.responder(c("qual é a menor cidade da Itália"))!!.contains("Morterone"))
        assertTrue(Saber.responder(c("Qual é a menor cidade do Brasil"))!!.contains("Serra da Saudade"))
        assertTrue(Saber.responder(c("Qual é a cidade mais populosa do Brasil"))!!.contains("São Paulo"))
        val p = Fatos.entender(c("qual a cidade mais populosa da Argentina"))!!
        assertEquals("maior_cidade", p.tipo)
        assertEquals("argentina", p.sujeito)
        val j = JSONObject(
            """{"results":{"bindings":[
              {"cLabel":{"value":"Região Metropolitana de Buenos Aires"},"p":{"value":"15000000"}},
              {"cLabel":{"value":"Buenos Aires"},"p":{"value":"3121707"}},
              {"cLabel":{"value":"Córdoba"},"p":{"value":"1329604"}},
              {"cLabel":{"value":"Rosário"},"p":{"value":"948312"}}]}}"""
        )
        assertEquals(
            "A cidade mais populosa de Argentina é Buenos Aires, com cerca de 3.121.707 habitantes. Depois vêm Córdoba e Rosário.",
            Fatos.montarMaiorCidade(j, "Argentina")
        )
    }

    @Test fun idadeDeLugares() {
        val p = Fatos.entender(c("Quantos anos existe a Roma"))!!
        assertEquals("idade", p.tipo)
        assertEquals("roma", p.sujeito)
        assertTrue(p.propriedades.contains("P571"))
        assertEquals(2777, Fatos.anosDesde("752 a.C.", 2026))
        assertEquals(472, Fatos.anosDesde("1554", 2026))
        assertEquals(472, Fatos.anosDesde("25 de janeiro de 1554", 2026))
    }

    @Test fun distancia() {
        val a = ComandosLocais.interpretar("Qual a distância da onde eu estou até Brasília")
        assertTrue("$a", a is Acao && a.nome == "distancia")
        a as Acao
        assertEquals("", a.args["origem"])
        assertEquals("Brasília", a.args["destino"])
        val b = ComandosLocais.interpretar("distância entre Porto Alegre e Caxias do Sul") as Acao
        assertEquals("Porto Alegre", b.args["origem"])
        assertEquals("Caxias do Sul", b.args["destino"])
        val km = Calculadora.distanciaKm(-30.03, -51.23, -15.79, -47.88)
        assertTrue("$km", km in 1550.0..1700.0)
        assertTrue(Calculadora.fraseDistancia("", "Brasília", 1621.4).startsWith("Daqui até Brasília são cerca de 1.620 km em linha reta."))
    }
}
