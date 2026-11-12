package com.tonimarques.sextafeira

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch

/**
 * A aparência escolhida valendo para o app inteiro: cor de destaque, modelo do orbe e pacote de ícones.
 * A tela principal atualiza [destaque] a cada mudança de cor do orbe; as outras telas (ajustes, formulários,
 * gaveta, popup) só leem daqui, assim tudo na Vênus segue o mesmo estilo.
 */
object Aparencia {
    @Volatile var destaque: Int = Color.parseColor("#E36BD0")
    @Volatile var pacote: Tema.Icones = Tema.Icones.VENUS

    fun definir(p: SharedPreferences, corAtual: Int) {
        destaque = corAtual or 0xFF000000.toInt()
        pacote = Tema.icones(p)
    }

    /** Segunda cor do degradê dos botões cheios: a de destaque deslocada no círculo de cores. */
    fun par(): Int {
        val h = Tema.hsv(destaque and 0xFFFFFF)
        return 0xFF000000.toInt() or Tema.deHsv(h[0] + 40f, h[1] * 0.9f, h[2])
    }

    fun comAlfa(alfa: Int): Int = Color.argb(alfa, Color.red(destaque), Color.green(destaque), Color.blue(destaque))

    /**
     * Pinta com a cor escolhida os controles nativos do Android (interruptor, caixa de marcar, opção redonda, barra).
     * Sem isso eles usam a cor fixa do tema do app (roxo), mesmo depois de a pessoa escolher outra.
     * Pode ser chamada com uma tela inteira: percorre tudo que estiver dentro.
     */
    fun pintar(v: View?) {
        if (v == null) return
        val ligado = destaque or 0xFF000000.toInt()
        val apagado = Color.parseColor("#9A93B5")
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val marca = ColorStateList(estados, intArrayOf(ligado, apagado))
        when (v) {
            is Switch -> {
                v.thumbTintList = marca
                v.trackTintList = ColorStateList(estados, intArrayOf(comAlfa(120), Color.parseColor("#2A2440")))
            }
            is CheckBox -> v.buttonTintList = marca
            is RadioButton -> v.buttonTintList = marca
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(ligado)
                v.thumbTintList = ColorStateList.valueOf(ligado)
            }
            is ProgressBar -> v.indeterminateTintList = ColorStateList.valueOf(ligado)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) pintar(v.getChildAt(i))
    }
}
