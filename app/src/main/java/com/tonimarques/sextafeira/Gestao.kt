package com.tonimarques.sextafeira

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * O que os ERPs com IA fazem de melhor, trazido para a Vênus:
 * curva ABC e sugestão de compra com ponto de pedido (Estoque.ai, LEAFIO, Zoho),
 * clientes para recompra (Nuvemshop), calendário comercial (previsão sazonal)
 * e ações em lote, como promoção nos produtos parados (Shopify Sidekick).
 */
object Gestao {

    private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")
    private fun dinheiro(c: Int) = Loja.dinheiro(c)
    private fun plural(n: Int, um: String, varios: String) = "$n ${if (n == 1) um else varios}"
    private fun norm(s: String) = ComandosLocais.normalizar(s.trim())

    private fun pagos(pedidos: List<Loja.Pedido>, desde: Long, ate: Long = Long.MAX_VALUE) =
        pedidos.filter { it.status in PAGOS && (it.pago ?: it.criado) in desde until ate }

    private fun produtoDo(i: Loja.Item, produtos: List<Loja.Produto>, porNome: Map<String, Loja.Produto>) =
        i.produtoId?.let { id -> produtos.firstOrNull { it.id == id } } ?: porNome[norm(i.nome)]

    // ================================================================== curva ABC
    data class ItemABC(val nome: String, val receita: Long, val unidades: Int, val classe: Char, val acumulado: Double)

    /** Classifica os produtos pela receita: A = até 80% do faturamento, B = até 95%, C = o resto. */
    fun curvaABC(pedidos: List<Loja.Pedido>, agora: Calendar, dias: Int = 90): List<ItemABC> {
        val desde = agora.timeInMillis - dias * 86_400_000L
        val porNome = mutableMapOf<String, Pair<Long, Int>>()
        val nomeBonito = mutableMapOf<String, String>()
        for (o in pagos(pedidos, desde)) for (i in o.itens) {
            val k = norm(i.nome)
            nomeBonito.putIfAbsent(k, i.nome.trim())
            val (r, u) = porNome[k] ?: (0L to 0)
            porNome[k] = (r + i.precoCentavos.toLong() * i.quantidade) to (u + i.quantidade)
        }
        val total = porNome.values.sumOf { it.first }.toDouble()
        if (total <= 0) return emptyList()
        var acc = 0.0
        return porNome.entries.sortedByDescending { it.value.first }.map { (k, v) ->
            val antes = acc
            acc += v.first / total
            val classe = when {
                antes < 0.80 -> 'A'
                antes < 0.95 -> 'B'
                else -> 'C'
            }
            ItemABC(nomeBonito[k] ?: k, v.first, v.second, classe, acc)
        }
    }

    fun textoABC(itens: List<ItemABC>, dias: Int = 90): String {
        if (itens.isEmpty()) return "Ainda não tem vendas suficientes nos últimos $dias dias para montar a curva ABC."
        val a = itens.filter { it.classe == 'A' }
        val b = itens.filter { it.classe == 'B' }
        val c = itens.filter { it.classe == 'C' }
        val total = itens.sumOf { it.receita }
        val pctA = (a.sumOf { it.receita } * 100.0 / total).roundToInt()
        var t = "Curva ABC dos últimos $dias dias:\n" +
            "A, os que sustentam a loja (${pctA}% do faturamento): " + a.joinToString(", ") { it.nome } + ".\n"
        if (b.isNotEmpty()) t += "B, importantes: " + b.take(8).joinToString(", ") { it.nome } + (if (b.size > 8) " e mais ${b.size - 8}" else "") + ".\n"
        if (c.isNotEmpty()) t += "C, pouco peso: " + c.take(8).joinToString(", ") { it.nome } + (if (c.size > 8) " e mais ${c.size - 8}" else "") + ".\n"
        t += "Dica: nunca deixe faltar os da curva A; nos da C, compre pouco e use em kits ou promoções."
        return t
    }

    // ================================================================== sugestão de compra
    data class Compra(val produto: Loja.Produto, val ritmoDia: Double, val pontoDePedido: Int, val comprar: Int, val diasRestantes: Int?)

    /**
     * Ponto de pedido = venda média por dia × prazo do fornecedor + estoque de segurança (metade do prazo).
     * Quando o estoque chega nele, é hora de comprar o suficiente para [cobertura] dias.
     */
    fun sugestaoDeCompra(
        pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, agora: Calendar, prazoDias: Int = 7, cobertura: Int = 30,
    ): List<Compra> {
        val ritmo = Loja.ritmoDeVendas(pedidos, produtos, agora, 45)
        return produtos.filter { it.ativo && it.estoque != null }.mapNotNull { p ->
            val r = ritmo[p.id] ?: 0.0
            if (r <= 0.0) return@mapNotNull null
            val seguranca = ceil(r * prazoDias * 0.5).toInt()
            val ponto = ceil(r * prazoDias).toInt() + seguranca
            val estoque = p.estoque!!
            if (estoque > ponto) return@mapNotNull null
            val comprar = maxOf(1, ceil(r * (prazoDias + cobertura)).toInt() + seguranca - maxOf(estoque, 0))
            Compra(p, r, ponto, comprar, if (estoque <= 0) 0 else (estoque / r).toInt())
        }.sortedWith(compareBy<Compra> { it.diasRestantes ?: 0 }.thenByDescending { it.ritmoDia })
    }

    fun textoCompra(lista: List<Compra>, prazoDias: Int, cobertura: Int = 30): String {
        if (lista.isEmpty()) return "Nenhum produto chegou no ponto de pedido. Com o fornecedor entregando em $prazoDias dias, ainda dá tempo de esperar."
        val linhas = lista.take(10).joinToString("\n") { c ->
            val situacao = when {
                (c.produto.estoque ?: 0) <= 0 -> "esgotado"
                c.diasRestantes != null && c.diasRestantes < prazoDias -> "acaba em ${plural(maxOf(c.diasRestantes, 0), "dia", "dias")}, antes da mercadoria chegar"
                else -> "restam ${c.produto.estoque}, acaba em uns ${plural(c.diasRestantes ?: 0, "dia", "dias")}"
            }
            "• ${c.produto.nome}: comprar ${c.comprar} ($situacao)"
        }
        val custo = lista.mapNotNull { c -> c.produto.custoCentavos?.let { it.toLong() * c.comprar } }
        val total = if (custo.size == lista.size && custo.isNotEmpty()) "\nInvestimento estimado: ${dinheiro(custo.sum().toInt())}." else ""
        return "Hora de comprar (fornecedor entrega em $prazoDias dias, compra para $cobertura dias de venda):\n$linhas" +
            (if (lista.size > 10) "\nE mais ${lista.size - 10}." else "") + total
    }

    // ================================================================== recompra
    data class Cliente(val nome: String, val telefone: String, val ultimaCompra: Long, val compras: Int, val gasto: Int, val produtos: List<String>)

    /** Clientes que compraram entre [minDias] e [maxDias] dias atrás e não voltaram desde então. */
    fun clientesParaRecompra(pedidos: List<Loja.Pedido>, agora: Long, minDias: Int = 30, maxDias: Int = 180): List<Cliente> {
        val porCliente = pagos(pedidos, 0).filter { it.telefone.replace(Regex("\\D"), "").length >= 10 }
            .groupBy { it.telefone.replace(Regex("\\D"), "").takeLast(11) }
        return porCliente.mapNotNull { (tel, lista) ->
            val ultima = lista.maxOf { it.pago ?: it.criado }
            val dias = (agora - ultima) / 86_400_000L
            if (dias < minDias || dias > maxDias) return@mapNotNull null
            val produtos = lista.sortedByDescending { it.pago ?: it.criado }.flatMap { it.itens.map { i -> i.nome.trim() } }.distinct()
            Cliente(lista.first().cliente.trim(), tel, ultima, lista.size, lista.sumOf { it.totalCentavos }, produtos)
        }.sortedWith(compareByDescending<Cliente> { it.compras }.thenByDescending { it.gasto })
    }

    fun mensagemRecompra(c: Cliente): String {
        val nome = c.nome.split(" ").firstOrNull()?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()
        val produto = c.produtos.firstOrNull()
        return "Oi${if (nome.isNotBlank()) ", $nome" else ""}! Aqui é da ${Perfil.loja} 💕 " +
            (if (produto != null) "Esperamos que esteja curtindo o $produto. " else "") +
            "Chegaram novidades na loja e separamos um carinho para clientes especiais como você. Quer dar uma olhadinha? ${Loja.SITE}"
    }

    fun linkWhatsapp(c: Cliente, mensagem: String = mensagemRecompra(c)): String {
        val tel = c.telefone.let { if (it.length <= 11) "55$it" else it }
        return "https://wa.me/$tel?text=" + java.net.URLEncoder.encode(mensagem, "UTF-8").replace("+", "%20")
    }

    fun textoRecompra(lista: List<Cliente>, agora: Long): String {
        if (lista.isEmpty()) return "Ninguém para chamar de volta agora: não tem cliente que comprou entre 1 e 6 meses atrás sem voltar."
        val linhas = lista.take(8).joinToString("\n") { c ->
            val dias = ((agora - c.ultimaCompra) / 86_400_000L).toInt()
            "• ${c.nome.split(" ").take(2).joinToString(" ")}: última compra há $dias dias" +
                (if (c.compras > 1) ", ${c.compras} compras" else "") + ", ${dinheiro(c.gasto)}" +
                (c.produtos.firstOrNull()?.let { " ($it)" } ?: "")
        }
        return "${plural(lista.size, "cliente pode", "clientes podem")} ser chamados de volta:\n$linhas" +
            (if (lista.size > 8) "\nE mais ${lista.size - 8}." else "") +
            "\nPara mandar a mensagem, diga: chama de volta, e o nome da cliente. A mensagem abre pronta no WhatsApp, você só confere e envia."
    }

    fun acharCliente(lista: List<Cliente>, falado: String): Cliente? {
        val k = norm(falado).replace(Regex("^(?:o|a|do|da|pro|pra|para o|para a)\\s+"), "")
        if (k.isBlank()) return lista.firstOrNull()
        return lista.firstOrNull { norm(it.nome) == k } ?: lista.firstOrNull { norm(it.nome).startsWith(k) }
            ?: lista.firstOrNull { norm(it.nome).contains(k) }
    }

    // ================================================================== calendário comercial
    data class Data(val nome: String, val dia: Calendar, val dica: String)

    /** Domingo de Páscoa (algoritmo de Meeus), para o Carnaval. */
    fun pascoa(ano: Int): Calendar {
        val a = ano % 19
        val b = ano / 100
        val c = ano % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val mes = (h + l - 7 * m + 114) / 31
        val dia = ((h + l - 7 * m + 114) % 31) + 1
        return Calendar.getInstance().apply { clear(); set(ano, mes - 1, dia, 12, 0) }
    }

    /** Black Friday: a sexta depois da quarta quinta-feira de novembro. */
    fun blackFriday(ano: Int): Calendar {
        val c = Calendar.getInstance().apply { clear(); set(ano, Calendar.NOVEMBER, 1, 12, 0) }
        var quintas = 0
        while (true) {
            if (c.get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY) {
                quintas++
                if (quintas == 4) break
            }
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c
    }

    fun datasDoAno(ano: Int): List<Data> {
        fun d(mes: Int, dia: Int) = Calendar.getInstance().apply { clear(); set(ano, mes, dia, 12, 0) }
        val carnaval = pascoa(ano).apply { add(Calendar.DAY_OF_MONTH, -47) }
        return listOf(
            Data("Carnaval", carnaval, "kits para a folia, géis e preservativos; poste uma semana antes"),
            Data("Dia Internacional da Mulher", d(Calendar.MARCH, 8), "autocuidado e prazer feminino; lingeries e vibradores em destaque"),
            Data("Dia do Consumidor", d(Calendar.MARCH, 15), "cupom ou frete grátis por 1 ou 2 dias"),
            Data("Dia do Beijo", d(Calendar.APRIL, 13), "géis beijáveis e sabores"),
            Data("Dia dos Namorados", d(Calendar.JUNE, 12), "a data mais forte do ano: kits para casal, embalagem para presente, estoque reforçado 3 semanas antes"),
            Data("Dia do Orgasmo", d(Calendar.JULY, 31), "conteúdo divertido no Instagram e promoção de vibradores"),
            Data("Dia do Solteiro", d(Calendar.AUGUST, 15), "produtos de uso individual, tom bem-humorado"),
            Data("Dia do Sexo", d(Calendar.SEPTEMBER, 6), "data da categoria: campanha forte, combos e brindes"),
            Data("Black Friday", blackFriday(ano), "descontos reais nos parados e nos da curva C; garanta estoque dos da curva A"),
            Data("Natal", d(Calendar.DECEMBER, 25), "presentes discretos, embalagem caprichada e prazo de entrega bem avisado"),
            Data("Réveillon", d(Calendar.DECEMBER, 31), "lingerie branca, vermelha e dourada; poste até o dia 26"),
        )
    }

    /** As datas comerciais dos próximos [dias] dias, a partir de hoje. */
    fun proximasDatas(agora: Calendar, dias: Int = 60): List<Pair<Data, Int>> {
        val ano = agora.get(Calendar.YEAR)
        val hoje = (agora.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return (datasDoAno(ano) + datasDoAno(ano + 1)).map { d ->
            d to ((d.dia.timeInMillis - hoje.timeInMillis) / 86_400_000L).toInt()
        }.filter { it.second in 0..dias }.sortedBy { it.second }
    }

    fun textoDatas(lista: List<Pair<Data, Int>>, dias: Int = 60): String {
        if (lista.isEmpty()) return "Nenhuma data comercial importante nos próximos $dias dias. Bom momento para cuidar do estoque e do Instagram."
        return "Datas que vendem nos próximos $dias dias:\n" + lista.joinToString("\n") { (d, faltam) ->
            val quando = when (faltam) {
                0 -> "é hoje"
                1 -> "é amanhã"
                else -> "faltam $faltam dias (${"%02d/%02d".format(d.dia.get(Calendar.DAY_OF_MONTH), d.dia.get(Calendar.MONTH) + 1)})"
            }
            "• ${d.nome}: $quando. ${d.dica.replaceFirstChar { it.uppercase() }}."
        }
    }

    /** Aviso para o resumo/bom dia quando uma data está chegando (21, 14, 7 e 2 dias antes). */
    fun avisoDeData(agora: Calendar): String? {
        val (d, faltam) = proximasDatas(agora, 21).firstOrNull { it.second in setOf(21, 14, 7, 2) } ?: return null
        return "Faltam $faltam dias para ${d.nome}: ${d.dica}."
    }

    // ================================================================== promoção em lote
    /** Monta a promoção de [pct]% nos produtos sem venda há [dias] dias (para confirmar de uma vez). */
    fun promocaoNosParados(pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, agora: Calendar, dias: Int, pct: Int): Loja.Preparo {
        if (pct !in 1..90) return Loja.Preparo("O desconto precisa ser entre 1 e 90 por cento.", null)
        val desde = agora.timeInMillis - dias * 86_400_000L
        val vendidos = pagos(pedidos, desde).flatMap { it.itens }.map { norm(it.nome) }.toSet()
        val parados = produtos.filter { it.ativo && (it.estoque == null || it.estoque > 0) && norm(it.nome) !in vendidos && it.precoCentavos != null }
        if (parados.isEmpty()) return Loja.Preparo("Nenhum produto ficou parado nos últimos $dias dias. Nada para colocar em promoção.", null)
        val lote = mutableListOf<Loja.Alteracao>()
        val linhas = mutableListOf<String>()
        val pulados = mutableListOf<String>()
        for (p in parados) {
            val preco = p.precoCentavos!!
            val cheio = if (p.precoAntigo != null && p.precoAntigo > preco) p.precoAntigo else preco
            val novo = ((cheio.toLong() * (100 - pct) + 50) / 100).toInt()
            val custo = p.custoCentavos
            if (novo <= 0 || novo >= cheio || Loja.corpoDoProduto(p.bruto, "promocao", novo) == null) {
                pulados += p.nome
                continue
            }
            if (custo != null && novo <= custo) {
                pulados += "${p.nome} (ficaria abaixo do custo)"
                continue
            }
            lote += Loja.Alteracao("promocao", produto = p, valor = novo)
            linhas += "• ${p.nome}: de ${dinheiro(cheio)} por ${dinheiro(novo)}"
        }
        if (lote.isEmpty()) return Loja.Preparo("Não deu para montar a promoção: " + pulados.joinToString(", ") + ".", null)
        val texto = "Promoção de $pct% em ${plural(lote.size, "produto parado", "produtos parados")} há $dias dias:\n" +
            linhas.take(10).joinToString("\n") + (if (linhas.size > 10) "\nE mais ${linhas.size - 10}." else "") +
            (if (pulados.isNotEmpty()) "\nFicaram de fora: ${pulados.joinToString(", ")}." else "") +
            "\nDiga sim para confirmar ou não para cancelar."
        return Loja.Preparo(texto, Loja.Alteracao("lote", lote = lote))
    }
}
