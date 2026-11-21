package com.tonimarques.sextafeira

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Páginas do servidor que o lojista usa dentro da Vênus, em tela cheia: a agenda de serviços e o editor do site com logo.
 * O token vai no #fragmento (nunca sai do aparelho) e a loja aberta, se for uma das extras, vai junto em `l=`.
 * Escolher foto (galeria/câmera) e baixar o logo funcionam aqui dentro; links de fora (WhatsApp etc.) abrem no app certo.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.abrirPaginaDoServidor(titulo: String, caminho: String, semConta: Boolean = false, aoAbrir: () -> Unit = {}) {
    // páginas públicas (feedback) abrem sem login; as da loja precisam da conta
    emFundo({ if (!semConta) Nuvem.garantirConta(this) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess {
            aoAbrir()
            val dialogo = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#070A1C")) }
            val barra = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            barra.addView(TextView(this).apply {
                text = if (semConta) titulo.uppercase() else "${titulo.uppercase()} · ${Perfil.loja}"
                setTextColor(Color.WHITE)
                textSize = 14f
                letterSpacing = 0.08f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            barra.addView(TextView(this).apply {
                text = "Fechar"
                setTextColor(Color.parseColor("#36D6FF"))
                textSize = 15f
                setPadding(dp(12), dp(10), dp(4), dp(10))
                setOnClickListener { dialogo.dismiss() }
            })
            val loja = Lojas.aberta(this)
            val endereco = if (semConta) "${Servidor.ENDERECO}$caminho" else "${Servidor.ENDERECO}$caminho#t=${Nuvem.token(this)}" + if (loja.isNotBlank()) "&l=$loja" else ""
            val web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(Color.parseColor("#070A1C"))
                isVerticalScrollBarEnabled = true
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addJavascriptInterface(PonteDaPagina(this@abrirPaginaDoServidor), "VenusAndroid")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.toString().startsWith(Servidor.ENDERECO)) return false
                        try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (e: Exception) { aviso("Não achei um app para abrir esse link.") }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, retorno: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        arquivoDaPagina?.onReceiveValue(null)
                        arquivoDaPagina = retorno
                        return try {
                            startActivityForResult(params.createIntent(), MainActivity.PEDIDO_ARQUIVO_DA_PAGINA)
                            true
                        } catch (e: Exception) {
                            arquivoDaPagina = null
                            aviso("Não consegui abrir a galeria.")
                            false
                        }
                    }
                }
                loadUrl(endereco)
            }
            raiz.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            raiz.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // tela cheia por trás das barras do sistema: o app fica ENTRE a barra de notificações e a de navegação
            @Suppress("DEPRECATION")
            raiz.setOnApplyWindowInsetsListener { v, ins ->
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                ins
            }
            dialogo.setContentView(raiz)
            dialogo.setOnKeyListener { _, tecla, evento ->
                if (tecla == KeyEvent.KEYCODE_BACK && evento.action == KeyEvent.ACTION_UP) { dialogo.dismiss(); true } else false
            }
            dialogo.setOnDismissListener {
                arquivoDaPagina?.onReceiveValue(null); arquivoDaPagina = null
                web.stopLoading(); web.destroy()
            }
            dialogo.show()
        }
    }
}

/** Feedback, relato de erro e pesquisa rápida (página pública do servidor; vai o app, a versão e a plataforma). */
internal fun MainActivity.abrirFeedback() {
    val versao = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
    abrirPaginaDoServidor("Feedback", "/feedback?app=venus&v=" + Uri.encode(versao) + "&p=android", semConta = true)
}

/** O que a página pode pedir ao app: guardar um arquivo (o logo em PNG/SVG) na pasta Download › Vênus. */
internal class PonteDaPagina(private val atividade: MainActivity) {
    @JavascriptInterface
    fun baixar(nome: String, mime: String, base64: String) {
        val limpo = nome.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "arquivo" }
        val tipo = if (mime == "image/png" || mime == "image/svg+xml" || mime == "image/jpeg") mime else "application/octet-stream"
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { atividade.runOnUiThread { atividade.aviso("Não consegui guardar o arquivo.") }; return }
        if (bytes.size > 8_000_000) return
        val onde = try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, limpo)
                    put(MediaStore.Downloads.MIME_TYPE, tipo)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vênus")
                }
                val uri = atividade.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri == null) null else { atividade.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; "Download › Vênus" }
            } else {
                val pasta = java.io.File(atividade.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Vênus").apply { mkdirs() }
                java.io.File(pasta, limpo).writeBytes(bytes)
                "a pasta Documentos do app Vênus"
            }
        } catch (e: Exception) { null }
        atividade.runOnUiThread { atividade.aviso(if (onde != null) "Guardado em $onde: $limpo" else "Não consegui guardar o arquivo.") }
    }
}
