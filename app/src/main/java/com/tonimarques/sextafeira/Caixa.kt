package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * Janelinhas de pergunta/confirmação com o visual atual da Vênus: cor escolhida pela pessoa (título, borda, botões),
 * cantos e vidro escuro iguais aos cartões das telas de Ajustes. Troque `AlertDialog.Builder` por `CaixaBuilder`.
 */
internal open class CaixaBuilder(contexto: Context) : AlertDialog.Builder(contexto) {
    override fun create(): AlertDialog = super.create().also { d -> d.setOnShowListener { Caixa.estilizar(d) } }

    override fun show(): AlertDialog {
        val d = create()
        d.show()
        Caixa.estilizar(d)
        return d
    }
}

internal object Caixa {
    private val TEXTO = Color.parseColor("#D7DAEE")

    /** Cor de texto legível sobre [fundo] (claro vira texto escuro). */
    fun textoSobre(fundo: Int): Int {
        val luz = (0.299 * Color.red(fundo) + 0.587 * Color.green(fundo) + 0.114 * Color.blue(fundo)) / 255.0
        return if (luz > 0.62) Color.parseColor("#12101C") else Color.WHITE
    }

    fun estilizar(d: AlertDialog) {
        try {
            val ctx = d.context
            val dens = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * dens).toInt()
            val destaque = Aparencia.destaque or 0xFF000000.toInt()

            d.window?.apply {
                setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
                    setColor(Color.parseColor("#F2090D1F"))
                    setStroke(dp(1).coerceAtLeast(1), Aparencia.comAlfa(95))
                    cornerRadius = dp(20).toFloat()
                }, dp(18), dp(24), dp(18), dp(24)))
                setDimAmount(0.62f)
            }

            val idTitulo = ctx.resources.getIdentifier("alertTitle", "id", "android")
            (if (idTitulo != 0) d.findViewById<TextView>(idTitulo) else null)?.apply {
                setTextColor(destaque); textSize = 15f; letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            d.findViewById<TextView>(android.R.id.message)?.setTextColor(TEXTO)

            for (qual in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                val b: Button = d.getButton(qual) ?: continue
                val cheio = qual == AlertDialog.BUTTON_POSITIVE
                b.isAllCaps = false
                b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                b.minHeight = dp(40); b.minimumHeight = dp(40); b.minWidth = dp(72); b.minimumWidth = dp(72)
                b.setPadding(dp(16), 0, dp(16), 0)
                b.stateListAnimator = null
                if (cheio) {
                    b.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(destaque, Aparencia.par())).apply { cornerRadius = dp(20).toFloat() }
                    b.setTextColor(textoSobre(destaque))
                } else {
                    b.background = GradientDrawable().apply {
                        setColor(Color.argb(22, 255, 255, 255)); setStroke(dp(1).coerceAtLeast(1), Color.argb(40, 255, 255, 255)); cornerRadius = dp(20).toFloat()
                    }
                    b.setTextColor(TEXTO)
                }
                (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = dp(6); it.marginEnd = dp(2); b.layoutParams = it }
            }

            Aparencia.pintar(d.window?.decorView)
            d.window?.decorView?.let { pintarCampos(it, destaque) }
        } catch (e: Exception) {
            // o visual é só enfeite: se algo falhar, a janela continua com o estilo padrão
        }
    }

    private fun pintarCampos(v: android.view.View, destaque: Int) {
        if (v is android.widget.EditText) {
            v.setTextColor(Color.WHITE); v.setHintTextColor(Color.parseColor("#8A93B8"))
            v.highlightColor = Aparencia.comAlfa(90)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) pintarCampos(v.getChildAt(i), destaque)
    }
}
