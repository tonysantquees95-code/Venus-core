package com.tonimarques.sextafeira

import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pesquisas que não dependem do Gemini:
 *  - notícias e novidades (Google Notícias, por RSS, sem chave);
 *  - cotações de moedas (AwesomeAPI, sem chave);
 *  - Wikipédia (busca completa) e respostas rápidas do DuckDuckGo.
 * As funções que acessam a internet devem rodar fora da thread principal.
 */
object Pesquisa {

    data class Noticia(val titulo: String, val fonte: String, val link: String, val data: Long)

    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------- notícias
    fun noticias(consulta: String, maximo: Int = 8): List<Noticia> {
        val url = "https://news.google.com/rss/search?hl=pt-BR&gl=BR&ceid=BR:pt-419&q=" + codificar(consulta)
        val xml = Web.texto(url, aceitar = "application/rss+xml, application/xml, text/xml")
        return lerRss(xml).take(maximo)
    }

    /** Lê o RSS do Google Notícias (separado da rede para poder ser testado). */
    fun lerRss(xml: String): List<Noticia> {
        val formato = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val itens = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        val lista = mutableListOf<Noticia>()
        for (item in itens) {
            val bloco = item.groupValues[1]
            val fonte = decodificar(campo(bloco, "source"))
            var titulo = decodificar(campo(bloco, "title"))
            if (fonte.isNotBlank() && titulo.endsWith(" - $fonte")) titulo = titulo.removeSuffix(" - $fonte")
            val link = decodificar(campo(bloco, "link"))
            val data = try {
                formato.parse(campo(bloco, "pubDate"))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            if (titulo.isNotBlank() && link.isNotBlank()) lista += Noticia(titulo.trim(), fonte.trim(), link.trim(), data)
        }
        return lista
    }

    private fun campo(bloco: String, nome: String): String {
        val m = Regex("<$nome(?:\\s[^>]*)?>(.*?)</$nome>", RegexOption.DOT_MATCHES_ALL).find(bloco) ?: return ""
        return m.groupValues[1].removePrefix("<![CDATA[").removeSuffix("]]>")
    }

    private fun decodificar(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    /** "há 2 dias", "hoje", "ontem" */
    fun quando(data: Long, agora: Long = System.currentTimeMillis()): String {
        if (data <= 0) return ""
        val dias = TimeUnit.MILLISECONDS.toDays(agora - data)
        return when {
            dias <= 0 -> "hoje"
            dias == 1L -> "ontem"
            else -> "há $dias dias"
        }
    }

    fun formatarNoticias(lista: List<Noticia>, introducao: String): Web.Resposta {
        val partes = lista.take(3).mapIndexed { i, n ->
            val detalhe = listOf(n.fonte, quando(n.data)).filter { it.isNotBlank() }.joinToString(", ")
            "${i + 1}. ${n.titulo}" + if (detalhe.isNotBlank()) " ($detalhe)." else "."
        }
        return Web.Resposta(
            "$introducao ${partes.joinToString(" ")}",
            lista.take(3).map { Web.Fonte(it.fonte.ifBlank { "notícia" }, it.link) }
        )
    }

    // ------------------------------------------------------------- cotações
    private val MOEDAS = mapOf(
        "dolar" to "USD", "euro" to "EUR", "bitcoin" to "BTC", "libra" to "GBP", "iene" to "JPY",
        "peso argentino" to "ARS", "dolar canadense" to "CAD", "dolar australiano" to "AUD", "ethereum" to "ETH",
    )

    fun codigoDaMoeda(nome: String): String? {
        val n = ComandosLocais.normalizar(nome).trim()
        return MOEDAS[n] ?: MOEDAS.entries.firstOrNull { n.contains(it.key) }?.value
    }

    fun cotacao(moeda: String): String {
        val codigo = codigoDaMoeda(moeda) ?: return "Não conheço a moeda $moeda."
        val json = JSONObject(Web.texto("https://economia.awesomeapi.com.br/json/last/$codigo-BRL"))
        return montarCotacao(json, codigo)
    }

    /** Separado da rede para poder ser testado. */
    fun montarCotacao(json: JSONObject, codigo: String): String {
        val dados = json.getJSONObject("${codigo}BRL")
        val valor = dados.getString("bid").toDouble()
        val variacao = dados.optString("pctChange").toDoubleOrNull() ?: 0.0
        val dinheiro = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(valor).replace(' ', ' ')
        val nome = dados.optString("name").substringBefore("/").ifBlank { codigo }
        val tendencia = when {
            variacao > 0.005 -> ", alta de ${"%.2f".format(Locale("pt", "BR"), variacao)}% hoje"
            variacao < -0.005 -> ", queda de ${"%.2f".format(Locale("pt", "BR"), -variacao)}% hoje"
            else -> ", estável hoje"
        }
        return "O $nome está em $dinheiro$tendencia."
    }

    // ------------------------------------------------------------- wikipédia e afins
    fun wikipediaBusca(pergunta: String): Web.Resposta? {
        val json = JSONObject(
            Web.texto(
                "https://pt.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=" +
                    codificar(pergunta)
            )
        )
        val titulo = json.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title").orEmpty()
        if (titulo.isBlank()) return null
        return Web.resumoWikipedia(titulo)
    }

    fun duckduckgo(pergunta: String): Web.Resposta? {
        val json = JSONObject(
            Web.texto("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&kl=br-pt&q=" + codificar(pergunta))
        )
        val resposta = json.optString("Answer").ifBlank { json.optString("AbstractText") }
        if (resposta.isBlank()) return null
        val link = json.optString("AbstractURL").ifBlank { "https://duckduckgo.com/?q=" + codificar(pergunta) }
        return Web.Resposta(Web.primeirasFrases(resposta, 3), listOf(Web.Fonte(json.optString("AbstractSource").ifBlank { "DuckDuckGo" }, link)))
    }

    // ------------------------------------------------------------- roteador
    private val NOVIDADE = Regex(
        "\\b(noticia|noticias|novidade|novidades|lancad\\w*|lancament\\w*|recente\\w*|ultim[oa]s|aconteceu|" +
            "preco|precos|quanto custa|melhores|vale a pena|e bom|e boa|e confiavel|reclama\\w*|avalia\\w*|review)\\b"
    )
    private val ENCHIMENTO = setOf(
        "qual", "quais", "quanto", "quantos", "quantas", "como", "onde", "quando", "quem", "porque", "sao", "foi", "foram",
        "pesquisa", "pesquise", "procura", "procure", "busca", "busque", "fala", "fale", "conta", "conte", "sobre",
        "noticia", "noticias", "novidade", "novidades", "ultimas", "ultimos", "recentemente", "recentes", "recente",
        "existe", "existem", "tem", "ha", "custa", "aconteceu", "saber", "sabe", "voce", "diz", "diga", "pra", "para", "mim",
    )

    /** Palavras que importam numa pergunta, sem as de enchimento ("quais", "sobre", "novidades"...). */
    fun palavrasChave(pergunta: String): List<String> =
        Corretor.palavras(pergunta).filter { it !in BancoDeFrases.IRRELEVANTES && it !in ENCHIMENTO && it.length >= 2 }

    fun ehPesquisavel(pergunta: String): Boolean {
        val c = ComandosLocais.normalizar(pergunta)
        return Web.assuntoEnciclopedico(pergunta) != null || NOVIDADE.containsMatchIn(c)
    }

    /** Pontuação de uma notícia guardada para uma pergunta (palavras em comum no título e no assunto). */
    fun pontuar(chaves: List<String>, titulo: String, tema: String): Int {
        val t = ComandosLocais.normalizar(titulo)
        val a = ComandosLocais.normalizar(tema)
        var pontos = 0
        for (k in chaves) {
            val raiz = if (k.length >= 6) k.take(k.length - 2) else k
            if (Regex("\\b${Regex.escape(raiz)}").containsMatchIn(t)) pontos += 2
            if (a.contains(raiz)) pontos += 1
        }
        return pontos
    }

    /**
     * Responde uma pergunta sem o Gemini, escolhendo a melhor fonte.
     * [amplo] = false: só responde com fontes certeiras (verbetes e notícias), sem chutar uma busca genérica.
     * Devolve null quando nenhuma fonte tem uma resposta.
     */
    fun responderPergunta(pergunta: String, conhecimento: Conhecimento?, amplo: Boolean = true): Web.Resposta? {
        val normal = ComandosLocais.normalizar(pergunta)
        Web.assuntoEnciclopedico(pergunta)?.let { assunto ->
            (tentar { Web.wikipedia(assunto) } ?: tentar { wikipediaBusca(assunto) })?.let { return it }
        }
        val chaves = palavrasChave(pergunta)
        if (NOVIDADE.containsMatchIn(normal) && chaves.isNotEmpty()) {
            conhecimento?.buscar(pergunta)?.takeIf { it.isNotEmpty() }?.let {
                return formatarNoticias(it, "Pelas novidades que eu acompanho:")
            }
            tentar { noticias(chaves.joinToString(" ") + " when:30d") }?.takeIf { it.isNotEmpty() }?.let {
                conhecimento?.guardar(chaves.joinToString(" "), it)
                return formatarNoticias(it, "Encontrei estas notícias recentes:")
            }
        }
        if (!amplo || chaves.isEmpty()) return null
        return tentar { wikipediaBusca(chaves.joinToString(" ")) } ?: tentar { duckduckgo(pergunta) }
    }

    private inline fun <T> tentar(bloco: () -> T?): T? = try {
        bloco()
    } catch (e: Exception) {
        null
    }
}
