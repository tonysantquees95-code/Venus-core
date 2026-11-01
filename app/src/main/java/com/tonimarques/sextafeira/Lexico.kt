package com.tonimarques.sextafeira

import android.content.Context
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * O dicionário da Vênus: cerca de 367 mil palavras do português (verbetes, conjugações, plurais e formas
 * do dia a dia), guardadas no próprio app, sem internet. Serve para:
 *  - nunca "corrigir" uma palavra que existe ("charada" não vira "chamada");
 *  - responder se uma palavra existe e sugerir a escrita certa ("dacueba" → não existe).
 *
 * Fontes: léxico e conjugações de fserb/pt-br (MIT) e frequência de uso do OpenSubtitles (FrequencyWords, CC BY-SA 4.0).
 * As palavras ficam sem acento, como a Vênus compara tudo.
 */
object Lexico {

    @Volatile
    private var dados: ByteArray? = null
    private var inicio = IntArray(0)
    private var fim = IntArray(0)
    private var nivel = ByteArray(0)

    @Volatile
    private var carregando = false

    val pronto get() = dados != null
    val tamanho get() = inicio.size

    /** Carrega do app em segundo plano (umas centenas de milissegundos, uma vez só). */
    fun iniciar(ctx: Context) {
        if (pronto || carregando) return
        carregando = true
        val app = ctx.applicationContext
        Thread({
            try {
                app.assets.open("palavras.txt.gz").use { carregar(GZIPInputStream(it)) }
            } catch (e: Exception) {
            } finally {
                carregando = false
            }
        }, "venus-lexico").start()
    }

    /** Lê a lista (uma palavra por linha, já em ordem; "palavra\tfrequência" para as usadas no dia a dia). */
    fun carregar(entrada: InputStream) {
        val bytes = entrada.readBytes()
        var linhas = 0
        for (b in bytes) if (b == '\n'.code.toByte()) linhas++
        val ini = IntArray(linhas)
        val fi = IntArray(linhas)
        val niv = ByteArray(linhas)
        var n = 0
        var comeco = 0
        var i = 0
        while (i < bytes.size && n < linhas) {
            if (bytes[i] == '\n'.code.toByte()) {
                var fimPalavra = i
                var f = 0
                for (k in comeco until i) if (bytes[k] == '\t'.code.toByte()) {
                    fimPalavra = k
                    f = bytes[k + 1] - '0'.code.toByte()
                    break
                }
                if (fimPalavra > comeco) {
                    ini[n] = comeco
                    fi[n] = fimPalavra
                    niv[n] = f.toByte()
                    n++
                }
                comeco = i + 1
            }
            i++
        }
        inicio = ini.copyOf(n)
        fim = fi.copyOf(n)
        nivel = niv.copyOf(n)
        dados = bytes
    }

    private fun comparar(i: Int, palavra: ByteArray): Int {
        val d = dados!!
        val a = inicio[i]
        val tamA = fim[i] - a
        val lim = minOf(tamA, palavra.size)
        for (k in 0 until lim) {
            val x = d[a + k].toInt() and 0xff
            val y = palavra[k].toInt() and 0xff
            if (x != y) return x - y
        }
        return tamA - palavra.size
    }

    private fun indice(palavra: String): Int {
        if (dados == null || palavra.isEmpty()) return -1
        val p = palavra.toByteArray(Charsets.US_ASCII)
        var lo = 0
        var hi = inicio.size - 1
        while (lo <= hi) {
            val meio = (lo + hi) ushr 1
            val c = comparar(meio, p)
            when {
                c < 0 -> lo = meio + 1
                c > 0 -> hi = meio - 1
                else -> return meio
            }
        }
        return -1
    }

    private fun chave(palavra: String) = ComandosLocais.normalizar(palavra.trim()).filter { it in 'a'..'z' || it == '-' }

    /** A palavra existe no português? (sem diferenciar acentos e maiúsculas) */
    fun existe(palavra: String): Boolean = indice(chave(palavra)) >= 0

    /** 0 = rara ou só de dicionário … 9 = muito usada no dia a dia; -1 = não existe. */
    fun frequencia(palavra: String): Int = indice(chave(palavra)).let { if (it < 0) -1 else nivel[it].toInt() }

    /** Palavras que existem e diferem por uma letra (trocada, faltando, sobrando ou invertida), as mais usadas primeiro. */
    fun sugestoes(palavra: String, maximo: Int = 3): List<String> {
        val p = chave(palavra)
        if (p.length < 3 || !pronto) return emptyList()
        val letras = "abcdefghijklmnopqrstuvwxyz"
        val candidatas = HashSet<String>()
        for (i in 0..p.length) {
            if (i < p.length) candidatas += p.removeRange(i, i + 1)
            if (i < p.length - 1) candidatas += p.substring(0, i) + p[i + 1] + p[i] + p.substring(i + 2)
            for (l in letras) {
                if (i < p.length) candidatas += p.substring(0, i) + l + p.substring(i + 1)
                candidatas += p.substring(0, i) + l + p.substring(i)
            }
        }
        candidatas -= p
        return candidatas.mapNotNull { c -> indice(c).takeIf { it >= 0 }?.let { c to nivel[it] } }
            .sortedByDescending { it.second }.take(maximo).map { it.first }
    }

    /** "Dacueba existe?" → resposta falada. */
    fun responderExiste(palavra: String): String {
        val p = palavra.trim().trim('"', '“', '”', '\'', '?', '.')
        if (!pronto) return "Meu dicionário ainda está carregando. Tenta de novo em um segundo."
        if (existe(p)) {
            val f = frequencia(p)
            val uso = when {
                f >= 6 -> "e é bem usada no dia a dia"
                f >= 3 -> "e é usada normalmente"
                else -> "mas é pouco usada no dia a dia"
            }
            return "Existe sim: \"$p\" é uma palavra do português $uso. Se quiser o significado, diga: o que significa $p."
        }
        val s = sugestoes(p)
        return "Não encontrei \"$p\" no dicionário do português." +
            (if (s.isNotEmpty()) " Você quis dizer: ${s.joinToString(", ")}?" else " Pode ser gíria, nome próprio ou palavra inventada.")
    }
}
