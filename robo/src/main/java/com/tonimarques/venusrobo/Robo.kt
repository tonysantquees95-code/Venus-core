package com.tonimarques.venusrobo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configuração do robô e a conversa com a Vênus da loja (site).
 * O site recebe {messengerPackageName, query:{sender, message, isGroup}} com a chave no cabeçalho x-venus-chave
 * e devolve {"replies":[{"message","delay","delayMax"}], "humano": true|false}.
 */
object Robo {

    const val ENDERECO_PADRAO = "https://desejo-de-intimidade.floot.app/_api/venus/robo"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val WHATSAPP = "com.whatsapp"
    const val INSTAGRAM = "com.instagram.android"
    val NOMES = mapOf(WHATSAPP_BUSINESS to "WhatsApp Business", WHATSAPP to "WhatsApp", INSTAGRAM to "Instagram")

    data class Resposta(val mensagens: List<String>, val atrasoMin: Int, val atrasoMax: Int, val humano: Boolean)
    data class Registro(val quando: Long, val canal: String, val cliente: String, val mensagem: String, val resposta: String, val humano: Boolean, val erro: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("robo", Context.MODE_PRIVATE)

    fun ligado(ctx: Context) = prefs(ctx).getBoolean("ligado", true)
    fun definirLigado(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("ligado", v).apply()
    fun endereco(ctx: Context) = prefs(ctx).getString("endereco", ENDERECO_PADRAO).orEmpty().ifBlank { ENDERECO_PADRAO }
    /** A chave fica cifrada com o chip de segurança do Android (as antigas, em texto, são cifradas na primeira leitura). */
    fun chave(ctx: Context) = Cofre.ler(prefs(ctx), "chave")
    fun salvar(ctx: Context, endereco: String, chave: String) =
        prefs(ctx).edit().putString("endereco", endereco.trim()).putString("chave", Cofre.selar(chave.trim())).apply()

    /** Em quais apps ele responde (padrão: WhatsApp Business e Instagram). */
    fun canalLigado(ctx: Context, pacote: String) = prefs(ctx).getBoolean("canal_$pacote", pacote != WHATSAPP)
    fun definirCanal(ctx: Context, pacote: String, v: Boolean) = prefs(ctx).edit().putBoolean("canal_$pacote", v).apply()

    // ------------------------------------------------------------------ pergunta à Vênus da loja
    fun corpo(pacote: String, cliente: String, mensagem: String, teste: Boolean): JSONObject = JSONObject()
        .put("appPackageName", "com.tonimarques.venusrobo")
        .put("messengerPackageName", pacote)
        .put("query", JSONObject()
            .put("sender", cliente.take(200))
            .put("message", mensagem.take(4000))
            .put("isGroup", false)
            .put("groupParticipant", "")
            .put("isTestMessage", teste))

    fun lerResposta(json: String): Resposta? = try {
        val o = JSONObject(json)
        val lista = o.optJSONArray("replies") ?: JSONArray()
        val msgs = (0 until lista.length()).mapNotNull { lista.optJSONObject(it)?.optString("message")?.takeIf { m -> m.isNotBlank() } }
        val primeira = lista.optJSONObject(0)
        val min = primeira?.optInt("delay", 3) ?: 3
        Resposta(msgs, min, maxOf(min, primeira?.optInt("delayMax", min) ?: min), o.optBoolean("humano", false))
    } catch (e: Exception) {
        null
    }

    /** Bloqueante: chame fora da thread principal. Devolve a resposta ou lança erro com a explicação. */
    fun perguntar(ctx: Context, pacote: String, cliente: String, mensagem: String, teste: Boolean = false): Resposta {
        val chave = chave(ctx)
        if (chave.isBlank()) throw IllegalStateException("Falta a chave do robô (está no painel da loja).")
        val c = URL(endereco(ctx)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-venus-chave", chave)
            c.outputStream.use { it.write(corpo(pacote, cliente, mensagem, teste).toString().toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo == 401) throw IllegalStateException("A chave do robô não confere. Copie de novo no painel da loja.")
            if (codigo == 404) throw IllegalStateException("O site ainda não tem o robô (endereço não encontrado).")
            if (codigo !in 200..299) throw IllegalStateException("O site respondeu com erro $codigo.")
            return lerResposta(texto) ?: throw IllegalStateException("O site respondeu algo que não entendi.")
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ registro (só no celular)
    fun anotar(ctx: Context, r: Registro) {
        val a = try {
            JSONArray(prefs(ctx).getString("registro", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        a.put(JSONObject().put("quando", r.quando).put("canal", r.canal).put("cliente", r.cliente.take(60)).put("mensagem", r.mensagem.take(300))
            .put("resposta", r.resposta.take(500)).put("humano", r.humano).put("erro", r.erro))
        while (a.length() > 40) a.remove(0)
        prefs(ctx).edit().putString("registro", a.toString()).apply()
    }

    fun registros(ctx: Context): List<Registro> {
        val a = try {
            JSONArray(prefs(ctx).getString("registro", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Registro(it.optLong("quando"), it.optString("canal"), it.optString("cliente"), it.optString("mensagem"), it.optString("resposta"),
                it.optBoolean("humano"), it.optBoolean("erro"))
        }.reversed()
    }

    fun hora(t: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(t))

    // ------------------------------------------------------------------ o que é mensagem de cliente
    private val MIDIA = Regex("^(?:📷|🎤|🎥|📄|🎵|📍|👤|🖼|GIF|Foto|Áudio|Audio|Vídeo|Video|Figurinha|Sticker|Documento|Localização|Contato|Mensagem apagada|Esta mensagem foi apagada|Chamada)", RegexOption.IGNORE_CASE)
    private val SISTEMA = Regex("(?:\\d+ (?:novas )?mensagens|mensagens de \\d+ conversas|Verificando novas mensagens|Checking for new messages|WhatsApp Web|está digitando|is typing|(?:curtiu|reagiu)(?: [aà])? sua mensagem|liked your message|reacted to your)", RegexOption.IGNORE_CASE)

    /** Texto que o robô deve responder (null = ignorar: mídia, aviso do sistema, vazio). */
    fun textoDaCliente(texto: String?): String? {
        val t = texto?.trim().orEmpty()
        if (t.isEmpty() || MIDIA.containsMatchIn(t) || SISTEMA.containsMatchIn(t)) return null
        return t
    }
}
