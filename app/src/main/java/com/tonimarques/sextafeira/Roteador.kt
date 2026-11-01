package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * O "cérebro executivo" da Vênus: quando as regras dela não reconhecem a frase, o Gemini só INTERPRETA
 * (recebe a frase, o contexto da conversa e a lista do que a Vênus sabe fazer) e devolve qual ação executar.
 * Quem executa é a Vênus, no celular: os dados da loja nunca vão para o Gemini.
 * Cada frase decifrada fica guardada; na próxima vez ela entende sozinha, até sem internet.
 */
object Roteador {

    data class Rota(val acao: String, val args: Map<String, Any>)

    /** O que a Vênus sabe fazer sozinha (nome, para que serve, parâmetros). */
    val ACOES: List<Triple<String, String, String>> = listOf(
        Triple("loja_resumo", "vendas e faturamento da loja", "periodo: hoje|ontem|semana|mes"),
        Triple("loja_pedidos", "pedidos pagos para separar", ""),
        Triple("loja_produto", "tudo sobre um produto: preço, Pix, promoção, estoque, custo e margem", "produto: nome"),
        Triple("loja_estoque", "estoque de um produto", "produto: nome"),
        Triple("loja_estoque_geral", "visão geral do estoque", ""),
        Triple("loja_acabando", "produtos esgotados ou com 2 unidades ou menos", ""),
        Triple("loja_reposicao", "itens vendidos no período para repor", "periodo: hoje|ontem|semana|mes"),
        Triple("loja_previsao", "em quantos dias o estoque acaba; sem produto = os que acabam logo", "produto: nome ou vazio"),
        Triple("loja_mais_vendidos", "ranking de mais vendidos", "periodo: hoje|ontem|semana|mes"),
        Triple("loja_comparar", "vendas deste mês comparadas ao mês passado", ""),
        Triple("loja_parados", "produtos sem venda há 30 dias", ""),
        Triple("loja_margem", "margem de lucro de um produto", "produto: nome"),
        Triple("loja_margens", "margem média e produtos com menos lucro", ""),
        Triple("loja_lucro", "lucro das vendas no período", "periodo: hoje|ontem|semana|mes"),
        Triple("loja_retiradas", "pedidos de retirada para agendar", ""),
        Triple("loja_agendar", "abrir o WhatsApp da cliente para agendar a retirada", "pedido: número"),
        Triple("loja_entregue", "marcar pedido como entregue", "pedido: número"),
        Triple("loja_status", "mudar a etapa do pedido", "pedido: número; status: paid|preparing|shipped; rastreio: código ou vazio"),
        Triple(
            "loja_alterar", "mudar produto (sempre pede confirmação)",
            "tipo: estoque_somar|estoque_tirar|estoque_definir|preco|custo|promocao|promocao_pct|fim_promocao|ativar|desativar; produto: nome; " +
                "valor: inteiro (unidades para estoque; centavos para preco, custo e promocao, ex. 29,90 = 2990; porcentagem para promocao_pct)",
        ),
        Triple("loja_cadastrar", "cadastrar produto novo", "nome; preco: centavos; estoque: unidades (opcionais)"),
        Triple("programar_post", "programar post ou story no Instagram", "tipo: feed|story; produto: nome (opcional)"),
        Triple("listar_posts", "ver os posts programados", ""),
        Triple("responder_cliente", "escrever resposta para mensagem de cliente", ""),
        Triple("ler_nota", "ler nota ou print de compra de mercadoria", ""),
        Triple("editar_fundo", "trocar o fundo de uma foto de produto", ""),
        Triple("abrir_arquivo", "abrir e ler um arquivo (PDF, Word, Excel, áudio)", ""),
        Triple("criar_lembrete", "criar lembrete", "quando: AAAA-MM-DDTHH:MM; texto"),
        Triple("listar_lembretes", "ver lembretes", ""),
        Triple("cancelar_lembrete", "cancelar lembrete", "trecho: palavra do lembrete ou vazio"),
        Triple("rotina_bom_dia", "resumo da manhã (clima, lembretes, loja)", ""),
        Triple("caixa_lancar", "lançar entrada ou despesa no caixa da loja", "tipo: entrada|saida; valor: centavos; descricao; categoria"),
        Triple("caixa_resultado", "resultado financeiro do mês (vendas, custos, despesas, lucro)", "periodo: mes|mes_passado|semana"),
        Triple("caixa_despesas", "lista de despesas", "periodo: mes|mes_passado|semana"),
        Triple("caixa_equilibrio", "ponto de equilíbrio: quanto precisa vender para empatar", ""),
        Triple("caixa_previsao", "previsão de faturamento e despesas do próximo mês", ""),
        Triple("caixa_grafico", "gráfico de vendas e despesas", ""),
        Triple("caixa_relatorio", "relatório financeiro em PDF", "periodo: mes|mes_passado|semana"),
        Triple("buscar_conversa", "voltar a uma conversa antiga sobre um assunto (lembra do que falamos sobre…)", "assunto: só o tema, em poucas palavras"),
        Triple("loja_curva_abc", "curva ABC: quais produtos sustentam o faturamento", ""),
        Triple("loja_compras", "o que comprar do fornecedor agora (ponto de pedido)", "prazo: dias que o fornecedor leva para entregar, se dito"),
        Triple("gestao_prazo", "anotar quantos dias o fornecedor leva para entregar", "dias"),
        Triple("loja_recompra", "clientes que sumiram e podem ser chamados de volta", ""),
        Triple("loja_chamar_cliente", "abrir o WhatsApp de uma cliente com mensagem de recompra", "nome"),
        Triple("loja_datas", "próximas datas comerciais (Black Friday, Dia dos Namorados, Natal…)", "dias: quantos dias para frente"),
        Triple("loja_promo_parados", "colocar em promoção todos os produtos parados", "pct: desconto em %; dias: sem venda há quantos dias"),
        Triple("caixa_apagar", "apagar o último lançamento do caixa", ""),
        Triple("caixa_fixa", "cadastrar despesa fixa mensal (aluguel, internet, sistema)", "descricao; valor: centavos; dia: dia do vencimento 1-28"),
        Triple("caixa_remover_fixa", "tirar uma despesa fixa", "trecho: nome da despesa"),
        Triple("financeiro_calculo", "conta de matemática financeira",
            "tipo: juros_compostos (a=capital em reais, b=taxa % ao mês, c=meses, taxa_anual=true se a taxa for ao ano) | parcela (a=valor em reais, b=taxa % ao mês, c=número de parcelas) | desconto_venda (a=desconto %, b=margem % se dita) | markup (a=margem % desejada, b=custo em reais)"),
    )
    private val NOMES = ACOES.map { it.first }.toSet()

    // ------------------------------------------------------------------ memória das frases decifradas
    private const val PREFS = "rotas_aprendidas"
    private const val LIMITE = 600

    fun chave(frase: String): String =
        ComandosLocais.normalizar(frase).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun lembrada(ctx: Context, frase: String): Rota? {
        val bruto = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(chave(frase), null) ?: return null
        return try {
            lerRota(JSONObject(bruto))
        } catch (e: Exception) {
            null
        }
    }

    fun lembrar(ctx: Context, frase: String, rota: Rota) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // não guarda o que depende de data ("amanhã às 10") nem frases longas demais
        if (rota.acao == "criar_lembrete" || chave(frase).length > 120) return
        val ed = prefs.edit()
        if (prefs.all.size >= LIMITE) prefs.all.keys.take(50).forEach { ed.remove(it) }
        ed.putString(chave(frase), JSONObject().put("acao", rota.acao).put("args", JSONObject(rota.args)).toString()).apply()
    }

    fun esquecerTudo(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    // ------------------------------------------------------------------ pergunta ao Gemini
    fun montarPedido(frase: String, contexto: String): String =
        "Você é o interpretador de comandos da Vênus, assistente de voz do Toni, dono da loja online Desejo de Intimidade. " +
            "Escolha qual ação da lista atende a frase dele, ou \"conversa\" se for pergunta geral, papo, conhecimento ou algo fora da lista. " +
            "Use o contexto para entender \"ele\", \"dela\", \"e o do…\", \"o mesmo\". Datas relativas em relação a ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", java.util.Locale("pt", "BR")).format(java.util.Date())}.\n" +
            "Ações:\n" + ACOES.joinToString("\n") { "- ${it.first}: ${it.second}" + if (it.third.isNotBlank()) " | ${it.third}" else "" } +
            "\n\nContexto da conversa: ${contexto.ifBlank { "(início)" }}\n" +
            "Frase: \"$frase\"\n" +
            "Responda só com JSON: {\"acao\":\"nome\",\"args\":{...}}. Nomes de produto como o usuário falou. Não invente valores que ele não disse."

    /** Pergunta ao Gemini qual ação a frase quer. Null se não deu (sem internet, sem chave) ou se é conversa. */
    fun rotear(chave: String, modelo: String, frase: String, contexto: String): Rota? {
        if (chave.isBlank()) return null
        val corpo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", montarPedido(frase, contexto))))))
            .put("generationConfig", JSONObject().put("temperature", 0.0).put("responseMimeType", "application/json"))
            .toString().toByteArray()
        for (m in listOf("gemini-flash-lite-latest", modelo.removePrefix("models/"), "gemini-2.5-flash").filter { it.isNotBlank() }.distinct()) {
            var c: HttpURLConnection? = null
            try {
                c = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 6_000
                c.readTimeout = 12_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("Connection", "close")
                c.setRequestProperty("x-goog-api-key", chave)
                c.outputStream.use { it.write(corpo) }
                if (c.responseCode !in 200..299) continue
                val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val partes = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                val texto = (0 until partes.length()).joinToString("") { partes.optJSONObject(it)?.optString("text").orEmpty() }
                return interpretar(texto)
            } catch (e: Exception) {
                continue
            } finally {
                c?.disconnect()
            }
        }
        return null
    }

    /** Lê a resposta do Gemini e confere se a ação existe e tem o que precisa. */
    fun interpretar(texto: String): Rota? {
        val json = try {
            JSONObject("{" + texto.substringAfter("{").substringBeforeLast("}") + "}")
        } catch (e: Exception) {
            return null
        }
        return lerRota(json)
    }

    private fun lerRota(json: JSONObject): Rota? {
        val acao = json.optString("acao").trim()
        if (acao !in NOMES) return null
        val a = json.optJSONObject("args") ?: JSONObject()
        val args = mutableMapOf<String, Any>()
        for (k in a.keys()) {
            val v = a.opt(k)
            if (v == null || v == JSONObject.NULL || (v is String && v.isBlank())) continue
            args[k] = when (v) {
                is Double -> if (v % 1.0 == 0.0) v.toInt() else v
                is Long -> v.toInt()
                else -> v
            }
        }
        // números que vieram como texto ("15", "29,90")
        for (k in listOf("pct", "dias", "prazo")) {
            val v = args[k]
            if (v is String) v.filter { it.isDigit() }.toIntOrNull()?.let { args[k] = it } ?: args.remove(k)
            if (v is Double) args[k] = v.toInt()
        }
        for (k in listOf("pedido", "valor", "preco", "estoque")) {
            val v = args[k]
            if (v is String) {
                val n = if (k == "pedido" || k == "estoque" || (k == "valor" && args["tipo"]?.toString()?.startsWith("estoque") == true))
                    v.filter { it.isDigit() }.toIntOrNull() else ComandosLocais.centavos(v.replace(Regex("[^0-9,.]"), ""))
                if (n != null) args[k] = n else args.remove(k)
            }
        }
        if (acao == "criar_lembrete") {
            val q = args["quando"]?.toString().orEmpty()
            val t = try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(q)?.time
            } catch (e: Exception) {
                null
            } ?: return null
            if (t <= System.currentTimeMillis()) return null
            args["quando"] = t
            if (args["texto"] == null) args["texto"] = "Lembrete"
        }
        val obrigatorios = mapOf(
            "loja_estoque" to listOf("produto"), "loja_produto" to listOf("produto"), "loja_margem" to listOf("produto"),
            "loja_agendar" to listOf("pedido"), "loja_entregue" to listOf("pedido"), "loja_status" to listOf("pedido", "status"),
            "loja_alterar" to listOf("tipo", "produto"), "caixa_lancar" to listOf("tipo", "valor"),
            "caixa_fixa" to listOf("descricao", "valor"), "gestao_prazo" to listOf("dias"), "buscar_conversa" to listOf("assunto"), "loja_chamar_cliente" to listOf("nome"), "caixa_remover_fixa" to listOf("trecho"), "financeiro_calculo" to listOf("tipo", "a"),
        )
        for (k in listOf("a", "b", "c")) {
            val v = args[k]
            if (v is String) ComandosLocais.num(v.replace(Regex("[^0-9,.]"), "")).takeIf { it != 0.0 }?.let { args[k] = it } ?: args.remove(k)
        }
        if (acao == "financeiro_calculo" && args["tipo"] !in setOf("juros_compostos", "parcela", "desconto_venda", "markup")) return null
        if (obrigatorios[acao].orEmpty().any { it !in args }) return null
        if (acao == "loja_alterar" && args["tipo"] !in setOf("estoque_somar", "estoque_tirar", "estoque_definir", "preco", "custo", "promocao", "promocao_pct", "fim_promocao", "ativar", "desativar")) return null
        if (acao == "loja_status" && args["status"] !in setOf("paid", "preparing", "shipped")) return null
        return Rota(acao, args)
    }
}

/**
 * Memória curta da conversa: o último produto, pedido e ação. Assim "e o preço dele?", "coloca ele em promoção"
 * e "e o do vibrador?" funcionam. Vale por 15 minutos.
 */
object Contexto {
    var produto: String? = null
        private set
    var pedido: Int? = null
        private set
    var ultimaAcao: Pair<String, Map<String, Any>>? = null
        private set
    private var quando = 0L

    private fun valido() = System.currentTimeMillis() - quando < 15 * 60_000L

    /** Anota o que acabou de ser feito. */
    fun registrar(acao: String, args: Map<String, Any>) {
        quando = System.currentTimeMillis()
        args["produto"]?.toString()?.takeIf { it.isNotBlank() }?.let { produto = it }
        (args["pedido"] as? Int)?.let { pedido = it }
        ultimaAcao = acao to args
    }

    fun limpar() {
        produto = null
        pedido = null
        ultimaAcao = null
    }

    /** Texto curto para o roteador. */
    fun descrever(): String {
        if (!valido()) return ""
        val partes = mutableListOf<String>()
        produto?.let { partes += "último produto citado: $it" }
        pedido?.let { partes += "último pedido citado: $it" }
        ultimaAcao?.let { (a, args) -> partes += "última ação: $a ${JSONObject(args.filterKeys { it != "quando" })}" }
        return partes.joinToString("; ")
    }

    private val PRONOMES = listOf(
        Regex("\\b(?:desse|deste|daquele) produto\\b", RegexOption.IGNORE_CASE) to "do",
        Regex("\\b(?:esse|este|aquele) produto\\b", RegexOption.IGNORE_CASE) to "o",
        Regex("\\b(?:dele|dela)\\b", RegexOption.IGNORE_CASE) to "do",
        Regex("\\b(?:nele|nela)\\b", RegexOption.IGNORE_CASE) to "no",
        Regex("\\b(?:ele|ela)\\b", RegexOption.IGNORE_CASE) to "o",
        Regex("\\bdo mesmo\\b", RegexOption.IGNORE_CASE) to "do",
        Regex("\\bo mesmo\\b", RegexOption.IGNORE_CASE) to "o",
    )

    /** Troca "ele", "dele", "esse produto" pelo último produto. Devolve null se não havia o que trocar. */
    fun comProduto(frase: String, produtoAtual: String? = if (valido()) produto else null): String? {
        val p = produtoAtual ?: return null
        var saida = frase
        var trocou = false
        for ((r, artigo) in PRONOMES) {
            if (r.containsMatchIn(saida)) {
                saida = r.replace(saida, "$artigo $p")
                trocou = true
            }
        }
        return if (trocou) saida.replace(Regex("\\b(o|do|no) (o|do|no) "), "$1 ") else null
    }

    /** Troca "esse pedido", "dele" (pedido) pelo número do último pedido. */
    fun comPedido(frase: String, pedidoAtual: Int? = if (valido()) pedido else null): String? {
        val n = pedidoAtual ?: return null
        val r = Regex("\\b(?:esse|este|o mesmo|aquele) pedido\\b", RegexOption.IGNORE_CASE)
        return if (r.containsMatchIn(frase)) r.replace(frase, "o pedido $n") else null
    }
}
