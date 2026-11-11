package com.tonimarques.sextafeira

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A conta da loja no servidor da Vênus: canais de venda, catálogo central, marketing, painel e assinatura.
 * O token da loja fica só neste celular (SharedPreferences) e é enviado apenas ao servidor da Vênus.
 */
object Nuvem {
    private const val PREFS = "config"
    private const val PREF_TOKEN = "nuvem_token"
    private const val PREF_SLUG = "nuvem_slug"

    class Resposta(val status: Int, val json: JSONObject) {
        val ok get() = status in 200..299
        fun erro(): String = json.optString("error").ifBlank { "Erro $status ao falar com o servidor." }
    }

    class ErroNuvem(mensagem: String) : Exception(mensagem)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun token(ctx: Context): String = prefs(ctx).getString(PREF_TOKEN, "").orEmpty()
    fun slug(ctx: Context): String = prefs(ctx).getString(PREF_SLUG, "").orEmpty()
    fun registrada(ctx: Context) = token(ctx).startsWith("vl_")

    /** Um identificador do aparelho que não volta ao servidor em claro: serve para dar um único teste grátis por celular. */
    fun dispositivo(ctx: Context): String = try {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        sha256(id)
    } catch (e: Exception) {
        ""
    }

    fun sha256(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Cria a conta da loja no servidor na primeira vez (7 dias de teste grátis). Chamar fora da tela principal (rede). */
    fun garantirConta(ctx: Context) {
        if (registrada(ctx)) return
        val corpo = JSONObject().put("nome", Perfil.loja).put("dispositivo", dispositivo(ctx))
        val r = enviar(ctx, "POST", "/loja/registrar", corpo, comToken = false)
        if (!r.ok) throw ErroNuvem(r.erro())
        prefs(ctx).edit().putString(PREF_TOKEN, r.json.optString("token")).putString(PREF_SLUG, r.json.optString("slug")).apply()
    }

    fun pedir(ctx: Context, metodo: String, caminho: String, corpo: JSONObject? = null): Resposta {
        garantirConta(ctx)
        return enviar(ctx, metodo, caminho, corpo, comToken = true)
    }

    private fun enviar(ctx: Context, metodo: String, caminho: String, corpo: JSONObject?, comToken: Boolean): Resposta {
        if (!Servidor.configurado()) throw ErroNuvem("O servidor da Vênus ainda não está configurado.")
        val c = URL(Servidor.ENDERECO + caminho).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.setRequestProperty("Accept", "application/json")
            if (comToken) c.setRequestProperty("Authorization", "Bearer ${token(ctx)}")
            if (corpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Resposta(codigo, try { JSONObject(texto) } catch (e: Exception) { JSONObject() })
        } catch (e: ErroNuvem) {
            throw e
        } catch (e: Exception) {
            throw ErroNuvem("Sem conexão com o servidor da Vênus. Tente de novo em instantes.")
        } finally {
            c.disconnect()
        }
    }

    /** Pede um texto à IA do servidor (roteador /v1/chat). Devolve null se a IA estiver fora do ar: quem chama tem um plano B. */
    fun conversar(ctx: Context, sistema: String, pergunta: String): String? = try {
        val corpo = JSONObject().put("sistema", sistema).put("tarefa", "texto").put("temperatura", 0.9)
            .put("mensagens", org.json.JSONArray().put(JSONObject().put("papel", "user").put("texto", pergunta)))
        val r = enviar(ctx, "POST", "/v1/chat", corpo, comToken = false)
        if (r.ok) r.json.optString("resposta").takeIf { it.isNotBlank() } else null
    } catch (e: Exception) {
        null
    }

    /** Endereço do painel de BI em tempo real, já com o token no #fragmento (que nunca vai para o servidor). */
    fun enderecoDoPainel(ctx: Context) = "${Servidor.ENDERECO}/painel#t=${token(ctx)}"

    fun reais(centavos: Long): String = java.text.NumberFormat.getCurrencyInstance(java.util.Locale("pt", "BR")).format(centavos / 100.0)
}
