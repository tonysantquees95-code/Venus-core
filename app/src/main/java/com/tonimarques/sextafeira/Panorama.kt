package com.tonimarques.sextafeira

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * O retrato da loja em números, para a IA basear decisões de negócio nos dados reais
 * ("vale a pena investir em lingerie?", "qual a melhor estratégia para a Black Friday?").
 *
 * Só vão totais e médias: nenhum nome, telefone, e-mail ou endereço de cliente.
 */
object Panorama {

    private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")
    private fun d(c: Long) = Loja.dinheiro(c.toInt())
    private fun norm(s: String) = ComandosLocais.normalizar(s.trim())

    /** A pergunta é sobre o negócio (vale mandar os números da loja junto)? */
    fun sobreNegocio(frase: String): Boolean = Regex(
        "\\b(?:loja|vend\\w*|investi\\w*|estoque|produtos?|lingeries?|conjuntos?|vibrador\\w*|ge(?:l|is)|lubrificantes?|cosmeticos?|" +
            "precos?|margem|margens|lucr\\w*|promoc\\w*|desconto|black friday|natal|namorados|faturamento|faturar|clientes?|" +
            "mercadorias?|fornecedor\\w*|marketing|anuncios?|trafego|instagram|kits?|categorias?|catalogo|ticket|frete|negocio|empresa|caixa|despesas?)\\b"
    ).containsMatchIn(norm(frase))

    fun montar(
        pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, lancamentos: List<Financas.Lancamento>, fixas: List<Financas.Fixa>,
        agora: Calendar,
    ): String {
        val linhas = mutableListOf<String>()
        val t = agora.timeInMillis
        val dia = 86_400_000L
        val pagos = pedidos.filter { it.status in PAGOS }
        val porId = produtos.associateBy { it.id }
        val porNome = produtos.associateBy { norm(it.nome) }
        fun produtoDe(i: Loja.Item) = i.produtoId?.let { porId[it] } ?: porNome[norm(i.nome)]

        // vendas por mês
        val hist = Financas.historico(pedidos, produtos, lancamentos, fixas, 4, agora)
        linhas += "Vendas por mês (site + vendas por fora): " + hist.joinToString("; ") { (mes, r) ->
            "$mes ${d(r.receita)} em ${r.pedidos} pedidos" + (if (r.pedidos > 0) ", ticket médio ${d(r.vendasSite / r.pedidos)}" else "")
        } + ". O mês atual está em andamento (${(Financas.fracaoDoMes(agora) * 100).roundToInt()}% do mês passou)."
        hist.lastOrNull()?.second?.let { r ->
            linhas += "Resultado do mês até agora: receita ${d(r.receita)}, custo das mercadorias vendidas ${d(r.custoMercadoria)}, " +
                "despesas ${d(r.despesas + r.despesasFixas)}, lucro ${d(r.lucro)}" +
                (if (r.itensSemCusto > 0) " (${r.itensSemCusto} itens vendidos sem custo cadastrado, lucro real menor)" else "") + "."
        }
        val fixasMes = fixas.sumOf { it.valorCentavos.toLong() }
        if (fixasMes > 0) linhas += "Despesas fixas por mês: ${d(fixasMes)} (" + fixas.joinToString(", ") { it.descricao } + ")."

        // catálogo e margens
        val ativos = produtos.filter { it.ativo }
        val comCusto = ativos.filter { it.custoCentavos != null && (it.precoCentavos ?: 0) > 0 }
        val margem = comCusto.map { (it.precoCentavos!! - it.custoCentavos!!).toDouble() / it.precoCentavos }.average().takeIf { comCusto.isNotEmpty() }
        linhas += "Catálogo: ${ativos.size} produtos à venda" +
            (if (margem != null) ", margem média de ${(margem * 100).roundToInt()}% sobre o preço (${comCusto.size} com custo cadastrado)" else ", custos ainda não cadastrados") + "."
        val estoqueUn = ativos.sumOf { maxOf(it.estoque ?: 0, 0) }
        val estoqueCusto = ativos.sumOf { maxOf(it.estoque ?: 0, 0).toLong() * (it.custoCentavos ?: 0) }
        linhas += "Estoque: $estoqueUn unidades" + (if (estoqueCusto > 0) ", uns ${d(estoqueCusto)} a preço de custo" else "") + "."

        // por categoria (90 dias)
        val desde90 = t - 90 * dia
        val itens90 = pagos.filter { (it.pago ?: it.criado) >= desde90 }.flatMap { it.itens }
        val porCategoria = itens90.groupBy { i -> produtoDe(i)?.bruto?.optString("category").orEmpty().ifBlank { "outros" } }
            .mapValues { (_, v) -> v.sumOf { it.precoCentavos.toLong() * it.quantidade } }
        if (porCategoria.isNotEmpty()) {
            linhas += "Faturamento por categoria em 90 dias: " + porCategoria.entries.sortedByDescending { it.value }
                .joinToString("; ") { "${it.key.replace('_', ' ')} ${d(it.value)}" } + "."
        }

        // produtos que mais vendem, com margem e giro
        val ritmo = Loja.ritmoDeVendas(pedidos, produtos, agora, 45)
        val top = itens90.groupBy { i -> produtoDe(i)?.id ?: -norm(i.nome).hashCode() }
            .map { (_, v) -> Triple(v.first(), v.sumOf { it.quantidade }, v.sumOf { it.precoCentavos.toLong() * it.quantidade }) }
            .sortedByDescending { it.third }.take(8)
        if (top.isNotEmpty()) {
            linhas += "Mais vendidos em 90 dias: " + top.joinToString("; ") { (item, qtd, receita) ->
                val p = produtoDe(item)
                val partes = mutableListOf("${item.nome.trim()}: $qtd un., ${d(receita)}")
                val preco = p?.precoCentavos
                val custo = p?.custoCentavos
                if (preco != null && custo != null && preco > 0) partes += "margem ${((preco - custo) * 100.0 / preco).roundToInt()}%"
                val r = p?.let { ritmo[it.id] } ?: 0.0
                if (p?.estoque != null) partes += if (r > 0) "estoque ${p.estoque} (dura uns ${(p.estoque / r).toInt()} dias)" else "estoque ${p.estoque}"
                partes.joinToString(", ")
            } + "."
        }

        // parados
        val vendidos30 = pagos.filter { (it.pago ?: it.criado) >= t - 30 * dia }.flatMap { it.itens }.map { norm(it.nome) }.toSet()
        val parados = ativos.filter { (it.estoque ?: 1) > 0 && norm(it.nome) !in vendidos30 }
        if (parados.isNotEmpty()) linhas += "Sem venda há 30 dias (${parados.size}): " + parados.take(10).joinToString(", ") { "${it.nome} (${it.estoque ?: "?"} un.)" } + "."

        // clientes (só contagens)
        val porCliente = pagos.groupBy { it.telefone.replace(Regex("\\D"), "").takeLast(11).ifBlank { norm(it.cliente) } }
        if (porCliente.isNotEmpty()) {
            val voltaram = porCliente.values.count { it.size > 1 }
            linhas += "Clientes: ${porCliente.size} compradores; ${voltaram} compraram mais de uma vez (${(voltaram * 100.0 / porCliente.size).roundToInt()}% de recompra)."
        }
        val retirada = pagos.count { it.entrega == "pickup" }
        if (pagos.isNotEmpty()) linhas += "Entrega: ${(retirada * 100.0 / pagos.size).roundToInt()}% dos pedidos são retirada em ${Perfil.cidade}, o resto vai pelos Correios. Frete e taxa do cartão são pagos pelo cliente; Pix sem taxa."

        // datas
        val datas = Gestao.proximasDatas(agora, 60)
        if (datas.isNotEmpty()) linhas += "Próximas datas comerciais: " + datas.joinToString(", ") { "${it.first.nome} em ${it.second} dias" } + "."
        return linhas.joinToString("\n").take(3500)
    }

    /** O bloco que vai junto com a pergunta. */
    fun anexar(pergunta: String, panorama: String): String =
        "$pergunta\n\n[Dados reais da loja ${Perfil.loja} (só totais, sem dados de clientes). " +
            "Baseie números, riscos e recomendação nestes dados em vez de médias de mercado; se faltar algum dado, diga qual.]\n$panorama"
}
