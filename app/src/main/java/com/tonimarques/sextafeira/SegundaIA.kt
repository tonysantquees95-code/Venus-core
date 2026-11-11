package com.tonimarques.sextafeira

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Inicia o que precisa existir antes de qualquer tela ou trabalho em segundo plano. */
class VenusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Perfil.carregar(this)
        SegundaIA.iniciar(this)
    }
}

/**
 * A IA reserva: quando o Gemini trava, fica sem cota ou sai do ar, a Vênus pergunta para outra IA
 * (Groq, grátis com ~1.000 pedidos por dia, ou Grok da xAI, pago). As duas usam o formato da OpenAI.
 *
 * Para não mexer no resto do app, ela recebe o pedido no formato do Gemini (systemInstruction, contents,
 * functionDeclarations, generationConfig) e devolve a resposta também no formato do Gemini
 * (candidates → content → parts com text ou functionCall). Fotos vão para os modelos que enxergam; PDF e áudio não.
 */
object SegundaIA {

    data class Provedor(val id: String, val nome: String, val endereco: String, val modelos: List<String>, val preferencia: Regex, val site: String)

    val PROVEDORES = listOf(
        Provedor(
            "groq", "Groq (grátis)", "https://api.groq.com/openai/v1",
            listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"),
            Regex("gpt-oss-120b|llama-3\\.3-70b|qwen|gpt-oss|llama"), "https://console.groq.com/keys",
        ),
        Provedor(
            "xai", "Grok (xAI, pago)", "https://api.x.ai/v1",
            listOf("grok-4-fast-non-reasoning", "grok-3-mini"),
            Regex("grok-4.*fast|grok-4|grok-3"), "https://console.x.ai",
        ),
    )

    /**
     * Segunda reserva: depois da Groq (ou do Grok) e antes do Gemini. Cloudflare Workers AI, grátis todo dia
     * (10 mil "neurons", sem cartão e sem usar os pedidos para treinar). O endereço leva o código da conta.
     */
    val CLOUDFLARE = Provedor(
        "cloudflare", "Cloudflare (grátis)", "https://api.cloudflare.com/client/v4/accounts/{conta}/ai/v1",
        listOf("@cf/openai/gpt-oss-120b", "@cf/openai/gpt-oss-20b", "@cf/qwen/qwen3-30b-a3b-fp8"),
        Regex("gpt-oss|qwen3|llama-3\\.3"), "https://dash.cloudflare.com/?to=/:account/ai/workers-ai",
    )

    private const val PREFS = "config"
    @Volatile
    private var ctx: Context? = null

    /** Modelo que respondeu por último em cada provedor (evita tentar de novo os que não existem). */
    private val modeloQueFunciona = mutableMapOf<String, String>()

    @Volatile
    var ultimaVez: Long = 0
        private set

    /** O último erro de uma IA reserva (para o teste da chave explicar o que houve). */
    @Volatile
    var ultimoErro: String? = null
        private set

    fun iniciar(c: Context) {
        ctx = c.applicationContext
    }

    fun provedor(c: Context? = ctx): Provedor {
        val id = c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("reserva_provedor", "groq") ?: "groq"
        return PROVEDORES.firstOrNull { it.id == id } ?: PROVEDORES.first()
    }

    fun chave(c: Context? = ctx): String = c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.let { Cofre.ler(it, "reserva_chave") }.orEmpty()

    fun chaveCloudflare(c: Context? = ctx): String = c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.let { Cofre.ler(it, "cloudflare_chave") }.orEmpty()
    fun contaCloudflare(c: Context? = ctx): String = c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("cloudflare_conta", "").orEmpty()

    /** Aceita o código da conta sozinho ou colado junto com o endereço (…/accounts/CODIGO/ai/…). */
    fun lerContaCloudflare(t: String): String {
        val limpo = t.trim()
        Regex("accounts/([0-9a-fA-F]{32})").find(limpo)?.let { return it.groupValues[1].lowercase() }
        return Regex("[0-9a-fA-F]{32}").find(limpo)?.value?.lowercase() ?: limpo
    }

    fun salvarCloudflare(c: Context, conta: String, chave: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cloudflare_conta", lerContaCloudflare(conta)).putString("cloudflare_chave", Cofre.selar(Instagram.limparToken(chave)))
            .remove("cerebras_chave").apply()
        modeloQueFunciona.remove(CLOUDFLARE.id)
        modeloQueFunciona.remove(CLOUDFLARE.id + ":visao")
        Diario.anotar(c, "conexao", if (chave.isBlank()) "Segunda reserva (Cloudflare) desligada" else "Segunda reserva configurada: Cloudflare Workers AI", "tela")
    }

    private fun cloudflare(c: Context?): Pair<Provedor, String>? {
        val chave = chaveCloudflare(c).takeIf { it.isNotBlank() } ?: return null
        val conta = contaCloudflare(c).takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
        return CLOUDFLARE.copy(endereco = CLOUDFLARE.endereco.replace("{conta}", conta)) to chave
    }

    /**
     * A IA da Vênus no servidor: o app não precisa de chave nenhuma, só de estar logado no Vetorial ID.
     * O servidor guarda a chave e responde no mesmo formato da OpenAI/Groq (rota /ia/v1).
     */
    val DO_SERVIDOR = Provedor(
        "venus", "IA da Vênus (servidor)", "",
        listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"),
        Regex("gpt-oss|llama-3\\.3"), "",
    )

    private fun doServidor(c: Context?): Pair<Provedor, String>? {
        if (c == null || !Servidor.configurado()) return null
        val token = VetorialId.token(c).takeIf { it.isNotBlank() } ?: return null
        return DO_SERVIDOR.copy(endereco = "${Servidor.ENDERECO}/ia/v1") to token
    }

    /** Tem a IA do servidor (Vetorial ID logado)? É o caminho de quem não usa chave própria. */
    fun doServidorDisponivel(c: Context? = ctx): Boolean = doServidor(c) != null

    /** As IAs na ordem em que são tentadas: chave própria (se o dono tiver), Cloudflare própria e, por fim, a IA da Vênus no servidor. */
    fun cadeia(c: Context? = ctx): List<Pair<Provedor, String>> = listOfNotNull(
        chave(c).takeIf { it.isNotBlank() }?.let { provedor(c) to it },
        cloudflare(c),
        doServidor(c),
    )

    fun configurada(c: Context? = ctx) = cadeia(c).isNotEmpty()

    /** A Groq (ou o Grok) responde a conversa primeiro, e o Gemini fica de reserva (padrão desde a v1.128). */
    fun principal(c: Context? = ctx): Boolean =
        configurada(c) && (c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean("reserva_principal", true) ?: true)

    fun definirPrincipal(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("reserva_principal", v).apply()
        Diario.anotar(c, "conexao", if (v) "${provedor(c).nome} passou a ser a IA principal; Gemini de reserva" else "Gemini voltou a ser a IA principal; ${provedor(c).nome} de reserva", "tela")
    }

    fun salvar(c: Context, provedor: String, chave: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("reserva_provedor", provedor).putString("reserva_chave", Cofre.selar(chave.trim())).apply()
        modeloQueFunciona.clear()
        Diario.anotar(c, "conexao", if (chave.isBlank()) "IA reserva desligada" else "IA reserva configurada: ${PROVEDORES.firstOrNull { it.id == provedor }?.nome ?: provedor}", "voz")
    }

    /**
     * Responde um pedido no formato do Gemini. Null se não houver reserva configurada ou ela também falhar.
     * Bloqueante: chame fora da thread principal.
     */
    fun responder(corpoGemini: JSONObject, tempo: Int = 30_000, esforco: String = "low"): JSONObject? {
        val cadeia = cadeia()
        if (cadeia.isEmpty()) return null
        val pedido = paraOpenAI(corpoGemini) ?: return null
        for ((p, chave) in cadeia) responderCom(p, chave, pedido, tempo, esforco)?.let { return it }
        return null
    }

    /** Tenta os modelos de um provedor (o que respondeu por último primeiro). */
    private fun responderCom(p: Provedor, chave: String, pedido: JSONObject, tempo: Int, esforco: String): JSONObject? {
        // com foto: só modelos que enxergam (na Groq, os Llama 4)
        val comFoto = temFoto(pedido)
        val modelos = if (comFoto) (VISAO[p.id] ?: return null) else p.modelos
        val preferencia = if (comFoto) Regex("llama-4|scout|maverick|vision") else p.preferencia
        val chaveDoModelo = if (comFoto) p.id + ":visao" else p.id
        val ordem = (listOfNotNull(modeloQueFunciona[chaveDoModelo]) + modelos).distinct()
        var tentouAlgum = false
        for (modelo in ordem) {
            when (val r = chamar(p, chave, modelo, pedido, tempo, esforco = esforco)) {
                is Resultado.Ok -> {
                    modeloQueFunciona[chaveDoModelo] = modelo
                    ultimaVez = System.currentTimeMillis()
                    return paraGemini(r.json)
                }
                Resultado.ModeloNaoExiste -> continue
                // limite por minuto ou por dia é de cada modelo: tenta o próximo
                Resultado.Limite -> {
                    tentouAlgum = true
                    continue
                }
                Resultado.Falhou -> return null
            }
        }
        if (tentouAlgum) return null
        // nenhum dos modelos conhecidos existe mais: pergunta a lista ao provedor e usa o melhor
        val melhor = listarModelos(p, chave).firstOrNull { preferencia.containsMatchIn(it) } ?: return null
        val r = chamar(p, chave, melhor, pedido, tempo, esforco = esforco) as? Resultado.Ok ?: return null
        modeloQueFunciona[chaveDoModelo] = melhor
        ultimaVez = System.currentTimeMillis()
        return paraGemini(r.json)
    }

    /** Modelos que leem fotos, por provedor. */
    val VISAO = mapOf(
        "groq" to listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"),
        "xai" to listOf("grok-4-fast-non-reasoning", "grok-2-vision-1212"),
        "cloudflare" to listOf("@cf/meta/llama-4-scout-17b-16e-instruct"),
        "venus" to listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"),
    )

    fun temFoto(pedido: JSONObject): Boolean {
        val msgs = pedido.optJSONArray("messages") ?: return false
        return (0 until msgs.length()).any { msgs.optJSONObject(it)?.opt("content") is JSONArray }
    }

    /**
     * Pesquisa na internet pela Groq (busca no navegador do próprio modelo gpt-oss), para quando a busca do Google
     * pelo Gemini não está disponível (cota esgotada ou plano grátis). Null sem chave ou se não der.
     */
    fun pesquisar(pergunta: String, instrucao: String? = null): Web.Resposta? {
        val chave = chave()
        if (chave.isBlank() || provedor().id != "groq") return null
        val agora = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date())
        for (modelo in listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")) {
            val corpo = JSONObject()
                .put("model", modelo)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        (instrucao ?: ("Pesquise na internet e responda em português do Brasil, em até 4 frases, direto ao ponto, sem markdown e sem tabelas. " +
                            "Se for sobre uma loja ou produto, resuma a reputação e as reclamações mais comuns.")) + " Hoje é $agora."))
                    .put(JSONObject().put("role", "user").put("content", pergunta)))
                .put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
                .put("tool_choice", "required")
                .put("reasoning_effort", "low")
            var c: HttpURLConnection? = null
            try {
                c = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 45_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $chave")
                }
                c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
                val codigo = c.responseCode
                val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (codigo !in 200..299) {
                    ctx?.let { Registro.anotar(it, "erro", "pesquisa pela Groq → $codigo ${texto.take(200)}") }
                    continue
                }
                val msg = JSONObject(texto).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: continue
                val resposta = limparPesquisa(msg.optString("content"))
                if (resposta.isBlank()) continue
                ultimaVez = System.currentTimeMillis()
                return Web.Resposta(resposta, fontesDe(msg.opt("executed_tools")).take(6))
            } catch (e: Exception) {
                continue
            } finally {
                c?.disconnect()
            }
        }
        return null
    }

    /** Tira as marcas de citação (【2†L6-L10】) e a formatação. */
    fun limparPesquisa(t: String): String = t
        .replace(Regex("【[^】]*】"), "")
        .replace(Regex("\\[\\d+(?:[,†][^\\]]*)?]"), "")
        .replace(Regex("[*#`]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s+([.,;:!?])"), "$1")
        .trim()
        .take(1200)

    /** Procura {title, url} em qualquer lugar do que a ferramenta de busca devolveu. */
    fun fontesDe(v: Any?): List<Web.Fonte> {
        val saida = mutableListOf<Web.Fonte>()
        fun andar(x: Any?) {
            when (x) {
                is JSONObject -> {
                    val url = x.optString("url")
                    if (url.startsWith("http") && saida.none { it.url == url }) saida += Web.Fonte(x.optString("title").ifBlank { "fonte" }, url)
                    x.keys().forEach { andar(x.opt(it)) }
                }
                is JSONArray -> for (i in 0 until x.length()) andar(x.opt(i))
                is String -> if (x.trimStart().startsWith("{") || x.trimStart().startsWith("[")) try {
                    andar(if (x.trimStart().startsWith("{")) JSONObject(x) else JSONArray(x))
                } catch (e: Exception) {
                }
            }
        }
        andar(v)
        return saida
    }

    /** Teste rápido da chave (para o botão "Salvar e testar"). Devolve a resposta ou lança erro com a explicação. */
    fun testar(c: Context, segunda: Boolean = false): String {
        iniciar(c)
        val (p, k) = if (segunda) (cloudflare(c) ?: throw IllegalStateException("Falta o código da conta (32 letras e números) ou a chave da Cloudflare."))
        else provedor(c) to chave(c)
        val corpo = JSONObject().put(
            "contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Responda só: funcionando"))))
        )
        val pedido = paraOpenAI(corpo) ?: throw IllegalStateException("Pedido de teste inválido.")
        ultimoErro = null
        val r = responderCom(p, k, pedido, 20_000, "low")
            ?: throw IllegalStateException("A ${p.nome.substringBefore(" (")} não respondeu. Confira a chave." + (ultimoErro?.let { " ($it)" } ?: ""))
        return textoDe(r)
    }

    private sealed class Resultado {
        class Ok(val json: JSONObject) : Resultado()
        object ModeloNaoExiste : Resultado()
        object Limite : Resultado()
        object Falhou : Resultado()
    }

    private fun chamar(p: Provedor, chave: String, modelo: String, pedido: JSONObject, tempo: Int, ajuste: Boolean = true, esforco: String = "low"): Resultado {
        val corpo = JSONObject(pedido.toString()).put("model", modelo)
        if (ajuste && modelo.contains("gpt-oss")) corpo.put("reasoning_effort", esforco)
        var c: HttpURLConnection? = null
        return try {
            c = (URL("${p.endereco}/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = tempo
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $chave")
            }
            c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                codigo in 200..299 -> Resultado.Ok(JSONObject(texto))
                codigo == 404 || (codigo == 400 && texto.contains("model", true) && (texto.contains("not found", true) || texto.contains("does not exist", true) || texto.contains("decommissioned", true))) -> Resultado.ModeloNaoExiste
                codigo == 429 || codigo == 413 || texto.contains("allocation", true) -> Resultado.Limite
                // modelo que não aceitou o ajuste de raciocínio: tenta sem ele
                codigo == 400 && corpo.has("reasoning_effort") -> chamar(p, chave, modelo, pedido, tempo, ajuste = false)
                // modelo que não aceitou o formato JSON obrigatório: o pedido já diz para responder em JSON
                codigo == 400 && pedido.has("response_format") -> chamar(p, chave, modelo, JSONObject(pedido.toString()).apply { remove("response_format") }, tempo, ajuste, esforco)
                else -> {
                    ultimoErro = "erro $codigo: ${texto.take(160)}"
                    ctx?.let { Registro.anotar(it, "erro", "IA reserva ${p.nome} → $codigo ${texto.take(200)}") }
                    Resultado.Falhou
                }
            }
        } catch (e: Exception) {
            Resultado.Falhou
        } finally {
            c?.disconnect()
        }
    }

    private fun listarModelos(p: Provedor, chave: String): List<String> = try {
        val c = URL("${p.endereco}/models").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Authorization", "Bearer $chave")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val lista = json.optJSONArray("data") ?: JSONArray()
        (0 until lista.length()).mapNotNull { lista.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() && !it.contains("whisper") && !it.contains("guard") && !it.contains("tts") }
    } catch (e: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ Gemini → OpenAI
    /** Converte o pedido do Gemini; null se ele depende de PDF, áudio ou foto grande demais. */
    fun paraOpenAI(g: JSONObject): JSONObject? {
        val mensagens = JSONArray()
        g.optJSONObject("systemInstruction")?.let { s -> textos(s.optJSONArray("parts")).takeIf { it.isNotBlank() }?.let { mensagens.put(JSONObject().put("role", "system").put("content", it)) } }
        val conteudos = g.optJSONArray("contents") ?: JSONArray()
        var proximoId = 0
        var fotos = 0
        val pendentes = ArrayDeque<Pair<String, String>>() // (nome da ferramenta, id da chamada)
        for (i in 0 until conteudos.length()) {
            val c = conteudos.optJSONObject(i) ?: continue
            val partes = c.optJSONArray("parts") ?: JSONArray()
            val texto = StringBuilder()
            val chamadas = JSONArray()
            val imagens = JSONArray()
            for (j in 0 until partes.length()) {
                val pt = partes.optJSONObject(j) ?: continue
                when {
                    pt.has("inlineData") || pt.has("inline_data") -> {
                        // foto vai como imagem (data URI) para os modelos que enxergam; PDF e áudio não
                        val d = pt.optJSONObject("inlineData") ?: pt.optJSONObject("inline_data") ?: return null
                        val tipo = d.optString("mimeType").ifBlank { d.optString("mime_type") }
                        if (!tipo.startsWith("image/") || d.optString("data").length > LIMITE_FOTO) return null
                        imagens.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$tipo;base64,${d.optString("data")}")))
                    }
                    pt.has("fileData") || pt.has("file_data") -> return null
                    pt.optBoolean("thought", false) -> Unit
                    pt.has("functionCall") -> {
                        val fc = pt.getJSONObject("functionCall")
                        val id = "chamada_${proximoId++}"
                        pendentes.addLast(fc.optString("name") to id)
                        chamadas.put(
                            JSONObject().put("id", id).put("type", "function")
                                .put("function", JSONObject().put("name", fc.optString("name")).put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString()))
                        )
                    }
                    pt.has("functionResponse") -> {
                        val fr = pt.getJSONObject("functionResponse")
                        val nome = fr.optString("name")
                        val par = pendentes.firstOrNull { it.first == nome } ?: pendentes.firstOrNull()
                        if (par != null) pendentes.remove(par)
                        mensagens.put(
                            JSONObject().put("role", "tool").put("tool_call_id", par?.second ?: "chamada_${proximoId++}")
                                .put("content", (fr.opt("response") ?: JSONObject()).toString().take(8000))
                        )
                    }
                    pt.has("text") -> texto.append(pt.optString("text"))
                }
            }
            val papel = if (c.optString("role") == "model") "assistant" else "user"
            if (papel == "assistant" && chamadas.length() > 0) {
                mensagens.put(JSONObject().put("role", "assistant").put("content", texto.toString().ifBlank { JSONObject.NULL }).put("tool_calls", chamadas))
            } else if (imagens.length() > 0 && papel == "user") {
                if (fotos + imagens.length() > MAXIMO_FOTOS) return null
                fotos += imagens.length()
                val partes = JSONArray()
                if (texto.isNotBlank()) partes.put(JSONObject().put("type", "text").put("text", texto.toString()))
                for (k in 0 until imagens.length()) partes.put(imagens.get(k))
                mensagens.put(JSONObject().put("role", "user").put("content", partes))
            } else if (texto.isNotBlank()) {
                mensagens.put(JSONObject().put("role", papel).put("content", texto.toString()))
            }
        }
        if (mensagens.length() == 0) return null
        val pedido = JSONObject().put("messages", mensagens)
        // ferramentas (ações no celular)
        val declaracoes = JSONArray()
        g.optJSONArray("tools")?.let { t ->
            for (i in 0 until t.length()) t.optJSONObject(i)?.optJSONArray("functionDeclarations")?.let { fd ->
                for (j in 0 until fd.length()) fd.optJSONObject(j)?.let { d ->
                    declaracoes.put(
                        JSONObject().put("type", "function").put(
                            "function", JSONObject().put("name", d.optString("name")).put("description", d.optString("description").take(1000))
                                .put("parameters", esquema(d.optJSONObject("parameters") ?: JSONObject().put("type", "OBJECT")))
                        )
                    )
                }
            }
        }
        if (declaracoes.length() > 0) pedido.put("tools", declaracoes)
        val config = g.optJSONObject("generationConfig")
        config?.optDouble("temperature")?.takeIf { !it.isNaN() }?.let { pedido.put("temperature", it) }
        if (config?.optString("responseMimeType") == "application/json") pedido.put("response_format", JSONObject().put("type", "json_object"))
        return pedido
    }

    /** A Groq aceita até 5 fotos por pedido, cada uma com até 4 MB em base64. */
    const val MAXIMO_FOTOS = 5
    const val LIMITE_FOTO = 4_000_000

    /** Esquema do Gemini (tipos em maiúsculas) → JSON Schema (minúsculas). */
    fun esquema(e: JSONObject): JSONObject {
        val s = JSONObject()
        val tipo = e.optString("type").lowercase().ifBlank { "object" }
        s.put("type", tipo)
        e.optString("description").takeIf { it.isNotBlank() }?.let { s.put("description", it) }
        e.optJSONArray("enum")?.let { s.put("enum", it) }
        e.optJSONObject("properties")?.let { props ->
            val novas = JSONObject()
            props.keys().forEach { k -> props.optJSONObject(k)?.let { novas.put(k, esquema(it)) } }
            s.put("properties", novas)
        }
        e.optJSONArray("required")?.let { s.put("required", it) }
        e.optJSONObject("items")?.let { s.put("items", esquema(it)) }
        if (tipo == "object" && !s.has("properties")) s.put("properties", JSONObject())
        return s
    }

    private fun textos(partes: JSONArray?): String {
        if (partes == null) return ""
        return (0 until partes.length()).mapNotNull { partes.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }.joinToString("\n")
    }

    // ------------------------------------------------------------------ OpenAI → Gemini
    fun paraGemini(o: JSONObject): JSONObject? {
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        val partes = JSONArray()
        val texto = msg.optString("content").takeIf { it.isNotBlank() && it != "null" }
        if (texto != null) partes.put(JSONObject().put("text", texto))
        msg.optJSONArray("tool_calls")?.let { tc ->
            for (i in 0 until tc.length()) {
                val f = tc.optJSONObject(i)?.optJSONObject("function") ?: continue
                val args = try {
                    JSONObject(f.optString("arguments").ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                // assinatura neutra: o Gemini 3 exige uma em chamadas de ferramenta que voltam no histórico
                partes.put(JSONObject().put("functionCall", JSONObject().put("name", f.optString("name")).put("args", args)).put("thoughtSignature", "skip_thought_signature_validator"))
            }
        }
        if (partes.length() == 0) return null
        return JSONObject().put(
            "candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", partes)).put("finishReason", "STOP"))
        )
    }

    fun textoDe(geminiJson: JSONObject): String {
        val partes = geminiJson.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return textos(partes)
    }
}
