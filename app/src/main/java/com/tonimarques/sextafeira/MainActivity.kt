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
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
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
    @Volatile private var atualizandoConhecimento = false
    private val memoria by lazy { Memoria(this) }

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val tarefaVigilia = Runnable { iniciarVigilia() }

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ferramentas = Ferramentas(this, memoria)
        // A busca no Google roda numa thread de fundo, chamada pela ferramenta buscar_na_internet.
        ferramentas.buscador = { pergunta -> gemini?.buscarNaInternet(pergunta) }
        cerebro.apelido = memoria.valor("apelido") ?: "chefe"
        cerebro.carregarAprendidas(memoria.aprendidas())
        cerebro.aoAprender = { frase, intencao -> memoria.aprender(frase, intencao) }
        tts = TextToSpeech(this, this)
        setContentView(montarTela())
        carregarConversaAnterior()
        mensagemInicial()?.let { adicionarMensagem(it, false, salvar = false) }
        tratarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        naTela = true
        VigiliaService.pausarPorTela(true)
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
        VigiliaService.pausarPorTela(false)
        principal.removeCallbacks(tarefaVigilia)
        if (escuta != Escuta.NADA) {
            escuta = Escuta.NADA
            reconhecedor?.cancel()
        }
        super.onPause()
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        tts?.shutdown()
        reconhecedor?.destroy()
        super.onDestroy()
    }

    protected open fun mensagemInicial(): String? = "Olá! Diga \"${Marca.nome}, ativar\", toque no orbe ou digite abaixo."

    /** Quando a conversa termina e não há mais nada a ouvir. Na tela principal, volta a esperar a ativação. */
    protected open fun aoFicarOcioso() {
        agendarVigilia(500)
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
            setPadding(dp(22), dp(14), dp(10), dp(4))
        }
        val nomes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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

        val decisao = cerebro.responder(pedido)
        topicoAtual = when (decisao) {
            is Cerebro.Decisao.Memoria -> "Sobre mim"
            is Cerebro.Decisao.Executar -> Topicos.classificar(pedido, decisao.nome)
            else -> Topicos.classificar(pedido)
        }
        adicionarMensagem(pedido, true)
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
                decisao.nome in Ferramentas.USAM_REDE -> executarNaRede(decisao.nome, decisao.args)
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
        comTempoLimite("BUSCANDO…") {
            ferramentas.ultimasFontes = emptyList()
            val resultado = ferramentas.executar(nome, args)
            val fontes = ferramentas.ultimasFontes
            val acao: () -> Unit = {
                responder(resultado)
                mostrarFontes(fontes)
            }
            acao
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
            responder("$motivo ${(decisao as? Cerebro.Decisao.Responder)?.texto ?: ""}".trim())
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
                    pesquisarSozinha(pedido, amplo = true, depoisIA = false, motivo = "A inteligência artificial está indisponível agora.")
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
        if (continuar) ouvirComando() else aoFicarOcioso()
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
                        aoFicarOcioso()
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
                    aoFicarOcioso()
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

    // ------------------------------------------------------------------- menu
    private fun abrirMenu(ancora: View) {
        val popup = PopupMenu(this, ancora)
        val menu = popup.menu
        menu.add(0, MENU_VIGILIA, 0, "Ouvir \"${Marca.nome}, ativar\"")
            .setCheckable(true).setChecked(vigiliaLigada())
        menu.add(0, MENU_VOZ, 1, "Escolher a voz")
        menu.add(0, MENU_AJUSTE_VOZ, 2, "Velocidade e tom da voz")
        menu.add(0, MENU_FALAR, 3, "Responder em voz alta")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_FALAR, true))
        menu.add(0, MENU_CONTINUA, 4, "Conversa contínua por voz")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_CONTINUA, true))
        menu.add(0, MENU_HISTORICO, 5, "Histórico por tópico")
        menu.add(0, MENU_MEMORIAS, 5, "O que a ${Marca.nome} sabe sobre mim")
        menu.add(0, MENU_FUNDO, 0, "Ouvir \"${Marca.nome}, ativar\" com o app fechado")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_FUNDO, false))
        menu.add(0, MENU_ASSISTENTE, 1, "Abrir segurando o botão (assistente padrão)")
        menu.add(0, MENU_INDEPENDENTE, 5, "Modo independente (sem Gemini)")
            .setCheckable(true).setChecked(modoIndependente())
        menu.add(0, MENU_CHAVE, 5, "Chave do Gemini")
        menu.add(0, MENU_MODELO, 6, "Modelo do Gemini")
        menu.add(0, MENU_NOVA, 7, "Nova conversa")
        menu.add(0, MENU_DIAGNOSTICO, 8, "Diagnóstico de conexão")
        popup.setOnMenuItemClickListener { tratarMenu(it) }
        popup.show()
    }

    private fun tratarMenu(item: MenuItem): Boolean {
        when (item.itemId) {
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
                Toast.makeText(
                    this, if (ligado) "Diga \"${Marca.nome}, ativar\" com o app aberto." else "Ativação por voz desligada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            MENU_VOZ -> escolherVoz()
            MENU_HISTORICO -> mostrarHistorico()
            MENU_MEMORIAS -> mostrarMemorias()
            MENU_FUNDO -> alternarEscutaDeFundo()
            MENU_ASSISTENTE -> definirAssistentePadrao()
            MENU_INDEPENDENTE -> {
                val ligado = !modoIndependente()
                prefs.edit().putBoolean(PREF_INDEPENDENTE, ligado).apply()
                gemini = null
                Toast.makeText(
                    this,
                    if (ligado) "Modo independente: a ${Marca.nome} responde sozinha, sem o Gemini."
                    else "Gemini ligado de novo para perguntas que ela não souber.",
                    Toast.LENGTH_LONG
                ).show()
            }
            MENU_AJUSTE_VOZ -> ajustarVoz()
            MENU_CHAVE -> pedirChave()
            MENU_MODELO -> pedirModelo()
            MENU_DIAGNOSTICO -> diagnosticar()
            MENU_FALAR -> {
                val ligado = !prefs.getBoolean(PREF_FALAR, true)
                prefs.edit().putBoolean(PREF_FALAR, ligado).apply()
                if (!ligado) tts?.stop()
                Toast.makeText(this, if (ligado) "Respostas em voz alta ligadas." else "Respostas em voz alta desligadas.", Toast.LENGTH_SHORT).show()
            }
            MENU_CONTINUA -> {
                val ligado = !prefs.getBoolean(PREF_CONTINUA, true)
                prefs.edit().putBoolean(PREF_CONTINUA, ligado).apply()
                Toast.makeText(this, if (ligado) "Conversa contínua ligada." else "Conversa contínua desligada.", Toast.LENGTH_SHORT).show()
            }
            MENU_NOVA -> {
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
        val topicos = memoria.topicos()
        if (topicos.isEmpty()) {
            Toast.makeText(this, "Ainda não há conversas salvas.", Toast.LENGTH_SHORT).show()
            return
        }
        val nomes = topicos.map { (t, n) -> "$t  ($n)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Histórico por tópico")
            .setItems(nomes) { _, qual -> mostrarTopico(topicos[qual].first) }
            .setNeutralButton("Apagar todo o histórico") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Apagar todo o histórico?")
                    .setMessage("As conversas salvas serão apagadas. As memórias continuam.")
                    .setPositiveButton("Apagar") { _, _ -> memoria.apagarHistorico() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun mostrarTopico(topico: String) {
        val formato = java.text.SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
        val texto = memoria.mensagens(topico, 150).joinToString("\n\n") { m ->
            val quem = if (m.doUsuario) "Você" else Marca.nome
            "${formato.format(java.util.Date(m.quando))} · $quem\n${m.texto}"
        }
        val vista = TextView(this).apply {
            this.text = texto
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        val rolar = ScrollView(this).apply { addView(vista) }
        rolar.post { rolar.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this)
            .setTitle(topico)
            .setView(rolar)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Apagar tópico") { _, _ -> memoria.apagarTopico(topico) }
            .show()
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
        if (!prefs.getBoolean(PREF_FUNDO, false) || !temMicrofone() || !podeSobrepor()) return
        try {
            VigiliaService.iniciar(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui ligar a escuta em segundo plano.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun alternarEscutaDeFundo() {
        val ligar = !prefs.getBoolean(PREF_FUNDO, false)
        prefs.edit().putBoolean(PREF_FUNDO, ligar).apply()
        if (!ligar) {
            VigiliaService.parar(this)
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
        private const val PREF_INDEPENDENTE = "modo_independente"
        private const val PREF_FUNDO = "vigilia_fundo"
        const val ACAO_ATIVAR = "com.tonimarques.sextafeira.ATIVAR"
        const val ACAO_PEDIDO = "com.tonimarques.sextafeira.PEDIDO"
        const val EXTRA_PEDIDO = "pedido"

        private val COR_TEXTO_APAGADO = Color.parseColor("#6B769A")
    }
}
