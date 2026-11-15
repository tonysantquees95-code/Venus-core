package com.tonimarques.sextafeira

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Os três botõezinhos embaixo de cada resposta da Vênus: ouvir (com pausa), compartilhar e guardar.
 * Seguem o visual do app (vidro com a cor de destaque do tema instalado).
 */
internal const val ROTULO_OUVIR = "Ouvir"
internal const val ROTULO_PAUSAR = "Pausar"
internal const val ROTULO_CONTINUAR = "Continuar"

/** Só as respostas de verdade ganham os botões (saudações e frases curtinhas não). */
internal fun MainActivity.merecemControles(texto: String) = texto.trim().length >= 20

internal fun MainActivity.colocarControles(balao: TextView) {
    val pai = balao.parent as? LinearLayout ?: return
    (balao.tag as? View)?.let { pai.removeView(it) }
    fun botao(rotulo: String, aoTocar: (TextView) -> Unit) = TextView(this).apply {
        text = rotulo
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(comAlfa(corAtual, 230))
        background = vidro(comAlfa(corAtual, 22), comAlfa(corAtual, 90), 14)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        isClickable = true
        contentDescription = rotulo
        setOnClickListener { aoTocar(this) }
    }
    val linha = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val espaco = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        addView(botao(ROTULO_OUVIR) { b -> alternarLeitura(balao.text.toString(), b) }, espaco)
        addView(botao("Compartilhar") { compartilharResposta(balao.text.toString()) }, espaco)
        addView(botao("Guardar") { guardarResposta(balao.text.toString()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.START
        topMargin = dp(4)
        leftMargin = dp(4)
    }
    pai.addView(linha, pai.indexOfChild(balao) + 1, params)
    balao.tag = linha
}

internal fun MainActivity.textoParaOuvir(texto: String) =
    texto.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+—\\s+(?=\\n|$)"), "").trim()

/** Ouvir → Pausar → Continuar. A voz neural (arquivo) pausa de verdade; a do celular para e recomeça do início. */
internal fun MainActivity.alternarLeitura(texto: String, botao: TextView) {
    val mesma = botaoDeLeitura === botao
    if (mesma && falando) {
        val mp = tocador
        if (mp != null) {
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    botao.text = ROTULO_CONTINUAR
                } else {
                    mp.start()
                    botao.text = ROTULO_PAUSAR
                }
            } catch (e: Exception) {
                encerrarLeitura()
            }
        } else {
            encerrarLeitura()
        }
        return
    }
    if (falando) pararFala()
    encerrarLeitura()
    val limpo = textoParaOuvir(texto)
    if (limpo.isBlank()) return
    botaoDeLeitura = botao
    botao.text = ROTULO_PAUSAR
    falar(limpo, forcar = true)
}

/** Volta o botão para "Ouvir" e solta a fala que ele controlava. */
internal fun MainActivity.encerrarLeitura() {
    val b = botaoDeLeitura ?: return
    botaoDeLeitura = null
    b.text = ROTULO_OUVIR
    if (falando) {
        pararFala()
        falando = false
        mudarEstadoOcioso()
    }
}

internal fun MainActivity.compartilharResposta(texto: String) {
    val envio = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto.trim())
    try {
        startActivity(Intent.createChooser(envio, "Compartilhar resposta").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(this, "Não consegui abrir o compartilhamento.", Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.guardarResposta(texto: String) {
    val onde = Guardados.guardar(this, texto.trim())
    Toast.makeText(
        this,
        if (onde != null) "Texto guardado em $onde." else "Não consegui guardar o texto.",
        Toast.LENGTH_LONG,
    ).show()
    Diario.anotar(this, "resposta", if (onde != null) "Guardei uma resposta em $onde" else "Não consegui guardar uma resposta", "botão", onde != null)
}
