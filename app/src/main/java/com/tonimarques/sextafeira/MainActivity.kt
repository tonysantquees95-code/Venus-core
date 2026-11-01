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
import java.util.Locale

open class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private enum class Escuta { NADA, VIGILIA, COMANDO }

    protected lateinit var orbe: Orbe
    protected lateinit var titulo: TextView
    protected lateinit var status: TextView
    protected lateinit var parcial: TextView
    protected lateinit var mensagens: LinearLayout
    protected lateinit var rolagem: ScrollView
    protected lateinit var campo: EditText
    protected lateinit var botaoMic: ImageButton
    protected lateinit var botaoEnviar: ImageButton
    private lateinit var ferramentas: Ferramentas

    private val cerebro = Cerebro()
    protected val principal = Handler(Looper.getMainLooper())

    private var gemini: Gemini? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var falando = false
    private var falaPendente: String? = null
    private var reconhecedor: SpeechRecognizer? = null
    private var escuta = Escuta.NADA
    private var errosSeguidos = 0
    private var ocupado = false
    private var pedidoAtual = 0
    private var ultimaFoiVoz = false
    private var forcarEscuta = false
    private var ouvirAposPermissao = false
    private var naTela = false
    private var corAtual = Color.parseColor("#36D6FF")
    private var topicoAtual = "Conversa"
    private var respostaAtrasada = 0

    /** Esperando o endereço de um lugar ("casa", "trabalho"...) depois de perguntar. */
    private var aguardandoLugar: String? = null
    @Volatile private var atualizandoConhecimento = false
    private val memoria by lazy { Memoria(this) }

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val tarefaVigilia = Runnable { iniciarVigilia() }
    private var despertador: Despertador? = null

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ferramentas = Ferramentas(this, memoria)
        // A busca no Google roda numa thread de fundo, chamada pela ferramenta buscar_na_internet.
        ferramentas.buscador = { pergunta -> gemini?.buscarNaInternet(pergunta) }
        cerebro.apelido = memoria.valor("apelido") ?: "chefe"
        VigiaDaLoja.garantir(this)
        cerebro.carregarAprendidas(memoria.aprendidas())
        cerebro.aoAprender = { frase, intencao -> memoria.aprender(frase, intencao) }
        try {
            if (Calculadora.conversaVazia()) for (m in memoria.mensagens(null, 120)) Calculadora.lembrar(m.texto)
        } catch (e: Exception) {
        }
        tts = TextToSpeech(this, this)
        setContentView(montarTela())
        carregarConversaAnterior()
        mensagemInicial()?.let { adicionarMensagem(it, false, salvar = false) }
        tratarIntent(intent)
        if (javaClass == MainActivity::class.java) principal.postDelayed({ oferecerVozOffline() }, 1500)
        if (javaClass == MainActivity::class.java) principal.postDelayed({ procurarAtualizacao(manual = false) }, 4000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        naTela = true
        VigiliaService.pausarPorTela(true)
        VigiliaService.aoGestoNaTela = {
            if (!ocupado && escuta != Escuta.COMANDO) {
                ultimaFoiVoz = true
                ouvirComando()
            }
        }
        iniciarEscutaDeFundoSePreciso()
        atualizarConhecimentoSePreciso()
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
        tts?.shutdown()
        reconhecedor?.destroy()
        super.onDestroy()
    }

    protected open fun mensagemInicial(): String? = "Olá! Diga \"${Marca.nome}, ativar\", toque no orbe ou digite abaixo."

    /** Quando a conversa termina e não há mais nada a ouvir. Na tela principal, volta a esperar a ativação. */
    protected open fun aoFicarOcioso() {
        agendarVigilia(500)
    }

    /** Terminou a conversa: devolve o som para o app de música e segue o que cada tela faz quando fica ociosa. */
    private fun ficarOcioso() {
        liberarFoco()
        aoFicarOcioso()
    }

    // ------------------------------------------------- foco de áudio (música)
    private var foco: android.media.AudioFocusRequest? = null

    /** Enquanto a Vênus ouve e responde, a música pausa sozinha (e volta depois). */
    private fun pedirFoco() {
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

    private fun liberarFoco() {
        val f = foco ?: return
        foco = null
        try {
            getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(f)
        } catch (e: Exception) {
        }
    }

    protected fun estaOcioso() = !ocupado && !falando && escuta == Escuta.NADA

    private fun tratarIntent(intent: Intent?) {
        val acao = intent?.action ?: return
        when (acao) {
            ACAO_OUVIR, Intent.ACTION_ASSIST -> orbe.post {
                ultimaFoiVoz = true
                ouvirComando()
            }
            ACAO_ATIVAR -> orbe.post { ativar() }
            Atualizador.ACAO_INSTALAR -> resultadoDaInstalacao(intent)
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { u -> orbe.post { abrirDocumento(u) } }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null && intent.type?.startsWith("image/") == true) orbe.post { fotoCompartilhada(uri) }
                else if (uri != null) orbe.post { abrirDocumento(uri) }
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
    protected open fun montarTela(): View {
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
        cabecalho.addView(botaoIcone(R.drawable.ic_historico, "Histórico de conversas", COR_TEXTO_APAGADO) { mostrarHistorico() })
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
        val botaoMenu = botaoIcone(R.drawable.ic_menu_futuro, "Menu", COR_TEXTO_APAGADO) { }
        botaoMenu.setOnClickListener { abrirMenu(it) }
        cabecalho.addView(botaoMenu)
        raiz.addView(cabecalho)

        orbe = Orbe(this).apply {
            setOnClickListener { alternarEscuta() }
            aoMudarCor = { cor -> aplicarCor(cor) }
        }
        val alturaOrbe = (resources.displayMetrics.heightPixels * 0.38f).toInt()
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

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(4), dp(2))
            background = vidro(Color.argb(18, 255, 255, 255), Color.argb(40, 255, 255, 255), 28)
        }
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
        val paramsBarra = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(14), dp(6), dp(14), dp(14)) }
        raiz.addView(barra, paramsBarra)

        return raiz
    }

    protected fun aplicarCor(cor: Int) {
        corAtual = cor
        titulo.setTextColor(cor)
        botaoMic.setColorFilter(cor)
        botaoEnviar.setColorFilter(cor)
    }

    protected fun botaoIcone(icone: Int, descricao: String, cor: Int, acao: () -> Unit) = ImageButton(this).apply {
        setImageResource(icone)
        setColorFilter(cor)
        contentDescription = descricao
        background = null
        setPadding(dp(11), dp(11), dp(11), dp(11))
        setOnClickListener { acao() }
    }

    protected fun vidro(preenchimento: Int, borda: Int, raio: Int) = GradientDrawable().apply {
        setColor(preenchimento)
        setStroke(dp(1).coerceAtLeast(1), borda)
        cornerRadius = dp(raio).toFloat()
    }

    private fun comAlfa(cor: Int, alfa: Int) = Color.argb(alfa, Color.red(cor), Color.green(cor), Color.blue(cor))

    protected fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    /** Mostra as últimas mensagens da conversa anterior, para dar continuidade. */
    protected open fun carregarConversaAnterior() {
        val anteriores = try {
            memoria.mensagens(null, 6)
        } catch (e: Exception) {
            emptyList()
        }
        for (m in anteriores) {
            if (m.texto.startsWith("Fontes: ")) continue
            adicionarMensagem(m.texto, m.doUsuario, salvar = false).alpha = 0.55f
        }
    }

    protected open fun adicionarMensagem(texto: String, doUsuario: Boolean, salvar: Boolean = true): TextView {
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

    private fun rolarParaBaixo() {
        rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }
    }

    private fun mudarEstado(estado: Orbe.Estado, texto: String) {
        orbe.estado = estado
        status.text = texto
    }

    private fun mudarEstadoOcioso() {
        mudarEstado(Orbe.Estado.OCIOSO, if (vigiliaLigada() && temMicrofone()) STATUS_VIGILIA else STATUS_PADRAO)
    }

    // ---------------------------------------------------------------- conversa
    private fun enviarTexto() {
        val texto = campo.text.toString().trim()
        if (texto.isEmpty()) return
        campo.setText("")
        ultimaFoiVoz = false
        processar(texto)
    }

    private fun processar(entrada: String) {
        if (ocupado) return
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
                documentoAberto = null
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
        val decisao = cerebro.responder(pedido)
        topicoAtual = when (decisao) {
            is Cerebro.Decisao.Memoria -> "Sobre mim"
            is Cerebro.Decisao.Executar -> Topicos.classificar(pedido, decisao.nome)
            else -> Topicos.classificar(pedido)
        }
        adicionarMensagem(original, true)
        cerebro.ultimaCorrecao?.let { mostrarNota("Entendi: “$it”") }
        if (decisao is Cerebro.Decisao.Incerto) {
            responderPergunta(pedido)
        } else {
            mostrarDecisao(decisao, pedido)
        }
    }

    private fun mostrarDecisao(decisao: Cerebro.Decisao, pedido: String, semIA: Boolean = false) {
        when (decisao) {
            is Cerebro.Decisao.Executar -> when {
                decisao.nome == "pesquisar_na_web" ->
                    buscaInteligente(decisao.args["termo"]?.toString().orEmpty().ifBlank { pedido })
                decisao.nome == "loja_cadastrar" -> abrirCadastro(decisao.args)
                decisao.nome == "editar_fundo" -> editarFundo(null)
                decisao.nome == "ler_nota" -> lerNota(null)
                decisao.nome == "abrir_arquivo" -> escolherArquivo()
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
                responder(decisao.texto + aviso)
            }
            is Cerebro.Decisao.Perguntar -> {
                forcarEscuta = true
                responder(decisao.texto)
            }
            is Cerebro.Decisao.Especial -> {
                when (decisao.tipo) {
                    "nova_conversa" -> {
                        gemini = null
                        cerebro.esquecerPendencias()
                        mensagens.removeAllViews()
                    }
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
    private fun tratarMemoria(pedido: ComandosMemoria.Pedido) {
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

    private fun resumoDaMemoria(): String {
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

    private fun confirmarApagarMemorias() {
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

    private fun tocarFavorita() {
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
    private fun comTempoLimite(estado: String, trabalho: () -> (() -> Unit)) {
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
                { responder("Não consegui buscar isso agora. Confira sua internet e tente de novo.") }
            }
            runOnUiThread {
                if (id != pedidoAtual) return@runOnUiThread
                pedidoAtual++
                ocupado = false
                mostrar()
            }
        }.start()
    }

    private fun executarNaRede(nome: String, args: Map<String, Any>) {
        comTempoLimite(if (nome == "loja_confirmar") "SALVANDO…" else "BUSCANDO…") {
            ferramentas.ultimasFontes = emptyList()
            val resultado = ferramentas.executar(nome, args)
            val fontes = ferramentas.ultimasFontes
            val pendente = if (nome in setOf("loja_alterar", "loja_entregue", "loja_status", "responder_mensagem")) ferramentas.pendenteQualquer else null
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
    private var dialogoLoja: AlertDialog? = null

    /** Mostra Confirmar/Cancelar na tela; também dá para responder "sim" ou "não" falando. */
    private fun mostrarConfirmacaoLoja(pergunta: String) {
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

    private fun fecharConfirmacaoLoja() {
        dialogoLoja?.dismiss()
        dialogoLoja = null
    }

    private fun responderConfirmacaoLoja(sim: Boolean) {
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
    private fun buscaInteligente(termo: String) {
        val assistente = obterGemini()
        val conhecimento = ferramentas.conhecimento
        comTempoLimite("PESQUISANDO…") {
            val r = assistente?.buscarNaInternet(termo)
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
    private fun responderPergunta(pedido: String) {
        if (documentoAtivo() != null) {
            perguntarAoDocumento(pedido)
            return
        }
        try {
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
            Pesquisa.ehPesquisavel(pedido) -> pesquisarSozinha(pedido, amplo = false, depoisIA = true, motivo = null)
            else -> consultarIA(pedido)
        }
    }

    /**
     * Pesquisa sem o Gemini. [amplo] = também tenta Wikipédia/DuckDuckGo com as palavras-chave
     * (para quando não há outra saída). [depoisIA] = se não achar, pergunta ao Gemini.
     */
    private fun pesquisarSozinha(pedido: String, amplo: Boolean, depoisIA: Boolean, motivo: String?) {
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

    private fun semResposta(motivo: String, pedido: String) {
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
    private fun mostrarNota(texto: String) {
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
    private fun aprenderComResposta(pedido: String, resposta: Gemini.RespostaIA) {
        try {
            if (resposta.chamadas.size == 1) {
                val (nome, args) = resposta.chamadas[0]
                val mapa = args.keys().asSequence().associateWith { args.opt(it) }
                cerebro.aprenderComIA(pedido, nome, mapa)
            } else if (resposta.chamadas.isEmpty()) {
                ferramentas.conhecimento?.lembrarResposta(pedido, resposta.texto)
            }
        } catch (e: Exception) {
        }
    }

    /** Atualiza as novidades dos assuntos acompanhados, em segundo plano, a cada 6 horas. */
    private fun atualizarConhecimentoSePreciso() {
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

    private fun modoIndependente() = prefs.getBoolean(PREF_INDEPENDENTE, false)

    private fun mostrarFontes(fontes: List<Web.Fonte>) {
        if (fontes.isEmpty()) return
        val html = fontes.joinToString(" · ") { "<a href=\"${TextUtils.htmlEncode(it.url)}\">${TextUtils.htmlEncode(it.titulo)}</a>" }
        val vista = TextView(this).apply {
            text = Html.fromHtml("Fontes: $html", Html.FROM_HTML_MODE_COMPACT)
            textSize = 12f
            setTextColor(COR_TEXTO_APAGADO)
            setLinkTextColor(corAtual)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(dp(6), dp(4), dp(6), 0)
        }
        mensagens.addView(vista)
        rolarParaBaixo()
        memoria.salvarMensagem(false, "Fontes: " + fontes.joinToString("; ") { "${it.titulo} (${it.url})" }, topicoAtual)
    }

    /** Envia para o Gemini, com tempo limite: se ele travar ou falhar, o cérebro local assume. */
    private fun consultarIA(pedido: String) {
        val assistente = obterGemini()
            ?: return pesquisarSozinha(pedido, amplo = true, depoisIA = false, motivo = null)
        ocupado = true
        val id = ++pedidoAtual
        mudarEstado(Orbe.Estado.PENSANDO, "PENSANDO…")
        ferramentas.ultimasFontes = emptyList()

        principal.postDelayed({
            if (pedidoAtual == id && ocupado) {
                pedidoAtual++
                ocupado = false
                respostaAtrasada = id
                responder("A resposta está demorando mais que o normal. Se ela chegar, eu mostro aqui.")
            }
        }, LIMITE_IA_MS)

        Thread {
            val resposta = try {
                assistente.enviar(pedido)
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
                    // Gemini fora do ar: a Vênus pesquisa sozinha antes de desistir.
                    pesquisarSozinha(pedido, amplo = true, depoisIA = false, motivo = "Essa eu ainda não sei sozinha, e o Gemini não respondeu agora.")
                }
            }
        }.start()
    }

    private fun responder(texto: String) {
        adicionarMensagem(texto, false)
        falar(texto)
    }

    private fun obterGemini(): Gemini? {
        if (modoIndependente()) return null
        val k = chave()
        if (k.isBlank()) return null
        return gemini ?: Gemini(k, modelo(), ferramentas) { memoria.resumoParaIA() }.also { gemini = it }
    }

    private fun chave() = prefs.getString(PREF_CHAVE, "").orEmpty()
    private fun modelo() = prefs.getString(PREF_MODELO, MODELO_PADRAO).orEmpty().ifBlank { MODELO_PADRAO }

    // ---------------------------------------------------------------- ativação
    private fun ativar() {
        Som.ativacao()
        orbe.pulsar()
        ultimaFoiVoz = true
        forcarEscuta = true
        val saudacao = cerebro.saudacaoDeAtivacao()
        adicionarMensagem(saudacao, false)
        principal.postDelayed({ falar(saudacao) }, 450)
    }

    private fun restoDepoisDaAtivacao(texto: String): String? = Cerebro.restoDepoisDaAtivacao(texto)

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

    private fun falar(texto: String) {
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
        tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resposta")
    }

    /** Depois de responder: volta a ouvir (conversa) ou volta a esperar "Sexta-feira, ativar". */
    private fun aposFala() {
        falando = false
        mudarEstadoOcioso()
        val continuar = ultimaFoiVoz && naTela && !ocupado &&
            (forcarEscuta || cerebro.aguardandoResposta || prefs.getBoolean(PREF_CONTINUA, true))
        if (continuar) ouvirComando() else ficarOcioso()
    }

    private fun vozesEmPortugues(): List<Voice> {
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

    private fun aplicarVozSalva() {
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

    private fun amostraDeVoz() {
        tts?.speak("Olá! Eu sou a ${Marca.nome}. Gostou desta voz?", TextToSpeech.QUEUE_FLUSH, null, ID_AMOSTRA)
    }

    private fun escolherVoz() {
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

    private fun baixarVozes() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Instale o app Serviços de voz do Google pela Play Store.", Toast.LENGTH_LONG).show()
        }
    }

    private fun ajustarVoz() {
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
    protected open fun vigiliaLigada() = prefs.getBoolean(PREF_VIGILIA, true)

    private fun temMicrofone() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun obterReconhecedor(): SpeechRecognizer? {
        return reconhecedor ?: (Reconhecedor.criar(this) ?: return null).also {
            it.setRecognitionListener(ouvinte)
            reconhecedor = it
        }
    }

    private fun intentDeEscuta() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

    private fun agendarVigilia(atraso: Long) {
        principal.removeCallbacks(tarefaVigilia)
        principal.postDelayed(tarefaVigilia, atraso)
    }

    /** Fica ouvindo em silêncio, esperando "Sexta-feira, ativar". Só com o app aberto na tela. */
    private fun iniciarVigilia() {
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

    private fun pararVigilia() {
        principal.removeCallbacks(tarefaVigilia)
        despertador?.parar()
        if (escuta == Escuta.VIGILIA) {
            escuta = Escuta.NADA
            reconhecedor?.cancel()
        }
    }

    protected fun alternarEscuta() {
        when (escuta) {
            Escuta.COMANDO -> reconhecedor?.stopListening()
            else -> {
                ultimaFoiVoz = true
                ouvirComando()
            }
        }
    }

    private fun ouvirComando() {
        if (ocupado || escuta == Escuta.COMANDO) return
        pararVigilia()
        tts?.stop()
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

    private fun mostrarParcial(texto: String) {
        parcial.text = texto
        parcial.visibility = if (texto.isBlank()) View.GONE else View.VISIBLE
    }

    private val ouvinte = object : RecognitionListener {
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
                    ultimaFoiVoz = false
                    mostrarParcial("")
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
    private fun ouvirPeloGoogle() {
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
        if (requestCode == PEDIDO_ARQUIVO) {
            if (resultCode == RESULT_OK) data?.data?.let { abrirDocumento(it) }
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
    private var ajustes: TelaDeAjustes? = null
    private var progressoVozOffline: String? = null

    private fun abrirMenu(@Suppress("UNUSED_PARAMETER") ancora: View) {
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
                    item("Escolher a voz", { "Vozes em português instaladas no celular." }, MENU_VOZ),
                    item("Velocidade e tom", { "Deixe a fala mais rápida, mais lenta, mais grave ou mais aguda." }, MENU_AJUSTE_VOZ),
                    item("Responder em voz alta", { "Além de escrever, ela fala as respostas." }, MENU_FALAR) {
                        prefs.getBoolean(PREF_FALAR, true)
                    },
                    item("Conversa contínua", { "Depois de responder, ela volta a ouvir sem você precisar tocar no orbe." },
                        MENU_CONTINUA) { prefs.getBoolean(PREF_CONTINUA, true) },
                )
            ),
            TelaDeAjustes.Secao(
                "Loja Desejo de Intimidade", listOf(
                    TelaDeAjustes.Ajuste("Conectar à loja", {
                        ferramentas.loja.email?.let { "Conectada como $it. Pergunte: como foram as vendas hoje, tem pedido novo, o que está acabando. Toque para desconectar." }
                            ?: "Entre com o login de administrador do site para a $n consultar vendas, pedidos e estoque. A senha fica criptografada só no celular."
                    }) { conectarLoja() },
                    TelaDeAjustes.Ajuste("Avisos de pedidos e estoque", {
                        "A cada 15 minutos, mais ou menos, a $n olha a loja e avisa por notificação quando entra um pedido pago " +
                            "ou um produto fica com 2 unidades ou menos."
                    }, { VigiaDaLoja.ligado(this) }) { alternarAvisosDaLoja() },
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
                    item("Modelo do Gemini", { modelo() }, MENU_MODELO),
                )
            ),
            TelaDeAjustes.Secao(
                "Memória e conversas", listOf(
                    item("O que a $n sabe sobre mim", { "Seus gostos, favoritos e o que ela aprendeu. Dá para apagar." }, MENU_MEMORIAS),
                    item("Nova conversa", { "Limpa a tela e começa do zero (o histórico continua salvo)." }, MENU_NOVA),
                )
            ),
            TelaDeAjustes.Secao(
                "Sistema", listOf(
                    TelaDeAjustes.Ajuste("Atualizações", {
                        "Versão ${versaoDoApp()} instalada. A ${Marca.nome} avisa sozinha quando sai uma nova; toque para procurar agora."
                    }) { procurarAtualizacao(manual = true) },
                    item("Diagnóstico de conexão", { "Testa a internet e o Gemini e mostra o que está funcionando." }, MENU_DIAGNOSTICO),
                )
            ),
        )
        ajustes = TelaDeAjustes(this, versaoDoApp(), secoes).also {
            it.setOnDismissListener { ajustes = null }
            it.show()
        }
    }

    private fun versaoDoApp(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun descricaoVozOffline(): String = progressoVozOffline ?: if (ModeloDeVoz.pronto(this)) {
        "Instalada. O microfone fica aberto direto, só com o pontinho verde, sem o ícone piscando. Toque para remover."
    } else {
        "Toque para baixar (31 MB, uma vez só). Sem ela, o ícone do microfone fica piscando na barra de status enquanto a ${Marca.nome} espera a frase."
    }

    private fun tratarVozOffline() {
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

    private fun baixarVozOffline() {
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
    private fun oferecerVozOffline(forcar: Boolean = false) {
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

    private fun tratarMenu(id: Int): Boolean {
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
                if (!ligado) tts?.stop()
            }
            MENU_CONTINUA -> {
                val ligado = !prefs.getBoolean(PREF_CONTINUA, true)
                prefs.edit().putBoolean(PREF_CONTINUA, ligado).apply()
            }
            MENU_NOVA -> {
                ajustes?.dismiss()
                gemini = null
                cerebro.esquecerPendencias()
                mensagens.removeAllViews()
                adicionarMensagem("Conversa nova. Em que posso ajudar?", false, salvar = false)
            }
            else -> return false
        }
        return true
    }

    // --------------------------------------------------- histórico e memórias
    private fun mostrarHistorico() {
        TelaDeHistorico(this, memoria, corAtual).show()
    }

    private fun mostrarMemorias() {
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
    private fun podeSobrepor() = Settings.canDrawOverlays(this)

    private fun iniciarEscutaDeFundoSePreciso() {
        val escuta = prefs.getBoolean(PREF_FUNDO, false) && temMicrofone()
        val gesto = prefs.getBoolean(VigiliaService.PREF_GESTO, false)
        if ((!escuta && !gesto) || !podeSobrepor()) return
        try {
            VigiliaService.iniciar(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui ligar a escuta em segundo plano.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun alternarTelaApagada() {
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

    private fun alternarGesto() {
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

    private fun alternarEscutaDeFundo() {
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

    private fun definirAssistentePadrao() {
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

    private fun diagnosticar() {
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

    private fun alternarAvisosDaLoja() {
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
    private var fotoDaCamera: Uri? = null
    private var cadastroFoto: ((Uri) -> Unit)? = null

    /** Formulário de produto novo, já preenchido com o que foi falado. Foto pela câmera ou galeria e descrição pela IA. */
    private fun pedirFotoCamera() {
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

    private fun pedirFotoGaleria() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PEDIDO_FOTO_GALERIA)
        } catch (e: Exception) {
            Toast.makeText(this, "Não achei a galeria.", Toast.LENGTH_LONG).show()
        }
    }

    private fun abrirCadastro(args: Map<String, Any>, fotoInicial: ByteArray? = null) {
        val loja = ferramentas.loja
        if (!loja.conectada) {
            responder("A loja ainda não está conectada. Abra os ajustes e toque em Conectar à loja.")
            return
        }
        val nomeFalado = args["nome"]?.toString().orEmpty()
        responder(if (nomeFalado.isBlank()) "Vamos cadastrar. Preencha o produto na tela." else "Vamos cadastrar $nomeFalado. Confira os dados na tela e adicione a foto.")
        var foto: ByteArray? = fotoInicial

        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun rotulo(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        }
        fun campo(valor: String, dica: String, tipo: Int) = EditText(this).apply {
            setText(valor)
            hint = dica
            inputType = tipo
        }
        val nome = campo(nomeFalado, "Ex.: Gel de massagem morango 60ml", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val categorias = Loja.CATEGORIAS
        val categoria = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categorias.map { it.second })
            setSelection(categorias.indexOfFirst { it.first == Loja.categoriaProvavel(nomeFalado) }.coerceAtLeast(0))
        }
        val preco = campo((args["preco"] as? Int)?.let { "%d,%02d".format(it / 100, it % 100) }.orEmpty(), "Ex.: 29,90",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val estoque = campo((args["estoque"] as? Int)?.toString().orEmpty(), "Quantas unidades (deixe vazio se não controla)", InputType.TYPE_CLASS_NUMBER)
        val descricao = campo("", "Descrição curta (até 300 letras)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 2
            maxLines = 5
            filters = arrayOf(android.text.InputFilter.LengthFilter(300))
        }
        val previa = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(180)
            visibility = View.GONE
        }
        val avisoFoto = TextView(this).apply { textSize = 12f }
        val botoesFoto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tirar = android.widget.Button(this).apply { text = "Tirar foto" }
        val galeria = android.widget.Button(this).apply { text = "Da galeria" }
        botoesFoto.addView(tirar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        botoesFoto.addView(galeria, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val ia = android.widget.Button(this).apply { text = "Escrever a descrição com IA" }
        val mostrar = android.widget.Switch(this).apply {
            text = "Já mostrar no site"
            isChecked = false
            setPadding(0, dp(10), 0, dp(6))
        }

        caixa.addView(rotulo("Nome"))
        caixa.addView(nome)
        caixa.addView(rotulo("Categoria"))
        caixa.addView(categoria)
        caixa.addView(rotulo("Preço (R$)"))
        caixa.addView(preco)
        caixa.addView(rotulo("Estoque"))
        caixa.addView(estoque)
        caixa.addView(rotulo("Foto"))
        caixa.addView(botoesFoto)
        caixa.addView(previa)
        caixa.addView(avisoFoto)
        val trocarFundo = android.widget.Button(this).apply { text = "Trocar o fundo da foto" }
        caixa.addView(trocarFundo)
        fun mostrarFoto(bytes: ByteArray) {
            foto = bytes
            previa.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            previa.visibility = View.VISIBLE
            avisoFoto.text = "Foto pronta (${bytes.size / 1024} KB)."
        }
        fotoInicial?.let { mostrarFoto(it) }
        trocarFundo.setOnClickListener {
            val atual = foto
            if (atual == null) Toast.makeText(this, "Escolha a foto primeiro.", Toast.LENGTH_SHORT).show()
            else editarFundo(atual) { nova -> mostrarFoto(nova) }
        }
        caixa.addView(rotulo("Descrição"))
        caixa.addView(descricao)
        if (chave().isNotBlank()) caixa.addView(ia)
        caixa.addView(mostrar)

        cadastroFoto = { uri ->
            avisoFoto.text = "Preparando a foto…"
            Thread {
                val bytes = FotoProvider.prepararParaEnviar(this, uri)
                runOnUiThread {
                    if (bytes == null) {
                        avisoFoto.text = "Não consegui ler essa foto. Tente outra."
                    } else {
                        mostrarFoto(bytes)
                    }
                }
            }.start()
        }
        tirar.setOnClickListener { pedirFotoCamera() }
        galeria.setOnClickListener { pedirFotoGaleria() }
        ia.setOnClickListener {
            val n = nome.text.toString().trim()
            if (n.isBlank()) {
                Toast.makeText(this, "Escreva o nome antes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ia.isEnabled = false
            ia.text = "Escrevendo…"
            val cat = categorias[categoria.selectedItemPosition].second
            val k = chave()
            val m = modelo()
            val f = foto
            Thread {
                val t = Gemini.descreverProduto(k, m, f, n, cat)
                runOnUiThread {
                    ia.isEnabled = true
                    ia.text = "Escrever a descrição com IA"
                    if (t != null) descricao.setText(t)
                    else Toast.makeText(this, "A IA não conseguiu escrever agora. Escreva você mesmo ou tente de novo.", Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Novo produto")
            .setView(ScrollView(this).apply { addView(caixa) })
            .setPositiveButton("Cadastrar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.setOnDismissListener { cadastroFoto = null }
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = nome.text.toString().trim()
                if (n.isBlank()) {
                    Toast.makeText(this, "Informe o nome do produto.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val textoPreco = preco.text.toString().trim()
                val centavos = if (textoPreco.isBlank()) null else ComandosLocais.centavos(textoPreco.replace(Regex("[^0-9,.]"), ""))
                if (textoPreco.isNotBlank() && (centavos == null || centavos <= 0)) {
                    Toast.makeText(this, "Confira o preço (ex.: 29,90).", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (mostrar.isChecked && centavos == null) {
                    Toast.makeText(this, "Para mostrar no site, informe o preço.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val novo = Loja.NovoProduto(
                    nome = n,
                    categoria = categorias[categoria.selectedItemPosition].first,
                    descricao = descricao.text.toString().trim().ifBlank { null },
                    precoCentavos = centavos,
                    estoque = estoque.text.toString().trim().toIntOrNull(),
                    imagemUrl = null,
                    ativo = mostrar.isChecked,
                )
                val fotoFinal = foto
                dialogo.dismiss()
                comTempoLimite("CADASTRANDO…") {
                    val r = try {
                        val url = fotoFinal?.let { loja.enviarFoto(it) }
                        loja.cadastrar(novo.copy(imagemUrl = url))
                    } catch (e: Loja.ErroDaLoja) {
                        "Não consegui cadastrar: ${e.message}"
                    } catch (e: Exception) {
                        "Não consegui falar com a loja agora. Confira a internet e tente de novo."
                    }
                    val acao: () -> Unit = { responder(r) }
                    acao
                }
            }
        }
        dialogo.show()
    }

    // ----------------------------------------------------------- trocar o fundo da foto
    /**
     * Editor de fundo: recorta o produto e coloca no fundo da loja, branco ou rosa; ou pede um fundo livre à IA.
     * [aoUsar]: quando veio do cadastro, devolve a foto pronta; sem ele, oferece salvar na galeria ou compartilhar.
     */
    private fun editarFundo(fotoInicial: ByteArray?, aoUsar: ((ByteArray) -> Unit)? = null) {
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

    private fun salvarNaGaleria(jpeg: ByteArray): Boolean = try {
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

    private fun compartilharFoto(jpeg: ByteArray) {
        val (arquivo, uri) = FotoProvider.novaFoto(this)
        arquivo.writeBytes(jpeg)
        val i = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("foto", uri)
        startActivity(Intent.createChooser(i, "Compartilhar foto"))
    }

    // ----------------------------------------------------------- arquivos (PDF, Word, Excel, áudio...)
    private var documentoAberto: Arquivos.Documento? = null
    private var documentoQuando = 0L
    private val conversaDocumento = mutableListOf<Pair<String, String>>()

    /** O arquivo continua "aberto" para perguntas por 30 minutos. */
    private fun documentoAtivo(): Arquivos.Documento? =
        documentoAberto?.takeIf { System.currentTimeMillis() - documentoQuando < 30 * 60_000L }

    private fun escolherArquivo() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                PEDIDO_ARQUIVO,
            )
        } catch (e: Exception) {
            responder("Não consegui abrir o seletor de arquivos.")
        }
    }

    /** Abre o arquivo, manda ao Gemini e mostra o resumo; boleto vira lembrete e nota vira compra, se você quiser. */
    private fun abrirDocumento(uri: Uri, avisado: Boolean = false) {
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
                    documentoAberto = doc
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

    private fun oferecerAcoesDoDocumento(doc: Arquivos.Documento, a: Gemini.Analise) {
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
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Linha digitável", linha))
                        Toast.makeText(this, "Linha digitável copiada. É só colar no app do banco.", Toast.LENGTH_LONG).show()
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Boleto" + (a.valorCentavos?.let { " de " + Loja.dinheiro(it) } ?: ""))
                    .setItems(opcoes.map { it.first }.toTypedArray()) { _, i -> opcoes[i].second() }
                    .setNegativeButton("Agora não", null)
                    .show()
            }
            a.tipo == "nota_fiscal" && doc.bytes != null && ferramentas.loja.conectada -> {
                AlertDialog.Builder(this)
                    .setTitle("Nota de compra?")
                    .setMessage("Se for uma compra de produtos para a loja, eu posso somar ao estoque e guardar o custo de cada um.")
                    .setPositiveButton("Lançar a compra") { _, _ -> lerNotaDoDocumento(doc) }
                    .setNegativeButton("Agora não", null)
                    .show()
            }
        }
    }

    private fun lerNotaDoDocumento(doc: Arquivos.Documento) {
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

    private fun perguntarAoDocumento(pergunta: String) {
        val doc = documentoAtivo() ?: return responder("Nenhum arquivo aberto. Compartilhe um arquivo comigo ou diga: abre um arquivo.")
        documentoQuando = System.currentTimeMillis()
        val k = chave()
        val m = modelo()
        val historico = conversaDocumento.toList()
        comTempoLimite("LENDO O ARQUIVO…") {
            val r = Gemini.perguntarDocumento(k, m, doc, pergunta, historico)
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
    private fun lerNota(uri: Uri?) {
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

    private fun confirmarCompra(itens: List<Loja.ItemDeCompra>, produtos: List<Loja.Produto>) {
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
                adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, opcoes)
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

    /** Foto recebida pelo "Compartilhar" de outro app. */
    private fun fotoCompartilhada(uri: Uri) {
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

    // ----------------------------------------------------------- atualização automática
    private val atualizador by lazy { Atualizador(this) }

    /** Procura versão nova. Automática (ao abrir, no máximo a cada 6 h e sem incomodar) ou pelo menu. */
    private fun procurarAtualizacao(manual: Boolean) {
        if (!manual && (atualizador.procurouRecentemente() || !atualizador.temToken)) return
        if (manual) Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            val resultado = try {
                Result.success(atualizador.procurar())
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                resultado.onSuccess { v ->
                    if (v != null) oferecerAtualizacao(v)
                    else if (manual) Toast.makeText(this, "Você já está com a versão mais nova (${versaoDoApp()}).", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    if (!manual) return@onFailure
                    if (e is Atualizador.ErroDeAtualizacao && e.precisaToken) pedirTokenDoGithub(e.message)
                    else Toast.makeText(this, e.message ?: "Não consegui procurar agora. Confira a internet.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun oferecerAtualizacao(v: Atualizador.Versao) {
        val novidades = v.notas.ifBlank { "Melhorias e correções." }
        AlertDialog.Builder(this)
            .setTitle("Nova versão da ${Marca.nome}: 1.${v.numero}")
            .setMessage("$novidades\n\nA atualização mantém suas memórias, ajustes e a conexão com a loja.")
            .setPositiveButton("Atualizar agora") { _, _ -> instalarAtualizacao(v) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun instalarAtualizacao(v: Atualizador.Versao) {
        val aviso = TextView(this).apply {
            text = "Baixando… 0%"
            setPadding(dp(24), dp(16), dp(24), dp(8))
            textSize = 16f
        }
        val dialogo = AlertDialog.Builder(this).setTitle("Atualizando a ${Marca.nome}").setView(aviso).setCancelable(false).show()
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
    private fun resultadoDaInstalacao(intent: Intent) {
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
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> Toast.makeText(this, "${Marca.nome} atualizada!", Toast.LENGTH_SHORT).show()
            -999 -> {}
            else -> {
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(this, "A instalação não foi concluída. $msg".trim(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pedirTokenDoGithub(motivo: String?) {
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

    private fun conectarLoja() {
        val loja = ferramentas.loja
        if (loja.conectada) {
            AlertDialog.Builder(this)
                .setTitle("Desconectar a loja?")
                .setMessage("A ${Marca.nome} deixa de consultar vendas, pedidos e estoque até você conectar de novo.")
                .setPositiveButton("Desconectar") { _, _ ->
                    loja.desconectar()
                    VigiaDaLoja.definir(this, false)
                    ajustes?.atualizar()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val email = campoDeDialogo("", "E-mail do administrador").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val senha = campoDeDialogo("", "Senha").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        caixa.addView(email)
        caixa.addView(senha)
        AlertDialog.Builder(this)
            .setTitle("Conectar à loja")
            .setMessage("Use o mesmo login do painel do site (conta de administrador).")
            .setView(caixa)
            .setPositiveButton("Conectar") { _, _ ->
                val e = email.text.toString().trim()
                val s = senha.text.toString()
                if (e.isBlank() || s.isBlank()) {
                    Toast.makeText(this, "Preencha o e-mail e a senha.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Conectando…", Toast.LENGTH_SHORT).show()
                Thread {
                    val erro = loja.conectar(e, s)
                    runOnUiThread {
                        ajustes?.atualizar()
                        if (erro == null) {
                            Toast.makeText(this, "Loja conectada!", Toast.LENGTH_SHORT).show()
                            responder("Loja conectada! Pode perguntar: como foram as vendas hoje, tem pedido novo, o que está acabando, ou quanto tem de estoque de um produto. Também posso mudar estoque e preço, esconder ou mostrar produtos, sempre pedindo confirmação, e listar as retiradas para agendar.")
                        } else {
                            Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirChave() {
        val entrada = campoDeDialogo(chave(), "Cole aqui sua chave")
        AlertDialog.Builder(this)
            .setTitle("Chave do Gemini")
            .setMessage(
                "Crie uma chave grátis em aistudio.google.com/apikey, copie e cole aqui. " +
                    "Os comandos e a conversa básica funcionam mesmo sem chave."
            )
            .setView(entrada)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit().putString(PREF_CHAVE, entrada.text.toString().trim()).apply()
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

    private fun pedirModelo() {
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

    private fun campoDeDialogo(valor: String, dica: String) = EditText(this).apply {
        setText(valor)
        hint = dica
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }

    companion object {
        const val ACAO_OUVIR = "com.tonimarques.sextafeira.OUVIR"
        private const val PEDIDO_VOZ = 10
        private const val PEDIDO_MICROFONE = 11
        private const val PEDIDO_NOTIFICACOES = 12
        private const val PEDIDO_FOTO_CAMERA = 40
        private const val PEDIDO_FOTO_GALERIA = 41
        private const val PEDIDO_ARQUIVO = 42
        private const val PREF_AVISO_ARQUIVOS = "aviso_arquivos"
        private const val LIMITE_IA_MS = 35_000L
        private const val ID_AMOSTRA = "amostra"
        private const val MODELO_PADRAO = "gemini-flash-latest"
        private const val STATUS_PADRAO = "TOQUE NO ORBE PARA FALAR"
        private val STATUS_VIGILIA = "DIGA “${Marca.nomeMaiusculo}, ATIVAR”"

        private const val PREF_CHAVE = "chave"
        private const val PREF_MODELO = "modelo"
        private const val PREF_FALAR = "falar"
        private const val PREF_CONTINUA = "continua"
        private const val PREF_VIGILIA = "vigilia"
        private const val PREF_PEDIU_MIC = "pediu_mic"
        private const val PREF_VOZ = "voz"
        private const val PREF_VELOCIDADE = "velocidade"
        private const val PREF_TOM = "tom"

        private const val MENU_CHAVE = 1
        private const val MENU_MODELO = 2
        private const val MENU_FALAR = 3
        private const val MENU_CONTINUA = 4
        private const val MENU_NOVA = 5
        private const val MENU_DIAGNOSTICO = 6
        private const val MENU_VIGILIA = 7
        private const val MENU_VOZ = 8
        private const val MENU_AJUSTE_VOZ = 9
        private const val MENU_HISTORICO = 10
        private const val MENU_MEMORIAS = 11
        private const val MENU_FUNDO = 12
        private const val MENU_ASSISTENTE = 13
        private const val MENU_INDEPENDENTE = 14
        private const val MENU_MUSICA = 15
        private const val MENU_GESTO = 16
        private const val MENU_TELA_APAGADA = 17
        private const val PREF_INDEPENDENTE = "modo_independente"
        private const val PREF_OFERECEU_VOZ = "ofereceu_voz_offline"
        private const val PREF_FUNDO = "vigilia_fundo"
        const val ACAO_ATIVAR = "com.tonimarques.sextafeira.ATIVAR"
        const val ACAO_PEDIDO = "com.tonimarques.sextafeira.PEDIDO"
        const val EXTRA_PEDIDO = "pedido"

        private val COR_TEXTO_APAGADO = Color.parseColor("#6B769A")
    }
}
