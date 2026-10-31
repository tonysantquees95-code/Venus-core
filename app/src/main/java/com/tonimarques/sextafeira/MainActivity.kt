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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

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

    private var gemini: Gemini? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var reconhecedor: SpeechRecognizer? = null
    private var ouvindo = false
    private var ocupado = false
    private var ultimaFoiVoz = false
    private var naTela = false
    private var corAtual = Color.parseColor("#36D6FF")

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ferramentas = Ferramentas(this)
        tts = TextToSpeech(this, this)
        setContentView(montarTela())
        adicionarMensagem("Olá! Toque no orbe e fale comigo, ou digite abaixo.", false)
        if (chave().isBlank()) pedirChave()
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
    }

    override fun onPause() {
        naTela = false
        if (ouvindo) {
            reconhecedor?.cancel()
            ouvindo = false
            mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
        }
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        reconhecedor?.destroy()
        super.onDestroy()
    }

    private fun tratarIntent(intent: Intent?) {
        val acao = intent?.action ?: return
        if (acao == ACAO_OUVIR || acao == Intent.ACTION_ASSIST) {
            orbe.post { ouvir() }
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

        // Cabeçalho
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

        // Orbe
        orbe = Orbe(this).apply {
            setOnClickListener { alternarEscuta() }
            aoMudarCor = { cor -> aplicarCor(cor) }
        }
        val alturaOrbe = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        raiz.addView(orbe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alturaOrbe))

        // Status e transcrição ao vivo
        status = TextView(this).apply {
            text = STATUS_PADRAO
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

        // Conversa
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

        // Barra de digitação em "vidro"
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

    // ---------------------------------------------------------------- conversa
    private fun enviarTexto() {
        val texto = campo.text.toString().trim()
        if (texto.isEmpty()) return
        campo.setText("")
        ultimaFoiVoz = false
        processar(texto)
    }

    private fun processar(pedido: String) {
        if (ocupado) return

        // 1) Comando rápido: resolvido no próprio celular, sem internet.
        val local = ComandosLocais.interpretar(pedido)
        if (local != null) {
            val resposta = when (local) {
                is ComandosLocais.Resultado.Resposta -> local.texto
                is ComandosLocais.Resultado.Acao -> ferramentas.executar(local.nome, local.args)
            }
            val appNaoAchado = local is ComandosLocais.Resultado.Acao &&
                local.nome == "abrir_app" && resposta.startsWith("Não encontrei")
            // Se não achou o app, talvez a frase não fosse um nome de app: deixa a IA tentar.
            if (!appNaoAchado || obterGemini() == null) {
                adicionarMensagem(pedido, true)
                adicionarMensagem(resposta, false)
                falar(resposta)
                return
            }
        }

        // 2) Pedido mais complexo: vai para a inteligência artificial.
        val assistente = obterGemini()
        if (assistente == null) {
            adicionarMensagem(pedido, true)
            val aviso = "Esse pedido precisa da inteligência artificial. Configure a chave do Gemini " +
                "no menu, ou diga \"ajuda\" para ver os comandos que funcionam sem ela."
            adicionarMensagem(aviso, false)
            falar(aviso)
            return
        }
        ocupado = true
        adicionarMensagem(pedido, true)
        mudarEstado(Orbe.Estado.PENSANDO, "PENSANDO…")

        Thread {
            val resposta = try {
                assistente.enviar(pedido)
            } catch (e: Exception) {
                "Algo deu errado: ${e.message}"
            }
            runOnUiThread {
                ocupado = false
                adicionarMensagem(resposta, false)
                falar(resposta)
            }
        }.start()
    }

    private fun obterGemini(): Gemini? {
        val k = chave()
        if (k.isBlank()) return null
        return gemini ?: Gemini(k, modelo(), ferramentas).also { gemini = it }
    }

    private fun chave() = prefs.getString(PREF_CHAVE, "").orEmpty()
    private fun modelo() = prefs.getString(PREF_MODELO, MODELO_PADRAO).orEmpty().ifBlank { MODELO_PADRAO }

    // ------------------------------------------------------------ fala (TTS)
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.setLanguage(Locale("pt", "BR"))
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { mudarEstado(Orbe.Estado.FALANDO, "FALANDO") }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
                    // Conversa contínua: depois de responder, volta a ouvir.
                    if (ultimaFoiVoz && naTela && !ocupado && prefs.getBoolean(PREF_CONTINUA, true)) ouvir()
                }
            }

            @Deprecated("Exigido pela classe base")
            override fun onError(utteranceId: String?) {
                runOnUiThread { mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO) }
            }
        })
        ttsPronto = true
    }

    private fun falar(texto: String) {
        if (!ttsPronto || !prefs.getBoolean(PREF_FALAR, true)) {
            mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
            return
        }
        val limpo = texto.replace(Regex("[*#_`>]"), "")
        tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resposta")
    }

    // ------------------------------------------------------- escuta (microfone)
    private fun alternarEscuta() {
        if (ouvindo) {
            reconhecedor?.stopListening()
        } else {
            ouvir()
        }
    }

    private fun ouvir() {
        if (ocupado || ouvindo) return
        tts?.stop()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ouvirPeloGoogle()
            return
        }
        val r = reconhecedor ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(ouvinte)
            reconhecedor = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        ouvindo = true
        mostrarParcial("")
        mudarEstado(Orbe.Estado.OUVINDO, "OUVINDO…")
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            ouvindo = false
            mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
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
            orbe.definirNivelVoz(rmsdB)
        }

        override fun onEndOfSpeech() {
            orbe.definirNivelVoz(-2f)
            mudarEstado(Orbe.Estado.PENSANDO, "PROCESSANDO…")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texto = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!texto.isNullOrBlank()) mostrarParcial(texto)
        }

        override fun onResults(results: Bundle?) {
            ouvindo = false
            mostrarParcial("")
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (texto.isNullOrBlank()) {
                ultimaFoiVoz = false
                mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
            } else {
                ultimaFoiVoz = true
                mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
                processar(texto)
            }
        }

        override fun onError(error: Int) {
            ouvindo = false
            ultimaFoiVoz = false
            mostrarParcial("")
            val texto = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NÃO OUVI NADA · TOQUE PARA FALAR"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMITA O USO DO MICROFONE"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "SEM INTERNET PARA OUVIR"
                else -> STATUS_PADRAO
            }
            mudarEstado(Orbe.Estado.OCIOSO, texto)
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
            ouvir()
        } else {
            mudarEstado(Orbe.Estado.OCIOSO, "SEM PERMISSÃO DO MICROFONE")
        }
    }

    // ------------------------------------------------------------------- menu
    private fun abrirMenu(ancora: View) {
        val popup = PopupMenu(this, ancora)
        val menu = popup.menu
        menu.add(0, MENU_CHAVE, 0, "Chave do Gemini")
        menu.add(0, MENU_MODELO, 1, "Modelo do Gemini")
        menu.add(0, MENU_FALAR, 2, "Responder em voz alta")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_FALAR, true))
        menu.add(0, MENU_CONTINUA, 3, "Conversa contínua por voz")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_CONTINUA, true))
        menu.add(0, MENU_NOVA, 4, "Nova conversa")
        menu.add(0, MENU_DIAGNOSTICO, 5, "Diagnóstico de conexão")
        popup.setOnMenuItemClickListener { tratarMenu(it) }
        popup.show()
    }

    private fun tratarMenu(item: MenuItem): Boolean {
        when (item.itemId) {
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
                mudarEstado(Orbe.Estado.OCIOSO, STATUS_PADRAO)
            }
        }.start()
    }

    private fun pedirChave() {
        val entrada = campoDeDialogo(chave(), "Cole aqui sua chave")
        AlertDialog.Builder(this)
            .setTitle("Chave do Gemini")
            .setMessage("Crie uma chave grátis em aistudio.google.com/apikey, copie e cole aqui. " +
                "Os comandos rápidos funcionam mesmo sem chave.")
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
        private const val MODELO_PADRAO = "gemini-flash-latest"
        private const val STATUS_PADRAO = "TOQUE NO ORBE PARA FALAR"

        private const val PREF_CHAVE = "chave"
        private const val PREF_MODELO = "modelo"
        private const val PREF_FALAR = "falar"
        private const val PREF_CONTINUA = "continua"

        private const val MENU_CHAVE = 1
        private const val MENU_MODELO = 2
        private const val MENU_FALAR = 3
        private const val MENU_CONTINUA = 4
        private const val MENU_NOVA = 5
        private const val MENU_DIAGNOSTICO = 6

        private val COR_TEXTO_APAGADO = Color.parseColor("#6B769A")
    }
}
