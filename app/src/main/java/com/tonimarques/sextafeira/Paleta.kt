package com.tonimarques.sextafeira

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cores de uma marca: extrai as cores dominantes de uma imagem (quantização em caixas de cor, "median cut")
 * e devolve os códigos em Hex para usar nas postagens e na identidade da loja.
 */
object Paleta {
    fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    fun lerHex(texto: String): Int? = Tema.lerHex(texto)

    private fun luminancia(rgb: Int): Double {
        fun canal(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * canal(Tema.vermelho(rgb)) + 0.7152 * canal(Tema.verde(rgb)) + 0.0722 * canal(Tema.azul(rgb))
    }

    /** Razão de contraste WCAG entre duas cores (1 a 21). */
    fun contraste(a: Int, b: Int): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Branco ou quase preto, o que ler melhor sobre a cor. */
    fun textoSobre(fundo: Int): Int = if (contraste(fundo, 0xFFFFFF) >= contraste(fundo, 0x06081A)) 0xFFFFFF else 0x06081A

    /** As [quantas] cores mais presentes em [pixels] (ARGB ou RGB), ignorando pixels transparentes; das mais frequentes para as menos. */
    fun extrair(pixels: IntArray, quantas: Int = 5): List<Int> {
        val uteis = ArrayList<Int>(pixels.size)
        for (p in pixels) if ((p ushr 24) >= 128) uteis += p and 0xFFFFFF
        if (uteis.isEmpty() || quantas <= 0) return emptyList()
        var caixas = mutableListOf<List<Int>>(uteis)
        while (caixas.size < quantas) {
            val maior = caixas.filter { it.size > 1 && amplitude(it).first > 0 }.maxByOrNull { it.size } ?: break
            caixas.remove(maior)
            val canal = amplitude(maior).second
            val ordenada = maior.sortedBy { canalDe(it, canal) }
            val meio = ordenada.size / 2
            caixas.add(ordenada.subList(0, meio))
            caixas.add(ordenada.subList(meio, ordenada.size))
        }
        val medias = caixas.sortedByDescending { it.size }.map { media(it) }
        // tira cores quase iguais (distância pequena) para a paleta não repetir o mesmo tom
        val saida = mutableListOf<Int>()
        for (c in medias) if (saida.none { distancia(it, c) < 28 }) saida += c
        return saida
    }

    private fun canalDe(rgb: Int, canal: Int) = when (canal) { 0 -> Tema.vermelho(rgb); 1 -> Tema.verde(rgb); else -> Tema.azul(rgb) }

    /** (maior amplitude, canal em que ela ocorre). */
    private fun amplitude(cores: List<Int>): Pair<Int, Int> {
        var melhor = 0 to 0
        for (canal in 0..2) {
            var mn = 255
            var mx = 0
            for (c in cores) {
                val v = canalDe(c, canal)
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mx - mn > melhor.first) melhor = (mx - mn) to canal
        }
        return melhor
    }

    private fun media(cores: List<Int>): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (c in cores) { r += Tema.vermelho(c); g += Tema.verde(c); b += Tema.azul(c) }
        val n = cores.size
        return Tema.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun distancia(a: Int, b: Int) =
        abs(Tema.vermelho(a) - Tema.vermelho(b)) + abs(Tema.verde(a) - Tema.verde(b)) + abs(Tema.azul(a) - Tema.azul(b))

    /**
     * Quatro cores que combinam a partir de uma principal: ela, uma análoga, uma complementar suave e um fundo escuro.
     * Usado quando a IA está fora do ar e para o logotipo sempre ter paleta.
     */
    fun harmonia(base: Int): List<Int> {
        val h = Tema.hsv(base)
        val s = max(h[1], 0.45f)
        val v = max(h[2], 0.75f)
        return listOf(
            Tema.deHsv(h[0], s, v),
            Tema.deHsv(h[0] + 30f, s * 0.9f, v),
            Tema.deHsv(h[0] + 180f, s * 0.6f, v),
            Tema.deHsv(h[0], min(0.6f, s), 0.14f),
        )
    }

    /** Matiz estável a partir do nome da marca (o mesmo nome dá sempre o mesmo conceito, sem IA). */
    fun corDoNome(nome: String): Int {
        var h = 7
        for (c in nome.lowercase()) h = h * 31 + c.code
        return Tema.deHsv((abs(h) % 360).toFloat(), 0.62f, 0.9f)
    }
}
