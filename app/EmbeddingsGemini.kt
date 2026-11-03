package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gera o "vetor semântico" de uma frase com o Gemini (embedContent).
 * Sem bibliotecas extras: usa só HttpURLConnection e org.json, que já vêm no Android.
 * Tudo aqui é bloqueante: chame fora da thread principal (ex.: Dispatchers.IO).
 */
object EmbeddingsGemini {
    /**
     * O text-embedding-004 foi desligado pelo Google em janeiro de 2026.
     * Se um dia este também sair do ar, troque só este nome; as frases guardadas
     * com o modelo antigo são refeitas sozinhas por Memoria.completarVetores().
     */
    const val MODELO = "gemini-embedding-2"

    private const val ENDERECO = "https://googleapis.com"
    private const val MAXIMO_DE_LETRAS = 2000

    /** O vetor da frase (já com comprimento 1), ou null se faltar chave/internet ou o Gemini recusar. */
    fun gerar(texto: String, chaveApi: String): FloatArray? {
        val limpo = texto.trim().take(MAXIMO_DE_LETRAS)
        if (limpo.isEmpty() || chaveApi.isBlank()) return null
        var conexao: HttpURLConnection? = null
        return try {
            conexao = (URL("$ENDERECO$MODELO:embedContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", chaveApi)
            }
            val corpo = JSONObject()
                .put("model", "models/$MODELO")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", limpo))))
            conexao.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            if (conexao.responseCode !in 200..299) {
                null
            } else {
                val resposta = conexao.inputStream.bufferedReader().use { it.readText() }
                val valores = JSONObject(resposta).getJSONObject("embedding").getJSONArray("values")
                normalizar(FloatArray(valores.length()) { valores.getDouble(it).toFloat() })
            }
        } catch (e: Exception) {
            null
        } finally {
            conexao?.disconnect()
        }
    }

    /** Deixa o vetor com comprimento 1 (o Gemini nem sempre devolve normalizado). */
    private fun normalizar(vetor: FloatArray): FloatArray? {
        var soma = 0.0
        for (v in vetor) soma += v.toDouble() * v.toDouble()
        if (soma == 0.0) return null
        val norma = Math.sqrt(soma).toFloat()
        return FloatArray(vetor.size) { vetor[it] / norma }
    }
}
