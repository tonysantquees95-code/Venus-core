package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
    private var modeloAtivo = this.modelo
    private var modelosReserva: List<String>? = null
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
            return if (e is IOException) {
                "Não consegui falar com o Gemini: a conexão caiu. " +
                    "Use o menu ⋮ > Diagnóstico de conexão para ver o motivo. " +
                    "(${e.javaClass.simpleName}: ${e.message.orEmpty().take(100)})"
            } else {
                "Algo deu errado: ${e.message ?: e.javaClass.simpleName}"
            }
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

        val bytes = corpo.toString().toByteArray(Charsets.UTF_8)
        var ultimaResposta: Pair<Int, JSONObject>? = null
        var ultimoErro: IOException? = null

        fun ocupado(codigo: Int) = codigo >= 500 || codigo == 429

        fun tentar(modeloUsado: String, vezes: Int): Pair<Int, JSONObject>? {
            for (tentativa in 1..vezes) {
                try {
                    val resposta = enviarRequisicao(modeloUsado, bytes)
                    if (!ocupado(resposta.first)) return resposta
                    ultimaResposta = resposta
                } catch (e: IOException) {
                    ultimoErro = e
                }
                if (tentativa < vezes) Thread.sleep(1000L * tentativa)
            }
            return null
        }

        // 1) Modelo em uso, com uma nova tentativa se a rede oscilar ou o servidor estiver ocupado.
        tentar(modeloAtivo, 2)?.let { return it }

        // 2) Modelo sobrecarregado ou sem cota: passa para outro modelo Flash disponível na chave.
        for (reserva in reservas()) {
            if (reserva == modeloAtivo) continue
            val resposta = tentar(reserva, 1) ?: continue
            if (resposta.first in 200..299) {
                modeloAtivo = reserva // segue a conversa com o modelo que respondeu
                return resposta
            }
        }
        return ultimaResposta ?: throw (ultimoErro ?: IOException("sem conexão"))
    }

    private fun reservas(): List<String> =
        modelosReserva ?: listarModelosFlash(chave).ifEmpty { RESERVA_FIXA }.also { modelosReserva = it }

    private fun enviarRequisicao(modeloUsado: String, bytes: ByteArray): Pair<Int, JSONObject> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modeloUsado:generateContent")
        val conexao = url.openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = "POST"
            conexao.connectTimeout = 20_000
            conexao.readTimeout = 90_000
            conexao.doOutput = true
            conexao.useCaches = false
            conexao.setFixedLengthStreamingMode(bytes.size)
            conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Não reaproveita conexões antigas, que o Android às vezes já fechou.
            conexao.setRequestProperty("Connection", "close")
            conexao.setRequestProperty("x-goog-api-key", chave)
            conexao.outputStream.use { it.write(bytes) }

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
                "Todos os modelos do Gemini que testei estão sobrecarregados agora (erro $codigo). " +
                    "Tente de novo em alguns minutos."
            else -> "O Gemini respondeu com erro $codigo: ${mensagem.take(200)}"
        }
    }

    companion object {
        private const val MAX_RODADAS = 8
        private const val MAX_RESERVAS = 4
        private val RESERVA_FIXA = listOf("gemini-flash-lite-latest", "gemini-2.5-flash")
        private val IGNORAR = listOf("image", "tts", "live", "audio", "embed", "exp", "computer", "robotic", "native")

        /**
         * Pergunta ao Google quais modelos Flash de texto a chave pode usar,
         * dos mais novos para os mais antigos. Lista vazia se não conseguir.
         */
        fun listarModelosFlash(chave: String): List<String> {
            var conexao: HttpURLConnection? = null
            return try {
                conexao = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                    .openConnection() as HttpURLConnection
                conexao.connectTimeout = 15_000
                conexao.readTimeout = 20_000
                conexao.setRequestProperty("Connection", "close")
                conexao.setRequestProperty("x-goog-api-key", chave)
                if (conexao.responseCode !in 200..299) return emptyList()
                val texto = conexao.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val lista = JSONObject(texto).optJSONArray("models") ?: return emptyList()

                val nomes = mutableListOf<String>()
                for (i in 0 until lista.length()) {
                    val m = lista.optJSONObject(i) ?: continue
                    val nome = m.optString("name").removePrefix("models/")
                    val metodos = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    val util = nome.startsWith("gemini") && "flash" in nome &&
                        "generateContent" in metodos && IGNORAR.none { it in nome }
                    if (util) nomes += nome
                }
                nomes.sortedWith(
                    compareBy<String> { "preview" in it }
                        .thenByDescending { versao(it) }
                        .thenBy { it.length }
                ).take(MAX_RESERVAS + 2)
            } catch (e: Exception) {
                emptyList()
            } finally {
                conexao?.disconnect()
            }
        }

        private fun versao(nome: String): Double =
            Regex("gemini-(\\d+(?:\\.\\d+)?)").find(nome)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }
}
