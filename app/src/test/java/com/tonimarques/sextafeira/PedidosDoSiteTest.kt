package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedidosDoSiteTest {
    @Test fun lePedidosDoSite() {
        val json = JSONObject(
            """{"pedidos":[
              {"id":7,"tipo":"story_outro","turno":"tarde","ideiaRef":null,"ideiaTitulo":null,"quando":null,"legenda":null},
              {"id":8,"tipo":"post","turno":null,"ideiaRef":"123","ideiaTitulo":"Fantasia e Diversão","quando":"2026-11-06T22:00:00.000Z","legenda":"Olha só!"},
              {"id":0,"tipo":"post"}
            ]}""",
        )
        val p = PedidosDoSite.lerPedidos(json)
        assertEquals(2, p.size)
        assertEquals("tarde", p[0].turno)
        assertNull(p[0].ideiaRef)
        assertNull(p[0].quando)
        assertEquals("123", p[1].ideiaRef)
        assertEquals(1794002400000L, p[1].quando)
        assertEquals("Olha só!", p[1].legenda)
        assertTrue(PedidosDoSite.lerPedidos(JSONObject("{}")).isEmpty())
    }

    @Test fun dataIso() {
        assertEquals(0L, PedidosDoSite.dataIso("1970-01-01T00:00:00.000Z"))
        assertNull(PedidosDoSite.dataIso("ontem"))
    }

    @Test fun ideiaNoFormatoDoSite() {
        val i = IdeiasDePost.ler(Memoria.Item(42, "ideia_post", "Fantasia e Diversão (2026-12-14, story)", "Legenda boa", null, null, null))
        val o = PedidosDoSite.ideiaParaSite(42, i)
        assertEquals("42", o.getString("ref"))
        assertEquals("Fantasia e Diversão", o.getString("titulo"))
        assertEquals("2026-12-14", o.getString("data"))
        assertEquals("story", o.getString("formato"))
        val semData = PedidosDoSite.ideiaParaSite(1, IdeiasDePost.ler(Memoria.Item(1, "ideia_post", "Sem data", "", null, null, null)))
        assertTrue(semData.isNull("data"))
    }

    @Test fun descricao() {
        val p = PedidosDoSite.Pedido(1, "story_outro", "manha", null, null, null, null)
        assertEquals("outro story da manhã", PedidosDoSite.descricao(p))
    }
}

class CanalDeAtualizacaoTest {
    @Test fun leOCanalDoSite() {
        val v = Atualizador.lerCanal(JSONObject("""{"numero":160,"tag":"v1.160","notas":"Novidades","tamanho":123,"sha256":"ABC","url":"https://objects.githubusercontent.com/x?sig=1"}"""), robo = false)!!
        assertEquals(160, v.numero)
        assertEquals("abc", v.sha256)
        assertTrue(v.viaSite)
        assertTrue(!v.ehRobo)
        val r = Atualizador.lerCanal(JSONObject("""{"numero":150,"url":"https://x"}"""), robo = true)!!
        assertTrue(r.ehRobo)
        assertNull(Atualizador.lerCanal(JSONObject("""{"numero":0,"url":"https://x"}"""), false))
        assertNull(Atualizador.lerCanal(JSONObject("""{"numero":3,"url":"http://x"}"""), false))
    }
}
