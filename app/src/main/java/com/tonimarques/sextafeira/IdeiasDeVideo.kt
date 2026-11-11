package com.tonimarques.sextafeira

import org.json.JSONObject

/**
 * Ideias de vídeo (Reels e stories em vídeo) feitas a partir dos produtos do catálogo: roteiro curto, o pedido
 * pronto para colar num gerador de vídeo por IA (Veo, Kling, Runway, Pika, Sora...), legenda e hashtags.
 * Os textos vêm da IA do servidor; o vídeo em si é gerado no gerador que você escolher.
 */
object IdeiasDeVideo {

    data class Ideia(val produto: String, val titulo: String, val roteiro: List<String>, val pedidoParaIA: String, val legenda: String, val hashtags: String)

    /** Escolhe até [max] produtos ativos, variando entre as chamadas, e resume nome e descrição de cada um. */
    fun escolher(produtos: List<Loja.Produto>, max: Int = 5, sorteio: java.util.Random = java.util.Random()): List<Loja.Produto> =
        produtos.filter { it.ativo }.shuffled(sorteio).take(max)

    fun pedido(produtos: List<Loja.Produto>, loja: String, ramo: String): String {
        val lista = produtos.joinToString("\n") { p ->
            val desc = p.bruto?.optString("description").orEmpty().takeIf { it != "null" }.orEmpty().replace(Regex("\\s+"), " ").take(220)
            "- ${p.nome}" + (if (desc.isNotBlank()) ": $desc" else "")
        }
        return "Você é a social media da $loja, loja online de $ramo. Crie uma ideia de vídeo curto (Reels de 8 a 15 segundos) para CADA produto abaixo, " +
            "para o Instagram da loja. Estética elegante, sensual sem ser explícita, nada vulgar nem nudez (regras do Instagram), sem promessas de saúde. " +
            "Cada vídeo deve fazer sentido com o produto de verdade: mostre o produto, o clima e o benefício, sem inventar características.\n" +
            "Produtos:\n$lista\n" +
            "Responda só com JSON: {\"ideias\":[{\"produto\":\"nome exato\",\"titulo\":\"até 50 caracteres\",\"roteiro\":[\"cena 1\",\"cena 2\",\"cena 3\"]," +
            "\"pedido_ia\":\"descrição da cena em inglês, pronta para um gerador de vídeo por IA: câmera, luz, cores, movimento, sem texto na tela\"," +
            "\"legenda\":\"até 220 caracteres, com chamada para ver no site\",\"hashtags\":\"5 hashtags\"}]}"
    }

    fun ler(json: String): List<Ideia> {
        val o = try {
            val i = json.indexOf('{')
            val f = json.lastIndexOf('}')
            if (i < 0 || f < i) return emptyList()
            JSONObject(json.substring(i, f + 1))
        } catch (e: Exception) {
            return emptyList()
        }
        val a = o.optJSONArray("ideias") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
            val roteiro = x.optJSONArray("roteiro")?.let { r -> (0 until r.length()).map { r.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty()
            val produto = x.optString("produto").trim()
            val pedido = x.optString("pedido_ia").trim()
            if (produto.isBlank() || roteiro.isEmpty() || pedido.isBlank()) null
            else Ideia(produto, x.optString("titulo").trim().take(70), roteiro.take(6), pedido.take(600), x.optString("legenda").trim().take(260), x.optString("hashtags").trim().take(160))
        }
    }

    /** Links de busca para ver como outras pessoas e marcas fazem vídeos parecidos (abrem a busca pronta, sem precisar de chave nenhuma). */
    fun inspiracao(produto: String): List<Pair<String, String>> {
        val termo = produto.replace(Regex("\\s+"), " ").trim().take(60)
        val q = java.net.URLEncoder.encode(termo, "UTF-8")
        val qIA = java.net.URLEncoder.encode("$termo vídeo feito com IA", "UTF-8")
        return listOf(
            "Pinterest" to "https://br.pinterest.com/search/videos/?q=$q",
            "YouTube Shorts" to "https://www.youtube.com/results?search_query=$q+shorts",
            "Vídeos feitos por IA" to "https://www.youtube.com/results?search_query=$qIA",
            "Na internet" to "https://www.google.com/search?tbm=vid&q=$q"
        )
    }

    fun descrever(ideias: List<Ideia>): String = buildString {
        append("Ideias de vídeo com os produtos da loja:\n")
        ideias.forEachIndexed { i, v ->
            append("\n${i + 1}. ${v.titulo.ifBlank { v.produto }} (${v.produto})\n")
            v.roteiro.forEachIndexed { n, c -> append("   Cena ${n + 1}: $c\n") }
            append("   Para o gerador de vídeo por IA: ${v.pedidoParaIA}\n")
            append("   Legenda: ${v.legenda} ${v.hashtags}\n")
            append("   Buscar inspiração:\n")
            inspiracao(v.produto).forEach { (nome, url) -> append("     • $nome: $url\n") }
        }
        append("\nPara gerar: cole o pedido em um gerador de vídeo por IA (Veo no Gemini, Kling, Runway, Pika ou Sora) e, se puder, anexe a foto do produto como referência. Depois é só postar como Reels.")
    }
}

/** Menu → "Ideias de vídeo": gera e mostra no chat. */
internal fun MainActivity.ideiasDeVideo() {
    val k = chave()
    if (k.isBlank() && !SegundaIA.configurada(this)) return responder("Entre no Vetorial ID para eu criar as ideias de vídeo.")
    responder("Vou olhar o seu catálogo e criar ideias de vídeo. Leva um minutinho.")
    Thread {
        val texto = try {
            val produtos = IdeiasDeVideo.escolher(Loja(this).produtos())
            if (produtos.isEmpty()) "Não achei produtos ativos no catálogo para criar os vídeos."
            else {
                val modelo = prefs.getString("modelo", "").orEmpty().ifBlank { "gemini-flash-latest" }
                val json = Gemini.gerarJson(k, modelo, IdeiasDeVideo.pedido(produtos, Perfil.loja, Perfil.ramo), temperatura = 0.9)
                val ideias = json?.let { IdeiasDeVideo.ler(it) }.orEmpty()
                if (ideias.isEmpty()) "Não consegui criar as ideias agora. Tente de novo daqui a pouco."
                else IdeiasDeVideo.descrever(ideias).also { Diario.anotar(this, "instagram", "${ideias.size} ideias de vídeo criadas a partir do catálogo", "tela") }
            }
        } catch (e: Exception) {
            "Não consegui criar as ideias: ${e.message ?: "sem conexão"}"
        }
        runOnUiThread { adicionarMensagem(texto, false) }
    }.start()
}
