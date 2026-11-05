package com.tonimarques.sextafeira

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

/*
 * O que eu aprendi: ver e apagar o que a Vênus aprendeu.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

internal fun MainActivity.mostrarAprendizado() {
    Thread {
        val contagem = try {
            memoria.contarVetores()
        } catch (e: Exception) {
            emptyMap()
        }
        runOnUiThread {
            if (contagem.isEmpty()) return@runOnUiThread responder("Minha memória ainda está vazia.")
            val tipos = contagem.entries.sortedByDescending { it.value.first }.map { it.key }
            val total = contagem.values.sumOf { it.first }
            AlertDialog.Builder(this)
                .setTitle("O que eu aprendi ($total)")
                .setItems(tipos.map { "${NOMES_DO_APRENDIZADO[it] ?: it}: ${contagem[it]?.first ?: 0}" }.toTypedArray()) { _, i -> listarAprendizado(tipos[i]) }
                .setNegativeButton("Fechar", null)
                .show()
        }
    }.start()
}

internal fun MainActivity.listarAprendizado(tipo: String) {
    Thread {
        val itens = try {
            memoria.itensDoTipo(tipo, 60)
        } catch (e: Exception) {
            emptyList()
        }
        runOnUiThread {
            if (itens.isEmpty()) return@runOnUiThread Toast.makeText(this, "Nada aqui.", Toast.LENGTH_SHORT).show()
            fun linha(x: Memoria.Item) = x.texto.take(90) + when {
                !x.resposta.isNullOrBlank() -> "\n→ " + x.resposta.take(90)
                !x.acao.isNullOrBlank() -> "\n→ ${x.acao}"
                else -> ""
            }
            AlertDialog.Builder(this)
                .setTitle("${NOMES_DO_APRENDIZADO[tipo] ?: tipo} (últimos ${itens.size})")
                .setItems(itens.map { linha(it) }.toTypedArray()) { _, i -> verAprendizado(itens[i]) }
                .setPositiveButton("Voltar") { _, _ -> mostrarAprendizado() }
                .setNegativeButton("Fechar", null)
                .show()
        }
    }.start()
}

internal fun MainActivity.verAprendizado(item: Memoria.Item) {
    val texto = item.texto + (item.resposta?.takeIf { it.isNotBlank() }?.let { "\n\nResposta: $it" } ?: "") +
        (item.acao?.takeIf { it.isNotBlank() }?.let { "\n\nAção: $it ${item.args.orEmpty()}" } ?: "") +
        (item.fonte?.takeIf { it.isNotBlank() }?.let { "\n\nDe onde veio: $it" } ?: "")
    AlertDialog.Builder(this)
        .setTitle(NOMES_DO_APRENDIZADO[item.tipo] ?: item.tipo)
        .setMessage(texto)
        .setPositiveButton("Voltar") { _, _ -> listarAprendizado(item.tipo) }
        .setNegativeButton("Apagar") { _, _ ->
            Thread {
                val ok = try {
                    memoria.apagarItem(item.id)
                } catch (e: Exception) {
                    false
                }
                if (ok) Diario.anotar(this, "memoria", "Aprendizado apagado (${NOMES_DO_APRENDIZADO[item.tipo] ?: item.tipo}): ${item.texto.take(80)}", "tela")
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Apagado. Não uso mais isso." else "Não consegui apagar.", Toast.LENGTH_SHORT).show()
                    listarAprendizado(item.tipo)
                }
            }.start()
        }
        .show()
}
