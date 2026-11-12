package com.tonimarques.sextafeira

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/** O painel ao vivo da loja (faturamento, taxas, auditoria) aberto aqui dentro da Vênus, em tela cheia, e não no navegador. */
@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.abrirPainelDentro() {
    emFundo({ Nuvem.garantirConta(this) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess {
            val dialogo = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#070A1C")) }
            val barra = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            barra.addView(TextView(this).apply {
                text = "PAINEL AO VIVO · ${Perfil.loja}"
                setTextColor(Color.WHITE)
                textSize = 14f
                letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            barra.addView(TextView(this).apply {
                text = "Fechar"
                setTextColor(Color.parseColor("#36D6FF"))
                textSize = 15f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener { dialogo.dismiss() }
            })
            val web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.parseColor("#070A1C"))
                isVerticalScrollBarEnabled = true
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                webViewClient = object : WebViewClient() {}
                loadUrl(Nuvem.enderecoDoPainel(this@abrirPainelDentro))
            }
            raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            raiz.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // tela cheia por trás das barras do sistema: sem isto o fim da página fica escondido atrás da barra de navegação
            @Suppress("DEPRECATION")
            raiz.setOnApplyWindowInsetsListener { v, ins ->
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                ins
            }
            dialogo.setContentView(raiz)
            dialogo.setOnKeyListener { _, tecla, evento ->
                if (tecla == KeyEvent.KEYCODE_BACK && evento.action == KeyEvent.ACTION_UP) { dialogo.dismiss(); true } else false
            }
            dialogo.setOnDismissListener { web.stopLoading(); web.destroy() }
            dialogo.show()
        }
    }
}
