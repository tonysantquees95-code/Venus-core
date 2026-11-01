package com.tonimarques.sextafeira

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.max

/**
 * Deixa interromper a Vênus enquanto ela fala: dizendo "Vênus", "para", "chega", "espera" ou "silêncio",
 * ela se cala na hora e passa a ouvir. Usa o ouvido offline (sem internet, sem bipe) e ignora as palavras
 * que ela mesma está falando, para não se interromper com a própria voz.
 */
class Interruptor(private val contexto: Context, private val aoInterromper: () -> Unit) {
    private val principal = Handler(Looper.getMainLooper())

    @Volatile
    private var geracao = 0

    @Volatile
    var ativo = false
        private set

    /** Começa a escutar enquanto ela fala [frase]. Devolve false se o ouvido offline não estiver instalado. */
    fun iniciar(frase: String): Boolean {
        parar()
        if (!ModeloDeVoz.pronto(contexto)) return false
        if (contexto.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val proibidas = palavrasDa(frase)
        // "para" sozinho nunca vale (ela fala muito); em "vênus para", o que conta é o "vênus"
        val validas = PALAVRAS.filter { p -> p.split(" ").filter { it != "para" }.none { it in proibidas } }
        if (validas.isEmpty()) return false
        ativo = true
        val minha = ++geracao
        Thread({ executar(minha, validas.toSet()) }, "venus-interruptor").start()
        return true
    }

    fun parar() {
        ativo = false
        geracao++
    }

    private fun executar(minha: Int, validas: Set<String>) {
        var gravador: AudioRecord? = null
        var reconhecedor: org.vosk.Recognizer? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            val modelo = Despertador.carregarModelo(contexto) ?: return
            if (minha != geracao) return
            val gramatica = (GRAMATICA + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }
            reconhecedor = org.vosk.Recognizer(modelo, TAXA.toFloat(), gramatica)
            val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            gravador = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, TAXA,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minimo, TAXA * 2),
            )
            if (gravador.state != AudioRecord.STATE_INITIALIZED) return
            // Cancela o eco da própria voz quando o celular oferece isso
            try {
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(gravador.audioSessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(gravador.audioSessionId)?.apply { enabled = true }
            } catch (e: Exception) {
            }
            gravador.startRecording()
            val inicio = SystemClock.elapsedRealtime()
            val bloco = ShortArray(TAXA / 10)
            var candidato: String? = null
            var repeticoes = 0
            while (minha == geracao) {
                val n = gravador.read(bloco, 0, bloco.size)
                if (n < 0) break
                if (n == 0) continue
                val terminou = reconhecedor.acceptWaveForm(bloco, n)
                // ignora o comecinho (o eco do início da fala)
                if (SystemClock.elapsedRealtime() - inicio < 500) {
                    if (terminou) reconhecedor.reset()
                    continue
                }
                val texto = campo(if (terminou) reconhecedor.result else reconhecedor.partialResult, if (terminou) "text" else "partial")
                val achada = acharPalavra(texto, validas)
                if (achada != null) {
                    if (achada == candidato) repeticoes++ else {
                        candidato = achada
                        repeticoes = 1
                    }
                    if (terminou || repeticoes >= 2) {
                        if (minha == geracao) principal.post {
                            if (minha == geracao && ativo) {
                                ativo = false
                                aoInterromper()
                            }
                        }
                        break
                    }
                } else if (terminou) {
                    candidato = null
                    repeticoes = 0
                }
            }
        } catch (e: Throwable) {
        } finally {
            try {
                gravador?.stop()
            } catch (e: Exception) {
            }
            gravador?.release()
            try {
                aec?.release()
                ns?.release()
            } catch (e: Exception) {
            }
            try {
                reconhecedor?.close()
            } catch (e: Throwable) {
            }
        }
    }

    companion object {
        private const val TAXA = 16000

        /** O que interrompe (sem acento, como o reconhecedor devolve depois de normalizado). */
        val PALAVRAS = listOf("venus", "venus para", "chega", "espera", "silencio", "pare", "para venus", "cala a boca")
        private val GRAMATICA = listOf("vênus", "vênus para", "chega", "espera", "silêncio", "pare", "para vênus", "cala a boca")

        fun palavrasDa(frase: String): Set<String> =
            ComandosLocais.normalizar(frase).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

        /** A palavra de interrupção dita (ou null). Só aceita quando é o que foi falado, não um pedaço de frase longa. */
        fun acharPalavra(texto: String, validas: Set<String>): String? {
            val t = ComandosLocais.normalizar(texto).replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")
            if (t.isBlank()) return null
            return validas.firstOrNull { t == it || t.endsWith(" $it") || t.startsWith("$it ") }
        }

        private fun campo(json: String?, nome: String): String = try {
            JSONObject(json ?: "{}").optString(nome)
        } catch (e: Exception) {
            ""
        }
    }
}
