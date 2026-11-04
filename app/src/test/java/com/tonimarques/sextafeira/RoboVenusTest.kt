package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoboVenusTest {
    @Test fun comandos() {
        fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao
        assertEquals("robo_resumo", acao("como está o robô?")?.nome)
        assertEquals("robo_resumo", acao("quantos atendimentos hoje")?.nome)
        assertEquals("robo_resumo", acao("tem cliente esperando?")?.nome)
        assertEquals(false, acao("desliga o robô")?.args?.get("ligado"))
        assertEquals(true, acao("liga o robozinho")?.args?.get("ligado"))
        assertEquals(false, acao("pausa o atendimento automático")?.args?.get("ligado"))
    }

    @Test fun resumo() {
        val agora = 1_800_000_000_000L
        val o = JSONObject(
            """{"ligado":true,"hoje":{"atendimentos":12,"clientes":5,"passadas":1},
               "esperando":[{"canal":"whatsapp","ultimaMensagem":"meu pedido não chegou","quando":${agora - 25 * 60_000}}]}"""
        )
        val t = Loja.montarRobo(o, agora)
        assertTrue(t, t.startsWith("O robô está ligado. Hoje respondeu 12 mensagens de 5 clientes e passou 1 para você."))
        assertTrue(t, t.contains("1 cliente esperando você: Whatsapp (há 25 min): “meu pedido não chegou”."))
        assertTrue(Loja.montarRobo(JSONObject("""{"ligado":false}""")).contains("desligado"))
    }
}
