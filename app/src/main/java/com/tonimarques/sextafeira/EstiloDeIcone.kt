package com.tonimarques.sextafeira

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView

/**
 * Visual de um botão de ícone conforme o pacote escolhido. Usado na tela principal e na prévia da Aparência,
 * para os dois ficarem sempre iguais. O glifo do pacote Sólido é sempre escuro: assim nunca some sobre o círculo.
 */
object EstiloDeIcone {
    private val ESCURO = Color.parseColor("#06081A")

    fun aplicar(b: ImageView, pacote: Tema.Icones, cor: Int, comFundo: Boolean, apagado: Boolean, dp: (Int) -> Int) {
        b.background = null
        b.clearColorFilter()
        when (pacote) {
            Tema.Icones.VENUS -> if (comFundo) {
                b.background = GradientDrawable().apply {
                    setColor(Color.argb(20, Color.red(cor), Color.green(cor), Color.blue(cor))); setStroke(dp(1).coerceAtLeast(1), Color.argb(70, Color.red(cor), Color.green(cor), Color.blue(cor))); cornerRadius = dp(22).toFloat()
                }
                b.setColorFilter(Tema.clarear(cor and 0xFFFFFF, 0.35f) or 0xFF000000.toInt())
            } else b.setColorFilter(if (apagado) MainActivity.COR_TEXTO_APAGADO else cor)
            Tema.Icones.LINHA -> b.setColorFilter(cor)
            Tema.Icones.SOLIDO -> {
                b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cor) }
                b.setColorFilter(ESCURO)
            }
            Tema.Icones.NEON -> {
                b.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(40, Color.red(cor), Color.green(cor), Color.blue(cor)))
                    setStroke(dp(2), cor)
                }
                b.setColorFilter(Tema.clarear(cor and 0xFFFFFF, 0.55f) or 0xFF000000.toInt())
            }
            Tema.Icones.VIDRO -> {
                b.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(46, 255, 255, 255))
                    setStroke(dp(1).coerceAtLeast(1), Color.argb(90, 255, 255, 255))
                }
                b.setColorFilter(Color.WHITE)
            }
        }
    }
}
