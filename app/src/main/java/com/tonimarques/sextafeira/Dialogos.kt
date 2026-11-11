package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** Pequenos atalhos para as telas da Central da loja: rede em segundo plano, perguntas e cópia. */

/** Roda [trabalho] fora da tela e entrega o resultado (ou o erro) de volta na tela. */
internal fun <T> MainActivity.emFundo(trabalho: () -> T, pronto: (Result<T>) -> Unit) {
    Thread {
        val r = try { Result.success(trabalho()) } catch (e: Exception) { Result.failure(e) }
        Handler(Looper.getMainLooper()).post { if (!isFinishing && !isDestroyed) pronto(r) }
    }.start()
}

internal fun MainActivity.aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

internal fun Throwable.mensagemAmigavel(): String = message?.takeIf { it.isNotBlank() } ?: "Algo deu errado. Tente de novo."

internal fun MainActivity.copiar(rotulo: String, texto: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText(rotulo, texto))
    aviso("Copiado!")
}

/** Uma caixa com vários campos de texto. [campos] são (rótulo, valor inicial). */
internal fun MainActivity.perguntar(
    titulo: String,
    campos: List<Pair<String, String>>,
    ok: String = "Ok",
    numerico: Boolean = false,
    resposta: (List<String>) -> Unit,
) {
    val coluna = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val entradas = campos.map { (rotulo, inicial) ->
        EditText(this).apply {
            hint = rotulo
            setText(inicial)
            inputType = if (numerico) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            setSingleLine()
            coluna.addView(this)
        }
    }
    AlertDialog.Builder(this).setTitle(titulo).setView(coluna)
        .setPositiveButton(ok) { _, _ -> resposta(entradas.map { it.text.toString().trim() }) }
        .setNegativeButton("Cancelar", null).show()
}

/** Lista de opções; cada uma tem texto e ação. */
internal fun MainActivity.escolher(titulo: String, opcoes: List<Pair<String, () -> Unit>>) {
    AlertDialog.Builder(this).setTitle(titulo)
        .setItems(opcoes.map { it.first }.toTypedArray()) { _, i -> opcoes[i].second() }
        .setNegativeButton("Fechar", null).show()
}
