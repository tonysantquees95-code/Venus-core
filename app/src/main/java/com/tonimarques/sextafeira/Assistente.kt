package com.tonimarques.sextafeira

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/*
 * Peças que permitem escolher a Vênus como "app assistente digital" do celular.
 * Com isso, segurar o botão de início (ou o de ligar, dependendo do aparelho) abre o pop-up do orbe.
 */

/** Serviço principal do assistente. Não precisa fazer nada além de existir. */
class VenusAssistenteService : VoiceInteractionService()

/** Cria uma sessão cada vez que o usuário chama a assistente. */
class VenusSessaoService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VenusSessao(this)
}

/** A sessão só abre o pop-up do orbe, já ouvindo, e sai do caminho. */
class VenusSessao(contexto: Context) : VoiceInteractionSession(contexto) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, PopupActivity::class.java).setAction(MainActivity.ACAO_OUVIR)
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hide()
    }
}

/**
 * Quando a Vênus vira assistente padrão, o Android também passa a usar o reconhecedor de voz dela.
 * Este serviço apenas repassa tudo para o reconhecedor do Google, para nada parar de funcionar
 * (nem na Vênus, nem em outros apps que usam reconhecimento de voz).
 */
class VenusReconhecimentoService : RecognitionService() {

    private var interno: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        interno?.destroy()
        val r = Reconhecedor.criar(this)
        if (r == null) {
            seguro { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        interno = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = seguro { listener.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = seguro { listener.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = seguro { listener.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = seguro { if (buffer != null) listener.bufferReceived(buffer) }
            override fun onEndOfSpeech() = seguro { listener.endOfSpeech() }
            override fun onError(error: Int) = seguro { listener.error(error) }
            override fun onResults(results: Bundle?) = seguro { listener.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = seguro { listener.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(recognizerIntent ?: Intent())
        } catch (e: Exception) {
            seguro { listener.error(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    override fun onStopListening(listener: Callback?) {
        interno?.stopListening()
    }

    override fun onCancel(listener: Callback?) {
        interno?.cancel()
    }

    override fun onDestroy() {
        interno?.destroy()
        interno = null
        super.onDestroy()
    }

    private inline fun seguro(bloco: () -> Unit) {
        try {
            bloco()
        } catch (e: Exception) {
        }
    }
}
