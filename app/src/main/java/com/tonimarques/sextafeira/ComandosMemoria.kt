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

        /** "Salva o endereço do meu trabalho" sem dizer o endereço: a Vênus pergunta. */
        data class PerguntarLugar(val lugar: String) : Pedido()

        data class Esquecer(val trecho: String) : Pedido()
        object Listar : Pedido()
        object ApagarTudo : Pedido()
    }

    fun interpretar(texto: String): Pedido? {
        val f = ComandosLocais.limpar(texto)
        val c = f.chave
        if (c.isBlank()) return null
        // "anota uma despesa de 30 de gasolina", "registra uma entrada de 200": é o caixa da loja, não a memória
        if (ComandosLocais.financas(f) != null) return null
        // "lembra do que falamos sobre…": é buscar uma conversa antiga, não guardar uma memória
        if (ComandosLocais.conversaAntiga(f) != null) return null

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
        // "grave o endereço do meu trabalho: eu trabalho no Hotel X", "meu endereço é Rua X, grave como minha casa"
        GRAVAR_ENDERECO_COM.matchEntire(c)?.let { m ->
            val lugar = nomeDoLugar(m.groupValues[1])
            val endereco = f.trecho(m.groups[2])?.trim()?.trimEnd('.', '!')
            if (lugar != null && !endereco.isNullOrBlank() && endereco.length >= 4) return Pedido.Definir("lugar:$lugar", endereco)
        }
        MEU_ENDERECO_COMO.matchEntire(c)?.let { m ->
            val lugar = nomeDoLugar(m.groupValues[2])
            val endereco = f.trecho(m.groups[1])?.trim()?.trimEnd('.', '!', ',')
            if (lugar != null && !endereco.isNullOrBlank()) return Pedido.Definir("lugar:$lugar", endereco)
        }
        MEU_ENDERECO.matchEntire(c)?.let { m ->
            val endereco = f.trecho(m.groups[1])?.trim()?.trimEnd('.', '!')
            if (!endereco.isNullOrBlank() && endereco.length >= 4) return Pedido.Definir("lugar:casa", endereco)
        }
        PEDIR_ENDERECO.matchEntire(c)?.let { m ->
            val lugar = nomeDoLugar(m.groupValues[1]) ?: return@let
            return Pedido.PerguntarLugar(lugar)
        }
        // Endereços com nome: "minha casa fica na Rua X, 123", "o endereço do meu trabalho é ..."
        for (regra in LUGARES) {
            val m = regra.matchEntire(c) ?: continue
            val lugar = nomeDoLugar(m.groupValues[1]) ?: continue
            val endereco = f.trecho(m.groups[2])?.trim()?.trimEnd('.', '!')
                ?.replace(Regex("^(?i)(na|no|em|nas|nos) "), "")
            if (!endereco.isNullOrBlank() && endereco.length >= 4) return Pedido.Definir("lugar:$lugar", endereco)
        }
        // "eu moro na Rua X" é endereço de casa, não cidade
        MORO_NA_RUA.matchEntire(c)?.let { m ->
            val endereco = f.trecho(m.groups[1])?.trim()?.trimEnd('.', '!')
            if (!endereco.isNullOrBlank()) return Pedido.Definir("lugar:casa", endereco)
        }
        TRABALHO_NA_RUA.matchEntire(c)?.let { m ->
            val endereco = f.trecho(m.groups[1])?.trim()?.trimEnd('.', '!')
            if (!endereco.isNullOrBlank()) return Pedido.Definir("lugar:trabalho", endereco)
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

    /** "casa", "trabalho", "academia"... Sinônimos viram um nome só. */
    fun nomeDoLugar(palavra: String): String? {
        val p = palavra.trim().replace(Regex("\\b(?:minha|meu|nossa|nosso) "), "").removePrefix("a ").removePrefix("o ").trim()
        return when (p) {
            "casa", "lar", "residencia", "ape", "apartamento" -> "casa"
            "trabalho", "servico", "emprego", "firma", "escritorio", "empresa" -> "trabalho"
            "" -> null
            else -> p.takeIf { it.length in 3..30 && it.split(" ").size <= 3 }
        }
    }

    private const val TIPOS_LUGAR = "casa|lar|residencia|ape|apartamento|trabalho|servico|emprego|firma|escritorio|empresa|loja|academia|faculdade|escola|" +
        "igreja|mae|pai|vo|avo|namorada|namorado|esposa|marido|clinica|mercado|deposito|estudio|consultorio"
    private val RUA = "(?:rua|r\\.|avenida|av\\.?|travessa|alameda|estrada|rodovia|praca|largo|beco|servidao|condominio|bairro)"
    private val LUGARES = listOf(
        Regex("^(?:a |o )?(?:minha |meu |nossa |nosso )?((?:$TIPOS_LUGAR)(?: d[aoe] (?:minha |meu )?\\w+)?) (?:fica|e|eh|e localizad[oa]|fica localizad[oa]) ((?:na|no|em|nas|nos) .+|$RUA .+|.*\\d.*)$"),
        Regex("^(?:o )?endereco d[aoe] (?:minha |meu )?((?:$TIPOS_LUGAR)(?: d[aoe] (?:minha |meu )?\\w+)?) (?:e|eh) (.+)$"),
        Regex("^(?:salva|salve|guarda|guarde|anota|anote) como (?:minha |meu |a |o )?((?:$TIPOS_LUGAR)(?: d[aoe] \\w+)?):? (?:o endereco )?(.+)$"),
    )
    private const val GRAVAR = "(?:salva|salve|salvar|grava|grave|gravar|guarda|guarde|guardar|anota|anote|anotar|registra|registre|cadastra|cadastre|memoriza|memorize)"
    private val NOME_LUGAR = "((?:$TIPOS_LUGAR)(?: d[aoe] (?:minha |meu )?\\w+)?)"
    private val GRAVAR_ENDERECO_COM = Regex(
        "^$GRAVAR (?:tambem )?(?:o |a )?(?:meu |minha )?endereco d[aoe] (?:meu |minha )?$NOME_LUGAR[,:]? " +
            "(?:que (?:e|eh) |(?:e|eh|fica) )?(?:(?:eu )?(?:trabalho|moro|estudo|treino) )?(?:na |no |em )?(.+)$"
    )
    private val MEU_ENDERECO_COMO = Regex(
        "^(?:o )?meu endereco (?:e|eh) (?:na |no |em )?(.+?),? (?:e )?$GRAVAR (?:este|esse|o|isso) (?:endereco )?(?:como )?(?:a |o )?(?:minha |meu )?$NOME_LUGAR$"
    )
    private val MEU_ENDERECO = Regex("^(?:o )?meu endereco (?:e|eh|fica) (?:na |no |em )?(.+)$")
    private val PEDIR_ENDERECO = Regex(
        "^(?:$GRAVAR (?:tambem )?)?(?:o |a )?(?:meu |minha )?endereco d[aoe] (?:meu |minha )?$NOME_LUGAR$"
    )
    private val MORO_NA_RUA = Regex("^(?:eu )?moro (?:na |no |em )($RUA .+)$")
    private val TRABALHO_NA_RUA = Regex("^(?:eu )?trabalho (?:na |no |em )($RUA .+)$")

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
