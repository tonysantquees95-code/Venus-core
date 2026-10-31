package com.tonimarques.sextafeira

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Sons curtos gerados na hora (sem arquivos): o "plim" futurista de ativação. */
object Som {

    private const val TAXA = 44_100

    /** Três notas subindo, com brilho e eco suave. Dura cerca de meio segundo. */
    fun ativacao() {
        val notas = floatArrayOf(659.25f, 987.77f, 1318.51f) // Mi, Si, Mi agudo
        val duracaoNota = 0.16f
        val passo = 0.085f
        val total = ((passo * (notas.size - 1) + duracaoNota + 0.25f) * TAXA).toInt()
        val amostras = FloatArray(total)

        notas.forEachIndexed { i, freq ->
            val inicio = (i * passo * TAXA).toInt()
            val n = ((duracaoNota + 0.25f) * TAXA).toInt()
            for (k in 0 until n) {
                val idx = inicio + k
                if (idx >= total) break
                val t = k.toFloat() / TAXA
                val ataque = (t / 0.008f).coerceAtMost(1f)
                val queda = exp(-t * 9f)
                val onda = sin(2 * PI * freq * t) + 0.35 * sin(2 * PI * freq * 2 * t) + 0.12 * sin(2 * PI * freq * 3 * t)
                amostras[idx] += (onda * ataque * queda * 0.22).toFloat()
            }
        }
        tocar(amostras)
    }

    private fun tocar(amostras: FloatArray) {
        val pcm = ShortArray(amostras.size) { (amostras[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        try {
            val faixa = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TAXA)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            faixa.write(pcm, 0, pcm.size)
            faixa.play()
            Thread {
                Thread.sleep(pcm.size * 1000L / TAXA + 200)
                faixa.release()
            }.start()
        } catch (e: Exception) {
            // Som é só enfeite: se falhar, segue sem ele.
        }
    }
}
