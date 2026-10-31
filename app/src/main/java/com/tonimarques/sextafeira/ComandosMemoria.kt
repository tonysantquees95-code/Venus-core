package com.tonimarques.sextafeira

/**
 * Pedidos sobre a memória: "lembra que...", "meu nome é...", "minha cidade é...",
 * "eu gosto de...", "minha música favorita é...", "o que você sabe sobre mim", "esquece que...".
 */
object ComandosMemoria {

    sealed class Pedido {
        /** Guarda um fato solto. */
        data class Lembrar(val texto: String, val categoria: String) : Pedido()

        /** Guarda um valor único (apelido, cidade, favorito). */
        data class Definir(val categoria: String, val valor: String) : Pedido()

        data class Esquecer(val trecho: String) : Pedido()
        object Listar : Pedido()
        object ApagarTudo : Pedido()
    }

    fun interpretar(texto: String): Pedido? {
        val f = ComandosLocais.limpar(texto)
        val c = f.chave
        if (c.isBlank()) return null

        if (APAGAR_TUDO.matches(c)) return Pedido.ApagarTudo
        if (LISTAR.matches(c)) return Pedido.Listar

        ESQUECER.matchEntire(c)?.let { m ->
            val trecho = f.trecho(m.groups[1]) ?: f.trecho(m.groups[2])
            if (!trecho.isNullOrBlank()) return Pedido.Esquecer(trecho.trimEnd('.', '!'))
        }
        APELIDO.matchEntire(c)?.let { m ->
            val nome = f.trecho(m.groups[1])?.trimEnd('.', '!')?.let { capitalizar(it) }
            if (!nome.isNullOrBlank() && nome.split(" ").size <= 4) return Pedido.Definir("apelido", nome)
        }
        CIDADE.matchEntire(c)?.let { m ->
            val cidade = f.trecho(m.groups[1])?.trimEnd('.', '!')?.let { capitalizar(it) }
            if (!cidade.isNullOrBlank() && cidade.split(" ").size <= 5) return Pedido.Definir("cidade", cidade)
        }
        FAVORITO.matchEntire(c)?.let { m ->
            val tipo = m.groupValues[1]
            val valor = f.trecho(m.groups[3])?.trimEnd('.', '!')
            if (!valor.isNullOrBlank()) return Pedido.Definir("favorito:$tipo", valor)
        }
        LEMBRAR.matchEntire(c)?.let { m ->
            val fato = f.trecho(m.groups[1])?.trimEnd('.', '!')
            if (!fato.isNullOrBlank()) return Pedido.Lembrar(fato, "fato")
        }
        GOSTO.matchEntire(c)?.let { m ->
            val objeto = m.groupValues[3]
            if (objeto.isNotBlank() && objeto !in setOf("voce", "vc", "te", "tu", "isso")) {
                return Pedido.Lembrar(f.original.trimEnd('.', '!'), "gosto")
            }
        }
        return null
    }

    private fun capitalizar(s: String) = s.split(" ").joinToString(" ") { p ->
        if (p.length <= 2 && p.lowercase() in setOf("de", "da", "do", "dos", "das", "e")) p.lowercase()
        else p.replaceFirstChar { it.uppercase() }
    }

    private val APAGAR_TUDO = Regex(
        "^(?:apaga|apague|limpa|limpe|esquece|esqueca|deleta)\\s+(?:todas as\\s+|tudo\\s+|toda a\\s+)?(?:suas\\s+|as\\s+|a\\s+)?" +
            "(?:memorias|memoria)(?:\\s+sobre mim)?$|^esquece tudo sobre mim$|^esqueca tudo sobre mim$"
    )
    private val LISTAR = Regex(
        "^(?:o que|que)\\s+(?:voce|vc)\\s+(?:sabe|lembra|lembra de|conhece|guardou|anotou)\\s+(?:sobre|de)\\s+mim$" +
            "|^(?:o que|que)\\s+(?:voce|vc)\\s+lembra$|^(?:quais sao\\s+)?(?:as\\s+)?minhas\\s+(?:preferencias|memorias)$" +
            "|^(?:me\\s+)?mostra\\s+(?:suas|as)\\s+memorias$|^(?:suas\\s+)?memorias$"
    )
    private val ESQUECER = Regex(
        "^(?:esquece|esqueca)\\s+(?:que|de|sobre|do|da)\\s+(.+)$" +
            "|^(?:apaga|apague|remove|remova|tira)\\s+(?:a\\s+|da\\s+)?memoria\\s+(?:de|sobre|que|do|da)\\s+(.+)$"
    )
    private val APELIDO = Regex(
        "^(?:meu nome e|meu nome eh|me chamo|me chama de|pode me chamar de|me chame de|quero que me chame de|eu sou o|eu sou a)\\s+(.+)$"
    )
    private val CIDADE = Regex(
        "^(?:minha cidade e|eu moro em|moro em|eu moro no|moro no|eu moro na|moro na|minha cidade agora e)\\s+(.+)$"
    )
    private val FAVORITO = Regex(
        "^(?:o |a )?(?:meu|minha)\\s+(musica|banda|cantor|cantora|artista|comida|cor|time|filme|serie|app|aplicativo|bebida|playlist|estilo)" +
            "\\s+(favorit[oa]|preferid[oa])\\s+(?:e|eh|sao)\\s+(.+)$"
    )
    private val LEMBRAR = Regex(
        "^(?:lembra|lembre|lembre-se|lembra-se|anota|anote|guarda|guarde|memoriza|memorize|grava na memoria|salva na memoria)" +
            "(?:\\s+(?:que|de que|de|disso|isso|ai))*[:,]?\\s+(?:que\\s+)?(.+)$"
    )
    private val GOSTO = Regex("^(?:eu\\s+)?(gosto|adoro|amo|curto|prefiro|odeio|detesto|nao gosto)\\s+(de |do |da |dos |das )?(.+)$")
}
