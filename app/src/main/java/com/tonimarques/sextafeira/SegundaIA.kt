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
        SegundaIA.iniciar(this)
    }
}

/**
 * A IA reserva: quando o Gemini trava, fica sem cota ou sai do ar, a Vênus pergunta para outra IA
 * (Groq, grátis com ~1.000 pedidos por dia, ou Grok da xAI, pago). As duas usam o formato da OpenAI.
 *
 * Para não mexer no resto do app, ela recebe o pedido no formato do Gemini (systemInstruction, contents,
 * functionDeclarations, generationConfig) e devolve a resposta também no formato do Gemini
 * (candidates → content → parts com text ou functionCall). Fotos não vão (a reserva é só texto).
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

    private const val PREFS = "config"
    @Volatile
    private var ctx: Context? = null

    /** Modelo que respondeu por último em cada provedor (evita tentar de novo os que não existem). */
    private val modeloQueFunciona = mutableMapOf<String, String>()

    @Volatile
    var ultimaVez: Long = 0
        private set

    fun iniciar(c: Context) {
        ctx = c.applicationContext
    }

    fun provedor(c: Context? = ctx): Provedor {
        val id = c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("reserva_provedor", "groq") ?: "groq"
        return PROVEDORES.firstOrNull { it.id == id } ?: PROVEDORES.first()
    }

    fun chave(c: Context? = ctx): String = c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("reserva_chave", "").orEmpty()

    fun configurada(c: Context? = ctx) = chave(c).isNotBlank()

    fun salvar(c: Context, provedor: String, chave: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("reserva_provedor", provedor).putString("reserva_chave", chave.trim()).apply()
        modeloQueFunciona.clear()
        Diario.anotar(c, "conexao", if (chave.isBlank()) "IA reserva desligada" else "IA reserva configurada: ${PROVEDORES.firstOrNull { it.id == provedor }?.nome ?: provedor}", "voz")
    }

    /**
     * Responde um pedido no formato do Gemini. Null se não houver reserva configurada ou ela também falhar.
     * Bloqueante: chame fora da thread principal.
     */
    fun responder(corpoGemini: JSONObject, tempo: Int = 30_000): JSONObject? {
        val chave = chave()
        if (chave.isBlank()) return null
        val p = provedor()
        val pedido = paraOpenAI(corpoGemini) ?: return null
        val ordem = (listOfNotNull(modeloQueFunciona[p.id]) + p.modelos).distinct()
        for (modelo in ordem) {
            when (val r = chamar(p, chave, modelo, pedido, tempo)) {
                is Resultado.Ok -> {
                    modeloQueFunciona[p.id] = modelo
                    ultimaVez = System.currentTimeMillis()
                    return paraGemini(r.json)
                }
                Resultado.ModeloNaoExiste -> continue
                Resultado.Falhou -> return null
            }
        }
        // nenhum dos modelos conhecidos existe mais: pergunta a lista ao provedor e usa o melhor
        val melhor = listarModelos(p, chave).firstOrNull { p.preferencia.containsMatchIn(it) } ?: return null
        val r = chamar(p, chave, melhor, pedido, tempo) as? Resultado.Ok ?: return null
        modeloQueFunciona[p.id] = melhor
        ultimaVez = System.currentTimeMillis()
        return paraGemini(r.json)
    }

    /** Teste rápido da chave (para o botão "Salvar e testar"). Devolve a resposta ou lança erro com a explicação. */
    fun testar(c: Context): String {
        iniciar(c)
        val corpo = JSONObject().put(
            "contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Responda só: funcionando"))))
        )
        val r = responder(corpo, 20_000) ?: throw IllegalStateException("A ${provedor(c).nome} não respondeu. Confira a chave.")
        return textoDe(r)
    }

    private sealed class Resultado {
        class Ok(val json: JSONObject) : Resultado()
        object ModeloNaoExiste : Resultado()
        object Falhou : Resultado()
    }

    private fun chamar(p: Provedor, chave: String, modelo: String, pedido: JSONObject, tempo: Int, ajuste: Boolean = true): Resultado {
        val corpo = JSONObject(pedido.toString()).put("model", modelo)
        if (ajuste && modelo.contains("gpt-oss")) corpo.put("reasoning_effort", "low")
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
                // modelo que não aceitou o ajuste de raciocínio: tenta sem ele
                codigo == 400 && corpo.has("reasoning_effort") -> chamar(p, chave, modelo, pedido, tempo, ajuste = false)
                else -> {
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
    /** Converte o pedido do Gemini; null se ele depende de foto ou áudio (a reserva é só texto). */
    fun paraOpenAI(g: JSONObject): JSONObject? {
        val mensagens = JSONArray()
        g.optJSONObject("systemInstruction")?.let { s -> textos(s.optJSONArray("parts")).takeIf { it.isNotBlank() }?.let { mensagens.put(JSONObject().put("role", "system").put("content", it)) } }
        val conteudos = g.optJSONArray("contents") ?: JSONArray()
        var proximoId = 0
        val pendentes = ArrayDeque<Pair<String, String>>() // (nome da ferramenta, id da chamada)
        for (i in 0 until conteudos.length()) {
            val c = conteudos.optJSONObject(i) ?: continue
            val partes = c.optJSONArray("parts") ?: JSONArray()
            val texto = StringBuilder()
            val chamadas = JSONArray()
            for (j in 0 until partes.length()) {
                val pt = partes.optJSONObject(j) ?: continue
                when {
                    pt.has("inlineData") || pt.has("inline_data") || pt.has("fileData") || pt.has("file_data") -> return null
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
