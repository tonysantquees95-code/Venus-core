package com.tonimarques.sextafeira

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Aparência da Vênus: modelo do orbe, pacote de ícones e a cor (paleta). Tudo é lógica pura e guardada
 * em SharedPreferences, para a tela aplicar na hora e para os testes rodarem sem o Android.
 * Cor 0 = automática (as cores oscilam em torno dos tons da Vênus, como sempre foi).
 */
object Tema {
    const val PREF_MODELO = "tema_modelo"
    const val PREF_ICONES = "tema_icones"
    const val PREF_COR = "tema_cor"

    enum class Modelo(val rotulo: String) { LIQUIDO("Líquido"), CRISTAL("Cristal"), PULSO("Pulso"), NEURAL("Neural"), HOLOGRAMA("Holograma") }
    enum class Icones(val rotulo: String) { VENUS("Vênus"), LINHA("Linha"), SOLIDO("Sólido"), NEON("Neon"), VIDRO("Vidro") }

    class Predefinida(val nome: String, val cor: Int)

    /** Cores prontas (0xRRGGBB, sem transparência). A primeira, 0, é "automática". */
    val PREDEFINIDAS = listOf(
        Predefinida("Automática", 0),
        Predefinida("Magenta", 0xE36BD0),
        Predefinida("Ciano", 0x36D6FF),
        Predefinida("Dourado", 0xF5B93A),
        Predefinida("Esmeralda", 0x2ECC8F),
        Predefinida("Rubi", 0xE5384F),
        Predefinida("Violeta", 0x8F6BFF),
    )

    fun modelo(p: SharedPreferences): Modelo = Modelo.values().getOrElse(p.getInt(PREF_MODELO, 0)) { Modelo.LIQUIDO }
    fun icones(p: SharedPreferences): Icones = Icones.values().getOrElse(p.getInt(PREF_ICONES, 0)) { Icones.VENUS }
    fun cor(p: SharedPreferences): Int = p.getInt(PREF_COR, 0) and 0xFFFFFF

    fun definirModelo(p: SharedPreferences, m: Modelo) = p.edit().putInt(PREF_MODELO, m.ordinal).apply()
    fun definirIcones(p: SharedPreferences, i: Icones) = p.edit().putInt(PREF_ICONES, i.ordinal).apply()
    fun definirCor(p: SharedPreferences, rgb: Int) = p.edit().putInt(PREF_COR, rgb and 0xFFFFFF).apply()

    // ------------------------------------------------------------ cores (sem android.graphics, para testar)
    fun vermelho(rgb: Int) = (rgb shr 16) and 0xFF
    fun verde(rgb: Int) = (rgb shr 8) and 0xFF
    fun azul(rgb: Int) = rgb and 0xFF
    fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** "#E36BD0" ou "E36BD0" (também "#ABC") → 0xE36BD0; null se não for uma cor. */
    fun lerHex(texto: String): Int? {
        var t = texto.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return t.toInt(16)
    }

    fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    /** Matiz (0..360), saturação e brilho (0..1). */
    fun hsv(rgb: Int): FloatArray {
        val r = vermelho(rgb) / 255f
        val g = verde(rgb) / 255f
        val b = azul(rgb) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    fun deHsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = vv - c
        val (r, g, b) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f))
    }

    /** Mistura com branco (quanto 0..1). */
    fun clarear(rgb: Int, quanto: Float): Int = rgb(
        (vermelho(rgb) + (255 - vermelho(rgb)) * quanto).toInt(),
        (verde(rgb) + (255 - verde(rgb)) * quanto).toInt(),
        (azul(rgb) + (255 - azul(rgb)) * quanto).toInt(),
    )

    /** As três cores do orbe a partir de uma cor escolhida: ela mesma e dois vizinhos no círculo de cores. */
    fun trio(rgb: Int): IntArray {
        val (h, s, v) = hsv(rgb).let { Triple(it[0], it[1], it[2]) }
        val sat = max(s, 0.35f)
        val br = max(v, 0.7f)
        return intArrayOf(deHsv(h, sat, br), deHsv(h + 28f, sat, br), deHsv(h + 56f, sat * 0.9f, br))
    }

    /** Verdadeiro se a cor é escura demais para texto sobre fundo preto. */
    fun escuraDemais(rgb: Int): Boolean = hsv(rgb)[2] < 0.35f
}
