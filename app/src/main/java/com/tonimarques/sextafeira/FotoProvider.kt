package com.tonimarques.sextafeira

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Entrega à câmera um arquivo do próprio app para salvar a foto do produto
 * (o Android exige um endereço "content://" para isso).
 */
class FotoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw IllegalStateException()
        val nome = uri.lastPathSegment?.takeIf { Regex("^[a-z0-9_-]+\\.(?:jpg|png|pdf)$").matches(it) } ?: throw SecurityException("arquivo inválido")
        val arquivo = File(pasta(ctx), nome)
        return ParcelFileDescriptor.open(arquivo, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        else -> "image/jpeg"
    }
    /** Nome e tamanho do arquivo: o WhatsApp e outros apps pedem antes de anexar um documento. */
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val ctx = context ?: return null
        val nome = uri.lastPathSegment?.takeIf { Regex("^[a-z0-9_-]+\\.(?:jpg|png|pdf)$").matches(it) } ?: return null
        val arquivo = File(pasta(ctx), nome)
        val colunas = p?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(colunas).apply { addRow(colunas.map { if (it == OpenableColumns.SIZE) arquivo.length() else nome }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTORIDADE = "com.tonimarques.sextafeira.fotos"

        fun pasta(ctx: Context) = File(ctx.cacheDir, "fotos").apply { mkdirs() }

        /** Um arquivo novo para a câmera escrever, e o endereço dele. */
        fun novaFoto(ctx: Context): Pair<File, Uri> {
            pasta(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
            val nome = "produto_${System.currentTimeMillis()}.jpg"
            return File(pasta(ctx), nome) to Uri.parse("content://$AUTORIDADE/$nome")
        }

        /** Um arquivo novo com o nome dado (ex.: "relatorio_123.pdf"), e o endereço dele para compartilhar. */
        fun novoArquivo(ctx: Context, nome: String): Pair<File, Uri> {
            pasta(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
            return File(pasta(ctx), nome) to Uri.parse("content://$AUTORIDADE/$nome")
        }

        /** Lê a foto (da câmera ou da galeria), gira para a posição certa e reduz para no máximo 1200 px em JPEG. */
        fun prepararParaEnviar(ctx: Context, origem: Uri, ladoMaximo: Int = 1200): ByteArray? = try {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(origem)?.use { BitmapFactory.decodeStream(it, null, limites) }
            var amostra = 1
            while (maxOf(limites.outWidth, limites.outHeight) / (amostra * 2) >= ladoMaximo) amostra *= 2
            val bruto = ctx.contentResolver.openInputStream(origem)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = amostra })
            } ?: throw IllegalStateException("foto ilegível")
            val giro = ctx.contentResolver.openInputStream(origem)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val escala = minOf(1f, ladoMaximo.toFloat() / maxOf(bruto.width, bruto.height))
            val m = Matrix().apply {
                postScale(escala, escala)
                postRotate(giro)
            }
            val final = Bitmap.createBitmap(bruto, 0, 0, bruto.width, bruto.height, m, true)
            ByteArrayOutputStream().use { out ->
                final.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }
}
