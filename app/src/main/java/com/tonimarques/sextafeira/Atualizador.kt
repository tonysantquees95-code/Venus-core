package com.tonimarques.sextafeira

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
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
 * O repositório é privado: o dono consulta com um token do GitHub só de leitura (guardado criptografado no celular);
 * os outros lojistas, sem token, usam o canal público do site ([CANAL]), que devolve a versão nova com um link
 * temporário do APK. Nos dois casos o arquivo é conferido pelo SHA-256 publicado antes de instalar.
 *
 * Também cuida do Vênus Robô (app separado, publicado como "robo-1.N"): se ele estiver instalado e houver
 * versão nova, a Vênus baixa e abre a instalação dele do mesmo jeito. O token fica só na Vênus.
 */
class Atualizador(private val contexto: Context) {

    private val prefs = contexto.getSharedPreferences("atualizacao", Context.MODE_PRIVATE)

    class ErroDeAtualizacao(mensagem: String, val precisaToken: Boolean = false) : Exception(mensagem)

    data class Versao(
        val numero: Int, val tag: String, val urlApi: String, val urlPublica: String, val tamanho: Long, val notas: String,
        /** Qual app esta versão atualiza (vazio = a própria Vênus). */
        val pacote: String = "",
        /** Impressão digital SHA-256 que o GitHub publica para o arquivo (vazio = o GitHub não informou). */
        val sha256: String = "",
        /** Veio do canal do site: o link do APK vale poucos minutos, então é pedido de novo na hora de baixar. */
        val viaSite: Boolean = false,
    ) {
        val ehRobo get() = pacote == PACOTE_ROBO
    }

    val temToken get() = prefs.getString(P_TOKEN, null) != null

    fun guardarToken(token: String?) {
        if (token.isNullOrBlank()) prefs.edit().remove(P_TOKEN).apply()
        else prefs.edit().putString(P_TOKEN, cifrar(token.trim())).apply()
    }

    private fun token(): String? = prefs.getString(P_TOKEN, null)?.let { decifrar(it) }

    /** O mesmo token, para mandar os relatórios de erro (precisa da permissão "Issues: Read and write"). */
    fun tokenParaRelatorio(): String? = token()

    fun versaoInstalada(): Int = try {
        numeroDaVersao(contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName.orEmpty()) ?: 0
    } catch (e: Exception) {
        0
    }

    /** Versão do Vênus Robô instalado, ou null se ele não está no celular. */
    fun versaoDoRobo(): Int? = try {
        numeroDaVersao(contexto.packageManager.getPackageInfo(PACOTE_ROBO, 0).versionName.orEmpty()) ?: 0
    } catch (e: Exception) {
        null
    }

    /** Faz a próxima abertura procurar de novo (depois de atualizar a Vênus, para oferecer o robô em seguida). */
    fun esquecerBusca() = prefs.edit().remove(P_ULTIMA).apply()

    /**
     * A versão nova do Vênus Robô, ou null (não instalado, já está na última, ou não deu para consultar).
     * Nunca lança erro: o robô é extra, não deve atrapalhar a atualização da Vênus.
     */
    fun procurarRobo(): Versao? {
        val instalada = versaoDoRobo() ?: return null
        val doCanalPublico = {
            try {
                doCanal(robo = true)?.takeIf { it.numero > instalada }
            } catch (e: Exception) {
                null
            }
        }
        if (token() == null) return doCanalPublico()
        return try {
            val c = apiGet("https://api.github.com/repos/$REPO/releases?per_page=100", token())
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return doCanalPublico() // token sem acesso ou GitHub com problema: o canal do servidor resolve
            }
            val v = ultimoRobo(JSONArray(ler(c))) ?: return null
            if (v.numero > instalada) v else null
        } catch (e: Exception) {
            doCanalPublico()
        }
    }

    /** Já procurou nas últimas horas? (para a busca automática ao abrir o app não gastar internet à toa) */
    fun procurouRecentemente(horas: Int = 6): Boolean =
        System.currentTimeMillis() - prefs.getLong(P_ULTIMA, 0) < horas * 3600_000L

    /** A versão mais nova publicada, ou null se a instalada já é a última. Roda fora da thread principal. */
    fun procurar(): Versao? {
        prefs.edit().putLong(P_ULTIMA, System.currentTimeMillis()).apply()
        if (token() == null) {
            val v = doCanal(robo = false) ?: throw ErroDeAtualizacao("Não consegui consultar as versões agora. Tente mais tarde.")
            return if (v.numero > versaoInstalada()) v else null
        }
        // Com token: consulta o GitHub direto. Se der qualquer problema (repositório renomeado, token sem acesso,
        // GitHub fora do ar), o canal do servidor da Vênus assume, para a atualização pelo app nunca parar.
        val erro: ErroDeAtualizacao = try {
            return procurarComToken()
        } catch (e: ErroDeAtualizacao) {
            e
        } catch (e: Exception) {
            ErroDeAtualizacao("Não consegui consultar as versões agora. Tente mais tarde.")
        }
        val v = doCanal(robo = false) ?: throw erro
        return if (v.numero > versaoInstalada()) v else null
    }

    private fun procurarComToken(): Versao? {
        val c = apiGet("https://api.github.com/repos/$REPO/releases/latest", token())
        val codigo = c.responseCode
        val texto = ler(c)
        when (codigo) {
            in 200..299 -> {}
            401 -> throw ErroDeAtualizacao("O token do GitHub não vale mais. Cadastre um novo.", precisaToken = true)
            403, 404 -> throw ErroDeAtualizacao("O token não tem acesso às versões da Vênus.", precisaToken = true)
            else -> throw ErroDeAtualizacao("O GitHub respondeu com erro $codigo.")
        }
        val v = lerVersao(JSONObject(texto)) ?: throw ErroDeAtualizacao("A última publicação não tem o Venus.apk.")
        return if (v.numero > versaoInstalada()) v else null
    }

    /** Baixa o APK e pede ao Android para instalar. [progresso] recebe de 0 a 100. */
    fun baixarEInstalar(v: Versao, progresso: (Int) -> Unit) {
        val conexao = baixar(v)
        val resumo = java.security.MessageDigest.getInstance("SHA-256")
        val instalador = contexto.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(v.pacote.ifBlank { contexto.packageName })
        }
        val id = instalador.createSession(params)
        try {
            instalador.openSession(id).use { sessao ->
                val total = if (conexao.contentLengthLong > 0) conexao.contentLengthLong else v.tamanho
                sessao.openWrite(if (v.ehRobo) "VenusRobo.apk" else "Venus.apk", 0, total.coerceAtLeast(-1)).use { saida ->
                    conexao.inputStream.use { entrada ->
                        val buffer = ByteArray(64 * 1024)
                        var lidos = 0L
                        var ultimo = -1
                        while (true) {
                            val n = entrada.read(buffer)
                            if (n < 0) break
                            saida.write(buffer, 0, n)
                            resumo.update(buffer, 0, n)
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
                // confere se o arquivo é exatamente o publicado (nada trocado no caminho)
                val baixado = resumo.digest().joinToString("") { "%02x".format(it) }
                if (!conferir(v.sha256, baixado)) throw ErroDeAtualizacao("O arquivo baixado não confere com o publicado. Cancelei a instalação por segurança.")
                // O Android devolve o resultado para a MainActivity, que abre a tela "Instalar?"
                val volta = Intent(contexto, MainActivity::class.java).setAction(ACAO_INSTALAR)
                    .putExtra(EXTRA_ROBO, v.ehRobo).putExtra(EXTRA_VERSAO, v.numero)
                val pendente = PendingIntent.getActivity(
                    contexto, if (v.ehRobo) 78 else 77, volta, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
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
        val t = if (v.viaSite) null else token()
        // pelo canal do site, pede um link novo agora (o anterior pode ter vencido)
        val url = if (v.viaSite) (doCanal(v.ehRobo)?.urlPublica ?: throw ErroDeAtualizacao("Não consegui o link de download agora.")) else v.urlPublica
        var c = if (t != null) abrir(v.urlApi, t) else abrir(url, null)
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

    /** A versão mais nova pelo canal público (sem token): primeiro o servidor da Vênus, depois o site da loja. */
    private fun doCanal(robo: Boolean): Versao? {
        val app = if (robo) "robo" else "venus"
        val canais = listOfNotNull(if (Servidor.configurado()) "${Servidor.ENDERECO}/atualizacao" else null, CANAL)
        for (canal in canais) {
            try {
                val c = abrir("$canal?app=$app", null)
                val codigo = c.responseCode
                val texto = ler(c)
                if (codigo in 200..299) lerCanal(JSONObject(texto), robo)?.let { return it }
            } catch (e: Exception) {
                // fora do ar: tenta o próximo canal
            }
        }
        return null
    }

    /**
     * GET na API do GitHub que acompanha o redirecionamento (repositório renomeado responde 301), mas só
     * dentro de api.github.com: o token nunca vai para outro endereço.
     */
    private fun apiGet(url: String, token: String?): HttpURLConnection {
        var atual = url
        var c = abrir(atual, token)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        repeat(3) {
            val destino = destinoSeguro(atual, c.responseCode, c.getHeaderField("Location")) ?: return c
            c.disconnect()
            atual = destino
            c = abrir(atual, token)
            c.setRequestProperty("Accept", "application/vnd.github+json")
        }
        return c
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
        const val REPO = "tonysantquees95-code/Venus-core"
        /** Canal público de atualização (para lojistas sem token do GitHub). */
        const val CANAL = "https://desejo-de-intimidade.tonysantquees95.workers.dev/_api/venus/atualizacao"
        const val ACAO_INSTALAR = "com.tonimarques.sextafeira.INSTALAR"
        const val PACOTE_ROBO = "com.tonimarques.venusrobo"
        const val EXTRA_ROBO = "robo"
        const val EXTRA_VERSAO = "versao"
        private const val P_TOKEN = "token"
        private const val P_ULTIMA = "ultima_busca"
        private const val ALIAS = "venus_github"

        /** "1.38", "v1.38" → 38. */
        fun numeroDaVersao(texto: String): Int? = Regex("^v?1\\.(\\d+)").find(texto.trim())?.groupValues?.get(1)?.toIntOrNull()

        /** O GitHub informa "digest": "sha256:…" em cada arquivo publicado. */
        fun sha256De(asset: JSONObject): String =
            asset.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.trim()?.lowercase().orEmpty()

        /** Sem impressão publicada, aceita (o Android ainda confere a assinatura); com ela, tem que ser igual. */
        fun conferir(esperado: String, baixado: String): Boolean = esperado.isBlank() || esperado.equals(baixado, ignoreCase = true)

        /** Para onde seguir num redirecionamento da API: só se for 301/302/307/308 e continuar em api.github.com. */
        fun destinoSeguro(urlAtual: String, codigo: Int, location: String?): String? {
            if (codigo !in intArrayOf(301, 302, 307, 308) || location.isNullOrBlank()) return null
            return try {
                val novo = URL(URL(urlAtual), location)
                if (novo.protocol == "https" && novo.host == "api.github.com") novo.toString() else null
            } catch (e: Exception) {
                null
            }
        }

        /** "robo-1.112" → 112. */
        fun numeroDoRobo(tag: String): Int? = Regex("^robo-1\\.(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

        /** Entre as publicações, a do robô com o número mais alto que tenha o VenusRobo.apk. */
        fun ultimoRobo(lista: JSONArray): Versao? = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .mapNotNull { r ->
                val numero = numeroDoRobo(r.optString("tag_name")) ?: return@mapNotNull null
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals("VenusRobo.apk", ignoreCase = true) } ?: return@mapNotNull null
                Versao(numero, r.optString("tag_name"), apk.optString("url"), apk.optString("browser_download_url"), apk.optLong("size"),
                    notas(r.optString("body")), PACOTE_ROBO, sha256De(apk))
            }
            .maxByOrNull { it.numero }

        /** Lê a publicação do GitHub (tag e o arquivo Venus.apk). */
        fun lerVersao(json: JSONObject): Versao? {
            val tag = json.optString("tag_name")
            val numero = numeroDaVersao(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            // a Vênus é o Venus.apk (o Vênus Robô sai em outra publicação, mas nunca pega o arquivo errado)
            val ordem = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .sortedByDescending { it.optString("name").equals("Venus.apk", ignoreCase = true) }
            for (a in ordem) {
                if (!a.optString("name").endsWith(".apk", ignoreCase = true) || a.optString("name").contains("robo", ignoreCase = true)) continue
                return Versao(
                    numero, tag, a.optString("url"), a.optString("browser_download_url"), a.optLong("size"),
                    notas(json.optString("body")), sha256 = sha256De(a),
                )
            }
            return null
        }

        /** A resposta do canal do site: {numero, tag, notas, tamanho, sha256, url}. */
        fun lerCanal(o: JSONObject, robo: Boolean): Versao? {
            val numero = o.optInt("numero")
            val url = o.optString("url")
            if (numero <= 0 || !url.startsWith("https://")) return null
            return Versao(numero, o.optString("tag"), "", url, o.optLong("tamanho"), o.optString("notas").take(600),
                if (robo) PACOTE_ROBO else "", o.optString("sha256").lowercase(), viaSite = true)
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
