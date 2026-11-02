package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeloReservaTest {
    private fun erro(msg: String, status: String = "") = JSONObject().put("error", JSONObject().put("message", msg).put("status", status))

    @Test fun trocaParaOFlashQuandoSobrecarrega() {
        assertTrue(Gemini.sobrecarregado(429, null))
        assertTrue(Gemini.sobrecarregado(503, null))
        assertTrue(Gemini.sobrecarregado(500, null))
        assertTrue(Gemini.sobrecarregado(400, erro("The model is overloaded. Please try again later.")))
        assertTrue(Gemini.sobrecarregado(400, erro("x", "UNAVAILABLE")))
        assertTrue(Gemini.sobrecarregado(403, erro("Error 503 from backend")))
        assertTrue(Gemini.sobrecarregado(400, erro("Quota exceeded", "RESOURCE_EXHAUSTED")))
        assertFalse(Gemini.sobrecarregado(200, erro("overloaded")))
        assertFalse(Gemini.sobrecarregado(400, erro("Invalid argument", "INVALID_ARGUMENT")))
        assertFalse(Gemini.sobrecarregado(401, erro("API key not valid")))
    }
}
