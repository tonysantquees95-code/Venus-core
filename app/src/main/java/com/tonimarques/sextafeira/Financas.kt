package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Finanças da loja: livro caixa (entradas por fora e despesas, por voz), despesas fixas do mês,
 * resultado do mês (vendas do site − custo das mercadorias − despesas), fluxo de caixa, ponto de equilíbrio,
 * previsão do próximo mês e matemática financeira.
 * Por enquanto os lançamentos ficam guardados no celular (depois vão para o painel do site, para a Natália ver também).
 */
object Financas {

    data class Lancamento(
        val id: Int, val data: Long, val tipo: String, val valorCentavos: Int, val descricao: String, val categoria: String,
    ) {
        val entrada get() = tipo == "entrada"
    }

    data class Fixa(val id: Int, val descricao: String, val valorCentavos: Int, val categoria: String, val dia: Int, val desde: Long)

    // ================================================================== categorias
    val CATEGORIAS = listOf(
        "mercadoria", "embalagem", "frete e entrega", "internet e telefone", "aluguel", "contas da casa", "marketing",
        "sistemas e apps", "taxas e impostos", "transporte", "material", "pró-labore", "outros",
    )

    /** Adivinha a categoria pela descrição. */
    fun categoria(descricao: String): String {
        val d = ComandosLocais.normalizar(descricao)
        return when {
            Regex("\\b(mercadoria|produto|produtos|fornecedor|estoque|reposicao|gel|geis|vibrador|lingerie|kit)").containsMatchIn(d) -> "mercadoria"
            Regex("\\b(embalage|caixa|caixinha|sacola|fita|papel de seda|etiqueta|plastico bolha)").containsMatchIn(d) -> "embalagem"
            Regex("\\b(frete|correio|sedex|pac|motoboy|entrega|uber flash|loggi)").containsMatchIn(d) -> "frete e entrega"
            Regex("\\b(internet|celular|telefone|chip|plano|dados moveis)").containsMatchIn(d) -> "internet e telefone"
            Regex("\\b(aluguel|condominio)").containsMatchIn(d) -> "aluguel"
            Regex("\\b(luz|energia|agua|gas)").containsMatchIn(d) -> "contas da casa"
            Regex("\\b(anuncio|propaganda|impulsion|instagram|facebook|meta|google ads|marketing|panfleto|brinde)").containsMatchIn(d) -> "marketing"
            Regex("\\b(floot|site|dominio|app|aplicativo|assinatura|sistema|software|canva|chatgpt|gemini)").containsMatchIn(d) -> "sistemas e apps"
            Regex("\\b(taxa|imposto|das|tarifa|mercado pago|juros|multa|banco)").containsMatchIn(d) -> "taxas e impostos"
            Regex("\\b(gasolina|combustivel|uber|onibus|passagem|estacionamento|pedagio)").containsMatchIn(d) -> "transporte"
            Regex("\\b(pro.?labore|retirada minha|meu salario|salario)").containsMatchIn(d) -> "pró-labore"
            Regex("\\b(impressora|tinta|papel|caneta|material)").containsMatchIn(d) -> "material"
            else -> "outros"
        }
    }

    // ================================================================== guardar
    private const val PREFS = "financas"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lancamentos(ctx: Context): List<Lancamento> = lerLancamentos(prefs(ctx).getString("lancamentos", "[]").orEmpty())

    fun fixas(ctx: Context): List<Fixa> = lerFixas(prefs(ctx).getString("fixas", "[]").orEmpty())

    fun lerLancamentos(json: String): List<Lancamento> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Lancamento(it.optInt("id"), it.optLong("data"), it.optString("tipo"), it.optInt("valor"), it.optString("descricao"), it.optString("categoria"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun lerFixas(json: String): List<Fixa> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Fixa(it.optInt("id"), it.optString("descricao"), it.optInt("valor"), it.optString("categoria"), it.optInt("dia", 1), it.optLong("desde"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun lancar(ctx: Context, tipo: String, valor: Int, descricao: String, categoria: String? = null, data: Long = System.currentTimeMillis()): Lancamento {
        val lista = lancamentos(ctx).toMutableList()
        val l = Lancamento((lista.maxOfOrNull { it.id } ?: 0) + 1, data, tipo, valor, descricao.trim().replaceFirstChar { it.uppercase() },
            categoria ?: if (tipo == "entrada") "vendas por fora" else categoria(descricao))
        lista += l
        val a = JSONArray()
        for (x in lista) a.put(JSONObject().put("id", x.id).put("data", x.data).put("tipo", x.tipo).put("valor", x.valorCentavos).put("descricao", x.descricao).put("categoria", x.categoria))
        prefs(ctx).edit().putString("lancamentos", a.toString()).apply()
        return l
    }

    fun apagarUltimo(ctx: Context): Lancamento? {
        val lista = lancamentos(ctx)
        val ultimo = lista.maxByOrNull { it.id } ?: return null
        val a = JSONArray()
        for (x in lista - ultimo) a.put(JSONObject().put("id", x.id).put("data", x.data).put("tipo", x.tipo).put("valor", x.valorCentavos).put("descricao", x.descricao).put("categoria", x.categoria))
        prefs(ctx).edit().putString("lancamentos", a.toString()).apply()
        return ultimo
    }

    fun salvarFixa(ctx: Context, descricao: String, valor: Int, dia: Int): Fixa {
        val lista = fixas(ctx).filter { ComandosLocais.normalizar(it.descricao) != ComandosLocais.normalizar(descricao.trim()) }.toMutableList()
        val f = Fixa((fixas(ctx).maxOfOrNull { it.id } ?: 0) + 1, descricao.trim().replaceFirstChar { it.uppercase() }, valor, categoria(descricao), dia.coerceIn(1, 28), System.currentTimeMillis())
        lista += f
        gravarFixas(ctx, lista)
        return f
    }

    fun removerFixa(ctx: Context, trecho: String): Int {
        val chave = ComandosLocais.normalizar(trecho)
        val (sai, fica) = fixas(ctx).partition { ComandosLocais.normalizar(it.descricao).contains(chave) }
        gravarFixas(ctx, fica)
        return sai.size
    }

    private fun gravarFixas(ctx: Context, lista: List<Fixa>) {
        val a = JSONArray()
        for (x in lista) a.put(JSONObject().put("id", x.id).put("descricao", x.descricao).put("valor", x.valorCentavos).put("categoria", x.categoria).put("dia", x.dia).put("desde", x.desde))
        prefs(ctx).edit().putString("fixas", a.toString()).apply()
    }

    // ================================================================== períodos
    data class Periodo(val ini: Long, val fim: Long, val nome: String)

    fun periodo(nome: String, agora: Calendar): Periodo {
        fun zero(c: Calendar) = c.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val inicioMes = zero((agora.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) })
        return when (nome) {
            "mes_passado" -> {
                val ini = (inicioMes.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                Periodo(ini.timeInMillis, inicioMes.timeInMillis, "no mês passado")
            }
            "semana" -> Periodo(zero(agora.clone() as Calendar).timeInMillis - 6 * 86_400_000L, agora.timeInMillis + 1, "nos últimos 7 dias")
            else -> Periodo(inicioMes.timeInMillis, agora.timeInMillis + 1, "neste mês")
        }
    }

    /** Quanto das despesas fixas cai no período (cada fixa conta uma vez por mês, a partir do mês em que foi cadastrada). */
    fun fixasNoPeriodo(fixas: List<Fixa>, p: Periodo): List<Pair<Fixa, Long>> {
        val saida = mutableListOf<Pair<Fixa, Long>>()
        for (f in fixas) {
            val c = Calendar.getInstance().apply { timeInMillis = p.ini; set(Calendar.DAY_OF_MONTH, 1) }
            while (c.timeInMillis < p.fim) {
                val venc = (c.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, minOf(f.dia, getActualMaximum(Calendar.DAY_OF_MONTH)))
                    set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
                }.timeInMillis
                val inicioDoMesDaFixa = Calendar.getInstance().apply { timeInMillis = f.desde; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
                if (venc in p.ini until p.fim && venc >= inicioDoMesDaFixa) saida += f to venc
                c.add(Calendar.MONTH, 1)
            }
        }
        return saida
    }

    // ================================================================== resultado
    data class Resultado(
        val vendasSite: Long, val pedidos: Int, val entradasPorFora: Long, val custoMercadoria: Long, val itensSemCusto: Int,
        val despesas: Long, val despesasFixas: Long, val comprasMercadoria: Long, val fretesCobrados: Long,
        val porCategoria: Map<String, Long>,
    ) {
        val receita get() = vendasSite + entradasPorFora
        val lucro get() = receita - custoMercadoria - despesas - despesasFixas
        val fluxoDeCaixa get() = receita - despesas - despesasFixas - comprasMercadoria
        val margemContribuicao get() = if (receita > 0) (receita - custoMercadoria).toDouble() / receita else 0.0
    }

    val STATUS_PAGOS = setOf("paid", "preparing", "shipped", "delivered")

    fun pagosNoPeriodo(pedidos: List<Loja.Pedido>, p: Periodo) = pedidos.filter { it.status in STATUS_PAGOS && (it.pago ?: it.criado) in p.ini until p.fim }

    /** Produtos mais vendidos no período (nome, unidades). */
    fun maisVendidos(pedidos: List<Loja.Pedido>, p: Periodo): List<Pair<String, Int>> =
        pagosNoPeriodo(pedidos, p).flatMap { it.itens }.groupBy { it.nome.trim() }
            .map { (nome, itens) -> nome to itens.sumOf { it.quantidade } }.sortedByDescending { it.second }

    /** Junta as vendas do site com o caixa. Compras de mercadoria entram no fluxo de caixa, mas no lucro entra o custo do que foi vendido. */
    fun resultado(
        pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, lancamentos: List<Lancamento>, fixas: List<Fixa>, p: Periodo,
    ): Resultado {
        val pagos = pagosNoPeriodo(pedidos, p)
        val porId = produtos.associateBy { it.id }
        val porNome = produtos.associateBy { ComandosLocais.normalizar(it.nome.trim()) }
        var vendas = 0L
        var cmv = 0L
        var semCusto = 0
        for (o in pagos) {
            val bruto = o.itens.sumOf { it.precoCentavos.toLong() * it.quantidade }
            vendas += bruto - o.descontoCentavos
            for (i in o.itens) {
                val prod = i.produtoId?.let { porId[it] } ?: porNome[ComandosLocais.normalizar(i.nome.trim())]
                val c = prod?.custoCentavos
                if (c == null) semCusto += i.quantidade else cmv += c.toLong() * i.quantidade
            }
        }
        val doPeriodo = lancamentos.filter { it.data in p.ini until p.fim }
        val entradas = doPeriodo.filter { it.entrada }.sumOf { it.valorCentavos.toLong() }
        val saidas = doPeriodo.filter { !it.entrada }
        val compras = saidas.filter { it.categoria == "mercadoria" }.sumOf { it.valorCentavos.toLong() }
        val despesas = saidas.filter { it.categoria != "mercadoria" }.sumOf { it.valorCentavos.toLong() }
        val fixasP = fixasNoPeriodo(fixas, p)
        val categorias = (saidas.filter { it.categoria != "mercadoria" }.map { it.categoria to it.valorCentavos.toLong() } +
            fixasP.map { it.first.categoria to it.first.valorCentavos.toLong() })
            .groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
        return Resultado(
            vendas, pagos.size, entradas, cmv, semCusto, despesas, fixasP.sumOf { it.first.valorCentavos.toLong() }, compras,
            pagos.sumOf { it.freteCentavos.toLong() }, categorias,
        )
    }

    private fun r(c: Long) = Loja.dinheiro(c.toInt())
    private fun pct(a: Long, b: Long) = if (b == 0L) 0 else ((a * 100.0) / b).roundToLong().toInt()

    fun textoResultado(res: Resultado, p: Periodo): String {
        if (res.receita == 0L && res.despesas == 0L && res.despesasFixas == 0L && res.comprasMercadoria == 0L) {
            return "Ainda não tem vendas nem despesas lançadas ${p.nome}."
        }
        var t = "Resultado ${p.nome}: vendas de ${r(res.receita)}" +
            (if (res.entradasPorFora > 0) " (${r(res.vendasSite)} no site e ${r(res.entradasPorFora)} por fora)" else "") +
            ", custo das mercadorias vendidas ${r(res.custoMercadoria)}, despesas ${r(res.despesas + res.despesasFixas)}" +
            (if (res.despesasFixas > 0) " (${r(res.despesasFixas)} fixas)" else "") + ". "
        t += if (res.lucro >= 0) "Lucro de ${r(res.lucro)}" else "Prejuízo de ${r(-res.lucro)}"
        if (res.receita > 0) t += ", ${pct(res.lucro, res.receita)}% das vendas"
        t += "."
        if (res.comprasMercadoria > 0) t += " No caixa: ${if (res.fluxoDeCaixa >= 0) "sobraram" else "faltaram"} ${r(kotlin.math.abs(res.fluxoDeCaixa))}, contando ${r(res.comprasMercadoria)} de compras de mercadoria."
        if (res.itensSemCusto > 0) t += " Atenção: ${res.itensSemCusto} ${if (res.itensSemCusto == 1) "item vendido está" else "itens vendidos estão"} sem custo cadastrado, então o lucro real é menor."
        if (res.fretesCobrados > 0) t += " Frete cobrado dos clientes: ${r(res.fretesCobrados)}, repassado ao envio."
        return t
    }

    fun textoDespesas(lancamentos: List<Lancamento>, fixas: List<Fixa>, p: Periodo): String {
        val saidas = lancamentos.filter { !it.entrada && it.data in p.ini until p.fim }
        val fixasP = fixasNoPeriodo(fixas, p)
        if (saidas.isEmpty() && fixasP.isEmpty()) return "Nenhuma despesa lançada ${p.nome}. Diga, por exemplo: paguei 120 de internet."
        val total = saidas.sumOf { it.valorCentavos.toLong() } + fixasP.sumOf { it.first.valorCentavos.toLong() }
        val porCat = (saidas.map { it.categoria to it.valorCentavos.toLong() } + fixasP.map { it.first.categoria to it.first.valorCentavos.toLong() })
            .groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }.entries.sortedByDescending { it.value }
        return "Despesas ${p.nome}: ${r(total)}.\n" + porCat.joinToString("\n") { "• ${it.key}: ${r(it.value)}" } +
            (if (fixasP.isNotEmpty()) "\nFixas incluídas: " + fixasP.joinToString(", ") { "${it.first.descricao} (${r(it.first.valorCentavos.toLong())})" } else "")
    }

    /** Ponto de equilíbrio: despesas do mês ÷ margem de contribuição (quanto sobra de cada real vendido depois do custo). */
    fun textoEquilibrio(fixas: List<Fixa>, despesasMediasVariaveis: Long, margem: Double): String {
        val mensais = fixas.sumOf { it.valorCentavos.toLong() } + despesasMediasVariaveis
        if (mensais == 0L) return "Para calcular o ponto de equilíbrio, cadastre as despesas fixas, por exemplo: despesa fixa de internet de 120 todo dia 10."
        if (margem <= 0.0) return "Para calcular, preciso do custo dos produtos (para saber a margem). Cadastre os custos ou mande a nota de compra."
        val equilibrio = (mensais / margem).roundToLong()
        return "Suas despesas giram em torno de ${r(mensais)} por mês e, de cada R$ 100 vendidos, sobram uns R$ ${(margem * 100).roundToLong()} depois do custo das mercadorias. " +
            "Então você precisa vender cerca de ${r(equilibrio)} por mês para empatar; tudo acima disso é lucro."
    }

    /** Previsão do próximo mês: média dos últimos meses com a tendência (regressão linear simples). */
    fun prever(valoresMensais: List<Long>): Long? {
        val v = valoresMensais.dropWhile { it == 0L }
        if (v.isEmpty()) return null
        if (v.size == 1) return v[0]
        val n = v.size
        val xm = (n - 1) / 2.0
        val ym = v.average()
        var num = 0.0
        var den = 0.0
        for (i in v.indices) {
            num += (i - xm) * (v[i] - ym)
            den += (i - xm) * (i - xm)
        }
        val inclinacao = if (den == 0.0) 0.0 else num / den
        // mistura metade média, metade tendência (mais estável com pouco histórico)
        val tendencia = ym + inclinacao * (n - xm)
        return maxOf(0L, ((ym + tendencia) / 2).roundToLong())
    }

    // ================================================================== matemática financeira
    /** Juros compostos: valor final de [capital] a [taxa]% por período durante [n] períodos. */
    fun jurosCompostos(capital: Double, taxa: Double, n: Int) = capital * (1 + taxa / 100).pow(n)

    /** Parcela fixa (tabela Price) de [valor] em [n] vezes com [taxa]% ao mês. */
    fun parcela(valor: Double, taxa: Double, n: Int): Double {
        if (taxa == 0.0) return valor / n
        val i = taxa / 100
        return valor * i / (1 - (1 + i).pow(-n))
    }

    /** Dando [desconto]% num produto com [margem]% de margem, quanto precisa vender a mais para lucrar o mesmo (em %). */
    fun vendasAMais(desconto: Double, margem: Double): Double? {
        val nova = margem - desconto
        if (nova <= 0) return null
        return (margem / nova - 1) * 100
    }

    /** Preço de venda para ter [margem]% de margem sobre o preço, com [custo]. */
    fun precoParaMargem(custo: Double, margem: Double): Double? = if (margem >= 100) null else custo / (1 - margem / 100)
    // ================================================================== histórico, previsão e textos
    private val MESES = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")

    /** Os últimos [meses] meses (o atual por último), com o resultado de cada um. */
    fun historico(
        pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, lancamentos: List<Lancamento>, fixas: List<Fixa>,
        meses: Int, agora: Calendar,
    ): List<Pair<String, Resultado>> {
        val saida = mutableListOf<Pair<String, Resultado>>()
        for (k in meses - 1 downTo 0) {
            val ini = (agora.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, -k)
            }
            val fim = (ini.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val p = Periodo(ini.timeInMillis, minOf(fim.timeInMillis, agora.timeInMillis + 1), "")
            saida += MESES[ini.get(Calendar.MONTH)] to resultado(pedidos, produtos, lancamentos, fixas, p)
        }
        return saida
    }

    /** Fração do mês atual que já passou (para projetar o mês corrente). */
    fun fracaoDoMes(agora: Calendar): Double {
        val dias = agora.getActualMaximum(Calendar.DAY_OF_MONTH)
        val passados = agora.get(Calendar.DAY_OF_MONTH) - 1 + agora.get(Calendar.HOUR_OF_DAY) / 24.0
        return (passados / dias).coerceIn(0.05, 1.0)
    }

    fun textoPrevisao(hist: List<Pair<String, Resultado>>, fixasMensais: Long, agora: Calendar): String {
        if (hist.all { it.second.receita == 0L && it.second.despesas == 0L && it.second.despesasFixas == 0L }) {
            return "Ainda não tenho histórico para prever. Conforme as vendas e as despesas forem entrando, eu passo a projetar o mês."
        }
        val fracao = fracaoDoMes(agora)
        val atual = hist.last().second
        val anteriores = hist.dropLast(1)
        // projeção do mês atual: o que já entrou, no ritmo do mês
        val receitaProjetada = (atual.receita / fracao).roundToLong()
        val variaveisProjetadas = (atual.despesas / fracao).roundToLong()
        val despesasProjetadas = variaveisProjetadas + maxOf(fixasMensais, atual.despesasFixas)
        val margem = if (atual.receita > 0) atual.margemContribuicao else anteriores.lastOrNull { it.second.receita > 0 }?.second?.margemContribuicao ?: 0.0
        val lucroProjetado = (receitaProjetada * margem).roundToLong() - despesasProjetadas
        val proxReceita = prever(anteriores.map { it.second.receita } + receitaProjetada)
        val proxDespesas = prever(anteriores.map { it.second.despesas + it.second.despesasFixas } + despesasProjetadas)
        var t = "No ritmo atual, este mês deve fechar com vendas de uns ${r(receitaProjetada)} e despesas de uns ${r(despesasProjetadas)}"
        t += if (margem > 0) ", com ${if (lucroProjetado >= 0) "lucro" else "prejuízo"} perto de ${r(kotlin.math.abs(lucroProjetado))}." else "."
        if (proxReceita != null && anteriores.count { it.second.receita > 0 } >= 1) {
            t += " Para o mês que vem, a previsão é de ${r(proxReceita)} em vendas"
            if (proxDespesas != null && proxDespesas > 0) t += " e ${r(proxDespesas)} em despesas"
            t += "."
            val ultimo = anteriores.lastOrNull()?.second?.receita ?: 0
            if (ultimo > 0) {
                val v = ((receitaProjetada - ultimo) * 100.0 / ultimo).roundToLong()
                if (v >= 10) t += " As vendas estão $v% acima do mês passado."
                if (v <= -10) t += " As vendas estão ${-v}% abaixo do mês passado; vale uma promoção ou um post novo."
            }
        } else {
            t += " Com mais um mês de histórico eu consigo prever o próximo mês também."
        }
        if (fracao < 0.2) t += " O mês ainda está no começo, então a projeção pode mudar bastante."
        return t
    }

    fun textoLancado(l: Lancamento): String =
        if (l.entrada) "Anotado: entrada de ${r(l.valorCentavos.toLong())}, ${l.descricao.replaceFirstChar { it.lowercase() }}. Se errei, diga: apaga o último lançamento."
        else "Anotado: despesa de ${r(l.valorCentavos.toLong())} com ${l.descricao.replaceFirstChar { it.lowercase() }} (${l.categoria}). Se errei, diga: apaga o último lançamento."

    fun textoFixa(f: Fixa): String =
        "Combinado: ${f.descricao.replaceFirstChar { it.lowercase() }}, ${r(f.valorCentavos.toLong())} por mês, todo dia ${f.dia}. Entra nas contas de todo mês a partir deste."

    /** Contas de matemática financeira, faladas. */
    fun textoCalculo(tipo: String, a: Double, b: Double, c: Double, taxaAnual: Boolean = false): String {
        fun d(v: Double) = r((v * 100).roundToLong())
        fun p(v: Double) = String.format(java.util.Locale("pt", "BR"), "%.1f", v).removeSuffix(",0")
        return when (tipo) {
            "juros_compostos" -> {
                val meses = c.toInt().coerceAtLeast(1)
                val taxaMes = if (taxaAnual) ((1 + b / 100).pow(1.0 / 12) - 1) * 100 else b
                val fim = jurosCompostos(a, taxaMes, meses)
                "${d(a)} a ${p(b)}% ${if (taxaAnual) "ao ano" else "ao mês"} por $meses ${if (meses == 1) "mês" else "meses"} viram ${d(fim)}, ou seja, ${d(fim - a)} de rendimento (juros compostos, sem descontar imposto)."
            }
            "parcela" -> {
                val n = c.toInt().coerceAtLeast(1)
                val parc = parcela(a, b, n)
                "${d(a)} em $n vezes com ${p(b)}% ao mês dá parcelas de ${d(parc)}. No total você paga ${d(parc * n)}, sendo ${d(parc * n - a)} de juros."
            }
            "desconto_venda" -> {
                val margem = if (b > 0) b else 40.0
                val mais = vendasAMais(a, margem)
                val base = if (b > 0) "" else " (considerando uma margem de 40%; me diga a margem certa se quiser)"
                if (mais == null) "Com ${p(a)}% de desconto você venderia no prejuízo$base: o desconto é maior que a margem."
                else "Dando ${p(a)}% de desconto, você precisa vender ${p(mais)}% a mais só para lucrar o mesmo$base."
            }
            "markup" -> {
                if (b <= 0) "Para ter ${p(a)}% de margem, divida o custo por ${p(1 - a / 100)}. Por exemplo, um produto que custa R$ 20 deve sair por ${precoParaMargem(20.0, a)?.let { d(it) } ?: "—"}. Me diga o custo para eu calcular certinho."
                else precoParaMargem(b, a)?.let { "Com custo de ${d(b)}, para ter ${p(a)}% de margem o preço deve ser ${d(it)} (lucro de ${d(it - b)} por unidade)." }
                    ?: "Margem de 100% ou mais não existe sobre o preço de venda."
            }
            else -> "Não conheço essa conta ainda."
        }
    }
}
