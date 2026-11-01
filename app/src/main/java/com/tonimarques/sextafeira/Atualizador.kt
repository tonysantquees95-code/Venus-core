package com.tonimarques.sextafeira

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Atualização automática: procura uma versão nova nas publicações do GitHub (as mesmas do link de download),
 * baixa o Venus.apk e abre a tela de instalação do Android. Os dados do app continuam.
 *
 * O repositório é privado, então a consulta precisa de um token do GitHub só de leitura
 * (guardado criptografado no celular). Sem token, tenta sem (funciona se o repositório virar público).
 */
class Atualizador(private val contexto: Context) {

    private val prefs = contexto.getSharedPreferences("atualizacao", Context.MODE_PRIVATE)

    class ErroDeAtualizacao(mensagem: String, val precisaToken: Boolean = false) : Exception(mensagem)

    data class Versao(val numero: Int, val tag: String, val urlApi: String, val urlPublica: String, val tamanho: Long, val notas: String)

    val temToken get() = prefs.getString(P_TOKEN, null) != null

    fun guardarToken(token: String?) {
        if (token.isNullOrBlank()) prefs.edit().remove(P_TOKEN).apply()
        else prefs.edit().putString(P_TOKEN, cifrar(token.trim())).apply()
    }

    private fun token(): String? = prefs.getString(P_TOKEN, null)?.let { decifrar(it) }

    fun versaoInstalada(): Int = try {
        numeroDaVersao(contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName.orEmpty()) ?: 0
    } catch (e: Exception) {
        0
    }

    /** Já procurou nas últimas horas? (para a busca automática ao abrir o app não gastar internet à toa) */
    fun procurouRecentemente(horas: Int = 6): Boolean =
        System.currentTimeMillis() - prefs.getLong(P_ULTIMA, 0) < horas * 3600_000L

    /** A versão mais nova publicada, ou null se a instalada já é a última. Roda fora da thread principal. */
    fun procurar(): Versao? {
        prefs.edit().putLong(P_ULTIMA, System.currentTimeMillis()).apply()
        val c = abrir("https://api.github.com/repos/$REPO/releases/latest", token())
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val codigo = c.responseCode
        val texto = ler(c)
        when (codigo) {
            in 200..299 -> {}
            401 -> throw ErroDeAtualizacao("O token do GitHub não vale mais. Cadastre um novo.", precisaToken = true)
            403, 404 -> throw ErroDeAtualizacao(
                if (token() == null) "Para procurar atualizações, cadastre o token do GitHub." else "O token não tem acesso às versões da Vênus.",
                precisaToken = true,
            )
            else -> throw ErroDeAtualizacao("O GitHub respondeu com erro $codigo.")
        }
        val v = lerVersao(JSONObject(texto)) ?: throw ErroDeAtualizacao("A última publicação não tem o Venus.apk.")
        return if (v.numero > versaoInstalada()) v else null
    }

    /** Baixa o APK e pede ao Android para instalar. [progresso] recebe de 0 a 100. */
    fun baixarEInstalar(v: Versao, progresso: (Int) -> Unit) {
        val conexao = baixar(v)
        val instalador = contexto.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(contexto.packageName)
        }
        val id = instalador.createSession(params)
        try {
            instalador.openSession(id).use { sessao ->
                val total = if (conexao.contentLengthLong > 0) conexao.contentLengthLong else v.tamanho
                sessao.openWrite("Venus.apk", 0, total.coerceAtLeast(-1)).use { saida ->
                    conexao.inputStream.use { entrada ->
                        val buffer = ByteArray(64 * 1024)
                        var lidos = 0L
                        var ultimo = -1
                        while (true) {
                            val n = entrada.read(buffer)
                            if (n < 0) break
                            saida.write(buffer, 0, n)
                            lidos += n
                            val pct = if (total > 0) ((lidos * 100) / total).toInt().coerceIn(0, 100) else 0
                            if (pct != ultimo) {
                                ultimo = pct
                                progresso(pct)
                            }
                        }
                    }
                    sessao.fsync(saida)
                }
                // O Android devolve o resultado para a MainActivity, que abre a tela "Instalar?"
                val volta = Intent(contexto, MainActivity::class.java).setAction(ACAO_INSTALAR)
                val pendente = PendingIntent.getActivity(
                    contexto, 77, volta, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                sessao.commit(pendente.intentSender)
            }
        } catch (e: Exception) {
            try {
                instalador.abandonSession(id)
            } catch (x: Exception) {
            }
            throw e
        } finally {
            conexao.disconnect()
        }
    }

    /** Abre o download do APK, seguindo o redirecionamento do GitHub sem mandar o token para o outro site. */
    private fun baixar(v: Versao): HttpURLConnection {
        val t = token()
        var c = if (t != null) abrir(v.urlApi, t) else abrir(v.urlPublica, null)
        if (t != null) c.setRequestProperty("Accept", "application/octet-stream")
        repeat(5) {
            val codigo = c.responseCode
            if (codigo in 300..399) {
                val destino = c.getHeaderField("Location") ?: throw ErroDeAtualizacao("O download não abriu.")
                c.disconnect()
                c = abrir(destino, null)
                return@repeat
            }
            if (codigo !in 200..299) throw ErroDeAtualizacao("O download falhou (erro $codigo).")
            return c
        }
        throw ErroDeAtualizacao("O download não abriu.")
    }

    private fun abrir(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Venus-Android")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun ler(c: HttpURLConnection): String = try {
        (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } finally {
        c.disconnect()
    }

    // ------------------------------------------------------------- criptografia do token
    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gerador.generateKey()
    }

    private fun cifrar(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(texto.toByteArray()), Base64.NO_WRAP)
    }

    private fun decifrar(guardado: String): String? = try {
        val (iv, dados) = guardado.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, iv))
        String(c.doFinal(dados))
    } catch (e: Exception) {
        null
    }

    companion object {
        const val REPO = "tonysantquees95-code/Sexta-feira-"
        const val ACAO_INSTALAR = "com.tonimarques.sextafeira.INSTALAR"
        private const val P_TOKEN = "token"
        private const val P_ULTIMA = "ultima_busca"
        private const val ALIAS = "venus_github"

        /** "1.38", "v1.38" → 38. */
        fun numeroDaVersao(texto: String): Int? = Regex("^v?1\\.(\\d+)").find(texto.trim())?.groupValues?.get(1)?.toIntOrNull()

        /** Lê a publicação do GitHub (tag e o arquivo Venus.apk). */
        fun lerVersao(json: JSONObject): Versao? {
            val tag = json.optString("tag_name")
            val numero = numeroDaVersao(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
                return Versao(
                    numero, tag, a.optString("url"), a.optString("browser_download_url"), a.optLong("size"),
                    notas(json.optString("body")),
                )
            }
            return null
        }

        /** O texto da publicação, sem as linhas técnicas. */
        fun notas(corpo: String): String = corpo.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("Co-Authored-By", true) && !it.startsWith("Claude-Session", true) }
            .filterNot { it.startsWith("Baixe o arquivo") }
            .joinToString("\n")
            .take(600)
    }
}
