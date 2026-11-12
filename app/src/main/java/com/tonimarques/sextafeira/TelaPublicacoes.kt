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
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_NOTIFICACOES
import java.util.Calendar
import java.util.Locale

/*
 * Publicações: stories do dia, posts programados, ideias de post e Instagram.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

// ---------------------------------------------------------------- publicações
/** Tudo de publicação num lugar só: stories do dia, automático, enquete, horários, posts, ideias e contas. */
internal fun MainActivity.abrirPublicacoes() {
    if (ajustes?.isShowing == true) return
    val n = Marca.nome
    val secoes = listOf(
        TelaDeAjustes.Secao(
            "Stories do dia", listOf(
                TelaDeAjustes.Ajuste("Ver os stories de hoje", {
                    "Prévia da manhã e da tarde, Publicar Instagram, Publicar WhatsApp e Gerar outro. Também abre ao dizer: stories do dia."
                }) { ajustes?.dismiss(); abrirStoriesDoDia() },
                TelaDeAjustes.Ajuste("Stories automáticos (manhã e tarde)", { StoriesDoDia.descricao(this) }, { StoriesDoDia.ligado(this) }) {
                    StoriesDoDia.definirLigado(this, !StoriesDoDia.ligado(this))
                    ajustes?.atualizar()
                },
                TelaDeAjustes.Ajuste("Story da tarde com enquete", {
                    "A API do Instagram não põe adesivos. À tarde a $n prepara a imagem, copia a pergunta e abre o Instagram: " +
                        "você toca em Enquete, cola e publica. Desligado: a tarde também sai sozinha, sem enquete."
                }, { StoriesDoDia.tardeInterativa(this) }) {
                    StoriesDoDia.definirTardeInterativa(this, !StoriesDoDia.tardeInterativa(this))
                    ajustes?.atualizar()
                },
                TelaDeAjustes.Ajuste("Horários dos stories", {
                    "Manhã às ${StoriesDoDia.horaTexto(StoriesDoDia.horario(this, "manha"))} e tarde às ${StoriesDoDia.horaTexto(StoriesDoDia.horario(this, "tarde"))}. Toque para mudar."
                }) { escolherHorariosDosStories() },
            )
        ),
        TelaDeAjustes.Secao(
            "Posts no Instagram", listOf(
                TelaDeAjustes.Ajuste("Programar um post", { "Feed ou story, com foto, legenda e dia e hora para sair sozinho." }) {
                    ajustes?.dismiss()
                    programarPost(emptyMap())
                },
                TelaDeAjustes.Ajuste("Posts programados", {
                    val k = Postagens.pendentes(this).size
                    if (k == 0) "Nenhum post na fila." else "$k ${if (k == 1) "post na fila" else "posts na fila"}. Toque para ver, mudar ou apagar."
                }) {
                    ajustes?.dismiss()
                    listarPosts()
                },
                TelaDeAjustes.Ajuste("Ideias de post", { "As ideias da $n para a semana, prontas para virar post." }) {
                    ajustes?.dismiss()
                    mostrarIdeiasDePost()
                },
                TelaDeAjustes.Ajuste("Ideias de vídeo (Reels)", { "Cria ideias de vídeo com os produtos do catálogo: roteiro, pedido pronto para um gerador de vídeo por IA, legenda e hashtags." }) {
                    ajustes?.dismiss()
                    ideiasDeVideo()
                },
            )
        ),
        TelaDeAjustes.Secao(
            "Contas e respostas", listOf(
                TelaDeAjustes.Ajuste("Instagram da loja", {
                    Instagram.usuario(this)?.takeIf { Instagram.conectado(this) }?.let {
                        "Conectado como @$it. Os posts programados e os stories saem sozinhos."
                    } ?: "Desconectado: os stories chegam no aviso para postar com um toque. Toque para colar o token da Meta."
                }) { conectarInstagram() },
                TelaDeAjustes.Ajuste("Respostas do Instagram (Robô)", {
                    "As respostas prontas para colar nas Perguntas frequentes e Respostas salvas do Instagram."
                }) { startActivity(android.content.Intent(this, com.tonimarques.sextafeira.robo.TelaRespostas::class.java)) },
            )
        ),
    )
    ajustes = TelaDeAjustes(this, versaoDoApp(), secoes, "PUBLICAÇÕES").also {
        it.setOnDismissListener { ajustes = null }
        it.show()
    }
}

// ----------------------------------------------------------- posts do Instagram
/** Formulário de post programado: foto (com troca de fundo), feed ou story, dia e hora, legenda pela IA. */
internal fun MainActivity.programarPost(args: Map<String, Any>) {
    var foto: ByteArray? = null
    var quando = (args["quando"] as? Long)?.takeIf { it > System.currentTimeMillis() } ?: Postagens.proximoHorario(this)
    val produtoFalado = args["produto"]?.toString().orEmpty()
    var contexto = produtoFalado
    var linkDoProduto = ""
    val sozinha = Postagens.podePublicarSozinho(this)
    val f = Formulario(this, "Post no Instagram", if (sozinha) "Na hora marcada eu publico sozinha" else "Na hora marcada eu te aviso com tudo pronto")

    // --- formato
    f.cartao("Formato")
    lateinit var legenda: EditText
    val (escolhaTipo, tipoMarcado) = f.escolha(listOf("Post no feed", "Story"), if (args["tipo"] == "story") 1 else 0) { i ->
        legenda.hint = if (i == 1) "Texto que vai escrito no story (curto)" else "Legenda do post, com hashtags"
    }
    f.adicionar(escolhaTipo, 2)
    val dicaTipo = f.aviso("No story, a Vênus monta a arte em tela cheia com a foto inteira, o texto e o endereço do produto.")
    f.adicionar(dicaTipo, 8)

    // --- foto
    f.cartao("Foto")
    val previa = f.previa()
    f.adicionar(previa, 2)
    val aviso = f.aviso("Escolha a foto do post.")
    f.adicionar(aviso, 6)
    fun mostrarFoto(b: ByteArray) {
        foto = b
        previa.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size))
        previa.visibility = View.VISIBLE
        aviso.text = ""
        aviso.visibility = View.GONE
    }
    f.lado(f.botao("Câmera") { pedirFotoCamera() }, f.botao("Galeria") { pedirFotoGaleria() })
    f.adicionar(f.botao("Trocar o fundo") {
        val atual = foto
        if (atual == null) Toast.makeText(this, "Escolha a foto primeiro.", Toast.LENGTH_SHORT).show()
        else editarFundo(atual) { nova -> mostrarFoto(nova) }
    }, 10)

    // --- quando
    f.cartao("Quando")
    lateinit var horario: TextView
    fun mostrarHorario() {
        horario.text = "🗓  " + Lembretes.quandoFalado(quando).replaceFirstChar { it.uppercase() }
    }
    horario = f.botao("") {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = quando }
        android.app.DatePickerDialog(this, { _, a, m, d ->
            android.app.TimePickerDialog(this, { _, h, min ->
                quando = java.util.Calendar.getInstance().apply { clear(); set(a, m, d, h, min) }.timeInMillis
                mostrarHorario()
            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }
    mostrarHorario()
    f.adicionar(horario, 2)
    f.adicionar(f.aviso("Toque para escolher o dia e a hora. Boa hora para a loja: entre 19h e 21h."), 6)

    // --- texto
    f.cartao("Texto")
    val (blocoLegenda, campoLegenda) = f.campo("Legenda", "", if (tipoMarcado() == 1) "Texto que vai escrito no story (curto)" else "Legenda do post, com hashtags",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, linhas = 4)
    legenda = campoLegenda
    legenda.maxLines = 10
    f.adicionar(blocoLegenda, 2)
    if (chave().isNotBlank()) {
        lateinit var ia: TextView
        ia = f.botao("✦  Escrever com IA") {
            ia.isEnabled = false
            ia.text = "Escrevendo…"
            val k = chave()
            val m = modelo()
            val fotoAtual = foto
            val ehStory = tipoMarcado() == 1
            val ctx = contexto
            Thread {
                val t = Gemini.legendaInstagram(k, m, fotoAtual, ctx, ehStory)
                runOnUiThread {
                    ia.isEnabled = true
                    ia.text = "✦  Escrever com IA"
                    if (t != null) legenda.setText(t) else Toast.makeText(this, "A IA não conseguiu agora. Tente de novo.", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        f.adicionar(ia, 10)
    }

    val anterior = cadastroFoto
    cadastroFoto = { uri ->
        aviso.visibility = View.VISIBLE
        aviso.text = "Preparando a foto…"
        Thread {
            val b = FotoProvider.prepararParaEnviar(this, uri, 1440)
            runOnUiThread { if (b == null) aviso.text = "Não consegui ler essa foto." else mostrarFoto(b) }
        }.start()
    }
    f.aoFechar { cadastroFoto = anterior }

    // "post do gel": usa a foto e os dados do produto da loja
    if (produtoFalado.isNotBlank() && ferramentas.loja.conectada) {
        aviso.text = "Buscando $produtoFalado na loja…"
        Thread {
            val achado = try {
                Loja.acharProdutos(ferramentas.loja.produtos(), produtoFalado).firstOrNull()
            } catch (e: Exception) {
                null
            }
            val url = achado?.bruto?.optString("imageUrl")?.let { Loja.enderecoCompleto(it) }?.takeIf { it.startsWith("http") }
            val bytes = url?.let {
                try {
                    java.net.URL(it).openStream().use { s -> s.readBytes() }
                } catch (e: Exception) {
                    null
                }
            }
            runOnUiThread {
                if (achado != null) {
                    linkDoProduto = "${Loja.SITE}/produto/${achado.id}"
                    contexto = "produto ${achado.nome}" + (achado.precoCentavos?.let { ", por ${Loja.dinheiro(it)}" } ?: "") +
                        (achado.bruto?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }?.let { ". $it" } ?: "")
                    if (bytes != null) mostrarFoto(bytes) else aviso.text = "Achei ${achado.nome}, mas sem foto. Escolha uma."
                    dicaTipo.text = "Produto: ${achado.nome}. No story vai o endereço da página dele no site."
                } else aviso.text = "Não achei $produtoFalado na loja. Escolha a foto."
            }
        }.start()
    }

    fun salvar(): Boolean {
        val fotoFinal = foto ?: run {
            Toast.makeText(this, "Escolha a foto.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (quando <= System.currentTimeMillis()) {
            Toast.makeText(this, "Escolha um horário no futuro.", Toast.LENGTH_SHORT).show()
            return false
        }
        val p = Postagens.criar(this, quando, if (tipoMarcado() == 1) "story" else "feed", legenda.text.toString(), fotoFinal, linkDoProduto)
        focarPost(p.id)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACOES)
        }
        responder(
            "Programado: ${if (p.ehStory) "story" else "post"} ${Lembretes.quandoFalado(p.quando)}. " +
                if (sozinha) "Na hora eu publico sozinha. Para mudar, diga: muda para hoje às 20h, no feed, ou posta agora."
                else "Na hora eu te aviso com tudo pronto."
        )
        return true
    }
    f.acoes(
        f.botao("Programar", primario = true) { if (salvar()) f.fechar() },
        f.botao("+ Outro") {
            if (salvar()) {
                val tipo = if (tipoMarcado() == 1) "story" else "feed"
                f.fechar()
                programarPost(mapOf("tipo" to tipo))
            }
        },
    )
    f.mostrar()
}

internal fun MainActivity.listarPosts() {
    val lista = Postagens.pendentes(this)
    if (lista.isEmpty()) {
        responder("Nenhum post programado. Diga, por exemplo: programa um post do gel amanhã às 19h.")
        return
    }
    responder("Você tem ${lista.size} ${if (lista.size == 1) "post programado" else "posts programados"}. O próximo é ${Lembretes.quandoFalado(lista[0].quando)}.")
    val itens = lista.map { p ->
        "${Lembretes.quandoFalado(p.quando)} · ${if (p.ehStory) "Story" else "Feed"}" + (if (p.legenda.isNotBlank()) "\n" + p.legenda.take(60) else "")
    }
    AlertDialog.Builder(this)
        .setTitle("Posts programados")
        .setItems(itens.toTypedArray()) { _, i ->
            val p = lista[i]
            AlertDialog.Builder(this)
                .setTitle(if (p.ehStory) "Story" else "Post")
                .setMessage(p.legenda.ifBlank { "(sem legenda)" })
                .setPositiveButton("Postar agora") { _, _ -> postarAgora(p.id) }
                .setNegativeButton("Cancelar este post") { _, _ ->
                    Postagens.cancelar(this, p.id)
                    responder("Post cancelado.")
                }
                .setNeutralButton("Voltar", null)
                .show()
        }
        .setNegativeButton("Fechar", null)
        .show()
}

/** Publica já: pela API, se o Instagram estiver conectado; senão abre o Instagram com a foto e a legenda copiada. */
internal fun MainActivity.postarAgora(id: Int) {
    val p = Postagens.achar(this, id) ?: return responder("Esse post não existe mais.")
    if (!java.io.File(p.foto).exists()) return responder("A foto desse post sumiu. Programe de novo.")
    if (p.publicado) return responder("Esse post já foi publicado.")
    if (!Postagens.podePublicarSozinho(this)) return postarPeloApp(p)
    mudarEstado(Orbe.Estado.PENSANDO, "PUBLICANDO…")
    Thread {
        val resultado = try {
            Result.success(Postagens.publicarPelaApi(this, p))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            resultado.onSuccess { link ->
                Diario.anotar(this, "instagram", (if (p.ehStory) "Story" else "Post") + " publicado agora" + (if (link.isNotBlank()) " ($link)" else ""), "voz")
                responder(if (p.ehStory) "Story publicado no Instagram." else "Post publicado no Instagram.")
                if (link.isNotBlank()) mostrarFontes(listOf(Web.Fonte("Ver o post no Instagram", link)))
            }.onFailure { e ->
                responder("Não consegui publicar sozinha: ${e.message} Vou abrir o Instagram para você postar.")
                principal.postDelayed({ postarPeloApp(p) }, 2500)
            }
        }
    }.start()
}

/** Copia a legenda e abre o Instagram com a foto (feed ou story, você escolhe lá). */
internal fun MainActivity.postarPeloApp(p: Postagens.Post) {
    val id = p.id
    val arquivo = java.io.File(p.foto)
    if (!arquivo.exists()) return responder("A foto desse post sumiu. Programe de novo.")
    if (p.legenda.isNotBlank()) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Legenda", p.legenda))
    }
    val (copia, uri) = FotoProvider.novaFoto(this)
    arquivo.copyTo(copia, overwrite = true)
    val envio = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    envio.clipData = android.content.ClipData.newRawUri("foto", uri)
    val instagram = Intent(envio).setPackage("com.instagram.android")
    try {
        startActivity(if (instagram.resolveActivity(packageManager) != null) instagram else Intent.createChooser(envio, "Publicar"))
        Postagens.marcarPublicado(this, id)
        responder(if (p.legenda.isNotBlank()) "Abri o Instagram com a foto. A legenda já está copiada: é só colar e publicar." else "Abri o Instagram com a foto.")
    } catch (e: Exception) {
        responder("Não consegui abrir o Instagram.")
    }
}

// ----------------------------------------------------------- stories do dia (dica ou curiosidade, manhã e tarde)
internal fun MainActivity.storiesAutomaticos(ligar: Boolean?) {
    if (ligar != null) StoriesDoDia.definirLigado(this, ligar)
    val estado = if (StoriesDoDia.ligado(this)) "ligados" else "desligados"
    responder(
        "Os stories automáticos estão $estado. " + StoriesDoDia.descricao(this) +
            " Diga: mostra o story do dia, para ver o próximo, ou posta a dica agora."
    )
}

internal fun MainActivity.previaDoStory() {
    responder("Vou montar o próximo story para você ver, só um instante.")
    Thread {
        val r = try {
            Result.success(StoriesDoDia.previa(this))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onSuccess { (tema, arquivo) ->
                responder("O próximo story é \"${tema.titulo}\". Abri a imagem para você ver; ela não foi publicada.")
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}"), "image/jpeg")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (e: Exception) {
                    responder("Não achei um app para abrir a imagem.")
                }
            }.onFailure { responder("Não consegui montar o story: ${it.message}") }
        }
    }.start()
}

/** Do aviso do story da tarde: copia a pergunta da enquete e abre o Instagram com a imagem. */
internal fun MainActivity.abrirStoryComEnquete(arquivo: String, pergunta: String) {
    val f = java.io.File(FotoProvider.pasta(this), arquivo)
    if (!f.exists()) return responder("A imagem desse story não está mais aqui (passou mais de um dia). Diga: prepara o story com enquete.")
    if (pergunta.isNotBlank()) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Enquete", pergunta))
    }
    try {
        whatsappDepois = f
        saiuParaOInstagram = false
        startActivity(StoriesDoDia.intencaoInstagram(f))
        Toast.makeText(
            this,
            (if (pergunta.isNotBlank()) "Escolha Story, toque no adesivo Enquete e cole: $pergunta. " else "Escolha Story e publique. ") +
                "Ao voltar, eu abro o WhatsApp para o status.",
            Toast.LENGTH_LONG,
        ).show()
    } catch (e: ActivityNotFoundException) {
        whatsappDepois = null
        responder("Não achei o Instagram neste celular.")
    }
}

// ----------------------------------------------------------- página Stories do dia
/** Os stories de hoje (manhã e tarde): prévia, Publicar no Instagram, Publicar no WhatsApp e Gerar outro. */
internal fun MainActivity.abrirStoriesDoDia() {
    val f = Formulario(this, "Stories do dia", "Veja, publique no Instagram e no status do WhatsApp ou gere outro")
    for (turno in listOf("manha", "tarde")) {
        val hora = StoriesDoDia.horaTexto(StoriesDoDia.horario(this, turno))
        f.cartao("${StoriesDoDia.nomeTurno(turno).replaceFirstChar { it.uppercase() }} · $hora")
        val r = StoriesDoDia.registroDeHoje(this, turno)
        if (r == null || !r.arquivo.exists()) {
            f.adicionar(f.aviso(if (r == null) "Ainda não preparado hoje. Sai sozinho às $hora, ou prepare agora." else "A imagem de hoje não está mais no celular. Gere outra."), 2)
            f.adicionar(f.botao("Preparar agora", primario = true) { gerarOutroStory(f, turno) })
            continue
        }
        val previa = f.previa().apply {
            visibility = android.view.View.VISIBLE
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(r.arquivo.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }))
            setOnClickListener { verFotoGrande(r.arquivo) }
        }
        f.adicionar(previa, 2)
        val situacao = listOf(
            if (r.automatico) "Instagram: publicado sozinho ✓" else if (r.instagram) "Instagram: aberto para publicar ✓" else "Instagram: falta publicar",
            if (r.whatsapp) "WhatsApp: aberto para o status ✓" else "WhatsApp: falta pôr no status",
        ).joinToString(" · ")
        f.adicionar(f.aviso("\"${r.titulo}\"\n$situacao" + (if (r.pergunta.isNotBlank()) "\nEnquete: ${r.pergunta} (copio ao abrir o Instagram)" else "")), 8)
        val instagram = f.botao("Publicar Instagram", primario = !r.instagram && !r.automatico) {
            if (r.pergunta.isNotBlank()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Enquete", r.pergunta))
            }
            try {
                startActivity(StoriesDoDia.intencaoInstagram(r.arquivo))
                StoriesDoDia.marcarCanal(this, turno, "instagram")
                f.fechar()
                Toast.makeText(this, if (r.pergunta.isNotBlank()) "Escolha Story, ponha o adesivo Enquete e cole: ${r.pergunta}" else "Escolha Story e publique.", Toast.LENGTH_LONG).show()
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Não achei o Instagram neste celular.", Toast.LENGTH_SHORT).show()
            }
        }
        val whatsapp = f.botao("Publicar WhatsApp", primario = r.instagram || r.automatico) {
            try {
                startActivity(StoriesDoDia.intencaoWhatsApp(this, r.arquivo))
                StoriesDoDia.marcarCanal(this, turno, "whatsapp")
                f.fechar()
                Toast.makeText(this, "Escolha Meu status e envie.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Não consegui abrir o WhatsApp.", Toast.LENGTH_SHORT).show()
            }
        }
        f.lado(instagram, whatsapp)
        f.adicionar(f.botao("Não gostei: gerar outro") { gerarOutroStory(f, turno) }, 8)
    }
    f.cartao("Automático")
    f.adicionar(f.aviso(StoriesDoDia.descricao(this)), 2)
    f.acoes(f.botao("Fechar", primario = true) { f.fechar() }, f.botao("Horários e ajustes") {
        f.fechar()
        abrirPublicacoes()
    })
    f.mostrar()
}

internal fun MainActivity.gerarOutroStory(f: Formulario, turno: String) {
    f.fechar()
    responder("Gerando outro story da ${StoriesDoDia.nomeTurno(turno)}…")
    Thread {
        val r = try {
            Result.success(StoriesDoDia.refazer(applicationContext, turno))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onSuccess {
                responder("Pronto: \"${it.titulo}\".")
                abrirStoriesDoDia()
            }.onFailure { responder("Não consegui gerar outro: ${it.message}") }
        }
    }.start()
}

/** Chamado no onResume: se voltou do Instagram com um story pendente, abre o status do WhatsApp. */
internal fun MainActivity.continuarStoryNoWhatsApp() {
    val f = whatsappDepois ?: return
    if (!saiuParaOInstagram) return
    whatsappDepois = null
    saiuParaOInstagram = false
    orbe.postDelayed({
        try {
            startActivity(StoriesDoDia.intencaoWhatsApp(this, f))
            Toast.makeText(this, "Agora o status do WhatsApp: escolha Meu status e envie.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            responder("Não consegui abrir o WhatsApp: ${e.message}")
        }
    }, 700)
}

internal fun MainActivity.storyComEnqueteAgora() {
    responder("Vou preparar o story com enquete; quando estiver pronto, chega o aviso para abrir no Instagram.")
    Thread {
        val r = try {
            Result.success(StoriesDoDia.prepararComEnquete(this))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onSuccess { responder("Pronto: \"${it.titulo}\". Toque no aviso para abrir o Instagram; a pergunta da enquete é: ${it.pergunta}") }
                .onFailure { responder("Não consegui preparar: ${it.message}") }
        }
    }.start()
}

internal fun MainActivity.storyDoDiaAgora() {
    responder("Vou montar e publicar o story de dica agora.")
    Thread {
        val r = try {
            Result.success(StoriesDoDia.publicar(this, forcar = true))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onSuccess { responder("Publiquei no Instagram o story \"${it.titulo}\". Chegou um aviso para pôr no status do WhatsApp também.") }
                .onFailure { responder("Não consegui publicar: ${it.message}") }
        }
    }.start()
}

internal fun MainActivity.escolherHorariosDosStories() {
    val manha = StoriesDoDia.horario(this, "manha")
    android.app.TimePickerDialog(this, { _, h, m ->
        StoriesDoDia.definirHorario(this, "manha", h * 60 + m)
        val tarde = StoriesDoDia.horario(this, "tarde")
        android.app.TimePickerDialog(this, { _, h2, m2 ->
            StoriesDoDia.definirHorario(this, "tarde", h2 * 60 + m2)
            ajustes?.atualizar()
            Toast.makeText(this, "Stories às ${StoriesDoDia.horaTexto(h * 60 + m)} e às ${StoriesDoDia.horaTexto(h2 * 60 + m2)}", Toast.LENGTH_LONG).show()
        }, tarde / 60, tarde % 60, true).apply { setTitle("Story da tarde") }.show()
    }, manha / 60, manha % 60, true).apply { setTitle("Story da manhã") }.show()
}

/**
 * Pede ideias novas ao Gemini (em segundo plano) e entrega (título com data e formato, legenda); null se não deu.
 * Usado para gerar tudo de novo, trocar uma ideia e repor a que acabou de ser programada.
 */
internal fun MainActivity.gerarIdeiasNovas(quantidade: Int, evitar: List<String>, pronto: (List<Pair<String, String>>?) -> Unit) {
    val chave = chave()
    if (chave.isBlank()) return pronto(null)
    Thread {
        val novas = try {
            val datas = Gestao.textoDatas(Gestao.proximasDatas(Calendar.getInstance(), 45), 45)
            val nomes = try {
                ferramentas.loja.takeIf { it.conectada }?.produtos()?.filter { it.ativo }?.take(60)?.joinToString(", ") { it.nome }.orEmpty()
            } catch (e: Exception) {
                ""
            }
            val hoje = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            Gemini.gerarJson(chave, modelo(), Estudo.pedidoDeIdeias(datas, nomes, evitar, hoje, quantidade))?.let { Estudo.lerIdeias(it) }?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
        runOnUiThread { pronto(novas) }
    }.start()
}

private fun MainActivity.guardarIdeias(novas: List<Pair<String, String>>) {
    for ((texto, legenda) in novas) memoria.guardarVetor("ideia_post", texto, legenda)
}

/** Troca uma ideia por outra nova. */
internal fun MainActivity.gerarOutraIdeia(ideia: IdeiasDePost.Ideia, depois: () -> Unit) {
    responder("Pensando em outra ideia no lugar de \"${ideia.titulo}\"…")
    val todas = try { memoria.itensDoTipo("ideia_post", Estudo.IDEIAS_POR_SEMANA * 2).map { it.texto } } catch (e: Exception) { emptyList() }
    gerarIdeiasNovas(1, todas) { novas ->
        if (novas == null) return@gerarIdeiasNovas responder("Não consegui uma ideia nova agora (confira a chave do Gemini e tente de novo).")
        memoria.apagarItem(ideia.id)
        guardarIdeias(novas)
        Diario.anotar(this, "instagram", "Ideia de post trocada: ${ideia.titulo}", "tela", true)
        depois()
    }
}

/** Joga fora as ideias de agora e gera um lote inteiro novo (sem repetir os títulos de antes). */
internal fun MainActivity.gerarTodasAsIdeias(depois: () -> Unit) {
    responder("Gerando um lote novo de ideias de post…")
    val antigas = try { memoria.itensDoTipo("ideia_post", Estudo.IDEIAS_POR_SEMANA * 2) } catch (e: Exception) { emptyList() }
    gerarIdeiasNovas(Estudo.IDEIAS_POR_SEMANA, antigas.map { it.texto }) { novas ->
        if (novas == null) return@gerarIdeiasNovas responder("Não consegui gerar as ideias agora (confira a chave do Gemini e tente de novo).")
        antigas.forEach { memoria.apagarItem(it.id) }
        guardarIdeias(novas)
        Diario.anotar(this, "instagram", "Ideias de post geradas de novo (${novas.size}).", "tela", true)
        depois()
    }
}

/** A ideia programada sai da lista de sugestões e uma nova entra no lugar (em silêncio; se falhar, só sai). */
internal fun MainActivity.tirarIdeiaProgramada(ideia: IdeiasDePost.Ideia) {
    memoria.apagarItem(ideia.id)
    val restantes = try { memoria.itensDoTipo("ideia_post", Estudo.IDEIAS_POR_SEMANA * 2).map { it.texto } } catch (e: Exception) { emptyList() }
    gerarIdeiasNovas(1, restantes + ideia.titulo) { novas -> if (novas != null) guardarIdeias(novas) }
}

/** Página das ideias de post que a Vênus criou: cada uma com Programar post, Programar story e Gerar outra. */
internal fun MainActivity.mostrarIdeiasDePost() {
    Thread {
        val ideias = try {
            memoria.itensDoTipo("ideia_post", Estudo.IDEIAS_POR_SEMANA * 2).map { IdeiasDePost.ler(it) }
        } catch (e: Exception) {
            emptyList()
        }
        runOnUiThread {
            val f = Formulario(this, "Ideias de post", "Sugestões da ${Marca.nome} para as próximas semanas")
            val dia = java.text.SimpleDateFormat("dd/MM", Locale("pt", "BR"))
            f.adicionar(f.botao(if (ideias.isEmpty()) "Gerar ideias agora" else "Gerar todas de novo", primario = true) {
                f.fechar(); gerarTodasAsIdeias { mostrarIdeiasDePost() }
            }, 4)
            if (ideias.isEmpty()) f.adicionar(f.aviso("Ainda não há ideias. Elas saem no estudo da semana; toque acima para gerar já."), 8)
            for (ideia in ideias) {
                f.cartao(ideia.titulo.take(40))
                f.adicionar(f.aviso("Sugerido: " + (ideia.data?.let { dia.format(it.time) } ?: "quando quiser") + " · " + ideia.formato), 2)
                f.adicionar(TextView(this).apply {
                    text = ideia.legenda
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 6
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), dp(4), dp(4), 0)
                }, 6)
                f.lado(
                    f.botao("Programar post", primario = ideia.formato != "story") { f.fechar(); prepararIdeia(ideia, "feed") },
                    f.botao("Programar story", primario = ideia.formato == "story") { f.fechar(); prepararIdeia(ideia, "story") },
                )
                f.adicionar(f.botao("↻ Gerar outra ideia") { f.fechar(); gerarOutraIdeia(ideia) { mostrarIdeiasDePost() } }, 6)
            }
            f.acoes(f.botao("Fechar", primario = true) { f.fechar() })
            f.mostrar()
        }
    }.start()
}

/** Monta a arte da ideia (com a foto do produto citado) e abre a tela para conferir e programar. */
internal fun MainActivity.prepararIdeia(ideia: IdeiasDePost.Ideia, tipo: String, variacao: Int = 0) {
    responder(if (variacao == 0) "Montando a arte de \"${ideia.titulo}\"…" else "Montando outra arte…")
    Thread {
        val r = try {
            val produto = try {
                ferramentas.loja.takeIf { it.conectada }?.produtos()?.let { IdeiasDePost.produtoDaIdeia(ideia, it) }
            } catch (e: Exception) {
                null
            }
            val foto = produto?.let { IdeiasDePost.fotoDoProduto(it) }
            Result.success(IdeiasDePost.arte(ideia, tipo, foto, variacao) to produto)
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onSuccess { (jpeg, produto) -> conferirIdeia(ideia, tipo, jpeg, produto?.nome, variacao) }
                .onFailure { responder("Não consegui montar a arte: ${it.message}") }
        }
    }.start()
}

internal fun MainActivity.conferirIdeia(ideia: IdeiasDePost.Ideia, tipo: String, jpeg: ByteArray, produto: String?, variacao: Int) {
    val f = Formulario(this, if (tipo == "story") "Programar story" else "Programar post", ideia.titulo)
    f.cartao("Arte")
    val previa = f.previa().apply {
        visibility = android.view.View.VISIBLE
        maxHeight = dp(if (tipo == "story") 420 else 340)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
    }
    f.adicionar(previa, 2)
    f.adicionar(f.aviso(if (produto != null) "Com a foto de: $produto" else "Não achei o produto citado no catálogo: a arte vai só com o título."), 6)
    f.adicionar(f.botao("Não gostei: outra arte") { f.fechar(); prepararIdeia(ideia, tipo, variacao + 1) }, 8)
    val (blocoLegenda, legenda) = f.campo(
        if (tipo == "story") "Texto (o story vai só com a imagem; o texto fica guardado)" else "Legenda", ideia.legenda, "",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 5,
    )
    f.cartao("Texto")
    f.adicionar(blocoLegenda, 2)
    val quando = Calendar.getInstance().apply { timeInMillis = IdeiasDePost.quandoSugerido(ideia, tipo) }
    f.cartao("Quando sai")
    val (blocoData, data) = f.campo("Dia", java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(quando.time), "dd/mm/aaaa", InputType.TYPE_CLASS_DATETIME)
    val (blocoHora, hora) = f.campo("Hora", java.text.SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(quando.time), "19:00", InputType.TYPE_CLASS_DATETIME)
    f.lado(blocoData, blocoHora)
    f.adicionar(f.aviso(if (Instagram.conectado(this)) "Sai sozinho na hora marcada." else "O Instagram está desconectado: na hora, chega o aviso para postar com um toque."), 6)
    f.acoes(f.botao("Programar", primario = true) {
        val m = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(data.text.toString())
        val hm = Regex("(\\d{1,2})[:h](\\d{2})?").find(hora.text.toString())
        if (m == null || hm == null) return@botao Toast.makeText(this, "Confira o dia (dd/mm/aaaa) e a hora (hh:mm).", Toast.LENGTH_SHORT).show()
        val c = Calendar.getInstance().apply {
            clear()
            set(m.groupValues[3].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[1].toInt(), hm.groupValues[1].toInt(), hm.groupValues[2].ifBlank { "0" }.toInt())
        }
        if (c.timeInMillis < System.currentTimeMillis()) return@botao Toast.makeText(this, "Esse horário já passou.", Toast.LENGTH_SHORT).show()
        val p = Postagens.criar(this, c.timeInMillis, tipo, legenda.text.toString(), jpeg)
        focarPost(p.id)
        tirarIdeiaProgramada(ideia)
        Diario.anotar(this, "instagram", "Ideia programada como ${if (tipo == "story") "story" else "post"}: ${ideia.titulo} (${java.text.SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(c.time)})", "tela")
        f.fechar()
        responder("Programado: \"${ideia.titulo}\" como ${if (tipo == "story") "story" else "post no feed"} em ${java.text.SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(c.time)}.")
    }, f.botao("Voltar às ideias") { f.fechar(); mostrarIdeiasDePost() })
    f.mostrar()
}

internal fun MainActivity.focarPost(id: Int) {
    postFocado = id
    postFocadoEm = System.currentTimeMillis()
}

/** O post programado há menos de 20 minutos e ainda não publicado. */
internal fun MainActivity.postEmFoco(): Int? {
    val id = postFocado ?: return null
    if (System.currentTimeMillis() - postFocadoEm > 20 * 60_000L) return null
    return id.takeIf { Postagens.achar(this, it)?.publicado == false }
}

/** "muda para hoje às 19h" / "no feed": ajusta o post em foco. Devolve true se era isso. */
internal fun MainActivity.ajustarPostEmFoco(id: Int, pedido: String): Boolean {
    val c = ComandosLocais.normalizar(pedido).replace(Regex("[^a-z0-9: ]"), " ").replace(Regex("\\s+"), " ").trim()
    ComandosLocais.TIPO_POST.find(c)?.let { m ->
        if (c.split(" ").size <= 10) {
            val tipo = if (m.groupValues[1] == "feed") "feed" else "story"
            val p = Postagens.mudarTipo(this, id, tipo) ?: return false
            focarPost(id)
            responder("Combinado: vai ${if (tipo == "feed") "no feed" else "no story"}, ${Lembretes.quandoFalado(p.quando)}.")
            return true
        }
    }
    val m = ComandosLocais.REPROGRAMAR_POST.matchEntire(c) ?: return false
    val quando = Lembretes.interpretar("lembrete ${m.groupValues[1]}")?.quando ?: return false
    if (quando <= System.currentTimeMillis()) {
        responder("Esse horário já passou. Para publicar já, diga: posta agora.")
        return true
    }
    val p = Postagens.reprogramar(this, id, quando) ?: return false
    focarPost(id)
    responder("Mudei: ${if (p.ehStory) "story" else "post"} ${Lembretes.quandoFalado(p.quando)}.")
    return true
}

// ----------------------------------------------------------- Instagram da loja (API da Meta)
/** Cola o token gerado no painel da Meta; mostra o passo a passo e permite desconectar. */
internal fun MainActivity.conectarInstagram() {
    val usuario = Instagram.usuario(this)
    val campo = EditText(this).apply {
        hint = "Cole aqui o token (começa com IG… ou EAA…)"
        minLines = 2
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }
    val passos = "O Instagram precisa estar ligado a uma página do Facebook.\n" +
        "1. Toque em Abrir a Meta (abre o Explorador da Graph API) e entre com o seu Facebook.\n" +
        "2. Em Aplicativo da Meta, escolha o seu app. Em Usuário ou página, escolha Token do usuário.\n" +
        "3. Em Permissões, adicione: instagram_basic, instagram_content_publish, instagram_manage_comments, pages_show_list, pages_read_engagement e business_management.\n" +
        "4. Toque em Generate Access Token, escolha a página e a conta do Instagram da loja e confirme.\n" +
        "5. Copie o token, abra developers.facebook.com/tools/debug/accesstoken, cole, toque em Depurar e, embaixo, em Estender token de acesso. Copie o token novo e cole aqui."
    val d = AlertDialog.Builder(this)
        .setTitle(if (usuario != null) "Instagram: @$usuario" else "Conectar o Instagram")
        .setMessage((if (usuario != null) "Conectado. Para trocar, cole um token novo.\n\n" else "") + passos)
        .setView(campo)
        .setPositiveButton("Conectar", null)
        .setNeutralButton("Abrir a Meta") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Instagram.COMO_CONECTAR)))
            } catch (e: Exception) {
                responder("Não consegui abrir o navegador.")
            }
        }
        .setNegativeButton(if (usuario != null) "Desconectar" else "Cancelar") { _, _ ->
            if (usuario != null) {
                Instagram.desconectar(this)
                ajustes?.atualizar()
                Diario.anotar(this, "conexao", "Instagram desconectado", "tela")
                responder("Desconectei o Instagram. Os posts programados voltam a chegar como aviso para você postar.")
            }
        }
        .create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val token = campo.text.toString()
            if (token.isBlank()) {
                Toast.makeText(this, "Cole o token primeiro.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Thread {
                val r = try {
                    Result.success(Instagram.conectar(this, token))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                runOnUiThread {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    r.onSuccess { conta ->
                        d.dismiss()
                        ajustes?.atualizar()
                        val loja = if (ferramentas.loja.conectada) "" else " Para os posts saírem sozinhos, conecte também a loja nos ajustes (a foto sobe pelo site)."
                        Diario.anotar(this, "conexao", "Instagram conectado: @${conta.usuario}", "tela")
                        responder("Instagram conectado: @${conta.usuario}. Agora os posts programados saem sozinhos na hora marcada.$loja Pergunte: como está o Instagram.")
                    }.onFailure { e ->
                        // o erro inteiro vai para a conversa (o aviso curto cortava o motivo)
                        Toast.makeText(this, "Não conectou. O motivo está na conversa.", Toast.LENGTH_LONG).show()
                        responder("Não consegui conectar o Instagram: ${e.message ?: "erro desconhecido"}")
                    }
                }
            }.start()
        }
    }
    d.show()
}

/** Comentários sem resposta: escolhe um, a IA sugere a resposta, você ajusta e envia. */
internal fun MainActivity.comentariosInstagram() {
    if (!Instagram.conectado(this)) return responder(Instagram.SEM_CONTA)
    comTempoLimite("LENDO COMENTÁRIOS…") {
        val lista = Instagram.comentariosSemResposta(this)
        val acao: () -> Unit = {
            if (lista.isEmpty()) responder("Nenhum comentário esperando resposta nos últimos posts.")
            else {
                responder(if (lista.size == 1) "Tem 1 comentário sem resposta." else "Tem ${lista.size} comentários sem resposta. Escolha um para eu sugerir a resposta.")
                escolherComentario(lista)
            }
        }
        acao
    }
}

internal fun MainActivity.escolherComentario(lista: List<Instagram.Comentario>) {
    val itens = lista.map { c -> "@${c.usuario}: ${c.texto.take(120)}\n· ${Instagram.resumirLegenda(c.legendaDoPost)}" }
    AlertDialog.Builder(this)
        .setTitle("Comentários sem resposta")
        .setItems(itens.toTypedArray()) { _, i -> sugerirRespostaComentario(lista, i) }
        .setNegativeButton("Fechar", null)
        .show()
}

internal fun MainActivity.sugerirRespostaComentario(lista: List<Instagram.Comentario>, i: Int) {
    val c = lista[i]
    val k = chave()
    val m = modelo()
    val usarIA = k.isNotBlank() && !modoIndependente()
    comTempoLimite(if (usarIA) "ESCREVENDO…" else "ABRINDO…") {
        val sugestao = if (usarIA) {
            val catalogo = try {
                if (ferramentas.loja.conectada) Loja.catalogoParaIA(ferramentas.loja.produtos()) else ""
            } catch (e: Exception) {
                ""
            }
            Gemini.respostaComentario(k, m, c.texto, c.usuario, c.legendaDoPost, catalogo)
        } else null
        val acao: () -> Unit = {
            val campo = EditText(this).apply {
                setText(sugestao ?: "@${c.usuario} ")
                setSelection(text.length)
                minLines = 3
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(dp(24), dp(16), dp(24), dp(16))
            }
            responder(if (sugestao != null) "Sugeri uma resposta para @${c.usuario}. Confira e toque em Responder." else "Escreva a resposta para @${c.usuario}.")
            AlertDialog.Builder(this)
                .setTitle("Responder @${c.usuario}")
                .setMessage("“${c.texto.take(300)}”")
                .setView(campo)
                .setPositiveButton("Responder") { _, _ -> enviarRespostaComentario(lista, i, campo.text.toString()) }
                .setNegativeButton("Voltar") { _, _ -> escolherComentario(lista) }
                .show()
        }
        acao
    }
}

internal fun MainActivity.enviarRespostaComentario(lista: List<Instagram.Comentario>, i: Int, texto: String) {
    if (texto.isBlank()) return
    val c = lista[i]
    comTempoLimite("RESPONDENDO…") {
        Instagram.responderComentario(this, c.id, texto.trim())
        val resto = lista.filterIndexed { j, _ -> j != i }
        val acao: () -> Unit = {
            Diario.anotar(this, "instagram", "Comentário de @${c.usuario} respondido: ${texto.trim().take(120)}", "tela")
            responder("Respondi @${c.usuario}." + if (resto.isNotEmpty()) " Faltam ${resto.size}." else " Não tem mais comentário esperando.")
            if (resto.isNotEmpty()) escolherComentario(resto)
        }
        acao
    }
}
