package com.tonimarques.sextafeira

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Gráficos e relatório em PDF das finanças da loja, desenhados no próprio celular (nada sai do aparelho).
 */
object Relatorio {

    private val ROSA = Color.parseColor("#E36BD0")
    private val ROXO = Color.parseColor("#8A5CFF")
    private val VERDE = Color.parseColor("#5FD8A4")
    private val VERMELHO = Color.parseColor("#FF6B81")
    private val FUNDO_1 = Color.parseColor("#1A0B22")
    private val FUNDO_2 = Color.parseColor("#06040A")
    private val TEXTO = Color.parseColor("#F5E9F7")
    private val APAGADO = Color.parseColor("#A893B0")

    private fun curto(centavos: Long): String {
        val reais = centavos / 100.0
        return when {
            kotlin.math.abs(reais) >= 1_000_000 -> String.format(Locale("pt", "BR"), "R$ %.1f mi", reais / 1_000_000)
            kotlin.math.abs(reais) >= 10_000 -> String.format(Locale("pt", "BR"), "R$ %.0f mil", reais / 1000)
            kotlin.math.abs(reais) >= 1_000 -> String.format(Locale("pt", "BR"), "R$ %.1f mil", reais / 1000)
            else -> String.format(Locale("pt", "BR"), "R$ %.0f", reais)
        }
    }

    private fun custos(r: Financas.Resultado) = r.custoMercadoria + r.despesas + r.despesasFixas

    // ================================================================== gráfico
    /** Vendas × custos por mês (barras) com o lucro (linha), e as despesas do mês por categoria. */
    fun grafico(hist: List<Pair<String, Financas.Resultado>>, escuro: Boolean = true, largura: Int = 1080): Bitmap {
        val altura = (largura * 1.25f).toInt()
        val b = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        desenharGrafico(c, hist, largura.toFloat(), altura.toFloat(), escuro)
        return b
    }

    fun desenharGrafico(c: Canvas, hist: List<Pair<String, Financas.Resultado>>, w: Float, h: Float, escuro: Boolean) {
        val u = w / 1080f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val corTexto = if (escuro) TEXTO else Color.parseColor("#2A1830")
        val corApagada = if (escuro) APAGADO else Color.parseColor("#7A6680")
        if (escuro) {
            p.shader = LinearGradient(0f, 0f, 0f, h, FUNDO_1, FUNDO_2, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
        } else {
            c.drawColor(Color.WHITE)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = corTexto; textSize = 46 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        c.drawText("Vendas × custos por mês", 60 * u, 90 * u, t)
        val leg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = corApagada; textSize = 28 * u }
        var lx = 60 * u
        for ((cor, nome) in listOf(ROSA to "Vendas", ROXO to "Custos e despesas", VERDE to "Lucro")) {
            p.color = cor
            c.drawRoundRect(RectF(lx, 120 * u, lx + 28 * u, 148 * u), 6 * u, 6 * u, p)
            c.drawText(nome, lx + 40 * u, 144 * u, leg)
            lx += 40 * u + leg.measureText(nome) + 40 * u
        }

        // área das barras
        val topo = 200 * u
        val base = h * 0.56f
        val esq = 60 * u
        val dir = w - 60 * u
        val maximo = maxOf(1L, hist.maxOfOrNull { maxOf(it.second.receita, custos(it.second), it.second.lucro) } ?: 1L)
        val minimo = minOf(0L, hist.minOfOrNull { it.second.lucro } ?: 0L)
        val faixa = (maximo - minimo).toFloat()
        fun y(v: Long) = base - (v - minimo) / faixa * (base - topo)
        val zero = y(0)
        val grade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (escuro) Color.argb(40, 255, 255, 255) else Color.argb(30, 0, 0, 0); strokeWidth = 2 * u }
        for (k in 0..4) {
            val gy = topo + (base - topo) * k / 4f
            c.drawLine(esq, gy, dir, gy, grade)
        }
        c.drawLine(esq, zero, dir, zero, Paint(grade).apply { color = corApagada })

        val n = hist.size.coerceAtLeast(1)
        val coluna = (dir - esq) / n
        val barra = coluna * 0.3f
        val valor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = corTexto; textSize = 22 * u; textAlign = Paint.Align.CENTER }
        val mes = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = corApagada; textSize = 30 * u; textAlign = Paint.Align.CENTER }
        val pontos = mutableListOf<Pair<Float, Float>>()
        hist.forEachIndexed { i, (nome, r) ->
            val cx = esq + coluna * i + coluna / 2
            p.color = ROSA
            c.drawRoundRect(RectF(cx - barra - 4 * u, y(r.receita), cx - 4 * u, zero), 8 * u, 8 * u, p)
            p.color = ROXO
            c.drawRoundRect(RectF(cx + 4 * u, y(custos(r)), cx + barra + 4 * u, zero), 8 * u, 8 * u, p)
            if (r.receita > 0) c.drawText(curto(r.receita), cx - barra / 2 - 4 * u, y(r.receita) - 10 * u, valor)
            c.drawText(nome, cx, base + 44 * u, mes)
            pontos += cx to y(r.lucro)
        }
        if (pontos.size > 1) {
            val linha = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VERDE; strokeWidth = 6 * u; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
            val caminho = Path().apply {
                moveTo(pontos[0].first, pontos[0].second)
                for (k in 1 until pontos.size) lineTo(pontos[k].first, pontos[k].second)
            }
            c.drawPath(caminho, linha)
        }
        p.color = VERDE
        for ((px, py) in pontos) c.drawCircle(px, py, 10 * u, p)
        hist.lastOrNull()?.second?.let { r ->
            val lucro = Paint(valor).apply { color = if (r.lucro >= 0) VERDE else VERMELHO; textSize = 26 * u; typeface = Typeface.DEFAULT_BOLD }
            pontos.lastOrNull()?.let { (px, py) -> c.drawText(curto(r.lucro), px, py - 22 * u, lucro) }
        }

        // despesas do mês por categoria
        val atual = hist.lastOrNull()?.second
        var yy = base + 120 * u
        c.drawText("Para onde foi o dinheiro neste mês", 60 * u, yy, Paint(t).apply { textSize = 38 * u })
        yy += 30 * u
        val categorias = buildList {
            if (atual != null && atual.custoMercadoria > 0) add("mercadorias vendidas" to atual.custoMercadoria)
            atual?.porCategoria?.entries?.sortedByDescending { it.value }?.forEach { add(it.key to it.value) }
        }.take(7)
        if (categorias.isEmpty()) {
            yy += 50 * u
            c.drawText("Nenhuma despesa lançada ainda. Diga: paguei 120 de internet.", 60 * u, yy, leg)
        } else {
            val maior = categorias.maxOf { it.second }.coerceAtLeast(1)
            val nomes = Paint(leg).apply { color = corTexto; textSize = 28 * u }
            val larguraNome = 300 * u
            val cores = listOf(ROSA, ROXO, Color.parseColor("#FF9F6B"), Color.parseColor("#6BC5FF"), VERDE, Color.parseColor("#FFD36B"), Color.parseColor("#C08BFF"))
            categorias.forEachIndexed { i, (nome, v) ->
                yy += 62 * u
                c.drawText(nome.replaceFirstChar { it.uppercase() }, 60 * u, yy, nomes)
                val x0 = 60 * u + larguraNome
                val x1 = x0 + (w - x0 - 220 * u) * (v.toFloat() / maior)
                p.color = cores[i % cores.size]
                c.drawRoundRect(RectF(x0, yy - 28 * u, maxOf(x1, x0 + 8 * u), yy + 2 * u), 8 * u, 8 * u, p)
                c.drawText(curto(v), maxOf(x1, x0 + 8 * u) + 16 * u, yy, nomes)
            }
        }
        val rodape = Paint(leg).apply { textSize = 24 * u }
        c.drawText("${Marca.nome} · Desejo de Intimidade", 60 * u, h - 40 * u, rodape)
    }

    fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        b.compress(Bitmap.CompressFormat.JPEG, 92, out)
        out.toByteArray()
    }

    // ================================================================== PDF
    class Dados(
        val periodo: Financas.Periodo,
        val tituloPeriodo: String,
        val resultado: Financas.Resultado,
        val historico: List<Pair<String, Financas.Resultado>>,
        val lancamentos: List<Financas.Lancamento>,
        val fixas: List<Financas.Fixa>,
        val maisVendidos: List<Pair<String, Int>>,
        val previsao: String,
    )

    /** O relatório financeiro em PDF (A4), pronto para compartilhar ou imprimir. */
    fun pdf(d: Dados): ByteArray {
        val doc = PdfDocument()
        val largura = 595
        val altura = 842
        val margem = 40f
        var numero = 0
        var pagina: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f

        val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A1830"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B0379A"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A1830"); textSize = 10.5f }
        val cinza = Paint(normal).apply { color = Color.parseColor("#7A6680"); textSize = 9f }
        val direita = Paint(normal).apply { textAlign = Paint.Align.RIGHT }
        val negrito = Paint(direita).apply { typeface = Typeface.DEFAULT_BOLD }

        fun novaPagina() {
            pagina?.let { doc.finishPage(it) }
            numero++
            pagina = doc.startPage(PdfDocument.PageInfo.Builder(largura, altura, numero).create())
            c = pagina!!.canvas
            val faixa = Paint().apply { shader = LinearGradient(0f, 0f, largura.toFloat(), 0f, ROSA, ROXO, Shader.TileMode.CLAMP) }
            c!!.drawRect(0f, 0f, largura.toFloat(), 6f, faixa)
            c!!.drawText("Desejo de Intimidade · relatório financeiro · página $numero", margem, altura - 20f, cinza)
            y = margem + 10f
        }
        fun espaco(precisa: Float) {
            if (y + precisa > altura - 50f) novaPagina()
        }
        fun texto(s: String, paint: Paint = normal, recuo: Float = 0f) {
            for (linha in quebrar(s, paint, largura - 2 * margem - recuo)) {
                espaco(paint.textSize + 5)
                y += paint.textSize + 4
                c!!.drawText(linha, margem + recuo, y, paint)
            }
        }
        fun linhaValor(nome: String, valor: String, destaque: Boolean = false) {
            espaco(18f)
            y += 16f
            c!!.drawText(nome, margem, y, if (destaque) Paint(normal).apply { typeface = Typeface.DEFAULT_BOLD } else normal)
            c!!.drawText(valor, largura - margem, y, if (destaque) negrito else direita)
            c!!.drawLine(margem, y + 5, largura - margem, y + 5, Paint().apply { color = Color.parseColor("#EFE3F1") })
        }
        fun secao(s: String) {
            espaco(40f)
            y += 22f
            c!!.drawText(s, margem, y, sub)
            y += 4f
        }

        novaPagina()
        val r = d.resultado
        y += 14f
        c!!.drawText("Relatório financeiro", margem, y, titulo)
        y += 18f
        val quando = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR")).format(Calendar.getInstance().time)
        c!!.drawText("${d.tituloPeriodo.replaceFirstChar { it.uppercase() }} · gerado em $quando pela ${Marca.nome}", margem, y, cinza)

        secao("Resumo")
        linhaValor("Vendas no site (${r.pedidos} ${if (r.pedidos == 1) "pedido" else "pedidos"})", Loja.dinheiro(r.vendasSite.toInt()))
        if (r.entradasPorFora > 0) linhaValor("Vendas e entradas por fora", Loja.dinheiro(r.entradasPorFora.toInt()))
        linhaValor("Receita total", Loja.dinheiro(r.receita.toInt()), true)
        linhaValor("(−) Custo das mercadorias vendidas", Loja.dinheiro(r.custoMercadoria.toInt()))
        linhaValor("(−) Despesas do dia a dia", Loja.dinheiro(r.despesas.toInt()))
        linhaValor("(−) Despesas fixas", Loja.dinheiro(r.despesasFixas.toInt()))
        linhaValor(if (r.lucro >= 0) "(=) Lucro" else "(=) Prejuízo", Loja.dinheiro(kotlin.math.abs(r.lucro).toInt()), true)
        if (r.receita > 0) {
            linhaValor("Margem de lucro", "${(r.lucro * 100 / r.receita)}% das vendas")
            linhaValor("Margem de contribuição", "${(r.margemContribuicao * 100).toInt()}%")
        }
        if (r.pedidos > 0) linhaValor("Ticket médio no site", Loja.dinheiro((r.vendasSite / r.pedidos).toInt()))
        if (r.comprasMercadoria > 0) {
            linhaValor("Compras de mercadoria (estoque)", Loja.dinheiro(r.comprasMercadoria.toInt()))
            linhaValor("Fluxo de caixa", (if (r.fluxoDeCaixa < 0) "−" else "") + Loja.dinheiro(kotlin.math.abs(r.fluxoDeCaixa).toInt()), true)
        }
        if (r.fretesCobrados > 0) linhaValor("Frete cobrado dos clientes (repassado)", Loja.dinheiro(r.fretesCobrados.toInt()))
        if (r.itensSemCusto > 0) {
            y += 6f
            texto("Atenção: ${r.itensSemCusto} ${if (r.itensSemCusto == 1) "item vendido está" else "itens vendidos estão"} sem custo cadastrado; o lucro real é menor que o mostrado.", cinza)
        }

        // gráfico
        if (d.historico.isNotEmpty()) {
            secao("Últimos meses")
            val gw = largura - 2 * margem
            val gh = gw * 1.25f * 0.62f
            espaco(gh + 10)
            c!!.save()
            c!!.translate(margem, y + 6)
            c!!.clipRect(0f, 0f, gw, gh)
            // só a parte de cima (barras e linha) do gráfico, em versão clara
            desenharGrafico(c!!, d.historico, gw, gw * 1.25f, escuro = false)
            c!!.restore()
            y += gh + 10
        }

        if (r.porCategoria.isNotEmpty()) {
            secao("Despesas por categoria")
            for ((nome, v) in r.porCategoria.entries.sortedByDescending { it.value }) linhaValor(nome.replaceFirstChar { it.uppercase() }, Loja.dinheiro(v.toInt()))
        }
        if (d.maisVendidos.isNotEmpty()) {
            secao("Mais vendidos")
            for ((nome, q) in d.maisVendidos.take(8)) linhaValor(nome, "$q ${if (q == 1) "unidade" else "unidades"}")
        }
        if (d.previsao.isNotBlank()) {
            secao("Previsão")
            texto(d.previsao)
        }
        if (d.fixas.isNotEmpty()) {
            secao("Despesas fixas cadastradas")
            for (f in d.fixas) linhaValor("${f.descricao} (dia ${f.dia})", Loja.dinheiro(f.valorCentavos))
        }
        val doPeriodo = d.lancamentos.filter { it.data in d.periodo.ini until d.periodo.fim }.sortedBy { it.data }
        if (doPeriodo.isNotEmpty()) {
            secao("Lançamentos do livro caixa")
            val fmt = SimpleDateFormat("dd/MM", Locale("pt", "BR"))
            for (l in doPeriodo) {
                linhaValor("${fmt.format(l.data)}  ${l.descricao} · ${l.categoria}", (if (l.entrada) "+ " else "− ") + Loja.dinheiro(l.valorCentavos))
            }
        }
        y += 18f
        texto("Lucro = vendas − custo das mercadorias vendidas − despesas. Compras de estoque entram no fluxo de caixa, não no lucro, " +
            "porque o custo só conta quando o produto é vendido. Taxas de cartão e frete são repassados ao cliente.", cinza)

        pagina?.let { doc.finishPage(it) }
        val saida = ByteArrayOutputStream()
        doc.writeTo(saida)
        doc.close()
        return saida.toByteArray()
    }

    /** Quebra o texto em linhas que cabem em [largura]. */
    fun quebrar(texto: String, paint: Paint, largura: Float): List<String> {
        val linhas = mutableListOf<String>()
        for (paragrafo in texto.split("\n")) {
            var atual = ""
            for (palavra in paragrafo.split(" ")) {
                val tentativa = if (atual.isEmpty()) palavra else "$atual $palavra"
                if (paint.measureText(tentativa) <= largura || atual.isEmpty()) atual = tentativa
                else {
                    linhas += atual
                    atual = palavra
                }
            }
            linhas += atual
        }
        return linhas
    }
}
