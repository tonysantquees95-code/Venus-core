package com.tonimarques.sextafeira

import android.content.SharedPreferences
import android.graphics.Color

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
}
