package com.tonimarques.sextafeira

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * As ideias de post que a Vênus estuda toda semana, prontas para virar post ou story programado:
 * ela acha o produto citado no catálogo, baixa a foto e monta a arte (feed 4:5 ou story 9:16) com a cara da loja.
 */
object IdeiasDePost {

    data class Ideia(val id: Long, val titulo: String, val data: Calendar?, val formato: String, val legenda: String)

    /** "Fantasia e Diversão (2026-12-14, feed)" + legenda → Ideia. */
    fun ler(item: Memoria.Item): Ideia {
        val m = Regex("^(.*?)\\s*\\(([^)]*)\\)\\s*$").find(item.texto)
        val titulo = (m?.groupValues?.get(1) ?: item.texto).trim()
        val detalhe = m?.groupValues?.get(2).orEmpty()
        val formato = Regex("\\b(feed|story|stories|reels?)\\b", RegexOption.IGNORE_CASE).find(detalhe)?.value?.lowercase()
            ?.let { if (it.startsWith("stor")) "story" else if (it.startsWith("reel")) "reels" else "feed" } ?: "feed"
        return Ideia(item.id, titulo, data(detalhe), formato, item.resposta.orEmpty())
    }

    /** "2026-12-14", "14/12", "14/12/2026" → data (null se não tiver). */
    fun data(t: String, hoje: Calendar = Calendar.getInstance()): Calendar? {
        Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(t)?.let { m ->
            return Calendar.getInstance().apply { clear(); set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt()) }
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(t)?.let { m ->
            val ano = m.groupValues[3].takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: hoje.get(Calendar.YEAR)
            return Calendar.getInstance().apply { clear(); set(ano, m.groupValues[2].toInt() - 1, m.groupValues[1].toInt()) }
        }
        return null
    }

    /** Quando programar: a data sugerida às 19h (feed) ou 12h (story); se já passou ou não tem, amanhã. */
    fun quandoSugerido(ideia: Ideia, tipo: String, agora: Long = System.currentTimeMillis()): Long {
        val hora = if (tipo == "story") 12 else 19
        val c = (ideia.data?.clone() as? Calendar) ?: Calendar.getInstance().apply { timeInMillis = agora; add(Calendar.DAY_OF_YEAR, 1) }
        c.set(Calendar.HOUR_OF_DAY, hora)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= agora + 10 * 60_000L) {
            c.timeInMillis = agora
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, hora)
            c.set(Calendar.MINUTE, 0)
        }
        return c.timeInMillis
    }

    /** O produto do catálogo citado na ideia (o nome mais longo que aparece no título ou na legenda). */
    fun produtoDaIdeia(ideia: Ideia, produtos: List<Loja.Produto>): Loja.Produto? {
        val texto = ComandosLocais.normalizar(ideia.titulo + " " + ideia.legenda)
        return produtos.filter { it.ativo }
            .filter { p ->
                val nome = ComandosLocais.normalizar(p.nome)
                nome.length >= 4 && (texto.contains(nome) || palavras(nome).let { ws -> ws.size >= 2 && ws.take(3).all { texto.contains(it) } })
            }
            .maxByOrNull { it.nome.length }
    }

    private fun palavras(t: String) = t.split(' ').filter { it.length >= 4 }

    /** Foto do produto (do site da loja), ou null. Bloqueante. */
    fun fotoDoProduto(p: Loja.Produto): Bitmap? {
        val b = p.bruto ?: return null
        val url = b.optString("imageUrl").ifBlank { b.optString("image") }.ifBlank {
            b.optJSONArray("images")?.let { a -> a.optJSONObject(0)?.optString("src") ?: a.optString(0) }.orEmpty()
        }.ifBlank { return null }
        if (url.startsWith("file://")) return BitmapFactory.decodeFile(url.removePrefix("file://"))
        return try {
            val c = URL(Loja.enderecoCompleto(url)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.inputStream.use { BitmapFactory.decodeStream(it) }.also { c.disconnect() }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ arte
    private val ROSA = Color.parseColor("#FF2E97")
    private val LILAS = Color.parseColor("#9B5CF6")

    /** Arte do post: feed 1080×1350 ou story 1080×1920, com a foto do produto (ou só o fundo da marca) e o título. */
    fun arte(ideia: Ideia, tipo: String, foto: Bitmap?, variacao: Int = 0): ByteArray {
        val w = 1080
        val h = if (tipo == "story") 1920 else 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tela = Canvas(bmp)
        val suave = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // fundo da marca: escuro com brilho rosa/lilás (a variação muda o lado do brilho)
        val paleta = listOf(intArrayOf(Color.parseColor("#1A0B24"), Color.BLACK), intArrayOf(Color.parseColor("#120A26"), Color.parseColor("#05040D")))[variacao % 2]
        tela.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), paleta[0], paleta[1], Shader.TileMode.CLAMP) })
        val brilhoX = if (variacao % 2 == 0) w * 0.85f else w * 0.15f
        tela.drawCircle(brilhoX, h * 0.18f, w * 0.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(brilhoX, h * 0.18f, w * 0.7f, Color.argb(90, 255, 46, 151), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        val margem = 70f
        // foto do produto num cartão arredondado
        val areaFoto = if (tipo == "story") RectF(margem, 260f, w - margem, 260f + (w - 2 * margem) * 1.15f) else RectF(margem, 120f, w - margem, 120f + (w - 2 * margem) * 0.78f)
        if (foto != null) {
            val cobre = maxOf(areaFoto.width() / foto.width, areaFoto.height() / foto.height)
            val fw = foto.width * cobre
            val fh = foto.height * cobre
            tela.save()
            tela.clipPath(android.graphics.Path().apply { addRoundRect(areaFoto, 48f, 48f, android.graphics.Path.Direction.CW) })
            tela.drawBitmap(foto, null, RectF(areaFoto.centerX() - fw / 2, areaFoto.centerY() - fh / 2, areaFoto.centerX() + fw / 2, areaFoto.centerY() + fh / 2), suave)
            tela.restore()
            tela.drawRoundRect(areaFoto, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                shader = LinearGradient(areaFoto.left, areaFoto.top, areaFoto.right, areaFoto.bottom, ROSA, LILAS, Shader.TileMode.CLAMP)
            })
        }
        // título e chamada
        val largura = (w - 2 * margem).toInt()
        val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (ideia.titulo.length > 28) 74f else 88f
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        val lTitulo = StaticLayout.Builder.obtain(ideia.titulo, 0, ideia.titulo.length, pTitulo, largura)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        val topoTexto = if (foto != null) areaFoto.bottom + 70f else h * 0.36f
        tela.save()
        tela.translate(margem, topoTexto)
        lTitulo.draw(tela)
        tela.restore()
        val chamada = if (tipo == "story") "Link na bio  ✦  ${Perfil.loja}" else Perfil.loja
        val pChamada = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f
            textAlign = Paint.Align.CENTER
            color = Color.parseColor("#F3C6EC")
            letterSpacing = 0.12f
        }
        val yChamada = topoTexto + lTitulo.height + 90f
        val larguraPilula = pChamada.measureText(chamada) + 90f
        tela.drawRoundRect(RectF(w / 2f - larguraPilula / 2, yChamada - 58f, w / 2f + larguraPilula / 2, yChamada + 24f), 42f, 42f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, ROSA, LILAS, Shader.TileMode.CLAMP)
        })
        tela.drawText(chamada, w / 2f, yChamada, pChamada)
        val saida = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, saida)
        bmp.recycle()
        return saida.toByteArray()
    }
}
