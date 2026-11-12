package com.tonimarques.sextafeira

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Identidade visual da loja lida do próprio site (logo, cor principal e fonte), para panfletos, cartões e QR Codes.
 * É independente da personalização do app Vênus (tema, orbe, ícones).
 */
object IdentidadeDoSite {
    class Identidade(val cor: Int?, val logo: String?, val fonte: String?) {
        /** A fonte do site mais parecida entre as que o Android já tem. */
        val estilo: String get() = estiloDaFonte(fonte)
    }

    private val GENERICAS = setOf("serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "inherit", "initial", "unset", "ui-sans-serif", "ui-serif", "-apple-system", "blinkmacsystemfont", "segoe ui", "roboto", "arial", "helvetica", "helvetica neue", "apple color emoji")
    private val COM_SERIFA = listOf("serif", "georgia", "times", "garamond", "playfair", "merriweather", "lora", "cormorant", "baskerville", "bodoni", "libre caslon", "crimson")

    fun estiloDaFonte(fonte: String?): String {
        val f = fonte?.lowercase().orEmpty()
        if (f.isBlank()) return "moderna"
        if (f.contains("sans")) return "moderna"
        return if (COM_SERIFA.any { f.contains(it) }) "serifa" else "moderna"
    }

    private fun atributo(tag: String, nome: String): String? =
        Regex("""\b$nome\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private fun tags(html: String, nome: String): List<String> = Regex("""<$nome\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()

    private fun https(base: String, relativo: String?): String? {
        if (relativo.isNullOrBlank() || relativo.startsWith("data:")) return null
        return try {
            val u = URI(base).resolve(relativo.trim()).toString()
            if (u.startsWith("https://")) u else null
        } catch (e: Exception) { null }
    }

    /** Folhas de estilo do mesmo site (para ler variáveis de cor e fontes). */
    fun folhas(html: String, base: String): List<String> {
        val origem = try { URI(base).host } catch (e: Exception) { null } ?: return emptyList()
        return tags(html, "link").filter { atributo(it, "rel")?.contains("stylesheet", true) == true }
            .mapNotNull { https(base, atributo(it, "href")) }.filter { try { URI(it).host == origem } catch (e: Exception) { false } }.distinct().take(2)
    }

    fun extrair(html: String, base: String, css: String = ""): Identidade {
        val tudo = html + "\n" + css
        return Identidade(corPrincipal(html, tudo), logoDe(html, base), fonteDe(html, tudo))
    }

    private fun corPrincipal(html: String, tudo: String): Int? {
        tags(html, "meta").firstOrNull { atributo(it, "name")?.equals("theme-color", true) == true }
            ?.let { atributo(it, "content") }?.let { Tema.lerHex(it) }?.let { if (vivida(it, 0.12f)) return it }
        Regex("""--[\w-]*(?:primary|accent|brand|main|cor|principal)[\w-]*\s*:\s*(#[0-9a-fA-F]{3,6})\b""", RegexOption.IGNORE_CASE).findAll(tudo)
            .mapNotNull { Tema.lerHex(it.groupValues[1]) }.firstOrNull { vivida(it, 0.12f) }?.let { return it }
        // senão, a cor viva que mais aparece
        val contagem = HashMap<Int, Int>()
        Regex("""#([0-9a-fA-F]{6})\b""").findAll(tudo).mapNotNull { Tema.lerHex(it.groupValues[1]) }.filter { vivida(it, 0.3f) }.forEach { contagem[it] = (contagem[it] ?: 0) + 1 }
        return contagem.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    }

    /** Cor com tom de verdade (nem cinza, nem quase preta ou branca). */
    private fun vivida(rgb: Int, saturacaoMinima: Float): Boolean {
        val h = Tema.hsv(rgb)
        return h[1] >= saturacaoMinima && h[2] in 0.25f..0.98f
    }

    private fun logoDe(html: String, base: String): String? {
        tags(html, "img").firstOrNull { t -> listOf("class", "id", "alt", "src").any { atributo(t, it)?.contains("logo", true) == true } }
            ?.let { https(base, atributo(it, "src")) }?.let { return it }
        tags(html, "link").firstOrNull { atributo(it, "rel")?.contains("apple-touch-icon", true) == true }?.let { https(base, atributo(it, "href")) }?.let { return it }
        tags(html, "meta").firstOrNull { atributo(it, "property")?.equals("og:image", true) == true }?.let { https(base, atributo(it, "content")) }?.let { return it }
        return tags(html, "link").firstOrNull { atributo(it, "rel")?.contains("icon", true) == true }?.let { https(base, atributo(it, "href")) }
    }

    private fun fonteDe(html: String, tudo: String): String? {
        Regex("""fonts\.googleapis\.com/css2?\?[^"'>\s]*family=([^:&"'>\s;]+)""", RegexOption.IGNORE_CASE).find(tudo)?.let {
            return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8").replace('+', ' ').trim().ifBlank { null }
        }
        return Regex("""font-family\s*:\s*([^;}{]+)""", RegexOption.IGNORE_CASE).findAll(tudo)
            .flatMap { it.groupValues[1].split(",").asSequence() }.map { it.trim().trim('"', '\'') }
            .firstOrNull { it.isNotBlank() && it.lowercase() !in GENERICAS && !it.startsWith("var(") }
    }

    // ------------------------------------------------------------------ rede
    private fun ler(url: String, limite: Int): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Venus/1.0 (identidade da loja)")
        if (c.responseCode !in 200..299) null else c.inputStream.use { it.readNBytesAte(limite) }
    } catch (e: Exception) { null }

    private fun java.io.InputStream.readNBytesAte(limite: Int): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (saida.size() < limite) { val n = read(b); if (n < 0) break; saida.write(b, 0, n) }
        return saida.toByteArray()
    }

    /** Baixa a página (só https) e as folhas de estilo do mesmo site; devolve a identidade e os bytes do logo, se achar. */
    fun buscar(site: String): Pair<Identidade, ByteArray?>? {
        val url = site.trim().let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else if (it.startsWith("https://")) it else "https://$it" }
        val html = ler(url, 600_000)?.toString(Charsets.UTF_8) ?: return null
        val css = folhas(html, url).mapNotNull { ler(it, 300_000)?.toString(Charsets.UTF_8) }.joinToString("\n")
        val id = extrair(html, url, css)
        val logo = id.logo?.let { ler(it, 2_000_000) }
        return id to logo
    }
}
