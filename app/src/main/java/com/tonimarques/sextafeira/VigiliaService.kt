package com.tonimarques.sextafeira

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Escuta "Ei Vênus" com o app fechado (celular desbloqueado).
 * Roda como serviço em primeiro plano, com notificação fixa, como o Android exige.
 * Quando ouve a frase, abre o pop-up do orbe por cima do que estiver na tela.
 * Fica em pausa enquanto a tela do app ou o pop-up estão abertos (eles mesmos escutam).
 */
class VigiliaService : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private var reconhecedor: SpeechRecognizer? = null
    private var ouvindo = false
    private var errosSeguidos = 0
    private var despertador: Despertador? = null
    private var falhasOffline = 0
    private var gesto: GestoTorcer? = null
    private val tarefa = Runnable { ouvir() }
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    private var travaDeTela: android.os.PowerManager.WakeLock? = null

    private fun escutaLigada() = prefs.getBoolean(PREF_FUNDO, false)
    private fun telaApagadaLigada() = prefs.getBoolean(PREF_TELA_APAGADA, false)

    /**
     * Com "funcionar com a tela apagada", mantém o processador acordado (só ele, a tela continua apagada),
     * para a escuta e o gesto não pararem quando o celular dorme. Gasta mais bateria.
     */
    private fun atualizarTravaDeTela() {
        if (telaApagadaLigada()) {
            if (travaDeTela?.isHeld != true) {
                val energia = getSystemService(android.os.PowerManager::class.java)
                travaDeTela = energia?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "venus:escuta")?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            }
        } else {
            liberarTravaDeTela()
        }
    }

    private fun liberarTravaDeTela() {
        try {
            if (travaDeTela?.isHeld == true) travaDeTela?.release()
        } catch (e: Exception) {
        }
        travaDeTela = null
    }
    private fun gestoLigado() = prefs.getBoolean(PREF_GESTO, false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        try {
            entrarEmPrimeiroPlano()
        } catch (e: Exception) {
            // O Android não deixou (por exemplo, iniciado com o app em segundo plano). Desiste em silêncio.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            prefs.edit().putBoolean(PREF_FUNDO, false).putBoolean(PREF_GESTO, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!escutaLigada() && !gestoLigado()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            entrarEmPrimeiroPlano()
        } catch (e: Exception) {
        }
        atualizarGesto()
        atualizarTravaDeTela()
        if (escutaLigada()) agendar(600) else pausar()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        despertador?.parar()
        gesto?.parar()
        gesto = null
        liberarTravaDeTela()
        try {
            reconhecedor?.destroy()
        } catch (e: Exception) {
        }
        reconhecedor = null
        if (instancia === this) instancia = null
        super.onDestroy()
    }

    // ----------------------------------------------------------- notificação
    private fun entrarEmPrimeiroPlano() {
        val gerente = getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(
            NotificationChannel(CANAL, "Escuta da ${Marca.nome}", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aparece enquanto a ${Marca.nome} espera a frase de ativação."
                setShowBadge(false)
            }
        )
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, VigiliaService::class.java).setAction(ACAO_PARAR), PendingIntent.FLAG_IMMUTABLE
        )
        val notificacao = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_venus_simbolo)
            .setColor(Aparencia.destaque)
            .setContentTitle(if (escutaLigada()) "${Marca.nome} está ouvindo" else "${Marca.nome} está pronta")
            .setContentText(
                when {
                    escutaLigada() && gestoLigado() -> "Diga “Ei ${Marca.nome}” ou torça o pulso 2 vezes"
                    escutaLigada() -> "Diga “Ei ${Marca.nome}”"
                    else -> "Torça o pulso 2 vezes para chamar"
                }
            )
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_venus_simbolo), "Desligar", parar).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICACAO, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID_NOTIFICACAO, notificacao)
        }
    }

    // --------------------------------------------------------------- escuta
    private fun agendar(atraso: Long) {
        principal.removeCallbacks(tarefa)
        principal.postDelayed(tarefa, atraso)
    }

    // --------------------------------------------------------------- gesto
    private fun atualizarGesto() {
        if (gestoLigado()) {
            if (gesto == null) gesto = GestoTorcer(this) { aoGesto() }.also { if (!it.iniciar()) gesto = null }
        } else {
            gesto?.parar()
            gesto = null
        }
    }

    private fun aoGesto() {
        // Com o app na tela, ele mesmo começa a ouvir; senão abre o pop-up já ouvindo.
        aoGestoNaTela?.let {
            it()
            return
        }
        if (pausadoPorTela) return
        abrirPopup("", ouvirDireto = true)
    }

    private fun ouvir() {
        if (!escutaLigada()) return
        if (pausadoPorTela || ouvindo || despertador?.ativo == true) return
        // Com a voz offline instalada: microfone aberto direto, sem o ícone piscando.
        if (falhasOffline < 5 && ModeloDeVoz.pronto(this)) {
            val d = despertador ?: Despertador(
                this,
                aoOuvir = { resto ->
                    falhasOffline = 0
                    abrirPopup(resto)
                },
                aoFalhar = {
                    falhasOffline++
                    agendar(4000)
                },
                aoComando = { acao -> Midia.executar(this, acao) },
                modoMusicaLigado = { prefs.getBoolean(PREF_MUSICA, true) },
                acionarPeloNome = { prefs.getBoolean(PREF_NOME, true) },
            ).also { despertador = it }
            if (d.iniciar()) return
        }
        val r = reconhecedor ?: (Reconhecedor.criar(this) ?: return).also {
            it.setRecognitionListener(ouvinte)
            reconhecedor = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        ouvindo = true
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            ouvindo = false
            agendar(3000)
        }
    }

    private fun pausar() {
        principal.removeCallbacks(tarefa)
        despertador?.parar()
        if (ouvindo) {
            ouvindo = false
            try {
                reconhecedor?.cancel()
            } catch (e: Exception) {
            }
        }
    }

    private fun abrirPopup(resto: String, ouvirDireto: Boolean = false) {
        // Pedido seco de música ("Vênus, próxima música", "Vênus, toca pagode"): só faz, sem abrir o pop-up nem ficar ouvindo.
        // Vale para qualquer caminho de escuta (voz offline ou reconhecimento do Google).
        if (!ouvirDireto && resto.isNotBlank() && prefs.getBoolean(PREF_MUSICA, true)) {
            val seco = Cerebro.pedidoDeMusica("vênus $resto")
            if (seco != null) {
                pausar()
                Midia.executar(this, seco)
                agendar(1500)
                return
            }
        }
        pausar()
        errosSeguidos = 0
        if (!Settings.canDrawOverlays(this)) {
            // Sem a permissão de sobreposição, o Android não deixa abrir o pop-up com o app fechado.
            agendar(1500)
            return
        }
        val intent = Intent(this, PopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ouvirDireto) {
            intent.action = MainActivity.ACAO_OUVIR
        } else if (resto.isBlank()) {
            intent.action = MainActivity.ACAO_ATIVAR
        } else {
            intent.action = MainActivity.ACAO_PEDIDO
            intent.putExtra(MainActivity.EXTRA_PEDIDO, resto)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            agendar(1500)
        }
    }

    private val ouvinte = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val texto = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (Cerebro.restoDepoisDaAtivacao(texto)?.isBlank() == true) abrirPopup("")
        }

        override fun onResults(results: Bundle?) {
            ouvindo = false
            errosSeguidos = 0
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val resto = Cerebro.restoDepoisDaAtivacao(texto)
            if (resto != null) abrirPopup(resto) else agendar(250)
        }

        override fun onError(error: Int) {
            ouvindo = false
            errosSeguidos++
            val atraso = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopSelf()
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 6000L
                SpeechRecognizer.ERROR_CLIENT -> {
                    try {
                        reconhecedor?.destroy()
                    } catch (e: Exception) {
                    }
                    reconhecedor = null
                    1000L
                }
                else -> 300L
            }
            agendar(if (errosSeguidos > 10) 8000L else atraso)
        }
    }

    companion object {
        private const val CANAL = "vigilia"
        private const val ID_NOTIFICACAO = 7
        private const val ACAO_PARAR = "com.tonimarques.sextafeira.PARAR_ESCUTA"
        const val PREF_FUNDO = "vigilia_fundo"
        const val PREF_GESTO = "gesto_torcer"
        const val PREF_MUSICA = "modo_musica"
        const val PREF_NOME = "acionar_pelo_nome"
        const val PREF_TELA_APAGADA = "tela_apagada"

        /** Quando o app está na tela, o gesto chama esta função (ele mesmo começa a ouvir). */
        @Volatile
        var aoGestoNaTela: (() -> Unit)? = null

        @Volatile
        private var instancia: VigiliaService? = null

        /** true enquanto a tela do app ou o pop-up estão abertos: eles usam o microfone. */
        @Volatile
        private var pausadoPorTela = false

        fun iniciar(contexto: Context) {
            contexto.startForegroundService(Intent(contexto, VigiliaService::class.java))
        }

        fun parar(contexto: Context) {
            contexto.stopService(Intent(contexto, VigiliaService::class.java))
        }

        fun pausarPorTela(pausar: Boolean) {
            pausadoPorTela = pausar
            val s = instancia ?: return
            if (pausar) s.pausar() else s.agendar(1200)
        }
    }
}
