package com.tonimarques.sextafeira

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
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
 *
 * Educada com os outros apps: quando outro app começa a usar o microfone (ditado do teclado,
 * áudio do WhatsApp, ligação, gravador...), ela solta o microfone na hora e só volta a escutar
 * uns segundos depois que o outro app terminar.
 */
class Despertador(
    private val contexto: Context,
    private val aoOuvir: () -> Unit,
    private val aoFalhar: () -> Unit = {},
    /** Comandos de música sem "Vênus, ativar" (pausar, próxima, volume...). null = desligado. */
    private val aoComando: ((String) -> Unit)? = null,
    /** Lido a cada segundo: o usuário pode ligar e desligar nos ajustes. */
    private val modoMusicaLigado: () -> Boolean = { true },
    /** Lido a cada fala: com o nome sozinho ("Vênus") o orbe já ativa, sem precisar dizer "ativar". */
    private val acionarPeloNome: () -> Boolean = { false },
) {
    private val principal = Handler(Looper.getMainLooper())
    private val audio = contexto.getSystemService(AudioManager::class.java)

    @Volatile
    private var geracao = 0

    /** A gravação que está aberta agora (para reconhecer qual é a nossa na lista do sistema). */
    @Volatile
    private var minhaSessao = -1

    @Volatile
    private var gravando = false

    /** true enquanto a Vênus deve esperar a frase (mesmo que, no momento, tenha cedido o microfone). */
    @Volatile
    var ativo = false
        private set

    /** true quando soltou o microfone porque outro app está usando. */
    @Volatile
    var cedido = false
        private set

    private val retomar = Runnable {
        if (ativo && cedido && !outroAppGravando()) {
            cedido = false
            abrirMicrofone()
        }
    }

    private val observador = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            reavaliar(configs.orEmpty())
        }
    }

    /** Começa a escutar. Devolve false se a voz offline não estiver instalada ou faltar permissão. */
    fun iniciar(): Boolean {
        if (ativo) return true
        if (!ModeloDeVoz.pronto(contexto)) return false
        if (contexto.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        ativo = true
        cedido = false
        try {
            audio?.registerAudioRecordingCallback(observador, principal)
        } catch (e: Exception) {
        }
        if (outroAppGravando()) cedido = true else abrirMicrofone()
        return true
    }

    /** Para de escutar e libera o microfone (em até ~0,1 s). */
    fun parar() {
        ativo = false
        cedido = false
        principal.removeCallbacks(retomar)
        try {
            audio?.unregisterAudioRecordingCallback(observador)
        } catch (e: Exception) {
        }
        fecharMicrofone()
    }

    private fun abrirMicrofone() {
        if (!ativo || gravando) return
        gravando = true
        val minha = ++geracao
        Thread({ executar(minha) }, "venus-despertador").start()
    }

    private fun fecharMicrofone() {
        geracao++
        gravando = false
        minhaSessao = -1
    }

    /** Outro app (ou uma ligação) quer o microfone? */
    private fun outroAppGravando(configs: List<AudioRecordingConfiguration>? = null): Boolean {
        val lista = configs ?: try {
            audio?.activeRecordingConfigurations.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val emLigacao = audio?.mode == AudioManager.MODE_IN_CALL || audio?.mode == AudioManager.MODE_IN_COMMUNICATION
        return emLigacao || lista.any { it.clientAudioSessionId != minhaSessao }
    }

    private fun reavaliar(configs: List<AudioRecordingConfiguration>) {
        if (!ativo) return
        val silenciada = Build.VERSION.SDK_INT >= 29 &&
            configs.any { it.clientAudioSessionId == minhaSessao && it.isClientSilenced }
        if (gravando && (silenciada || outroAppGravando(configs))) {
            // Outro app começou a gravar: solta o microfone na hora.
            cedido = true
            principal.removeCallbacks(retomar)
            fecharMicrofone()
        } else if (cedido && !outroAppGravando(configs)) {
            // O outro app terminou: espera um pouco (ele pode voltar a gravar) e retoma.
            principal.removeCallbacks(retomar)
            principal.postDelayed(retomar, 2500)
        }
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
            if (minha != geracao) return
            minhaSessao = gravador.audioSessionId
            gravador.startRecording()

            val bloco = ShortArray(TAXA / 10) // 100 ms
            val anteriores = ArrayDeque<ShortArray>()
            var piso = 200.0
            var vozAte = 0L
            var analisando = false
            // Modo música: com algo tocando, só uma lista fechada de comandos (reconhece bem melhor no barulho)
            var modoMusica = false
            var proximaChecagem = 0L
            var candidato: String? = null
            var repeticoes = 0
            var esperaAte = 0L
            var inicioDaFala = 0L
            while (minha == geracao) {
                val n = gravador.read(bloco, 0, bloco.size)
                if (n < 0) {
                    falhou = true
                    break
                }
                if (n == 0) continue
                val agora = SystemClock.elapsedRealtime()

                if (agora >= proximaChecagem) {
                    proximaChecagem = agora + 1000
                    val deveSer = aoComando != null && modoMusicaLigado() && audio?.isMusicActive == true
                    if (deveSer != modoMusica) {
                        modoMusica = deveSer
                        reconhecedor?.close()
                        reconhecedor = if (modoMusica) Recognizer(modelo, TAXA.toFloat(), GRAMATICA_MUSICA) else Recognizer(modelo, TAXA.toFloat())
                        analisando = false
                        candidato = null
                        repeticoes = 0
                        inicioDaFala = agora
                    }
                }
                val rec = reconhecedor ?: break

                if (modoMusica) {
                    // Com música o som nunca "para": analisa tudo e confirma o comando por alguns instantes.
                    val terminou = rec.acceptWaveForm(bloco, n)
                    val texto = if (terminou) campo(rec.result, "text") else campo(rec.partialResult, "partial")
                    if (Cerebro.pareceAtivacao(texto, acionarPeloNome())) {
                        ouviu = true
                        break
                    }
                    val comando = Cerebro.comandoDeMusica(texto)
                    if (comando != null && agora >= esperaAte) {
                        if (comando == candidato) repeticoes++ else {
                            candidato = comando
                            repeticoes = 1
                        }
                        if (terminou || repeticoes >= 3) {
                            val c = comando
                            principal.post { aoComando?.invoke(c) }
                            esperaAte = agora + 1500
                            candidato = null
                            repeticoes = 0
                            rec.reset()
                            inicioDaFala = agora
                        }
                    } else if (comando == null) {
                        candidato = null
                        repeticoes = 0
                    }
                    if (terminou) inicioDaFala = agora
                    if (agora - inicioDaFala > 8000) {
                        rec.reset()
                        inicioDaFala = agora
                    }
                    continue
                }

                val energia = energia(bloco, n)
                val temVoz = energia > max(piso * 2.2, LIMIAR_MINIMO)
                if (temVoz) vozAte = agora + 1200 else piso = piso * 0.97 + energia * 0.03

                if (agora < vozAte) {
                    if (!analisando) {
                        // Inclui o pedacinho antes da voz, para não perder o começo do "Vênus".
                        for (a in anteriores) rec.acceptWaveForm(a, a.size)
                        analisando = true
                    }
                    val terminou = rec.acceptWaveForm(bloco, n)
                    val texto = if (terminou) campo(rec.result, "text") else campo(rec.partialResult, "partial")
                    if (Cerebro.pareceAtivacao(texto, acionarPeloNome())) {
                        ouviu = true
                        break
                    }
                    // Comandos de música também funcionam com o som baixo
                    if (terminou && aoComando != null && modoMusicaLigado() && audio?.isMusicActive == true) {
                        Cerebro.comandoDeMusica(texto)?.let { c -> principal.post { aoComando?.invoke(c) } }
                    }
                } else if (analisando) {
                    analisando = false
                    if (Cerebro.pareceAtivacao(campo(rec.finalResult, "text"), acionarPeloNome())) {
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
                gravando = false
                minhaSessao = -1
                when {
                    ouviu -> principal.post {
                        parar()
                        aoOuvir()
                    }
                    // Não conseguiu abrir porque outro app está com o microfone: espera ele terminar.
                    falhou && outroAppGravando() -> cedido = true
                    falhou -> principal.post {
                        parar()
                        aoFalhar()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAXA = 16000
        private const val LIMIAR_MINIMO = 180.0

        /** O que a Vênus espera ouvir quando tem música tocando ("[unk]" = qualquer outra coisa). */
        val FRASES_MUSICA = listOf(
            "pausar música", "pausa a música", "pausa música", "parar música", "para a música", "para música",
            "continuar música", "continua a música", "tocar música", "toca a música", "volta a tocar",
            "próxima música", "próxima", "passa a música", "pula a música", "pular música",
            "música anterior", "volta a música", "voltar música",
            "aumentar volume", "aumenta o volume", "aumentar o volume", "sobe o volume",
            "diminuir volume", "diminui o volume", "diminuir o volume", "abaixa o volume", "abaixar volume", "baixa o volume",
            "vênus ativar", "venus ativar", "vênus ativa", "vênus",
            "próxima faixa", "passa essa", "pula essa", "faixa anterior", "volta a faixa",
            "pausa de tocar", "para de tocar", "pausa o som", "para o som",
            "volume máximo", "volume mínimo", "volume na metade", "mais alto", "mais baixo", "silenciar música",
        )
        private val GRAMATICA_MUSICA = (FRASES_MUSICA + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

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
