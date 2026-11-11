package com.tonimarques.sextafeira

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menu lateral em gaveta (SidebarDrawer): botão "Nova conversa" em destaque, acesso ao Robô e à aparência,
 * e o histórico das conversas salvas. Cobre a tela por cima; toque fora ou "voltar" fecha.
 */
class Gaveta(private val a: MainActivity) : FrameLayout(a) {
    private val larguraPainel = minOf((a.resources.displayMetrics.widthPixels * 0.84f).toInt(), a.dp(340))
    private val fundo = View(a).apply {
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        alpha = 0f
        setOnClickListener { fechar() }
    }
    private val painel = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0E1330"), Color.parseColor("#05060F"))
        )
        setPadding(a.dp(16), a.dp(34), a.dp(16), a.dp(16))
        isClickable = true
    }
    private val lista = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    var aberta = false
        private set

    init {
        visibility = View.GONE
        addView(fundo, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(painel, LayoutParams(larguraPainel, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        painel.translationX = -larguraPainel.toFloat()
        montar()
    }

    private fun cor() = a.corAtual

    private fun montar() {
        painel.removeAllViews()
        painel.addView(TextView(a).apply {
            text = Marca.nomeMaiusculo
            textSize = 15f
            letterSpacing = 0.35f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cor())
        })
        painel.addView(TextView(a).apply {
            text = "Conversas e atalhos"
            textSize = 11f
            setTextColor(MainActivity.COR_TEXTO_APAGADO)
            setPadding(0, a.dp(2), 0, a.dp(14))
        })
        // botão em destaque
        painel.addView(TextView(a).apply {
            text = "＋  Nova conversa"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (Tema.escuraDemais(cor() and 0xFFFFFF)) Color.WHITE else Color.parseColor("#06081A"))
            background = GradientDrawable().apply { setColor(cor()); cornerRadius = a.dp(26).toFloat() }
            minHeight = a.dp(52)
            isClickable = true
            setOnClickListener { fechar(); a.novaConversaDaGaveta() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(52)))

        painel.addView(linha(R.drawable.ic_robo, "Vênus Robô", "Respostas automáticas aos clientes") { fechar(); a.abrirRobo() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(14) })
        painel.addView(linha(R.drawable.ic_publicacoes, "Central da loja", "Canais, marketing, QR Code, painel e plano") { fechar(); a.abrirCentral() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
        painel.addView(linha(R.drawable.ic_publicacoes, "Aparência", "Cor, orbe e ícones") { fechar(); a.abrirAparencia() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })

        painel.addView(TextView(a).apply {
            text = "CONVERSAS SALVAS"
            textSize = 10.5f
            letterSpacing = 0.25f
            setTextColor(MainActivity.COR_TEXTO_APAGADO)
            setPadding(a.dp(4), a.dp(18), 0, a.dp(6))
        })
        (lista.parent as? ViewGroup)?.removeView(lista)
        val rolagem = ScrollView(a).apply { addView(lista) }
        painel.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        painel.addView(TextView(a).apply {
            text = "Buscar em todo o histórico"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(cor())
            setPadding(0, a.dp(12), 0, a.dp(4))
            setOnClickListener { fechar(); a.mostrarHistorico() }
        })
    }

    private fun linha(icone: Int, titulo: String, sub: String, acao: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        background = a.vidro(Color.argb(14, 255, 255, 255), Color.argb(40, 255, 255, 255), 16)
        isClickable = true
        setOnClickListener { acao() }
        addView(ImageView(a).apply { setImageResource(icone) }, LinearLayout.LayoutParams(a.dp(28), a.dp(28)))
        val textos = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(12), 0, 0, 0)
        }
        textos.addView(TextView(a).apply { text = titulo; textSize = 15f; setTextColor(Color.WHITE) })
        textos.addView(TextView(a).apply { text = sub; textSize = 11f; setTextColor(MainActivity.COR_TEXTO_APAGADO) })
        addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun preencherLista() {
        lista.removeAllViews()
        val conversas = try { a.memoria.conversas(40) } catch (e: Exception) { emptyList() }
        val atual = try { a.memoria.conversaRecente() } catch (e: Exception) { null }
        if (conversas.isEmpty()) {
            lista.addView(TextView(a).apply {
                text = "Nenhuma conversa salva ainda. Elas aparecem aqui assim que você conversar."
                textSize = 12.5f
                setTextColor(MainActivity.COR_TEXTO_APAGADO)
                setPadding(a.dp(4), a.dp(4), a.dp(4), 0)
            })
            return
        }
        val formato = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
        for (c in conversas) {
            val emAndamento = c.id == atual
            val nome = c.titulo.ifBlank { c.previa.ifBlank { "Conversa sem título" } }
            lista.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(12), a.dp(9), a.dp(12), a.dp(9))
                background = if (emAndamento) a.vidro(a.comAlfa(cor(), 36), a.comAlfa(cor(), 120), 14)
                else a.vidro(Color.argb(8, 255, 255, 255), Color.argb(22, 255, 255, 255), 14)
                isClickable = true
                setOnClickListener { fechar(); a.abrirConversa(c.id) }
                addView(TextView(a).apply {
                    text = nome
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                })
                addView(TextView(a).apply {
                    text = formato.format(Date(c.ultimo)) + " · ${c.quantidade} mensagens" + if (emAndamento) " · agora" else ""
                    textSize = 10.5f
                    setTextColor(MainActivity.COR_TEXTO_APAGADO)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(6) })
        }
    }

    fun abrir() {
        if (aberta) return
        aberta = true
        a.vigiarVoltarDaGaveta(true)
        montar()
        preencherLista()
        visibility = View.VISIBLE
        bringToFront()
        fundo.animate().alpha(1f).setDuration(180).start()
        painel.animate().translationX(0f).setDuration(220).start()
    }

    fun fechar() {
        if (!aberta) return
        aberta = false
        a.vigiarVoltarDaGaveta(false)
        fundo.animate().alpha(0f).setDuration(160).start()
        painel.animate().translationX(-larguraPainel.toFloat()).setDuration(200).withEndAction {
            if (!aberta) visibility = View.GONE
        }.start()
    }
}
