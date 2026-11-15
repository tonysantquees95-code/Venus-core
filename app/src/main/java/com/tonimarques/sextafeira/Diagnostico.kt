package com.tonimarques.sextafeira

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Testa, passo a passo, o caminho entre o celular e o Gemini e devolve um relatório legível.
 * Deve ser chamado fora da thread principal.
 */
object Diagnostico {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    fun executar(contexto: Context, chave: String, modelo: String, ferramentas: Ferramentas): String {
        val linhas = mutableListOf<String>()
        linhas += "Diagnóstico de conexão"
        linhas += rede(contexto)
        linhas += "Chave: ${chave.length} caracteres"

        linhas += testar("1. Internet (Google)", "https://www.google.com/generate_204", "GET", null, null)
        linhas += testar("2. Servidor do Gemini + chave", "$BASE/models?pageSize=1", "GET", chave, null)

        val flash = Gemini.listarModelosFlash(chave)
        linhas += "Modelos Flash disponíveis: " + flash.joinToString(", ").ifEmpty { "não consegui listar" }

        val simples = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "Responda apenas: ok")))
            )
        )
        linhas += testar("3. Pergunta simples ao $modelo", "$BASE/models/$modelo:generateContent", "POST", chave, simples)

        val comFerramentas = JSONObject(simples.toString()).put(
            "tools",
            JSONArray().put(JSONObject().put("functionDeclarations", ferramentas.declaracoes()))
        )
        linhas += testar("4. Pergunta com as ferramentas", "$BASE/models/$modelo:generateContent", "POST", chave, comFerramentas)

        flash.filter { it != modelo }.take(3).forEachIndexed { i, reserva ->
            linhas += testar("${5 + i}. Reserva $reserva", "$BASE/models/$reserva:generateContent", "POST", chave, comFerramentas)
        }

        linhas += Tempos.relatorio()
        return linhas.joinToString("\n\n")
    }

    private fun rede(contexto: Context): String {
        return try {
            val cm = contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Rede: nenhuma rede ativa"
            val tipos = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) tipos += "Wi-Fi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) tipos += "dados móveis"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) tipos += "VPN ATIVA"
            val economia = when (cm.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "economia de dados LIGADA"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "economia de dados ligada (app liberado)"
                else -> "economia de dados desligada"
            }
            val validada = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "com internet" else "SEM internet validada"
            "Rede: ${tipos.joinToString(" + ").ifEmpty { "outra" }}, $validada, $economia"
        } catch (e: Exception) {
            "Rede: não consegui verificar (${e.javaClass.simpleName})"
        }
    }

    private fun testar(nome: String, endereco: String, metodo: String, chave: String?, corpo: JSONObject?): String {
        val inicio = System.currentTimeMillis()
        var conexao: HttpURLConnection? = null
        return try {
            conexao = URL(endereco).openConnection() as HttpURLConnection
            conexao.requestMethod = metodo
            conexao.connectTimeout = 15_000
            conexao.readTimeout = 60_000
            conexao.useCaches = false
            conexao.setRequestProperty("Connection", "close")
            if (chave != null) conexao.setRequestProperty("x-goog-api-key", chave)
            if (corpo != null) {
                val bytes = corpo.toString().toByteArray(Charsets.UTF_8)
                conexao.doOutput = true
                conexao.setFixedLengthStreamingMode(bytes.size)
                conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conexao.outputStream.use { it.write(bytes) }
            }
            val codigo = conexao.responseCode
            val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
            val texto = fluxo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val tempo = System.currentTimeMillis() - inicio
            val ok = codigo in 200..299
            val detalhe = if (ok) resumoSucesso(texto) else resumoErro(texto)
            "${if (ok) "✅" else "❌"} $nome: HTTP $codigo em ${tempo} ms" +
                if (detalhe.isNotBlank()) "\n   $detalhe" else ""
        } catch (e: Exception) {
            val tempo = System.currentTimeMillis() - inicio
            "❌ $nome: falhou após ${tempo} ms\n   ${e.javaClass.simpleName}: ${e.message.orEmpty().take(150)}"
        } finally {
            conexao?.disconnect()
        }
    }

    private fun resumoSucesso(texto: String): String = try {
        val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        if (partes == null) "" else {
            val sb = StringBuilder()
            for (i in 0 until partes.length()) {
                val p = partes.optJSONObject(i) ?: continue
                if (p.has("text")) sb.append(p.optString("text"))
                p.optJSONObject("functionCall")?.let { sb.append("[chamou ${it.optString("name")}]") }
            }
            "Resposta: " + sb.toString().trim().take(80)
        }
    } catch (e: Exception) {
        ""
    }

    private fun resumoErro(texto: String): String = try {
        "Erro: " + JSONObject(texto).optJSONObject("error")?.optString("message").orEmpty().take(200)
    } catch (e: Exception) {
        "Erro: " + texto.take(200)
    }
}
