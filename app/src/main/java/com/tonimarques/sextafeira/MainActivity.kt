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
import java.util.Calendar
import java.util.Locale

open class MainActivity : Activity(), TextToSpeech.OnInitListener {

    internal enum class Escuta { NADA, VIGILIA, COMANDO }

    internal lateinit var orbe: Orbe
    internal lateinit var titulo: TextView
    internal lateinit var status: TextView
    internal lateinit var parcial: TextView
    internal lateinit var mensagens: LinearLayout
    internal lateinit var rolagem: ScrollView
    internal lateinit var campo: EditText
    internal lateinit var botaoMic: ImageButton
    internal lateinit var botaoEnviar: ImageButton
    internal lateinit var botaoAnexar: ImageButton
    internal lateinit var bandejaAnexos: LinearLayout
    internal lateinit var rolagemAnexos: android.widget.HorizontalScrollView

    /** Um anexo escolhido: carrega em segundo plano (foto reduzida, texto do Word/Excel...). */
    internal class Anexo(val uri: Uri, var nome: String) {
        var doc: Arquivos.Documento? = null
        var miniatura: android.graphics.Bitmap? = null
        var erro: String? = null
        var vista: View? = null
    }

    internal val anexos = mutableListOf<Anexo>()
    internal var alturaOrbe = 0
    internal var tecladoAberto = false
    internal var animacaoOrbe: android.animation.ValueAnimator? = null
    internal lateinit var ferramentas: Ferramentas

    internal val cerebro = Cerebro()
    internal val principal = Handler(Looper.getMainLooper())

    internal var gemini: Gemini? = null
    internal var tts: TextToSpeech? = null
    /** Voz neural (Microsoft): o áudio da resposta toca aqui. */
    internal var tocador: android.media.MediaPlayer? = null
    /** Muda a cada fala nova ou interrupção: áudio que chega atrasado de uma fala antiga é descartado. */
    internal var numeroDaFala = 0
    internal var ttsPronto = false
    internal var falando = false
    internal var falaPendente: String? = null
    internal var reconhecedor: SpeechRecognizer? = null
    internal var escuta = Escuta.NADA
    internal var errosSeguidos = 0
    internal var ocupado = false
    internal var pedidoAtual = 0
    internal var ultimaFoiVoz = false
    internal var forcarEscuta = false
    internal var ouvirAposPermissao = false
    internal var naTela = false
    internal var corAtual = Color.parseColor("#36D6FF")
    internal var topicoAtual = "Conversa"
    internal var respostaAtrasada = 0

    /** Esperando o endereço de um lugar ("casa", "trabalho"...) depois de perguntar. */
    internal var aguardandoLugar: String? = null
    @Volatile private var atualizandoConhecimento = false
    internal val memoria by lazy { Memoria(this) }

    internal val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    internal val tarefaVigilia = Runnable { iniciarVigilia() }
    internal var despertador: Despertador? = null
    /** Ouve "Vênus", "chega", "espera"... enquanto ela fala, para dar para interromper. */
    internal var interruptor: Interruptor? = null
    /** Até quando continuar tentando ouvir depois que ela fala (o reconhecedor desiste rápido no silêncio). */
    internal var escutaAte = 0L
    /** O último pedido e a ação feita, para anotar quando você diz "não foi isso". */
    internal var ultimoPedido: String? = null
    internal var ultimaAcaoFeita: String? = null
    internal val apagarTela = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ferramentas = Ferramentas(this, memoria)
        // A busca no Google roda numa thread de fundo, chamada pela ferramenta buscar_na_internet.
        ferramentas.buscador = { pergunta -> gemini?.buscarNaInternet(pergunta) ?: SegundaIA.pesquisar(pergunta) }
        ferramentas.pesquisadorDePrecos = { produto ->
            val k = chave()
            if (k.isBlank() || modoIndependente()) null else Gemini.pesquisarPrecos(k, modelo(), produto)
        }
        cerebro.apelido = memoria.valor("apelido") ?: "chefe"
        VigiaDaLoja.garantir(this)
        cerebro.carregarAprendidas(memoria.aprendidas())
        Lexico.iniciar(this)
        cerebro.aoAprender = { frase, intencao ->
            memoria.aprender(frase, intencao)
            Thread { memoria.guardarVetor("intencao", frase, acao = intencao, fonte = "aprendida") }.start()
        }
        EstudoDiario.agendar(this)
        StoriesDoDia.agendar(this)
        // o Direct automático roda no site: ele precisa do token do Instagram em dia
        // quem instala do zero (sem nenhuma chave) vê os primeiros passos uma vez
        if (!prefs.getBoolean("primeiros_passos_visto", false)) {
            if (chave().isBlank() && !SegundaIA.configurada(this)) window.decorView.postDelayed({ primeirosPassos() }, 900)
            prefs.edit().putBoolean("primeiros_passos_visto", true).apply()
        }
        Thread {
            try {
                RegrasDaLoja.semear(applicationContext, memoria)
                RegrasDaLoja.sincronizarVenda(applicationContext, memoria)
            } catch (e: Exception) {
            }
            if (Instagram.conectado(this)) Instagram.sincronizarComSite(applicationContext)
        }.start()
        try {
            if (Calculadora.conversaVazia()) for (m in memoria.mensagens(null, 120)) Calculadora.lembrar(m.texto)
        } catch (e: Exception) {
        }
        tts = TextToSpeech(this, this)
        setContentView(montarTela())
        val antes = mensagens.childCount
        carregarConversaAnterior()
        // a saudação só aparece numa conversa nova (não embaixo das mensagens de uma conversa em andamento)
        if (mensagens.childCount == antes) mensagemInicial()?.let { adicionarMensagem(it, false, salvar = false) }
        tratarIntent(intent)
        if (javaClass == MainActivity::class.java) principal.postDelayed({ oferecerVozOffline() }, 1500)
        if (javaClass == MainActivity::class.java) principal.postDelayed({ procurarAtualizacao(manual = false) }, 4000)
        if (javaClass == MainActivity::class.java) principal.postDelayed({ resumirConversasAntigas() }, 8000)
        if (javaClass == MainActivity::class.java) principal.postDelayed({ enviarRelatorioDeErros(manual = false) }, 12_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        naTela = true
        conferirLicenca()
        continuarStoryNoWhatsApp()
        try {
            StoriesDoDia.recuperarAtrasado(this)
        } catch (e: Exception) {
        }
        VigiliaService.pausarPorTela(true)
        VigiliaService.aoGestoNaTela = {
            if (!ocupado && escuta != Escuta.COMANDO) {
                ultimaFoiVoz = true
                ouvirComando()
            }
        }
        iniciarEscutaDeFundoSePreciso()
        atualizarConhecimentoSePreciso()
        // o que aconteceu com os posts programados enquanto o app estava fechado
        Postagens.novidades(this).forEach { adicionarMensagem(it, false) }
        Postagens.pendentes(this).filter { it.quando < System.currentTimeMillis() - 5 * 60_000L && it.id !in atrasosAvisados }.forEach { p ->
            atrasosAvisados += p.id
            adicionarMensagem(
                "O ${if (p.ehStory) "story" else "post"} programado para ${Lembretes.quandoFalado(p.quando)} ainda não saiu. " +
                    "Diga posta agora para publicar.", false
            )
        }
        orbe.invalidate()
        if (vigiliaLigada()) {
            if (temMicrofone()) {
                agendarVigilia(700)
            } else if (!prefs.getBoolean(PREF_PEDIU_MIC, false)) {
                prefs.edit().putBoolean(PREF_PEDIU_MIC, true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
            }
        }
        mudarEstadoOcioso()
    }

    override fun onPause() {
        naTela = false
        if (whatsappDepois != null) saiuParaOInstagram = true
        interruptor?.parar()
        escutaAte = 0L
        principal.removeCallbacks(apagarTela)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        VigiliaService.aoGestoNaTela = null
        liberarFoco()
        VigiliaService.pausarPorTela(false)
        principal.removeCallbacks(tarefaVigilia)
        despertador?.parar()
        if (escuta != Escuta.NADA) {
            escuta = Escuta.NADA
            reconhecedor?.cancel()
        }
        super.onPause()
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        despertador?.parar()
        interruptor?.parar()
        tts?.shutdown()
        tocador?.release()
        tocador = null
        reconhecedor?.destroy()
        super.onDestroy()
    }

    internal open fun mensagemInicial(): String? = "Olá! Diga \"${Marca.nome}, ativar\", toque no orbe ou digite abaixo."

    /** Quando a conversa termina e não há mais nada a ouvir. Na tela principal, volta a esperar a ativação. */
    internal open fun aoFicarOcioso() {
        agendarVigilia(500)
    }

    /** Terminou a conversa: devolve o som para o app de música e segue o que cada tela faz quando fica ociosa. */
    internal fun ficarOcioso() {
        liberarFoco()
        aoFicarOcioso()
    }

    // ------------------------------------------------- foco de áudio (música)
    internal var foco: android.media.AudioFocusRequest? = null

    /** Enquanto a Vênus ouve e responde, a música pausa sozinha (e volta depois). */
    internal fun pedirFoco() {
        if (foco != null) return
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        if (!audio.isMusicActive) return
        val pedido = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        if (audio.requestAudioFocus(pedido) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) foco = pedido
    }

    internal fun liberarFoco() {
        val f = foco ?: return
        foco = null
        try {
            getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(f)
        } catch (e: Exception) {
        }
    }

    internal fun estaOcioso() = !ocupado && !falando && escuta == Escuta.NADA

    internal fun tratarIntent(intent: Intent?) {
        val acao = intent?.action ?: return
        when (acao) {
            ACAO_OUVIR, Intent.ACTION_ASSIST -> orbe.post {
                ultimaFoiVoz = true
                ouvirComando()
            }
            ACAO_ATIVAR -> orbe.post { ativar() }
            Atualizador.ACAO_INSTALAR -> resultadoDaInstalacao(intent)
            Postagens.ACAO_POSTAR -> intent.getIntExtra("id", 0).takeIf { it > 0 }?.let { id -> orbe.post { postarAgora(id) } }
            StoriesDoDia.ACAO_INTERATIVO -> {
                val arquivo = intent.getStringExtra("arquivo").orEmpty()
                val pergunta = intent.getStringExtra("pergunta").orEmpty()
                if (arquivo.isNotBlank()) orbe.post { abrirStoryComEnquete(arquivo, pergunta) }
            }
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { u -> orbe.post { anexar(listOf(u)) } }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val lista = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!lista.isNullOrEmpty()) orbe.post { anexar(lista) }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) orbe.post { anexar(listOf(uri)) }
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { t -> orbe.post { textoCompartilhado(t) } }
            }
            ACAO_PEDIDO -> {
                val pedido = intent.getStringExtra(EXTRA_PEDIDO).orEmpty()
                if (pedido.isNotBlank()) orbe.post {
                    ultimaFoiVoz = true
                    processar(pedido)
                }
            }
        }
    }

    // --------------------------------------------------------------------- tela
    internal open fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0A0F24"), Color.parseColor("#04050C"), Color.parseColor("#000000"))
            )
        }

        val cabecalho = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(10), dp(4))
        }
        // Canto esquerdo: histórico de conversas
        val botaoConversas = botaoIcone(R.drawable.ic_conversas, "Conversas", COR_TEXTO_APAGADO) { mostrarHistorico() }.apply {
            clearColorFilter() // o ícone já vem com o degradê da Vênus
            background = vidro(Color.argb(20, 227, 107, 208), Color.argb(70, 227, 107, 208), 22)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        cabecalho.addView(botaoConversas, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        val nomes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        titulo = TextView(this).apply {
            text = Marca.nomeMaiusculo
            textSize = 15f
            letterSpacing = 0.35f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(corAtual)
        }
        val subtitulo = TextView(this).apply {
            text = Marca.subtitulo
            textSize = 9.5f
            letterSpacing = 0.3f
            setTextColor(COR_TEXTO_APAGADO)
        }
        nomes.addView(titulo)
        nomes.addView(subtitulo)
        cabecalho.addView(nomes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Publicações (stories, posts, Instagram e status) ao lado dos ajustes
        val botaoPublicacoes = botaoIcone(R.drawable.ic_publicacoes, "Publicações", COR_TEXTO_APAGADO) { abrirPublicacoes() }.apply {
            clearColorFilter() // o ícone já vem com o degradê da Vênus
            background = vidro(Color.argb(20, 227, 107, 208), Color.argb(70, 227, 107, 208), 22)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        cabecalho.addView(botaoPublicacoes, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        val botaoMenu = botaoIcone(R.drawable.ic_menu_futuro, "Menu", COR_TEXTO_APAGADO) { }
        botaoMenu.setOnClickListener { abrirMenu(it) }
        cabecalho.addView(botaoMenu)
        raiz.addView(cabecalho)

        orbe = Orbe(this).apply {
            setOnClickListener { alternarEscuta() }
            aoMudarCor = { cor -> aplicarCor(cor) }
        }
        alturaOrbe = (resources.displayMetrics.heightPixels * 0.30f).toInt()
        raiz.addView(orbe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alturaOrbe))

        status = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
            setTextColor(COR_TEXTO_APAGADO)
        }
        raiz.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parcial = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(220, 255, 255, 255))
            typeface = Typeface.create("sans-serif-light", Typeface.ITALIC)
            setPadding(dp(24), dp(6), dp(24), 0)
            visibility = View.GONE
        }
        raiz.addView(parcial, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rolagem = ScrollView(this).apply {
            isFillViewport = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(36))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        mensagens = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        rolagem.addView(mensagens)
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // com o teclado aberto, o orbe encolhe para a conversa continuar à vista
        raiz.viewTreeObserver.addOnGlobalLayoutListener {
            val visivel = android.graphics.Rect()
            raiz.getWindowVisibleDisplayFrame(visivel)
            val total = raiz.rootView.height
            val aberto = total > 0 && total - visivel.bottom > total * 0.15f
            if (aberto != tecladoAberto) {
                tecladoAberto = aberto
                ajustarOrbeAoTeclado(aberto)
            }
        }

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(4), dp(2))
            background = vidro(Color.argb(18, 255, 255, 255), Color.argb(40, 255, 255, 255), 28)
        }
        botaoAnexar = botaoIcone(R.drawable.ic_anexar, "Anexar foto ou arquivo", corAtual) { abrirAnexos(botaoAnexar) }
        barra.addView(botaoAnexar)
        campo = EditText(this).apply {
            hint = "Digite ou toque no orbe…"
            setHintTextColor(COR_TEXTO_APAGADO)
            setTextColor(Color.WHITE)
            textSize = 15f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(0, dp(12), 0, dp(12))
            setOnEditorActionListener { _, acao, _ ->
                if (acao == EditorInfo.IME_ACTION_SEND) {
                    enviarTexto()
                    true
                } else {
                    false
                }
            }
        }
        barra.addView(campo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        botaoMic = botaoIcone(R.drawable.ic_microfone, "Falar", corAtual) { alternarEscuta() }
        botaoEnviar = botaoIcone(R.drawable.ic_enviar, "Enviar", corAtual) { enviarTexto() }
        barra.addView(botaoMic)
        barra.addView(botaoEnviar)
        // anexos escolhidos (fotos e arquivos), esperando o pedido, como no chat do Gemini
        bandejaAnexos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(4), dp(2), dp(2))
        }
        rolagemAnexos = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(bandejaAnexos)
        }
        raiz.addView(rolagemAnexos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(4), dp(14), 0)
        })
        val paramsBarra = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(14), dp(6), dp(14), dp(14)) }
        raiz.addView(barra, paramsBarra)

        return raiz
    }

    internal fun aplicarCor(cor: Int) {
        corAtual = cor
        titulo.setTextColor(cor)
        botaoMic.setColorFilter(cor)
        botaoEnviar.setColorFilter(cor)
        if (::botaoAnexar.isInitialized) botaoAnexar.setColorFilter(cor) // o pop-up não tem o "+"
    }

    /** Teclado aberto: orbe pequeno (e sem o aviso embaixo); fechado: volta ao tamanho normal. */
    internal fun ajustarOrbeAoTeclado(aberto: Boolean) {
        val lp = orbe.layoutParams ?: return
        val destino = if (aberto) dp(96) else alturaOrbe
        animacaoOrbe?.cancel()
        animacaoOrbe = android.animation.ValueAnimator.ofInt(lp.height, destino).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                orbe.layoutParams = lp
            }
            start()
        }
        status.visibility = if (aberto) View.GONE else View.VISIBLE
        rolagem.postDelayed({ rolarParaBaixo() }, 260)
    }

    internal fun botaoIcone(icone: Int, descricao: String, cor: Int, acao: () -> Unit) = ImageButton(this).apply {
        setImageResource(icone)
        setColorFilter(cor)
        contentDescription = descricao
        background = null
        setPadding(dp(11), dp(11), dp(11), dp(11))
        setOnClickListener { acao() }
    }

    internal fun vidro(preenchimento: Int, borda: Int, raio: Int) = GradientDrawable().apply {
        setColor(preenchimento)
        setStroke(dp(1).coerceAtLeast(1), borda)
        cornerRadius = dp(raio).toFloat()
    }

    internal fun comAlfa(cor: Int, alfa: Int) = Color.argb(alfa, Color.red(cor), Color.green(cor), Color.blue(cor))

    internal fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    /**
     * Se a conversa atual ainda está em andamento (menos de 3 horas sem falar), mostra as últimas mensagens dela.
     * Senão, a tela abre em branco: a conversa antiga fica guardada no histórico e a próxima começa do zero.
     */
    internal open fun carregarConversaAnterior() {
        val anteriores = try {
            memoria.conversaRecente()?.let { memoria.mensagensDaConversa(it, 12) }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (m in anteriores) {
            if (m.texto.startsWith("Fontes: ")) {
                mostrarFontes(Web.lerFontesSalvas(m.texto), salvar = false)
                continue
            }
            if (m.doUsuario && m.texto.startsWith("📎 ")) {
                mostrarAnexosSalvos(m.texto)
                continue
            }
            adicionarMensagem(m.texto, m.doUsuario, salvar = false).alpha = 0.7f
        }
    }

    /**
     * Executa várias etapas em ordem: as que só consultam ou anotam rodam juntas e a resposta sai numa fala só;
     * a última (que pode precisar de tela ou de confirmação) vai pelo caminho normal.
     */
    internal fun executarPlano(partes: List<String>) {
        val decisoes = partes.map { cerebro.responder(it) }
        val ultima = decisoes.last()
        val antes = decisoes.dropLast(1).filterIsInstance<Cerebro.Decisao.Executar>()
        val final = ultima as? Cerebro.Decisao.Executar
        val juntaNoFim = final != null && final.nome !in Raciocinio.SO_NO_FIM
        comTempoLimite("FAZENDO ${decisoes.size} COISAS…") {
            ferramentas.ultimasFontes = emptyList()
            val textos = antes.map { d ->
                Contexto.registrar(d.nome, d.args)
                ferramentas.executar(d.nome, d.args)
            }.toMutableList()
            if (juntaNoFim) {
                Contexto.registrar(final!!.nome, final.args)
                textos += ferramentas.executar(final.nome, final.args)
            }
            val fontes = ferramentas.ultimasFontes
            val pendente = ferramentas.pendenteQualquer
            val acao: () -> Unit = {
                if (textos.isNotEmpty()) {
                    adicionarMensagem(textos.joinToString("\n\n"), false)
                    mostrarFontes(fontes)
                }
                if (!juntaNoFim) {
                    // a última etapa segue o caminho normal (tela, confirmação, IA…); as anteriores ficam escritas acima
                    mostrarDecisao(ultima, partes.last())
                } else {
                    if (pendente != null) {
                        forcarEscuta = true
                        mostrarConfirmacaoLoja(textos.last())
                    }
                    falar(textos.joinToString(". "))
                }
            }
            acao
        }
    }

    /** Manda o relatório de erros para o GitHub (sozinho a cada 12 horas, ou na hora se o app travou). */
    internal fun enviarRelatorioDeErros(manual: Boolean) {
        if (!manual && !Registro.deveEnviar(this)) return
        if (manual && Registro.pendentes(this).isEmpty()) {
            Toast.makeText(this, "Nenhum erro anotado desde o último relatório. 👏", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val erro = Registro.enviar(this, atualizador.tokenParaRelatorio())
            if (manual) runOnUiThread {
                Toast.makeText(this, erro ?: "Relatório enviado. Na próxima atualização o Claude lê e corrige.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Começa uma conversa nova: tela em branco, a anterior fica arquivada no histórico. */
    internal fun comecarConversaNova() {
        memoria.novaConversa()
        gemini = null
        cerebro.esquecerPendencias()
        Contexto.limpar()
        documentosAbertos = emptyList()
        mensagens.removeAllViews()
        resumirConversasAntigas()
    }

    /** Abre uma conversa antiga na tela e passa a continuar nela. */
    internal fun abrirConversa(id: Long, aviso: Boolean = true) {
        memoria.continuarConversa(id)
        gemini = null
        cerebro.esquecerPendencias()
        Contexto.limpar()
        documentosAbertos = emptyList()
        mensagens.removeAllViews()
        val c = try { memoria.conversa(id) } catch (e: Exception) { null }
        if (aviso && c != null) {
            val data = java.text.SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(java.util.Date(c.ultimo))
            mostrarNota("Conversa de $data: ${c.titulo.ifBlank { "sem título" }}")
        }
        for (m in memoria.mensagensDaConversa(id, 16)) {
            if (m.texto.startsWith("Fontes: ")) {
                mostrarFontes(Web.lerFontesSalvas(m.texto), salvar = false)
                continue
            }
            if (m.doUsuario && m.texto.startsWith("📎 ")) {
                mostrarAnexosSalvos(m.texto)
                continue
            }
            adicionarMensagem(m.texto, m.doUsuario, salvar = false).alpha = 0.7f
        }
        rolarParaBaixo()
    }

    /** "Lembra do que falamos sobre…": acha a conversa, volta para ela e continua dali. */
    internal fun retomarConversa(assunto: String, pedido: String) {
        val atual = memoria.conversaAtual()
        val achadas = try {
            memoria.buscarConversas(assunto, excluir = atual)
        } catch (e: Exception) {
            emptyList()
        }
        val c = achadas.firstOrNull()
        if (c == null) {
            responder("Procurei nas nossas conversas e não achei nada sobre $assunto. Me conta de novo que eu continuo daqui.")
            return
        }
        // a pergunta vai junto para a conversa retomada (e sai da atual, que pode ficar vazia)
        try {
            memoria.moverUltimaMensagem(atual)
        } catch (e: Exception) {
        }
        abrirConversa(c.id)
        topicoAtual = c.topico
        adicionarMensagem(pedido, true)
        if (obterGemini() != null) {
            consultarIA(pedido)
        } else {
            val data = java.text.SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(java.util.Date(c.ultimo))
            val ultima = memoria.mensagensDaConversa(c.id, 40).lastOrNull { !it.doUsuario && !it.texto.startsWith("Fontes: ") }?.texto
            responder(
                "Achei nossa conversa de $data: ${c.titulo}." +
                    (if (c.resumo.isNotBlank()) " ${c.resumo}" else if (ultima != null) " A última coisa que eu disse foi: ${ultima.take(220)}" else "") +
                    " Pode continuar daqui."
            )
        }
        if (achadas.size > 1) mostrarNota("Também falamos disso em outras ${achadas.size - 1} conversas. Veja no histórico.")
    }

    /** Dá título e resumo às conversas encerradas (com a IA), para a busca achar melhor. Roda em segundo plano. */
    internal fun resumirConversasAntigas() {
        val k = chave()
        if (k.isBlank() || modoIndependente()) return
        val m = modelo()
        Thread {
            try {
                for (c in memoria.conversasSemResumo(3)) {
                    val transcricao = memoria.mensagensDaConversa(c.id, 120).filter { !it.texto.startsWith("Fontes: ") }
                        .joinToString("\n") { (if (it.doUsuario) "Usuário: " else "Vênus: ") + it.texto }
                    val (titulo, resumo) = Gemini.resumirConversa(k, m, transcricao) ?: continue
                    memoria.definirResumo(c.id, titulo, resumo)
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    internal open fun adicionarMensagem(texto: String, doUsuario: Boolean, salvar: Boolean = true): TextView {
        // Contexto da conversa: valores e nomes citados ficam disponíveis para as próximas contas.
        if (salvar) Calculadora.lembrar(texto)
        if (salvar) {
            try {
                memoria.salvarMensagem(doUsuario, texto, topicoAtual)
            } catch (e: Exception) {
            }
        }
        val balao = TextView(this).apply {
            text = texto
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextColor(if (doUsuario) Color.WHITE else Color.parseColor("#DCE3F5"))
            typeface = Typeface.create(if (doUsuario) "sans-serif" else "sans-serif-light", Typeface.NORMAL)
            background = if (doUsuario) {
                vidro(comAlfa(corAtual, 40), comAlfa(corAtual, 110), 18)
            } else {
                vidro(Color.argb(14, 255, 255, 255), Color.argb(34, 255, 255, 255), 18)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(true)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (doUsuario) Gravity.END else Gravity.START
            topMargin = dp(8)
            if (doUsuario) leftMargin = dp(56) else rightMargin = dp(40)
        }
        mensagens.addView(balao, params)
        rolarParaBaixo()
        return balao
    }

    /**
     * Vai até a última mensagem sem mexer no foco. (O fullScroll "entrega" o foco à última mensagem, que é
     * selecionável, e aí o teclado abria mas o que você digitava não ia para o campo.)
     */
    internal fun rolarParaBaixo() {
        rolagem.post {
            val fim = (rolagem.getChildAt(0)?.height ?: 0) - rolagem.height + rolagem.paddingBottom
            if (fim > 0) rolagem.smoothScrollTo(0, fim)
        }
    }

    internal fun mudarEstado(estado: Orbe.Estado, texto: String) {
        orbe.estado = estado
        status.text = texto
        // A tela não apaga enquanto ela ouve, pensa ou fala (e por mais 1 minuto depois)
        principal.removeCallbacks(apagarTela)
        if (estado != Orbe.Estado.OCIOSO) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else principal.postDelayed(apagarTela, 60_000)
    }

    internal fun mudarEstadoOcioso() {
        mudarEstado(Orbe.Estado.OCIOSO, if (vigiliaLigada() && temMicrofone()) STATUS_VIGILIA else STATUS_PADRAO)
    }

    // ---------------------------------------------------------------- conversa
    internal fun enviarTexto() {
        val texto = campo.text.toString().trim()
        if (temAnexos()) {
            campo.setText("")
            ultimaFoiVoz = false
            enviarComAnexos(texto)
            return
        }
        if (texto.isEmpty()) return
        campo.setText("")
        ultimaFoiVoz = false
        processar(texto)
    }

    internal fun processar(entrada: String) {
        if (ocupado) return
        // com anexos esperando, o que você falar é o pedido sobre eles
        if (temAnexos() && Cerebro.restoDepoisDaAtivacao(entrada)?.isBlank() != true) {
            enviarComAnexos(Cerebro.restoDepoisDaAtivacao(entrada) ?: entrada)
            return
        }
        pararVigilia()
        respostaAtrasada = 0
        var pedido = entrada

        // "Sexta-feira, ativar" (sozinho ou seguido de um pedido)
        restoDepoisDaAtivacao(pedido)?.let { resto ->
            if (resto.isBlank()) {
                topicoAtual = "Conversa"
                adicionarMensagem(pedido, true)
                ativar()
                return
            }
            pedido = resto
        }

        // "muito interessante Vênus e qual é..." → "qual é..."
        val original = pedido
        pedido = Cerebro.limparFala(pedido)

        // "Não foi isso", "você entendeu errado": anota o erro (vai no relatório) e pede para repetir
        if (Registro.ehCorrecao(pedido) && ultimoPedido != null) {
            topicoAtual = "Conversa"
            adicionarMensagem(original, true)
            Registro.anotar(this, "corrigido", "ação: ${ultimaAcaoFeita ?: "resposta"}", ultimoPedido.orEmpty())
            ultimoPedido = null
            forcarEscuta = true
            responder("Desculpa! Anotei esse erro para melhorar. O que você queria? Pode falar de outro jeito.")
            return
        }
        ultimoPedido = pedido
        ultimaAcaoFeita = null

        // Resposta a "qual é o endereço do seu trabalho?"
        aguardandoLugar?.let { lugar ->
            aguardandoLugar = null
            val endereco = Cerebro.enderecoDaResposta(pedido)
            if (endereco != null) {
                topicoAtual = "Sobre mim"
                adicionarMensagem(original, true)
                tratarMemoria(ComandosMemoria.Pedido.Definir("lugar:$lugar", endereco))
                return
            }
        }
        // Arquivo aberto: "resume", "o que diz esse pdf", "fecha o documento"
        if (documentoAtivo() != null) {
            val chave = ComandosLocais.normalizar(pedido)
            if (Regex("^(?:fecha|feche|fechar|esquece|esqueca|sai|sair) (?:d?o |d?a |desse |deste )?(?:documento|arquivo|pdf|audio|planilha|boleto|nota)\\b").containsMatchIn(chave)) {
                adicionarMensagem(original, true)
                documentosAbertos = emptyList()
                responder("Pronto, fechei o arquivo.")
                return
            }
            if (Regex("\\b(?:arquivo|documento|pdf|boleto|contrato|planilha|audio|nota fiscal|nesse|neste|nele|dele|desse|deste|resume|resumo|resuma)\\b").containsMatchIn(chave)) {
                adicionarMensagem(original, true)
                perguntarAoDocumento(pedido)
                return
            }
        }
        // Resposta a "Mudar o preço de X para Y? Diga sim ou não."
        if (ferramentas.temPendente) {
            val resposta = Loja.simOuNao(pedido)
            if (resposta != null) {
                topicoAtual = if (ferramentas.lojaPendente != null) "Loja" else "Mensagens e ligações"
                adicionarMensagem(original, true)
                responderConfirmacaoLoja(resposta)
                return
            }
            // falou outra coisa: a alteração (ou a resposta) é deixada de lado
            ferramentas.esquecerPendentes()
            fecharConfirmacaoLoja()
        }
        // Post que acabou de ser programado: "muda para hoje às 19h", "no feed"
        postEmFoco()?.let { id ->
            if (ajustarPostEmFoco(id, pedido)) {
                adicionarMensagem(original, true)
                return
            }
        }
        // Memória da conversa: "esse pedido" → o pedido 15; "coloca ele em promoção" → coloca o gel em promoção
        Contexto.comPedido(pedido)?.let { pedido = it }
        Contexto.comProduto(pedido)?.let { trocado ->
            val r = ComandosLocais.interpretar(trocado)
            if (r is ComandosLocais.Resultado.Acao && (r.nome.startsWith("loja_") || r.nome == "programar_post")) pedido = trocado
        }
        // Vários pedidos numa frase só ("quanto vendi hoje e o que está acabando"): faz um de cada vez
        Raciocinio.dividir(pedido)?.let { etapas ->
            topicoAtual = Topicos.classificar(pedido, etapas.first().second)
            adicionarMensagem(original, true)
            executarPlano(etapas.map { it.first })
            return
        }
        val decisao = cerebro.responder(pedido)
        topicoAtual = when (decisao) {
            is Cerebro.Decisao.Memoria -> "Sobre mim"
            is Cerebro.Decisao.Executar -> Topicos.classificar(pedido, decisao.nome)
            else -> Topicos.classificar(pedido)
        }
        adicionarMensagem(original, true)
        cerebro.ultimaCorrecao?.let { mostrarNota("Entendi: “$it”") }
        val naoEntendeuLoja = decisao is Cerebro.Decisao.Responder && decisao.texto.startsWith("Sobre a loja eu entendo")
        if (decisao is Cerebro.Decisao.Incerto || naoEntendeuLoja) {
            rotearOuPerguntar(pedido)
        } else {
            mostrarDecisao(decisao, pedido)
        }
    }

    /**
     * As regras não entenderam: primeiro vê se já aprendeu essa frase; senão o Gemini só interpreta qual ação é
     * (a Vênus executa no celular). Se for conversa ou pergunta geral, segue o caminho de sempre.
     */
    internal fun rotearOuPerguntar(pedido: String) {
        if (documentoAtivo() != null) {
            responderPergunta(pedido)
            return
        }
        // pergunta de raciocínio não é comando: só procura charada/resposta já aprendida, depois vai para quem pensa
        val pensar = Raciocinio.precisaPensar(pedido)
        if (!pensar) Roteador.lembrada(this, pedido)?.let {
            mostrarDecisao(Cerebro.Decisao.Executar(it.acao, it.args), pedido)
            return
        }
        val k = chave()
        if (k.isBlank() || modoIndependente()) {
            responderPergunta(pedido)
            return
        }
        val m = modelo()
        val contexto = Contexto.descrever()
        comTempoLimite("ENTENDENDO…") {
            // 1) memória vetorial no celular: mesmo sentido do que ela já aprendeu? resolve sem o modelo principal
            val sentido = try {
                Semantica.entender(memoria, k, pedido, soRespostas = pensar)
            } catch (e: Exception) {
                null
            }
            if (sentido != null) {
                memoria.contarUso(sentido.id)
                val porSentido: () -> Unit = { aplicarSentido(sentido, pedido) }
                return@comTempoLimite porSentido
            }
            if (pensar) {
                val pergunta: () -> Unit = { responderPergunta(pedido) }
                return@comTempoLimite pergunta
            }
            // 2) o Gemini só interpreta qual ação é
            val rota = Roteador.rotear(k, m, pedido, contexto)
            // frases que dependem do assunto anterior ("e o dele?") não viram regra fixa
            val dependeDoContexto = contexto.isNotBlank() &&
                Regex("\\b(?:ele|ela|dele|dela|nele|nela|mesmo|esse|essa|este|esta|aquele|aquela|isso)\\b|^e ").containsMatchIn(Roteador.chave(pedido))
            if (rota != null && !dependeDoContexto) {
                try {
                    Semantica.aprenderComando(memoria, pedido, rota.acao, rota.args, "roteador")
                } catch (e: Exception) {
                }
            }
            val acao: () -> Unit = if (rota != null) {
                {
                    if (!dependeDoContexto) Roteador.lembrar(this, pedido, rota)
                    topicoAtual = Topicos.classificar(pedido, rota.acao)
                    mostrarDecisao(Cerebro.Decisao.Executar(rota.acao, rota.args), pedido)
                }
            } else {
                { responderPergunta(pedido) }
            }
            acao
        }
    }

    /** Achou na memória vetorial algo com o mesmo sentido: executa, usa a frase aprendida ou responde. */
    internal fun aplicarSentido(s: Semantica.Decisao, pedido: String) {
        val parecido = "${(s.nota * 100).toInt()}% parecido"
        when (s) {
            is Semantica.Decisao.Executar -> {
                topicoAtual = Topicos.classificar(pedido, s.acao)
                mostrarNota("Entendi pelo sentido ($parecido)")
                mostrarDecisao(Cerebro.Decisao.Executar(s.acao, s.args), pedido)
            }
            is Semantica.Decisao.Frase -> {
                mostrarNota("Entendi como “${s.frase}” ($parecido)")
                val d = cerebro.responder(s.frase)
                if (d is Cerebro.Decisao.Incerto) responderPergunta(pedido) else mostrarDecisao(d, pedido)
            }
            is Semantica.Decisao.Responder -> {
                mostrarNota("Da minha memória ($parecido)")
                responder(s.texto)
            }
        }
    }

    internal fun mostrarDecisao(decisao: Cerebro.Decisao, pedido: String, semIA: Boolean = false) {
        when (decisao) {
            is Cerebro.Decisao.Executar -> when {
                decisao.nome.also { Contexto.registrar(it, decisao.args); ultimaAcaoFeita = it } == "pesquisar_na_web" ->
                    buscaInteligente(decisao.args["termo"]?.toString().orEmpty().ifBlank { pedido })
                decisao.nome == "loja_cadastrar" -> abrirCadastro(decisao.args)
                decisao.nome == "editar_fundo" -> editarFundo(null)
                decisao.nome == "ler_nota" -> lerNota(null)
                decisao.nome == "abrir_arquivo" -> escolherArquivo()
                decisao.nome == "programar_post" -> programarPost(decisao.args)
                decisao.nome == "listar_posts" -> listarPosts()
                decisao.nome == "instagram_conectar" -> conectarInstagram()
                decisao.nome == "estudo_resumo" -> {
                    val r = Estudo.ultimo(this)
                    Thread {
                        val contagem = try {
                            memoria.contarVetores()
                        } catch (e: Exception) {
                            emptyMap()
                        }
                        runOnUiThread { responder(Estudo.descrever(r, contagem)) }
                    }.start()
                }
                decisao.nome == "aprendizado" -> mostrarAprendizado()
                decisao.nome == "estudo_agora" -> estudarAgora()
                decisao.nome == "estudo_ideias" -> mostrarIdeiasDePost()
                decisao.nome == "story_auto" -> storiesAutomaticos(decisao.args["ligado"] as? Boolean)
                decisao.nome == "story_auto_ver" -> previaDoStory()
                decisao.nome == "stories_pagina" -> abrirStoriesDoDia()
                decisao.nome == "story_auto_agora" -> storyDoDiaAgora()
                decisao.nome == "story_enquete" -> storyComEnqueteAgora()
                decisao.nome == "diario_resumo" -> {
                    val semana = decisao.args["periodo"] == "semana"
                    val desde = if (semana) System.currentTimeMillis() - 7 * 86_400_000L else Diario.inicioDoDia()
                    Thread {
                        val lista = Diario.de(this).ultimos(200, desde)
                        runOnUiThread {
                            responder(Diario.resumo(lista, if (semana) "nos últimos 7 dias" else "hoje"))
                            mostrarLog()
                        }
                    }.start()
                }
                decisao.nome == "log_erros" -> {
                    val n = Registro.log(this).size
                    responder(if (n == 0) "Nenhum erro em aberto. 👏" else "$n ${if (n == 1) "erro em aberto" else "erros em aberto"}. Mostrei na tela.")
                    mostrarLog()
                }
                decisao.nome == "limpar_erros" -> limparLogDeErros()
                decisao.nome == "postar_agora" -> {
                    val alvo = postEmFoco() ?: Postagens.pendentes(this).firstOrNull()?.id
                    if (alvo == null) responder("Não tem post na fila. Diga, por exemplo: programa um story do gel.") else postarAgora(alvo)
                }
                decisao.nome == "instagram_comentarios" -> comentariosInstagram()
                decisao.nome == "responder_cliente" -> responderCliente(null)
                decisao.nome == "caixa_grafico" -> mostrarGrafico()
                decisao.nome == "enviar_relatorio_erros" -> {
                    val n = Registro.pendentes(this).sumOf { it.vezes }
                    if (n == 0) responder("Não tem nenhum erro anotado desde o último relatório.")
                    else {
                        responder("Mandando o relatório com $n ${if (n == 1) "ocorrência" else "ocorrências"} para o GitHub.")
                        enviarRelatorioDeErros(manual = true)
                    }
                }
                decisao.nome == "buscar_conversa" -> retomarConversa(decisao.args["assunto"]?.toString().orEmpty().ifBlank { pedido }, pedido)
                decisao.nome == "caixa_relatorio" -> gerarRelatorio(decisao.args["periodo"]?.toString() ?: "mes")
                decisao.nome in Ferramentas.USAM_REDE -> executarNaRede(decisao.nome, decisao.args)
                decisao.nome == "controlar_midia" || decisao.nome == "tocar_musica" -> {
                    // A música estava pausada só para ouvir você: devolve o som e só então aperta o botão.
                    val tinhaFoco = foco != null
                    liberarFoco()
                    principal.postDelayed({ responder(ferramentas.executar(decisao.nome, decisao.args)) }, if (tinhaFoco) 450 else 0)
                }
                else -> {
                    val resultado = ferramentas.executar(decisao.nome, decisao.args)
                    val appNaoAchado = decisao.nome == "abrir_app" && resultado.startsWith("Não encontrei")
                    if (appNaoAchado && obterGemini() != null) consultarIA(pedido) else responder(resultado)
                }
            }
            is Cerebro.Decisao.Responder -> {
                val aviso = if (semIA && !modoIndependente() && obterGemini() == null && decisao.texto in BancoDeFrases.NAO_ENTENDI) {
                    " Para perguntas livres, configure a chave do Gemini no menu."
                } else {
                    ""
                }
                if (decisao.texto in BancoDeFrases.NAO_ENTENDI || decisao.texto.startsWith("Sobre a loja eu entendo")) {
                    Registro.anotar(this, "nao_entendi", "sem ação para a frase", pedido)
                }
                responder(decisao.texto + aviso)
            }
            is Cerebro.Decisao.Perguntar -> {
                forcarEscuta = true
                responder(decisao.texto)
            }
            is Cerebro.Decisao.Especial -> {
                when (decisao.tipo) {
                    "nova_conversa" -> comecarConversaNova()
                    "desativar" -> {
                        ultimaFoiVoz = false
                        forcarEscuta = false
                    }
                    "musica_favorita" -> {
                        tocarFavorita()
                        return
                    }
                }
                responder(decisao.texto)
            }
            is Cerebro.Decisao.Memoria -> tratarMemoria(decisao.pedido)
            is Cerebro.Decisao.Incerto -> mostrarDecisao(cerebro.usarPalpite(), pedido, semIA)
        }
    }

    // ----------------------------------------------------------- memória
    internal fun tratarMemoria(pedido: ComandosMemoria.Pedido) {
        when (pedido) {
            is ComandosMemoria.Pedido.Lembrar ->
                responder(if (memoria.lembrar(pedido.texto, pedido.categoria)) "Anotado! Vou lembrar disso." else "Isso eu já sabia!")
            is ComandosMemoria.Pedido.Definir -> {
                memoria.definir(pedido.categoria, pedido.valor)
                val resposta = when {
                    pedido.categoria == "apelido" -> {
                        cerebro.apelido = pedido.valor
                        "Prazer, ${pedido.valor}! Vou te chamar assim."
                    }
                    pedido.categoria == "cidade" -> "Anotado: você mora em ${pedido.valor}. Vou usar isso na previsão do tempo."
                    pedido.categoria.startsWith("lugar:") -> {
                        val lugar = pedido.categoria.removePrefix("lugar:")
                        val falado = if (lugar == "casa") "sua casa" else if (lugar == "trabalho") "seu trabalho" else lugar
                        "Anotado: $falado fica em ${pedido.valor}. Agora é só dizer: me leva para ${if (lugar == "casa") "casa" else lugar}."
                    }
                    else -> {
                        val tipo = pedido.categoria.removePrefix("favorito:")
                        val feminino = tipo in setOf("musica", "banda", "cantora", "comida", "cor", "serie", "bebida", "playlist")
                        val nomeTipo = mapOf("musica" to "música", "serie" to "série")[tipo] ?: tipo
                        if (feminino) "Anotado: sua $nomeTipo favorita é ${pedido.valor}."
                        else "Anotado: seu $nomeTipo favorito é ${pedido.valor}."
                    }
                }
                responder(resposta)
            }
            is ComandosMemoria.Pedido.PerguntarLugar -> {
                aguardandoLugar = pedido.lugar
                forcarEscuta = true
                val falado = when (pedido.lugar) {
                    "casa" -> "da sua casa"
                    "trabalho" -> "do seu trabalho"
                    else -> "de ${pedido.lugar}"
                }
                responder("Qual é o endereço $falado? Pode falar a rua, o número e a cidade, ou o nome do lugar.")
            }
            is ComandosMemoria.Pedido.Esquecer ->
                responder(if (memoria.esquecer(pedido.trecho) > 0) "Pronto, esqueci." else "Não encontrei nada sobre isso na minha memória.")
            ComandosMemoria.Pedido.Listar -> responder(resumoDaMemoria())
            ComandosMemoria.Pedido.ApagarTudo -> confirmarApagarMemorias()
        }
    }

    internal fun resumoDaMemoria(): String {
        val partes = mutableListOf<String>()
        memoria.valor("apelido")?.let { partes += "seu nome é $it" }
        memoria.valor("cidade")?.let { partes += "você mora em $it" }
        for (f in memoria.fatos()) {
            if (f.categoria.startsWith("favorito:")) {
                val tipo = f.categoria.removePrefix("favorito:").let { mapOf("musica" to "música", "serie" to "série")[it] ?: it }
                partes += "seu favorito em $tipo é ${f.texto}"
            }
        }
        for (f in memoria.fatos()) {
            if (f.categoria.startsWith("lugar:")) {
                val lugar = f.categoria.removePrefix("lugar:")
                val valor = if (Regex("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$").matches(f.texto)) "o lugar que você marcou" else f.texto
                partes += "${if (lugar == "casa") "sua casa" else if (lugar == "trabalho") "seu trabalho" else lugar} fica em $valor"
            }
        }
        val fatos = memoria.fatos().filter { it.categoria in setOf("fato", "gosto", "ia") }.takeLast(8).map { it.texto }
        partes += fatos
        if (partes.isEmpty()) return "Ainda não sei nada sobre você. Diga, por exemplo: lembra que eu gosto de rock."
        return "O que eu sei sobre você: " + partes.joinToString("; ") + "."
    }

    internal fun confirmarApagarMemorias() {
        responder("Tem certeza? Confirme na tela.")
        AlertDialog.Builder(this)
            .setTitle("Apagar todas as memórias?")
            .setMessage("A ${Marca.nome} vai esquecer seu nome, sua cidade, seus favoritos, o que aprendeu e o que você mais usa. O histórico de conversas continua.")
            .setPositiveButton("Apagar") { _, _ ->
                memoria.apagarMemorias()
                cerebro.apelido = "chefe"
                cerebro.esquecerAprendidas()
                responder("Pronto, memórias apagadas. Começando do zero.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    internal fun tocarFavorita() {
        val favorita = listOf("musica", "banda", "cantor", "cantora", "artista", "playlist", "estilo")
            .firstNotNullOfOrNull { memoria.valor("favorito:$it") }
            ?: memoria.maisUsados("musica", 1).firstOrNull()
        if (favorita == null) {
            responder("Ainda não sei sua favorita. Me diga: minha música favorita é, e o nome dela.")
            return
        }
        responder(ferramentas.executar("tocar_musica", mapOf("busca" to favorita, "app" to "")))
    }

    // ----------------------------------------------------------- internet
    internal fun comTempoLimite(estado: String, trabalho: () -> (() -> Unit)) {
        ocupado = true
        val id = ++pedidoAtual
        mudarEstado(Orbe.Estado.PENSANDO, estado)
        principal.postDelayed({
            if (pedidoAtual == id && ocupado) {
                pedidoAtual++
                ocupado = false
                responder("Demorou demais para responder. Pode tentar de novo?")
            }
        }, LIMITE_IA_MS)
        Thread {
            val mostrar = try {
                trabalho()
            } catch (e: Exception) {
                if (e !is java.io.IOException && e !is Instagram.ErroDoInstagram) Registro.anotar(this, "erro", "$estado → " + Registro.resumoDoErro(e).replace("\n", " | "))
                val aviso: () -> Unit = {
                    responder(if (e is Instagram.ErroDoInstagram) e.message.orEmpty() else "Não consegui buscar isso agora. Confira sua internet e tente de novo.")
                }
                aviso
            }
            runOnUiThread {
                if (id != pedidoAtual) return@runOnUiThread
                pedidoAtual++
                ocupado = false
                mostrar()
            }
        }.start()
    }

    internal fun executarNaRede(nome: String, args: Map<String, Any>) {
        comTempoLimite(if (nome == "loja_confirmar") "SALVANDO…" else "BUSCANDO…") {
            ferramentas.ultimasFontes = emptyList()
            val resultado = ferramentas.executar(nome, args)
            val fontes = ferramentas.ultimasFontes
            val pendente = if (nome in setOf("loja_alterar", "loja_entregue", "loja_status", "responder_mensagem", "loja_promo_parados")) ferramentas.pendenteQualquer else null
            val acao: () -> Unit = {
                if (pendente != null) forcarEscuta = true
                responder(resultado)
                mostrarFontes(fontes)
                if (pendente != null) mostrarConfirmacaoLoja(resultado)
            }
            acao
        }
    }

    // ----------------------------------------------------------- confirmação de mudanças na loja
    internal var dialogoLoja: AlertDialog? = null

    /** Mostra Confirmar/Cancelar na tela; também dá para responder "sim" ou "não" falando. */
    internal fun mostrarConfirmacaoLoja(pergunta: String) {
        fecharConfirmacaoLoja()
        dialogoLoja = AlertDialog.Builder(this)
            .setTitle(if (ferramentas.lojaPendente != null) "Confirmar mudança na loja" else "Confirmar resposta")
            .setMessage(pergunta.replace(" Diga sim para confirmar ou não para cancelar.", ""))
            .setPositiveButton("Confirmar") { _, _ ->
                dialogoLoja = null
                if (ferramentas.temPendente && !ocupado) {
                    adicionarMensagem("Sim, confirmar", true)
                    responderConfirmacaoLoja(true)
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                dialogoLoja = null
                if (ferramentas.temPendente) {
                    adicionarMensagem("Cancelar", true)
                    responderConfirmacaoLoja(false)
                }
            }
            .setCancelable(false)
            .show()
    }

    internal fun fecharConfirmacaoLoja() {
        dialogoLoja?.dismiss()
        dialogoLoja = null
    }

    internal fun responderConfirmacaoLoja(sim: Boolean) {
        fecharConfirmacaoLoja()
        val eraMensagem = ferramentas.lojaPendente == null && ferramentas.respostaPendente != null
        if (sim) {
            executarNaRede(if (eraMensagem) "confirmar_resposta" else "loja_confirmar", emptyMap())
        } else {
            ferramentas.esquecerPendentes()
            responder(if (eraMensagem) "Tudo bem, não respondi." else "Tudo bem, não mudei nada.")
        }
    }

    /** Pesquisa e responde no chat: Google (pela IA) → pesquisa própria da Vênus → navegador, nessa ordem. */
    internal fun buscaInteligente(termo: String) {
        val assistente = obterGemini()
        val conhecimento = ferramentas.conhecimento
        comTempoLimite("PESQUISANDO…") {
            val r = assistente?.buscarNaInternet(termo)
                ?: SegundaIA.pesquisar(termo)
                ?: Pesquisa.responderPergunta(termo, conhecimento)
                ?: try {
                    Web.wikipedia(termo)
                } catch (e: Exception) {
                    null
                }
            val acao: () -> Unit = if (r != null) {
                {
                    responder(r.texto)
                    mostrarFontes(r.fontes)
                }
            } else {
                {
                    ferramentas.executar("pesquisar_na_web", mapOf("termo" to termo))
                    responder("Não consegui a resposta direto aqui, então abri a pesquisa no navegador.")
                }
            }
            acao
        }
    }

    /**
     * Uma pergunta que os comandos não resolveram. A Vênus tenta sozinha primeiro:
     *  1. uma resposta que ela já deu antes;
     *  2. a pesquisa própria (Wikipédia, notícias acompanhadas, Google Notícias, DuckDuckGo);
     *  3. só então o Gemini (se houver chave e o modo independente estiver desligado).
     */
    internal fun responderPergunta(pedido: String) {
        if (documentoAtivo() != null) {
            perguntarAoDocumento(pedido)
            return
        }
        // resposta guardada só para perguntas simples: num problema, um número diferente muda tudo
        if (Raciocinio.precisaPensar(pedido)) null else try {
            ferramentas.conhecimento?.respostaGuardada(pedido)
        } catch (e: Exception) {
            null
        }?.let {
            responder(it)
            return
        }
        val assistente = obterGemini()
        when {
            assistente == null -> pesquisarSozinha(pedido, amplo = true, depoisIA = false, motivo = null)
            // pergunta de raciocínio vai direto para quem pensa (não para a busca rápida)
            Raciocinio.precisaPensar(pedido) -> consultarIA(pedido)
            Pesquisa.ehPesquisavel(pedido) -> pesquisarSozinha(pedido, amplo = false, depoisIA = true, motivo = null)
            else -> consultarIA(pedido)
        }
    }

    /**
     * Pesquisa sem o Gemini. [amplo] = também tenta Wikipédia/DuckDuckGo com as palavras-chave
     * (para quando não há outra saída). [depoisIA] = se não achar, pergunta ao Gemini.
     */
    internal fun pesquisarSozinha(pedido: String, amplo: Boolean, depoisIA: Boolean, motivo: String?) {
        val conhecimento = ferramentas.conhecimento
        comTempoLimite("PESQUISANDO…") {
            val r = Pesquisa.responderPergunta(pedido, conhecimento, amplo)
            val acao: () -> Unit = when {
                r != null -> {
                    {
                        responder(r.texto)
                        mostrarFontes(r.fontes)
                    }
                }
                depoisIA -> {
                    { consultarIA(pedido) }
                }
                motivo != null -> {
                    { semResposta(motivo, pedido) }
                }
                else -> {
                    { mostrarDecisao(cerebro.usarPalpite(), pedido, semIA = true) }
                }
            }
            acao
        }
    }

    internal fun semResposta(motivo: String, pedido: String) {
        val decisao = cerebro.usarPalpite()
        if (decisao is Cerebro.Decisao.Perguntar) {
            mostrarDecisao(decisao, pedido)
        } else {
            val extra = (decisao as? Cerebro.Decisao.Responder)?.texto?.takeIf { it !in BancoDeFrases.NAO_ENTENDI }
                ?: "Pode perguntar de outro jeito, ou de novo daqui a pouco?"
            responder("$motivo $extra".trim())
        }
    }

    /** Uma linha discreta no chat (ex.: a frase que a Vênus corrigiu). */
    internal fun mostrarNota(texto: String) {
        val vista = TextView(this).apply {
            text = texto
            textSize = 12f
            setTextColor(COR_TEXTO_APAGADO)
            setPadding(dp(6), dp(2), dp(6), 0)
            gravity = Gravity.END
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END }
        mensagens.addView(vista, params)
        rolarParaBaixo()
    }

    /** Depois que o Gemini resolve, a Vênus aprende para resolver sozinha da próxima vez. */
    internal fun aprenderComResposta(pedido: String, resposta: Gemini.RespostaIA) {
        try {
            if (resposta.chamadas.size == 1) {
                val (nome, args) = resposta.chamadas[0]
                val mapa = args.keys().asSequence().associateWith { args.opt(it) }
                cerebro.aprenderComIA(pedido, nome, mapa)
                if (nome in Roteador.NOMES) {
                    @Suppress("UNCHECKED_CAST")
                    val limpo = mapa.filterValues { it != null && it != org.json.JSONObject.NULL } as Map<String, Any>
                    Thread { Semantica.aprenderComando(memoria, pedido, nome, limpo, "ia") }.start()
                }
            } else if (resposta.chamadas.isEmpty()) {
                ferramentas.conhecimento?.lembrarResposta(pedido, resposta.texto)
                // charada ou pergunta atemporal respondida: vira memória vetorial (mesmo dita de outro jeito, ela já sabe)
                val tipo = if (Regex("\\b(?:o que e o que e|adivinha|charada|enigma)\\b").containsMatchIn(ComandosLocais.normalizar(pedido))) "enigma" else "resposta"
                Thread { Semantica.aprenderResposta(memoria, pedido, resposta.texto, tipo) }.start()
            }
        } catch (e: Exception) {
        }
    }

    /** Atualiza as novidades dos assuntos acompanhados, em segundo plano, a cada 6 horas. */
    internal fun atualizarConhecimentoSePreciso() {
        val conhecimento = ferramentas.conhecimento ?: return
        if (atualizandoConhecimento) return
        atualizandoConhecimento = true
        Thread {
            try {
                if (conhecimento.precisaAtualizar()) conhecimento.atualizar()
            } catch (e: Exception) {
            } finally {
                atualizandoConhecimento = false
            }
        }.start()
    }

    internal fun modoIndependente() = prefs.getBoolean(PREF_INDEPENDENTE, false)

    /**
     * Fontes discretas: um selinho "• • • Consultou N fontes" embaixo da resposta.
     * Tocando, abre a lista com todas (cada uma abre no navegador).
     */
    internal fun mostrarFontes(fontes: List<Web.Fonte>, salvar: Boolean = true) {
        if (fontes.isEmpty()) return
        val selo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(12), dp(5))
            background = vidro(Color.argb(16, 255, 255, 255), Color.argb(40, 255, 255, 255), 14)
            isClickable = true
            contentDescription = "Ver as ${fontes.size} fontes"
            setOnClickListener { listarFontes(fontes) }
        }
        // uma bolinha por fonte (até 3), com a cor da Vênus
        repeat(minOf(3, fontes.size)) { i ->
            selo.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(corAtual)
                    alpha = 255 - i * 60
                }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(3) })
        }
        selo.addView(TextView(this).apply {
            text = if (fontes.size == 1) "Consultou 1 fonte" else "Consultou ${fontes.size} fontes"
            textSize = 11.5f
            setTextColor(COR_TEXTO_APAGADO)
            setPadding(dp(4), 0, 0, 0)
        })
        mensagens.addView(selo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
            bottomMargin = dp(6)
            marginStart = dp(4)
        })
        rolarParaBaixo()
        if (salvar) memoria.salvarMensagem(false, "Fontes: " + fontes.joinToString("; ") { "${it.titulo} (${it.url})" }, topicoAtual)
    }

    internal fun listarFontes(fontes: List<Web.Fonte>) {
        val nomes = fontes.map { f ->
            val site = try {
                Uri.parse(f.url).host.orEmpty().removePrefix("www.")
            } catch (e: Exception) {
                ""
            }
            if (site.isNotBlank() && !f.titulo.contains(site, ignoreCase = true)) "${f.titulo}\n$site" else f.titulo
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Fontes consultadas")
            .setItems(nomes) { _, i ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fontes[i].url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Envia para o Gemini, com tempo limite: se ele travar ou falhar, o cérebro local assume. */
    internal fun consultarIA(pedido: String) {
        val assistente = obterGemini()
            ?: return pesquisarSozinha(pedido, amplo = true, depoisIA = false, motivo = null)
        ocupado = true
        val id = ++pedidoAtual
        // Pergunta que pede raciocínio: ela pensa com mais calma (e pode responder mais completo)
        val pensarMais = Raciocinio.precisaPensar(pedido)
        mudarEstado(Orbe.Estado.PENSANDO, if (pensarMais) "PENSANDO COM CALMA…" else "PENSANDO…")
        ferramentas.ultimasFontes = emptyList()

        principal.postDelayed({
            if (pedidoAtual == id && ocupado) {
                pedidoAtual++
                ocupado = false
                respostaAtrasada = id
                Registro.anotar(this, "lento", "a IA passou do tempo limite", pedido)
                responder("A resposta está demorando mais que o normal. Se ela chegar, eu mostro aqui.")
            }
        }, if (pensarMais) LIMITE_IA_PROFUNDO_MS else LIMITE_IA_MS)

        Thread {
            val resposta = try {
                // decisão de negócio: vai junto o retrato da loja em números (sem dados de clientes)
                val comDados = if (pensarMais && Panorama.sobreNegocio(pedido) && ferramentas.loja.conectada) try {
                    val (pedidos, produtos) = ferramentas.dadosDoCaixa()
                    Panorama.anexar(pedido, Panorama.montar(pedidos, produtos, Financas.lancamentos(this), Financas.fixas(this), java.util.Calendar.getInstance()))
                } catch (e: Exception) {
                    pedido
                } else pedido
                assistente.enviar(comDados, pensarMais)
            } catch (e: Exception) {
                Gemini.RespostaIA(false, "Algo deu errado: ${e.message}")
            }
            runOnUiThread {
                if (id != pedidoAtual) {
                    // Chegou depois do tempo limite: mostra, se nada novo foi pedido enquanto isso.
                    if (respostaAtrasada == id && !ocupado && resposta.ok) {
                        respostaAtrasada = 0
                        aprenderComResposta(pedido, resposta)
                        responder(resposta.texto)
                        mostrarFontes(ferramentas.ultimasFontes)
                    }
                    return@runOnUiThread
                }
                pedidoAtual++
                ocupado = false
                if (resposta.ok) {
                    aprenderComResposta(pedido, resposta)
                    responder(resposta.texto)
                    mostrarFontes(ferramentas.ultimasFontes)
                } else {
                    Registro.anotar(this, "ia", resposta.texto, pedido)
                    // Gemini fora do ar: a Vênus pesquisa sozinha antes de desistir.
                    pesquisarSozinha(pedido, amplo = true, depoisIA = false, motivo = "Essa eu ainda não sei sozinha, e o Gemini não respondeu agora.")
                }
            }
        }.start()
    }

    internal fun responder(texto: String) {
        adicionarMensagem(texto, false)
        falar(texto)
    }

    internal fun obterGemini(): Gemini? {
        if (modoIndependente()) return null
        if (!Licenca.liberada(this)) return null // período de testes encerrado: só os comandos locais
        val k = chave()
        // sem a chave do Gemini, a conversa ainda funciona se houver IA reserva (Groq, Cloudflare)
        if (k.isBlank() && !SegundaIA.configurada(this)) return null
        return gemini ?: Gemini(k, modelo(), ferramentas, autoconhecimento = { Consciencia.retrato(this, memoria, ferramentas) }) { memoria.resumoParaIA() }.also { g ->
            // continua a conversa em andamento (mesmo depois de fechar o app), sem misturar com as outras
            try {
                memoria.conversaRecente()?.let { id -> g.carregarHistorico(memoria.mensagensDaConversa(id, 40).map { it.doUsuario to it.texto }) }
            } catch (e: Exception) {
            }
            gemini = g
        }
    }

    internal fun chave() = Cofre.ler(prefs, PREF_CHAVE)
    internal fun modelo() = prefs.getString(PREF_MODELO, MODELO_PADRAO).orEmpty().ifBlank { MODELO_PADRAO }

    // ---------------------------------------------------------------- ativação
    internal fun ativar() {
        Som.ativacao()
        orbe.pulsar()
        ultimaFoiVoz = true
        forcarEscuta = true
        // a saudação fica só escrita: ela não fala, já começa a ouvir (o som de ativação avisa que está pronta)
        adicionarMensagem(cerebro.saudacaoDeAtivacao(), false, salvar = false)
        principal.postDelayed({ if (!falando && !ocupado) aposFala() }, 450)
    }

    internal fun restoDepoisDaAtivacao(texto: String): String? = Cerebro.restoDepoisDaAtivacao(texto)

    // ------------------------------------------------------------ fala (TTS)
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.setLanguage(Locale("pt", "BR"))
        aplicarVozSalva()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { mudarEstado(Orbe.Estado.FALANDO, "FALANDO") }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == ID_AMOSTRA) return
                runOnUiThread { aposFala() }
            }

            @Deprecated("Exigido pela classe base")
            override fun onError(utteranceId: String?) {
                runOnUiThread { aposFala() }
            }
        })
        ttsPronto = true
        falaPendente?.let {
            falaPendente = null
            runOnUiThread { falar(it) }
        }
    }

    internal fun falar(texto: String) {
        pararVigilia()
        if (!ttsPronto && prefs.getBoolean(PREF_FALAR, true) && tts != null) {
            // A voz ainda está carregando (app acabou de abrir): fala assim que ficar pronta.
            falaPendente = texto
            falando = true
            principal.postDelayed({
                if (falaPendente == texto && !ttsPronto) {
                    falaPendente = null
                    aposFala()
                }
            }, 4000)
            return
        }
        if (!ttsPronto || !prefs.getBoolean(PREF_FALAR, true)) {
            principal.post { aposFala() }
            return
        }
        falando = true
        val limpo = texto.replace(Regex("[*#_`>]"), "")
        if (VozNeural.ativa(this)) falarComVozNeural(limpo)
        else tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resposta")
        if (naTela && prefs.getBoolean(PREF_INTERROMPER, true) && temMicrofone()) {
            val i = interruptor ?: Interruptor(this) { interromperFala() }.also { interruptor = it }
            i.iniciar(limpo)
        }
    }

    /** Gera a fala na Microsoft (em segundo plano) e toca; se não vier em poucos segundos, fala com a voz do celular. */
    internal fun falarComVozNeural(limpo: String) {
        val numero = ++numeroDaFala
        Thread {
            val arquivo = try {
                VozNeural.sintetizar(this, limpo)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (numero != numeroDaFala || !falando) return@runOnUiThread // foi interrompida enquanto gerava
                if (arquivo == null) {
                    tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resposta")
                    return@runOnUiThread
                }
                tocarAudio(arquivo, numero)
            }
        }.start()
    }

    internal fun tocarAudio(arquivo: java.io.File, numero: Int, amostra: Boolean = false) {
        tocador?.release()
        tocador = try {
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(arquivo.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release()
                    if (tocador === mp) tocador = null
                    if (!amostra && numero == numeroDaFala) aposFala()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (tocador === mp) tocador = null
                    if (!amostra && numero == numeroDaFala) aposFala()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            if (!amostra && numero == numeroDaFala) aposFala()
            null
        }
        if (!amostra && tocador != null) mudarEstado(Orbe.Estado.FALANDO, "FALANDO")
    }

    /** Cala as duas vozes (a do celular e a neural). */
    internal fun pararFala() {
        numeroDaFala++
        tts?.stop()
        tocador?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        tocador = null
    }

    /** Ajuste da voz neural: chave, região, voz (com amostra) e jeito de falar. */
    internal fun configurarVozNeural() {
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

    /** Você falou "Vênus", "chega", "espera"... durante a fala: ela se cala e passa a ouvir. */
    internal fun interromperFala() {
        if (!falando) return
        pararFala()
        falando = false
        ultimaFoiVoz = true
        escutaAte = android.os.SystemClock.elapsedRealtime() + 12_000
        mudarEstado(Orbe.Estado.OUVINDO, "PODE FALAR…")
        // espera o microfone do interruptor ser solto
        principal.postDelayed({ if (!ocupado && escuta == Escuta.NADA && !falando) ouvirComando() }, 300)
    }

    /** Depois de responder: volta a ouvir (conversa) ou volta a esperar "Sexta-feira, ativar". */
    internal fun aposFala() {
        falando = false
        val estavaOuvindo = interruptor?.ativo == true
        interruptor?.parar()
        mudarEstadoOcioso()
        val continuar = ultimaFoiVoz && naTela && !ocupado &&
            (forcarEscuta || cerebro.aguardandoResposta || prefs.getBoolean(PREF_CONTINUA, true))
        if (continuar) {
            // Fica uns 12 segundos esperando você responder (tentando de novo se o reconhecedor desistir antes)
            escutaAte = android.os.SystemClock.elapsedRealtime() + if (forcarEscuta || cerebro.aguardandoResposta) 15_000 else 12_000
            // um respiro para não ouvir o finzinho da própria voz (e para o microfone do interruptor ser solto)
            principal.postDelayed({ if (!falando && !ocupado && escuta == Escuta.NADA) ouvirComando() }, if (estavaOuvindo) 300 else 150)
        } else ficarOcioso()
    }

    internal fun vozesEmPortugues(): List<Voice> {
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

    internal fun aplicarVozSalva() {
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

    internal fun amostraDeVoz() {
        tts?.speak("Olá! Eu sou a ${Marca.nome}. Gostou desta voz?", TextToSpeech.QUEUE_FLUSH, null, ID_AMOSTRA)
    }

    internal fun escolherVoz() {
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

    internal fun baixarVozes() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Instale o app Serviços de voz do Google pela Play Store.", Toast.LENGTH_LONG).show()
        }
    }

    internal fun ajustarVoz() {
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

    internal fun temMicrofone() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    internal fun obterReconhecedor(): SpeechRecognizer? {
        return reconhecedor ?: (Reconhecedor.criar(this) ?: return null).also {
            it.setRecognitionListener(ouvinte)
            reconhecedor = it
        }
    }

    internal fun intentDeEscuta() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        // dá tempo para pensar no meio da frase sem ela cortar
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)

    internal fun agendarVigilia(atraso: Long) {
        principal.removeCallbacks(tarefaVigilia)
        principal.postDelayed(tarefaVigilia, atraso)
    }

    /** Fica ouvindo em silêncio, esperando "Sexta-feira, ativar". Só com o app aberto na tela. */
    internal fun iniciarVigilia() {
        if (!vigiliaLigada() || !naTela || ocupado || falando || escuta != Escuta.NADA || !temMicrofone()) return
        // Voz offline instalada: microfone aberto direto, sem o ícone piscando na barra de status.
        if (ModeloDeVoz.pronto(this)) {
            val d = despertador ?: Despertador(
                this,
                aoOuvir = {
                    if (escuta == Escuta.VIGILIA) {
                        escuta = Escuta.NADA
                        errosSeguidos = 0
                        ativar()
                    }
                },
                aoFalhar = {
                    if (escuta == Escuta.VIGILIA) {
                        escuta = Escuta.NADA
                        agendarVigilia(4000)
                    }
                },
                aoComando = { acao -> mostrarNota("♪ " + Midia.executar(this, acao)) },
                modoMusicaLigado = { prefs.getBoolean(VigiliaService.PREF_MUSICA, true) },
            ).also { despertador = it }
            if (d.iniciar()) {
                escuta = Escuta.VIGILIA
                mudarEstadoOcioso()
                return
            }
        }
        val r = obterReconhecedor() ?: return
        escuta = Escuta.VIGILIA
        mudarEstadoOcioso()
        try {
            r.startListening(intentDeEscuta())
        } catch (e: Exception) {
            escuta = Escuta.NADA
            agendarVigilia(3000)
        }
    }

    internal fun pararVigilia() {
        principal.removeCallbacks(tarefaVigilia)
        despertador?.parar()
        if (escuta == Escuta.VIGILIA) {
            escuta = Escuta.NADA
            reconhecedor?.cancel()
        }
    }

    internal fun alternarEscuta() {
        when (escuta) {
            Escuta.COMANDO -> {
                escutaAte = 0L
                reconhecedor?.stopListening()
            }
            else -> {
                ultimaFoiVoz = true
                escutaAte = android.os.SystemClock.elapsedRealtime() + 8_000
                ouvirComando()
            }
        }
    }

    internal fun ouvirComando() {
        if (ocupado || escuta == Escuta.COMANDO) return
        pararVigilia()
        interruptor?.parar()
        pararFala()
        falando = false
        forcarEscuta = false
        if (!temMicrofone()) {
            ouvirAposPermissao = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
            return
        }
        val r = obterReconhecedor()
        if (r == null) {
            ouvirPeloGoogle()
            return
        }
        pedirFoco()
        escuta = Escuta.COMANDO
        mostrarParcial("")
        mudarEstado(Orbe.Estado.OUVINDO, "OUVINDO…")
        try {
            r.startListening(intentDeEscuta())
        } catch (e: Exception) {
            escuta = Escuta.NADA
            mudarEstadoOcioso()
            ouvirPeloGoogle()
        }
    }

    internal fun mostrarParcial(texto: String) {
        parcial.text = texto
        parcial.visibility = if (texto.isBlank()) View.GONE else View.VISIBLE
    }

    internal val ouvinte = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (escuta == Escuta.COMANDO) orbe.definirNivelVoz(rmsdB)
        }

        override fun onEndOfSpeech() {
            if (escuta == Escuta.COMANDO) {
                orbe.definirNivelVoz(-2f)
                mudarEstado(Orbe.Estado.PENSANDO, "PROCESSANDO…")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texto = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (texto.isNullOrBlank()) return
            when (escuta) {
                Escuta.COMANDO -> mostrarParcial(texto)
                Escuta.VIGILIA -> {
                    // Responde rápido: "Sexta-feira, ativar" já reconhecido antes de terminar a frase.
                    if (restoDepoisDaAtivacao(texto)?.isBlank() == true) {
                        escuta = Escuta.NADA
                        reconhecedor?.cancel()
                        errosSeguidos = 0
                        ativar()
                    }
                }
                Escuta.NADA -> {}
            }
        }

        override fun onResults(results: Bundle?) {
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val modo = escuta
            escuta = Escuta.NADA
            errosSeguidos = 0
            when (modo) {
                Escuta.VIGILIA -> {
                    val resto = restoDepoisDaAtivacao(texto)
                    when {
                        resto == null -> agendarVigilia(250)
                        resto.isBlank() -> ativar()
                        else -> {
                            ultimaFoiVoz = true
                            processar(resto)
                        }
                    }
                }
                Escuta.COMANDO -> {
                    mostrarParcial("")
                    mudarEstadoOcioso()
                    if (texto.isBlank()) {
                        ultimaFoiVoz = false
                        ficarOcioso()
                    } else {
                        ultimaFoiVoz = true
                        processar(texto)
                    }
                }
                Escuta.NADA -> {}
            }
        }

        override fun onError(error: Int) {
            val modo = escuta
            escuta = Escuta.NADA
            when (modo) {
                Escuta.VIGILIA -> {
                    errosSeguidos++
                    val atraso = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 5000L
                        SpeechRecognizer.ERROR_CLIENT -> {
                            reconhecedor?.destroy()
                            reconhecedor = null
                            900L
                        }
                        else -> 250L
                    }
                    agendarVigilia(if (errosSeguidos > 8) 6000L else atraso)
                }
                Escuta.COMANDO -> {
                    mostrarParcial("")
                    // Ainda dentro da janela de conversa: o reconhecedor desistiu cedo, então tenta de novo
                    val semFala = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (semFala && naTela && !ocupado && android.os.SystemClock.elapsedRealtime() < escutaAte) {
                        mudarEstado(Orbe.Estado.OUVINDO, "OUVINDO…")
                        principal.postDelayed({ if (escuta == Escuta.NADA && !ocupado && !falando) ouvirComando() }, 120)
                        return
                    }
                    escutaAte = 0L
                    ultimaFoiVoz = false
                    val texto = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NÃO OUVI NADA · TOQUE PARA FALAR"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMITA O USO DO MICROFONE"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "SEM INTERNET PARA OUVIR"
                        else -> STATUS_PADRAO
                    }
                    mudarEstado(Orbe.Estado.OCIOSO, texto)
                    ficarOcioso()
                }
                Escuta.NADA -> {}
            }
        }
    }

    /** Plano B: a janela de voz do Google, se o reconhecimento direto não estiver disponível. */
    internal fun ouvirPeloGoogle() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com a ${Marca.nome}")
        try {
            startActivityForResult(intent, PEDIDO_VOZ)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Para usar a voz, instale o app Google.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("API clássica, suficiente para este app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PEDIDO_ARQUIVO || requestCode == PEDIDO_ANEXO_FOTOS) {
            if (resultCode == RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                anexar(uris)
            }
            return
        }
        if (requestCode == PEDIDO_FOTO_CAMERA || requestCode == PEDIDO_FOTO_GALERIA) {
            if (resultCode == RESULT_OK) {
                val uri = if (requestCode == PEDIDO_FOTO_GALERIA) data?.data else fotoDaCamera
                if (uri != null) cadastroFoto?.invoke(uri)
            }
            return
        }
        if (requestCode != PEDIDO_VOZ) return
        val texto = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !texto.isNullOrBlank()) {
            ultimaFoiVoz = true
            processar(texto)
        } else {
            ultimaFoiVoz = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Ferramentas.PEDIDO_LOCALIZACAO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Localização liberada. Pergunte o clima de novo.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != PEDIDO_MICROFONE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (ouvirAposPermissao) ouvirComando() else agendarVigilia(500)
        } else {
            mudarEstado(Orbe.Estado.OCIOSO, "SEM PERMISSÃO DO MICROFONE")
        }
        ouvirAposPermissao = false
    }

    // ---------------------------------------------------------------- ajustes
    internal var ajustes: TelaDeAjustes? = null
    internal var progressoVozOffline: String? = null

    internal fun abrirMenu(@Suppress("UNUSED_PARAMETER") ancora: View) {
        if (ajustes?.isShowing == true) return
        fun item(titulo: String, descricao: () -> String, id: Int, ligado: (() -> Boolean)? = null) =
            TelaDeAjustes.Ajuste(titulo, descricao, ligado) { tratarMenu(id) }

        val n = Marca.nome
        val secoes = listOf(
            TelaDeAjustes.Secao(
                "Ativação por voz", listOf(
                    item("Ouvir \"$n, ativar\" com o app aberto", { "Fala a frase e ela já começa a ouvir o seu pedido." },
                        MENU_VIGILIA) { vigiliaLigada() },
                    item("Ouvir com o app fechado", {
                        "Diga \"$n, ativar\" de qualquer tela e o orbe aparece num pop-up, sem abrir o app inteiro."
                    }, MENU_FUNDO) { prefs.getBoolean(PREF_FUNDO, false) },
                    TelaDeAjustes.Ajuste("Voz offline para a ativação", { descricaoVozOffline() }) { tratarVozOffline() },
                    item("Comandos de música sem chamar", {
                        "Com música tocando, diga só: pausar música, próxima música, música anterior, aumentar volume, diminuir volume. " +
                            "Funciona com qualquer app de música. Precisa da voz offline e da escuta ligada."
                    }, MENU_MUSICA) { prefs.getBoolean(VigiliaService.PREF_MUSICA, true) },
                    item("Torcer o pulso 2 vezes para chamar", {
                        "Gire o celular para um lado e para o outro, duas vezes, com a tela ligada: a $n abre ouvindo, mesmo com música alta. " +
                            "Desligue a \"Câmera rápida\" no app Moto para não abrir os dois."
                    }, MENU_GESTO) { prefs.getBoolean(VigiliaService.PREF_GESTO, false) },
                    item("Funcionar com a tela apagada", {
                        "Ouve \"$n, ativar\", os comandos de música e o gesto mesmo com a tela apagada ou bloqueada. " +
                            "Acende a tela e aparece por cima do bloqueio; para abrir apps ou mandar mensagem, pede a senha antes. Gasta mais bateria."
                    }, MENU_TELA_APAGADA) { prefs.getBoolean(VigiliaService.PREF_TELA_APAGADA, false) },
                    item("Abrir segurando o botão", {
                        "Define a $n como assistente padrão: segure o botão de início ou de ligar para chamar o orbe."
                    }, MENU_ASSISTENTE),
                )
            ),
            TelaDeAjustes.Secao(
                "Voz da $n", listOf(
                    TelaDeAjustes.Ajuste("Voz neural (Microsoft)", { VozNeural.resumo(this) }) { configurarVozNeural() },
                    item("Escolher a voz", { "Vozes em português instaladas no celular (usadas quando a voz neural está desligada ou sem internet)." }, MENU_VOZ),
                    item("Velocidade e tom", { "Deixe a fala mais rápida, mais lenta, mais grave ou mais aguda." }, MENU_AJUSTE_VOZ),
                    item("Responder em voz alta", { "Além de escrever, ela fala as respostas." }, MENU_FALAR) {
                        prefs.getBoolean(PREF_FALAR, true)
                    },
                    item("Conversa contínua", { "Depois de responder, ela volta a ouvir sem você precisar tocar no orbe." },
                        MENU_CONTINUA) { prefs.getBoolean(PREF_CONTINUA, true) },
                    item("Interromper enquanto ela fala", {
                        if (ModeloDeVoz.pronto(this)) "Diga \"$n\", \"chega\", \"espera\" ou \"silêncio\" durante a fala e ela se cala e passa a ouvir. Tocar no orbe também interrompe."
                        else "Precisa da voz offline (ajuste acima). Enquanto isso, toque no orbe para interromper."
                    }, MENU_INTERROMPER) { prefs.getBoolean(PREF_INTERROMPER, true) },
                )
            ),
            TelaDeAjustes.Secao(
                "Loja ${Perfil.loja}", listOf(
                    TelaDeAjustes.Ajuste("Licença", { Licenca.descricao(this) }) { pedirCodigoDeLiberacao(obrigatorio = false) },
                    TelaDeAjustes.Ajuste("Licenças de teste (só o dono)", {
                        if (!Licenca.isenta(this)) "Só no celular do dono da ${Marca.nome}."
                        else if (Licenca.ehAdministrador(this)) "Gerar códigos de liberação para lojistas em teste."
                        else "Criar a chave que assina os códigos (fica só neste celular)."
                    }) { if (Licenca.isenta(this)) administrarLicencas() else Toast.makeText(this, "Só no celular do dono.", Toast.LENGTH_SHORT).show() },
                    TelaDeAjustes.Ajuste("Primeiros passos", { "A lista do que configurar para a $n funcionar com a sua loja." }) { primeirosPassos() },
                    TelaDeAjustes.Ajuste("Perfil do dono e da loja", { Perfil.resumo() + ". A $n usa esses dados nas respostas, nos avisos e no estudo." }) { editarPerfil() },
                    TelaDeAjustes.Ajuste("Site, ramo e regras de venda", {
                        "${Loja.SITE.removePrefix("https://")} · ${Perfil.ramo} · Pix ${Perfil.pixPorcento}%. É o que a $n pode prometer às clientes."
                    }) { editarDadosDaLoja() },
                    TelaDeAjustes.Ajuste("Plataforma da loja", {
                        val t = Plataformas.TIPOS.firstOrNull { it.id == Plataformas.tipo(this) }?.nome ?: "Floot"
                        if (Plataformas.tipo(this) == "floot") "$t. Também funciona com Shopify, WooCommerce e Nuvemshop."
                        else "$t" + (if (ferramentas.loja.conectada) ", conectada." else ", falta a chave.")
                    }) { escolherPlataforma() },
                    TelaDeAjustes.Ajuste("Conectar à loja", {
                        ferramentas.loja.email?.let { "Conectada como $it. Pergunte: como foram as vendas hoje, tem pedido novo, o que está acabando. Toque para desconectar." }
                            ?: "Entre com o login de administrador do site para a $n consultar vendas, pedidos e estoque. A senha fica criptografada só no celular."
                    }) { conectarLoja() },
                    TelaDeAjustes.Ajuste("Avisos de pedidos e estoque", {
                        "A cada 15 minutos, mais ou menos, a $n olha a loja e avisa por notificação quando entra um pedido pago " +
                            "ou um produto fica com 2 unidades ou menos."
                    }, { VigiaDaLoja.ligado(this) }) { alternarAvisosDaLoja() },
                    TelaDeAjustes.Ajuste("Relatório da semana (segunda, 9h)", {
                        "Com os avisos ligados, toda segunda chega o resumo da semana: vendas, comparação, mais vendidos e o que vai acabar. " +
                            "Também avisa quando uma retirada fica 2 dias sem agendar."
                    }, { VigiaDaLoja.semanalLigado(this) }) {
                        VigiaDaLoja.definirSemanal(this, !VigiaDaLoja.semanalLigado(this))
                        ajustes?.atualizar()
                    },
                    TelaDeAjustes.Ajuste("Resumo da loja às 21h", {
                        "Com os avisos ligados, toda noite chega uma notificação com as vendas do dia e o que fica para amanhã."
                    }, { VigiaDaLoja.resumoLigado(this) }) {
                        VigiaDaLoja.definirResumo(this, !VigiaDaLoja.resumoLigado(this))
                        ajustes?.atualizar()
                    },
                )
            ),
            TelaDeAjustes.Secao(
                "Inteligência", listOf(
                    item("Modo independente", {
                        "Responde só com os próprios recursos (comandos, pesquisa e notícias), sem usar o Gemini."
                    }, MENU_INDEPENDENTE) { modoIndependente() },
                    item("Chave do Gemini", { if (chave().isBlank()) "Não configurada" else "Configurada" }, MENU_CHAVE),
                    TelaDeAjustes.Ajuste("IA reserva (Groq ou Grok)", {
                        if (!SegundaIA.configurada(this)) "Não configurada. Com uma chave grátis da Groq, a $n continua respondendo quando o Gemini falhar ou ficar sem cota."
                        else "Usando ${SegundaIA.provedor(this).nome}." + (if (SegundaIA.ultimaVez > 0) " Última vez que ajudou: ${Diario.hora(SegundaIA.ultimaVez)}." else "")
                    }) { pedirIAReserva() },
                    TelaDeAjustes.Ajuste("Segunda reserva: Cloudflare", {
                        if (SegundaIA.chaveCloudflare(this).isBlank()) "Não configurada. Grátis todo dia, sem cartão: entra quando a Groq atingir o limite, antes do Gemini."
                        else "Configurada: entra quando a ${SegundaIA.provedor(this).nome.substringBefore(" (")} atingir o limite, antes do Gemini."
                    }) { pedirCloudflare() },
                    TelaDeAjustes.Ajuste("IA reserva como principal", {
                        if (!SegundaIA.configurada(this)) "Configure a IA reserva primeiro."
                        else if (SegundaIA.principal(this)) "Ligado: a ${SegundaIA.provedor(this).nome.substringBefore(" (")} responde primeiro (mais rápida) e o Gemini entra se ela falhar. PDF, áudio e busca no Google continuam no Gemini."
                        else "Desligado: o Gemini responde primeiro e a ${SegundaIA.provedor(this).nome.substringBefore(" (")} entra se ele falhar."
                    }, { SegundaIA.principal(this) }) {
                        if (!SegundaIA.configurada(this)) pedirIAReserva()
                        else {
                            SegundaIA.definirPrincipal(this, !SegundaIA.principal(this))
                            ajustes?.atualizar()
                        }
                    },
                    TelaDeAjustes.Ajuste("O que eu aprendi", { "Veja por tipo tudo o que a $n guardou na memória e apague o que estiver errado." }) { mostrarAprendizado() },
                    TelaDeAjustes.Ajuste("Estudo diário (memória vetorial)", {
                        val r = Estudo.ultimo(this)
                        "Todo dia, em segundo plano: ${Estudo.FRASES_POR_DIA} jeitos de pedir, ${Estudo.ENIGMAS_POR_DIA} charadas, " +
                            "${Estudo.CLIENTES_POR_DIA} respostas para clientes conferidas, até ${Estudo.REVISAO_MAXIMO} correções das suas conversas, " +
                            "${Estudo.VOZ_POR_DIA} erros de voz e digitação, ${Estudo.OBJECOES_POR_DIA} objeções de venda, " +
                            "até ${Estudo.LICOES_MAXIMO} lições dos próprios erros e ${Estudo.SABERES_POR_DIA} saberes de uma área por dia (hoje: ${Estudo.disciplinaDoDia()}); " +
                            "${Estudo.IDEIAS_POR_SEMANA} ideias de post por semana. Descarta o que já sabe e limpa o que nunca usou." +
                            (r?.let { " Último: ${it.data.split("-").reversed().take(2).joinToString("/")}, +${it.total} itens." } ?: "") +
                            " Pergunte: o que você estudou hoje?"
                    }, { Estudo.ligado(this) }) {
                        Estudo.definirLigado(this, !Estudo.ligado(this))
                        ajustes?.atualizar()
                    },
                    item("Modelo do Gemini", { modelo() }, MENU_MODELO),
                )
            ),
            TelaDeAjustes.Secao(
                "Memória e conversas", listOf(
                    item("O que a $n sabe sobre mim", { "Seus gostos, favoritos e o que ela aprendeu. Dá para apagar." }, MENU_MEMORIAS),
                    item("Nova conversa", { "Abre uma conversa em branco; a atual fica guardada no histórico. Para voltar a um assunto, diga: lembra do que falamos sobre, e o assunto." }, MENU_NOVA),
                )
            ),
            TelaDeAjustes.Secao(
                "Sistema", listOf(
                    TelaDeAjustes.Ajuste("Atualizações", {
                        "Versão ${versaoDoApp()} instalada. A ${Marca.nome} avisa sozinha quando sai uma nova; toque para procurar agora."
                    }) { procurarAtualizacao(manual = true) },
                    item("Diagnóstico de conexão", { "Testa a internet e o Gemini e mostra o que está funcionando." }, MENU_DIAGNOSTICO),
                    TelaDeAjustes.Ajuste("Log de ajustes e erros", {
                        val abertos = Registro.log(this).size
                        "Tudo o que a ${Marca.nome} mudou (loja, Instagram, caixa, conexões) e os erros em aberto. Os erros são enviados ao GitHub " +
                            "para o Claude corrigir e saem do log sozinhos quando a versão com o conserto é instalada. " +
                            (if (abertos == 0) "Nenhum erro em aberto." else "$abertos ${if (abertos == 1) "erro em aberto" else "erros em aberto"}.")
                    }) { mostrarLog() },
                )
            ),
        )
        ajustes = TelaDeAjustes(this, versaoDoApp(), secoes).also {
            it.setOnDismissListener { ajustes = null }
            it.show()
        }
    }

    internal fun versaoDoApp(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    internal fun descricaoVozOffline(): String = progressoVozOffline ?: if (ModeloDeVoz.pronto(this)) {
        "Instalada. O microfone fica aberto direto, só com o pontinho verde, sem o ícone piscando. Toque para remover."
    } else {
        "Toque para baixar (31 MB, uma vez só). Sem ela, o ícone do microfone fica piscando na barra de status enquanto a ${Marca.nome} espera a frase."
    }

    internal fun tratarVozOffline() {
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

    internal fun baixarVozOffline() {
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
    internal fun oferecerVozOffline(forcar: Boolean = false) {
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

    internal fun tratarMenu(id: Int): Boolean {
        when (id) {
            MENU_VIGILIA -> {
                val ligado = !vigiliaLigada()
                prefs.edit().putBoolean(PREF_VIGILIA, ligado).apply()
                if (ligado) {
                    if (temMicrofone()) agendarVigilia(300)
                    else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
                } else {
                    pararVigilia()
                }
                mudarEstadoOcioso()
            }
            MENU_VOZ -> escolherVoz()
            MENU_HISTORICO -> mostrarHistorico()
            MENU_MEMORIAS -> mostrarMemorias()
            MENU_FUNDO -> {
                alternarEscutaDeFundo()
                if (prefs.getBoolean(PREF_FUNDO, false) && podeSobrepor()) oferecerVozOffline(forcar = true)
            }
            MENU_ASSISTENTE -> definirAssistentePadrao()
            MENU_MUSICA -> {
                val ligado = !prefs.getBoolean(VigiliaService.PREF_MUSICA, true)
                prefs.edit().putBoolean(VigiliaService.PREF_MUSICA, ligado).apply()
                if (ligado && !ModeloDeVoz.pronto(this)) oferecerVozOffline(forcar = true)
            }
            MENU_GESTO -> alternarGesto()
            MENU_TELA_APAGADA -> alternarTelaApagada()
            MENU_INDEPENDENTE -> {
                val ligado = !modoIndependente()
                prefs.edit().putBoolean(PREF_INDEPENDENTE, ligado).apply()
                gemini = null
            }
            MENU_AJUSTE_VOZ -> ajustarVoz()
            MENU_CHAVE -> pedirChave()
            MENU_MODELO -> pedirModelo()
            MENU_DIAGNOSTICO -> diagnosticar()
            MENU_FALAR -> {
                val ligado = !prefs.getBoolean(PREF_FALAR, true)
                prefs.edit().putBoolean(PREF_FALAR, ligado).apply()
                if (!ligado) pararFala()
            }
            MENU_CONTINUA -> {
                val ligado = !prefs.getBoolean(PREF_CONTINUA, true)
                prefs.edit().putBoolean(PREF_CONTINUA, ligado).apply()
            }
            MENU_INTERROMPER -> {
                val ligado = !prefs.getBoolean(PREF_INTERROMPER, true)
                prefs.edit().putBoolean(PREF_INTERROMPER, ligado).apply()
                if (!ligado) interruptor?.parar()
            }
            MENU_NOVA -> {
                ajustes?.dismiss()
                comecarConversaNova()
                adicionarMensagem("Conversa nova. Em que posso ajudar?", false, salvar = false)
            }
            else -> return false
        }
        return true
    }

    // --------------------------------------------------- histórico e memórias
    internal fun mostrarHistorico() {
        TelaDeHistorico(this, memoria, corAtual) { id -> abrirConversa(id) }.show()
    }

    internal fun mostrarMemorias() {
        val fatos = memoria.fatos()
        if (fatos.isEmpty()) {
            Toast.makeText(this, "Ainda não sei nada sobre você. Diga: lembra que eu gosto de rock.", Toast.LENGTH_LONG).show()
            return
        }
        val nomes = fatos.map { f ->
            val rotulo = when {
                f.categoria == "apelido" -> "Seu nome"
                f.categoria == "cidade" -> "Sua cidade"
                f.categoria.startsWith("favorito:") -> "Favorito (" + f.categoria.removePrefix("favorito:") + ")"
                f.categoria == "ia" -> "Aprendido na conversa"
                else -> "Você disse"
            }
            "$rotulo: ${f.texto}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("O que eu sei sobre você")
            .setItems(nomes) { _, qual ->
                val f = fatos[qual]
                AlertDialog.Builder(this)
                    .setTitle("Esquecer isto?")
                    .setMessage(f.texto)
                    .setPositiveButton("Esquecer") { _, _ ->
                        memoria.esquecerId(f.id)
                        if (f.categoria == "apelido") cerebro.apelido = "chefe"
                    }
                    .setNegativeButton("Manter", null)
                    .show()
            }
            .setNeutralButton("Apagar tudo") { _, _ -> confirmarApagarMemorias() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ------------------------------------------------ app fechado e pop-up
    internal fun podeSobrepor() = Settings.canDrawOverlays(this)

    internal fun iniciarEscutaDeFundoSePreciso() {
        val escuta = prefs.getBoolean(PREF_FUNDO, false) && temMicrofone()
        val gesto = prefs.getBoolean(VigiliaService.PREF_GESTO, false)
        if ((!escuta && !gesto) || !podeSobrepor()) return
        try {
            VigiliaService.iniciar(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui ligar a escuta em segundo plano.", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun alternarTelaApagada() {
        val ligar = !prefs.getBoolean(VigiliaService.PREF_TELA_APAGADA, false)
        prefs.edit().putBoolean(VigiliaService.PREF_TELA_APAGADA, ligar).apply()
        // O serviço relê os ajustes (liga ou solta a trava do processador)
        try {
            if (prefs.getBoolean(PREF_FUNDO, false) || prefs.getBoolean(VigiliaService.PREF_GESTO, false)) VigiliaService.iniciar(this)
        } catch (e: Exception) {
        }
        if (!ligar) return
        val energia = getSystemService(android.os.PowerManager::class.java)
        val explicacao = "Com a tela apagada, a ${Marca.nome} continua ouvindo \"${Marca.nome}, ativar\", os comandos de música e o gesto. " +
            "Para o Android não desligar ela para economizar bateria, permita na próxima tela que ela funcione sem restrição de bateria."
        if (energia?.isIgnoringBatteryOptimizations(packageName) == true) {
            if (!prefs.getBoolean(PREF_FUNDO, false) && !prefs.getBoolean(VigiliaService.PREF_GESTO, false)) {
                Toast.makeText(this, "Ligue também \"Ouvir com o app fechado\" ou o gesto.", Toast.LENGTH_LONG).show()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Tela apagada")
            .setMessage(explicacao)
            .setPositiveButton("Permitir") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                    }
                }
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    internal fun alternarGesto() {
        val ligar = !prefs.getBoolean(VigiliaService.PREF_GESTO, false)
        prefs.edit().putBoolean(VigiliaService.PREF_GESTO, ligar).apply()
        if (!ligar) {
            // Atualiza o serviço (ele desliga sozinho se nada mais estiver ligado)
            try {
                VigiliaService.iniciar(this)
            } catch (e: Exception) {
            }
            return
        }
        if (!podeSobrepor()) {
            AlertDialog.Builder(this)
                .setTitle("Uma permissão a mais")
                .setMessage("Para a ${Marca.nome} abrir por cima de outros apps quando você torcer o pulso, ative \"Sobrepor a outros apps\" para ela na próxima tela.")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
                .setNegativeButton("Agora não", null)
                .show()
            return
        }
        iniciarEscutaDeFundoSePreciso()
        AlertDialog.Builder(this)
            .setTitle("Gesto ligado")
            .setMessage(
                "Com a tela ligada, gire o celular para um lado e para o outro, duas vezes, firme. A ${Marca.nome} abre já ouvindo, e a música pausa enquanto você fala.\n\n" +
                    "Para não abrir a câmera junto, desligue no app Moto: Gestos → Câmera rápida (torcer o pulso)."
            )
            .setPositiveButton("Abrir o app Moto") { _, _ ->
                val moto = listOf("com.motorola.actions", "com.motorola.moto", "com.motorola.motoapp")
                    .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                try {
                    startActivity(moto ?: Intent(Settings.ACTION_SETTINGS))
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("Entendi", null)
            .show()
    }

    internal fun alternarEscutaDeFundo() {
        val ligar = !prefs.getBoolean(PREF_FUNDO, false)
        prefs.edit().putBoolean(PREF_FUNDO, ligar).apply()
        if (!ligar) {
            // O gesto pode continuar ligado: o serviço se ajusta (ou desliga se nada mais estiver ligado)
            try {
                if (prefs.getBoolean(VigiliaService.PREF_GESTO, false)) VigiliaService.iniciar(this) else VigiliaService.parar(this)
            } catch (e: Exception) {
            }
            Toast.makeText(this, "Escuta com o app fechado desligada.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACOES)
        }
        if (!temMicrofone()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
        }
        if (!podeSobrepor()) {
            AlertDialog.Builder(this)
                .setTitle("Uma permissão a mais")
                .setMessage(
                    "Para o orbe aparecer por cima de outros apps quando você disser \"${Marca.nome}, ativar\", " +
                        "ative \"Sobrepor a outros apps\" (ou \"Exibir sobre outros apps\") para a ${Marca.nome} na próxima tela. " +
                        "Depois é só voltar aqui."
                )
                .setPositiveButton("Abrir configuração") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
                .setNegativeButton("Agora não", null)
                .show()
        } else {
            iniciarEscutaDeFundoSePreciso()
        }
        Toast.makeText(
            this,
            "Com o app fechado e o celular desbloqueado, diga \"${Marca.nome}, ativar\". Fica uma notificação enquanto ela escuta.",
            Toast.LENGTH_LONG
        ).show()
    }

    internal fun definirAssistentePadrao() {
        AlertDialog.Builder(this)
            .setTitle("Assistente padrão")
            .setMessage(
                "Na próxima tela, escolha a ${Marca.nome} como \"App assistente digital\" (ou \"Assistente padrão\"). " +
                    "Depois, segure o botão de início (ou o botão de ligar, dependendo do celular) para abrir o orbe."
            )
            .setPositiveButton("Abrir configuração") { _, _ ->
                val opcoes = listOf(
                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
                for (i in opcoes) {
                    try {
                        startActivity(i)
                        break
                    } catch (e: Exception) {
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    internal fun diagnosticar() {
        if (ocupado) return
        if (chave().isBlank()) {
            pedirChave()
            return
        }
        pararVigilia()
        ocupado = true
        mudarEstado(Orbe.Estado.PENSANDO, "TESTANDO A CONEXÃO…")
        val balao = adicionarMensagem("Testando a conexão, aguarde até 1 minuto…", false, salvar = false)
        val k = chave()
        val m = modelo()
        Thread {
            val relatorio = try {
                Diagnostico.executar(applicationContext, k, m, ferramentas)
            } catch (e: Exception) {
                "O diagnóstico falhou: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                balao.text = relatorio
                rolarParaBaixo()
                ocupado = false
                mudarEstadoOcioso()
                agendarVigilia(500)
            }
        }.start()
    }

    internal fun alternarAvisosDaLoja() {
        val ligar = !VigiaDaLoja.ligado(this)
        if (ligar && !ferramentas.loja.conectada) {
            Toast.makeText(this, "Conecte a loja primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        VigiaDaLoja.definir(this, ligar)
        if (ligar && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACOES)
        }
        if (ligar) {
            // primeira olhada já agora: só registra o que existe, para avisar apenas o que for novo
            Thread {
                try {
                    VigiaDaLoja.verificar(this)
                } catch (e: Exception) {
                }
            }.start()
        }
        Toast.makeText(this, if (ligar) "Avisos da loja ligados." else "Avisos da loja desligados.", Toast.LENGTH_SHORT).show()
        ajustes?.atualizar()
    }

    // ----------------------------------------------------------- cadastro de produto
    internal var fotoDaCamera: Uri? = null
    internal var cadastroFoto: ((Uri) -> Unit)? = null

    // ----------------------------------------------------------- finanças: gráfico e relatório
    internal fun mostrarGrafico() {
        comTempoLimite("MONTANDO O GRÁFICO…") {
            val (pedidos, produtos) = try {
                ferramentas.dadosDoCaixa()
            } catch (e: Loja.ErroDaLoja) {
                return@comTempoLimite { responder(e.message ?: "Não consegui buscar as vendas.") }
            }
            val agora = java.util.Calendar.getInstance()
            val hist = Financas.historico(pedidos, produtos, Financas.lancamentos(this), Financas.fixas(this), 6, agora)
            val imagem = Relatorio.grafico(hist)
            val jpeg = Relatorio.jpeg(imagem)
            val atual = hist.last().second
            val fala = if (atual.receita == 0L && hist.all { it.second.receita == 0L }) {
                "Ainda não tem vendas para mostrar no gráfico, mas já deixei ele pronto: conforme as vendas e despesas entrarem, ele vai se enchendo."
            } else {
                "Aqui está o gráfico dos últimos 6 meses. Neste mês, até agora: vendas de ${Loja.dinheiro(atual.receita.toInt())} e " +
                    (if (atual.lucro >= 0) "lucro de ${Loja.dinheiro(atual.lucro.toInt())}." else "prejuízo de ${Loja.dinheiro((-atual.lucro).toInt())}.")
            }
            val acao: () -> Unit = {
                responder(fala)
                mostrarImagem("Gráfico financeiro", imagem, jpeg)
            }
            acao
        }
    }

    internal fun gerarRelatorio(periodo: String) {
        comTempoLimite("GERANDO O RELATÓRIO…") {
            val (pedidos, produtos) = try {
                ferramentas.dadosDoCaixa()
            } catch (e: Loja.ErroDaLoja) {
                return@comTempoLimite { responder(e.message ?: "Não consegui buscar as vendas.") }
            }
            val agora = java.util.Calendar.getInstance()
            val p = Financas.periodo(periodo, agora)
            val lancamentos = Financas.lancamentos(this)
            val fixas = Financas.fixas(this)
            val res = Financas.resultado(pedidos, produtos, lancamentos, fixas, p)
            val hist = Financas.historico(pedidos, produtos, lancamentos, fixas, 6, agora)
            val nomeDoMes = java.text.SimpleDateFormat("MMMM 'de' yyyy", Locale("pt", "BR"))
            val titulo = when (periodo) {
                "mes_passado" -> nomeDoMes.format(java.util.Date(p.ini))
                "semana" -> "últimos 7 dias"
                else -> nomeDoMes.format(agora.time) + " (até hoje)"
            }
            val previsao = if (periodo == "mes") Financas.textoPrevisao(hist, fixas.sumOf { it.valorCentavos.toLong() }, agora) else ""
            val bytes = Relatorio.pdf(
                Relatorio.Dados(p, titulo, res, hist, lancamentos, fixas, Financas.maisVendidos(pedidos, p), previsao)
            )
            val (arquivo, uri) = FotoProvider.novoArquivo(this, "relatorio_${System.currentTimeMillis()}.pdf")
            arquivo.writeBytes(bytes)
            val fala = "O relatório de $titulo está pronto. " +
                (if (res.lucro >= 0) "Lucro de ${Loja.dinheiro(res.lucro.toInt())}" else "Prejuízo de ${Loja.dinheiro((-res.lucro).toInt())}") +
                " sobre ${Loja.dinheiro(res.receita.toInt())} em vendas. Você pode abrir, imprimir ou mandar para a Natália."
            val acao: () -> Unit = {
                responder(fala)
                oferecerPdf(uri)
            }
            acao
        }
    }

    internal fun oferecerPdf(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Relatório financeiro (PDF)")
            .setMessage("O que você quer fazer com o relatório?")
            .setPositiveButton("Compartilhar") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = android.content.ClipData.newRawUri("relatorio", uri)
                startActivity(Intent.createChooser(i, "Enviar relatório"))
            }
            .setNeutralButton("Abrir") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Nenhum app de PDF encontrado. Use Compartilhar e abra no Drive.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ----------------------------------------------------------- responder clientes
    /** Texto recebido pelo "Compartilhar" (ex.: mensagem de uma cliente no WhatsApp). */
    internal fun textoCompartilhado(texto: String) {
        AlertDialog.Builder(this)
            .setTitle("O que fazer com o texto?")
            .setMessage(texto.take(300))
            .setPositiveButton("Responder a cliente") { _, _ -> responderCliente(texto) }
            .setNeutralButton("Perguntar à ${Marca.nome}") { _, _ ->
                ultimaFoiVoz = false
                processar(texto)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Escreve a resposta para a cliente com os preços, o estoque e os links da loja; você confere, copia e cola no WhatsApp. */
    internal fun responderCliente(mensagem: String?) {
        if (chave().isBlank() || modoIndependente()) {
            responder("Para escrever respostas para clientes eu uso o Gemini. Configure a chave do Gemini nos ajustes.")
            return
        }
        if (mensagem.isNullOrBlank()) {
            val campo = EditText(this).apply {
                hint = "Cole aqui a mensagem da cliente"
                minLines = 3
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(dp(24), dp(16), dp(24), dp(16))
            }
            AlertDialog.Builder(this)
                .setTitle("Responder cliente")
                .setMessage("Dica: no WhatsApp, segure a mensagem da cliente, toque em Compartilhar e escolha a ${Marca.nome}.")
                .setView(campo)
                .setPositiveButton("Escrever resposta") { _, _ -> campo.text.toString().takeIf { it.isNotBlank() }?.let { responderCliente(it) } }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val k = chave()
        val m = modelo()
        comTempoLimite("ESCREVENDO…") {
            val catalogo = try {
                if (ferramentas.loja.conectada) Loja.catalogoParaIA(ferramentas.loja.produtos()) else ""
            } catch (e: Exception) {
                ""
            }
            // o que ela já aprendeu e conferiu no estudo (respostas para clientes e objeções)
            val conferidas = try {
                Semantica.vetorDe(mensagem, k)?.let { v ->
                    memoria.buscarVetores(v, EmbeddingsGemini.MODELO, setOf("cliente", "objecao", RegrasDaLoja.TIPO), minimo = 0.80, quantos = 4)
                }.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            val pronta = conferidas.firstOrNull()?.takeIf { it.nota >= 0.94 && !it.item.resposta.isNullOrBlank() }
            pronta?.let { memoria.contarUso(it.item.id) }
            val referencias = conferidas.filter { !it.item.resposta.isNullOrBlank() }.joinToString("\n") { "- \"${it.item.texto}\" → ${it.item.resposta}" }
            val r = pronta?.item?.resposta ?: Gemini.respostaCliente(
                k, m, mensagem,
                catalogo.ifBlank { "(catálogo indisponível agora)" } +
                    (if (referencias.isNotBlank()) "\n\nRespostas já conferidas da loja para perguntas parecidas (use o mesmo tom e as mesmas informações):\n$referencias" else ""),
            )
            val acao: () -> Unit = {
                if (r == null) responder("O Gemini não respondeu agora. Tente de novo em instantes.")
                else {
                    val campo = EditText(this).apply {
                        setText(r)
                        minLines = 4
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        setPadding(dp(24), dp(16), dp(24), dp(16))
                    }
                    responder("Escrevi uma resposta para a cliente. Confira na tela, copie e cole no WhatsApp.")
                    AlertDialog.Builder(this)
                        .setTitle("Resposta para a cliente")
                        .setView(campo)
                        .setPositiveButton("Copiar") { _, _ ->
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Resposta", campo.text.toString()))
                            Toast.makeText(this, "Copiada. É só colar no WhatsApp.", Toast.LENGTH_LONG).show()
                        }
                        .setNeutralButton("Abrir no WhatsApp") { _, _ ->
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, campo.text.toString())
                            val zap = listOf("com.whatsapp.w4b", "com.whatsapp").map { Intent(i).setPackage(it) }.firstOrNull { it.resolveActivity(packageManager) != null }
                            try {
                                startActivity(zap ?: Intent.createChooser(i, "Enviar resposta"))
                            } catch (e: Exception) {
                                Toast.makeText(this, "Não achei o WhatsApp.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
            }
            acao
        }
    }

    // ----------------------------------------------------------- log de ajustes e erros
    /** Tela com os ajustes feitos (diário) e os erros em aberto, com Enviar relatório e Limpar erros. */
    internal fun mostrarLog() {
        Thread {
            val ajustes = Diario.de(this).ultimos(60)
            val erros = Registro.log(this)
            runOnUiThread {
                val f = Formulario(this, "Log da ${Marca.nome}", "Ajustes feitos e erros em aberto")
                fun linha(titulo: String, texto: String, ruim: Boolean = false) = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(TextView(this@MainActivity).apply {
                        text = titulo
                        textSize = 11.5f
                        letterSpacing = 0.05f
                        setTextColor(if (ruim) Color.parseColor("#FF8A80") else Formulario.COR_APAGADA)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = texto
                        textSize = 14.5f
                        setTextColor(Color.WHITE)
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                f.cartao("Erros em aberto (${erros.size})")
                if (erros.isEmpty()) f.adicionar(f.aviso("Nenhum erro em aberto. Quando a ${Marca.nome} anotar um erro, ele aparece aqui até ser consertado."), 2)
                for (o in erros.take(40)) {
                    val quando = Diario.hora(o.ultima)
                    val estado = if (o.status == "enviado") "enviado ao GitHub" else "aguardando envio"
                    val nome = Registro.NOMES[o.tipo] ?: o.tipo
                    val texto = listOf(o.frase.takeIf { it.isNotBlank() }?.let { "“$it”" }, o.detalhe.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" → ")
                    f.adicionar(linha("${o.vezes}x · $nome · v${o.versao} · $quando · $estado", texto, ruim = o.tipo == "falha"), 2)
                }
                f.cartao("Últimos ajustes")
                if (ajustes.isEmpty()) f.adicionar(f.aviso("Ainda não há ajustes registrados."), 2)
                for (a in ajustes) {
                    f.adicionar(linha("${Diario.hora(a.quando)} · ${Diario.NOMES_AREA[a.area] ?: a.area}" + if (!a.ok) " · falhou" else "", a.texto, ruim = !a.ok), 2)
                }
                f.acoes(
                    f.botao("Enviar relatório", primario = true) { enviarRelatorioDeErros(manual = true) },
                    f.botao("Limpar erros") {
                        f.fechar()
                        limparLogDeErros()
                    },
                )
                f.mostrar()
            }
        }.start()
    }

    internal fun limparLogDeErros() {
        val n = Registro.log(this).size
        if (n == 0) return responder("O log de erros já está limpo.")
        AlertDialog.Builder(this)
            .setTitle("Limpar o log de erros?")
            .setMessage("Os $n erros em aberto saem do log. Use quando já foram consertados; os que ainda não foram enviados ao GitHub não serão mais enviados.")
            .setPositiveButton("Limpar") { _, _ ->
                Registro.limparTudo(this)
                Diario.anotar(this, "erro", "Log de erros limpo à mão ($n erros)", "tela")
                responder("Pronto, limpei o log de erros.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ----------------------------------------------------------- estudo diário
    internal var estudando = false

    internal fun estudarAgora() {
        if (chave().isBlank()) return responder("Para estudar eu uso o Gemini. Configure a chave do Gemini nos ajustes.")
        if (estudando || Estudo.ocupado()) return responder("Já estou estudando. Leva uns minutos; eu aviso quando terminar.")
        estudando = true
        responder("Vou estudar agora: jeitos de pedir, charadas, respostas para clientes, correções das nossas conversas, erros de voz e objeções de venda. Leva uns 5 minutos e eu aviso aqui.")
        Thread {
            val r = try {
                Estudo.estudar(this, forcar = true)
            } catch (e: Exception) {
                null
            }
            val contagem = try {
                memoria.contarVetores()
            } catch (e: Exception) {
                emptyMap()
            }
            runOnUiThread {
                estudando = false
                adicionarMensagem(Estudo.descrever(r, contagem), false)
            }
        }.start()
    }

    /** Story em sequência: depois de postar no Instagram e voltar, a Vênus abre o WhatsApp com a mesma imagem para o status. */
    internal var whatsappDepois: java.io.File? = null
    internal var saiuParaOInstagram = false

    // ----------------------------------------------------------- período de testes (lojistas convidados)
    internal var licencaAberta = false

    // ----------------------------------------------------------- "O que eu aprendi": ver e apagar aprendizados
    internal val NOMES_DO_APRENDIZADO = linkedMapOf(
        "comando" to "Jeitos de pedir", "intencao" to "Frases que entendi", "resposta" to "Respostas guardadas",
        "cliente" to "Respostas para clientes", "objecao" to "Objeções de venda", RegrasDaLoja.TIPO to "Regras da loja",
        "enigma" to "Charadas", "ideia_post" to "Ideias de post", "fato" to "Fatos sobre você", "conversa" to "Resumos de conversas",
        Consciencia.TIPO_LICAO to "Lições dos meus erros", Estudo.TIPO_SABER to "Saberes de outras áreas",
    )

    // ----------------------------------------------------------- post em foco (o último programado)
    internal val atrasosAvisados = mutableSetOf<Int>()
    internal var postFocado: Int? = null
    internal var postFocadoEm = 0L

    // ----------------------------------------------------------- arquivos (PDF, Word, Excel, áudio...)
    internal var documentosAbertos: List<Arquivos.Documento> = emptyList()
    internal var documentoQuando = 0L
    internal val conversaDocumento = mutableListOf<Pair<String, String>>()

    internal fun abrirLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Resposta com endereços clicáveis (fala sem ler os links). */
    internal fun responderComLinks(texto: String) {
        val balao = adicionarMensagem(texto, false)
        android.text.util.Linkify.addLinks(balao, android.text.util.Linkify.WEB_URLS)
        balao.setLinkTextColor(corAtual)
        balao.movementMethod = LinkMovementMethod.getInstance()
        falar(texto.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+—\\s+(?=\\n|$)"), ""))
    }

    /** Botõezinhos de sugestão no chat (no lugar das caixas de pergunta). */
    internal fun mostrarSugestoes(opcoes: List<Pair<String, () -> Unit>>) {
        if (opcoes.isEmpty()) return
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), 0, dp(6))
        }
        for ((rotulo, acao) in opcoes) {
            linha.addView(TextView(this).apply {
                text = rotulo
                textSize = 12.5f
                setTextColor(corAtual)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = vidro(Color.argb(14, 255, 255, 255), Color.argb(90, Color.red(corAtual), Color.green(corAtual), Color.blue(corAtual)), 16)
                isClickable = true
                setOnClickListener {
                    linha.alpha = 0.4f
                    linha.isEnabled = false
                    acao()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        mensagens.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(linha)
        })
        rolarParaBaixo()
    }

    internal val PEDE_NOTA = Regex("\\b(?:nota (?:de compra|fiscal)|lanca|lancar|lance|compra do fornecedor|soma(?:r)? (?:ao|no) estoque|entrada de estoque)\\b")
    internal val PEDE_PRECO = Regex(
        "\\b(?:pesquis\\w*|procur\\w*|encontr\\w*|ach(?:a|e|ar)|quanto (?:custa|sai|vale|ta|esta)|preco|precos|valor(?:es)?|onde (?:compr\\w*|vende\\w*|acho)|compar\\w*|mais barato|lens|link)\\b"
    )
    internal val PEDE_FUNDO = Regex("\\b(?:fundo|tira(?:r)? o fundo|troca(?:r)? o fundo|recort(?:a|ar|e))\\b")

    // ----------------------------------------------------------- atualização automática
    internal val atualizador by lazy { Atualizador(this) }

    /** Procura versão nova. Automática (ao abrir, no máximo a cada 6 h e sem incomodar) ou pelo menu. */
    internal fun procurarAtualizacao(manual: Boolean) {
        if (!manual && (atualizador.procurouRecentemente() || !atualizador.temToken)) return
        if (manual) Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            val resultado = try {
                Result.success(atualizador.procurar())
            } catch (e: Exception) {
                Result.failure(e)
            }
            // a Vênus primeiro; o robô só quando ela já está em dia (ou se a busca dela falhou)
            val robo = if (resultado.getOrNull() == null) atualizador.procurarRobo() else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                resultado.onSuccess { v ->
                    when {
                        v != null -> oferecerAtualizacao(v)
                        robo != null -> oferecerAtualizacao(robo)
                        manual -> Toast.makeText(this, "Você já está com a versão mais nova (${versaoDoApp()}).", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { e ->
                    if (robo != null) {
                        oferecerAtualizacao(robo)
                        return@onFailure
                    }
                    if (!manual) return@onFailure
                    if (e is Atualizador.ErroDeAtualizacao && e.precisaToken) pedirTokenDoGithub(e.message)
                    else Toast.makeText(this, e.message ?: "Não consegui procurar agora. Confira a internet.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    internal fun oferecerAtualizacao(v: Atualizador.Versao) {
        val novidades = v.notas.ifBlank { "Melhorias e correções." }
        if (v.ehRobo) {
            AlertDialog.Builder(this)
                .setTitle("Nova versão do Vênus Robô: 1.${v.numero}")
                .setMessage("$novidades\n\nO robô continua com a chave, os canais e o acesso às notificações. Toque em Atualizar na tela do Android.")
                .setPositiveButton("Atualizar o robô") { _, _ -> instalarAtualizacao(v) }
                .setNegativeButton("Depois", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Nova versão da ${Marca.nome}: 1.${v.numero}")
            .setMessage("$novidades\n\nA atualização mantém suas memórias, ajustes e a conexão com a loja.")
            .setPositiveButton("Atualizar agora") { _, _ -> instalarAtualizacao(v) }
            .setNegativeButton("Depois", null)
            .show()
    }

    internal fun instalarAtualizacao(v: Atualizador.Versao) {
        val aviso = TextView(this).apply {
            text = "Baixando… 0%"
            setPadding(dp(24), dp(16), dp(24), dp(8))
            textSize = 16f
        }
        val dialogo = AlertDialog.Builder(this).setTitle(if (v.ehRobo) "Atualizando o Vênus Robô" else "Atualizando a ${Marca.nome}")
            .setView(aviso).setCancelable(false).show()
        // depois que a Vênus reabrir atualizada, ela procura de novo e já oferece o robô
        if (!v.ehRobo) atualizador.esquecerBusca()
        Thread {
            try {
                atualizador.baixarEInstalar(v) { pct -> runOnUiThread { aviso.text = "Baixando… $pct%" } }
                runOnUiThread {
                    aviso.text = "Pronto. Confirme a instalação na próxima tela."
                    principal.postDelayed({ if (dialogo.isShowing) dialogo.dismiss() }, 2500)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialogo.dismiss()
                    Toast.makeText(this, e.message ?: "Não consegui baixar a atualização.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** O Android avisa como foi a instalação; quando precisa da confirmação, abre a tela dele. */
    internal fun resultadoDaInstalacao(intent: Intent) {
        when (intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmar = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmar != null) {
                    try {
                        startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Não consegui abrir a instalação.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                val robo = intent.getBooleanExtra(Atualizador.EXTRA_ROBO, false)
                val numero = intent.getIntExtra(Atualizador.EXTRA_VERSAO, 0)
                if (robo) Diario.anotar(this, "app", "Vênus Robô atualizado para a versão 1.$numero", "atualizacao")
                Toast.makeText(this, if (robo) "Vênus Robô atualizado!" else "${Marca.nome} atualizada!", Toast.LENGTH_SHORT).show()
            }
            -999 -> {}
            else -> {
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(this, "A instalação não foi concluída. $msg".trim(), Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun pedirTokenDoGithub(motivo: String?) {
        val campo = campoDeDialogo("", "Cole o token aqui (github_pat_…)")
        AlertDialog.Builder(this)
            .setTitle("Token do GitHub")
            .setMessage(
                (motivo?.let { "$it\n\n" } ?: "") +
                    "Como as versões ficam num repositório privado, a ${Marca.nome} precisa de uma chave só de leitura:\n\n" +
                    "1. No GitHub: Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.\n" +
                    "2. Repository access: Only select repositories → Sexta-feira-.\n" +
                    "3. Permissions → Repository → Contents: Read-only.\n" +
                    "4. Gere, copie e cole aqui.\n\n" +
                    "Ela fica criptografada só no celular e não deixa mudar nada no GitHub."
            )
            .setView(campo)
            .setPositiveButton("Salvar e procurar") { _, _ ->
                val t = campo.text.toString().trim()
                if (t.length < 20) {
                    Toast.makeText(this, "Esse token parece incompleto.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                atualizador.guardarToken(t)
                procurarAtualizacao(manual = true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Campo de chave: aparece em pontinhos (para colar a nova, apague e cole por cima). */
    internal fun campoSecreto(valor: String, dica: String) = campoDeDialogo(valor, dica).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    internal fun campoDeDialogo(valor: String, dica: String) = EditText(this).apply {
        setText(valor)
        hint = dica
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }

    companion object {
        const val ACAO_OUVIR = "com.tonimarques.sextafeira.OUVIR"
        internal const val PEDIDO_VOZ = 10
        internal const val PEDIDO_MICROFONE = 11
        internal const val PEDIDO_NOTIFICACOES = 12
        internal const val PEDIDO_FOTO_CAMERA = 40
        internal const val PEDIDO_FOTO_GALERIA = 41
        internal const val PEDIDO_ARQUIVO = 42
        internal const val PEDIDO_ANEXO_FOTOS = 43
        internal const val MAXIMO_ANEXOS = 10
        internal const val PREF_AVISO_ARQUIVOS = "aviso_arquivos"
        internal const val LIMITE_IA_MS = 35_000L
        internal const val LIMITE_IA_PROFUNDO_MS = 90_000L
        internal const val ID_AMOSTRA = "amostra"
        internal const val MODELO_PADRAO = "gemini-flash-latest"
        internal const val STATUS_PADRAO = "TOQUE NO ORBE PARA FALAR"
        internal val STATUS_VIGILIA = "DIGA “${Marca.nomeMaiusculo}, ATIVAR”"

        internal const val PREF_CHAVE = "chave"
        internal const val PREF_MODELO = "modelo"
        internal const val PREF_FALAR = "falar"
        internal const val PREF_CONTINUA = "continua"
        internal const val PREF_INTERROMPER = "interromper"
        internal const val PREF_VIGILIA = "vigilia"
        internal const val PREF_PEDIU_MIC = "pediu_mic"
        internal const val PREF_VOZ = "voz"
        internal const val PREF_VELOCIDADE = "velocidade"
        internal const val PREF_TOM = "tom"

        internal const val MENU_CHAVE = 1
        internal const val MENU_MODELO = 2
        internal const val MENU_FALAR = 3
        internal const val MENU_CONTINUA = 4
        internal const val MENU_INTERROMPER = 40
        internal const val MENU_NOVA = 5
        internal const val MENU_DIAGNOSTICO = 6
        internal const val MENU_VIGILIA = 7
        internal const val MENU_VOZ = 8
        internal const val MENU_AJUSTE_VOZ = 9
        internal const val MENU_HISTORICO = 10
        internal const val MENU_MEMORIAS = 11
        internal const val MENU_FUNDO = 12
        internal const val MENU_ASSISTENTE = 13
        internal const val MENU_INDEPENDENTE = 14
        internal const val MENU_MUSICA = 15
        internal const val MENU_GESTO = 16
        internal const val MENU_TELA_APAGADA = 17
        internal const val PREF_INDEPENDENTE = "modo_independente"
        internal const val PREF_OFERECEU_VOZ = "ofereceu_voz_offline"
        internal const val PREF_FUNDO = "vigilia_fundo"
        const val ACAO_ATIVAR = "com.tonimarques.sextafeira.ATIVAR"
        const val ACAO_PEDIDO = "com.tonimarques.sextafeira.PEDIDO"
        const val EXTRA_PEDIDO = "pedido"

        internal val COR_TEXTO_APAGADO = Color.parseColor("#6B769A")
    }
    }
