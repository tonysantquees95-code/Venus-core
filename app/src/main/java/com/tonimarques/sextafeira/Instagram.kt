package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Ligação da Vênus com o Instagram da loja, pela API oficial da Meta ("Instagram API com login do Instagram").
 * Não precisa de página do Facebook: só a conta profissional do Instagram e um token gerado no painel da Meta.
 *
 * O que dá para fazer: publicar foto no feed e story (a foto sobe antes para o site da loja, porque o Instagram
 * busca a imagem por um endereço público), ver seguidores e como foram os últimos posts, ler os comentários que
 * ainda não foram respondidos e responder.
 * Aceita os dois tipos de token da Meta:
 *  - "IG…" (login do Instagram): usa graph.instagram.com, vale 60 dias e a Vênus renova sozinha toda semana;
 *  - "EAA…" (login unificado, o padrão dos apps novos): usa graph.facebook.com, é de usuário do sistema e não vence.
 * As funções com rede rodam fora da thread principal.
 */
object Instagram {

    private const val API = "https://graph.instagram.com"
    private const val API_FACEBOOK = "https://graph.facebook.com"
    /** Conta do Instagram da loja (o número aparece embaixo do nome no painel da Meta), usada se a busca automática falhar. */
    const val CONTA_DA_LOJA = "17841459911574652"

    /** Token do login unificado (usuário do sistema): fala com graph.facebook.com e usa o id da conta no lugar de /me. */
    fun ehFacebook(token: String) = token.startsWith("EAA")
    private fun base(token: String) = if (ehFacebook(token)) API_FACEBOOK else API
    private fun perfil(token: String, id: String) = if (ehFacebook(token)) "/$id" else "/me"
    private const val PREFS = "instagram"

    class ErroDoInstagram(mensagem: String) : Exception(mensagem)

    data class Conta(val id: String, val usuario: String, val tipo: String)
    data class Post(
        val id: String, val legenda: String, val tipo: String, val quando: Long, val curtidas: Int, val comentarios: Int,
        val link: String,
    )
    data class Comentario(val id: String, val postId: String, val usuario: String, val texto: String, val quando: Long, val legendaDoPost: String)

    // ------------------------------------------------------------- conta
    /** Só conta como conectado se o token abre (um token que o cofre não consegue abrir precisa ser colado de novo). */
    fun conectado(ctx: Context) = Cofre.ler(prefs(ctx), "token").isNotBlank()
    fun usuario(ctx: Context): String? = prefs(ctx).getString("usuario", null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Testa o token e, se servir, guarda. Devolve a conta ligada. */
    fun conectar(ctx: Context, tokenColado: String): Conta {
        val token = limparToken(tokenColado)
        if (token.length < 40) throw ErroDoInstagram("Isso não parece um token. Ele é um texto comprido que começa com IG ou EAA.")
        val conta = if (ehFacebook(token)) contaPeloFacebook(token) else conta(pedir("GET", "/me", mapOf("fields" to "user_id,username,account_type"), token))
        prefs(ctx).edit()
            .putString("token", Cofre.selar(token)).putString("id", conta.id).putString("usuario", conta.usuario)
            .putLong("token_desde", System.currentTimeMillis())
            .remove("site_token")
            .apply()
        sincronizarComSite(ctx)
        return conta
    }

    /**
     * O Direct automático roda no site, que precisa do token atual (o "IG…" é renovado aqui toda semana).
     * Manda só quando mudou; se o site ou a loja não estiverem prontos, tenta de novo da próxima vez.
     * Bloqueante: chame fora da thread principal.
     */
    fun sincronizarComSite(ctx: Context): Boolean {
        val p = prefs(ctx)
        val token = Cofre.ler(p, "token").ifBlank { null } ?: return false
        val id = p.getString("id", null) ?: return false
        val marca = token.hashCode().toString() + ":" + id
        if (p.getString("site_token", null) == marca) return true
        return try {
            val loja = Loja(ctx)
            if (!loja.conectada) return false
            loja.enviarTokenDoInstagram(token, id, p.getString("usuario", null).orEmpty())
            p.edit().putString("site_token", marca).apply()
            Diario.anotar(ctx, "instagram", "Token do Instagram entregue ao site (Direct automático)", "automatico")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Com token do login unificado: acha a conta do Instagram ligada (pelas páginas ou pela conta da loja). */
    private fun contaPeloFacebook(token: String): Conta {
        try {
            val r = pedir("GET", "/me/accounts", mapOf("fields" to "instagram_business_account{id,username}"), token)
            val a = r.optJSONArray("data") ?: JSONArray()
            for (i in 0 until a.length()) {
                val ig = a.optJSONObject(i)?.optJSONObject("instagram_business_account") ?: continue
                if (ig.optString("id").isNotBlank()) return Conta(ig.optString("id"), ig.optString("username"), "BUSINESS")
            }
        } catch (e: ErroDoInstagram) {
            // usuário do sistema sem páginas: segue para a conta da loja
        }
        val r = pedir("GET", "/$CONTA_DA_LOJA", mapOf("fields" to "id,username"), token)
        return Conta(r.optString("id").ifBlank { CONTA_DA_LOJA }, r.optString("username"), "BUSINESS")
    }

    fun desconectar(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** O token copiado às vezes vem com espaços, aspas ou "access_token=" na frente. */
    fun limparToken(t: String): String = t.trim().trim('"', '\'').substringAfter("access_token=").substringBefore('&').replace(Regex("\\s+"), "")

    fun conta(json: JSONObject): Conta {
        val id = json.optString("user_id").ifBlank { json.optString("id") }
        if (id.isBlank()) throw ErroDoInstagram("O Instagram não devolveu a conta.")
        return Conta(id, json.optString("username"), json.optString("account_type"))
    }

    /** Token e id da conta, renovando o token quando passa de 7 dias (vale 60). */
    private fun credenciais(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        var token = Cofre.ler(p, "token").ifBlank { null } ?: throw ErroDoInstagram(SEM_CONTA)
        val id = p.getString("id", null) ?: throw ErroDoInstagram(SEM_CONTA)
        val desde = p.getLong("token_desde", 0)
        if (!ehFacebook(token) && System.currentTimeMillis() - desde > 7 * 24 * 3600_000L) {
            try {
                val r = pedir("GET", "/refresh_access_token", mapOf("grant_type" to "ig_refresh_token"), token)
                r.optString("access_token").takeIf { it.isNotBlank() }?.let {
                    token = it
                    p.edit().putString("token", Cofre.selar(it)).putLong("token_desde", System.currentTimeMillis()).apply()
                    sincronizarComSite(ctx)
                }
            } catch (e: ErroDoInstagram) {
                // se não renovou, segue com o atual (ainda vale até 60 dias)
            }
        }
        return token to id
    }

    // ------------------------------------------------------------- publicar
    /**
     * Publica uma foto (endereço público JPEG) no feed ou nos stories. Devolve o link do post (ou "" para story).
     * Passos da Meta: cria o "contêiner", espera ficar pronto e publica.
     */
    fun publicar(ctx: Context, urlDaFoto: String, legenda: String, story: Boolean): String {
        if (!Licenca.liberada(ctx)) throw ErroDoInstagram(Licenca.descricao(ctx))
        val (token, id) = credenciais(ctx)
        val campos = mutableMapOf("image_url" to urlDaFoto)
        if (story) campos["media_type"] = "STORIES" else if (legenda.isNotBlank()) campos["caption"] = legenda.take(2200)
        val conteiner = pedir("POST", "/$id/media", campos, token).optString("id")
        if (conteiner.isBlank()) throw ErroDoInstagram("O Instagram não aceitou a foto.")
        // espera o Instagram baixar e processar a foto (costuma levar poucos segundos)
        for (tentativa in 1..15) {
            val estado = pedir("GET", "/$conteiner", mapOf("fields" to "status_code"), token).optString("status_code")
            if (estado == "FINISHED") break
            if (estado == "ERROR" || estado == "EXPIRED") throw ErroDoInstagram("O Instagram não conseguiu processar a foto. Tente outra imagem (JPEG, até 8 MB).")
            Thread.sleep(2000)
        }
        val publicado = pedir("POST", "/$id/media_publish", mapOf("creation_id" to conteiner), token).optString("id")
        if (publicado.isBlank()) throw ErroDoInstagram("O Instagram não confirmou a publicação.")
        return try {
            pedir("GET", "/$publicado", mapOf("fields" to "permalink"), token).optString("permalink")
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------- desempenho
    fun resumo(ctx: Context): String {
        val (token, id) = credenciais(ctx)
        val perfil = pedir("GET", perfil(token, id), mapOf("fields" to "username,followers_count,follows_count,media_count"), token)
        val posts = postsRecentes(token, id, 12)
        val anterior = prefs(ctx).getInt("seguidores", -1)
        val agora = perfil.optInt("followers_count", -1)
        if (agora >= 0) prefs(ctx).edit().putInt("seguidores", agora).apply()
        return montarResumo(perfil, posts, anterior, System.currentTimeMillis())
    }

    private fun postsRecentes(token: String, id: String, quantos: Int): List<Post> {
        val r = pedir("GET", perfil(token, id) + "/media", mapOf("fields" to "id,caption,media_type,timestamp,like_count,comments_count,permalink", "limit" to quantos.toString()), token)
        return lerPosts(r.optJSONArray("data") ?: JSONArray())
    }

    fun lerPosts(a: JSONArray): List<Post> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
        Post(
            it.optString("id"), it.optString("caption").takeIf { c -> c != "null" }.orEmpty(), it.optString("media_type"),
            data(it.optString("timestamp")), it.optInt("like_count"), it.optInt("comments_count"), it.optString("permalink"),
        )
    }

    fun montarResumo(perfil: JSONObject, posts: List<Post>, seguidoresAntes: Int, agora: Long): String {
        val partes = mutableListOf<String>()
        val usuario = perfil.optString("username")
        val seguidores = perfil.optInt("followers_count", -1)
        if (seguidores >= 0) {
            var t = "O @$usuario tem ${milhar(seguidores)} seguidores"
            if (seguidoresAntes >= 0 && seguidoresAntes != seguidores) {
                val dif = seguidores - seguidoresAntes
                t += if (dif > 0) " ($dif a mais que da última vez que você perguntou)" else " (${-dif} a menos que da última vez que você perguntou)"
            }
            partes += "$t e ${perfil.optInt("media_count")} publicações."
        }
        val semana = posts.filter { agora - it.quando < 7 * 86_400_000L }
        if (posts.isEmpty()) {
            partes += "Ainda não tem posts."
        } else {
            if (semana.isEmpty()) {
                val dias = ((agora - posts.first().quando) / 86_400_000L).toInt()
                partes += "Não teve post nos últimos 7 dias (o último foi há $dias ${if (dias == 1) "dia" else "dias"}). Postar com frequência ajuda a aparecer mais."
            } else {
                partes += "Nos últimos 7 dias foram ${semana.size} ${if (semana.size == 1) "post" else "posts"}, com ${semana.sumOf { it.curtidas }} curtidas e ${semana.sumOf { it.comentarios }} comentários."
            }
            val media = posts.map { it.curtidas + it.comentarios * 3 }.average()
            val melhor = posts.maxByOrNull { it.curtidas + it.comentarios * 3 }!!
            partes += "O que mais engajou entre os últimos ${posts.size}: “${resumirLegenda(melhor.legenda)}”, com ${melhor.curtidas} curtidas e ${melhor.comentarios} comentários" +
                (if (media > 0 && posts.size >= 3) " (${((melhor.curtidas + melhor.comentarios * 3) / media * 100 - 100).toInt().coerceAtLeast(0)}% acima da média)" else "") + "."
            val pendentes = posts.sumOf { it.comentarios }
            if (pendentes > 0) partes += "Para ver e responder os comentários, diga: comentários do Instagram."
        }
        return partes.joinToString(" ")
    }

    // ------------------------------------------------------------- comentários
    /** Comentários dos últimos posts que a loja ainda não respondeu (mais novos primeiro). */
    fun comentariosSemResposta(ctx: Context, posts: Int = 10): List<Comentario> {
        val (token, id) = credenciais(ctx)
        val eu = prefs(ctx).getString("usuario", "").orEmpty()
        val lista = mutableListOf<Comentario>()
        for (p in postsRecentes(token, id, posts).filter { it.comentarios > 0 }) {
            val r = pedir("GET", "/${p.id}/comments", mapOf("fields" to "id,text,username,timestamp,replies{username}", "limit" to "50"), token)
            lista += semResposta(r.optJSONArray("data") ?: JSONArray(), p, eu)
        }
        return lista.sortedByDescending { it.quando }
    }

    fun semResposta(a: JSONArray, post: Post, eu: String): List<Comentario> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .filter { c ->
            val autor = c.optString("username")
            val respostas = c.optJSONObject("replies")?.optJSONArray("data") ?: JSONArray()
            !autor.equals(eu, true) && (0 until respostas.length()).none { respostas.optJSONObject(it)?.optString("username").equals(eu, true) }
        }
        .map { Comentario(it.optString("id"), post.id, it.optString("username"), it.optString("text"), data(it.optString("timestamp")), post.legenda) }

    fun responderComentario(ctx: Context, comentarioId: String, texto: String) {
        val (token, _) = credenciais(ctx)
        val r = pedir("POST", "/$comentarioId/replies", mapOf("message" to texto.take(2200)), token)
        if (r.optString("id").isBlank()) throw ErroDoInstagram("O Instagram não confirmou a resposta.")
    }

    // ------------------------------------------------------------- rede
    private fun pedir(metodo: String, caminho: String, campos: Map<String, String>, token: String): JSONObject {
        val pares = (campos + ("access_token" to token)).entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val api = base(token)
        val url = if (metodo == "GET") "$api$caminho?$pares" else "$api$caminho"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.setRequestProperty("User-Agent", "Venus/1.0 (Android)")
            if (metodo != "GET") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(pares.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val corpo = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(corpo)
            } catch (e: Exception) {
                JSONObject()
            }
            if (codigo !in 200..299 || json.has("error")) throw ErroDoInstagram(erroAmigavel(codigo, json.optJSONObject("error")))
            return json
        } catch (e: ErroDoInstagram) {
            throw e
        } catch (e: Exception) {
            throw ErroDoInstagram("Sem conexão com o Instagram agora. Confira a internet.")
        } finally {
            c.disconnect()
        }
    }

    /** Traduz os erros da Meta para o que fazer. */
    fun erroAmigavel(codigo: Int, erro: JSONObject?): String {
        val cod = erro?.optInt("code") ?: 0
        val sub = erro?.optInt("error_subcode") ?: 0
        val msg = erro?.optString("message").orEmpty()
        return when {
            cod == 190 || sub == 463 || sub == 460 || msg.contains("access token", true) && codigo == 400 ->
                "O token do Instagram venceu ou foi trocado. Gere outro no painel da Meta e diga: conectar o Instagram."
            cod == 10 || cod == 200 || cod == 3 ->
                "O token não tem permissão para isso. No painel da Meta, gere o token marcando as permissões de publicar e de comentários."
            cod == 4 || cod == 17 || cod == 32 || cod == 613 || sub == 2207042 ->
                "O Instagram pediu para esperar: muitas ações seguidas. Tente de novo daqui a pouco."
            sub == 2207026 || sub == 2207052 || msg.contains("image", true) && msg.contains("format", true) ->
                "O Instagram não aceitou o formato da foto. Use JPEG com proporção entre quadrada e vertical 4:5."
            sub == 2207009 -> "A proporção da foto não serve para o feed. Use quadrada ou vertical 4:5."
            msg.isNotBlank() -> "O Instagram recusou: ${msg.take(160)}"
            else -> "O Instagram respondeu com erro $codigo."
        }
    }

    // ------------------------------------------------------------- utilidades
    private val FORMATO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun data(s: String): Long = try {
        synchronized(FORMATO) { FORMATO.parse(s)?.time ?: 0L }
    } catch (e: Exception) {
        0L
    }

    private fun milhar(n: Int) = String.format(Locale("pt", "BR"), "%,d", n)

    fun resumirLegenda(l: String): String {
        val limpa = l.replace(Regex("#\\S+"), "").replace(Regex("\\s+"), " ").trim()
        if (limpa.isBlank()) return "post sem legenda"
        return if (limpa.length <= 60) limpa else limpa.take(57).substringBeforeLast(' ') + "…"
    }

    const val SEM_CONTA = "O Instagram ainda não está conectado. Diga: conectar o Instagram."
    const val COMO_CONECTAR = "https://developers.facebook.com/apps/"
}
