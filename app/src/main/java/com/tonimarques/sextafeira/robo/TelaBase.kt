package com.tonimarques.sextafeira.robo

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** As peças visuais das telas do robô (fundo escuro, cartões, textos, campos e botões). */
abstract class TelaBase : Activity() {

    /** A cor de destaque escolhida na Aparência da Vênus (o robô segue o mesmo tema). */
    protected val destaque: Int get() = Aparencia.destaque
    protected val apagado = Color.parseColor("#8E87A8")
    protected lateinit var coluna: LinearLayout

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Começa a tela do zero: uma coluna dentro de uma rolagem, com o fundo do robô. */
    protected fun novaTela() {
        cartaoAtual = null
        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }
        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0D0A1F"), Color.parseColor("#03040A")))
            addView(coluna)
        })
    }

    // ------------------------------------------------------------------ peças da tela
    protected var cartaoAtual: LinearLayout? = null

    protected fun cartao(titulo: String) {
        coluna.addView(TextView(this).apply {
            text = titulo.uppercase()
            textSize = 11f
            letterSpacing = 0.25f
            setTextColor(destaque)
            setPadding(dp(6), dp(20), 0, dp(8))
        })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(150, 24, 19, 44))
                setStroke(dp(1), Color.parseColor("#2C2546"))
            }
        }
        coluna.addView(c)
        cartaoAtual = c
    }

    protected fun adicionar(v: View) {
        (cartaoAtual ?: coluna).addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    protected fun texto(t: String, tamanho: Float, cor: Int) = TextView(this).apply {
        text = t
        textSize = tamanho
        setTextColor(cor)
        setPadding(dp(2), 0, dp(2), 0)
    }

    protected fun campo(valor: String, variacao: Int) = EditText(this).apply {
        setText(valor)
        inputType = InputType.TYPE_CLASS_TEXT or variacao
        textSize = 14.5f
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#120E22"))
            setStroke(dp(1), Color.parseColor("#2C2546"))
        }
    }

    protected fun botao(t: String, primario: Boolean, aoTocar: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setTextColor(if (primario) Color.WHITE else destaque)
        background = if (primario) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(destaque, Aparencia.par())).apply { cornerRadius = dp(26).toFloat() }
        else GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), destaque)
        }
        setOnClickListener { aoTocar() }
    }
}
