package com.tonimarques.sextafeira

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/** Controla o que estiver tocando (Deezer, Spotify, YouTube Music...) pelos botões de mídia do Android. */
object Midia {

    /** Posição do volume (0..max) para uma porcentagem 0..100, arredondada. */
    fun indiceDeVolume(pct: Int, max: Int): Int = ((pct.coerceIn(0, 100) * max) + 50) / 100

    /** "volume 50", "volume em 30 por cento" → 50 / 30; nada → null. */
    fun nivelFalado(texto: String): Int? =
        Regex("\\b(\\d{1,3})\\s*(?:%|por cento)?").find(texto)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    /** Qual player usar: o que foi dito, senão o último que tocou, senão o primeiro instalado da lista. */
    fun escolherPlayer(dito: String?, lembrado: String?, instalados: List<String>): String? =
        dito?.takeIf { it.isNotBlank() } ?: lembrado?.takeIf { it in instalados } ?: instalados.firstOrNull()

    fun executar(contexto: Context, acao: String): String {
        val audio = contexto.getSystemService(AudioManager::class.java) ?: return "Não consegui controlar o som."
        fun tecla(codigo: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, codigo))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, codigo))
        }
        fun volume(direcao: Int) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direcao, AudioManager.FLAG_SHOW_UI)
        fun definirVolume(pct: Int): String {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, indiceDeVolume(pct, max), AudioManager.FLAG_SHOW_UI)
            return "Volume em $pct%."
        }
        return when (acao) {
            "volume_maximo" -> definirVolume(100)
            "volume_minimo" -> definirVolume(0)
            "volume_metade" -> definirVolume(50)
            "parar" -> { tecla(KeyEvent.KEYCODE_MEDIA_STOP); "Música parada." }
            "avancar" -> { tecla(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); "Avançando." }
            "voltar" -> { tecla(KeyEvent.KEYCODE_MEDIA_REWIND); "Voltando." }
            "mudo" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); "Som no mudo (repita para tirar)." }
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
