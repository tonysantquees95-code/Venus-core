package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxoDaIATest {
    private fun sse(vararg eventos: String) = (eventos.joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n").byteInputStream()

    @Test
    fun textoEmPedacosViraUmaRespostaSoEAvisaAoPoucoPoucos() {
        val vistos = mutableListOf<String>()
        val r = SegundaIA.lerFluxo(
            sse(
                """{"choices":[{"delta":{"role":"assistant","content":"Oi"}}]}""",
                """{"choices":[{"delta":{"content":", tudo"}}]}""",
                """{"choices":[{"delta":{"content":" bem?"},"finish_reason":"stop"}]}""",
            ),
        ) { vistos += it }
        assertEquals("Oi, tudo bem?", r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertTrue(vistos.isNotEmpty())
        assertEquals("Oi", vistos.first()) // o primeiro pedaço aparece na hora
        assertTrue("Oi, tudo bem?".startsWith(vistos.last()))
    }

    @Test
    fun chamadaDeFerramentaEmPedacosNaoVaiParaATela() {
        val vistos = mutableListOf<String>()
        val r = SegundaIA.lerFluxo(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"abrir_app","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"app\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"whatsapp\"}"}}]}}]}""",
            ),
        ) { vistos += it }
        assertTrue(vistos.isEmpty())
        val msg = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val f = msg.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
        assertEquals("abrir_app", f.getString("name"))
        assertEquals("""{"app":"whatsapp"}""", f.getString("arguments"))
        // e o resto do app entende o resultado como de qualquer resposta
        val g = SegundaIA.paraGemini(r)
        assertNotNull(g)
        val parte = g!!.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0)
        assertEquals("whatsapp", parte.getJSONObject("functionCall").getJSONObject("args").getString("app"))
    }

    @Test
    fun linhasEstranhasEFluxoVazioNaoQuebram() {
        val r = SegundaIA.lerFluxo(": ping\n\ndata: {lixo\n\ndata: [DONE]\n\n".byteInputStream()) { }
        assertEquals(null, SegundaIA.paraGemini(r)) // sem texto nem ferramenta: conta como falha e passa para a próxima IA
    }
}
