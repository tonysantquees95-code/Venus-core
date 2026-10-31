package com.tonimarques.sextafeira

/** Dá um tópico a cada conversa, para o histórico ficar organizado. */
object Topicos {

    fun deFerramenta(nome: String?): String? = when (nome) {
        "tocar_musica", "controlar_midia" -> "Música"
        "enviar_whatsapp", "enviar_sms", "fazer_ligacao" -> "Mensagens e ligações"
        "criar_alarme", "criar_timer" -> "Alarmes e timers"
        "previsao_do_tempo" -> "Clima"
        "abrir_mapa" -> "Lugares e rotas"
        "pesquisar_na_web", "buscar_na_internet", "abrir_site", "resumo_wikipedia" -> "Pesquisas"
        "abrir_app", "abrir_camera", "abrir_configuracao", "lanterna", "nivel_bateria" -> "Celular"
        "salvar_memoria" -> "Sobre mim"
        else -> null
    }

    private val PALAVRAS: List<Pair<String, Regex>> = listOf(
        "Clima" to Regex("\\b(graus|temperatura|previsao|clima|chuva|chover|frio|calor|tempo amanha|tempo hoje|sol)\\b"),
        "Compras" to Regex("\\b(loja|lojas|comprar|compra|preco|precos|produto|frete|entrega|promocao|desconto|vale a pena|confiavel)\\b"),
        "Trabalho" to Regex("\\b(trabalho|cliente|clientes|venda|vendas|pedido|pedidos|estoque|reuniao|negocio|empresa|site)\\b"),
        "Finanças" to Regex("\\b(dinheiro|banco|pix|conta|boleto|cartao|investimento|salario|real|dolar)\\b"),
        "Saúde" to Regex("\\b(saude|medico|remedio|dor|doenca|exercicio|academia|dieta|sono)\\b"),
        "Culinária" to Regex("\\b(receita|comida|cozinhar|bolo|almoco|janta|jantar|cafe|lanche)\\b"),
        "Esportes" to Regex("\\b(futebol|jogo|time|gremio|inter|campeonato|placar|gol)\\b"),
        "Entretenimento" to Regex("\\b(filme|filmes|serie|series|netflix|show|livro|jogo)\\b"),
        "Notícias" to Regex("\\b(noticia|noticias|aconteceu|hoje no brasil|eleicao|governo)\\b"),
        "Sobre mim" to Regex("\\b(lembra|lembre|meu nome|me chama|minha cidade|eu gosto|favorit[oa]|minhas preferencias)\\b"),
    )

    fun deTexto(texto: String): String {
        val c = ComandosLocais.normalizar(texto)
        for ((topico, regra) in PALAVRAS) if (regra.containsMatchIn(c)) return topico
        return "Conversa"
    }

    fun classificar(texto: String, ferramenta: String? = null): String = deFerramenta(ferramenta) ?: deTexto(texto)
}
