package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegundaIATest {

    private fun parte(vararg pares: Pair<String, Any>) = JSONObject().apply { pares.forEach { (k, v) -> put(k, v) } }

    @Test fun converteConversaComFerramentas() {
        val gemini = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(parte("text" to "Você é a Vênus."))))
            .put(
                "contents", JSONArray()
                    .put(JSONObject().put("role", "user").put("parts", JSONArray().put(parte("text" to "liga a lanterna"))))
                    .put(JSONObject().put("role", "model").put("parts", JSONArray().put(parte("functionCall" to JSONObject().put("name", "lanterna").put("args", JSONObject().put("ligar", true))))))
                    .put(JSONObject().put("role", "user").put("parts", JSONArray().put(parte("functionResponse" to JSONObject().put("name", "lanterna").put("response", JSONObject().put("ok", true))))))
            )
            .put(
                "tools", JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations", JSONArray().put(
                            JSONObject().put("name", "lanterna").put("description", "liga ou desliga").put(
                                "parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("ligar", JSONObject().put("type", "BOOLEAN")))
                            )
                        )
                    )
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
        val o = SegundaIA.paraOpenAI(gemini)!!
        val msgs = o.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        val chamada = msgs.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("lanterna", chamada.getJSONObject("function").getString("name"))
        assertEquals("tool", msgs.getJSONObject(3).getString("role"))
        assertEquals(chamada.getString("id"), msgs.getJSONObject(3).getString("tool_call_id"))
        val params = o.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertEquals("boolean", params.getJSONObject("properties").getJSONObject("ligar").getString("type"))
        assertEquals("json_object", o.getJSONObject("response_format").getString("type"))
    }

    @Test fun fotoNaoVaiParaAReserva() {
        val g = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(parte("inline_data" to JSONObject())))))
        assertNull(SegundaIA.paraOpenAI(g))
    }

    @Test fun respostaVoltaNoFormatoDoGemini() {
        val openai = JSONObject("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"abrir_app","arguments":"{\"nome\":\"WhatsApp\"}"}}]}}]}""")
        val g = SegundaIA.paraGemini(openai)!!
        val p = g.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0)
        assertEquals("abrir_app", p.getJSONObject("functionCall").getString("name"))
        assertEquals("WhatsApp", p.getJSONObject("functionCall").getJSONObject("args").getString("nome"))
        assertTrue(p.has("thoughtSignature"))
        val texto = SegundaIA.paraGemini(JSONObject("""{"choices":[{"message":{"content":"Oi, Toni!"}}]}"""))!!
        assertEquals("Oi, Toni!", SegundaIA.textoDe(texto))
    }
}

class PesquisaReservaTest {
    @Test fun limpaCitacoesEAchaFontes() {
        assertEquals("O Grêmio venceu por 2 a 1.", SegundaIA.limparPesquisa("O **Grêmio** venceu por 2 a 1【3†L4-L9】."))
        val f = SegundaIA.fontesDe(
            org.json.JSONArray("""[{"type":"browser_search","output":"{\"results\":[{\"title\":\"GZH\",\"url\":\"https://gauchazh.com/x\"}]}","search_results":{"results":[{"title":"Globo","url":"https://ge.globo.com/y"}]}}]""")
        )
        assertEquals(listOf("https://ge.globo.com/y", "https://gauchazh.com/x").sorted(), f.map { it.url }.sorted())
    }
}
