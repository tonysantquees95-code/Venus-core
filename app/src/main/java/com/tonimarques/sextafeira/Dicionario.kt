package com.tonimarques.sextafeira

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Dicionário de português pelo Wikcionário (gratuito, sem chave):
 * significado, sinônimos, antônimos, plural e qual é a escrita certa ("exceção ou excessão").
 * As funções com rede rodam fora da thread principal; as de "ler" são testáveis.
 */
object Dicionario {

    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8")
    private const val API = "https://pt.wiktionary.org/w/api.php?format=json&formatversion=2&"

    private fun wikitexto(palavra: String): String? {
        val j = JSONObject(Web.texto(API + "action=parse&prop=wikitext&redirects=1&page=" + codificar(palavra)))
        return j.optJSONObject("parse")?.optString("wikitext")?.takeIf { it.isNotBlank() }
    }

    /** Tenta a palavra como veio e em minúsculas. */
    private fun pagina(palavra: String): Pair<String, String>? {
        for (p in listOf(palavra.trim().lowercase(), palavra.trim()).distinct()) {
            val w = try {
                wikitexto(p)
            } catch (e: Exception) {
                null
            } ?: continue
            return p to w
        }
        return null
    }

    fun consultar(tipo: String, palavra: String): String {
        val (p, w) = pagina(palavra) ?: return "Não encontrei a palavra \"$palavra\" no dicionário."
        val pt = secaoPortugues(w)
        return when (tipo) {
            "sinonimos" -> lista(pt, "Sinônimos").takeIf { it.isNotEmpty() }
                ?.let { "Sinônimos de \"$p\": ${it.take(8).joinToString(", ")}." }
                ?: "Não encontrei sinônimos de \"$p\" no dicionário."
            "antonimos" -> lista(pt, "Antônimos").takeIf { it.isNotEmpty() }
                ?.let { "Antônimos de \"$p\": ${it.take(8).joinToString(", ")}." }
                ?: "Não encontrei antônimos de \"$p\" no dicionário."
            "plural" -> plural(pt)?.let { "O plural de \"$p\" é \"$it\"." } ?: "Não encontrei o plural de \"$p\" no dicionário."
            else -> {
                val defs = definicoes(pt)
                if (defs.isEmpty()) "Não encontrei o significado de \"$p\"."
                else {
                    val classe = classe(pt)?.let { " ($it)" }.orEmpty()
                    "\"${p.replaceFirstChar { it.uppercase() }}\"$classe: " +
                        defs.take(3).mapIndexed { i, d -> if (defs.size > 1) "${i + 1}. $d" else d }.joinToString(" ")
                }
            }
        }
    }

    /** "exceção ou excessão": vê qual das duas existe no dicionário. */
    fun qualCerta(a: String, b: String): String {
        val j = JSONObject(Web.texto(API + "action=query&titles=" + codificar("${a.lowercase()}|${b.lowercase()}")))
        val paginas = j.optJSONObject("query")?.optJSONArray("pages") ?: return "Não consegui conferir agora."
        val existem = (0 until paginas.length()).mapNotNull { paginas.optJSONObject(it) }
            .filter { !it.optBoolean("missing") }.map { it.optString("title") }
        return when {
            existem.size == 1 -> "O certo é \"${existem[0]}\"."
            existem.size == 2 -> "As duas formas existem: \"$a\" e \"$b\". Elas têm significados diferentes; quer que eu diga o de alguma?"
            else -> "Não encontrei nenhuma das duas no dicionário. Confira a escrita."
        }
    }

    // ------------------------------------------------------------- leitura do wikitexto
    /** Só a parte em português da página (a página pode ter várias línguas). */
    fun secaoPortugues(w: String): String {
        val ini = Regex("^=\\s*\\{\\{-pt-}}\\s*=", RegexOption.MULTILINE).find(w)?.range?.first
            ?: Regex("^=\\s*Português\\s*=", RegexOption.MULTILINE).find(w)?.range?.first
            ?: return w
        val resto = w.substring(ini + 1)
        val fim = Regex("^=\\s*(?:\\{\\{-[a-z]{2,3}-}}|[A-ZÁÉÍÓÚ][a-záéíóúçãõâêô]+)\\s*=\\s*$", RegexOption.MULTILINE).find(resto)?.range?.first
        return if (fim != null) resto.substring(0, fim) else resto
    }

    fun definicoes(pt: String): List<String> = pt.lines()
        .filter { it.startsWith("#") && !it.startsWith("#:") && !it.startsWith("#*") && !it.startsWith("##") }
        .map { limpar(it.removePrefix("#")) }
        .filter { it.length >= 3 }

    fun classe(pt: String): String? {
        val m = Regex("==\\s*(?:\\{\\{)?(Substantivo|Adjetivo|Verbo|Advérbio|Pronome|Preposição|Conjunção|Interjeição|Numeral|Artigo)").find(pt)
        return m?.groupValues?.get(1)?.lowercase()
    }

    fun lista(pt: String, secao: String): List<String> {
        val m = Regex("=+\\s*(?:\\{\\{)?$secao[^=\\n]*=+\\s*\\n((?:[*:#].*\\n?)+)").find(pt) ?: return emptyList()
        return Regex("\\[\\[([^\\]|#]+)(?:\\|[^\\]]*)?]]").findAll(m.groupValues[1]).map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.contains(":") }.distinct().toList()
    }

    fun plural(pt: String): String? {
        val flex = Regex("\\{\\{flex\\.pt([^}]*)}}").find(pt)?.groupValues?.get(1) ?: return null
        val params = flex.split("|").map { it.trim() }.filter { it.isNotBlank() }
        for (chave in listOf("mp", "fp", "p")) {
            params.firstOrNull { it.startsWith("$chave=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val posicionais = params.filter { !it.contains("=") }
        return posicionais.getOrNull(1)
    }

    /** Tira marcações do Wikcionário: {{...}}, [[a|b]], '''negrito''', <ref>. */
    fun limpar(s: String): String {
        var t = s
        t = Regex("<ref[^>]*>.*?</ref>|<ref[^>]*/>|<!--.*?-->").replace(t, "")
        t = Regex("\\{\\{(?:l|link|lig|ll)\\|[a-z-]+\\|([^}|]+)[^}]*}}").replace(t) { it.groupValues[1] }
        repeat(3) { t = Regex("\\{\\{[^{}]*}}").replace(t, "") }
        t = Regex("\\[\\[[^\\]|]*\\|([^\\]]*)]]").replace(t) { it.groupValues[1] }
        t = Regex("\\[\\[([^\\]]*)]]").replace(t) { it.groupValues[1] }
        t = t.replace("'''", "").replace("''", "").replace(Regex("<[^>]+>"), "")
        t = t.replace(Regex("\\s+"), " ").trim().trimStart(':', ';', ' ', ',').trim()
        if (t.isNotEmpty() && t.last() !in ".!?") t += "."
        return t.replaceFirstChar { it.uppercase() }
    }
}
