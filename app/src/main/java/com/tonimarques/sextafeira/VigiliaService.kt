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
 * Escuta "Vênus, ativar" com o app fechado (celular desbloqueado).
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
    private val tarefa = Runnable { ouvir() }

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
            getSharedPreferences("config", Context.MODE_PRIVATE).edit().putBoolean("vigilia_fundo", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        agendar(600)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        despertador?.parar()
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
            .setColor(android.graphics.Color.parseColor("#E36BD0"))
            .setContentTitle("${Marca.nome} está ouvindo")
            .setContentText("Diga “${Marca.nome}, ativar”")
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

    private fun ouvir() {
        if (pausadoPorTela || ouvindo || despertador?.ativo == true) return
        // Com a voz offline instalada: microfone aberto direto, sem o ícone piscando.
        if (falhasOffline < 5 && ModeloDeVoz.pronto(this)) {
            val d = despertador ?: Despertador(
                this,
                aoOuvir = {
                    falhasOffline = 0
                    abrirPopup("")
                },
                aoFalhar = {
                    falhasOffline++
                    agendar(4000)
                },
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

    private fun abrirPopup(resto: String) {
        pausar()
        errosSeguidos = 0
        if (!Settings.canDrawOverlays(this)) {
            // Sem a permissão de sobreposição, o Android não deixa abrir o pop-up com o app fechado.
            agendar(1500)
            return
        }
        val intent = Intent(this, PopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resto.isBlank()) {
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
