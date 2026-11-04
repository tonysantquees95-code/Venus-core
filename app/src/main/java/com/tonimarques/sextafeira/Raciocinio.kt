package com.tonimarques.sextafeira

/**
 * Como a Vênus "pensa" antes de responder:
 *  1. lê a frase inteira e separa pedidos juntos ("quanto vendi hoje e o que está acabando") em etapas;
 *  2. reconhece perguntas que pedem raciocínio (lógica, contas com enunciado, comparações, decisões, estratégia)
 *     e, nelas, dá ao Gemini mais tempo para raciocinar por dentro, conferir as contas e explicar a conclusão.
 */
object Raciocinio {

    /** Quanto o modelo pode "pensar" (tokens internos) numa pergunta difícil. */
    const val ORCAMENTO = 2048

    val INSTRUCOES_PROFUNDAS = """
        Responda em português do Brasil e sem markdown (a resposta pode ser falada em voz alta).
        Esta pergunta pede raciocínio. Antes de responder, pense passo a passo por dentro:
        identifique o que foi perguntado e os dados dados; desconfie de pegadinhas e de palavras ambíguas;
        faça as contas com cuidado e confira o resultado por outro caminho; considere hipóteses alternativas.
        Na resposta, diga primeiro a conclusão, depois explique o porquê em poucas frases claras e encadeadas
        (até 8 frases). Se faltar algum dado, diga qual e qual hipótese você usou. Em decisões de negócio,
        traga números, riscos e uma recomendação prática. Quando vierem os dados reais da loja junto da pergunta,
        faça as contas com eles (margem, giro, ticket médio, despesas) e cite os números que usou.
    """.trimIndent()

    private val SINAIS = listOf(
        // lógica, enigmas e dedução
        "\\bo que e o que e\\b|\\b(?:adivinha|adivinhacao|adivinhe)\\b",
        "\\b(?:charada|enigma|logica|raciocinio|deduz|deducao|pegadinha|silogismo|paradoxo|sequencia logica|qual (?:e )?o proximo numero)\\b",
        "\\bse (?:todo|todos|toda|todas|nenhum|nenhuma|alguns|algumas)\\b",
        "\\b(?:quem (?:e|eh) (?:o|a) (?:mais|culpad)|quem esta mentindo|quem mente|verdade ou mentira)\\b",
        // contas com enunciado
        "\\b(?:quantos|quantas|quanto tempo|em quanto tempo|quanto (?:sobra|falta|custa cada|rende|lucro))\\b.*\\d.*\\b(?:se|cada|por|levam|leva|demora|dividid)\\b",
        "\\b(?:a mais que|a menos que|mais que|menos que|o dobro|a metade|o triplo)\\b.*\\b(?:quanto|quantos|quantas|qual)\\b",
        "\\b(?:probabilidade|chance de|porcentagem de|regra de tres|media ponderada|juros compostos|equacao|resolva|calcule)\\b",
        // decisões, comparações, estratégia
        "\\b(?:vale a pena|compensa|devo|deveria|qual (?:e )?(?:a )?melhor (?:opcao|estrategia|forma|jeito|escolha)|o que e melhor|pros e contras|vantagens e desvantagens|prós e contras)\\b",
        "\\b(?:compare|comparar|diferenca entre|analise|analisa|avalie|avalia|estrategia|planejamento|plano de|como (?:posso|devo) (?:aumentar|melhorar|crescer|vender mais|reduzir))\\b",
        "\\b(?:por que|porque sera|explique|explica (?:por que|como)|como funciona)\\b",
    ).map { Regex(it) }

    /** A pergunta pede raciocínio (vale pensar mais antes de responder)? */
    fun precisaPensar(frase: String): Boolean {
        val c = ComandosLocais.normalizar(frase).replace(Regex("[^a-z0-9%$ ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (c.isBlank()) return false
        if (SINAIS.any { it.containsMatchIn(c) }) return true
        // enunciados longos com vários números costumam ser problemas
        val numeros = Regex("\\d+").findAll(c).count()
        val palavras = c.split(" ").size
        return (numeros >= 3 && palavras >= 10) || palavras >= 35
    }

    // ================================================================== frases com vários pedidos
    private const val LIGA = "(?:e também|e tambem|e depois|e ainda|além disso|alem disso|depois|então|entao|e)"
    private val SEPARADOR = Regex("\\s*,\\s+(?:$LIGA\\s+)?|\\s+$LIGA\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE)

    /** Ações que pedem tela ou confirmação: só podem ser a última etapa de um plano. */
    val SO_NO_FIM = setOf(
        "loja_alterar", "loja_entregue", "loja_status", "loja_promo_parados", "loja_cadastrar", "editar_fundo", "ler_nota",
        "abrir_arquivo", "programar_post", "listar_posts", "instagram_conectar", "instagram_comentarios", "postar_agora", "estudo_agora", "estudo_resumo", "responder_cliente", "caixa_grafico", "caixa_relatorio",
        "buscar_conversa", "pesquisar_na_web", "tocar_musica", "controlar_midia", "loja_chamar_cliente", "abrir_app",
        "abrir_site", "abrir_mapa", "fazer_ligacao", "enviar_whatsapp", "enviar_sms",
    )

    /**
     * "Quanto vendi hoje e o que está acabando?" → ["Quanto vendi hoje", "o que está acabando"], quando cada parte,
     * sozinha, é um pedido que a Vênus entende. Devolve null se a frase é um pedido só.
     */
    fun dividir(frase: String, entende: (String) -> String? = ::acaoDe): List<Pair<String, String>>? {
        val texto = frase.trim().trimEnd('?', '!', '.')
        val verbo = texto.substringBefore(' ')
        // "paguei 120 de internet e 80 de luz": a segunda parte herda o verbo da primeira
        fun tenta(p: String): Pair<String, String>? =
            entende(p)?.let { p to it }
                ?: if (!p.startsWith(verbo, ignoreCase = true)) entende("$verbo $p")?.let { "$verbo $p" to it } else null
        val partes = mutableListOf<Pair<String, String>>()
        var resto = texto
        while (true) {
            val corte = SEPARADOR.findAll(resto).firstOrNull { m ->
                val esquerda = resto.substring(0, m.range.first).trim()
                val direita = resto.substring(m.range.last + 1).trim()
                esquerda.split(" ").size >= 2 && direita.split(" ").size >= 2 &&
                    (if (partes.isEmpty()) entende(esquerda) != null else tenta(esquerda) != null) &&
                    (tenta(direita) != null || SEPARADOR.containsMatchIn(direita))
            } ?: break
            val esquerda = resto.substring(0, corte.range.first).trim()
            partes += (if (partes.isEmpty()) entende(esquerda)?.let { esquerda to it } else tenta(esquerda)) ?: return null
            resto = resto.substring(corte.range.last + 1).trim()
        }
        if (partes.isEmpty()) return null
        partes += tenta(resto) ?: return null
        // o que precisa de tela ou de "sim" só pode vir por último
        if (partes.dropLast(1).any { it.second in SO_NO_FIM }) return null
        return partes
    }

    private fun acaoDe(parte: String): String? = (ComandosLocais.interpretar(parte) as? ComandosLocais.Resultado.Acao)?.nome
}
