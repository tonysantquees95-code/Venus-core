package com.tonimarques.sextafeira

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Etiquetas para colar nos produtos: um QR Code com o nome do produto embaixo, 15 por página A4 (3 x 5).
 * O QR guarda só o código do produto ([Pdv.codigoDoProduto]); o Caixa lê e soma o produto na venda.
 */
object GeradorDeEtiquetasQr {
    private const val LARGURA = 595
    private const val ALTURA = 842
    private const val COLUNAS = 3
    private const val LINHAS = 5
    private const val MARGEM = 28

    fun gerar(ctx: Context, produtos: List<Loja.Produto>): File {
        require(produtos.isNotEmpty()) { "Cadastre produtos primeiro." }
        val doc = PdfDocument()
        val celulas = Pdv.grade(LARGURA, ALTURA, COLUNAS, LINHAS, MARGEM)
        val nome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 11f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val borda = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val preto = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        produtos.chunked(celulas.size).forEachIndexed { i, grupo ->
            val pagina = doc.startPage(PdfDocument.PageInfo.Builder(LARGURA, ALTURA, i + 1).create())
            val c = pagina.canvas
            c.drawColor(Color.WHITE)
            grupo.forEachIndexed { k, p -> desenhar(c, celulas[k], p, nome, borda, preto) }
            doc.finishPage(pagina)
        }
        val (arquivo, _) = FotoProvider.novoArquivo(ctx, "etiquetas-qr.pdf")
        arquivo.outputStream().use { doc.writeTo(it) }
        doc.close()
        return arquivo
    }

    private fun desenhar(c: Canvas, cel: Pdv.Celula, p: Loja.Produto, nome: Paint, borda: Paint, preto: Paint) {
        c.drawRect(cel.x, cel.y, cel.x + cel.largura, cel.y + cel.altura, borda)
        val m = QrLocal.matriz(Pdv.codigoDoProduto(p.id))
        val reservaTexto = 34f
        val lado = minOf(cel.largura, cel.altura - reservaTexto) - 28f // folga em volta do QR (zona de silêncio)
        val modulo = lado / m.tamanho
        val x0 = cel.x + (cel.largura - lado) / 2
        val y0 = cel.y + 14f
        for (y in 0 until m.tamanho) for (x in 0 until m.tamanho) {
            if (m.modulos[y * m.tamanho + x] == '1') c.drawRect(x0 + x * modulo, y0 + y * modulo, x0 + (x + 1) * modulo, y0 + (y + 1) * modulo, preto)
        }
        val linhas = quebrar(p.nome, nome, cel.largura - 12f)
        var yTexto = y0 + lado + 14f
        for (l in linhas) { c.drawText(l, cel.x + cel.largura / 2, yTexto, nome); yTexto += 12f }
    }

    /** Nome em até 2 linhas que cabem em [max]; o que sobrar vira "…". */
    internal fun quebrar(texto: String, tinta: Paint, max: Float): List<String> {
        val palavras = texto.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val linhas = mutableListOf<String>()
        var atual = ""
        var i = 0
        while (i < palavras.size && linhas.size < 2) {
            val tenta = if (atual.isEmpty()) palavras[i] else atual + " " + palavras[i]
            if (tinta.measureText(tenta) <= max) { atual = tenta; i++ }
            else if (atual.isNotEmpty()) { linhas += atual; atual = "" }
            else { linhas += cortar(palavras[i], tinta, max); i++ }
        }
        if (atual.isNotEmpty() && linhas.size < 2) linhas += atual
        if (i < palavras.size && linhas.isNotEmpty()) linhas[linhas.size - 1] = cortar(linhas.last() + "…", tinta, max)
        return linhas
    }

    private fun cortar(t: String, tinta: Paint, max: Float): String {
        var s = t
        while (s.length > 1 && tinta.measureText(s) > max) s = s.dropLast(if (s.endsWith("…")) 2 else 1) + (if (s.endsWith("…")) "…" else "")
        return s
    }
}
