package com.tonimarques.sextafeira

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent

/** Controla o que estiver tocando (Deezer, Spotify, YouTube Music...) pelos botões de mídia do Android. */
object Midia {

    /** Posição do volume (0..max) para uma porcentagem 0..100, arredondada. */
    fun indiceDeVolume(pct: Int, max: Int): Int = ((pct.coerceIn(0, 100) * max) + 50) / 100

    /** "volume 50", "volume em 30 por cento" → 50 / 30; nada → null. */
    fun nivelFalado(texto: String): Int? =
        Regex("\\b(\\d{1,3})\\s*(?:%|por cento)?").find(texto)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    private val NUMEROS = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7,
        "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19, "vinte" to 20, "trinta" to 30, "quarenta" to 40,
        "cinquenta" to 50, "sessenta" to 60, "setenta" to 70, "oitenta" to 80, "noventa" to 90, "cem" to 100,
    )

    /**
     * O número dito numa frase de volume: "50", "cinquenta", "vinte e cinco". Números pequenos (menos de 10) só valem
     * com "por cento" depois, para "aumenta um pouco" não virar 1%.
     */
    fun numeroDasPalavras(p: List<String>): Int? {
        for (i in p.indices) {
            val d = p[i].toIntOrNull()
            if (d != null) return d.takeIf { it in 0..100 }
            val v = NUMEROS[p[i]] ?: continue
            if (v in 20..90 && v % 10 == 0 && i + 2 < p.size && p[i + 1] == "e") {
                val u = NUMEROS[p[i + 2]]
                if (u != null && u in 1..9) return v + u
            }
            if (v < 10 && !p.drop(i + 1).take(2).any { it == "cento" || it == "porcento" }) continue
            return v
        }
        return null
    }

    /** Qual player usar: o que foi dito, senão o último que tocou, senão o primeiro instalado da lista. */
    fun escolherPlayer(dito: String?, lembrado: String?, instalados: List<String>): String? =
        dito?.takeIf { it.isNotBlank() } ?: lembrado?.takeIf { it in instalados } ?: instalados.firstOrNull()

    private val PLAYERS = listOf("deezer.android.app", "com.spotify.music", "com.google.android.apps.youtube.music")

    /** "Vênus, toca pagode": pede ao player (o último usado primeiro) para tocar a busca. */
    fun tocarBusca(contexto: Context, busca: String): String {
        val consulta = busca.trim()
        if (consulta.isEmpty()) return "O que devo tocar?"
        val pm = contexto.packageManager
        val ultimo = contexto.getSharedPreferences("config", Context.MODE_PRIVATE).getString("ultimo_player", null)
        val ordem = listOfNotNull(ultimo?.takeIf { it in PLAYERS }) + PLAYERS.filter { it != ultimo }
        for (pacote in ordem) {
            if (pm.getLaunchIntentForPackage(pacote) == null) continue
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pacote)
                .putExtra(SearchManager.QUERY, consulta)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                contexto.startActivity(intent)
                return "Tocando \"$consulta\"."
            } catch (e: Exception) { }
        }
        return "Não achei um player de música para tocar \"$consulta\"."
    }

    fun executar(contexto: Context, acao: String): String {
        if (acao.startsWith("tocar_busca:")) return tocarBusca(contexto, acao.substringAfter(':'))
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
        if (acao.startsWith("volume_nivel:")) {
            return definirVolume(acao.substringAfter(':').toIntOrNull()?.coerceIn(0, 100) ?: return "Não entendi o volume.")
        }
        if (acao.startsWith("volume_rel:")) {
            val delta = acao.substringAfter(':').toIntOrNull() ?: return "Não entendi o volume."
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val atual = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            return definirVolume((atual + delta).coerceIn(0, 100))
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
