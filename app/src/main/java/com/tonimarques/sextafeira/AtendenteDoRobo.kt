package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * O "cérebro" do Vênus Robô quando ele responde pela Vênus deste celular (cada lojista com a sua loja),
 * em vez de perguntar à Vênus do site. Mesmo jeito do robô do site:
 * primeira mensagem → boas-vindas com as opções; volta em até 14 dias → "em que posso ajudar";
 * opções 2 a 5 com as respostas exatas das regras de venda; troca, defeito, atraso ou "falar com alguém"
 * → chama a equipe e fica 12 h quieto com aquela cliente; o resto vai para a IA (Groq → Cloudflare → Gemini)
 * com as regras e os produtos da loja. A conversa fica só neste celular, guardada por um código da cliente.
 */
object AtendenteDoRobo {

    const val GUARDAR_DIAS = 14
    const val PAUSA_HUMANO_MS = 12 * 3600_000L
    private const val PREFS = "robo_atendimento"

    val PEDE_PESSOA = Regex("\\b(atendente|humano|pessoa de verdade|falar com (alguem|a dona|o dono|voces|vcs)|troca|trocar|defeito|reclama|atrasad|nao chegou|reembolso|estorno|devolu)")
    val SAUDACAO = Regex("^(oi+|ola+|bom dia|boa tarde|boa noite|e ai|opa|hey|oie+|tudo bem|tudo bom|menu|inicio|comecar)\\b")
    private val CEP = Regex("\\b\\d{5}-?\\d{3}\\b")

    data class Msg(val cliente: Boolean, val texto: String, val quando: Long)
    data class Conversa(val mensagens: List<Msg>, val pausadoAte: Long, val menu: String?, val atualizado: Long)
    data class Decisao(val texto: String, val humano: Boolean, val menu: String?)
    data class Saida(val mensagens: List<String>, val humano: Boolean)

    // ------------------------------------------------------------------ textos
    fun menu(loja: String, assistente: String, temRetirada: Boolean, temDiscreta: Boolean = true): String =
        "Oi! Eu sou a $assistente, assistente virtual da $loja 💗 Como posso te ajudar? Responda com o número:\n" +
            "1 - Produtos e promoções\n2 - Frete e prazo de entrega\n3 - Formas de pagamento\n" + (if (temDiscreta) "4 - Entrega discreta\n" else "") +
            (if (temRetirada) "5 - Retirada\n" else "") + "6 - Falar com a loja\n\nSe preferir, é só escrever sua dúvida."

    const val RODAPE = "\n\nDigite 0 para ver as opções ou 6 para falar com a loja."
    const val CHAMAR_EQUIPE = "Claro! Vou chamar a equipe da loja para te atender pessoalmente, só um instante 💗"

    fun normalizar(t: String): String = ComandosLocais.normalizar(t)

    /**
     * O que dá para responder sem IA (menu, opções, pessoa da loja, CEP). Null = vai para a IA.
     * [regras] = respostas prontas por id (pagamento, frete, frete_gratis, postagem, retirada, discreto, troca…).
     */
    fun decidir(
        mensagem: String, conversa: Conversa?, regras: Map<String, String>, loja: String, assistente: String, site: String, agora: Long,
        temRetirada: Boolean = true, temDiscreta: Boolean = true, ramoIntimo: Boolean = true,
    ): Decisao? {
        val t = normalizar(mensagem)
        val numero = if (Regex("^\\s*\\d{1,2}\\s*[.)-]?\\s*$").matches(mensagem)) mensagem.filter { it.isDigit() }.toInt() else null
        val recente = conversa != null && conversa.mensagens.isNotEmpty() && agora - conversa.atualizado < GUARDAR_DIAS * 86_400_000L
        val menuAtual = if (recente) conversa?.menu else null
        val texto = { id: String -> regras[id] }
        val noSite = if (site.isNotBlank()) " Veja tudo no site: $site" else ""
        if (PEDE_PESSOA.containsMatchIn(t) || (numero == 6)) return Decisao(CHAMAR_EQUIPE, true, null)
        val curta = t.split(' ').filter { it.isNotBlank() }.size <= 3
        if (numero == 0 || t == "menu" || (!recente && (SAUDACAO.containsMatchIn(t) || curta))) {
            return Decisao(menu(loja, assistente, temRetirada, temDiscreta), false, "principal")
        }
        if (recente && SAUDACAO.containsMatchIn(t) && curta) {
            return Decisao("Oi! Que bom falar com você de novo 💗 Em que posso te ajudar?$RODAPE", false, "principal")
        }
        if (numero != null && menuAtual == "principal") {
            // sem site e sem a regra preenchida, quem responde é a equipe: não dá para mandar ver no site
            if (site.isBlank() && ((numero == 2 && listOf("frete", "frete_gratis", "postagem").all { texto(it) == null }) || (numero == 3 && texto("pagamento") == null))) {
                return Decisao("Vou pedir para a equipe te passar " + (if (numero == 2) "as opções de entrega e o valor" else "as formas de pagamento") + ", só um instante 💗", true, null)
            }
            val r = when (numero) {
                1 -> "Me conta o que você procura" + (if (ramoIntimo) " (por exemplo: gel, lingerie, presente para casal)" else "") + " que eu te mostro as opções 💗$noSite"
                2 -> listOfNotNull(texto("frete"), texto("frete_gratis"), texto("postagem")).joinToString(" ").ifBlank { "O frete é calculado pelo CEP." } +
                    (if (site.isNotBlank()) " Me mande seu CEP ou simule no site para ver o valor e o prazo certinhos. $site" else " Me mande seu CEP que a equipe confirma o valor e o prazo certinhos.")
                3 -> texto("pagamento") ?: "As formas de pagamento aparecem no site, na hora de finalizar.$noSite"
                4 -> if (temDiscreta) texto("discreto") ?: "Sim, a entrega é discreta: embalagem sem identificação do conteúdo."
                else return Decisao("Não encontrei essa opção. " + menu(loja, assistente, temRetirada, temDiscreta), false, "principal")
                5 -> if (temRetirada) texto("retirada") ?: "Dá para combinar a retirada com a equipe." else return Decisao("Não encontrei essa opção. " + menu(loja, assistente, temRetirada, temDiscreta), false, "principal")
                else -> return Decisao("Não encontrei essa opção. " + menu(loja, assistente, temRetirada, temDiscreta), false, "principal")
            }
            return Decisao(r + RODAPE, false, if (numero == 1) null else "principal")
        }
        if (CEP.containsMatchIn(mensagem)) {
            if (site.isBlank()) return Decisao("Vou pedir para a equipe calcular o frete para o seu CEP, só um instante 💗", true, null)
            val r = "Para ver o valor e o prazo para o seu CEP, é só colocar os produtos no carrinho do site: $site" +
                (texto("frete_gratis")?.let { " $it" } ?: "")
            // sem site, quem calcula o frete é a equipe
            return Decisao(r + RODAPE, site.isBlank(), null)
        }
        return null
    }

    // ------------------------------------------------------------------ mensagens prontas que o site manda a cliente enviar
    private val RE_TROCA = Regex("solicitacao de troca/devolucao n\\D{0,3}(\\d+)")
    private val RE_PEDIDO_FEITO = Regex("fiz o pedido #\\s?(\\d+) no site")
    private val RE_MOTOBOY = Regex("acabei de fazer um pedido no site e escolhi a entrega via motoboy")
    const val PRAZO_TROCA = "2 dias úteis"

    /**
     * A solicitação de troca/devolução e a confirmação de compra que o site prepara (as mesmas que o robô do site reconhece).
     * [pedido] busca o pedido pelo número (na cópia local da Vênus); [prazoPostagem] em dias úteis. Null = não é uma delas.
     */
    fun especial(mensagem: String, pedido: (Int) -> Loja.Pedido?, prazoPostagem: Int, embalagemDiscreta: Boolean = true, aceitaPix: Boolean = true): Decisao? {
        val t = normalizar(mensagem)
        RE_TROCA.find(t)?.let { m ->
            return Decisao(
                "Recebemos sua solicitação nº ${m.groupValues[1]} 💗 Obrigada por avisar! Agora é só mandar aqui as fotos do produto e do problema e, " +
                    "se tiver, o vídeo da abertura da embalagem. Nossa equipe analisa e te responde em até $PRAZO_TROCA por aqui.", true, null,
            )
        }
        if (RE_MOTOBOY.containsMatchIn(t)) {
            return Decisao("Recebemos seu pedido com entrega por motoboy ✅ A equipe confere o endereço e já confirma a saída do entregador por aqui 💗", true, null)
        }
        val numero = RE_PEDIDO_FEITO.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val p = try {
            pedido(numero)
        } catch (e: Exception) {
            null
        } ?: return Decisao("Recebi sua mensagem sobre o pedido 💗 Vou pedir para a equipe conferir, e já te respondemos por aqui.", true, null)
        return when (p.status) {
            "canceled" -> Decisao("Vi aqui que o pedido #${p.id} está cancelado. Vou chamar a equipe para te ajudar, só um instante 💗", true, null)
            "awaiting_payment" -> Decisao(
                "Recebemos seu pedido #${p.id} 💗 Ele ainda está aguardando a confirmação do pagamento. " +
                    (if (aceitaPix) "No Pix, ela costuma sair em poucos minutos e aparece" else "Ela aparece") +
                    " na página do pedido no site. Assim que entrar, a gente separa tudo!", false, null,
            )
            "shipped" -> Decisao("Seu pedido #${p.id} já foi postado 📦 O código de rastreio está na página do pedido no site. Qualquer dúvida, é só chamar!", false, null)
            "delivered" -> Decisao("Vi aqui que o pedido #${p.id} já foi entregue 💗 Em que posso te ajudar?", false, null)
            else -> when (p.entrega) {
                "pickup" -> Decisao("Recebemos seu pedido #${p.id} e o pagamento está confirmado ✅ Vou chamar a equipe para combinar com você o dia, o horário e o local da retirada, só um instante 💗", true, null)
                "motoboy" -> Decisao("Recebemos seu pedido #${p.id} e o pagamento está confirmado ✅ A equipe já vai confirmar a saída do entregador por aqui 💗", true, null)
                else -> Decisao(
                    "Oba! Recebemos seu pedido #${p.id} e o pagamento está confirmado ✅ Vamos postar em até $prazoPostagem ${if (prazoPostagem == 1) "dia útil" else "dias úteis"}, " +
                        (if (embalagemDiscreta) "em embalagem discreta, " else "") + "e te mandamos o código de rastreio por aqui. Obrigada pela compra! 💗", false, null,
                )
            }
        }
    }

    /** Os produtos mais ligados à conversa (palavras em comum), para não mandar o catálogo inteiro à IA. */
    fun produtosRelevantes(texto: String, produtos: List<Loja.Produto>, maximo: Int = 20, similaridades: Map<Int, Double>? = null): List<Loja.Produto> {
        val palavras = normalizar(texto).split(' ').filter { it.length >= 4 }.toSet()
        val ativos = produtos.filter { it.ativo && it.precoCentavos != null }
        val porPalavra = if (palavras.isEmpty()) emptyList() else
            ativos.map { p -> p to normalizar(p.nome).split(' ').count { w -> palavras.any { w.startsWith(it.take(5)) } } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first.id }
        val porSentido = similaridades?.entries?.filter { it.value >= SENTIDO_MINIMO }?.sortedByDescending { it.value }?.map { it.key }.orEmpty()
        val ids = fundirRankings(listOf(porPalavra, porSentido), maximo)
        if (ids.isEmpty()) return ativos.take(12)
        val porId = ativos.associateBy { it.id }
        return ids.mapNotNull { porId[it] }
    }

    /** Parecido mínimo (0 a 1) para o sentido contar; abaixo disso é ruído. */
    const val SENTIDO_MINIMO = 0.55

    /** Junta listas ordenadas (palavra + sentido) em uma só: quem aparece bem em várias sobe (soma de 1/(60+posição)). */
    fun fundirRankings(rankings: List<List<Int>>, maximo: Int, k: Int = 60): List<Int> {
        val nota = HashMap<Int, Double>()
        for (r in rankings) r.forEachIndexed { i, id -> nota[id] = (nota[id] ?: 0.0) + 1.0 / (k + i + 1) }
        return nota.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).take(maximo).map { it.key }
    }

    // ------------------------------------------------------------------ conversa guardada (só no celular)
    fun codigo(canal: String, cliente: String): String =
        MessageDigest.getInstance("SHA-256").digest("$canal:$cliente".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    fun lerConversa(json: String?): Conversa? = try {
        val o = JSONObject(json ?: return null)
        val a = o.optJSONArray("m") ?: JSONArray()
        Conversa(
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Msg(it.optBoolean("c"), it.optString("t"), it.optLong("q")) },
            o.optLong("p"), o.optString("menu").takeIf { it.isNotBlank() }, o.optLong("a"),
        )
    } catch (e: Exception) {
        null
    }

    fun gravarConversa(c: Conversa): String = JSONObject()
        .put("m", JSONArray().apply { c.mensagens.takeLast(12).forEach { put(JSONObject().put("c", it.cliente).put("t", it.texto.take(600)).put("q", it.quando)) } })
        .put("p", c.pausadoAte).put("menu", c.menu ?: "").put("a", c.atualizado).toString()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ atendimento
    private var cacheProdutos: Pair<Long, List<Loja.Produto>>? = null

    private fun produtos(ctx: Context): List<Loja.Produto> {
        cacheProdutos?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val lista = try {
            Loja(ctx).takeIf { it.conectada }?.produtos().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        cacheProdutos = System.currentTimeMillis() to lista
        return lista
    }

    /** Bloqueante (chamado pelo robô, fora da thread principal). Formato igual ao do site: mensagens + humano. */
    fun atender(ctx: Context, pacote: String, cliente: String, mensagem: String, teste: Boolean): Saida {
        Perfil.carregar(ctx)
        if (!Licenca.liberada(ctx)) return Saida(emptyList(), false)
        val canal = if (pacote.contains("instagram")) "instagram" else "whatsapp"
        val chave = codigo(canal, cliente)
        val p = prefs(ctx)
        val agora = System.currentTimeMillis()
        // limpa conversas antigas
        p.all.filter { (k, v) -> k.startsWith("c_") && (lerConversa(v as? String)?.atualizado ?: 0) < agora - GUARDAR_DIAS * 86_400_000L }
            .keys.takeIf { it.isNotEmpty() }?.let { velhas -> p.edit().apply { velhas.forEach { remove(it) } }.apply() }
        val conversa = lerConversa(p.getString("c_$chave", null))
        if (!teste && conversa != null && conversa.pausadoAte > agora) return Saida(emptyList(), false)

        val regras = if (Perfil.regrasConfiguradas) Perfil.regras.perguntasComId(Perfil.loja).associate { it.first to it.third } else emptyMap()
        val decisao = especial(mensagem, { n -> Loja(ctx).takeIf { it.conectada }?.pedidoPorNumero(n) }, Perfil.regras.prazoPostagemDias?.takeIf { it > 0 } ?: 2, Perfil.entregaDiscreta, Perfil.regras.aceitaPix)
            ?: decidir(mensagem, conversa, regras, Perfil.loja, Marca.nome, Perfil.site, agora, Perfil.regras.retiradaDisponivel, Perfil.entregaDiscreta, Perfil.ramoIntimo)
            ?: comIA(ctx, mensagem, conversa, canal)
        val nova = Conversa(
            (conversa?.mensagens.orEmpty() + Msg(true, mensagem, agora) + Msg(false, decisao.texto, agora)).takeLast(12),
            if (decisao.humano) agora + PAUSA_HUMANO_MS else 0L, decisao.menu, agora,
        )
        if (!teste) p.edit().putString("c_$chave", gravarConversa(nova)).apply()
        if (!teste) Resultado.anotarResposta(ctx, chave, decisao.humano)
        if (decisao.humano && !teste) {
            Diario.anotar(ctx, "robo", "Cliente do ${if (canal == "whatsapp") "WhatsApp" else "Instagram"} passada para a loja: \"${mensagem.take(120)}\"", "robô")
        }
        return Saida(listOf(decisao.texto), decisao.humano)
    }

    private fun comIA(ctx: Context, mensagem: String, conversa: Conversa?, canal: String): Decisao {
        val config = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val chaveGemini = GeminiRota.efetiva(ctx, Cofre.ler(config, "chave"))
        val modelo = config.getString("modelo", "gemini-flash-latest").orEmpty().ifBlank { "gemini-flash-latest" }
        val anterior = conversa?.mensagens?.lastOrNull { it.cliente }?.texto.orEmpty()
        val catalogo = ContextoDaLoja.catalogo("$anterior $mensagem", produtos(ctx), chaveGemini)
        val conferidas = try { ContextoDaLoja.textoDasReferencias(ContextoDaLoja.referencias(Memoria(ctx), mensagem, chaveGemini)) } catch (e: Exception) { "" }
        val historico = conversa?.mensagens?.takeLast(8)?.joinToString("\n") { (if (it.cliente) "Cliente: " else "${Marca.nome}: ") + it.texto }.orEmpty()
        val pedido = "Você é a ${Marca.nome}, atendente virtual da ${Perfil.loja} (${Perfil.ramo}). Está respondendo no " +
            (if (canal == "whatsapp") "WhatsApp" else "direct do Instagram") + " da loja. " + Perfil.injecao() + "\n" +
            "Escreva como uma pessoa simpática" + (if (Perfil.ramoIntimo) " e discreta" else "") + " escreve no celular: curto (1 a 3 frases), no máximo 1 emoji, sem markdown, sem palavras vulgares. " +
            "Use SOMENTE as regras acima e os produtos abaixo; não invente produtos, preços, prazos nem políticas. Quando indicar produto, mande o link dele (se tiver). " +
            (if (Plataformas.tipo(ctx) == "interno") "A loja não tem site: quando a cliente quiser comprar, confirme o produto e marque chamarHumano=true para a equipe fechar o pedido (pagamento e entrega). " else "") +
            ContextoDaLoja.AVISO_DADOS + " " +
            "Nunca peça CPF, endereço ou dados de cartão na conversa. Marque chamarHumano=true quando a cliente pedir para falar com alguém; " +
            "for troca, defeito, reclamação, pedido atrasado ou problema com pagamento; ou você não tiver a informação. Nesses casos, diga que vai chamar a equipe da loja.\n\n" +
            "Produtos (nome | preço | preço no Pix | estoque | link):\n${catalogo.ifBlank { "(catálogo indisponível agora)" }}\n\n" +
            (if (conferidas.isNotBlank()) "Regras e respostas já conferidas da loja para perguntas parecidas (use as mesmas informações e o mesmo tom):\n$conferidas\n\n" else "") +
            (if (historico.isNotBlank()) "Conversa até aqui:\n$historico\n\n" else "") +
            "Nova mensagem da cliente: $mensagem\n\nResponda só com JSON: {\"texto\":\"...\",\"chamarHumano\":false}"
        val json = try {
            Gemini.gerarJson(chaveGemini, modelo, pedido, temperatura = 0.4)
        } catch (e: Exception) {
            null
        }
        val o = json?.let { t ->
            try {
                JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
            } catch (e: Exception) {
                null
            }
        }
        val texto = o?.optString("texto")?.replace(Regex("[*`#]"), "")?.trim().orEmpty()
        if (texto.isBlank()) {
            return Decisao("Vou verificar isso com a equipe da loja e já te respondo, só um instante 💗", true, null)
        }
        var humano = o?.optBoolean("chamarHumano") == true
        var final = texto.take(700)
        if (humano && !normalizar(final).contains("equipe")) final += "\n\nVou chamar a equipe da loja para te responder pessoalmente, só um instante 💗"
        return Decisao(final, humano, null)
    }

    /** O JSON no formato do site ({"replies":[...],"humano":...}), para o robô ler do mesmo jeito. */
    fun json(s: Saida): String = JSONObject()
        .put("replies", JSONArray().apply { s.mensagens.forEach { put(JSONObject().put("message", it).put("delay", 3).put("delayMax", 8)) } })
        .put("humano", s.humano).toString()
}
