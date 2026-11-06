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
    /** Para os arquivos separados (fora da classe não dá para perguntar ::bandejaAnexos.isInitialized). */
    internal fun bandejaPronta() = ::bandejaAnexos.isInitialized
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
    /** Os três pontinhos de "pensando" na conversa (ver BalaoPensando.kt). */
    internal var balaoPensando: View? = null
    internal fun conversaPronta() = ::mensagens.isInitialized
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
        // quem instala do zero (sem nenhuma IA) vê o cadastro guiado, até concluir ou configurar uma IA
        if (javaClass == MainActivity::class.java && precisaDoCadastro()) window.decorView.postDelayed({ if (!isFinishing) cadastroGuiado() }, 900)
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
        // pedidos da página Publicações do site (Natália): confere ao abrir e mantém o alarme de 2 minutos
        PedidosDoSite.conferirEmSegundoPlano(this, forcar = true)
        Cobranca.conferirEmSegundoPlano(this)
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
        // a resposta chegou: tira os pontinhos (e põe de novo embaixo se ela ainda estiver pensando, ex.: aviso de progresso)
        val pensava = balaoPensando != null
        esconderPensando()
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
        if (pensava && orbe.estado == Orbe.Estado.PENSANDO) mostrarPensando()
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
        if (estado == Orbe.Estado.PENSANDO) mostrarPensando() else esconderPensando()
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
                decisao.nome == "catalogo" -> abrirCatalogo()
                decisao.nome == "catalogo_venda" -> registrarVenda(null)
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

    // ----------------------------------------------------------- cadastro de produto
    internal var fotoDaCamera: Uri? = null
    internal var cadastroFoto: ((Uri) -> Unit)? = null

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

    // ----------------------------------------------------------- estudo diário
    internal var estudando = false

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
