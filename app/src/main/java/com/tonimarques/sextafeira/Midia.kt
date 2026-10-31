package com.tonimarques.sextafeira

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/** Controla o que estiver tocando (Deezer, Spotify, YouTube Music...) pelos botões de mídia do Android. */
object Midia {

    fun executar(contexto: Context, acao: String): String {
        val audio = contexto.getSystemService(AudioManager::class.java) ?: return "Não consegui controlar o som."
        fun tecla(codigo: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, codigo))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, codigo))
        }
        fun volume(direcao: Int) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direcao, AudioManager.FLAG_SHOW_UI)
        return when (acao) {
            "pausar" -> { tecla(KeyEvent.KEYCODE_MEDIA_PAUSE); "Música pausada." }
            "tocar" -> { tecla(KeyEvent.KEYCODE_MEDIA_PLAY); "Música tocando." }
            "proxima" -> { tecla(KeyEvent.KEYCODE_MEDIA_NEXT); "Próxima música." }
            "anterior" -> { tecla(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Música anterior." }
            "aumentar_volume" -> { repeat(2) { volume(AudioManager.ADJUST_RAISE) }; "Volume aumentado." }
            "diminuir_volume" -> { repeat(2) { volume(AudioManager.ADJUST_LOWER) }; "Volume diminuído." }
            else -> "Comando de música desconhecido."
        }
    }
}
