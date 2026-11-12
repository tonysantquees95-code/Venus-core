package com.tonimarques.sextafeira

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File

/**
 * Desenha os materiais da loja direto no Canvas (sem depender de internet): logotipo, QR Code, cartão de visita
 * (frente e verso) e panfleto/encarte. Tudo sai em PNG para compartilhar ou imprimir.
 */
object Criativos {
    class Conceito(val nome: String, val slogan: String, val forma: String, val cor: Int, val estilo: String, val logo: Bitmap? = null) {
        val paleta: List<Int> get() = Paleta.harmonia(cor)
    }

    class Oferta(val nome: String, val precoCentavos: Long, val promocaoCentavos: Long?)

    private fun reduzir(abrir: (BitmapFactory.Options) -> Bitmap?, maxLado: Int): Bitmap? {
        return try {
            val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            abrir(medida)
            if (medida.outWidth <= 0 || medida.outHeight <= 0) return null
            var amostra = 1
            while (maxOf(medida.outWidth, medida.outHeight) / amostra > maxLado) amostra *= 2
            abrir(BitmapFactory.Options().apply { inSampleSize = amostra })
        } catch (e: Throwable) {
            null // imagem estragada ou grande demais: segue sem ela, sem derrubar o app
        }
    }

    /** Lê uma imagem de arquivo já reduzida (no máximo [maxLado] px) para não estourar a memória com logos enormes. */
    fun lerReduzida(arquivo: File, maxLado: Int = 1024): Bitmap? = reduzir({ BitmapFactory.decodeFile(arquivo.path, it) }, maxLado)

    fun lerReduzida(bytes: ByteArray, maxLado: Int = 1024): Bitmap? = reduzir({ BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }, maxLado)

    val FORMAS = listOf("circulo", "hexagono", "losango", "folha", "escudo")
    val ESTILOS = listOf("moderna", "serifa", "elegante")

    private fun argb(rgb: Int) = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    private fun fonte(estilo: String, negrito: Boolean): Typeface = when (estilo) {
        "serifa" -> Typeface.create(Typeface.SERIF, if (negrito) Typeface.BOLD else Typeface.NORMAL)
        "elegante" -> Typeface.create("sans-serif-light", if (negrito) Typeface.BOLD else Typeface.NORMAL)
        else -> Typeface.create("sans-serif-medium", if (negrito) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun tinta(cor: Int, tamanho: Float, tipo: Typeface, alinhamento: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb(cor); textSize = tamanho; typeface = tipo; textAlign = alinhamento
    }

    /** Texto em várias linhas com limite de linhas e reticências. Devolve a altura usada. */
    private fun bloco(c: Canvas, texto: String, x: Float, y: Float, largura: Int, tamanho: Float, cor: Int, tipo: Typeface, maxLinhas: Int = 2, centro: Boolean = false): Int {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(cor); textSize = tamanho; typeface = tipo }
        val layout = StaticLayout.Builder.obtain(texto, 0, texto.length, p, largura)
            .setAlignment(if (centro) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLinhas).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(x, y); layout.draw(c); c.restore()
        return layout.height
    }

    private fun caminhoDaForma(forma: String, cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        when (forma) {
            "hexagono" -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i - 90)
                    val x = cx + r * Math.cos(a).toFloat()
                    val y = cy + r * Math.sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "losango" -> { p.moveTo(cx, cy - r); p.lineTo(cx + r * 0.8f, cy); p.lineTo(cx, cy + r); p.lineTo(cx - r * 0.8f, cy); p.close() }
            "folha" -> { p.moveTo(cx - r, cy + r); p.quadTo(cx - r, cy - r, cx + r, cy - r); p.quadTo(cx + r, cy + r, cx - r, cy + r); p.close() }
            "escudo" -> {
                p.moveTo(cx - r * 0.85f, cy - r * 0.9f); p.lineTo(cx + r * 0.85f, cy - r * 0.9f); p.lineTo(cx + r * 0.85f, cy + r * 0.1f)
                p.quadTo(cx + r * 0.85f, cy + r * 0.75f, cx, cy + r); p.quadTo(cx - r * 0.85f, cy + r * 0.75f, cx - r * 0.85f, cy + r * 0.1f); p.close()
            }
            else -> p.addCircle(cx, cy, r, Path.Direction.CW)
        }
        return p
    }

    /** O símbolo da marca (forma com degradê e a inicial) centrado em (cx, cy). */
    private fun simbolo(c: Canvas, k: Conceito, cx: Float, cy: Float, r: Float) {
        val pal = k.paleta
        k.logo?.let { img ->
            // logo do próprio site: cabe inteiro dentro do círculo do símbolo, sem esticar
            val fator = minOf(2 * r / img.width, 2 * r / img.height)
            val l = img.width * fator
            val a = img.height * fator
            c.drawBitmap(img, null, RectF(cx - l / 2, cy - a / 2, cx + l / 2, cy + a / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return
        }
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, argb(pal[0]), argb(pal[1]), Shader.TileMode.CLAMP)
        }
        c.drawPath(caminhoDaForma(k.forma, cx, cy, r), pincel)
        val inicial = k.nome.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "V"
        val p = tinta(Paleta.textoSobre(pal[0]), r * 1.1f, fonte(k.estilo, true), Paint.Align.CENTER)
        c.drawText(inicial, cx, cy + r * 0.38f, p)
    }

    fun logo(k: Conceito, lado: Int = 800): Bitmap {
        val b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val pal = k.paleta
        c.drawColor(argb(pal[3]))
        val s = lado / 800f
        simbolo(c, k, lado / 2f, 300 * s, 190 * s)
        c.drawText(k.nome.uppercase(), lado / 2f, 600 * s, tinta(0xFFFFFF, (if (k.nome.length > 14) 56 else 74) * s, fonte(k.estilo, true), Paint.Align.CENTER).apply { letterSpacing = 0.12f })
        if (k.slogan.isNotBlank()) bloco(c, k.slogan, 80 * s, 640 * s, (lado - 160 * s).toInt(), 34 * s, pal[2], fonte(k.estilo, false), 2, true)
        return b
    }

    /** Desenha o QR a partir da matriz "0101…" (linha por linha). */
    fun qr(modulos: String, tamanho: Int, lado: Int, cor: Int, fundo: Int = 0xFFFFFF, margem: Int = 4): Bitmap {
        val b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(argb(fundo))
        val total = tamanho + margem * 2
        val passo = lado.toFloat() / total
        val p = Paint().apply { color = argb(cor); isAntiAlias = false }
        for (y in 0 until tamanho) for (x in 0 until tamanho) {
            if (modulos.getOrNull(y * tamanho + x) == '1') c.drawRect((x + margem) * passo, (y + margem) * passo, (x + margem + 1) * passo + 0.5f, (y + margem + 1) * passo + 0.5f, p)
        }
        return b
    }

    class Contato(val nome: String, val cargo: String, val telefone: String, val instagram: String, val site: String)

    fun cartaoFrente(k: Conceito, contato: Contato): Bitmap {
        val b = Bitmap.createBitmap(1050, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val pal = k.paleta
        c.drawColor(argb(pal[3]))
        c.drawRect(0f, 0f, 36f, 600f, Paint().apply { color = argb(pal[0]) })
        simbolo(c, k, 230f, 300f, 130f)
        c.drawText(k.nome.uppercase(), 420f, 280f, tinta(0xFFFFFF, 58f, fonte(k.estilo, true)).apply { letterSpacing = 0.08f })
        if (k.slogan.isNotBlank()) bloco(c, k.slogan, 420f, 310f, 560, 30f, pal[2], fonte(k.estilo, false), 2)
        return b
    }

    fun cartaoVerso(k: Conceito, contato: Contato, qrModulos: String?, qrTamanho: Int): Bitmap {
        val b = Bitmap.createBitmap(1050, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val pal = k.paleta
        c.drawColor(Color.WHITE)
        c.drawRect(0f, 0f, 1050f, 24f, Paint().apply { color = argb(pal[0]) })
        var y = 110f
        c.drawText(contato.nome, 70f, y, tinta(pal[3], 52f, fonte(k.estilo, true)))
        y += 48f
        if (contato.cargo.isNotBlank()) { c.drawText(contato.cargo, 70f, y, tinta(pal[0], 32f, fonte(k.estilo, false))); y += 80f } else y += 40f
        val linhas = listOfNotNull(
            contato.telefone.takeIf { it.isNotBlank() }?.let { "Tel/WhatsApp  $it" },
            contato.instagram.takeIf { it.isNotBlank() }?.let { "Instagram  ${if (it.startsWith("@")) it else "@$it"}" },
            contato.site.takeIf { it.isNotBlank() }?.let { "Site  ${it.removePrefix("https://")}" },
        )
        for (l in linhas) { bloco(c, l, 70f, y - 28f, 560, 30f, 0x333344, fonte("moderna", false), 1); y += 62f }
        if (qrModulos != null) {
            val q = qr(qrModulos, qrTamanho, 330, pal[3])
            c.drawBitmap(q, 660f, 150f, null)
            c.drawText("Aponte a câmera", 825f, 520f, tinta(0x666677, 26f, fonte("moderna", false), Paint.Align.CENTER))
        }
        return b
    }

    /** Panfleto/encarte A5 (1240x1748, 300 dpi) com as ofertas, QR da vitrine e contato. */
    fun panfleto(k: Conceito, titulo: String, ofertas: List<Oferta>, rodape: String, qrModulos: String?, qrTamanho: Int): Bitmap {
        val b = Bitmap.createBitmap(1240, 1748, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val pal = k.paleta
        c.drawColor(argb(pal[3]))
        val faixa = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 1240f, 0f, argb(pal[0]), argb(pal[1]), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 1240f, 300f, faixa)
        c.drawText(k.nome.uppercase(), 620f, 130f, tinta(Paleta.textoSobre(pal[0]), 60f, fonte(k.estilo, true), Paint.Align.CENTER).apply { letterSpacing = 0.1f })
        bloco(c, titulo, 80f, 160f, 1080, 78f, Paleta.textoSobre(pal[0]), fonte(k.estilo, true), 1, true)
        var y = 360f
        val cartao = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
        val nomeFonte = fonte("moderna", false)
        for (o in ofertas.take(6)) {
            c.drawRoundRect(RectF(70f, y, 1170f, y + 150f), 28f, 28f, cartao)
            bloco(c, o.nome, 110f, y + 36f, 640, 40f, 0xFFFFFF, nomeFonte, 2)
            val promo = o.promocaoCentavos != null && o.promocaoCentavos < o.precoCentavos
            val preco = Nuvem.reais(if (promo) o.promocaoCentavos!! else o.precoCentavos)
            c.drawText(preco, 1130f, y + 92f, tinta(pal[0], 58f, fonte("moderna", true), Paint.Align.RIGHT))
            if (promo) {
                val de = tinta(pal[2], 30f, nomeFonte, Paint.Align.RIGHT).apply { flags = flags or Paint.STRIKE_THRU_TEXT_FLAG }
                c.drawText(Nuvem.reais(o.precoCentavos), 1130f, y + 130f, de)
            }
            y += 172f
        }
        if (ofertas.isEmpty()) bloco(c, "Novidades chegando. Fale com a gente!", 80f, 480f, 1080, 52f, 0xFFFFFF, nomeFonte, 3, true)
        val baseY = 1400f
        c.drawRect(0f, baseY - 20f, 1240f, 1748f, Paint().apply { color = Color.argb(60, 0, 0, 0) })
        if (qrModulos != null) c.drawBitmap(qr(qrModulos, qrTamanho, 260, pal[3]), 90f, baseY + 20f, null)
        val ox = if (qrModulos != null) 390f else 90f
        bloco(c, "Aponte a câmera e veja a vitrine", ox, baseY + 40f, 760, 38f, pal[2], nomeFonte, 2)
        bloco(c, rodape, ox, baseY + 130f, 760, 36f, 0xFFFFFF, fonte("moderna", true), 3)
        return b
    }

    /** Salva em PNG na pasta compartilhável do app e devolve o endereço para enviar a outro app. */
    fun salvar(ctx: Context, b: Bitmap, nome: String): Uri {
        val limpo = nome.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        val (arquivo, uri) = FotoProvider.novoArquivo(ctx, "$limpo.png")
        File(arquivo.parent!!).mkdirs()
        arquivo.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }
}
