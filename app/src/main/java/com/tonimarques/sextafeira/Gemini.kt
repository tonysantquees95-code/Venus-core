package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversa com o Gemini pela API REST e executa as ferramentas que ele pedir.
 * Deve ser usado fora da thread principal (faz chamadas de rede).
 */
class Gemini(
    private val chave: String,
    modelo: String,
    private val ferramentas: Ferramentas,
) {
    private val modelo = modelo.trim().removePrefix("models/")
    private val historico = JSONArray()

    fun enviar(pedido: String): String {
        val inicio = historico.length()
        historico.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", pedido)))
        )

        try {
            repeat(MAX_RODADAS) {
                val (codigo, resposta) = chamarApi()
                if (codigo !in 200..299) {
                    desfazer(inicio)
                    return explicarErro(codigo, resposta)
                }

                val candidato = resposta.optJSONArray("candidates")?.optJSONObject(0)
                val conteudo = candidato?.optJSONObject("content")
                if (conteudo == null) {
                    desfazer(inicio)
                    return "Não consegui uma resposta para isso. Pode reformular?"
                }
                if (!conteudo.has("role")) conteudo.put("role", "model")
                // Guarda o conteúdo inteiro (inclui as assinaturas de raciocínio do Gemini).
                historico.put(conteudo)

                val partes = conteudo.optJSONArray("parts") ?: JSONArray()
                val chamadas = mutableListOf<JSONObject>()
                val texto = StringBuilder()
                for (i in 0 until partes.length()) {
                    val parte = partes.optJSONObject(i) ?: continue
                    parte.optJSONObject("functionCall")?.let { chamadas.add(it) }
                    if (parte.has("text") && !parte.optBoolean("thought", false)) {
                        texto.append(parte.optString("text"))
                    }
                }

                if (chamadas.isEmpty()) {
                    return texto.toString().trim().ifEmpty { "Feito." }
                }

                val respostas = JSONArray()
                for (chamada in chamadas) {
                    val nome = chamada.optString("name")
                    val argumentos = chamada.optJSONObject("args") ?: JSONObject()
                    val resultado = ferramentas.executar(nome, argumentos)
                    val respostaFuncao = JSONObject()
                        .put("name", nome)
                        .put("response", JSONObject().put("resultado", resultado))
                    if (chamada.has("id")) respostaFuncao.put("id", chamada.optString("id"))
                    respostas.put(JSONObject().put("functionResponse", respostaFuncao))
                }
                historico.put(JSONObject().put("role", "user").put("parts", respostas))
            }
        } catch (e: Exception) {
            desfazer(inicio)
            return "Tive um problema de conexão: ${e.message ?: e.javaClass.simpleName}"
        }
        return "Fiz várias ações seguidas e parei por segurança. Quer que eu continue?"
    }

    private fun desfazer(tamanho: Int) {
        while (historico.length() > tamanho) historico.remove(historico.length() - 1)
    }

    private fun instrucoes(): String {
        val agora = SimpleDateFormat("EEEE, dd/MM/yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        return """
            Você é a Sexta-feira, assistente pessoal no celular Android do usuário: eficiente,
            simpática e com um toque de humor leve.
            Responda sempre em português do Brasil, em 1 ou 2 frases curtas e sem markdown,
            porque a resposta pode ser falada em voz alta.
            Use as ferramentas sempre que o pedido envolver uma ação no celular; você pode
            chamar várias em sequência.
            Nunca diga que fez algo que a ferramenta não confirmou. Se o resultado disser que
            falta o usuário tocar em Enviar ou Ligar, avise isso.
            Se não houver ferramenta para o pedido, diga com honestidade o que não consegue fazer.
            Agora é $agora.
        """.trimIndent()
    }

    private fun chamarApi(): Pair<Int, JSONObject> {
        val corpo = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instrucoes())))
            )
            .put("contents", historico)
            .put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", ferramentas.declaracoes()))
            )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
        val conexao = url.openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = "POST"
            conexao.connectTimeout = 20_000
            conexao.readTimeout = 90_000
            conexao.doOutput = true
            conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexao.setRequestProperty("x-goog-api-key", chave)
            conexao.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

            val codigo = conexao.responseCode
            val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
            val texto = fluxo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(texto)
            } catch (e: Exception) {
                JSONObject().put("error", JSONObject().put("message", texto.take(300)))
            }
            return codigo to json
        } finally {
            conexao.disconnect()
        }
    }

    private fun explicarErro(codigo: Int, resposta: JSONObject): String {
        val mensagem = resposta.optJSONObject("error")?.optString("message").orEmpty()
        return when {
            mensagem.contains("API key", ignoreCase = true) || codigo == 401 || codigo == 403 ->
                "Sua chave do Gemini parece inválida. Toque nos três pontinhos e escolha Chave do Gemini."
            codigo == 404 ->
                "Não encontrei o modelo \"$modelo\". Troque em três pontinhos, Modelo do Gemini."
            codigo == 429 ->
                "Atingi o limite de uso gratuito do Gemini por agora. Tente de novo daqui a pouco."
            codigo >= 500 ->
                "Os servidores do Gemini estão instáveis agora. Tente de novo em instantes."
            else -> "O Gemini respondeu com erro $codigo: ${mensagem.take(200)}"
        }
    }

    companion object {
        private const val MAX_RODADAS = 8
    }
}
