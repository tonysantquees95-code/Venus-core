package com.tonimarques.sextafeira

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File

/**
 * Desenha recibo (A4), notinha (80 mm, altura sob medida) e etiqueta de envio (10 x 15 cm).
 * O mesmo desenho serve para a prévia na tela (Bitmap) e para o PDF. Medidas em pontos (1 pt = 1/72 pol).
 * Branco e preto com um destaque, para sair bem em qualquer impressora.
 */
object GeradorDeDocumentos {
    private const val A4_L = 595f
    private const val A4_A = 842f
    private const val NOTINHA_L = 226f // 80 mm
    private const val ETIQ_L = 283f // 10 cm
    private const val ETIQ_A = 425f // 15 cm
    private val DESTAQUE = Color.rgb(0xC2, 0x18, 0x5B)
    private val CINZA = Color.rgb(0x55, 0x55, 0x55)
    private const val MARCA = "Feito com a Vênus"

    class Pagina(val largura: Int, val altura: Int)

    fun tamanho(tipo: Documentos.Tipo, d: Documentos.Dados, ctx: Context, marca: Boolean): Pagina = when (tipo) {
        Documentos.Tipo.RECIBO -> Pagina(A4_L.toInt(), A4_A.toInt())
        Documentos.Tipo.ETIQUETA -> Pagina(ETIQ_L.toInt(), ETIQ_A.toInt())
        Documentos.Tipo.NOTINHA -> Pagina(NOTINHA_L.toInt(), Math.ceil(desenharNotinha(null, d, ctx, marca).toDouble()).toInt().coerceAtLeast(200))
    }

    /** Desenha o documento no canvas (já do tamanho de [tamanho]). */
    fun desenhar(c: Canvas, tipo: Documentos.Tipo, d: Documentos.Dados, ctx: Context, marca: Boolean) {
        c.drawColor(Color.WHITE)
        when (tipo) {
            Documentos.Tipo.RECIBO -> desenharRecibo(c, d, ctx, marca)
            Documentos.Tipo.NOTINHA -> desenharNotinha(c, d, ctx, marca)
            Documentos.Tipo.ETIQUETA -> desenharEtiqueta(c, d, ctx, marca)
        }
    }

    fun previa(tipo: Documentos.Tipo, d: Documentos.Dados, ctx: Context, marca: Boolean): Bitmap {
        val t = tamanho(tipo, d, ctx, marca)
        val b = Bitmap.createBitmap(t.largura * 2, t.altura * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(2f, 2f)
        desenhar(c, tipo, d, ctx, marca)
        return b
    }

    /** Grava o PDF e devolve o arquivo (nome que o FotoProvider entrega ao WhatsApp e ao leitor de PDF). */
    fun gerarPdf(ctx: Context, tipo: Documentos.Tipo, d: Documentos.Dados, marca: Boolean): File {
        val t = tamanho(tipo, d, ctx, marca)
        val doc = PdfDocument()
        val pagina = doc.startPage(PdfDocument.PageInfo.Builder(t.largura, t.altura, 1).create())
        desenhar(pagina.canvas, tipo, d, ctx, marca)
        doc.finishPage(pagina)
        val (arquivo, _) = FotoProvider.novoArquivo(ctx, Documentos.nomeDoArquivo(tipo, d.numero))
        arquivo.outputStream().use { doc.writeTo(it) }
        doc.close()
        return arquivo
    }

    // ------------------------------------------------------------------ ferramentas de desenho
    private fun tinta(tamanho: Float, cor: Int = Color.BLACK, negrito: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho
        color = cor
        typeface = Typeface.create(Typeface.SANS_SERIF, if (negrito) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Escreve um texto com quebra de linha. Com canvas nulo só mede. Devolve a altura usada. */
    private fun escrever(c: Canvas?, t: String, p: TextPaint, x: Float, y: Float, largura: Float, maxLinhas: Int = 6, direita: Boolean = false): Float {
        if (t.isEmpty()) return 0f
        val l = StaticLayout.Builder.obtain(t, 0, t.length, p, largura.toInt().coerceAtLeast(10))
            .setAlignment(if (direita) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLinhas).setEllipsize(TextUtils.TruncateAt.END).build()
        if (c != null) {
            c.save()
            c.translate(x, y)
            l.draw(c)
            c.restore()
        }
        return l.height.toFloat()
    }

    private fun linha(c: Canvas?, x1: Float, x2: Float, y: Float, cor: Int = Color.LTGRAY, espessura: Float = 0.8f, tracejada: Boolean = false) {
        if (c == null) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cor
            strokeWidth = espessura
            if (tracejada) pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
        }
        c.drawLine(x1, y, x2, y, p)
    }

    private fun logo(ctx: Context): Bitmap? {
        val f = GeradorDeCatalogo.arquivoDoLogo(ctx)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= 300) amostra *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = amostra })
    }

    private fun desenharLogo(c: Canvas?, b: Bitmap?, x: Float, y: Float, lado: Float) {
        if (c == null || b == null) return
        val escala = Math.min(lado / b.width, lado / b.height)
        val w = b.width * escala
        val h = b.height * escala
        c.drawBitmap(b, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun dinheiro(c: Int) = Documentos.dinheiro(c)

    private fun nomeDoCliente(d: Documentos.Dados) = d.cliente.ifBlank { "Cliente" }

    // ------------------------------------------------------------------ recibo (A4)
    private fun desenharRecibo(c: Canvas, d: Documentos.Dados, ctx: Context, marca: Boolean) {
        val m = 44f
        val l = A4_L - 2 * m
        var y = m
        val lg = logo(ctx)
        val temLogo = lg != null
        if (temLogo) desenharLogo(c, lg, m, y, 56f)
        val xTexto = if (temLogo) m + 70f else m
        val larguraEsq = l * 0.58f - (xTexto - m)
        var yEsq = y
        yEsq += escrever(c, d.loja.nome, tinta(18f, Color.BLACK, true), xTexto, yEsq, larguraEsq, 2)
        if (d.loja.documento.isNotBlank()) yEsq += escrever(c, d.loja.documento, tinta(9.5f, CINZA), xTexto, yEsq + 2, larguraEsq, 1) + 2
        if (d.loja.contato.isNotBlank()) yEsq += escrever(c, d.loja.contato, tinta(9.5f, CINZA), xTexto, yEsq + 1, larguraEsq, 1) + 1
        for (e in d.loja.enderecoLinhas.take(3)) yEsq += escrever(c, e, tinta(9.5f, CINZA), xTexto, yEsq + 1, larguraEsq, 1) + 1
        val xDir = m + l * 0.62f
        val lDir = l * 0.38f
        var yDir = y
        yDir += escrever(c, "RECIBO", tinta(20f, DESTAQUE, true), xDir, yDir, lDir, 1, direita = true)
        yDir += escrever(c, "Nº ${d.numero}", tinta(11f, Color.BLACK, true), xDir, yDir + 2, lDir, 1, direita = true) + 2
        if (d.data.isNotBlank()) yDir += escrever(c, d.data, tinta(10f, CINZA), xDir, yDir + 1, lDir, 1, direita = true) + 1
        y = Math.max(Math.max(yEsq, yDir), y + 60f) + 16f
        linha(c, m, m + l, y, DESTAQUE, 1.6f)
        y += 14f

        y += escrever(c, "CLIENTE", tinta(8.5f, CINZA, true), m, y, l, 1) + 2
        y += escrever(c, nomeDoCliente(d), tinta(13f, Color.BLACK, true), m, y, l, 2) + 1
        val contato = listOfNotNull(d.telefone.ifBlank { null }).joinToString(" · ")
        if (contato.isNotEmpty()) y += escrever(c, contato, tinta(10f, CINZA), m, y, l, 1) + 1
        if (d.enderecoLinhas.isNotEmpty() && !d.retirada) y += escrever(c, d.enderecoLinhas.joinToString(" · "), tinta(10f, CINZA), m, y, l, 3) + 1
        y += 16f

        // tabela de itens
        val xQtd = m + l * 0.60f
        val xUni = m + l * 0.74f
        c.drawRect(RectF(m, y, m + l, y + 20f), Paint().apply { color = Color.rgb(0xF4, 0xF4, 0xF4) })
        val cab = tinta(9f, CINZA, true)
        escrever(c, "ITEM", cab, m + 6f, y + 5f, l * 0.55f, 1)
        escrever(c, "QTD", cab, xQtd, y + 5f, l * 0.12f, 1, direita = true)
        escrever(c, "UNITÁRIO", cab, xUni, y + 5f, l * 0.12f + 22f, 1, direita = true)
        escrever(c, "TOTAL", cab, m + l * 0.88f - 6f, y + 5f, l * 0.12f, 1, direita = true)
        y += 26f
        val corpo = tinta(10.5f)
        val limite = A4_A - 210f
        var cortou = 0
        for ((i, it) in d.itens.withIndex()) {
            if (y > limite) { cortou = d.itens.size - i; break }
            val h = escrever(c, it.nome, corpo, m + 6f, y, l * 0.55f, 3)
            escrever(c, it.quantidade.toString(), corpo, xQtd, y, l * 0.12f, 1, direita = true)
            escrever(c, dinheiro(it.precoCentavos), corpo, xUni, y, l * 0.12f + 22f, 1, direita = true)
            escrever(c, dinheiro(it.quantidade * it.precoCentavos), corpo, m + l * 0.88f - 6f, y, l * 0.12f, 1, direita = true)
            y += Math.max(h, 14f) + 6f
            linha(c, m, m + l, y - 3f, Color.rgb(0xE6, 0xE6, 0xE6))
        }
        if (cortou > 0) y += escrever(c, "… e mais $cortou item(ns) deste pedido.", tinta(9.5f, CINZA), m + 6f, y, l, 1) + 4

        // totais, alinhados à direita
        y += 8f
        val xT = m + l * 0.55f
        val lT = l * 0.45f
        fun total(rot: String, valor: String, forte: Boolean = false) {
            val p = tinta(if (forte) 13f else 10.5f, if (forte) DESTAQUE else Color.BLACK, forte)
            escrever(c, rot, p, xT, y, lT * 0.5f, 1)
            escrever(c, valor, p, xT + lT * 0.4f, y, lT * 0.6f, 1, direita = true)
            y += (if (forte) 20f else 15f)
        }
        total("Subtotal", dinheiro(d.subtotalCentavos))
        if (d.freteCentavos > 0) total("Frete", dinheiro(d.freteCentavos))
        if (d.descontoCentavos > 0) total("Desconto", "-" + dinheiro(d.descontoCentavos))
        linha(c, xT, m + l, y - 3f, Color.BLACK, 0.8f)
        y += 3f
        total("TOTAL", dinheiro(d.totalCentavos), forte = true)
        if (d.rastreio != null) y += escrever(c, "Código de rastreio: ${d.rastreio}", tinta(10f, CINZA), m, y + 6f, l, 1) + 6
        if (d.observacao.isNotBlank()) y += escrever(c, d.observacao, tinta(10f, CINZA), m, y + 6f, l, 3) + 6

        if (d.loja.mensagem.isNotBlank()) {
            val yMsg = Math.max(y + 24f, A4_A - 130f)
            escrever(c, d.loja.mensagem, tinta(11f, DESTAQUE, true), m, yMsg, l, 3)
        }
        rodape(c, A4_L, A4_A - 44f, m, l, marca)
    }

    private fun rodape(c: Canvas?, largura: Float, y: Float, m: Float, l: Float, marca: Boolean) {
        linha(c, m, m + l, y, Color.LTGRAY)
        escrever(c, Documentos.AVISO_SEM_VALOR_FISCAL, tinta(8.5f, CINZA), m, y + 6f, l, 1)
        if (marca) escrever(c, MARCA, tinta(8.5f, CINZA), m, y + 6f, l, 1, direita = true)
    }

    // ------------------------------------------------------------------ notinha (80 mm); com canvas nulo só devolve a altura
    private fun desenharNotinha(c: Canvas?, d: Documentos.Dados, ctx: Context, marca: Boolean): Float {
        val m = 12f
        val l = NOTINHA_L - 2 * m
        var y = 14f
        val lg = if (c != null) logo(ctx) else null
        if (lg != null && c != null) {
            val lado = 44f
            desenharLogo(c, lg, (NOTINHA_L - lado) / 2f, y, lado)
            y += lado + 6f
        } else if (c == null && GeradorDeCatalogo.arquivoDoLogo(ctx).exists()) {
            y += 44f + 6f
        }
        fun centro(t: String, p: TextPaint, max: Int = 2): Float {
            val h = escrever(c, t, p, m, y, l, max)
            return h
        }
        fun centrado(t: String, p: TextPaint, max: Int = 2) {
            if (t.isEmpty()) return
            val lay = StaticLayout.Builder.obtain(t, 0, t.length, p, l.toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(max).setEllipsize(TextUtils.TruncateAt.END).build()
            if (c != null) { c.save(); c.translate(m, y); lay.draw(c); c.restore() }
            y += lay.height + 2f
        }
        centrado(d.loja.nome, tinta(13f, Color.BLACK, true))
        if (d.loja.documento.isNotBlank()) centrado(d.loja.documento, tinta(8f, CINZA), 1)
        if (d.loja.contato.isNotBlank()) centrado(d.loja.contato, tinta(8f, CINZA), 1)
        for (e in d.loja.enderecoLinhas.take(3)) centrado(e, tinta(8f, CINZA), 1)
        y += 4f
        linha(c, m, m + l, y, Color.BLACK, 0.8f, tracejada = true)
        y += 6f
        centrado("NOTINHA Nº ${d.numero}", tinta(10.5f, Color.BLACK, true), 1)
        if (d.data.isNotBlank()) centrado(d.data, tinta(8.5f, CINZA), 1)
        y += 2f
        y += centro("Cliente: ${nomeDoCliente(d)}", tinta(9f), 2) + 2f
        linha(c, m, m + l, y, Color.BLACK, 0.8f, tracejada = true)
        y += 6f
        val corpo = tinta(9f)
        val negrito = tinta(9f, Color.BLACK, true)
        for (it in d.itens) {
            y += escrever(c, it.nome, corpo, m, y, l, 3) + 1f
            val detalhe = "${it.quantidade} x ${dinheiro(it.precoCentavos)}"
            escrever(c, detalhe, tinta(8.5f, CINZA), m, y, l * 0.6f, 1)
            escrever(c, dinheiro(it.quantidade * it.precoCentavos), corpo, m + l * 0.5f, y, l * 0.5f, 1, direita = true)
            y += 14f
        }
        linha(c, m, m + l, y, Color.BLACK, 0.8f, tracejada = true)
        y += 6f
        fun par(rot: String, valor: String, p: TextPaint) {
            escrever(c, rot, p, m, y, l * 0.5f, 1)
            escrever(c, valor, p, m + l * 0.4f, y, l * 0.6f, 1, direita = true)
            y += p.textSize + 5f
        }
        par("Subtotal", dinheiro(d.subtotalCentavos), corpo)
        if (d.freteCentavos > 0) par("Frete", dinheiro(d.freteCentavos), corpo)
        if (d.descontoCentavos > 0) par("Desconto", "-" + dinheiro(d.descontoCentavos), corpo)
        par("TOTAL", dinheiro(d.totalCentavos), tinta(12f, Color.BLACK, true))
        y += 2f
        if (d.rastreio != null) { y += escrever(c, "Rastreio: ${d.rastreio}", tinta(8.5f, CINZA), m, y, l, 2) + 3f }
        linha(c, m, m + l, y, Color.BLACK, 0.8f, tracejada = true)
        y += 6f
        if (d.loja.mensagem.isNotBlank()) centrado(d.loja.mensagem, tinta(9f, Color.BLACK, true), 4)
        centrado(Documentos.AVISO_SEM_VALOR_FISCAL, tinta(7.5f, CINZA), 1)
        if (marca) centrado(MARCA, tinta(7.5f, CINZA), 1)
        y += 10f
        return y
    }

    // ------------------------------------------------------------------ etiqueta de envio (10 x 15 cm)
    private fun desenharEtiqueta(c: Canvas, d: Documentos.Dados, ctx: Context, marca: Boolean) {
        val m = 14f
        val l = ETIQ_L - 2 * m
        var y = m
        c.drawRect(RectF(4f, 4f, ETIQ_L - 4f, ETIQ_A - 4f), Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 1.6f })

        // remetente (pequeno, em cima)
        val lg = logo(ctx)
        if (lg != null) desenharLogo(c, lg, m, y, 30f)
        val xr = if (lg != null) m + 38f else m
        var yr = y
        yr += escrever(c, "REMETENTE", tinta(7f, CINZA, true), xr, yr, l - (xr - m), 1)
        yr += escrever(c, d.loja.nome, tinta(9.5f, Color.BLACK, true), xr, yr, l - (xr - m), 1)
        for (e in d.loja.enderecoLinhas.take(3)) yr += escrever(c, e, tinta(8f), xr, yr, l - (xr - m), 1)
        y = Math.max(yr, y + 32f) + 6f
        linha(c, m, m + l, y, Color.BLACK, 1.2f)
        y += 8f

        // destinatário (grande)
        c.drawRect(RectF(m, y, m + 62f, y + 14f), Paint().apply { color = Color.BLACK })
        escrever(c, "DESTINATÁRIO", tinta(7f, Color.WHITE, true), m + 4f, y + 3f, 58f, 1)
        y += 20f
        y += escrever(c, nomeDoCliente(d).uppercase(), tinta(17f, Color.BLACK, true), m, y, l, 2) + 4f
        if (d.retirada) {
            y += escrever(c, "RETIRADA NA LOJA", tinta(14f, DESTAQUE, true), m, y + 4f, l, 1) + 4f
        } else if (d.enderecoLinhas.isEmpty()) {
            y += escrever(c, "Sem endereço no pedido: toque em Completar dados para digitar.", tinta(10f, CINZA), m, y, l, 3) + 4f
        } else {
            for (e in d.enderecoLinhas) y += escrever(c, e, tinta(12.5f), m, y, l, 2) + 2f
        }
        if (d.telefone.isNotBlank()) y += escrever(c, "Tel.: ${d.telefone}", tinta(11f, CINZA), m, y + 4f, l, 1) + 4f

        // pedido, rastreio e observação, ancorados na parte de baixo
        var yb = ETIQ_A - 56f
        if (d.rastreio != null) yb -= 26f
        if (d.observacao.isNotBlank()) yb -= 30f
        yb = Math.max(yb, y + 8f)
        linha(c, m, m + l, yb, Color.BLACK, 1.2f)
        yb += 6f
        yb += escrever(c, "PEDIDO Nº ${d.numero}", tinta(15f, Color.BLACK, true), m, yb, l * 0.7f, 1)
        if (d.data.isNotBlank()) escrever(c, d.data, tinta(9f, CINZA), m + l * 0.55f, yb - 12f, l * 0.45f, 1, direita = true)
        if (d.rastreio != null) yb += escrever(c, "Rastreio: ${d.rastreio}", tinta(10f), m, yb + 2f, l, 1) + 2f
        if (d.observacao.isNotBlank()) yb += escrever(c, d.observacao, tinta(9f, CINZA), m, yb + 2f, l, 2) + 2f
        if (marca) escrever(c, MARCA, tinta(7f, CINZA), m, ETIQ_A - 20f, l, 1, direita = true)
    }
}
