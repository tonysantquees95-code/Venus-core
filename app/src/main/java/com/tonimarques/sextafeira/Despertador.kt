package com.tonimarques.sextafeira

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Espera "Vênus, ativar" com o reconhecimento de voz offline (sem internet e sem o Google).
 *
 * O microfone fica aberto o tempo todo, numa sessão só: na barra de status aparece apenas o
 * pontinho verde fixo, sem o ícone do microfone piscando a cada poucos segundos.
 * Para gastar pouca bateria, o áudio só é analisado quando há som parecido com voz.
 */
class Despertador(
    private val contexto: Context,
    private val aoOuvir: () -> Unit,
    private val aoFalhar: () -> Unit = {},
) {
    private val principal = Handler(Looper.getMainLooper())

    @Volatile
    private var geracao = 0

    @Volatile
    var ativo = false
        private set

    /** Começa a escutar. Devolve false se a voz offline não estiver instalada ou faltar permissão. */
    fun iniciar(): Boolean {
        if (ativo) return true
        if (!ModeloDeVoz.pronto(contexto)) return false
        if (contexto.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        ativo = true
        val minha = ++geracao
        Thread({ executar(minha) }, "venus-despertador").start()
        return true
    }

    /** Para de escutar e libera o microfone (em até ~0,1 s). */
    fun parar() {
        geracao++
        ativo = false
    }

    private fun executar(minha: Int) {
        var gravador: AudioRecord? = null
        var reconhecedor: Recognizer? = null
        var ouviu = false
        var falhou = false
        try {
            val modelo = carregarModelo(contexto)
            if (modelo == null) {
                falhou = true
                return
            }
            if (minha != geracao) return
            reconhecedor = Recognizer(modelo, TAXA.toFloat())
            val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            gravador = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, TAXA,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minimo, TAXA * 2)
            )
            if (gravador.state != AudioRecord.STATE_INITIALIZED) {
                falhou = true
                return
            }
            gravador.startRecording()

            val bloco = ShortArray(TAXA / 10) // 100 ms
            val anteriores = ArrayDeque<ShortArray>()
            var piso = 200.0
            var vozAte = 0L
            var analisando = false
            while (minha == geracao) {
                val n = gravador.read(bloco, 0, bloco.size)
                if (n < 0) {
                    falhou = true
                    break
                }
                if (n == 0) continue
                val energia = energia(bloco, n)
                val agora = SystemClock.elapsedRealtime()
                val temVoz = energia > max(piso * 2.2, LIMIAR_MINIMO)
                if (temVoz) vozAte = agora + 1200 else piso = piso * 0.97 + energia * 0.03

                if (agora < vozAte) {
                    if (!analisando) {
                        // Inclui o pedacinho antes da voz, para não perder o começo do "Vênus".
                        for (a in anteriores) reconhecedor.acceptWaveForm(a, a.size)
                        analisando = true
                    }
                    val terminou = reconhecedor.acceptWaveForm(bloco, n)
                    val texto = if (terminou) campo(reconhecedor.result, "text") else campo(reconhecedor.partialResult, "partial")
                    if (Cerebro.pareceAtivacao(texto)) {
                        ouviu = true
                        break
                    }
                } else if (analisando) {
                    analisando = false
                    if (Cerebro.pareceAtivacao(campo(reconhecedor.finalResult, "text"))) {
                        ouviu = true
                        break
                    }
                }
                anteriores.addLast(bloco.copyOf(n))
                if (anteriores.size > 4) anteriores.removeFirst()
            }
        } catch (e: Throwable) {
            falhou = true
        } finally {
            try {
                gravador?.stop()
            } catch (e: Exception) {
            }
            gravador?.release()
            try {
                reconhecedor?.close()
            } catch (e: Throwable) {
            }
            if (minha == geracao) {
                ativo = false
                when {
                    ouviu -> principal.post(aoOuvir)
                    falhou -> principal.post(aoFalhar)
                }
            }
        }
    }

    companion object {
        private const val TAXA = 16000
        private const val LIMIAR_MINIMO = 180.0

        private val trava = Any()

        @Volatile
        private var modelo: Model? = null

        /** O modelo é pesado: carrega uma vez só e reaproveita (app, pop-up e serviço). */
        fun carregarModelo(contexto: Context): Model? {
            modelo?.let { return it }
            synchronized(trava) {
                modelo?.let { return it }
                if (!ModeloDeVoz.pronto(contexto)) return null
                return try {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    Model(ModeloDeVoz.pasta(contexto).absolutePath).also { modelo = it }
                } catch (e: Throwable) {
                    null
                }
            }
        }

        fun liberarModelo() {
            synchronized(trava) {
                try {
                    modelo?.close()
                } catch (e: Throwable) {
                }
                modelo = null
            }
        }

        private fun campo(json: String?, nome: String): String = try {
            JSONObject(json ?: "{}").optString(nome)
        } catch (e: Exception) {
            ""
        }

        private fun energia(bloco: ShortArray, n: Int): Double {
            var soma = 0.0
            for (i in 0 until n) {
                val v = bloco[i].toDouble()
                soma += v * v
            }
            return sqrt(soma / n)
        }
    }
}
