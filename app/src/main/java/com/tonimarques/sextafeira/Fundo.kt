package com.tonimarques.sextafeira

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Troca o fundo da foto do produto: recorta o produto no próprio celular (ML Kit, sem internet depois
 * do primeiro uso) e coloca num fundo padrão da loja. Roda fora da thread principal.
 */
object Fundo {

    enum class Estilo(val nome: String) {
        LOJA("Fundo da loja (escuro com brilho rosa)"),
        BRANCO("Branco"),
        ROSA("Rosa claro"),
    }

    class ErroDeFundo(mensagem: String) : Exception(mensagem)

    /** Recorta o produto e devolve a foto pronta (JPEG quadrado de 1200 px). */
    fun trocar(jpeg: ByteArray, estilo: Estilo): ByteArray {
        val original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw ErroDeFundo("Não consegui abrir a foto.")
        val recorte = recortar(original)
        return compor(recorte, estilo)
    }

    private fun recortar(foto: Bitmap): Bitmap {
        val opcoes = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        val segmentador = SubjectSegmentation.getClient(opcoes)
        try {
            val resultado = try {
                Tasks.await(segmentador.process(InputImage.fromBitmap(foto, 0)), 60, TimeUnit.SECONDS)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                throw ErroDeFundo(
                    if ("download" in msg.lowercase() || "module" in msg.lowercase())
                        "O recorte de fotos ainda está sendo baixado pelo Google. Tente de novo em um ou dois minutos, com internet."
                    else "Não consegui recortar o produto dessa foto."
                )
            }
            return resultado.foregroundBitmap ?: throw ErroDeFundo("Não achei um produto na foto. Tente uma foto com o produto inteiro e bem visível.")
        } finally {
            segmentador.close()
        }
    }

    /** Monta o produto recortado, centralizado, sobre o fundo escolhido. */
    fun compor(recorte: Bitmap, estilo: Estilo, lado: Int = 1200): ByteArray {
        val caixa = limites(recorte) ?: throw ErroDeFundo("Não achei um produto na foto.")
        val saida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val c = Canvas(saida)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // fundo
        when (estilo) {
            Estilo.LOJA -> {
                p.shader = LinearGradient(0f, 0f, 0f, lado.toFloat(), Color.parseColor("#1A0B22"), Color.parseColor("#06040A"), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, lado.toFloat(), lado.toFloat(), p)
                p.shader = RadialGradient(lado / 2f, lado * 0.46f, lado * 0.48f,
                    intArrayOf(Color.argb(150, 227, 107, 208), Color.argb(50, 176, 79, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, lado.toFloat(), lado.toFloat(), p)
            }
            Estilo.BRANCO -> c.drawColor(Color.WHITE)
            Estilo.ROSA -> {
                p.shader = LinearGradient(0f, 0f, 0f, lado.toFloat(), Color.parseColor("#FFF1F8"), Color.parseColor("#F9D3E8"), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, lado.toFloat(), lado.toFloat(), p)
            }
        }
        p.shader = null

        // produto ocupando ~78% do quadro
        val escala = (lado * 0.78f) / maxOf(caixa.width(), caixa.height())
        val w = caixa.width() * escala
        val h = caixa.height() * escala
        val destino = RectF((lado - w) / 2f, (lado - h) / 2f, (lado + w) / 2f, (lado + h) / 2f)

        // sombra suave embaixo
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (estilo == Estilo.LOJA) Color.argb(150, 0, 0, 0) else Color.argb(55, 60, 30, 50)
            maskFilter = BlurMaskFilter(lado * 0.03f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawOval(RectF(destino.left + w * 0.12f, destino.bottom - lado * 0.02f, destino.right - w * 0.12f, destino.bottom + lado * 0.035f), sombra)

        val origem = android.graphics.Rect(caixa.left.toInt(), caixa.top.toInt(), caixa.right.toInt(), caixa.bottom.toInt())
        c.drawBitmap(recorte, origem, destino, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        return ByteArrayOutputStream().use { out ->
            saida.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    /** O retângulo onde o recorte não é transparente. */
    private fun limites(b: Bitmap): RectF? {
        val w = b.width
        val h = b.height
        val linha = IntArray(w)
        var esq = w
        var dir = -1
        var topo = h
        var base = -1
        for (y in 0 until h) {
            b.getPixels(linha, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((linha[x] ushr 24) > 24) {
                    if (x < esq) esq = x
                    if (x > dir) dir = x
                    if (y < topo) topo = y
                    if (y > base) base = y
                }
            }
        }
        if (dir < esq || base < topo) return null
        return RectF(esq.toFloat(), topo.toFloat(), (dir + 1).toFloat(), (base + 1).toFloat())
    }
}
