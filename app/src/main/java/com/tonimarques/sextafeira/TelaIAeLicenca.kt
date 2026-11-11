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
import com.tonimarques.sextafeira.MainActivity.Companion.MODELO_PADRAO
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_CHAVE
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_MODELO
import java.util.Calendar
import java.util.Locale

/*
 * IA e licença: chave do Gemini, modelo, IA reserva (Groq), Cloudflare e licença de teste.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

internal fun MainActivity.pedirIAReserva() {
    val opcoes = SegundaIA.PROVEDORES
    var escolhido = opcoes.indexOfFirst { it.id == SegundaIA.provedor(this).id }.coerceAtLeast(0)
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val grupo = android.widget.RadioGroup(this)
    opcoes.forEachIndexed { i, p ->
        grupo.addView(android.widget.RadioButton(this).apply {
            id = 9100 + i
            text = p.nome
            isChecked = i == escolhido
        })
    }
    grupo.setOnCheckedChangeListener { _, id -> escolhido = id - 9100 }
    caixa.addView(grupo)
    val entrada = campoSecreto(SegundaIA.chave(this), "Cole aqui a chave")
    caixa.addView(entrada)
    AlertDialog.Builder(this)
        .setTitle("IA reserva")
        .setMessage("Quando o Gemini travar ou ficar sem cota, a ${Marca.nome} pergunta para esta IA. A Groq é grátis (cerca de 1.000 pedidos por dia): crie a chave em console.groq.com.")
        .setView(caixa)
        .setPositiveButton("Salvar e testar") { _, _ ->
            SegundaIA.salvar(this, opcoes[escolhido].id, entrada.text.toString())
            if (entrada.text.isBlank()) return@setPositiveButton responder("IA reserva desligada.")
            responder("Testando a ${opcoes[escolhido].nome}…")
            Thread {
                val r = try {
                    Result.success(SegundaIA.testar(this))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                runOnUiThread {
                    r.onSuccess { responder("A ${opcoes[escolhido].nome} respondeu: \"${it.take(60)}\". Agora, se o Gemini travar, ela assume.") }
                        .onFailure { responder(it.message ?: "Não funcionou.") }
                    ajustes?.atualizar()
                }
            }.start()
        }
        .setNeutralButton("Criar chave") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(opcoes[escolhido].site)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

/** Encerrado: tela que só sai com o código. Faltando 5 dias ou menos: um aviso por dia. */
internal fun MainActivity.conferirLicenca() {
    when (val e = Licenca.estado(this)) {
        Licenca.Estado.Encerrada -> if (!licencaAberta) pedirCodigoDeLiberacao(obrigatorio = true)
        is Licenca.Estado.Teste -> if (e.diasRestantes <= 5) {
            val hoje = Licenca.dataDe(System.currentTimeMillis())
            if (prefs.getString("aviso_licenca", null) != hoje) {
                prefs.edit().putString("aviso_licenca", hoje).apply()
                responder("Faltam ${e.diasRestantes} ${if (e.diasRestantes == 1) "dia" else "dias"} do período de testes. Para continuar, peça o código de liberação informando a instalação ${Licenca.instalacao(this)}.")
            }
        }
        else -> Unit
    }
}

internal fun MainActivity.pedirCodigoDeLiberacao(obrigatorio: Boolean) {
    licencaAberta = true
    val id = Licenca.instalacao(this)
    val campo = campoDeDialogo("", "Cole aqui o código (VENUS-…)")
    val d = AlertDialog.Builder(this)
        .setTitle(if (obrigatorio) "Período de testes encerrado" else "Licença da ${Marca.nome}")
        .setMessage(
            (if (obrigatorio) "Os ${Licenca.DIAS_DE_TESTE} dias de teste terminaram. A conversa com IA, o estudo, as publicações e o robô estão pausados; " +
                "seus dados e ajustes continuam guardados.\n\n" else Licenca.descricao(this) + "\n\n") +
                "Código desta instalação: $id\nMande esse código para quem te passou a ${Marca.nome} e cole aqui o código de liberação."
        )
        .setView(campo)
        .setCancelable(!obrigatorio)
        .setPositiveButton("Liberar", null)
        .setNeutralButton("Copiar $id", null)
        .setNegativeButton(if (obrigatorio) "Fechar o app" else "Fechar") { _, _ ->
            licencaAberta = false
            if (obrigatorio) finishAffinity()
        }
        .create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val erro = Licenca.aplicarCodigo(this, campo.text.toString())
            if (erro != null) Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
            else {
                licencaAberta = false
                d.dismiss()
                ajustes?.atualizar()
                responder("Pronto, liberada. ${Licenca.descricao(this)}")
            }
        }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Instalação", id))
            Toast.makeText(this, "Copiado: $id", Toast.LENGTH_SHORT).show()
        }
    }
    d.setOnDismissListener { licencaAberta = false }
    d.show()
}

/** Só no celular do dono: cria a chave que assina os códigos e gera códigos de liberação. */
internal fun MainActivity.administrarLicencas() {
    val publica = Licenca.chavePublicaAdmin()
    if (publica == null || publica != Licenca.CHAVE_PUBLICA) {
        val msg = if (publica == null) "Primeiro, crie a chave que assina os códigos. Ela fica só no chip de segurança deste celular e nunca sai dele."
        else "A chave já existe neste celular, mas ainda não está embutida no app. Copie a chave pública (não é segredo) e mande para o Claude embutir na próxima versão."
        AlertDialog.Builder(this)
            .setTitle("Licenças de teste")
            .setMessage(msg + (publica?.let { "\n\nChave pública:\n$it" } ?: ""))
            .setPositiveButton(if (publica == null) "Criar a chave" else "Copiar a chave pública") { _, _ ->
                try {
                    val p = Licenca.criarChaveAdmin(this)
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Chave pública da Vênus", p))
                    responder("Chave pública copiada. Mande para o Claude embutir no app; depois disso você já consegue gerar códigos aqui.")
                } catch (e: Exception) {
                    responder("Não consegui criar a chave: ${e.message}")
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
        return
    }
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val id = campoDeDialogo("", "Código da instalação (8 letras e números)").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }
    val dias = campoDeDialogo("30", "Liberar por quantos dias (0 = sem prazo)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
    caixa.addView(id)
    caixa.addView(dias)
    AlertDialog.Builder(this)
        .setTitle("Gerar código de liberação")
        .setMessage("O lojista vê o código da instalação na tela de teste encerrado (ou em Ajustes → Licença).")
        .setView(caixa)
        .setPositiveButton("Gerar e copiar") { _, _ ->
            try {
                val codigo = Licenca.gerarCodigo(this, id.text.toString(), dias.text.toString().toIntOrNull() ?: 30)
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Código de liberação", codigo))
                responder("Código copiado. É só colar no WhatsApp do lojista:")
                adicionarMensagem(codigo, false)
            } catch (e: Exception) {
                responder(e.message ?: "Não consegui gerar o código.")
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.pedirCloudflare() {
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val conta = campoDeDialogo(SegundaIA.contaCloudflare(this), "Código da conta (Account ID)")
    val chave = campoSecreto(SegundaIA.chaveCloudflare(this), "Chave do Workers AI (API Token)")
    caixa.addView(conta)
    caixa.addView(chave)
    val primeira = SegundaIA.provedor(this).nome.substringBefore(" (")
    AlertDialog.Builder(this)
        .setTitle("Segunda reserva: Cloudflare")
        .setMessage("Quando a $primeira atingir o limite do dia, a ${Marca.nome} pergunta para a Cloudflare antes de ir ao Gemini. Grátis todo dia, sem cartão.\n\n" +
            "No site da Cloudflare: crie a conta → menu AI → Workers AI → Use REST API. Lá aparece o código da conta; toque em Create a Workers AI API Token e copie a chave.")
        .setView(caixa)
        .setPositiveButton("Salvar e testar") { _, _ ->
            SegundaIA.salvarCloudflare(this, conta.text.toString(), chave.text.toString())
            ajustes?.atualizar()
            if (chave.text.isBlank()) return@setPositiveButton responder("Segunda reserva desligada.")
            responder("Testando a Cloudflare…")
            Thread {
                val r = try {
                    Result.success(SegundaIA.testar(this, segunda = true))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                runOnUiThread {
                    r.onSuccess { responder("A Cloudflare respondeu: \"${it.take(60)}\". Agora a ordem é $primeira, Cloudflare e, por último, o Gemini.") }
                        .onFailure { responder(it.message ?: "Não funcionou.") }
                    ajustes?.atualizar()
                }
            }.start()
        }
        .setNeutralButton("Abrir a Cloudflare") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SegundaIA.CLOUDFLARE.site)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.pedirChave() {
    val entrada = campoSecreto(Cofre.ler(prefs, PREF_CHAVE), "Cole aqui sua chave")
    AlertDialog.Builder(this)
        .setTitle("Chave do Gemini")
        .setMessage(
            "Crie uma chave grátis em aistudio.google.com/apikey, copie e cole aqui. " +
                "Os comandos e a conversa básica funcionam mesmo sem chave."
        )
        .setView(entrada)
        .setPositiveButton("Salvar") { _, _ ->
            Cofre.guardar(prefs, PREF_CHAVE, entrada.text.toString())
            gemini = null
            Toast.makeText(this, "Chave salva.", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("Criar chave") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.pedirModelo() {
    val entrada = campoDeDialogo(modelo(), MODELO_PADRAO)
    AlertDialog.Builder(this)
        .setTitle("Modelo do Gemini")
        .setMessage("Padrão: $MODELO_PADRAO (sempre o Flash mais recente).")
        .setView(entrada)
        .setPositiveButton("Salvar") { _, _ ->
            prefs.edit().putString(PREF_MODELO, entrada.text.toString().trim()).apply()
            gemini = null
        }
        .setNeutralButton("Usar padrão") { _, _ ->
            prefs.edit().remove(PREF_MODELO).apply()
            gemini = null
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
