package com.tonimarques.sextafeira

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.sin

/*
 * Os três pontinhos pulando na conversa enquanto a Vênus pensa (como o "digitando…" do WhatsApp).
 * Aparece sozinho quando o orbe entra em PENSANDO e some quando chega a resposta ou ela volta a ouvir/falar/descansar.
 */

/** Mostra o balão de pensando no fim da conversa (se já estiver na tela, só desce para o fim). */
internal fun MainActivity.mostrarPensando() {
    if (!conversaPronta()) return
    balaoPensando?.let { v ->
        if (v.parent === mensagens) {
            if (mensagens.getChildAt(mensagens.childCount - 1) !== v) {
                mensagens.removeView(v)
                mensagens.addView(v)
            }
            rolarParaBaixo()
            return
        }
    }
    val bolha = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = vidro(Color.argb(14, 255, 255, 255), Color.argb(34, 255, 255, 255), 18)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        contentDescription = "${Marca.nome} está pensando"
    }
    val cor = corAtual
    val pontos = (0 until 3).map {
        View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cor)
            }
            alpha = 0.35f
        }
    }
    pontos.forEachIndexed { i, p ->
        bolha.addView(p, LinearLayout.LayoutParams(dp(8), dp(8)).apply { if (i > 0) marginStart = dp(7) })
    }
    val salto = dp(5).toFloat()
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { a ->
            val t = a.animatedValue as Float
            pontos.forEachIndexed { i, p ->
                // cada pontinho pula um pouco depois do anterior
                val fase = (t - i * 0.18f + 1f) % 1f
                val s = if (fase < 0.42f) sin(fase / 0.42f * PI).toFloat() else 0f
                p.translationY = -salto * s
                p.alpha = 0.35f + 0.65f * s
            }
        }
        start()
    }
    bolha.tag = anim
    mensagens.addView(bolha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.START
        topMargin = dp(8)
    })
    balaoPensando = bolha
    rolarParaBaixo()
}

internal fun MainActivity.esconderPensando() {
    val v = balaoPensando ?: return
    (v.tag as? ValueAnimator)?.cancel()
    (v.parent as? ViewGroup)?.removeView(v)
    balaoPensando = null
}
