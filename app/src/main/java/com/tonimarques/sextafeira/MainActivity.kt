package com.tonimarques.sextafeira

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var mensagens: LinearLayout
    private lateinit var rolagem: ScrollView
    private lateinit var campo: EditText
    private lateinit var ferramentas: Ferramentas

    private var gemini: Gemini? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var ocupado = false
    private var ultimaFoiVoz = false
    private var naTela = false

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        ferramentas = Ferramentas(this)
        tts = TextToSpeech(this, this)
        setContentView(montarTela())
        adicionarMensagem("Sexta-feira online. Em que posso ajudar?", false)
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
    }

    override fun onPause() {
        naTela = false
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun tratarIntent(intent: Intent?) {
        val acao = intent?.action ?: return
        if (acao == ACAO_OUVIR || acao == Intent.ACTION_ASSIST) {
            campo.post { ouvir() }
        }
    }

    // --------------------------------------------------------------------- tela
    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COR_FUNDO)
        }

        rolagem = ScrollView(this).apply { isFillViewport = true }
        mensagens = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        rolagem.addView(mensagens)
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
            setBackgroundColor(COR_BARRA)
        }

        campo = EditText(this).apply {
            hint = "Fale com a Sexta-feira…"
            setHintTextColor(Color.parseColor("#7A86A8"))
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = bolha(COR_CAMPO)
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
        barra.addView(botao(android.R.drawable.ic_btn_speak_now, "Falar") { ouvir() })
        barra.addView(botao(android.R.drawable.ic_menu_send, "Enviar") { enviarTexto() })

        raiz.addView(
            barra,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return raiz
    }

    private fun botao(icone: Int, descricao: String, acao: () -> Unit) = ImageButton(this).apply {
        setImageResource(icone)
        setColorFilter(COR_DESTAQUE)
        contentDescription = descricao
        background = null
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { acao() }
    }

    private fun bolha(cor: Int) = GradientDrawable().apply {
        setColor(cor)
        cornerRadius = dp(20).toFloat()
    }

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    private fun adicionarMensagem(texto: String, doUsuario: Boolean): TextView {
        val balao = TextView(this).apply {
            text = texto
            setTextColor(Color.WHITE)
            textSize = 16f
            background = bolha(if (doUsuario) COR_USUARIO else COR_SEXTA)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(true)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (doUsuario) Gravity.END else Gravity.START
            topMargin = dp(6)
            if (doUsuario) leftMargin = dp(48) else rightMargin = dp(48)
        }
        mensagens.addView(balao, params)
        rolarParaBaixo()
        return balao
    }

    private fun rolarParaBaixo() {
        rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }
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
        val assistente = obterGemini()
        if (assistente == null) {
            pedirChave()
            return
        }
        ocupado = true
        adicionarMensagem(pedido, true)
        val pensando = adicionarMensagem("…", false)

        Thread {
            val resposta = try {
                assistente.enviar(pedido)
            } catch (e: Exception) {
                "Algo deu errado: ${e.message}"
            }
            runOnUiThread {
                pensando.text = resposta
                rolarParaBaixo()
                ocupado = false
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

    // --------------------------------------------------------------------- voz
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.setLanguage(Locale("pt", "BR"))
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    // Conversa contínua: depois de responder, volta a ouvir.
                    if (ultimaFoiVoz && naTela && !ocupado && prefs.getBoolean(PREF_CONTINUA, true)) ouvir()
                }
            }

            @Deprecated("Exigido pela classe base")
            override fun onError(utteranceId: String?) {}
        })
        ttsPronto = true
    }

    private fun falar(texto: String) {
        if (!ttsPronto || !prefs.getBoolean(PREF_FALAR, true)) return
        val limpo = texto.replace(Regex("[*#_`>]"), "")
        tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resposta")
    }

    private fun ouvir() {
        if (ocupado) return
        tts?.stop()
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

    // ------------------------------------------------------------------- menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CHAVE, 0, "Chave do Gemini")
        menu.add(0, MENU_MODELO, 1, "Modelo do Gemini")
        menu.add(0, MENU_FALAR, 2, "Responder em voz alta")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_FALAR, true))
        menu.add(0, MENU_CONTINUA, 3, "Conversa contínua por voz")
            .setCheckable(true).setChecked(prefs.getBoolean(PREF_CONTINUA, true))
        menu.add(0, MENU_NOVA, 4, "Nova conversa")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_CHAVE -> pedirChave()
            MENU_MODELO -> pedirModelo()
            MENU_FALAR -> {
                val ligado = !item.isChecked
                item.isChecked = ligado
                prefs.edit().putBoolean(PREF_FALAR, ligado).apply()
                if (!ligado) tts?.stop()
            }
            MENU_CONTINUA -> {
                val ligado = !item.isChecked
                item.isChecked = ligado
                prefs.edit().putBoolean(PREF_CONTINUA, ligado).apply()
            }
            MENU_NOVA -> {
                gemini = null
                mensagens.removeAllViews()
                adicionarMensagem("Conversa nova. Em que posso ajudar?", false)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun pedirChave() {
        val entrada = campoDeDialogo(chave(), "Cole aqui sua chave")
        AlertDialog.Builder(this)
            .setTitle("Chave do Gemini")
            .setMessage("Crie uma chave grátis em aistudio.google.com/apikey, copie e cole aqui.")
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
        private const val MODELO_PADRAO = "gemini-flash-latest"

        private const val PREF_CHAVE = "chave"
        private const val PREF_MODELO = "modelo"
        private const val PREF_FALAR = "falar"
        private const val PREF_CONTINUA = "continua"

        private const val MENU_CHAVE = 1
        private const val MENU_MODELO = 2
        private const val MENU_FALAR = 3
        private const val MENU_CONTINUA = 4
        private const val MENU_NOVA = 5

        private val COR_FUNDO = Color.parseColor("#0B1020")
        private val COR_BARRA = Color.parseColor("#111833")
        private val COR_CAMPO = Color.parseColor("#1B2447")
        private val COR_SEXTA = Color.parseColor("#1A2A4A")
        private val COR_USUARIO = Color.parseColor("#0E7490")
        private val COR_DESTAQUE = Color.parseColor("#36D6FF")
    }
}
