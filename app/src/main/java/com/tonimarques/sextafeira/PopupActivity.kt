package com.tonimarques.sextafeira

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * O pop-up da Vênus: só o orbe na parte de baixo da tela, ouvindo e respondendo,
 * sem abrir o app inteiro. Usa toda a inteligência da tela principal (é uma versão dela)
 * e se fecha sozinho quando a conversa termina.
 */
class PopupActivity : MainActivity() {

    private val fechar = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (estaOcioso()) finish() else principal.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Com "funcionar com a tela apagada": acende a tela e aparece por cima da tela de bloqueio.
        // O que precisa do celular desbloqueado (abrir apps, mandar mensagem) pede a senha/digital antes.
        if (getSharedPreferences("config", MODE_PRIVATE).getBoolean(VigiliaService.PREF_TELA_APAGADA, false)) {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onNewIntent(intent: Intent) {
        principal.removeCallbacks(fechar)
        super.onNewIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Saiu da tela (por exemplo, abriu o WhatsApp): termina de falar e fecha.
        if (!isFinishing && !isChangingConfigurations) {
            principal.removeCallbacks(fechar)
            principal.postDelayed({ if (!isFinishing) finish() }, 8000)
        }
    }

    override fun onDestroy() {
        principal.removeCallbacks(fechar)
        super.onDestroy()
    }

    // O pop-up não fica esperando a ativação: quem faz isso é o serviço de fundo.
    override fun vigiliaLigada() = false

    override fun carregarConversaAnterior() {}

    override fun mensagemInicial(): String? = null

    override fun aoFicarOcioso() {
        principal.removeCallbacks(fechar)
        principal.postDelayed(fechar, 6000)
    }

    override fun adicionarMensagem(texto: String, doUsuario: Boolean, salvar: Boolean): TextView {
        principal.removeCallbacks(fechar)
        rolagem.visibility = View.VISIBLE
        return super.adicionarMensagem(texto, doUsuario, salvar)
    }

    override fun montarTela(): View {
        val raiz = FrameLayout(this).apply {
            // Tocar fora do cartão fecha o pop-up.
            setOnClickListener { finish() }
        }

        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            background = vidro(Color.argb(238, 7, 9, 22), Color.argb(46, 255, 255, 255), 30)
            setPadding(dp(14), dp(6), dp(14), dp(10))
        }

        // Elementos da tela principal que o pop-up não mostra, mas a lógica usa.
        titulo = TextView(this)
        campo = EditText(this)
        botaoEnviar = botaoIcone(R.drawable.ic_enviar, "Enviar", Color.WHITE) { }

        orbe = Orbe(this).apply {
            setOnClickListener { alternarEscuta() }
            aoMudarCor = { cor -> aplicarCor(cor) }
        }
        aplicarTema()
        cartao.addView(orbe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))

        status = TextView(this).apply {
            textSize = 10.5f
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8A93B5"))
        }
        cartao.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        parcial = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(225, 255, 255, 255))
            typeface = Typeface.create("sans-serif-light", Typeface.ITALIC)
            setPadding(dp(8), dp(6), dp(8), 0)
            visibility = View.GONE
        }
        cartao.addView(parcial, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rolagem = ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        mensagens = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        rolagem.addView(mensagens)
        cartao.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        // Microfone e atalho para abrir o app completo
        val botoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        botaoMic = botaoIcone(R.drawable.ic_microfone, "Falar", Color.WHITE) { alternarEscuta() }
        botoes.addView(botaoMic)
        botoes.addView(
            botaoIcone(R.drawable.ic_menu_futuro, "Abrir a ${Marca.nome}", Color.parseColor("#8A93B5")) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        )
        cartao.addView(botoes)

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { setMargins(dp(10), 0, dp(10), dp(18)) }
        raiz.addView(cartao, params)
        return raiz
    }
}
