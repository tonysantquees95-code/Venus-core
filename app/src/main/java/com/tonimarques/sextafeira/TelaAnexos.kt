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
import com.tonimarques.sextafeira.MainActivity.Companion.MAXIMO_ANEXOS
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_ANEXO_FOTOS
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_ARQUIVO
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_AVISO_ARQUIVOS
import java.util.Calendar
import java.util.Locale

/*
 * Anexos e documentos: escolher, mostrar e ler arquivos e fotos anexados, notas de compra.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

/** Menu do "+": foto, galeria ou documento; a Vênus lê e responde sobre o que foi anexado. */
internal fun MainActivity.abrirAnexos(ancora: View) {
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#1B1035"), Color.parseColor("#0A0F24"))).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.argb(90, 227, 107, 208))
        }
        elevation = dp(12).toFloat()
    }
    val janela = android.widget.PopupWindow(caixa, dp(260), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        elevation = dp(12).toFloat()
        animationStyle = android.R.style.Animation_Dialog
    }
    fun opcao(simbolo: String, titulo: String, detalhe: String, acao: () -> Unit) {
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(60, 227, 107, 208)), null,
                GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) })
            isClickable = true
            setOnClickListener {
                janela.dismiss()
                acao()
            }
        }
        linha.addView(TextView(this).apply {
            text = simbolo
            textSize = 18f
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#E36BD0"), Color.parseColor("#9B5CF6"))).apply {
                shape = GradientDrawable.OVAL
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        textos.addView(TextView(this).apply {
            text = titulo
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textos.addView(TextView(this).apply {
            text = detalhe
            textSize = 11.5f
            setTextColor(COR_TEXTO_APAGADO)
        })
        linha.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        caixa.addView(linha)
    }
    val lerFoto: (Uri) -> Unit = { u ->
        cadastroFoto = null
        anexar(listOf(u))
    }
    opcao("📷", "Tirar foto", "A ${Marca.nome} olha e responde") {
        cadastroFoto = lerFoto
        pedirFotoCamera()
    }
    opcao("🖼️", "Fotos da galeria", "Uma ou várias: imagem, print, nota") { escolherFotos() }
    opcao("📄", "Arquivos", "PDF, Word, Excel, áudio… (um ou vários)") { escolherArquivo() }
    opcao("🛍️", "Produto novo", "Cadastrar na loja com foto") { abrirCadastro(emptyMap()) }
    opcao("📣", "Post no Instagram", "Programar feed ou story") { programarPost(emptyMap()) }
    caixa.measure(View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    janela.showAsDropDown(ancora, 0, -(caixa.measuredHeight + ancora.height + dp(8)))
}

/** O arquivo continua "aberto" para perguntas por 30 minutos. */
internal fun MainActivity.documentoAtivo(): Arquivos.Documento? =
    documentosAtivos().firstOrNull()

internal fun MainActivity.documentosAtivos(): List<Arquivos.Documento> =
    if (System.currentTimeMillis() - documentoQuando < 30 * 60_000L) documentosAbertos else emptyList()

internal fun MainActivity.escolherArquivo() {
    try {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            PEDIDO_ARQUIVO,
        )
    } catch (e: Exception) {
        responder("Não consegui abrir o seletor de arquivos.")
    }
}

// ----------------------------------------------------------- anexos (vários, com o seu pedido)
internal fun MainActivity.temAnexos() = ::bandejaAnexos.isInitialized && anexos.isNotEmpty()

internal fun MainActivity.escolherFotos() {
    val intencao = if (Build.VERSION.SDK_INT >= 33) {
        Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
            .putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(MAXIMO_ANEXOS, android.provider.MediaStore.getPickImagesMaxLimit()))
    } else {
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
    try {
        startActivityForResult(intencao, PEDIDO_ANEXO_FOTOS)
    } catch (e: Exception) {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PEDIDO_ANEXO_FOTOS)
        } catch (e2: Exception) {
            responder("Não consegui abrir a galeria.")
        }
    }
}

internal fun MainActivity.nomeDoArquivo(uri: Uri): String = try {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "arquivo"
} catch (e: Exception) {
    uri.lastPathSegment ?: "arquivo"
}

/** Põe fotos e arquivos na bandeja acima do campo; nada é enviado até você tocar em enviar (com ou sem texto). */
internal fun MainActivity.anexar(uris: List<Uri>) {
    if (!::bandejaAnexos.isInitialized || uris.isEmpty()) return
    val cabem = MAXIMO_ANEXOS - anexos.size
    if (cabem <= 0) return Toast.makeText(this, "No máximo $MAXIMO_ANEXOS anexos por vez.", Toast.LENGTH_SHORT).show()
    if (uris.size > cabem) Toast.makeText(this, "Peguei só $cabem; o máximo é $MAXIMO_ANEXOS por vez.", Toast.LENGTH_SHORT).show()
    for (uri in uris.take(cabem)) {
        val a = Anexo(uri, nomeDoArquivo(uri))
        anexos += a
        a.vista = vistaDoAnexo(a)
        bandejaAnexos.addView(a.vista)
        Thread {
            try {
                val mime = Arquivos.tipo(contentResolver.getType(uri), a.nome)
                if (mime.startsWith("image/")) {
                    val bytes = FotoProvider.prepararParaEnviar(this, uri, 1600) ?: throw Arquivos.ErroDeArquivo("Não consegui abrir essa foto.")
                    a.doc = Arquivos.Documento(a.nome, "image/jpeg", bytes, null)
                    a.miniatura = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                } else {
                    a.doc = Arquivos.abrir(this, uri)
                }
            } catch (e: Exception) {
                a.erro = (e as? Arquivos.ErroDeArquivo)?.message ?: "Não consegui abrir ${a.nome}."
            }
            runOnUiThread {
                if (a.erro != null) {
                    Toast.makeText(this, a.erro, Toast.LENGTH_LONG).show()
                    tirarAnexo(a)
                } else atualizarVistaDoAnexo(a)
            }
        }.start()
    }
    rolagemAnexos.visibility = View.VISIBLE
    campo.hint = "Peça algo sobre ${if (anexos.size == 1) "o anexo" else "os anexos"} (ou só envie)"
    campo.requestFocus()
}

internal fun MainActivity.tirarAnexo(a: Anexo) {
    anexos.remove(a)
    a.vista?.let { bandejaAnexos.removeView(it) }
    if (anexos.isEmpty()) {
        rolagemAnexos.visibility = View.GONE
        campo.hint = "Digite ou toque no orbe…"
    }
}

internal fun MainActivity.limparAnexos() {
    anexos.clear()
    if (::bandejaAnexos.isInitialized) {
        bandejaAnexos.removeAllViews()
        rolagemAnexos.visibility = View.GONE
        campo.hint = "Digite ou toque no orbe…"
    }
}

internal fun MainActivity.iconeDoArquivo(nome: String, mime: String) = when {
    mime == "application/pdf" -> "PDF"
    mime.startsWith("audio/") -> "🎧"
    nome.endsWith(".xlsx", true) || nome.endsWith(".csv", true) -> "📊"
    nome.endsWith(".pptx", true) -> "📽"
    else -> "📄"
}

/** O quadradinho do anexo: miniatura da foto (ou ícone do arquivo), nome embaixo e um × para tirar. */
internal fun MainActivity.vistaDoAnexo(a: Anexo): View {
    val quadro = android.widget.FrameLayout(this).apply { alpha = 0.55f }
    val cartao = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = vidro(Color.argb(22, 255, 255, 255), Color.argb(60, 255, 255, 255), 14)
        clipToOutline = true
    }
    val imagem = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        tag = "imagem"
        visibility = View.GONE
    }
    val icone = TextView(this).apply {
        text = "…"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        tag = "icone"
    }
    val nome = TextView(this).apply {
        text = a.nome
        textSize = 9.5f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setTextColor(COR_TEXTO_APAGADO)
        setPadding(dp(4), 0, dp(4), dp(3))
        gravity = Gravity.CENTER
        tag = "nome"
    }
    cartao.addView(imagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    cartao.addView(icone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    cartao.addView(nome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    quadro.addView(cartao, android.widget.FrameLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(0, dp(6), dp(6), 0) })
    val fechar = TextView(this).apply {
        text = "×"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(230, 30, 20, 50))
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
        contentDescription = "Tirar ${a.nome}"
        setOnClickListener { tirarAnexo(a) }
    }
    quadro.addView(fechar, android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.TOP))
    return quadro
}

internal fun MainActivity.atualizarVistaDoAnexo(a: Anexo) {
    val v = a.vista ?: return
    v.alpha = 1f
    val imagem = v.findViewWithTag<android.widget.ImageView>("imagem")
    val icone = v.findViewWithTag<TextView>("icone")
    val nome = v.findViewWithTag<TextView>("nome")
    val doc = a.doc ?: return
    if (a.miniatura != null) {
        imagem.setImageBitmap(a.miniatura)
        imagem.visibility = View.VISIBLE
        icone.visibility = View.GONE
        nome.visibility = View.GONE
    } else {
        icone.text = iconeDoArquivo(doc.nome, doc.mime)
        icone.setTextColor(corAtual)
    }
}

// ----------------------------------------------------------- anexos na conversa (miniaturas)
internal fun MainActivity.pastaMiniaturas() = java.io.File(filesDir, "miniaturas").apply { mkdirs() }

internal fun MainActivity.arquivoDaMiniatura(nome: String): java.io.File {
    val h = java.security.MessageDigest.getInstance("SHA-1").digest(nome.toByteArray()).joinToString("") { "%02x".format(it) }
    return java.io.File(pastaMiniaturas(), "$h.jpg")
}

/** Guarda uma versão pequena da foto (para a conversa e para reabrir depois). */
internal fun MainActivity.guardarMiniatura(doc: Arquivos.Documento) {
    val bytes = doc.bytes ?: return
    try {
        val op = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        var amostra = 1
        while (maxOf(op.outWidth, op.outHeight) / (amostra * 2) >= 900) amostra *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return
        arquivoDaMiniatura(doc.nome).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        // não deixa a pasta crescer para sempre
        pastaMiniaturas().listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
    } catch (e: Exception) {
    }
}

/** O que você enviou: as fotos em miniatura (tocando, abre maior) e o seu pedido embaixo. */
internal fun MainActivity.mostrarAnexosNaConversa(docs: List<Arquivos.Documento>, texto: String) {
    docs.filter { it.mime.startsWith("image/") }.forEach { guardarMiniatura(it) }
    try {
        memoria.salvarMensagem(true, "📎 " + docs.joinToString(" | ") { it.nome } + if (texto.isNotBlank()) "\n$texto" else "", topicoAtual)
    } catch (e: Exception) {
    }
    desenharAnexos(docs.map { it.nome to it.mime }, texto)
}

/** Conversa reaberta: monta de novo a partir do que ficou guardado ("📎 nome | nome\npedido"). */
internal fun MainActivity.mostrarAnexosSalvos(guardado: String) {
    val primeira = guardado.removePrefix("📎 ").substringBefore("\n")
    val texto = guardado.substringAfter("\n", "")
    val nomes = (if (" | " in primeira) primeira.split(" | ") else primeira.split(", ")).map { it.trim() }.filter { it.isNotBlank() }
    desenharAnexos(nomes.map { it to Arquivos.tipo(null, it) }, texto, apagado = true)
}

internal fun MainActivity.desenharAnexos(itens: List<Pair<String, String>>, texto: String, apagado: Boolean = false) {
    val fileira = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    for ((nome, mime) in itens) {
        val miniatura = arquivoDaMiniatura(nome).takeIf { mime.startsWith("image/") && it.exists() }
        val vista: View = if (miniatura != null) {
            android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(android.graphics.BitmapFactory.decodeFile(miniatura.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }))
                background = vidro(Color.TRANSPARENT, comAlfa(corAtual, 110), 12)
                clipToOutline = true
                contentDescription = "Foto enviada. Toque para ver maior."
                setOnClickListener { verFotoGrande(miniatura) }
            }
        } else {
            TextView(this).apply {
                text = iconeDoArquivo(nome, mime) + "\n" + nome.substringAfterLast('.', "").uppercase().take(4)
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
                background = vidro(comAlfa(corAtual, 40), comAlfa(corAtual, 110), 12)
                contentDescription = nome
            }
        }
        fileira.addView(vista, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(6) })
    }
    val rolar = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(fileira)
        if (apagado) alpha = 0.7f
    }
    mensagens.addView(rolar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.END
        topMargin = dp(8)
        leftMargin = dp(56)
    })
    if (texto.isNotBlank()) adicionarMensagem(texto, true, salvar = false).apply { if (apagado) alpha = 0.7f }
    rolarParaBaixo()
}

/** A foto maior, num pop-up escuro; toque para fechar. */
internal fun MainActivity.verFotoGrande(arquivo: java.io.File) {
    val janela = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val foto = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setImageBitmap(android.graphics.BitmapFactory.decodeFile(arquivo.absolutePath))
        setBackgroundColor(Color.argb(235, 0, 0, 0))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { janela.dismiss() }
        contentDescription = "Foto. Toque para fechar."
    }
    janela.setContentView(foto)
    janela.show()
}

/**
 * Envia os anexos com o seu pedido (ou sem nada: ela entende sozinha o que é cada arquivo).
 * Pedidos de loja com foto vão direto para a tela certa (cadastro, nota de compra, fundo).
 */
internal fun MainActivity.enviarComAnexos(texto: String) {
    if (anexos.any { it.doc == null && it.erro == null }) {
        mudarEstado(Orbe.Estado.PENSANDO, "CARREGANDO OS ANEXOS…")
        principal.postDelayed({ enviarComAnexos(texto) }, 500)
        return
    }
    val docs = anexos.mapNotNull { it.doc }
    limparAnexos()
    if (docs.isEmpty()) return
    topicoAtual = "Arquivos"
    mostrarAnexosNaConversa(docs, texto)
    val chaveTexto = ComandosLocais.normalizar(texto)
    val fotos = docs.filter { it.mime.startsWith("image/") && it.bytes != null }
    val comando = if (texto.isNotBlank()) ComandosLocais.interpretar(texto) else null
    when {
        fotos.isNotEmpty() && (comando as? ComandosLocais.Resultado.Acao)?.nome == "loja_cadastrar" ->
            return abrirCadastro((comando as ComandosLocais.Resultado.Acao).args, fotos.first().bytes)
        texto.isNotBlank() && PEDE_NOTA.containsMatchIn(chaveTexto) && docs.first().bytes != null && ferramentas.loja.conectada ->
            return lerNotaDoDocumento(docs.first())
        fotos.isNotEmpty() && PEDE_FUNDO.containsMatchIn(chaveTexto) -> return editarFundo(fotos.first().bytes)
        fotos.isNotEmpty() && PEDE_PRECO.containsMatchIn(chaveTexto) -> return pesquisarProdutoDaFoto(fotos.first(), texto)
    }
    lerAnexos(docs, texto)
}

internal fun MainActivity.lerAnexos(docs: List<Arquivos.Documento>, texto: String) {
    val k = chave()
    val m = modelo()
    if (docs.any { it.bytes != null } && (k.isBlank() || modoIndependente())) {
        return responder("Para ler fotos, PDFs e áudios eu uso o Gemini. Configure a chave do Gemini e deixe o modo independente desligado.")
    }
    if (!prefs.getBoolean(PREF_AVISO_ARQUIVOS, false)) {
        prefs.edit().putBoolean(PREF_AVISO_ARQUIVOS, true).apply()
        mostrarNota("Para ler anexos, o conteúdo vai para a IA (Google). Evite documentos com senhas.")
    }
    comTempoLimite(if (docs.size > 1) "LENDO OS ANEXOS…" else "LENDO O ANEXO…") {
        // sem pedido e um arquivo só: a leitura completa (boleto e nota viram sugestões)
        if (texto.isBlank() && docs.size == 1) {
            val doc = docs.first()
            val analise = Gemini.analisarDocumento(k, m, doc)
            val acao: () -> Unit = {
                if (analise == null) responder("Não consegui ler ${doc.nome} agora. Tente de novo em instantes.")
                else {
                    documentosAbertos = docs
                    documentoQuando = System.currentTimeMillis()
                    conversaDocumento.clear()
                    responder(analise.resumo)
                    oferecerAcoesDoDocumento(doc, analise)
                }
            }
            return@comTempoLimite acao
        }
        val r = Gemini.perguntarDocumentos(k, m, docs, texto, emptyList())
        val acao: () -> Unit = {
            if (r == null) responder("Não consegui ler ${if (docs.size > 1) "os anexos" else "o anexo"} agora. Tente de novo em instantes.")
            else {
                documentosAbertos = docs
                documentoQuando = System.currentTimeMillis()
                conversaDocumento.clear()
                if (texto.isNotBlank()) conversaDocumento += texto to r
                responder(r)
            }
        }
        acao
    }
}

/** Abre o arquivo, manda ao Gemini e mostra o resumo; boleto vira lembrete e nota vira compra, se você quiser. */
internal fun MainActivity.abrirDocumento(uri: Uri, avisado: Boolean = false) {
    if (chave().isBlank() || modoIndependente()) {
        responder("Para ler arquivos eu uso o Gemini. Configure a chave do Gemini e deixe o modo independente desligado.")
        return
    }
    if (!avisado && !prefs.getBoolean(PREF_AVISO_ARQUIVOS, false)) {
        AlertDialog.Builder(this)
            .setTitle("Ler arquivo")
            .setMessage("Para ler o arquivo, a ${Marca.nome} envia o conteúdo ao Google (Gemini). Evite documentos com senhas ou dados muito sensíveis.")
            .setPositiveButton("Ler") { _, _ -> abrirDocumento(uri, true) }
            .setNeutralButton("Ler e não perguntar mais") { _, _ ->
                prefs.edit().putBoolean(PREF_AVISO_ARQUIVOS, true).apply()
                abrirDocumento(uri, true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
        return
    }
    val k = chave()
    val m = modelo()
    topicoAtual = "Arquivos"
    comTempoLimite("LENDO O ARQUIVO…") {
        val doc = try {
            Arquivos.abrir(this, uri)
        } catch (e: Exception) {
            val msg = (e as? Arquivos.ErroDeArquivo)?.message ?: "Não consegui abrir esse arquivo."
            return@comTempoLimite { responder(msg) }
        }
        val analise = Gemini.analisarDocumento(k, m, doc)
        val acao: () -> Unit = {
            if (analise == null) {
                responder("Abri ${doc.nome}, mas o Gemini não conseguiu ler agora. Tente de novo em instantes.")
            } else {
                documentosAbertos = listOf(doc)
                documentoQuando = System.currentTimeMillis()
                conversaDocumento.clear()
                adicionarMensagem("📎 ${doc.nome}", true)
                responder(analise.resumo + " Pode me perguntar sobre ${if (doc.tipoFalado == "áudio") "o áudio" else "o arquivo"}.")
                oferecerAcoesDoDocumento(doc, analise)
            }
        }
        acao
    }
}

internal fun MainActivity.oferecerAcoesDoDocumento(doc: Arquivos.Documento, a: Gemini.Analise) {
    when {
        a.tipo == "boleto" && (a.vencimento != null || a.linhaDigitavel != null) -> {
            val opcoes = mutableListOf<Pair<String, () -> Unit>>()
            a.vencimento?.let { v ->
                opcoes += "Me lembrar no dia do vencimento" to {
                    val (ano, mes, dia) = v.split("-").map { it.toInt() }
                    val quando = java.util.Calendar.getInstance().apply { clear(); set(ano, mes - 1, dia, 9, 0) }.timeInMillis
                    val texto = "Pagar boleto" + (a.valorCentavos?.let { " de " + Loja.dinheiro(it) } ?: "") + " (${doc.nome.substringBeforeLast('.')})"
                    if (quando > System.currentTimeMillis()) {
                        val l = Lembretes.criar(this, Lembretes.Pedido(quando, texto))
                        responder("Combinado: ${Lembretes.quandoFalado(l.quando)} eu te lembro de pagar esse boleto.")
                    } else responder("Esse boleto já venceu (${dia}/${mes}/${ano}).")
                }
            }
            a.linhaDigitavel?.let { linha ->
                opcoes += "Copiar a linha digitável" to {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Linha digitável", linha))
                    Toast.makeText(this, "Linha digitável copiada. É só colar no app do banco.", Toast.LENGTH_LONG).show()
                }
            }
            mostrarSugestoes(opcoes)
        }
        a.tipo == "nota_fiscal" && doc.bytes != null && ferramentas.loja.conectada -> {
            mostrarSugestoes(listOf("Lançar a compra no estoque" to { lerNotaDoDocumento(doc) }))
        }
    }
}

internal fun MainActivity.lerNotaDoDocumento(doc: Arquivos.Documento) {
    val bytes = doc.bytes ?: return
    val k = chave()
    val m = modelo()
    comTempoLimite("LENDO A NOTA…") {
        val linhas = Gemini.lerNotaDeCompra(k, m, bytes, doc.mime)
        val produtos = if (linhas != null) try {
            ferramentas.loja.produtos()
        } catch (e: Exception) {
            null
        } else null
        val acao: () -> Unit = when {
            linhas == null -> { { responder("Não consegui separar os itens dessa nota.") } }
            produtos == null -> { { responder("Li a nota, mas não consegui falar com a loja. Confira a internet.") } }
            else -> { { confirmarCompra(Loja.casarCompra(produtos, linhas), produtos) } }
        }
        acao
    }
}

internal fun MainActivity.perguntarAoDocumento(pergunta: String) {
    val docs = documentosAtivos().ifEmpty { return responder("Nenhum arquivo aberto. Toque no + para anexar fotos ou arquivos.") }
    documentoQuando = System.currentTimeMillis()
    val k = chave()
    val m = modelo()
    val historico = conversaDocumento.toList()
    comTempoLimite(if (docs.size > 1) "LENDO OS ARQUIVOS…" else "LENDO O ARQUIVO…") {
        val r = Gemini.perguntarDocumentos(k, m, docs, pergunta, historico)
        val acao: () -> Unit = {
            if (r == null) responder("O Gemini não respondeu agora. Pode perguntar de novo?")
            else {
                conversaDocumento += pergunta to r
                responder(r)
            }
        }
        acao
    }
}

// ----------------------------------------------------------- nota de compra
/** Lê a nota (foto ou print), mostra cada item ligado a um produto e, com a confirmação, soma ao estoque e salva o custo. */
internal fun MainActivity.lerNota(uri: Uri?) {
    if (!ferramentas.loja.conectada) {
        responder("A loja ainda não está conectada. Abra os ajustes e toque em Conectar à loja.")
        return
    }
    if (chave().isBlank()) {
        responder("Para ler a nota eu uso o Gemini. Configure a chave do Gemini nos ajustes.")
        return
    }
    if (uri == null) {
        responder("Mande a foto ou o print da nota de compra.")
        val anterior = cadastroFoto
        cadastroFoto = { u ->
            cadastroFoto = anterior
            lerNota(u)
        }
        AlertDialog.Builder(this)
            .setTitle("Nota de compra")
            .setMessage("Tire uma foto da nota ou escolha o print na galeria.")
            .setPositiveButton("Tirar foto") { _, _ -> pedirFotoCamera() }
            .setNeutralButton("Da galeria") { _, _ -> pedirFotoGaleria() }
            .setNegativeButton("Cancelar") { _, _ -> cadastroFoto = anterior }
            .show()
        return
    }
    val k = chave()
    val m = modelo()
    comTempoLimite("LENDO A NOTA…") {
        val foto = FotoProvider.prepararParaEnviar(this, uri, 2000)
        val linhas = foto?.let { Gemini.lerNotaDeCompra(k, m, it) }
        val produtos = if (linhas != null) try {
            ferramentas.loja.produtos()
        } catch (e: Exception) {
            null
        } else null
        val acao: () -> Unit = when {
            foto == null -> { { responder("Não consegui abrir essa imagem.") } }
            linhas == null -> { { responder("Não consegui ler os itens dessa nota. Tente uma foto mais nítida, de frente e com boa luz.") } }
            produtos == null -> { { responder("Li a nota, mas não consegui falar com a loja. Confira a internet e tente de novo.") } }
            else -> { { confirmarCompra(Loja.casarCompra(produtos, linhas), produtos) } }
        }
        acao
    }
}

internal fun MainActivity.confirmarCompra(itens: List<Loja.ItemDeCompra>, produtos: List<Loja.Produto>) {
    val podeCusto = produtos.any { it.bruto?.has(Loja.CAMPO_CUSTO) == true }
    val opcoes = listOf("— ignorar este item —") + produtos.sortedBy { it.nome }.map { it.nome }
    val ordenados = produtos.sortedBy { it.nome }
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    data class Linha(val item: Loja.ItemDeCompra, val produto: android.widget.Spinner, val qtd: EditText, val custo: EditText)
    val linhas = itens.map { item ->
        caixa.addView(TextView(this).apply {
            text = "Na nota: ${item.descricao}"
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })
        val sp = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@confirmarCompra, android.R.layout.simple_spinner_dropdown_item, opcoes)
            setSelection(item.produto?.let { p -> ordenados.indexOfFirst { it.id == p.id } + 1 } ?: 0)
        }
        caixa.addView(sp)
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtd = EditText(this).apply {
            setText(item.quantidade.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Qtd."
        }
        val custo = EditText(this).apply {
            setText("%d,%02d".format(item.custoUnitario / 100, item.custoUnitario % 100))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Custo por unidade"
        }
        linha.addView(TextView(this).apply { text = "Qtd." }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        linha.addView(qtd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        linha.addView(TextView(this).apply { text = "  R$/un." }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        linha.addView(custo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        caixa.addView(linha)
        Linha(item, sp, qtd, custo)
    }
    val somar = android.widget.CheckBox(this).apply {
        text = "Somar as quantidades ao estoque"
        isChecked = true
        setPadding(0, dp(14), 0, 0)
    }
    val salvar = android.widget.CheckBox(this).apply {
        text = if (podeCusto) "Salvar o custo de cada produto" else "Salvar o custo (o site ainda não tem esse campo)"
        isChecked = podeCusto
        isEnabled = podeCusto
    }
    caixa.addView(somar)
    caixa.addView(salvar)
    val reconhecidos = itens.count { it.produto != null }
    responder("Li ${itens.size} ${if (itens.size == 1) "item" else "itens"} na nota e reconheci $reconhecidos. Confira na tela antes de salvar.")
    AlertDialog.Builder(this)
        .setTitle("Compra lida da nota")
        .setView(ScrollView(this).apply { addView(caixa) })
        .setPositiveButton("Salvar") { _, _ ->
            val escolhidos = linhas.mapNotNull { l ->
                val pos = l.produto.selectedItemPosition
                if (pos <= 0) return@mapNotNull null
                val q = l.qtd.text.toString().trim().toIntOrNull() ?: return@mapNotNull null
                val c = ComandosLocais.centavos(l.custo.text.toString().replace(Regex("[^0-9,.]"), "")) ?: l.item.custoUnitario
                Loja.ItemDeCompra(l.item.descricao, q, c, ordenados[pos - 1])
            }
            if (escolhidos.isEmpty() || (!somar.isChecked && !salvar.isChecked)) {
                responder("Nada foi mudado.")
                return@setPositiveButton
            }
            comTempoLimite("SALVANDO…") {
                val r = try {
                    ferramentas.loja.aplicarCompra(escolhidos, somar.isChecked, salvar.isChecked)
                } catch (e: Exception) {
                    "Não consegui salvar a compra: ${e.message ?: "erro de conexão"}"
                }
                val acao: () -> Unit = { responder(r) }
                acao
            }
        }
        .setNegativeButton("Cancelar") { _, _ -> responder("Tudo bem, não mudei nada.") }
        .show()
}
