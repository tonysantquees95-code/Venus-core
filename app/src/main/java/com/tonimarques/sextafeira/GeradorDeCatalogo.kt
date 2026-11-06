package com.tonimarques.sextafeira

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File

/**
 * Catálogo da loja em PDF (A4), para mandar no WhatsApp, no Direct ou imprimir: capa com o logo, o nome da loja
 * e as formas de pagamento e entrega; depois os produtos em cartões (foto, nome, descrição, preço, preço no Pix,
 * promoção), agrupados por categoria. Usa os produtos ativos com preço; [comEsgotados] decide se os sem estoque entram.
 */
object GeradorDeCatalogo {

    private const val L = 595f // A4 em pontos
    private const val A = 842f
    private const val MARGEM = 36f
    private val ESCURO = Color.parseColor("#1D1622")
    private val APAGADO = Color.parseColor("#6E6474")
    private val DESTAQUE = Color.parseColor("#C2399F")
    private val FUNDO_CARTAO = Color.parseColor("#F7F2F7")

    /** O logo escolhido no Catálogo da loja (o mesmo dos stories). */
    fun arquivoDoLogo(ctx: Context) = File(ctx.filesDir, "logo_loja.png")

    fun guardarLogo(ctx: Context, bytes: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("Não consegui ler essa imagem.")
        arquivoDoLogo(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Os produtos que entram, na ordem do catálogo (categoria, depois nome). */
    fun produtosDoCatalogo(produtos: List<Loja.Produto>, comEsgotados: Boolean): List<Loja.Produto> =
        produtos.filter { it.ativo && it.precoCentavos != null && (comEsgotados || it.estoque == null || it.estoque > 0) }
            .sortedWith(compareBy({ categoria(it).lowercase() }, { it.nome.lowercase() }))

    fun categoria(p: Loja.Produto): String = p.bruto?.optString("category")?.takeIf { it.isNotBlank() && it != "null" } ?: "Produtos"

    /** O nome da categoria para a cliente ler: "acessorios_bdsm" vira "Acessórios BDSM". */
    fun nomeDaCategoria(c: String): String =
        Loja.CATEGORIAS.firstOrNull { it.first == c }?.second
            ?: c.replace(Regex("[_-]+"), " ").trim().replaceFirstChar { it.uppercase() }

    /** Monta o PDF e devolve o arquivo (na pasta compartilhável do app). Bloqueante. */
    fun gerar(ctx: Context, produtos: List<Loja.Produto>, comEsgotados: Boolean = false): File {
        val lista = produtosDoCatalogo(produtos, comEsgotados)
        require(lista.isNotEmpty()) { "Nenhum produto ativo com preço para o catálogo. Cadastre os preços primeiro." }
        // as fotos do site vêm pela internet: baixa várias ao mesmo tempo, já reduzidas
        val trabalho = java.util.concurrent.Executors.newFixedThreadPool(6)
        val fotos = try {
            lista.map { p -> trabalho.submit<Bitmap?> { try { foto(p) } catch (e: Exception) { null } } }.map {
                try { it.get(30, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null }
            }
        } finally {
            trabalho.shutdownNow()
        }
        val doc = PdfDocument()
        var numero = 0
        fun novaPagina(): PdfDocument.Page = doc.startPage(PdfDocument.PageInfo.Builder(L.toInt(), A.toInt(), ++numero).create())

        // ---- capa
        var pagina = novaPagina()
        capa(ctx, pagina.canvas, lista.size)
        doc.finishPage(pagina)

        // ---- produtos: 2 colunas × 3 linhas por página
        val colunas = 2
        val linhas = 3
        val topo = 70f
        val largura = (L - 2 * MARGEM - 16f) / colunas
        val altura = (A - topo - 50f - 2 * 14f) / linhas
        var i = 0
        var categoriaAtual = ""
        pagina = novaPagina()
        cabecalho(pagina.canvas, numero)
        var slot = 0
        for (p in lista) {
            if (slot == colunas * linhas) {
                doc.finishPage(pagina)
                pagina = novaPagina()
                cabecalho(pagina.canvas, numero)
                slot = 0
            }
            val cat = categoria(p)
            val coluna = slot % colunas
            val linha = slot / colunas
            val x = MARGEM + coluna * (largura + 16f)
            val y = topo + linha * (altura + 14f)
            cartao(pagina.canvas, p, fotos[i], RectF(x, y, x + largura, y + altura), if (cat != categoriaAtual) cat else null)
            categoriaAtual = cat
            slot++
            i++
        }
        doc.finishPage(pagina)

        // o FotoProvider só entrega nomes em minúsculas, sem acento (senão o WhatsApp e o leitor de PDF recebem nada)
        val nome = "catalogo-" + ComandosLocais.normalizar(Perfil.loja).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "loja" } + ".pdf"
        val (arquivo, _) = FotoProvider.novoArquivo(ctx, nome)
        arquivo.outputStream().use { doc.writeTo(it) }
        doc.close()
        return arquivo
    }

    private fun texto(tamanho: Float, cor: Int, negrito: Boolean = false, serifa: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho
        color = cor
        typeface = Typeface.create(if (serifa) Typeface.SERIF else Typeface.SANS_SERIF, if (negrito) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun escrever(c: Canvas, t: String, p: TextPaint, x: Float, y: Float, largura: Float, maxLinhas: Int, centro: Boolean = false): Float {
        val l = StaticLayout.Builder.obtain(t, 0, t.length, p, largura.toInt())
            .setAlignment(if (centro) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLinhas).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(x, y)
        l.draw(c)
        c.restore()
        return l.height.toFloat()
    }

    private fun capa(ctx: Context, c: Canvas, quantos: Int) {
        c.drawColor(Color.WHITE)
        val faixa = Paint().apply { color = ESCURO }
        c.drawRect(0f, 0f, L, A * 0.46f, faixa)
        var y = 90f
        val logo = arquivoDoLogo(ctx).takeIf { it.exists() }?.let { reduzir(it.readBytes(), 450, quadrada = false) }
        if (logo != null) {
            val lado = 150f
            val escala = minOf(lado / logo.width, lado / logo.height)
            val w = logo.width * escala
            val h = logo.height * escala
            c.drawBitmap(logo, null, RectF(L / 2 - w / 2, y, L / 2 + w / 2, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 26f
        }
        y += escrever(c, Perfil.loja, texto(30f, Color.WHITE, negrito = true, serifa = true), MARGEM, y, L - 2 * MARGEM, 2, centro = true) + 8f
        escrever(c, "Catálogo · ${Perfil.ramo}", texto(13f, Color.parseColor("#E9CFE6")), MARGEM, y, L - 2 * MARGEM, 1, centro = true)

        var y2 = A * 0.46f + 40f
        val titulo = texto(12f, DESTAQUE, negrito = true)
        val corpo = texto(11.5f, ESCURO)
        val regras = Perfil.regras
        val blocos = listOfNotNull(
            "COMO COMPRAR" to "Escolha os produtos e chame a gente no WhatsApp ou no Direct: separamos o seu pedido e combinamos a entrega.",
            if (Perfil.regrasConfiguradas) "PAGAMENTO" to Perfil.regras.texto().substringAfter("pagamento: ").substringBefore(";").replaceFirstChar { it.uppercase() } + "." else null,
            if (Perfil.regrasConfiguradas) "ENTREGA" to (regras.texto().split("; ").filter { it.startsWith("entrega") || it.startsWith("retirada") }
                .joinToString(". ") { it.replaceFirstChar { ch -> ch.uppercase() } } + ".") else null,
            if (Perfil.site.isNotBlank() && !Perfil.site.contains("sua loja")) "SITE" to Perfil.site else null,
        )
        for ((t, v) in blocos) {
            c.drawText(t, MARGEM, y2, titulo)
            y2 += 8f
            y2 += escrever(c, v, corpo, MARGEM, y2, L - 2 * MARGEM, 4) + 22f
        }
        val rodape = texto(10f, APAGADO)
        escrever(c, "$quantos produtos · preços válidos até a próxima atualização do catálogo", rodape, MARGEM, A - 50f, L - 2 * MARGEM, 1, centro = true)
    }

    private fun cabecalho(c: Canvas, numero: Int) {
        c.drawColor(Color.WHITE)
        c.drawText(Perfil.loja, MARGEM, 42f, texto(14f, ESCURO, negrito = true, serifa = true))
        val p = texto(10f, APAGADO).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("Catálogo · página $numero", L - MARGEM, 42f, p)
        c.drawRect(MARGEM, 52f, L - MARGEM, 53f, Paint().apply { color = DESTAQUE })
    }

    private fun cartao(c: Canvas, p: Loja.Produto, bmp: Bitmap?, r: RectF, categoria: String?) {
        c.drawRoundRect(r, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FUNDO_CARTAO })
        val pad = 10f
        // foto quadrada, recortada para preencher
        val lado = minOf(r.width() - 2 * pad, r.height() * 0.48f)
        val foto = RectF(r.centerX() - lado / 2, r.top + pad, r.centerX() + lado / 2, r.top + pad + lado)
        if (bmp != null) {
            val escala = maxOf(foto.width() / bmp.width, foto.height() / bmp.height)
            val w = bmp.width * escala
            val h = bmp.height * escala
            c.save()
            c.clipPath(Path().apply { addRoundRect(foto, 8f, 8f, Path.Direction.CW) })
            c.drawBitmap(bmp, null, RectF(foto.centerX() - w / 2, foto.centerY() - h / 2, foto.centerX() + w / 2, foto.centerY() + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            bmp.recycle()
        } else {
            c.drawRoundRect(foto, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E9DDE8") })
            escrever(c, Perfil.loja, texto(11f, APAGADO), foto.left, foto.centerY() - 8f, foto.width(), 1, centro = true)
        }
        if (categoria != null) {
            val etiqueta = texto(8.5f, Color.WHITE, negrito = true)
            val t = nomeDaCategoria(categoria).uppercase()
            val w = etiqueta.measureText(t) + 14f
            c.drawRoundRect(RectF(foto.left + 6f, foto.top + 6f, foto.left + 6f + w, foto.top + 22f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DESTAQUE })
            c.drawText(t, foto.left + 13f, foto.top + 17.5f, etiqueta)
        }
        var y = foto.bottom + 8f
        val largura = r.width() - 2 * pad
        y += escrever(c, p.nome, texto(11.5f, ESCURO, negrito = true), r.left + pad, y, largura, 2) + 3f
        // preços no pé do cartão; a descrição só ocupa o espaço que sobra entre o nome e o preço (não encavala)
        val preco = p.precoCentavos
        val pix = preco?.let { Perfil.regras.precoNoPix(it, emPromocao = p.precoAntigo != null) }
        val comPix = preco != null && pix != null && pix < preco
        val base = r.bottom - pad
        val topoDoPreco = base - (if (comPix) 13f else 0f) - 16f
        val desc = p.bruto?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }
        val miuda = texto(8.8f, APAGADO)
        val cabem = ((topoDoPreco - y) / miuda.fontSpacing).toInt().coerceAtMost(3)
        if (desc != null && cabem >= 1) escrever(c, desc, miuda, r.left + pad, y, largura, cabem)
        if (preco == null) return
        val grande = texto(14f, DESTAQUE, negrito = true)
        c.drawText(Loja.dinheiro(preco), r.left + pad, base - (if (comPix) 13f else 0f), grande)
        p.precoAntigo?.let { antigo ->
            val riscado = texto(9.5f, APAGADO).apply { isStrikeThruText = true }
            c.drawText(Loja.dinheiro(antigo), r.left + pad + grande.measureText(Loja.dinheiro(preco)) + 6f, base - (if (comPix) 13f else 0f), riscado)
        }
        if (comPix) c.drawText("${Loja.dinheiro(pix)} no Pix", r.left + pad, base, texto(9.5f, ESCURO))
        if (p.estoque != null && p.estoque <= 0) {
            val t = texto(9f, Color.WHITE, negrito = true).apply { textAlign = Paint.Align.RIGHT }
            c.drawRoundRect(RectF(r.right - pad - 62f, base - 12f, r.right - pad, base + 3f), 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = APAGADO })
            c.drawText("ESGOTADO", r.right - pad - 7f, base - 1.5f, t)
        }
    }

    /** A foto do produto, quadrada e pequena (o PDF guarda a imagem sem compressão JPEG: 768px por foto dava 33 MB). */
    private fun foto(p: Loja.Produto): Bitmap? {
        val url = p.bruto?.optString("imageUrl")?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        // foto no site (Floot, Shopify…): baixa; no catálogo da Vênus: lê do celular. Nos dois casos, reduzida
        val bytes = if (url.startsWith("file://")) java.io.File(url.removePrefix("file://")).takeIf { it.exists() }?.readBytes()
        else {
            val c = java.net.URL(Loja.enderecoCompleto(url)).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            try {
                if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
            } finally {
                c.disconnect()
            }
        } ?: return null
        return reduzir(bytes, 320, quadrada = true)
    }

    /** Decodifica já reduzida: corta no meio para ficar quadrada (se pedido) e deixa o lado maior com até [maximo] px. */
    internal fun reduzir(bytes: ByteArray, maximo: Int, quadrada: Boolean): Bitmap? {
        val so = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, so)
        if (so.outWidth <= 0 || so.outHeight <= 0) return null
        var amostra = 1
        while (minOf(so.outWidth, so.outHeight) / (amostra * 2) >= maximo) amostra *= 2
        val bruta = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
        val corte = if (quadrada && bruta.width != bruta.height) {
            val lado = minOf(bruta.width, bruta.height)
            Bitmap.createBitmap(bruta, (bruta.width - lado) / 2, (bruta.height - lado) / 2, lado, lado)
        } else bruta
        val escala = maximo.toFloat() / maxOf(corte.width, corte.height)
        val final = if (escala < 1f) Bitmap.createScaledBitmap(corte, maxOf(1, (corte.width * escala).toInt()), maxOf(1, (corte.height * escala).toInt()), true) else corte
        if (corte !== final) corte.recycle()
        if (bruta !== final && bruta !== corte) bruta.recycle()
        return final
    }
}
