package com.tonimarques.sextafeira

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Cria o reconhecedor de voz, escolhendo o do Google.
 * Quando a Vênus vira assistente padrão, o Android passa a apontar o reconhecedor "padrão" para ela
 * mesma; por isso escolhemos o serviço explicitamente, sem nunca usar o nosso.
 */
object Reconhecedor {

    private val PREFERIDOS = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

    fun componenteDoGoogle(contexto: Context): ComponentName? {
        val servicos = try {
            contexto.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }
                .filter { it.packageName != contexto.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        val escolhido = servicos.minByOrNull { s ->
            PREFERIDOS.indexOf(s.packageName).let { if (it < 0) 99 else it }
        } ?: return null
        return ComponentName(escolhido.packageName, escolhido.name)
    }

    fun criar(contexto: Context): SpeechRecognizer? {
        val componente = componenteDoGoogle(contexto)
        return try {
            when {
                componente != null -> SpeechRecognizer.createSpeechRecognizer(contexto, componente)
                SpeechRecognizer.isRecognitionAvailable(contexto) -> SpeechRecognizer.createSpeechRecognizer(contexto)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
