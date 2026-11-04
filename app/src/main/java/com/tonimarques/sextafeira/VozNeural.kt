package com.tonimarques.sextafeira

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voz neural da Microsoft (Azure Speech): vozes brasileiras naturais, bem menos "robô" que a voz do celular.
 * Plano grátis (F0): 500 mil caracteres por mês. A Vênus conta o que usou e, perto do limite, volta sozinha
 * para a voz do celular até o mês virar (nunca gera cobrança por conta própria).
 * Sem internet, sem chave ou se a Microsoft não responder em poucos segundos, também usa a voz do celular.
 * Frases repetidas ("Pronto!", "Anotado") ficam guardadas e não gastam de novo.
 */
object VozNeural {

    data class Opcao(val id: String, val nome: String, val descricao: String)

    /** Vozes femininas em português do Brasil. */
    val VOZES = listOf(
        Opcao("pt-BR-FranciscaNeural", "Francisca", "Firme e madura (recomendada para o estilo assistente)"),
        Opcao("pt-BR-ThalitaMultilingualNeural", "Thalita", "Natural e expressiva"),
        Opcao("pt-BR-ElzaNeural", "Elza", "Serena"),
        Opcao("pt-BR-BrendaNeural", "Brenda", "Clara e jovem"),
        Opcao("pt-BR-GiovannaNeural", "Giovanna", "Suave"),
        Opcao("pt-BR-LeilaNeural", "Leila", "Calorosa"),
        Opcao("pt-BR-LeticiaNeural", "Leticia", "Leve"),
        Opcao("pt-BR-ManuelaNeural", "Manuela", "Delicada"),
        Opcao("pt-BR-YaraNeural", "Yara", "Simpática"),
    )

    /** Jeitos de falar (ajuste de velocidade e tom em cima da voz escolhida). */
    data class Estilo(val id: String, val nome: String, val velocidade: String, val tom: String)

    val ESTILOS = listOf(
        Estilo("assistente", "Assistente de laboratório (calma, grave e confiante)", "-6%", "-6%"),
        Estilo("natural", "Natural", "0%", "0%"),
        Estilo("agil", "Ágil (mais rápida)", "+8%", "-2%"),
    )

    const val LIMITE_GRATIS = 500_000
    /** Para antes do limite, com folga. */
    private const val LIMITE_SEGURO = 470_000
    private const val PREFS = "voz_neural"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chave(ctx: Context) = prefs(ctx).getString("chave", "").orEmpty()
    fun regiao(ctx: Context) = prefs(ctx).getString("regiao", "brazilsouth").orEmpty().ifBlank { "brazilsouth" }
    fun voz(ctx: Context) = VOZES.firstOrNull { it.id == prefs(ctx).getString("voz", null) } ?: VOZES.first()
    fun estilo(ctx: Context) = ESTILOS.firstOrNull { it.id == prefs(ctx).getString("estilo", null) } ?: ESTILOS.first()
    fun ligada(ctx: Context) = prefs(ctx).getBoolean("ligada", true) && chave(ctx).isNotBlank()

    fun salvar(ctx: Context, chave: String, regiao: String, voz: String, estilo: String, ligada: Boolean = true) {
        prefs(ctx).edit().putString("chave", chave.trim()).putString("regiao", regiao.trim().lowercase().replace(" ", ""))
            .putString("voz", voz).putString("estilo", estilo).putBoolean("ligada", ligada).apply()
        Diario.anotar(ctx, "conexao", if (chave.isBlank() || !ligada) "Voz neural desligada" else "Voz neural: ${VOZES.firstOrNull { it.id == voz }?.nome ?: voz}", "voz")
    }

    fun definirLigada(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("ligada", v).apply()

    private fun mes() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    /** Caracteres usados no mês. */
    fun usados(ctx: Context): Int = if (prefs(ctx).getString("mes", "") == mes()) prefs(ctx).getInt("usados", 0) else 0

    private fun somar(ctx: Context, n: Int) {
        val atual = usados(ctx)
        prefs(ctx).edit().putString("mes", mes()).putInt("usados", atual + n).apply()
    }

    /** Ativa e dentro do limite grátis do mês? */
    fun ativa(ctx: Context) = ligada(ctx) && usados(ctx) < LIMITE_SEGURO

    fun resumo(ctx: Context): String {
        if (chave(ctx).isBlank()) return "Desligada. Com uma chave grátis da Microsoft (Azure Speech), a voz fica bem mais natural."
        val estado = if (!prefs(ctx).getBoolean("ligada", true)) "Desligada" else "Ligada: ${voz(ctx).nome}, ${estilo(ctx).nome.substringBefore(" (").lowercase()}"
        val pct = usados(ctx) * 100 / LIMITE_GRATIS
        return "$estado. Usou $pct% do limite grátis deste mês" + if (usados(ctx) >= LIMITE_SEGURO) " (voltou para a voz do celular até o mês virar)." else "."
    }

    /** Monta o SSML (texto escapado, voz e estilo). */
    fun ssml(texto: String, voz: String, estilo: Estilo): String {
        val seguro = texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        return "<speak version='1.0' xml:lang='pt-BR' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='https://www.w3.org/2001/mstts'>" +
            "<voice name='$voz'><prosody rate='${estilo.velocidade}' pitch='${estilo.tom}'>$seguro</prosody></voice></speak>"
    }

    private fun pastaCache(ctx: Context) = File(ctx.cacheDir, "voz_neural").apply { mkdirs() }

    /**
     * Gera o áudio (MP3) do texto. Bloqueante: chame fora da thread principal.
     * Null se não der (sem chave, sem internet, limite do mês, Microsoft fora do ar): use a voz do celular.
     */
    fun sintetizar(ctx: Context, texto: String, voz: Opcao = voz(ctx), estilo: Estilo = estilo(ctx), tempo: Int = 6_000): File? {
        val chave = chave(ctx)
        val limpo = texto.trim().take(3000)
        if (chave.isBlank() || limpo.isEmpty()) return null
        val nome = MessageDigest.getInstance("SHA-1").digest("${voz.id}|${estilo.id}|$limpo".toByteArray()).joinToString("") { "%02x".format(it) } + ".mp3"
        val arquivo = File(pastaCache(ctx), nome)
        if (arquivo.exists() && arquivo.length() > 0) {
            arquivo.setLastModified(System.currentTimeMillis())
            return arquivo
        }
        if (usados(ctx) + limpo.length > LIMITE_SEGURO) return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://${regiao(ctx)}.tts.speech.microsoft.com/cognitiveservices/v1").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = minOf(4_000, tempo)
                readTimeout = tempo
                doOutput = true
                setRequestProperty("Ocp-Apim-Subscription-Key", chave)
                setRequestProperty("Content-Type", "application/ssml+xml")
                setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                setRequestProperty("User-Agent", "VenusApp")
            }
            c.outputStream.use { it.write(ssml(limpo, voz.id, estilo).toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            if (codigo !in 200..299) {
                val erro = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Registro.anotar(ctx, "erro", "voz neural → $codigo ${erro.take(150)}")
                return null
            }
            val temp = File(pastaCache(ctx), "$nome.parcial")
            c.inputStream.use { entrada -> temp.outputStream().use { entrada.copyTo(it) } }
            if (temp.length() == 0L) return null
            temp.renameTo(arquivo)
            somar(ctx, limpo.length)
            limparCache(ctx)
            arquivo
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Teste da chave: lança erro com a explicação, para mostrar na tela. */
    fun testar(ctx: Context): File {
        if (chave(ctx).isBlank()) throw IllegalStateException("Falta a chave.")
        return sintetizar(ctx, "Olá, Toni. Sistemas prontos. No que posso ajudar?", tempo = 10_000)
            ?: throw IllegalStateException("A Microsoft não respondeu. Confira a chave e a região (ex.: brazilsouth, eastus).")
    }

    /** Guarda no máximo uns 300 áudios (os mais usados ficam). */
    private fun limparCache(ctx: Context) {
        val arquivos = pastaCache(ctx).listFiles()?.filter { it.name.endsWith(".mp3") } ?: return
        if (arquivos.size <= 300) return
        arquivos.sortedBy { it.lastModified() }.take(arquivos.size - 300).forEach { it.delete() }
    }
}
