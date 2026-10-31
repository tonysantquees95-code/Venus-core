package com.tonimarques.sextafeira

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Histórico das conversas, com a identidade da Vênus: tópicos em cartões de vidro,
 * busca em todas as conversas e cada conversa mostrada em balões, como no chat.
 */
class TelaDeHistorico(
    private val activity: Activity,
    private val memoria: Memoria,
    private var cor: Int,
) : Dialog(activity, R.style.Tema) {

    private val br = Locale("pt", "BR")
    private lateinit var titulo: TextView
    private lateinit var subtitulo: TextView
    private lateinit var lista: LinearLayout
    private lateinit var rolagem: ScrollView
    private lateinit var busca: EditText
    private lateinit var botaoApagar: ImageButton
    private var topicoAberto: String? = null

    init {
        setContentView(montar())
        window?.setWindowAnimations(android.R.style.Animation_Activity)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        mostrarTopicos()
    }

    override fun onBackPressed() {
        if (topicoAberto != null || busca.text.isNotBlank()) {
            busca.setText("")
            mostrarTopicos()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun vidro(preenchimento: Int, borda: Int, raio: Int) = GradientDrawable().apply {
        setColor(preenchimento)
        setStroke(dp(1).coerceAtLeast(1), borda)
        cornerRadius = dp(raio).toFloat()
    }

    private fun comAlfa(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun montar(): View {
        val raiz = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0A0F24"), Color.parseColor("#04050C"), Color.parseColor("#000000"))
            )
            setPadding(dp(12), dp(10), dp(12), 0)
        }

        val topo = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topo.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_voltar)
            setColorFilter(COR_APAGADA)
            background = null
            contentDescription = "Voltar"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onBackPressed() }
        })
        val nomes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titulo = TextView(context).apply {
            textSize = 15f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cor)
            maxLines = 2
        }
        subtitulo = TextView(context).apply {
            textSize = 9.5f
            letterSpacing = 0.25f
            setTextColor(COR_APAGADA)
        }
        nomes.addView(titulo)
        nomes.addView(subtitulo)
        topo.addView(nomes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        botaoApagar = ImageButton(context).apply {
            setImageResource(R.drawable.ic_lixeira)
            setColorFilter(COR_APAGADA)
            background = null
            contentDescription = "Apagar"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { confirmarApagar() }
        }
        topo.addView(botaoApagar)
        raiz.addView(topo)

        busca = EditText(context).apply {
            hint = "Buscar nas conversas…"
            setHintTextColor(COR_APAGADA)
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = vidro(Color.argb(16, 255, 255, 255), Color.argb(34, 255, 255, 255), 22)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val termo = s?.toString().orEmpty().trim()
                    if (termo.length >= 2) mostrarBusca(termo) else if (topicoAberto == null) mostrarTopicos()
                }
            })
        }
        raiz.addView(busca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(8), dp(6), dp(6))
        })

        rolagem = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
        }
        lista = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(32))
        }
        rolagem.addView(lista)
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return raiz
    }

    // ------------------------------------------------------------- tópicos
    private fun mostrarTopicos() {
        topicoAberto = null
        busca.visibility = View.VISIBLE
        lista.removeAllViews()
        val topicos = try {
            memoria.resumoTopicos()
        } catch (e: Exception) {
            emptyList()
        }
        titulo.text = "HISTÓRICO"
        val total = topicos.sumOf { it.quantidade }
        subtitulo.text = if (topicos.isEmpty()) "NENHUMA CONVERSA AINDA" else "$total MENSAGENS · ${topicos.size} ASSUNTOS"
        botaoApagar.visibility = if (topicos.isEmpty()) View.GONE else View.VISIBLE
        if (topicos.isEmpty()) {
            lista.addView(aviso("As conversas com a ${Marca.nome} aparecem aqui, separadas por assunto."))
            return
        }
        for (t in topicos) lista.addView(cartaoTopico(t))
        rolagem.post { rolagem.scrollTo(0, 0) }
    }

    private fun cartaoTopico(t: Memoria.ResumoTopico): View {
        val cartao = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = vidro(Color.argb(16, 255, 255, 255), Color.argb(30, 255, 255, 255), 20)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            foreground = toque()
            isClickable = true
            setOnClickListener { abrirTopico(t.topico) }
        }
        // bolinha com a inicial do assunto
        cartao.addView(TextView(context).apply {
            text = t.topico.take(1).uppercase(br)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(cor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(comAlfa(cor, 34))
                setStroke(dp(1), comAlfa(cor, 120))
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(14) })

        val textos = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(context).apply {
            text = t.topico
            textSize = 15.5f
            setTextColor(Color.WHITE)
        })
        if (t.previa.isNotBlank()) {
            textos.addView(TextView(context).apply {
                text = "“${t.previa}”"
                textSize = 12.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.argb(200, 200, 206, 230))
                setPadding(0, dp(2), 0, 0)
            })
        }
        textos.addView(TextView(context).apply {
            text = "${t.quantidade} mensagens · ${quando(t.ultimo)}"
            textSize = 11f
            setTextColor(COR_APAGADA)
            setPadding(0, dp(4), 0, 0)
        })
        cartao.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cartao.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_seta)
            setColorFilter(Color.argb(140, 138, 147, 184))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))

        return FrameLayout(context).apply {
            setPadding(0, dp(5), 0, dp(5))
            addView(cartao)
        }
    }

    // ------------------------------------------------------------- uma conversa
    private fun abrirTopico(topico: String) {
        topicoAberto = topico
        busca.visibility = View.GONE
        lista.removeAllViews()
        val mensagens = try {
            memoria.mensagens(topico, 300)
        } catch (e: Exception) {
            emptyList()
        }
        titulo.text = topico.uppercase(br)
        subtitulo.text = "${mensagens.size} MENSAGENS"
        botaoApagar.visibility = View.VISIBLE
        var diaAnterior = ""
        for (m in mensagens) {
            val dia = dia(m.quando)
            if (dia != diaAnterior) {
                lista.addView(separador(dia))
                diaAnterior = dia
            }
            lista.addView(balao(m))
        }
        rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }
    }

    private fun mostrarBusca(termo: String) {
        topicoAberto = null
        lista.removeAllViews()
        val achadas = try {
            memoria.buscarMensagens(termo)
        } catch (e: Exception) {
            emptyList()
        }
        titulo.text = "BUSCA"
        subtitulo.text = if (achadas.isEmpty()) "NADA ENCONTRADO" else "${achadas.size} RESULTADOS"
        botaoApagar.visibility = View.GONE
        if (achadas.isEmpty()) {
            lista.addView(aviso("Nenhuma mensagem com “$termo”."))
            return
        }
        for (m in achadas) {
            lista.addView(separador("${m.topico} · ${dia(m.quando)}"))
            lista.addView(balao(m))
        }
        rolagem.post { rolagem.scrollTo(0, 0) }
    }

    private fun balao(m: Memoria.Mensagem): View {
        val ehFonte = m.texto.startsWith("Fontes: ")
        val vista = TextView(context).apply {
            text = m.texto
            textSize = if (ehFonte) 11.5f else 14.5f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setTextColor(
                when {
                    ehFonte -> COR_APAGADA
                    m.doUsuario -> Color.WHITE
                    else -> Color.parseColor("#DCE3F5")
                }
            )
            background = when {
                ehFonte -> null
                m.doUsuario -> vidro(comAlfa(cor, 40), comAlfa(cor, 110), 18)
                else -> vidro(Color.argb(14, 255, 255, 255), Color.argb(34, 255, 255, 255), 18)
            }
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val hora = TextView(context).apply {
            text = SimpleDateFormat("HH:mm", br).format(Date(m.quando))
            textSize = 10f
            setTextColor(Color.argb(120, 138, 147, 184))
            setPadding(dp(6), dp(2), dp(6), 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.doUsuario) Gravity.END else Gravity.START
            setPadding(if (m.doUsuario) dp(48) else 0, dp(5), if (m.doUsuario) 0 else dp(36), 0)
            addView(vista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hora)
        }
    }

    private fun separador(texto: String) = TextView(context).apply {
        text = texto.uppercase(br)
        textSize = 10f
        letterSpacing = 0.25f
        gravity = Gravity.CENTER
        setTextColor(comAlfa(cor, 200))
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun aviso(texto: String) = TextView(context).apply {
        text = texto
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(COR_APAGADA)
        setPadding(dp(24), dp(48), dp(24), 0)
    }

    private fun confirmarApagar() {
        val topico = topicoAberto
        AlertDialog.Builder(activity)
            .setTitle(if (topico != null) "Apagar “$topico”?" else "Apagar todo o histórico?")
            .setMessage(
                if (topico != null) "As mensagens deste assunto serão apagadas. As memórias continuam."
                else "Todas as conversas salvas serão apagadas. As memórias e o que a ${Marca.nome} aprendeu continuam."
            )
            .setPositiveButton("Apagar") { _, _ ->
                if (topico != null) memoria.apagarTopico(topico) else memoria.apagarHistorico()
                mostrarTopicos()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------- datas
    private fun dia(quando: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = quando }
        val hoje = Calendar.getInstance()
        val ontem = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun mesmo(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            mesmo(c, hoje) -> "Hoje"
            mesmo(c, ontem) -> "Ontem"
            c.get(Calendar.YEAR) == hoje.get(Calendar.YEAR) -> SimpleDateFormat("EEEE, d 'de' MMMM", br).format(Date(quando))
            else -> SimpleDateFormat("d 'de' MMMM 'de' yyyy", br).format(Date(quando))
        }
    }

    private fun quando(ms: Long): String {
        val d = dia(ms)
        val hora = SimpleDateFormat("HH:mm", br).format(Date(ms))
        return if (d == "Hoje" || d == "Ontem") "${d.lowercase(br)} às $hora" else d
    }

    private fun toque() = TypedValue().let { v ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, v, true)
        if (v.resourceId != 0) context.getDrawable(v.resourceId) else null
    }

    companion object {
        private val COR_APAGADA = Color.parseColor("#8A93B8")
    }
}
