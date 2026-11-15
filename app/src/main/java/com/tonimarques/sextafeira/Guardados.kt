package com.tonimarques.sextafeira

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Textos que a pessoa guarda das respostas da Vênus ("Guardar" embaixo da resposta).
 * Viram um arquivo de texto na pasta Download › Vênus (no Android 10+ sem pedir permissão),
 * para a pessoa achar depois no app Arquivos.
 */
object Guardados {
    /** Nome do arquivo a partir das primeiras palavras do texto: "2026-11-15 1430 - como fazer um bolo.txt". */
    fun nomeDoArquivo(texto: String, agora: Long = System.currentTimeMillis()): String {
        val data = SimpleDateFormat("yyyy-MM-dd HHmm", Locale("pt", "BR")).format(Date(agora))
        val inicio = texto.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(6).joinToString(" ").lowercase(Locale("pt", "BR")).take(40).trim()
        return if (inicio.isBlank()) "$data.txt" else "$data - $inicio.txt"
    }

    /** Guarda o texto e devolve onde ficou (em palavras para a pessoa), ou null se não conseguiu. */
    fun guardar(contexto: Context, texto: String): String? {
        val nome = nomeDoArquivo(texto)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, nome)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vênus")
                }
                val uri = contexto.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores) ?: return null
                contexto.contentResolver.openOutputStream(uri)?.use { it.write(texto.toByteArray(Charsets.UTF_8)) } ?: return null
                "Download › Vênus"
            } else {
                val pasta = File(contexto.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Vênus").apply { mkdirs() }
                File(pasta, nome).writeText(texto)
                "a pasta Documentos do app Vênus"
            }
        } catch (e: Exception) {
            null
        }
    }
}
