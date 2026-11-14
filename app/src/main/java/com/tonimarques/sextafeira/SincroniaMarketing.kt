package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Publicações iguais no celular e no computador (Vetorial ID):
 *  - "ideiaspost": as ideias de post. As duas pontas leem e escrevem; apagar numa ponta deixa uma marca ("removida")
 *    para a outra ponta apagar também, e o que nasce num aparelho entra no outro;
 *  - "postagens": os posts programados deste celular (o computador só mostra);
 *  - "stories": a fila de dicas e curiosidades dos stories do dia, os horários e o último publicado (o computador só mostra).
 * As fotos não viajam (pesam demais): o computador mostra legenda, tipo e horário.
 */
internal object SincroniaMarketing {
    const val IDEIAS = "ideiaspost"
    const val POSTAGENS = "postagens"
    const val STORIES = "stories"
    /** Resumo do Instagram (seguidores e últimos posts) já pronto, para o computador responder sem chamar a Meta. */
    const val INSTAGRAM = "instagram"
    private const val PREF_RESUMO_IG = "mkt_resumo_ig"
    private const val PREF_RESUMO_IG_T = "mkt_resumo_ig_t"
    private const val VALIDADE_RESUMO_MS = 15L * 60_000L

    private const val MAX_IDEIAS = 40
    private const val MAX_MARCAS = 60
    private const val PREF_CONHECIDO = "mkt_ideias_conhecido"
    private const val PREF_VISTAS = "mkt_ideias_vistas"

    private fun JSONArray?.itens(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun chave(titulo: String) = ComandosLocais.normalizar(titulo)
    private fun dia(c: java.util.Calendar?) = c?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it.time) }.orEmpty()

    private fun vistas(a: MainActivity): Set<String> = try {
        JSONArray(a.prefs.getString(PREF_VISTAS, "[]")).let { j -> (0 until j.length()).map { j.optString(it) }.toSet() }
    } catch (e: Exception) {
        emptySet()
    }

    private fun conhecido(a: MainActivity): List<JSONObject> = try {
        JSONObject(a.prefs.getString(PREF_CONHECIDO, "{}") ?: "{}").optJSONArray("itens").itens()
    } catch (e: Exception) {
        emptyList()
    }

    /** Chegou o documento das ideias de outro aparelho: apaga as removidas lá e traz as novas. */
    fun aplicarIdeias(a: MainActivity, doc: JSONObject?) {
        if (doc == null) return
        val locais = try { a.memoria.itensDoTipo("ideia_post", 200) } catch (e: Exception) { return }
        val porChave = locais.groupBy { chave(IdeiasDePost.ler(it).titulo) }
        val jaVistas = vistas(a)
        for (r in doc.optJSONArray("itens").itens()) {
            val titulo = r.optString("titulo").trim()
            val k = chave(titulo)
            if (k.isBlank()) continue
            if (r.optBoolean("removida")) {
                porChave[k]?.forEach { a.memoria.apagarItem(it.id) }
            } else if (k !in porChave && k !in jaVistas && r.optString("legenda").isNotBlank()) {
                val detalhe = listOf(r.optString("quando"), r.optString("formato").ifBlank { "feed" }).filter { it.isNotBlank() }.joinToString(", ")
                a.memoria.guardarVetor("ideia_post", "$titulo ($detalhe)", r.optString("legenda"))
            }
        }
        a.prefs.edit().putString(PREF_CONHECIDO, doc.toString()).apply()
    }

    private fun ideiasParaSincronia(a: MainActivity): JSONObject {
        val antigas = conhecido(a)
        val jaVistas = vistas(a)
        val locais = try { a.memoria.itensDoTipo("ideia_post", MAX_IDEIAS).map { IdeiasDePost.ler(it) } } catch (e: Exception) { emptyList() }
        val saida = JSONArray()
        val presentes = mutableSetOf<String>()
        for (i in locais) {
            val k = chave(i.titulo)
            if (!presentes.add(k)) continue
            val velho = antigas.firstOrNull { chave(it.optString("titulo")) == k }
            saida.put(
                JSONObject().put("titulo", i.titulo.take(80)).put("formato", i.formato).put("legenda", i.legenda.take(500))
                    .put("quando", dia(i.data)).put("usada", velho?.optBoolean("usada") == true)
                    .put("origem", velho?.optString("origem")?.takeIf { it.isNotBlank() } ?: "celular")
            )
        }
        val agora = System.currentTimeMillis()
        var marcas = 0
        for (v in antigas) {
            val k = chave(v.optString("titulo"))
            if (k in presentes || marcas >= MAX_MARCAS) continue
            val apagadaAqui = !v.optBoolean("removida") && k in jaVistas
            if (v.optBoolean("removida") || apagadaAqui) {
                val ts = if (v.optBoolean("removida")) v.optLong("ts", agora) else agora
                if (agora - ts > 30L * 86_400_000L) continue
                saida.put(JSONObject().put("titulo", v.optString("titulo").take(80)).put("removida", true).put("ts", ts))
                marcas++
            } else if (!v.optBoolean("removida")) {
                saida.put(v) // veio de outro aparelho e ainda não entrou aqui: não perde
            }
        }
        return JSONObject().put("itens", saida)
    }

    private fun postagensParaSincronia(a: MainActivity): JSONObject {
        val arr = JSONArray()
        for (p in Postagens.pendentes(a).take(40)) {
            arr.put(JSONObject().put("id", p.id).put("quando", p.quando).put("tipo", p.tipo).put("legenda", p.legenda.take(500)).put("link", p.link))
        }
        return JSONObject().put("itens", arr)
    }

    /** Resumo do Instagram guardado por 15 minutos; só chama a Meta quando venceu (e só se o Instagram estiver conectado). */
    private fun resumoDoInstagram(a: MainActivity): JSONObject? {
        if (!Instagram.conectado(a)) return null
        val agora = System.currentTimeMillis()
        var texto = a.prefs.getString(PREF_RESUMO_IG, "").orEmpty()
        if (texto.isBlank() || agora - a.prefs.getLong(PREF_RESUMO_IG_T, 0) > VALIDADE_RESUMO_MS) {
            texto = try { Instagram.resumo(a) } catch (e: Exception) { texto }
            if (texto.isNotBlank()) a.prefs.edit().putString(PREF_RESUMO_IG, texto).putLong(PREF_RESUMO_IG_T, agora).apply()
        }
        if (texto.isBlank()) return null
        // "t" só muda quando o texto muda, para não gerar envio à toa
        val t = a.prefs.getLong(PREF_RESUMO_IG_T, agora)
        return JSONObject().put("resumo", texto.take(1500)).put("t", t)
    }

    /** Envia o que mudou aqui: ideias, posts programados e a fila de stories. */
    fun enviarTudo(a: MainActivity, ver: JSONObject, env: JSONObject) {
        try {
            val ideias = ideiasParaSincronia(a)
            SincroniaId.enviar(a, IDEIAS, ideias, ver, env)
            if (env.optString(IDEIAS) == ideias.toString()) {
                val itens = ideias.optJSONArray("itens").itens()
                a.prefs.edit().putString(PREF_CONHECIDO, ideias.toString())
                    .putString(PREF_VISTAS, JSONArray(itens.filter { !it.optBoolean("removida") }.map { chave(it.optString("titulo")) }).toString()).apply()
            }
        } catch (e: Exception) {
            // sem memória ou sem conexão: tenta de novo no próximo ciclo
        }
        try {
            resumoDoInstagram(a)?.let { SincroniaId.enviar(a, INSTAGRAM, it, ver, env) }
        } catch (e: Exception) {
        }
        try {
            SincroniaId.enviar(a, POSTAGENS, postagensParaSincronia(a), ver, env)
            SincroniaId.enviar(a, STORIES, StoriesDoDia.paraSincronia(a), ver, env)
        } catch (e: Exception) {
        }
    }
}
