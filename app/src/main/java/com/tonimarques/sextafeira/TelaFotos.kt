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
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_FOTO_CAMERA
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_FOTO_GALERIA
import java.util.Calendar
import java.util.Locale

/*
 * Fotos e cadastro de produto: câmera, galeria, fundo da foto, Lens e fotos compartilhadas.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

/** Formulário de produto novo, já preenchido com o que foi falado. Foto pela câmera ou galeria e descrição pela IA. */
internal fun MainActivity.pedirFotoCamera() {
    val (_, uri) = FotoProvider.novaFoto(this)
    fotoDaCamera = uri
    val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = android.content.ClipData.newRawUri("foto", uri)
    try {
        startActivityForResult(i, PEDIDO_FOTO_CAMERA)
    } catch (e: Exception) {
        Toast.makeText(this, "Não achei o app da câmera.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.pedirFotoGaleria() {
    try {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PEDIDO_FOTO_GALERIA)
    } catch (e: Exception) {
        Toast.makeText(this, "Não achei a galeria.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.abrirCadastro(args: Map<String, Any>, fotoInicial: ByteArray? = null) {
    val loja = ferramentas.loja
    if (!loja.conectada) {
        responder("A loja ainda não está conectada. Abra os ajustes e toque em Conectar à loja.")
        return
    }
    val nomeFalado = args["nome"]?.toString().orEmpty()
    responder(if (nomeFalado.isBlank()) "Vamos cadastrar. Preencha o produto na tela." else "Vamos cadastrar $nomeFalado. Confira os dados na tela e adicione a foto.")
    var foto: ByteArray? = fotoInicial
    val f = Formulario(this, "Novo produto", "Preencha, confira e cadastre na loja")
    val categorias = Loja.CATEGORIAS

    // --- produto
    f.cartao("Produto")
    val (blocoNome, nome) = f.campo("Nome", nomeFalado, "Ex.: Gel de massagem morango 60ml")
    f.adicionar(blocoNome, 2)
    // sem site (catálogo na Vênus): a categoria é livre, do ramo de cada loja
    val interno = loja.ehInterno
    val categoria = f.seletor(categorias.map { it.second }, categorias.indexOfFirst { it.first == Loja.categoriaProvavel(nomeFalado) }.coerceAtLeast(0))
    val (blocoCategoria, categoriaLivre) = f.campo("Categoria", "", "Ex.: Vestidos, Bolsas, Acessórios")
    if (interno) f.adicionar(blocoCategoria, 12)
    else {
        f.adicionar(f.rotulo("Categoria"), 12)
        f.adicionar(categoria, 5)
    }
    fun categoriaEscolhida(): Pair<String, String> =
        if (interno) categoriaLivre.text.toString().trim().let { it to it.ifBlank { Perfil.ramo } } else categorias[categoria.selectedItemPosition]

    // --- preço, custo e estoque
    f.cartao("Preço e estoque")
    val numero = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    val (blocoPreco, preco) = f.campo("Preço de venda (R$)", (args["preco"] as? Int)?.let { "%d,%02d".format(it / 100, it % 100) }.orEmpty(), "29,90", numero)
    val (blocoCusto, custo) = f.campo("Preço de custo (R$)", (args["custo"] as? Int)?.let { "%d,%02d".format(it / 100, it % 100) }.orEmpty(), "15,00", numero)
    f.lado(blocoPreco, blocoCusto)
    val margem = f.aviso("O custo só você vê. Com ele, a Vênus calcula o lucro de cada venda.")
    f.adicionar(margem, 6)
    fun centavosDe(e: EditText): Int? = e.text.toString().trim().takeIf { it.isNotBlank() }
        ?.let { ComandosLocais.centavos(it.replace(Regex("[^0-9,.]"), "")) }
    val atualizarMargem = {
        val v = centavosDe(preco)
        val c = centavosDe(custo)
        margem.text = when {
            v != null && c != null && v > 0 -> {
                val lucro = v - c
                val pct = lucro * 100.0 / v
                if (lucro <= 0) "Atenção: o preço está abaixo do custo (prejuízo de ${Loja.dinheiro(-lucro)} por unidade)."
                else "Lucro de ${Loja.dinheiro(lucro)} por unidade · margem de ${pct.toInt()}%"
            }
            else -> "O custo só você vê. Com ele, a Vênus calcula o lucro de cada venda."
        }
        margem.setTextColor(if (v != null && c != null && v <= c) Color.parseColor("#FF8A80") else Formulario.COR_APAGADA)
    }
    val observador = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) = atualizarMargem()
    }
    preco.addTextChangedListener(observador)
    custo.addTextChangedListener(observador)
    atualizarMargem()
    val (blocoEstoque, estoque) = f.campo("Estoque (unidades)", (args["estoque"] as? Int)?.toString().orEmpty(), "Vazio se não controla", InputType.TYPE_CLASS_NUMBER)
    f.adicionar(blocoEstoque, 12)

    // --- foto
    f.cartao("Foto")
    val previa = f.previa()
    f.adicionar(previa, 2)
    val avisoFoto = f.aviso("Uma foto boa vende mais: fundo limpo e boa luz.")
    f.adicionar(avisoFoto, 6)
    val tirar = f.botao("Câmera") { pedirFotoCamera() }
    val galeria = f.botao("Galeria") { pedirFotoGaleria() }
    f.lado(tirar, galeria)
    fun mostrarFoto(bytes: ByteArray) {
        foto = bytes
        previa.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        previa.visibility = View.VISIBLE
        avisoFoto.text = "Foto pronta (${bytes.size / 1024} KB)."
    }
    val trocarFundo = f.botao("Trocar o fundo da foto") {
        val atual = foto
        if (atual == null) Toast.makeText(this, "Escolha a foto primeiro.", Toast.LENGTH_SHORT).show()
        else editarFundo(atual) { nova -> mostrarFoto(nova) }
    }
    f.adicionar(trocarFundo, 10)
    fotoInicial?.let { mostrarFoto(it) }

    // --- descrição e vitrine
    f.cartao("Descrição")
    val (blocoDescricao, descricao) = f.campo(if (interno) "Descrição (vai para o catálogo)" else "Texto que aparece no site", "", "Descrição curta (até 300 letras)",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, linhas = 3)
    descricao.maxLines = 6
    descricao.filters = arrayOf(android.text.InputFilter.LengthFilter(300))
    f.adicionar(blocoDescricao, 2)
    lateinit var ia: TextView
    ia = f.botao("✦  Escrever a descrição com IA") {
        val n = nome.text.toString().trim()
        if (n.isBlank()) {
            Toast.makeText(this, "Escreva o nome antes.", Toast.LENGTH_SHORT).show()
            return@botao
        }
        ia.isEnabled = false
        ia.text = "Escrevendo…"
        val cat = categoriaEscolhida().second
        val k = chave()
        val m = modelo()
        val fotoAtual = foto
        Thread {
            val t = Gemini.descreverProduto(k, m, fotoAtual, n, cat)
            runOnUiThread {
                ia.isEnabled = true
                ia.text = "✦  Escrever a descrição com IA"
                if (t != null) descricao.setText(t)
                else Toast.makeText(this, "A IA não conseguiu escrever agora. Escreva você mesmo ou tente de novo.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    if (chave().isNotBlank()) f.adicionar(ia, 10)
    val mostrar = f.interruptor(if (interno) "Já mostrar para as clientes" else "Já mostrar no site", interno)
    f.adicionar(mostrar, 12)

    cadastroFoto = { uri ->
        avisoFoto.text = "Preparando a foto…"
        Thread {
            val bytes = FotoProvider.prepararParaEnviar(this, uri)
            runOnUiThread {
                if (bytes == null) avisoFoto.text = "Não consegui ler essa foto. Tente outra." else mostrarFoto(bytes)
            }
        }.start()
    }
    f.aoFechar { cadastroFoto = null }

    val cadastrar = f.botao("Cadastrar produto", primario = true) {
        val n = nome.text.toString().trim()
        if (n.isBlank()) {
            Toast.makeText(this, "Informe o nome do produto.", Toast.LENGTH_SHORT).show()
            return@botao
        }
        val centavos = centavosDe(preco)
        if (preco.text.isNotBlank() && (centavos == null || centavos <= 0)) {
            Toast.makeText(this, "Confira o preço (ex.: 29,90).", Toast.LENGTH_SHORT).show()
            return@botao
        }
        val custoCentavos = centavosDe(custo)
        if (custo.text.isNotBlank() && (custoCentavos == null || custoCentavos < 0)) {
            Toast.makeText(this, "Confira o preço de custo (ex.: 15,00).", Toast.LENGTH_SHORT).show()
            return@botao
        }
        if (mostrar.isChecked && centavos == null && !interno) {
            Toast.makeText(this, "Para mostrar no site, informe o preço.", Toast.LENGTH_LONG).show()
            return@botao
        }
        val novo = Loja.NovoProduto(
            nome = n,
            categoria = categoriaEscolhida().first,
            descricao = descricao.text.toString().trim().ifBlank { null },
            precoCentavos = centavos,
            estoque = estoque.text.toString().trim().toIntOrNull(),
            imagemUrl = null,
            ativo = mostrar.isChecked,
            custoCentavos = custoCentavos,
        )
        val fotoFinal = foto
        f.fechar()
        comTempoLimite("CADASTRANDO…") {
            val r = try {
                val url = fotoFinal?.let { loja.enviarFoto(it) }
                loja.cadastrar(novo.copy(imagemUrl = url)).also {
                    Diario.anotar(this, "loja", "Produto cadastrado: ${novo.nome}" + (novo.precoCentavos?.let { p -> " por ${Loja.dinheiro(p)}" } ?: "") +
                        (novo.custoCentavos?.let { c -> ", custo ${Loja.dinheiro(c)}" } ?: "") + if (novo.ativo) ", já no site" else ", escondido", "tela")
                }
            } catch (e: Loja.ErroDaLoja) {
                "Não consegui cadastrar: ${e.message}"
            } catch (e: Exception) {
                "Não consegui falar com a loja agora. Confira a internet e tente de novo."
            }
            val acao: () -> Unit = { responder(r) }
            acao
        }
    }
    f.acoes(cadastrar, f.botao("Cancelar") { f.fechar() })
    f.mostrar()
}

// ----------------------------------------------------------- trocar o fundo da foto
/**
 * Editor de fundo: recorta o produto e coloca no fundo da loja, branco ou rosa; ou pede um fundo livre à IA.
 * [aoUsar]: quando veio do cadastro, devolve a foto pronta; sem ele, oferece salvar na galeria ou compartilhar.
 */
internal fun MainActivity.editarFundo(fotoInicial: ByteArray?, aoUsar: ((ByteArray) -> Unit)? = null) {
    var foto: ByteArray? = fotoInicial
    var pronta: ByteArray? = null
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val previa = android.widget.ImageView(this).apply {
        adjustViewBounds = true
        maxHeight = dp(260)
    }
    val aviso = TextView(this).apply {
        textSize = 13f
        setPadding(0, dp(6), 0, dp(6))
    }
    fun mostrar(b: ByteArray) = previa.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size))
    val linhaFoto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tirar = android.widget.Button(this).apply { text = "Tirar foto" }
    val galeria = android.widget.Button(this).apply { text = "Da galeria" }
    linhaFoto.addView(tirar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    linhaFoto.addView(galeria, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    val estilos = Fundo.Estilo.values().map { e -> android.widget.Button(this).apply { text = e.nome; tag = e } }
    val pedido = EditText(this).apply {
        hint = "Ou descreva um fundo (ex.: lençol de cetim vermelho)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    val ia = android.widget.Button(this).apply { text = "Pedir esse fundo à IA" }

    caixa.addView(linhaFoto)
    caixa.addView(previa)
    caixa.addView(aviso)
    estilos.forEach { caixa.addView(it) }
    if (chave().isNotBlank()) {
        caixa.addView(pedido)
        caixa.addView(ia)
    }
    if (foto != null) {
        mostrar(foto!!)
        aviso.text = "Escolha o fundo."
    } else aviso.text = "Escolha a foto do produto."

    fun processar(rotulo: String, trabalho: (ByteArray) -> ByteArray) {
        val base = foto ?: run {
            Toast.makeText(this, "Escolha a foto primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        aviso.text = "$rotulo…"
        (estilos + ia).forEach { it.isEnabled = false }
        Thread {
            val r = try {
                Result.success(trabalho(base))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                (estilos + ia).forEach { it.isEnabled = true }
                r.onSuccess {
                    pronta = it
                    mostrar(it)
                    aviso.text = "Pronto! Se não gostar, escolha outro fundo."
                }.onFailure {
                    aviso.text = it.message ?: "Não consegui trocar o fundo dessa foto."
                }
            }
        }.start()
    }
    estilos.forEach { b -> b.setOnClickListener { processar("Recortando o produto") { Fundo.trocar(it, b.tag as Fundo.Estilo) } } }
    ia.setOnClickListener {
        val texto = pedido.text.toString().trim()
        if (texto.isBlank()) {
            Toast.makeText(this, "Descreva o fundo que você quer.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val k = chave()
        processar("Pedindo à IA (pode levar até um minuto)") {
            val r = Gemini.trocarFundoComIA(k, it, texto)
                ?: throw Exception("A IA não fez essa edição (ela recusa algumas fotos de produtos íntimos). Use um dos fundos da loja.")
            // a IA pode devolver PNG: converte para JPEG quadrado como as outras
            val b = android.graphics.BitmapFactory.decodeByteArray(r, 0, r.size) ?: throw Exception("A IA devolveu uma imagem inválida.")
            java.io.ByteArrayOutputStream().use { out -> b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }
        }
    }

    val anterior = cadastroFoto
    cadastroFoto = { uri ->
        aviso.text = "Preparando a foto…"
        Thread {
            val bytes = FotoProvider.prepararParaEnviar(this, uri, 1600)
            runOnUiThread {
                if (bytes == null) aviso.text = "Não consegui ler essa foto."
                else {
                    foto = bytes
                    pronta = null
                    mostrar(bytes)
                    aviso.text = "Escolha o fundo."
                }
            }
        }.start()
    }
    tirar.setOnClickListener { pedirFotoCamera() }
    galeria.setOnClickListener { pedirFotoGaleria() }

    val dialogo = AlertDialog.Builder(this)
        .setTitle("Trocar o fundo")
        .setView(ScrollView(this).apply { addView(caixa) })
        .setPositiveButton(if (aoUsar != null) "Usar esta foto" else "Salvar na galeria", null)
        .setNegativeButton("Fechar", null)
        .apply { if (aoUsar == null) setNeutralButton("Compartilhar", null) }
        .create()
    dialogo.setOnDismissListener { cadastroFoto = anterior }
    dialogo.setOnShowListener {
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val r = pronta ?: run {
                Toast.makeText(this, "Escolha um fundo antes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (aoUsar != null) {
                aoUsar(r)
                dialogo.dismiss()
            } else {
                Toast.makeText(this, if (salvarNaGaleria(r)) "Foto salva na galeria (pasta Vênus)." else "Não consegui salvar. Use Compartilhar.", Toast.LENGTH_LONG).show()
            }
        }
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val r = pronta ?: run {
                Toast.makeText(this, "Escolha um fundo antes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            compartilharFoto(r)
        }
    }
    dialogo.show()
}

internal fun MainActivity.salvarNaGaleria(jpeg: ByteArray): Boolean = try {
    if (Build.VERSION.SDK_INT < 29) false else {
        val valores = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "venus_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vênus")
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
        uri != null && contentResolver.openOutputStream(uri)?.use { it.write(jpeg); true } == true
    }
} catch (e: Exception) {
    false
}

internal fun MainActivity.compartilharFoto(jpeg: ByteArray) {
    val (arquivo, uri) = FotoProvider.novaFoto(this)
    arquivo.writeBytes(jpeg)
    val i = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = android.content.ClipData.newRawUri("foto", uri)
    startActivity(Intent.createChooser(i, "Compartilhar foto"))
}

internal fun MainActivity.mostrarImagem(titulo: String, imagem: android.graphics.Bitmap, jpeg: ByteArray) {
    val vista = android.widget.ImageView(this).apply {
        setImageBitmap(imagem)
        adjustViewBounds = true
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    AlertDialog.Builder(this)
        .setTitle(titulo)
        .setView(ScrollView(this).apply { addView(vista) })
        .setPositiveButton("Compartilhar") { _, _ -> compartilharFoto(jpeg) }
        .setNeutralButton("Salvar na galeria") { _, _ ->
            Toast.makeText(this, if (salvarNaGaleria(jpeg)) "Salvo na galeria, na pasta Vênus." else "Não consegui salvar.", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Fechar", null)
        .show()
}

// ----------------------------------------------------------- pesquisa de produto pela foto (como o Google Lens)
internal fun MainActivity.pesquisarProdutoDaFoto(foto: Arquivos.Documento, pedido: String) {
    val bytes = foto.bytes ?: return
    val k = chave()
    val m = modelo()
    val assistente = obterGemini()
    comTempoLimite("PROCURANDO O PRODUTO…") {
        val produto = Gemini.identificarProduto(k, m, bytes, foto.mime, pedido)
        val oferta = produto?.let { p ->
            val instrucao = "Você compara preços para um lojista do Brasil. Pesquise o produto em lojas online brasileiras " +
                "(Mercado Livre, Amazon, Shopee, Magazine Luiza, Americanas, lojas especializadas e atacadistas). " +
                "Responda em português, sem markdown e sem tabela: até 6 ofertas, uma por linha, no formato " +
                "\"Loja — R$ preço — link direto do produto\". Depois, numa linha: \"Menor preço: ...\" e, se fizer sentido, a faixa de preço comum. " +
                "Use só preços que você encontrou; se não achar o produto exato, diga e mostre os mais parecidos."
            val pergunta = "Preço de ${p.termos}" + (if (p.marca.isNotBlank() && !p.termos.contains(p.marca, true)) " ${p.marca}" else "")
            assistente?.buscarNaInternet(pergunta, instrucao) ?: SegundaIA.pesquisar(pergunta, instrucao)
        }
        val acao: () -> Unit = {
            if (produto == null) {
                responder("Não consegui reconhecer o produto dessa foto agora. Abra no Google Lens pelo botão abaixo.")
            } else {
                val inicio = "Parece ser: ${produto.nome}" + (if (produto.descricao.isNotBlank()) ". ${produto.descricao}" else ".")
                if (oferta != null) {
                    responderComLinks("$inicio\n\n${oferta.texto}")
                    mostrarFontes(oferta.fontes)
                } else {
                    responder("$inicio Não consegui buscar os preços agora; use os atalhos abaixo.")
                }
            }
            val termos = produto?.termos.orEmpty()
            val q = java.net.URLEncoder.encode(termos, "UTF-8")
            val atalhos = mutableListOf<Pair<String, () -> Unit>>()
            if (termos.isNotBlank()) {
                atalhos += "Google Shopping" to { abrirLink("https://www.google.com/search?tbm=shop&q=$q") }
                atalhos += "Mercado Livre" to { abrirLink("https://lista.mercadolivre.com.br/" + termos.trim().replace(Regex("\\s+"), "-")) }
                atalhos += "Shopee" to { abrirLink("https://shopee.com.br/search?keyword=$q") }
            }
            if (ferramentas.loja.conectada) atalhos += "Google Lens" to { abrirNoLens(bytes) }
            mostrarSugestoes(atalhos)
        }
        acao
    }
}

/** Sobe a foto (endereço público da loja) e abre a busca por imagem do Google Lens. */
internal fun MainActivity.abrirNoLens(bytes: ByteArray) {
    Toast.makeText(this, "Abrindo no Google Lens…", Toast.LENGTH_SHORT).show()
    Thread {
        val url = try {
            Loja.enderecoCompleto(ferramentas.loja.enviarFoto(bytes))
        } catch (e: Exception) {
            null
        }
        runOnUiThread {
            if (url == null) Toast.makeText(this, "Não consegui enviar a foto para o Lens.", Toast.LENGTH_LONG).show()
            else abrirLink("https://lens.google.com/uploadbyurl?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
        }
    }.start()
}

/** Foto recebida pelo "Compartilhar" de outro app. */
internal fun MainActivity.fotoCompartilhada(uri: Uri) {
    AlertDialog.Builder(this)
        .setTitle("O que fazer com a foto?")
        .setItems(arrayOf("Ler nota de compra (custo e estoque)", "Trocar o fundo", "Cadastrar produto com esta foto")) { _, qual ->
            when (qual) {
                0 -> lerNota(uri)
                else -> Thread {
                    val bytes = FotoProvider.prepararParaEnviar(this, uri, if (qual == 1) 1600 else 1200)
                    runOnUiThread {
                        when {
                            bytes == null -> responder("Não consegui abrir essa foto.")
                            qual == 1 -> editarFundo(bytes)
                            else -> abrirCadastro(emptyMap(), bytes)
                        }
                    }
                }.start()
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
