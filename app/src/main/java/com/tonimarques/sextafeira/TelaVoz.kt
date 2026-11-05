package com.tonimarques.sextafeira

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tonimarques.sextafeira.MainActivity.Companion.COR_TEXTO_APAGADO
import com.tonimarques.sextafeira.MainActivity.Companion.ID_AMOSTRA
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_FUNDO
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_OFERECEU_VOZ
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_TOM
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_VELOCIDADE
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_VIGILIA
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_VOZ
import java.util.Calendar
import java.util.Locale

/*
 * Voz: voz neural, escolher e ajustar a voz, voz offline.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

/** Ajuste da voz neural: chave, região, voz (com amostra) e jeito de falar. */
internal fun MainActivity.configurarVozNeural() {
    var voz = VozNeural.VOZES.indexOf(VozNeural.voz(this)).coerceAtLeast(0)
    var estilo = VozNeural.ESTILOS.indexOf(VozNeural.estilo(this)).coerceAtLeast(0)
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    fun rotulo(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(10), 0, dp(4))
        setTextColor(COR_TEXTO_APAGADO)
    }
    caixa.addView(rotulo("Voz (toque para ouvir)"))
    val grupoVoz = android.widget.RadioGroup(this)
    VozNeural.VOZES.forEachIndexed { i, v ->
        grupoVoz.addView(android.widget.RadioButton(this).apply {
            id = 9200 + i
            text = "${v.nome} — ${v.descricao}"
            isChecked = i == voz
        })
    }
    caixa.addView(grupoVoz)
    caixa.addView(rotulo("Jeito de falar"))
    val grupoEstilo = android.widget.RadioGroup(this)
    VozNeural.ESTILOS.forEachIndexed { i, e ->
        grupoEstilo.addView(android.widget.RadioButton(this).apply {
            id = 9300 + i
            text = e.nome
            isChecked = i == estilo
        })
    }
    caixa.addView(grupoEstilo)
    caixa.addView(rotulo("Chave do Azure Speech"))
    val chave = campoSecreto(VozNeural.chave(this), "Cole aqui a chave (KEY 1)")
    caixa.addView(chave)
    caixa.addView(rotulo("Região do recurso"))
    val regiao = campoDeDialogo(VozNeural.regiao(this), "brazilsouth")
    caixa.addView(regiao)

    fun ouvir() {
        if (chave.text.isBlank()) return Toast.makeText(this, "Cole a chave para ouvir as vozes da Microsoft.", Toast.LENGTH_SHORT).show()
        VozNeural.salvar(this, chave.text.toString(), regiao.text.toString(), VozNeural.VOZES[voz].id, VozNeural.ESTILOS[estilo].id)
        val opcao = VozNeural.VOZES[voz]
        val jeito = VozNeural.ESTILOS[estilo]
        Thread {
            val f = VozNeural.sintetizar(this, "Olá, ${Perfil.dono}. Eu sou a ${Marca.nome}. Sistemas prontos. No que posso ajudar?", opcao, jeito, 10_000)
            runOnUiThread {
                if (f != null) {
                    pararFala()
                    tocarAudio(f, numeroDaFala, amostra = true)
                } else Toast.makeText(this, "A Microsoft não respondeu. Confira a chave e a região.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    grupoVoz.setOnCheckedChangeListener { _, id ->
        voz = id - 9200
        ouvir()
    }
    grupoEstilo.setOnCheckedChangeListener { _, id ->
        estilo = id - 9300
        ouvir()
    }
    AlertDialog.Builder(this)
        .setTitle("Voz neural (Microsoft)")
        .setMessage("Bem mais natural que a voz do celular. Grátis até ${"%,d".format(VozNeural.LIMITE_GRATIS).replace(',', '.')} caracteres por mês; perto do limite ela volta sozinha para a voz do celular.")
        .setView(android.widget.ScrollView(this).apply { addView(caixa) })
        .setPositiveButton("Salvar e testar") { _, _ ->
            VozNeural.salvar(this, chave.text.toString(), regiao.text.toString(), VozNeural.VOZES[voz].id, VozNeural.ESTILOS[estilo].id)
            if (chave.text.isBlank()) return@setPositiveButton responder("Voz neural desligada; continuo com a voz do celular.")
            Thread {
                val r = try {
                    Result.success(VozNeural.testar(this))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                runOnUiThread {
                    r.onSuccess {
                        adicionarMensagem("Voz neural ligada: ${VozNeural.voz(this).nome}. É assim que eu falo agora.", false)
                        pararFala()
                        tocarAudio(it, numeroDaFala, amostra = true)
                    }.onFailure { responder(it.message ?: "Não funcionou.") }
                    ajustes?.atualizar()
                }
            }.start()
        }
        .setNeutralButton("Criar chave") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Fechar") { _, _ -> pararFala() }
        .show()
}

internal fun MainActivity.vozesEmPortugues(): List<Voice> {
    val vozes = try {
        tts?.voices.orEmpty()
    } catch (e: Exception) {
        emptySet()
    }
    return vozes
        .filter { it.locale.language == "pt" && "notInstalled" !in it.features.orEmpty() }
        .sortedWith(
            compareByDescending<Voice> { it.locale.country == "BR" }
                .thenByDescending { it.quality }
                .thenBy { it.isNetworkConnectionRequired }
                .thenBy { it.name }
        )
}

internal fun MainActivity.aplicarVozSalva() {
    val salva = prefs.getString(PREF_VOZ, null)
    val vozes = vozesEmPortugues()
    val escolhida = vozes.firstOrNull { it.name == salva } ?: vozes.firstOrNull()
    if (escolhida != null) {
        try {
            tts?.setVoice(escolhida)
        } catch (e: Exception) {
        }
    }
    tts?.setSpeechRate(prefs.getFloat(PREF_VELOCIDADE, 1.0f))
    tts?.setPitch(prefs.getFloat(PREF_TOM, 1.0f))
}

internal fun MainActivity.amostraDeVoz() {
    tts?.speak("Olá! Eu sou a ${Marca.nome}. Gostou desta voz?", TextToSpeech.QUEUE_FLUSH, null, ID_AMOSTRA)
}

internal fun MainActivity.escolherVoz() {
    if (!ttsPronto) {
        Toast.makeText(this, "A voz ainda está carregando.", Toast.LENGTH_SHORT).show()
        return
    }
    val vozes = vozesEmPortugues()
    if (vozes.isEmpty()) {
        Toast.makeText(this, "Nenhuma voz em português instalada. Vou abrir o download de vozes.", Toast.LENGTH_LONG).show()
        baixarVozes()
        return
    }
    val anterior = tts?.voice
    var marcada = vozes.indexOfFirst { it.name == anterior?.name }.coerceAtLeast(0)
    val nomes = vozes.mapIndexed { i, v ->
        val qualidade = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "qualidade máxima"
            v.quality >= Voice.QUALITY_HIGH -> "alta qualidade"
            else -> "qualidade normal"
        }
        val pais = if (v.locale.country == "BR") "" else " · Portugal"
        val rede = if (v.isNetworkConnectionRequired) " · online" else " · offline"
        "Voz ${i + 1} — $qualidade$rede$pais"
    }.toTypedArray()

    AlertDialog.Builder(this)
        .setTitle("Voz da ${Marca.nome}")
        .setSingleChoiceItems(nomes, marcada) { _, qual ->
            marcada = qual
            try {
                tts?.setVoice(vozes[qual])
            } catch (e: Exception) {
            }
            amostraDeVoz()
        }
        .setPositiveButton("Usar esta") { _, _ ->
            prefs.edit().putString(PREF_VOZ, vozes[marcada].name).apply()
            Toast.makeText(this, "Voz salva.", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("Baixar mais vozes") { _, _ -> baixarVozes() }
        .setNegativeButton("Cancelar") { _, _ ->
            if (anterior != null) {
                try {
                    tts?.setVoice(anterior)
                } catch (e: Exception) {
                }
            }
        }
        .show()
}

internal fun MainActivity.baixarVozes() {
    try {
        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Instale o app Serviços de voz do Google pela Play Store.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.ajustarVoz() {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(4))
    }
    fun barra(rotulo: String, valor: Float): SeekBar {
        layout.addView(TextView(this).apply {
            text = rotulo
            setPadding(0, dp(12), 0, dp(4))
        })
        val sb = SeekBar(this).apply {
            max = 100
            progress = ((valor - 0.5f) * 100).toInt().coerceIn(0, 100)
        }
        layout.addView(sb)
        return sb
    }
    val velocidade = barra("Velocidade", prefs.getFloat(PREF_VELOCIDADE, 1.0f))
    val tom = barra("Tom (grave ↔ agudo)", prefs.getFloat(PREF_TOM, 1.0f))
    val ouvinteBarra = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            tts?.setSpeechRate(0.5f + velocidade.progress / 100f)
            tts?.setPitch(0.5f + tom.progress / 100f)
            amostraDeVoz()
        }
    }
    velocidade.setOnSeekBarChangeListener(ouvinteBarra)
    tom.setOnSeekBarChangeListener(ouvinteBarra)

    AlertDialog.Builder(this)
        .setTitle("Velocidade e tom da voz")
        .setView(layout)
        .setPositiveButton("Salvar") { _, _ ->
            prefs.edit()
                .putFloat(PREF_VELOCIDADE, 0.5f + velocidade.progress / 100f)
                .putFloat(PREF_TOM, 0.5f + tom.progress / 100f)
                .apply()
            aplicarVozSalva()
        }
        .setNeutralButton("Padrão") { _, _ ->
            prefs.edit().remove(PREF_VELOCIDADE).remove(PREF_TOM).apply()
            aplicarVozSalva()
        }
        .setNegativeButton("Cancelar") { _, _ -> aplicarVozSalva() }
        .show()
}

// ------------------------------------------------------- escuta (microfone)
internal open fun vigiliaLigada() = prefs.getBoolean(PREF_VIGILIA, true)

internal fun MainActivity.descricaoVozOffline(): String = progressoVozOffline ?: if (ModeloDeVoz.pronto(this)) {
    "Instalada. O microfone fica aberto direto, só com o pontinho verde, sem o ícone piscando. Toque para remover."
} else {
    "Toque para baixar (31 MB, uma vez só). Sem ela, o ícone do microfone fica piscando na barra de status enquanto a ${Marca.nome} espera a frase."
}

internal fun MainActivity.tratarVozOffline() {
    if (ModeloDeVoz.baixando) return
    if (ModeloDeVoz.pronto(this)) {
        AlertDialog.Builder(this)
            .setTitle("Remover a voz offline?")
            .setMessage("Libera uns 50 MB. A ${Marca.nome} volta a usar o reconhecimento do Google para esperar a frase, e o ícone do microfone volta a piscar.")
            .setPositiveButton("Remover") { _, _ ->
                pararVigilia()
                VigiliaService.pausarPorTela(true)
                Despertador.liberarModelo()
                ModeloDeVoz.apagar(this)
                VigiliaService.pausarPorTela(naTela)
                agendarVigilia(500)
                ajustes?.atualizar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    } else {
        baixarVozOffline()
    }
}

internal fun MainActivity.baixarVozOffline() {
    if (ModeloDeVoz.baixando || ModeloDeVoz.pronto(this)) return
    progressoVozOffline = "Baixando… 0%"
    ajustes?.atualizar()
    val contexto = applicationContext
    Thread {
        val erro = ModeloDeVoz.baixar(contexto) { p ->
            runOnUiThread {
                progressoVozOffline = if (p >= 90) "Instalando…" else "Baixando… $p%"
                ajustes?.atualizar()
            }
        }
        // Já deixa o modelo carregado, para a primeira escuta ser rápida.
        if (erro == null) Despertador.carregarModelo(contexto)
        runOnUiThread {
            progressoVozOffline = null
            ajustes?.atualizar()
            if (erro == null) {
                Toast.makeText(this, "Voz offline instalada! O microfone não vai mais ficar piscando.", Toast.LENGTH_LONG).show()
                if (escuta == Escuta.VIGILIA) pararVigilia()
                agendarVigilia(400)
            } else {
                Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

/** Uma vez só: oferece a voz offline para quem usa a ativação por voz. */
internal fun MainActivity.oferecerVozOffline(forcar: Boolean = false) {
    if (isFinishing || ModeloDeVoz.pronto(this) || ModeloDeVoz.baixando) return
    if (!forcar && prefs.getBoolean(PREF_OFERECEU_VOZ, false)) return
    if (!vigiliaLigada() && !prefs.getBoolean(PREF_FUNDO, false)) return
    prefs.edit().putBoolean(PREF_OFERECEU_VOZ, true).apply()
    AlertDialog.Builder(this)
        .setTitle("Microfone sem piscar")
        .setMessage(
            "Para esperar \"${Marca.nome}, ativar\", ela liga e desliga o microfone o tempo todo, e o ícone fica piscando ao lado da bateria. " +
                "Com a voz offline (31 MB, baixa uma vez só), o microfone fica aberto direto: só o pontinho verde, sem piscar, e funciona até sem internet."
        )
        .setPositiveButton("Baixar agora") { _, _ -> baixarVozOffline() }
        .setNegativeButton("Depois", null)
        .show()
}
