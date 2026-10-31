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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
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

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private enum class Escuta { NADA, VIGILIA, COMANDO }

    private lateinit var orbe: Orbe
    private lateinit var titulo: TextView
    private lateinit var status: TextView
    private lateinit var parcial: TextView
    private lateinit var mensagens: LinearLayout
    private lateinit var rolagem: ScrollView
    private lateinit var campo: EditText
    private lateinit var botaoMic: ImageButton
    private lateinit var botaoEnviar: ImageButton
    private lateinit var ferramentas: Ferramentas

    private val cerebro = Cerebro()
    private val principal = Handler(Looper.getMainLooper())

    private var gemini: Gemini? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var falando = false
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

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val tarefaVigilia = Runnable { iniciarVigilia() }

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ferramentas = Ferramentas(this)
        tts = TextToSpeech(this, this)
        setContentView(montarTela())
        adicionarMensagem("Olá! Diga \"Sexta-feira, ativar\", toque no orbe ou digite abaixo.", false)
        tratarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        naTela = true
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

    private fun tratarIntent(intent: Intent?) {
        val acao = intent?.action ?: return
        if (acao == ACAO_OUVIR || acao == Intent.ACTION_ASSIST) {
            orbe.post { ouvirComando() }
        }
    }

    // --------------------------------------------------------------------- tela
    private fun montarTela(): View {
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
            text = "SEXTA-FEIRA"
            textSize = 15f
            letterSpacing = 0.35f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(corAtual)
        }
        val subtitulo = TextView(this).apply {
            text = "ASSISTENTE PESSOAL"
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

    private fun aplicarCor(cor: Int) {
        corAtual = cor
        titulo.setTextColor(cor)
        botaoMic.setColorFilter(cor)
        botaoEnviar.setColorFilter(cor)
    }

    private fun botaoIcone(icone: Int, descricao: String, cor: Int, acao: () -> Unit) = ImageButton(this).apply {
        setImageResource(icone)
        setColorFilter(cor)
        contentDescription = descricao
        background = null
        setPadding(dp(11), dp(11), dp(11), dp(11))
        setOnClickListener { acao() }
    }

    private fun vidro(preenchimento: Int, borda: Int, raio: Int) = GradientDrawable().apply {
        setColor(preenchimento)
        setStroke(dp(1).coerceAtLeast(1), borda)
        cornerRadius = dp(raio).toFloat()
    }

    private fun comAlfa(cor: Int, alfa: Int) = Color.argb(alfa, Color.red(cor), Color.green(cor), Color.blue(cor))

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    private fun adicionarMensagem(texto: String, doUsuario: Boolean): TextView {
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
        var pedido = entrada

        // "Sexta-feira, ativar" (sozinho ou seguido de um pedido)
        restoDepoisDaAtivacao(pedido)?.let { resto ->
            if (resto.isBlank()) {
                adicionarMensagem(pedido, true)
                ativar()
                return
            }
            pedido = resto
        }

        adicionarMensagem(pedido, true)
        when (val decisao = cerebro.responder(pedido)) {
            is Cerebro.Decisao.Incerto -> {
                if (obterGemini() == null) mostrarDecisao(cerebro.usarPalpite(), pedido, semIA = true)
                else consultarIA(pedido)
            }
            else -> mostrarDecisao(decisao, pedido)
        }
    }

    private fun mostrarDecisao(decisao: Cerebro.Decisao, pedido: String, semIA: Boolean = false) {
        when (decisao) {
            is Cerebro.Decisao.Executar -> {
                val resultado = ferramentas.executar(decisao.nome, decisao.args)
                val appNaoAchado = decisao.nome == "abrir_app" && resultado.startsWith("Não encontrei")
                if (appNaoAchado && obterGemini() != null) consultarIA(pedido) else responder(resultado)
            }
            is Cerebro.Decisao.Responder -> {
                val aviso = if (semIA && obterGemini() == null && decisao.texto in BancoDeFrases.NAO_ENTENDI) {
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
                }
                responder(decisao.texto)
            }
            is Cerebro.Decisao.Incerto -> mostrarDecisao(cerebro.usarPalpite(), pedido, semIA)
        }
    }

    /** Envia para o Gemini, com tempo limite: se ele travar ou falhar, o cérebro local assume. */
    private fun consultarIA(pedido: String) {
        val assistente = obterGemini() ?: return mostrarDecisao(cerebro.usarPalpite(), pedido, semIA = true)
        ocupado = true
        val id = ++pedidoAtual
        mudarEstado(Orbe.Estado.PENSANDO, "PENSANDO…")

        principal.postDelayed({
            if (pedidoAtual == id && ocupado) {
                pedidoAtual++
                ocupado = false
                planoB("A inteligência artificial demorou demais.", pedido)
            }
        }, LIMITE_IA_MS)

        Thread {
            val resposta = try {
                assistente.enviar(pedido)
            } catch (e: Exception) {
                Gemini.RespostaIA(false, "Algo deu errado: ${e.message}")
            }
            runOnUiThread {
                if (id != pedidoAtual) return@runOnUiThread
                pedidoAtual++
                ocupado = false
                if (resposta.ok) responder(resposta.texto) else planoB("A inteligência artificial está indisponível agora.", pedido)
            }
        }.start()
    }

    private fun planoB(motivo: String, pedido: String) {
        val decisao = cerebro.usarPalpite()
        if (decisao is Cerebro.Decisao.Perguntar) {
            mostrarDecisao(decisao, pedido)
        } else {
            responder("$motivo ${(decisao as? Cerebro.Decisao.Responder)?.texto ?: ""}".trim())
        }
    }

    private fun responder(texto: String) {
        adicionarMensagem(texto, false)
        falar(texto)
    }

    private fun obterGemini(): Gemini? {
        val k = chave()
        if (k.isBlank()) return null
        return gemini ?: Gemini(k, modelo(), ferramentas).also { gemini = it }
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
    }

    private fun falar(texto: String) {
        pararVigilia()
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
        if (continuar) ouvirComando() else agendarVigilia(500)
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
        tts?.speak("Olá! Eu sou a Sexta-feira. Gostou desta voz?", TextToSpeech.QUEUE_FLUSH, null, ID_AMOSTRA)
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
            .setTitle("Voz da Sexta-feira")
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
    private fun vigiliaLigada() = prefs.getBoolean(PREF_VIGILIA, true)

    private fun temMicrofone() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun obterReconhecedor(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        return reconhecedor ?: SpeechRecognizer.createSpeechRecognizer(this).also {
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

    private fun alternarEscuta() {
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
                        agendarVigilia(400)
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
                    agendarVigilia(1500)
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
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com a Sexta-feira")
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
        menu.add(0, MENU_VIGILIA, 0, "Ouvir \"Sexta-feira, ativar\"")
            .setCheckable(true).setChecked(vigiliaLigada())
        menu.add(0, MENU_VOZ, 1, "Escolher a voz")
        menu.add(0, MENU_AJUSTE_VOZ, 2, "Velocidade e tom da voz")
        menu.add(0, MENU_FALAR, 3, "Responder em voz alta")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_FALAR, true))
        menu.add(0, MENU_CONTINUA, 4, "Conversa contínua por voz")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_CONTINUA, true))
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
                    this, if (ligado) "Diga \"Sexta-feira, ativar\" com o app aberto." else "Ativação por voz desligada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            MENU_VOZ -> escolherVoz()
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
                adicionarMensagem("Conversa nova. Em que posso ajudar?", false)
            }
            else -> return false
        }
        return true
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
        val balao = adicionarMensagem("Testando a conexão, aguarde até 1 minuto…", false)
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
        private const val LIMITE_IA_MS = 22_000L
        private const val ID_AMOSTRA = "amostra"
        private const val MODELO_PADRAO = "gemini-flash-latest"
        private const val STATUS_PADRAO = "TOQUE NO ORBE PARA FALAR"
        private const val STATUS_VIGILIA = "DIGA “SEXTA-FEIRA, ATIVAR”"

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

        private val COR_TEXTO_APAGADO = Color.parseColor("#6B769A")
    }
}
