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
    /** O que a Sexta-feira sabe sobre o usuário (memórias), incluído nas instruções. */
    private val contexto: () -> String = { "" },
) {
    private val modelo = modelo.trim().removePrefix("models/")
    private var modeloAtivo = this.modelo
    private var modelosReserva: List<String>? = null
    private val historico = JSONArray()

    /** [chamadas]: ferramentas que a IA usou para responder (nome e argumentos), para a Vênus aprender. */
    data class RespostaIA(val ok: Boolean, val texto: String, val chamadas: List<Pair<String, JSONObject>> = emptyList())

    fun enviar(pedido: String): RespostaIA {
        val inicio = historico.length()
        val usadas = mutableListOf<Pair<String, JSONObject>>()
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
                    return RespostaIA(false, explicarErro(codigo, resposta))
                }

                val candidato = resposta.optJSONArray("candidates")?.optJSONObject(0)
                val conteudo = candidato?.optJSONObject("content")
                if (conteudo == null) {
                    desfazer(inicio)
                    return RespostaIA(false, "Não consegui uma resposta para isso. Pode reformular?")
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
                    return RespostaIA(true, texto.toString().trim().ifEmpty { "Feito." }, usadas)
                }

                val respostas = JSONArray()
                for (chamada in chamadas) {
                    val nome = chamada.optString("name")
                    val argumentos = chamada.optJSONObject("args") ?: JSONObject()
                    usadas += nome to argumentos
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
            return RespostaIA(
                false,
                if (e is IOException) "Não consegui falar com a inteligência artificial: a conexão caiu."
                else "Algo deu errado: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        return RespostaIA(true, "Fiz várias ações seguidas e parei por segurança. Quer que eu continue?")
    }

    private fun desfazer(tamanho: Int) {
        while (historico.length() > tamanho) historico.remove(historico.length() - 1)
    }

    private fun instrucoes(): String {
        val agora = SimpleDateFormat("EEEE, dd/MM/yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        return """
            ${Marca.personalidade}
            Responda sempre em português do Brasil, de forma curta (até 3 frases) e sem markdown,
            porque a resposta pode ser falada em voz alta.
            Para informações atuais (clima, notícias, avaliações de lojas, preços, horários, resultados),
            use previsao_do_tempo ou buscar_na_internet e responda com o que encontrou, em vez de abrir o navegador.
            Para "o que é" e "quem foi", pode usar resumo_wikipedia.
            Quando o usuário contar algo duradouro sobre ele (gostos, rotina, pessoas, planos), use salvar_memoria.
            Use o que você sabe sobre ele para personalizar as respostas, sem repetir isso à toa.
            Se a busca na internet não estiver disponível e a pergunta for sobre algo atual (lançamentos,
            preços, notícias), responda com o que sabe, mas avise em poucas palavras que a informação pode
            estar desatualizada.
            Use as ferramentas sempre que o pedido envolver uma ação no celular; você pode
            chamar várias em sequência.
            Nunca diga que fez algo que a ferramenta não confirmou. Se o resultado pedir para
            o usuário tocar em algo (Enviar, Ligar, Permitir), repita esse aviso.
            Se não houver ferramenta para o pedido, diga com honestidade o que não consegue fazer.
            Agora é $agora.
        """.trimIndent() + contexto().let { if (it.isBlank()) "" else "\n\nSobre o usuário:\n$it" }
    }

    /**
     * Pesquisa no Google pelo Gemini ("grounding") e devolve o resumo com as fontes.
     * Null se não der (por exemplo: a chave está no plano gratuito, que não inclui a busca).
     */
    fun buscarNaInternet(pergunta: String): Web.Resposta? {
        // Se a chave já recusou a busca há pouco, nem tenta de novo (evita esperar à toa).
        if (System.currentTimeMillis() < buscaRecusadaAte) return null
        val agora = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
        val corpo = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts", JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Responda em português do Brasil, em até 4 frases, direto ao ponto e sem markdown. " +
                                "Se for sobre uma loja ou produto, resuma a reputação e as reclamações mais comuns. " +
                                "Hoje é $agora." + contexto().let { if (it.isBlank()) "" else "\nSobre o usuário:\n$it" }
                        )
                    )
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pergunta)))
                )
            )
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val bytes = corpo.toString().toByteArray(Charsets.UTF_8)

        val modelos = (listOf(modeloAtivo) + reservas().take(1)).distinct()
        for (m in modelos) {
            val (codigo, resposta) = try {
                enviarRequisicao(m, bytes)
            } catch (e: IOException) {
                continue
            }
            if (codigo !in 200..299) {
                if (codigo == 400 || codigo == 403 || codigo == 429) {
                    // Busca não liberada para esta chave (plano gratuito) ou cota esgotada: espera 6 horas.
                    buscaRecusadaAte = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
                    return null
                }
                continue
            }
            val candidato = resposta.optJSONArray("candidates")?.optJSONObject(0) ?: continue
            val partes = candidato.optJSONObject("content")?.optJSONArray("parts") ?: continue
            val texto = StringBuilder()
            for (i in 0 until partes.length()) {
                val p = partes.optJSONObject(i) ?: continue
                if (p.has("text") && !p.optBoolean("thought", false)) texto.append(p.optString("text"))
            }
            if (texto.isBlank()) continue
            val fontes = mutableListOf<Web.Fonte>()
            val meta = candidato.optJSONObject("groundingMetadata")
            val pedacos = meta?.optJSONArray("groundingChunks")
            if (pedacos != null) {
                for (i in 0 until pedacos.length()) {
                    val web = pedacos.optJSONObject(i)?.optJSONObject("web") ?: continue
                    val url = web.optString("uri")
                    if (url.isNotBlank() && fontes.none { it.url == url }) fontes += Web.Fonte(web.optString("title", "fonte"), url)
                }
            }
            meta?.optJSONArray("webSearchQueries")?.let { consultas ->
                for (i in 0 until minOf(consultas.length(), 2)) {
                    val q = consultas.optString(i)
                    if (q.isNotBlank()) fontes += Web.Fonte(
                        "Pesquisar \"$q\" no Google",
                        "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    )
                }
            }
            return Web.Resposta(texto.toString().trim().replace(Regex("[*#]"), ""), fontes.take(6))
        }
        return null
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
        tentar(modeloAtivo, 1)?.let { return it }

        // 2) Modelo sobrecarregado ou sem cota: passa para outro modelo Flash disponível na chave.
        for (reserva in reservas().take(3)) {
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
            conexao.connectTimeout = 8_000
            conexao.readTimeout = 25_000
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
                "Atingi o limite de uso gratuito do Gemini por agora. " +
                    "Os comandos rápidos continuam funcionando: diga \"ajuda\" para ver a lista."
            codigo >= 500 ->
                "A inteligência artificial está sobrecarregada agora (erro $codigo). " +
                    "Os comandos rápidos continuam funcionando: diga \"ajuda\" para ver a lista."
            else -> "O Gemini respondeu com erro $codigo: ${mensagem.take(200)}"
        }
    }

    companion object {
        /** Até quando não tentar a busca do Google de novo, depois de uma recusa. */
        @Volatile
        var buscaRecusadaAte = 0L

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

        /**
         * Escreve a descrição de um produto da loja a partir da foto (e do nome), em tom discreto.
         * Chamada avulsa, sem histórico. Devolve null se não der (sem chave, sem internet ou bloqueio do Google).
         */
        fun descreverProduto(chave: String, modelo: String, foto: ByteArray?, nome: String, categoria: String): String? {
            if (chave.isBlank()) return null
            val pedido = "Você escreve descrições de produtos para a loja online Desejo de Intimidade (moda íntima e sex shop). " +
                "Tom elegante, acolhedor e discreto, sem palavras vulgares nem explícitas. " +
                "Escreva em português do Brasil a descrição do produto \"$nome\" (categoria: $categoria), com no máximo 280 caracteres: " +
                "o que é, para que serve e o principal diferencial. Sem emojis, sem preço, sem markdown, sem aspas. " +
                (if (foto != null) "Use a foto para descrever cor, formato e acabamento." else "")
            val partes = JSONArray()
            if (foto != null) {
                partes.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                    .put("data", android.util.Base64.encodeToString(foto, android.util.Base64.NO_WRAP))))
            }
            partes.put(JSONObject().put("text", pedido))
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partes)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 400).put("temperature", 0.6))
                .toString().toByteArray()
            for (m in listOf(modelo.trim().removePrefix("models/"), "gemini-2.5-flash", "gemini-flash-lite-latest").distinct()) {
                var conexao: HttpURLConnection? = null
                try {
                    conexao = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
                    conexao.requestMethod = "POST"
                    conexao.connectTimeout = 10_000
                    conexao.readTimeout = 40_000
                    conexao.doOutput = true
                    conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conexao.setRequestProperty("Connection", "close")
                    conexao.setRequestProperty("x-goog-api-key", chave)
                    conexao.outputStream.use { it.write(corpo) }
                    if (conexao.responseCode !in 200..299) continue
                    val json = JSONObject(conexao.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    val texto = textoDaResposta(json) ?: continue
                    return texto.take(300)
                } catch (e: Exception) {
                    continue
                } finally {
                    conexao?.disconnect()
                }
            }
            return null
        }

        /** Junta os textos da primeira resposta do Gemini e limpa sobras de formatação. */
        fun textoDaResposta(json: JSONObject): String? {
            val partes = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val texto = (0 until partes.length()).mapNotNull { partes.optJSONObject(it)?.optString("text") }.joinToString(" ")
                .replace(Regex("[*_#`\"“”]"), "").replace(Regex("\\s+"), " ").trim()
            return texto.ifBlank { null }
        }

        private fun versao(nome: String): Double =
            Regex("gemini-(\\d+(?:\\.\\d+)?)").find(nome)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }
}
