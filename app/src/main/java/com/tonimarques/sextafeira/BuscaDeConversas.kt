package com.tonimarques.sextafeira

/**
 * Acha uma conversa antiga pelo assunto, mesmo com palavras diferentes ou erros de digitação/fala:
 * "conjunto de langerie" acha "conjuntos de lingerie".
 */
object BuscaDeConversas {

    private val PALAVRAS_VAZIAS = setOf(
        "o", "a", "os", "as", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
        "que", "sobre", "para", "pra", "pro", "com", "por", "e", "ou", "se", "eu", "voce", "vc", "me", "mim", "meu", "minha",
        "gente", "nos", "aquilo", "aquela", "aquele", "isso", "esse", "essa", "este", "esta", "lembra", "lembras", "lembrar",
        "falamos", "conversamos", "combinamos", "falou", "falei", "disse", "vimos", "conversa", "assunto", "ontem", "dia",
        "outro", "outra", "naquela", "naquele", "tinha", "tinhamos", "estava", "estavamos", "foi", "era", "ai", "la", "tal",
        "coisa", "coisas", "quando", "como", "qual", "quais", "vez", "aquele", "sim", "nao", "mais", "muito", "bem",
    )

    /** As palavras que importam na frase (sem acento, sem palavras vazias). */
    fun palavras(texto: String): List<String> =
        ComandosLocais.normalizar(texto).split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in PALAVRAS_VAZIAS }
            .distinct()

    private fun radical(p: String): String = when {
        p.length > 5 && p.endsWith("oes") -> p.dropLast(3) + "ao"
        p.length > 5 && p.endsWith("aes") -> p.dropLast(3) + "ao"
        p.length > 4 && p.endsWith("es") && !p.endsWith("ies") -> p.dropLast(2)
        p.length > 3 && p.endsWith("s") -> p.dropLast(1)
        else -> p
    }

    fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        var anterior = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val atual = IntArray(b.length + 1)
            atual[0] = i
            for (j in 1..b.length) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                atual[j] = minOf(anterior[j] + 1, atual[j - 1] + 1, anterior[j - 1] + custo)
            }
            anterior = atual
        }
        return anterior[b.length]
    }

    /** A palavra procurada aparece (exata, no plural/singular, ou com um errinho de digitação)? */
    fun casa(procurada: String, palavra: String): Boolean {
        if (procurada == palavra) return true
        val a = radical(procurada)
        val b = radical(palavra)
        if (a == b) return true
        if (a.length >= 6 && b.length >= 6 && (a.startsWith(b) || b.startsWith(a)) && kotlin.math.abs(a.length - b.length) <= 3) return true
        if (a.length >= 5 && b.length >= 5) {
            val limite = if (a.length >= 8) 2 else 1
            if (distancia(a, b) <= limite) return true
        }
        return false
    }

    /**
     * Nota de 0 a 1: quanto das palavras procuradas aparece no texto da conversa.
     * [titulo] e [resumo] (quando a IA já resumiu a conversa) valem um pouco mais.
     */
    fun nota(consulta: List<String>, textos: List<String>, titulo: String = "", resumo: String = ""): Double {
        if (consulta.isEmpty()) return 0.0
        val doTexto = textos.flatMap { palavras(it) }.toSet()
        val doTitulo = (palavras(titulo) + palavras(resumo)).toSet()
        var soma = 0.0
        for (q in consulta) {
            soma += when {
                doTitulo.any { casa(q, it) } -> 1.2
                doTexto.any { casa(q, it) } -> 1.0
                else -> 0.0
            }
        }
        return (soma / consulta.size).coerceAtMost(1.0)
    }

    /** O pedaço da conversa que mais tem a ver com a busca (para mostrar no resultado). */
    fun melhorTrecho(consulta: List<String>, textos: List<String>): String? =
        textos.filter { !it.startsWith("Fontes: ") }
            .maxByOrNull { t -> val p = palavras(t); consulta.count { q -> p.any { casa(q, it) } } }
            ?.takeIf { t -> val p = palavras(t); consulta.any { q -> p.any { casa(q, it) } } }

    /** "Vale a pena investir em conjuntos de lingerie agora?" → "Vale a pena investir em conjuntos de lingerie agora" (título provisório). */
    fun tituloProvisorio(texto: String): String {
        val t = texto.trim().replace(Regex("\\s+"), " ").trimEnd('?', '!', '.')
        return if (t.length <= 48) t.replaceFirstChar { it.uppercase() } else t.take(46).substringBeforeLast(' ').replaceFirstChar { it.uppercase() } + "…"
    }
}
