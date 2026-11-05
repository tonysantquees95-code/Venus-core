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
    /** O que ela sabe de si mesma (versão, IAs, ferramentas, estado e lições), incluído nas instruções. */
    private val autoconhecimento: () -> String = { "" },
    /** O que a Sexta-feira sabe sobre o usuário (memórias), incluído nas instruções. */
    private val contexto: () -> String = { "" },
) {
    private val modelo = modelo.trim().removePrefix("models/")
    private var modeloAtivo = this.modelo
    /** Modelos que recusaram o ajuste de raciocínio (recebem o pedido sem ele). */
    private val modelosSemAjuste = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Quando passou para um modelo reserva (volta ao principal depois de 10 minutos). */
    private var reservaDesde = 0L
    private var modelosReserva: List<String>? = null
    private val historico = JSONArray()

    /** [chamadas]: ferramentas que a IA usou para responder (nome e argumentos), para a Vênus aprender. */
    data class RespostaIA(val ok: Boolean, val texto: String, val chamadas: List<Pair<String, JSONObject>> = emptyList())

    /** O que a Vênus entendeu de um arquivo aberto. */
    data class Analise(
        val tipo: String, val resumo: String, val vencimento: String?, val valorCentavos: Int?, val linhaDigitavel: String?,
    )

    /** true enquanto responde uma pergunta difícil: pensa mais (orçamento de raciocínio maior) e pode responder mais longo. */
    @Volatile
    private var profundo = false

    fun enviar(pedido: String, pensarMais: Boolean = false): RespostaIA {
        profundo = pensarMais
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

    /**
     * Começa com as mensagens de uma conversa salva (para continuar de onde parou, mesmo depois de fechar o app
     * ou ao retomar um assunto antigo). [mensagens]: (é do usuário?, texto), da mais antiga para a mais nova.
     */
    fun carregarHistorico(mensagens: List<Pair<Boolean, String>>) {
        desfazer(0)
        for (c in montarHistorico(mensagens)) historico.put(c)
    }

    private fun desfazer(tamanho: Int) {
        while (historico.length() > tamanho) historico.remove(historico.length() - 1)
    }

    private fun instrucoes(): String {
        val agora = SimpleDateFormat("EEEE, dd/MM/yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        val formato = if (profundo) Raciocinio.INSTRUCOES_PROFUNDAS else
            "Responda sempre em português do Brasil, de forma curta (até 3 frases) e sem markdown,\nporque a resposta pode ser falada em voz alta."
        return """
            ${Marca.personalidade}
            $formato
            Para informações atuais (clima, notícias, avaliações de lojas, preços, horários, resultados),
            use previsao_do_tempo ou buscar_na_internet e responda com o que encontrou, em vez de abrir o navegador.
            Para "o que é" e "quem foi", pode usar resumo_wikipedia.
            Você publica no Instagram da loja sozinha (feed e story) quando o Instagram está conectado nos ajustes:
            os posts programados saem na hora marcada. Nunca diga que não consegue publicar; se o usuário pedir para
            postar agora, diga para ele falar "posta agora".
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
            Quando o usuário retomar um assunto de uma conversa antiga (o histórico dela já vem carregado),
            relembre em uma frase o ponto em que vocês pararam e continue dali, sem repetir tudo.
            Agora é $agora.
        """.trimIndent() + autoconhecimento().let { if (it.isBlank()) "" else "\n\nSobre você mesma:\n$it" } +
            contexto().let { if (it.isBlank()) "" else "\n\nSobre o usuário:\n$it" }
    }

    /**
     * Pesquisa no Google pelo Gemini ("grounding") e devolve o resumo com as fontes.
     * Null se não der (por exemplo: a chave está no plano gratuito, que não inclui a busca).
     */
    fun buscarNaInternet(pergunta: String, instrucao: String? = null): Web.Resposta? =
        buscarNoGoogle(pergunta, instrucao) ?: SegundaIA.pesquisar(pergunta, instrucao)

    private fun buscarNoGoogle(pergunta: String, instrucao: String? = null): Web.Resposta? {
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
                            (instrucao ?: ("Responda em português do Brasil, em até 4 frases, direto ao ponto e sem markdown. " +
                                "Se for sobre uma loja ou produto, resuma a reputação e as reclamações mais comuns.")) +
                                " Hoje é $agora." + contexto().let { if (it.isBlank()) "" else "\nSobre o usuário:\n$it" }
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
        // O ajuste de raciocínio: pergunta difícil = mais "tempo de pensar"; pergunta comum = sem pensar por dentro
        // (é o que mais atrasa os modelos Flash novos). Modelo que recusar o ajuste (erro 400) recebe sem ele.
        val ajuste = JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", if (profundo) Raciocinio.ORCAMENTO else 0))
        val comAjuste = JSONObject(corpo.toString()).put("generationConfig", ajuste).toString().toByteArray(Charsets.UTF_8)
        val semAjuste = corpo.toString().toByteArray(Charsets.UTF_8)
        var ultimaResposta: Pair<Int, JSONObject>? = null
        var ultimoErro: IOException? = null

        // 0) A Groq é a principal (mais rápida e sem a cota de 20 pedidos por dia); o Gemini fica de reserva.
        // Pedido com PDF ou áudio ela não aceita (devolve null) e segue direto para o Gemini.
        var groqTentou = false
        if (SegundaIA.principal()) {
            groqTentou = true
            SegundaIA.responder(corpo, if (profundo) 60_000 else 25_000, esforco = if (profundo) "medium" else "low")?.let { return 200 to it }
        }

        // Passou para a reserva há mais de 10 minutos: tenta de novo o modelo principal.
        if (modeloAtivo != modelo && System.currentTimeMillis() - reservaDesde > 10 * 60_000L) modeloAtivo = modelo

        fun tentar(modeloUsado: String, vezes: Int): Pair<Int, JSONObject>? {
            for (tentativa in 1..vezes) {
                try {
                    var resposta = enviarRequisicao(modeloUsado, if (modeloUsado in modelosSemAjuste) semAjuste else comAjuste)
                    if (resposta.first == 400 && modeloUsado !in modelosSemAjuste) {
                        modelosSemAjuste += modeloUsado
                        resposta = enviarRequisicao(modeloUsado, semAjuste)
                    }
                    if (!sobrecarregado(resposta.first, resposta.second)) return resposta
                    ultimaResposta = resposta
                } catch (e: IOException) {
                    ultimoErro = e
                }
                if (tentativa < vezes) Thread.sleep(1000L * tentativa)
            }
            return null
        }

        // 1) Modelo em uso.
        tentar(modeloAtivo, 1)?.let { r ->
            if (r.first in 200..299 || r.first == 401 || r.first == 403) return r
            ultimaResposta = r
        }

        // 2) Modelo sobrecarregado (503, "overloaded"), sem cota (429) ou fora do ar: passa para outro modelo Flash.
        // (o gemini-2.5-flash não está mais disponível para chaves novas; o Google indica o gemini-3.8-flash)
        val ordem = (listOf("gemini-3.8-flash", "gemini-flash-lite-latest") + reservas().take(3)).distinct()
        for (reserva in ordem) {
            if (reserva == modeloAtivo) continue
            val resposta = tentar(reserva, 1) ?: continue
            if (resposta.first in 200..299) {
                modeloAtivo = reserva // segue a conversa com o modelo que respondeu
                reservaDesde = System.currentTimeMillis()
                return resposta
            }
        }
        // 3) Nenhum Gemini respondeu: a IA reserva (Groq ou Grok), se estiver configurada.
        if (!groqTentou) SegundaIA.responder(corpo, if (profundo) 60_000 else 30_000)?.let { return 200 to it }
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
            conexao.readTimeout = if (profundo) 75_000 else 25_000
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

        /**
         * O modelo está indisponível ou sobrecarregado? 429 (cota), 500/502/503/504, ou mensagem do Google
         * com "overloaded", "UNAVAILABLE", "RESOURCE_EXHAUSTED" ou "503". Aí a Vênus troca para um modelo Flash.
         */
        fun sobrecarregado(codigo: Int, resposta: JSONObject?): Boolean {
            if (codigo == 429 || codigo == 500 || codigo == 502 || codigo == 503 || codigo == 504) return true
            if (codigo in 200..299) return false
            val erro = resposta?.optJSONObject("error")
            val texto = (erro?.optString("message").orEmpty() + " " + erro?.optString("status").orEmpty())
            return Regex("overloaded|unavailable|resource_exhausted|\\b503\\b|try again later", RegexOption.IGNORE_CASE).containsMatchIn(texto)
        }
        private val RESERVA_FIXA = listOf("gemini-flash-lite-latest", "gemini-3.8-flash")
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
            for (m in listOf(modelo.trim().removePrefix("models/"), "gemini-3.8-flash", "gemini-flash-lite-latest").distinct()) {
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

        /** Uma chamada avulsa ao Gemini (sem histórico), tentando o modelo escolhido e duas reservas. */
        private fun chamarAvulso(chave: String, modelos: List<String>, corpo: ByteArray, tempo: Int = 60_000): JSONObject? {
            for (m in modelos.map { it.trim().removePrefix("models/") }.filter { it.isNotBlank() }.distinct()) {
                var conexao: HttpURLConnection? = null
                try {
                    conexao = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
                    conexao.requestMethod = "POST"
                    conexao.connectTimeout = 10_000
                    conexao.readTimeout = tempo
                    conexao.doOutput = true
                    conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conexao.setRequestProperty("Connection", "close")
                    conexao.setRequestProperty("x-goog-api-key", chave)
                    conexao.outputStream.use { it.write(corpo) }
                    if (conexao.responseCode !in 200..299) continue
                    return JSONObject(conexao.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                } catch (e: Exception) {
                    continue
                } finally {
                    conexao?.disconnect()
                }
            }
            // nenhum Gemini respondeu: a IA reserva. Pedido com busca no Google não vai: sem a busca ela inventaria preços e fatos.
            if (String(corpo, Charsets.UTF_8).contains("\"google_search\"")) return null
            return try {
                SegundaIA.responder(JSONObject(String(corpo, Charsets.UTF_8)), tempo)
            } catch (e: Exception) {
                null
            }
        }

        private fun imagem(foto: ByteArray, tipo: String = "image/jpeg") = JSONObject().put(
            "inline_data", JSONObject().put("mime_type", tipo).put("data", android.util.Base64.encodeToString(foto, android.util.Base64.NO_WRAP))
        )

        /**
         * Lê a foto de uma nota fiscal, pedido de fornecedor ou print de compra.
         * Devolve (descrição, quantidade, custo unitário em centavos) de cada item; null se não conseguir ler.
         */
        fun lerNotaDeCompra(chave: String, modelo: String, foto: ByteArray, tipo: String = "image/jpeg"): List<Triple<String, Int, Int>>? {
            if (chave.isBlank()) return null
            val pedido = "Esta imagem é uma nota fiscal, cupom, pedido de fornecedor ou print de uma compra de produtos para revenda. " +
                "Liste cada produto comprado. Responda só com JSON no formato " +
                "{\"itens\":[{\"descricao\":\"texto como está na nota\",\"quantidade\":5,\"valor_total\":13.00,\"valor_unitario\":2.60}]}. " +
                "Valores em reais com ponto decimal. Se só houver o total da linha, calcule o unitário dividindo pela quantidade. " +
                "Não inclua frete, impostos separados, descontos gerais nem a linha de total."
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(imagem(foto, tipo)).put(JSONObject().put("text", pedido)))))
                .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo) ?: return null
            val texto = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?.let { partes -> (0 until partes.length()).joinToString("") { partes.optJSONObject(it)?.optString("text").orEmpty() } } ?: return null
            return lerItensDaNota(texto)
        }

        /** Interpreta o JSON da nota (separado para poder ser testado). */
        fun lerItensDaNota(texto: String): List<Triple<String, Int, Int>>? {
            val limpo = texto.substringAfter("{", "").let { if (it.isBlank()) return null else "{$it" }.substringBeforeLast("}") + "}"
            val itens = try {
                JSONObject(limpo).optJSONArray("itens")
            } catch (e: Exception) {
                null
            } ?: return null
            val saida = mutableListOf<Triple<String, Int, Int>>()
            for (i in 0 until itens.length()) {
                val o = itens.optJSONObject(i) ?: continue
                val desc = o.optString("descricao").trim()
                val qtd = o.optDouble("quantidade", 1.0).let { if (it.isNaN() || it < 1) 1 else it.toInt() }
                var unit = o.optDouble("valor_unitario", Double.NaN)
                val total = o.optDouble("valor_total", Double.NaN)
                if (unit.isNaN() || unit <= 0) unit = if (!total.isNaN() && total > 0) total / qtd else Double.NaN
                if (desc.isBlank() || unit.isNaN() || unit <= 0) continue
                saida += Triple(desc, qtd, Math.round(unit * 100).toInt())
            }
            return saida.ifEmpty { null }
        }

        /**
         * Troca o fundo da foto pelo que foi pedido, com o modelo de imagens do Gemini.
         * Pode recusar fotos de produtos íntimos (regra do Google): aí devolve null.
         */
        fun trocarFundoComIA(chave: String, foto: ByteArray, pedido: String): ByteArray? {
            if (chave.isBlank()) return null
            val instrucao = "Edite esta foto de produto para uma loja online: mantenha o produto exatamente igual (forma, cor, rótulo, tamanho e posição) " +
                "e troque apenas o fundo por: $pedido. Iluminação de estúdio, foto realista, sem texto, sem pessoas."
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(imagem(foto)).put(JSONObject().put("text", instrucao)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf("gemini-2.5-flash-image", "gemini-2.5-flash-image-preview"), corpo, tempo = 90_000) ?: return null
            val partes = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            for (i in 0 until partes.length()) {
                val p = partes.optJSONObject(i) ?: continue
                val dados = (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))?.optString("data").orEmpty()
                if (dados.isNotBlank()) return android.util.Base64.decode(dados, android.util.Base64.DEFAULT)
            }
            return null
        }

        /** Rascunho de resposta a uma cliente, usando só o catálogo e as regras da loja. */
        fun respostaCliente(chave: String, modelo: String, mensagem: String, catalogo: String): String? {
            if (chave.isBlank()) return null
            val pedido = "Você atende clientes da Desejo de Intimidade (moda íntima e sex shop, Porto Alegre, site desejo-de-intimidade.floot.app) pelo WhatsApp. " +
                "Regras da loja: entrega discreta para todo o Brasil pelos Correios (SEDEX ou PAC), frete calculado pelo CEP no site; " +
                "pagamento por Pix com 5% de desconto (exceto produtos em promoção) ou cartão em até 3x; retirada grátis em ponto de retirada em Porto Alegre, " +
                "com dia e horário combinados pelo WhatsApp depois da compra; embalagem neutra e sem identificação; venda só para maiores de 18 anos. " +
                "Catálogo atual (nome | preço | preço no Pix | estoque | link):\n$catalogo\n\n" +
                "Escreva a resposta para a mensagem abaixo, como a loja, em português do Brasil: simpática, curta (até 5 frases), discreta, sem palavras vulgares, " +
                "no máximo 2 emojis. Use só as informações acima; cite preço e link quando falar de produto; se algo não estiver aqui, diga que vai verificar. " +
                "Não invente prazos nem produtos. Responda só com o texto da mensagem.\n\nMensagem da cliente: $mensagem"
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pedido)))))
                .put("generationConfig", JSONObject().put("temperature", 0.5))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo) ?: return null
            return textoBruto(json)?.replace(Regex("[*`#]"), "")?.trim()?.trim('"', '“', '”')
        }

        /** Legenda para Instagram (feed ou story) a partir da foto e do que se sabe do produto. */
        fun legendaInstagram(chave: String, modelo: String, foto: ByteArray?, contexto: String, story: Boolean): String? {
            if (chave.isBlank()) return null
            val pedido = "Você cuida do Instagram da Desejo de Intimidade, loja de moda íntima e sex shop com entrega discreta em todo o Brasil " +
                "(site desejo-de-intimidade.floot.app). Tom elegante, provocante na medida e acolhedor, sem palavras explícitas nem vulgares " +
                "(o Instagram remove conteúdo sexual explícito). " +
                (if (story) "Escreva um texto curto para um story (até 90 caracteres) com uma chamada para ação. "
                else "Escreva a legenda de um post do feed: 2 a 4 frases curtas, uma chamada para ação (link na bio ou chamar no WhatsApp), " +
                    "no máximo 3 emojis e, no fim, 6 a 8 hashtags em português. ") +
                "Sobre o post: ${contexto.ifBlank { "use a foto" }}. Responda só com o texto, sem aspas e sem markdown."
            val partes = JSONArray()
            if (foto != null) partes.put(imagem(foto))
            partes.put(JSONObject().put("text", pedido))
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partes)))
                .put("generationConfig", JSONObject().put("temperature", 0.8))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo) ?: return null
            return textoBruto(json)?.replace(Regex("[*`]"), "")?.trim()?.trim('"', '“', '”')?.take(2000)
        }


        /** Resposta pública a um comentário no Instagram da loja (curta, discreta, chama para o direct ou o WhatsApp). */
        fun respostaComentario(chave: String, modelo: String, comentario: String, usuario: String, legendaDoPost: String, catalogo: String): String? {
            if (chave.isBlank()) return null
            val pedido = "Você responde comentários no Instagram da Desejo de Intimidade, loja de moda íntima e sex shop com entrega discreta " +
                "em todo o Brasil (site desejo-de-intimidade.floot.app). A resposta é PÚBLICA: seja simpática, curta (1 ou 2 frases), elegante, " +
                "sem palavras explícitas, no máximo 1 emoji, começando com @$usuario. Se perguntarem preço ou detalhes, responda se estiver no catálogo " +
                "e convide para chamar no direct ou no WhatsApp para atendimento discreto. Elogio: agradeça com carinho. Reclamação: peça desculpas e " +
                "chame para o direct. Não invente prazos nem produtos.\n" +
                "Post comentado: ${legendaDoPost.take(300).ifBlank { "(sem legenda)" }}\n" +
                (if (catalogo.isNotBlank()) "Catálogo (nome | preço | preço no Pix | estoque | link):\n${catalogo.take(3000)}\n" else "") +
                "Comentário de @$usuario: $comentario\nResponda só com o texto da resposta."
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pedido)))))
                .put("generationConfig", JSONObject().put("temperature", 0.6))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo) ?: return null
            return textoBruto(json)?.replace(Regex("[*`#]"), "")?.trim()?.trim('"', '“', '”')?.take(1000)
        }

        private fun partesDoDocumento(doc: Arquivos.Documento, texto: String): JSONArray {
            val partes = JSONArray()
            if (doc.bytes != null) partes.put(imagem(doc.bytes, doc.mime))
            else partes.put(JSONObject().put("text", "Conteúdo do arquivo \"${doc.nome}\":\n${doc.texto}"))
            partes.put(JSONObject().put("text", texto))
            return partes
        }

        /** Primeira leitura: o que é o arquivo, um resumo e, se for boleto, vencimento, valor e linha digitável. */
        fun analisarDocumento(chave: String, modelo: String, doc: Arquivos.Documento): Analise? {
            if (chave.isBlank()) return null
            val audio = doc.mime.startsWith("audio/")
            val pedido = (if (audio) "Transcreva este áudio em português e depois resuma. " else "Leia este arquivo. ") +
                "Responda só com JSON: {\"tipo\":\"boleto|nota_fiscal|contrato|catalogo|planilha|audio|outro\"," +
                "\"resumo\":\"até 6 frases em português do Brasil, diretas, com os números e datas importantes" +
                (if (audio) ", começando pela transcrição curta do que foi dito" else "") + "\"," +
                "\"vencimento\":\"AAAA-MM-DD ou vazio\",\"valor\":0.00,\"linha_digitavel\":\"só os números do boleto ou vazio\"}. " +
                "Sem markdown no resumo."
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partesDoDocumento(doc, pedido))))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo, tempo = 120_000) ?: return null
            return lerAnalise(textoBruto(json) ?: return null)
        }

        /** Interpreta o JSON da análise (separado para poder ser testado). */
        fun lerAnalise(texto: String): Analise? {
            val o = try {
                JSONObject("{" + texto.substringAfter("{").substringBeforeLast("}") + "}")
            } catch (e: Exception) {
                return texto.trim().takeIf { it.isNotBlank() }?.let { Analise("outro", it.take(1500), null, null, null) }
            }
            val resumo = o.optString("resumo").trim().ifBlank { return null }
            val venc = o.optString("vencimento").trim().takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }
            val valor = o.optDouble("valor", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { Math.round(it * 100).toInt() }
            val linha = o.optString("linha_digitavel").replace(Regex("\\D"), "").takeIf { it.length in 44..48 }
            return Analise(o.optString("tipo", "outro").ifBlank { "outro" }, resumo, venc, valor, linha)
        }

        /** O que a foto mostra, para pesquisar preço (nome comercial, marca, modelo e termos de busca). */
        data class ProdutoDaFoto(val nome: String, val marca: String, val termos: String, val descricao: String)

        fun identificarProduto(chave: String, modelo: String, foto: ByteArray, mime: String, pedido: String): ProdutoDaFoto? {
            if (chave.isBlank()) return null
            val texto = "Identifique o produto principal desta foto para pesquisar preço em lojas do Brasil. " +
                (if (pedido.isNotBlank()) "Pedido do usuário: $pedido. " else "") +
                "Responda só com JSON: {\"nome\":\"nome comercial mais provável\",\"marca\":\"marca ou vazio\"," +
                "\"termos\":\"termos de busca curtos para achar este produto à venda (marca, modelo, tamanho, cor)\"," +
                "\"descricao\":\"1 frase dizendo o que é\"}"
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(imagem(foto, mime)).put(JSONObject().put("text", texto)))))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo, tempo = 60_000) ?: return null
            return lerProdutoDaFoto(textoBruto(json) ?: return null)
        }

        fun lerProdutoDaFoto(t: String): ProdutoDaFoto? = try {
            val o = JSONObject("{" + t.substringAfter("{").substringBeforeLast("}") + "}")
            val nome = o.optString("nome").trim()
            if (nome.isBlank()) null
            else ProdutoDaFoto(nome, o.optString("marca").trim(), o.optString("termos").trim().ifBlank { nome }, o.optString("descricao").trim())
        } catch (e: Exception) {
            null
        }

        /** Vários arquivos no mesmo pedido, cada um com o nome antes, e o pedido no fim. */
        private fun partesDosDocumentos(docs: List<Arquivos.Documento>, texto: String): JSONArray {
            val partes = JSONArray()
            docs.forEachIndexed { i, doc ->
                partes.put(JSONObject().put("text", "Arquivo ${i + 1}: \"${doc.nome}\" (${doc.tipoFalado})"))
                if (doc.bytes != null) partes.put(imagem(doc.bytes, doc.mime))
                else partes.put(JSONObject().put("text", "Conteúdo:\n${doc.texto}"))
            }
            partes.put(JSONObject().put("text", texto))
            return partes
        }

        /**
         * O pedido do usuário sobre um ou mais anexos (fotos, PDFs, planilhas, áudios), como no chat do Gemini:
         * entende o que é cada arquivo e responde o que foi pedido. Pedido vazio = diz o que é e o principal de cada um.
         */
        fun perguntarDocumentos(chave: String, modelo: String, docs: List<Arquivos.Documento>, pergunta: String, conversa: List<Pair<String, String>>): String? {
            if (docs.isEmpty()) return null
            if (chave.isBlank() && docs.any { it.bytes != null }) return null
            val antes = if (conversa.isEmpty()) "" else "Conversa até aqui sobre os arquivos:\n" +
                conversa.takeLast(4).joinToString("\n") { "Usuário: ${it.first}\nVênus: ${it.second}" } + "\n\n"
            val pedido = antes + "Você é a Vênus, assistente pessoal e da loja do Toni. Responda em português do Brasil, direto e sem markdown. " +
                "Entenda sozinha o que é cada arquivo (foto de produto, nota fiscal, boleto, print, documento, planilha, áudio...). " +
                "Use o conteúdo dos arquivos e, quando a pergunta pedir, também o seu conhecimento geral; diga quando algo não está nos arquivos. " +
                (if (docs.size > 1) "Quando ajudar, diga de qual arquivo veio cada informação. " else "") +
                "Pedido: " + pergunta.ifBlank {
                    if (docs.size == 1) "Diga em poucas frases o que é este arquivo e o que ele tem de mais importante (números, datas, valores)."
                    else "Diga em poucas frases o que é cada arquivo e o principal de cada um."
                }
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partesDosDocumentos(docs, pedido))))
                .put("generationConfig", JSONObject().put("temperature", 0.3))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo, tempo = 120_000) ?: return null
            return textoDaResposta(json)
        }

        /** Pergunta sobre o arquivo aberto (cada pergunta manda o arquivo de novo, com as últimas trocas). */
        fun perguntarDocumento(chave: String, modelo: String, doc: Arquivos.Documento, pergunta: String, conversa: List<Pair<String, String>>): String? {
            if (chave.isBlank()) return null
            val antes = if (conversa.isEmpty()) "" else "Conversa até aqui sobre o arquivo:\n" +
                conversa.takeLast(4).joinToString("\n") { "Usuário: ${it.first}\nVênus: ${it.second}" } + "\n\n"
            val pedido = antes + "Responda em português do Brasil, de forma curta e direta, usando só o conteúdo do arquivo. " +
                "Se a resposta não estiver no arquivo, diga isso. Sem markdown. Pergunta: $pergunta"
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partesDoDocumento(doc, pedido))))
                .put("generationConfig", JSONObject().put("temperature", 0.3))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest"), corpo, tempo = 120_000) ?: return null
            return textoDaResposta(json)
        }

        /**
         * Uma pergunta avulsa que devolve JSON (usado no estudo diário). [pensar] = orçamento de raciocínio
         * (0 = rápido). Tenta o modelo escolhido e os Flash de reserva.
         */
        fun gerarJson(chave: String, modelo: String, pedido: String, pensar: Int = 0, temperatura: Double = 0.9): String? {
            // a Groq vem primeiro: o estudo e os stories gastavam quase toda a cota diária do Gemini (20 pedidos)
            if (SegundaIA.configurada()) {
                val simples = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pedido)))))
                    .put("generationConfig", JSONObject().put("temperature", temperatura).put("responseMimeType", "application/json"))
                val r = try {
                    SegundaIA.responder(simples, 120_000, esforco = if (pensar >= 2048) "medium" else "low")
                } catch (e: Exception) {
                    null
                }
                r?.let { textoBruto(it) }?.takeIf { it.contains("{") }?.let { return it }
            }
            if (chave.isBlank()) return null
            fun corpo(comPensar: Boolean): ByteArray {
                val config = JSONObject().put("temperature", temperatura).put("responseMimeType", "application/json")
                if (comPensar) config.put("thinkingConfig", JSONObject().put("thinkingBudget", pensar))
                return JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pedido)))))
                    .put("generationConfig", config)
                    .toString().toByteArray()
            }
            val modelos = listOf(modelo, "gemini-3.8-flash", "gemini-flash-lite-latest")
            // com raciocínio primeiro; se nenhum modelo aceitar o ajuste, sem ele
            val json = (if (pensar > 0) chamarAvulso(chave, modelos, corpo(true), tempo = 120_000) else null)
                ?: chamarAvulso(chave, modelos, corpo(false), tempo = 120_000) ?: return null
            return textoBruto(json)
        }

        private fun textoBruto(json: JSONObject): String? {
            val partes = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            return (0 until partes.length()).joinToString("") { partes.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
        }

        /** Junta os textos da primeira resposta do Gemini e limpa sobras de formatação. */
        /** O histórico no formato do Gemini: alterna usuário e Vênus, começa pelo usuário e termina na Vênus. */
        fun montarHistorico(mensagens: List<Pair<Boolean, String>>, maximo: Int = 40): List<JSONObject> {
            val limpas = mensagens.filter { it.second.isNotBlank() && !it.second.startsWith("Fontes: ") }.takeLast(maximo)
            val juntas = mutableListOf<Pair<Boolean, String>>()
            for ((eu, t) in limpas) {
                if (juntas.isNotEmpty() && juntas.last().first == eu) juntas[juntas.size - 1] = eu to (juntas.last().second + "\n" + t)
                else juntas += eu to t
            }
            while (juntas.isNotEmpty() && !juntas.first().first) juntas.removeAt(0)
            while (juntas.isNotEmpty() && juntas.last().first) juntas.removeAt(juntas.size - 1)
            return juntas.map { (eu, t) ->
                JSONObject().put("role", if (eu) "user" else "model").put("parts", JSONArray().put(JSONObject().put("text", t.take(4000))))
            }
        }

        /**
         * Pesquisa no Google (pela busca do Gemini) os preços atuais de um produto em lojas do Brasil.
         * Devolve o texto com uma oferta por linha ("Loja — R$ 00,00 — observação") e as fontes; null se a busca não estiver liberada.
         */
        fun pesquisarPrecos(chave: String, modelo: String, produto: String): Web.Resposta? {
            if (chave.isBlank()) return null
            val pedido = "Pesquise na internet os preços atuais, no Brasil, de: $produto. " +
                "Procure em lojas diferentes (Mercado Livre, Amazon, Shopee, Magazine Luiza, Americanas, Casas Bahia e lojas especializadas). " +
                "Responda só com até 6 linhas, uma por oferta, exatamente no formato: Loja — R$ 00,00 — observação curta (ex.: frete grátis, kit com 2, tamanho). " +
                "Use somente preços que aparecem nos resultados da busca; não invente nem estime. Sem markdown e sem outras frases."
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pedido)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("generationConfig", JSONObject().put("temperature", 0.1))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf(modelo, "gemini-3.8-flash", "gemini-flash-latest"), corpo, tempo = 45_000) ?: return null
            val candidato = json.optJSONArray("candidates")?.optJSONObject(0) ?: return null
            val partes = candidato.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val texto = (0 until partes.length()).mapNotNull { partes.optJSONObject(it) }
                .filter { !it.optBoolean("thought", false) }.joinToString("") { it.optString("text") }.replace(Regex("[*#]"), "").trim()
            if (texto.isBlank()) return null
            val fontes = mutableListOf<Web.Fonte>()
            candidato.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { pedacos ->
                for (i in 0 until pedacos.length()) {
                    val web = pedacos.optJSONObject(i)?.optJSONObject("web") ?: continue
                    val url = web.optString("uri")
                    if (url.isNotBlank() && fontes.none { it.url == url }) fontes += Web.Fonte(web.optString("title", "loja"), url)
                }
            }
            return Web.Resposta(texto, fontes.take(6))
        }

        /** Título curto e resumo de uma conversa encerrada (para achar depois). */
        fun resumirConversa(chave: String, modelo: String, transcricao: String): Pair<String, String>? {
            if (chave.isBlank()) return null
            val pedido = "Esta é uma conversa entre um usuário e a assistente Vênus. Responda só com JSON: " +
                "{\"titulo\": \"assunto principal em até 6 palavras\", \"resumo\": \"2 ou 3 frases com o que foi falado, decidido ou ficou pendente, " +
                "incluindo números, nomes e produtos citados\"}.\n\n" + transcricao.takeLast(12000)
            val corpo = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", pedido)))))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
                .toString().toByteArray()
            val json = chamarAvulso(chave, listOf("gemini-flash-lite-latest", modelo, "gemini-3.8-flash"), corpo, tempo = 30_000) ?: return null
            val bruto = textoBruto(json) ?: return null
            return try {
                val o = JSONObject(bruto.substring(bruto.indexOf('{'), bruto.lastIndexOf('}') + 1))
                val titulo = o.optString("titulo").trim().trimEnd('.')
                val resumo = o.optString("resumo").trim()
                if (titulo.isBlank() || resumo.isBlank()) null else titulo.take(60) to resumo.take(600)
            } catch (e: Exception) {
                null
            }
        }

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
