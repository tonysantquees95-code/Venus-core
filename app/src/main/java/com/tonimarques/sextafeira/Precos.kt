package com.tonimarques.sextafeira

/**
 * Pesquisa de preços na internet (Mercado Livre, Amazon, Shopee, Magalu, lojas especializadas…)
 * e comparação com o preço da sua loja.
 */
object Precos {

    data class Oferta(val loja: String, val precoCentavos: Int, val obs: String)

    /** "Mercado Livre — R$ 29,90 — frete grátis" → Oferta. */
    fun lerOfertas(texto: String): List<Oferta> = texto.lines().mapNotNull { linha ->
        val l = linha.trim().trimStart('-', '•', '*', ' ').trim()
        if (l.startsWith("RESUMO", ignoreCase = true)) return@mapNotNull null
        val m = Regex("^(.{2,60}?)\\s*[—–:|-]+\\s*R\\$\\s?(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{2})?|\\d+(?:,\\d{2})?)\\s*(?:[—–|-]+\\s*(.*))?$").find(l) ?: return@mapNotNull null
        val centavos = ComandosLocais.centavosF(m.groupValues[2], "") ?: return@mapNotNull null
        Oferta(m.groupValues[1].trim().trimEnd('-', '—', ':').trim(), centavos, m.groupValues[3].trim())
    }

    /** O menor preço e a média das ofertas (centavos). */
    fun faixa(ofertas: List<Oferta>): Pair<Int, Int>? {
        if (ofertas.isEmpty()) return null
        return ofertas.minOf { it.precoCentavos } to (ofertas.sumOf { it.precoCentavos.toLong() } / ofertas.size).toInt()
    }

    /** Como o preço da sua loja está em relação ao mercado. */
    fun comparar(nome: String, nosso: Int, custo: Int?, ofertas: List<Oferta>): String? {
        val (menor, media) = faixa(ofertas) ?: return null
        val dif = ((nosso - media) * 100.0 / media).let { kotlin.math.round(it).toInt() }
        var t = "Na sua loja, $nome sai por ${Loja.dinheiro(nosso)}: "
        t += when {
            dif >= 15 -> "$dif% acima da média do mercado (${Loja.dinheiro(media)})."
            dif <= -15 -> "${-dif}% abaixo da média (${Loja.dinheiro(media)}); talvez dê para subir um pouco."
            else -> "na média do mercado (${Loja.dinheiro(media)})."
        }
        if (nosso > menor) t += " O menor preço achado é ${Loja.dinheiro(menor)}."
        if (custo != null && custo > 0) {
            val margemNaMedia = ((media - custo) * 100.0 / media).toInt()
            t += if (media > custo) " Com seu custo de ${Loja.dinheiro(custo)}, vendendo pela média a margem seria de $margemNaMedia%."
            else " Atenção: a média do mercado está abaixo do seu custo (${Loja.dinheiro(custo)})."
        }
        return t
    }

    /** O texto falado: as ofertas mais baratas primeiro. */
    fun textoOfertas(produto: String, ofertas: List<Oferta>): String {
        val ordem = ofertas.sortedBy { it.precoCentavos }.take(5)
        val (menor, media) = faixa(ordem) ?: return "Não achei preços confiáveis de $produto agora."
        return "Preços de $produto na internet: " + ordem.joinToString("; ") { o ->
            "${o.loja}, ${Loja.dinheiro(o.precoCentavos)}" + (if (o.obs.isNotBlank()) " (${o.obs.trimEnd('.')})" else "")
        } + ". Menor: ${Loja.dinheiro(menor)}; média: ${Loja.dinheiro(media)}. Os preços mudam rápido, confira no link antes de comprar."
    }

    fun linkShopping(produto: String) =
        "https://www.google.com/search?tbm=shop&q=" + java.net.URLEncoder.encode(produto, "UTF-8")
}
