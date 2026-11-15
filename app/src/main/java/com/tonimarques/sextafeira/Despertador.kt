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
 * Espera "Ei Vênus" com o reconhecimento de voz offline (sem internet e sem o Google).
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
    /** Ouviu a chamada; recebe o que foi dito logo depois dela na mesma frase ("Ei Vênus, toca pagode" → "toca pagode"). */
    private val aoOuvir: (String) -> Unit,
    private val aoFalhar: () -> Unit = {},
    /** Comandos de música sem "Ei Vênus" (pausar, próxima, volume...). null = desligado. */
    private val aoComando: ((String) -> Unit)? = null,
    /** Lido a cada segundo: o usuário pode ligar e desligar nos ajustes. */
    private val modoMusicaLigado: () -> Boolean = { true },
    /** Lido a cada fala: com o nome sozinho ("Vênus") o orbe já ativa, sem precisar dizer "ei". */
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
        var livre: Recognizer? = null // fala livre (nome de gênero, artista...) depois de "Vênus, toca"
        var ouviu = false
        var restoOuvido = ""
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
            var livreAte = 0L
            var inicioDaFala = 0L
            var aguardaDesde = 0L
            var mudouEm = 0L
            var textoDaChamada = ""
            // Ouviu a chamada ("Ei Vênus"): espera a pessoa terminar de falar (ou parar um instante), para já levar o pedido junto.
            fun acionou(texto: String, terminou: Boolean, agora: Long): Boolean {
                if (!Cerebro.pareceAtivacao(texto, acionarPeloNome())) {
                    aguardaDesde = 0L
                    textoDaChamada = ""
                    return false
                }
                if (aguardaDesde == 0L) aguardaDesde = agora
                if (texto != textoDaChamada) {
                    textoDaChamada = texto
                    mudouEm = agora
                }
                val ehComando = Cerebro.comandoDeMusica(texto, 12, exigirNome = true) != null
                val quieto = !ehComando && agora - mudouEm >= 600
                if (!terminou && !quieto && agora - aguardaDesde < 3000) return false
                restoOuvido = Cerebro.restoDepoisDaAtivacao(texto) ?: Cerebro.pedidoPorNome(texto).orEmpty()
                ouviu = true
                return true
            }
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
                        try { livre?.close() } catch (e: Throwable) { }
                        livre = null
                        reconhecedor = if (modoMusica) Recognizer(modelo, TAXA.toFloat(), GRAMATICA_MUSICA) else Recognizer(modelo, TAXA.toFloat())
                        analisando = false
                        candidato = null
                        repeticoes = 0
                        inicioDaFala = agora
                    }
                }
                val rec = reconhecedor ?: break

                if (modoMusica) {
                    // Guarda uns 4 s de áudio: ao ouvir "Vênus, toca…" a fala livre recomeça do nome.
                    anteriores.addLast(bloco.copyOf(n))
                    if (anteriores.size > 40) anteriores.removeFirst()
                    val l = livre
                    if (l != null) {
                        val fim = l.acceptWaveForm(bloco, n)
                        if (fim || agora >= livreAte) {
                            val dito = campo(if (fim) l.result else l.finalResult, "text")
                            try { l.close() } catch (e: Throwable) { }
                            livre = null
                            Cerebro.pedidoDeMusica(dito)?.let { c -> principal.post { aoComando?.invoke(c) } }
                            rec.reset()
                            candidato = null
                            repeticoes = 0
                            esperaAte = agora + 1500
                            inicioDaFala = agora
                        }
                        continue
                    }
                    // Com música o som nunca "para": analisa tudo e confirma o comando por alguns instantes.
                    val terminou = rec.acceptWaveForm(bloco, n)
                    val texto = if (terminou) campo(rec.result, "text") else campo(rec.partialResult, "partial")
                    if (agora >= esperaAte && Cerebro.pedeTocarAlgo(texto)) {
                        val novo = Recognizer(modelo, TAXA.toFloat())
                        for (a in anteriores) novo.acceptWaveForm(a, a.size)
                        livre = novo
                        livreAte = agora + 6000
                        continue
                    }
                    val comando = Cerebro.comandoDeMusica(texto, exigirNome = true)
                    // "Ei Vênus" abre o orbe; só o nome ("Vênus") vale depois que a pessoa termina de falar
                    if (comando == null && acionou(texto, terminou, agora)) break
                    if (comando != null && agora >= esperaAte) {
                        if (comando == candidato) repeticoes++ else {
                            candidato = comando
                            repeticoes = 1
                        }
                        // volume só depois da frase inteira ("vinte" não pode virar 20 antes do "e cinco")
                        val precisaDoFim = comando.startsWith("volume") || comando == "aumentar_volume" || comando == "diminuir_volume"
                        if (terminou || (!precisaDoFim && repeticoes >= 3)) {
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
                    // "Vênus, aumentar o volume" também funciona sem música tocando: vai direto, sem abrir o orbe
                    if (terminou && aoComando != null && modoMusicaLigado()) {
                        val c = Cerebro.comandoDeMusica(texto, 12, exigirNome = true)
                        if (c != null) {
                            principal.post { aoComando?.invoke(c) }
                            aguardaDesde = 0L
                            textoDaChamada = ""
                            analisando = false
                            rec.reset()
                            continue
                        }
                    }
                    if (acionou(texto, terminou, agora)) break
                } else if (analisando) {
                    analisando = false
                    if (acionou(campo(rec.finalResult, "text"), true, agora)) break
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
            try {
                livre?.close()
            } catch (e: Throwable) {
            }
            if (minha == geracao) {
                gravando = false
                minhaSessao = -1
                when {
                    ouviu -> principal.post {
                        parar()
                        aoOuvir(restoOuvido)
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

        /**
         * As palavras que a Vênus aceita ouvir quando tem música tocando ("[unk]" = qualquer outra coisa). São palavras soltas,
         * não frases: assim ela entende "diminui o volume para dez por cento" ou "pula essa" do jeito que você falar,
         * mas nada fora da música é reconhecido; para conversar, é só chamar "Vênus".
         */
        val FRASES_MUSICA = listOf(
            "vênus", "venus", "ei", "ativar", "ativa",
            "pausar", "pausa", "pause", "parar", "para", "pare", "continuar", "continua", "continue", "tocar", "toca", "toque", "retomar",
            "próxima", "proxima", "próximo", "seguinte", "pula", "pular", "pule", "passa", "passar", "anterior", "volta", "voltar", "volte",
            "música", "musica", "faixa", "som", "player", "essa", "esta", "de", "a", "o", "e", "um", "uma", "pouco",
            "volume", "aumentar", "aumenta", "aumente", "sobe", "subir", "suba", "diminuir", "diminui", "diminua", "abaixar", "abaixa", "abaixe",
            "baixa", "baixar", "reduz", "reduzir", "menos", "mais", "alto", "baixo", "máximo", "maximo", "total", "mínimo", "minimo", "metade", "meio",
            "mudo", "silenciar", "silencia", "silencie", "tira", "tirar", "coloca", "põe", "bota", "deixa", "no", "na", "em", "pra", "por", "cento", "porcento",
            "zero", "dois", "duas", "três", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze", "treze", "catorze", "quatorze",
            "quinze", "dezesseis", "dezessete", "dezoito", "dezenove", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa", "cem",
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
