package com.tonimarques.sextafeira

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Telas de formulário com a cara da Vênus: tela cheia escura com brilho rosa, cartões de vidro,
 * campos arredondados, botões em pílula e a ação principal sempre visível embaixo.
 * Usado no cadastro de produto e na programação de posts.
 */
class Formulario(private val activity: Activity, titulo: String, subtitulo: String) {

    private val dialogo = Dialog(activity, R.style.Tema)
    private val coluna = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(4), dp(18), dp(24))
    }
    private val rodape = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(18))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0, 4, 3, 12), FUNDO_2))
    }
    private var cartaoAtual: LinearLayout? = null
    private lateinit var rolagem: ScrollView
    private lateinit var vTitulo: TextView
    private lateinit var vSubtitulo: TextView

    /** Se definido, a seta do topo e o botão Voltar do celular chamam isto em vez de fechar (ex.: passo anterior). */
    var aoTocarVoltar: (() -> Unit)? = null

    init {
        val raiz = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(FUNDO_1, FUNDO_2, Color.BLACK))
        }
        // brilho rosa no alto, como o orbe
        raiz.addView(View(activity).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(260).toFloat()
                colors = intArrayOf(Aparencia.comAlfa(70), Color.TRANSPARENT)
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        val tudo = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val topo = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(18), dp(6))
        }
        topo.addView(android.widget.ImageButton(activity).apply {
            setImageResource(R.drawable.ic_voltar)
            setColorFilter(COR_APAGADA)
            background = null
            contentDescription = "Fechar"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { aoTocarVoltar?.invoke() ?: dialogo.dismiss() }
        })
        val nomes = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        vTitulo = TextView(activity).apply {
            text = titulo.uppercase()
            textSize = 15f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(DESTAQUE)
        }
        nomes.addView(vTitulo)
        vSubtitulo = TextView(activity).apply {
            text = subtitulo
            textSize = 12.5f
            setTextColor(COR_APAGADA)
            setPadding(0, dp(2), 0, 0)
        }
        nomes.addView(vSubtitulo)
        topo.addView(nomes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tudo.addView(topo)

        rolagem = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(coluna)
        }
        tudo.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tudo.addView(rodape)
        raiz.addView(tudo)

        dialogo.setContentView(raiz)
        dialogo.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dialogo.setOnKeyListener { _, codigo, evento ->
            val voltar = aoTocarVoltar
            if (codigo == android.view.KeyEvent.KEYCODE_BACK && voltar != null) {
                if (evento.action == android.view.KeyEvent.ACTION_UP) voltar()
                true
            } else false
        }
    }

    /** Apaga o conteúdo (para montar outra etapa na mesma tela) e volta a rolagem para o topo. */
    fun limpar() {
        coluna.removeAllViews()
        cartaoAtual = null
        rodape.removeAllViews()
        rolagem.scrollTo(0, 0)
    }

    /** Os próximos itens voltam a entrar direto na coluna (fora do último cartão). */
    fun fecharCartao() {
        cartaoAtual = null
    }

    fun trocarTitulo(titulo: String, subtitulo: String) {
        vTitulo.text = titulo.uppercase()
        vSubtitulo.text = subtitulo
    }

    /** Barrinha de progresso em segmentos (etapa [atual] de [total], começando em 1). */
    fun progresso(atual: Int, total: Int) {
        val barra = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(2))
        }
        for (i in 1..total) {
            barra.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(if (i <= atual) DESTAQUE else BORDA)
                }
            }, LinearLayout.LayoutParams(0, dp(5), 1f).apply { if (i > 1) marginStart = dp(5) })
        }
        coluna.addView(barra)
    }

    /** Texto solto na coluna (fora de cartão), para explicar a etapa. */
    fun paragrafo(t: CharSequence, tamanho: Float = 14.5f, cor: Int = COR_ROTULO): TextView = TextView(activity).apply {
        text = t
        textSize = tamanho
        setTextColor(cor)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(6), dp(14), dp(6), 0)
        (cartaoAtual ?: coluna).addView(this)
    }

    /** Cartão grande que se escolhe com um toque (ex.: Tenho site / Não tenho site). */
    fun opcaoGrande(titulo: String, texto: String, marcada: Boolean, aoTocar: () -> Unit): View {
        val c = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (marcada) Aparencia.comAlfa(60) else CARTAO)
                setStroke(dp(if (marcada) 2 else 1), if (marcada) DESTAQUE else BORDA)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { aoTocar() }
        }
        c.addView(TextView(activity).apply {
            text = (if (marcada) "●  " else "○  ") + titulo
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        c.addView(TextView(activity).apply {
            text = texto
            textSize = 13.5f
            setTextColor(COR_APAGADA)
            setPadding(0, dp(6), 0, 0)
        })
        coluna.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        return c
    }

    fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- estrutura
    /** Abre um cartão (bloco de vidro) com título; os próximos itens entram nele. */
    fun cartao(titulo: String): LinearLayout {
        coluna.addView(TextView(activity).apply {
            text = titulo.uppercase()
            textSize = 11f
            letterSpacing = 0.25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(DESTAQUE)
            setPadding(dp(6), dp(18), 0, dp(8))
        })
        val c = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(CARTAO)
                setStroke(dp(1), BORDA)
            }
        }
        coluna.addView(c)
        cartaoAtual = c
        return c
    }

    fun adicionar(v: View, margemTopo: Int = 8) {
        (cartaoAtual ?: coluna).addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(margemTopo)
        })
    }

    /** Dois itens lado a lado (ex.: preço e custo). */
    fun lado(a: View, b: View) {
        val linha = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        linha.addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        linha.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        adicionar(linha)
    }

    // ------------------------------------------------------------- componentes
    /** Rótulo pequeno + campo arredondado. Devolve (bloco para adicionar, campo para ler). */
    fun campo(rotulo: String, valor: String = "", dica: String = "", tipo: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, linhas: Int = 1): Pair<View, EditText> {
        val bloco = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        bloco.addView(rotulo(rotulo))
        val e = EditText(activity).apply {
            setText(valor)
            hint = dica
            inputType = tipo
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setHintTextColor(COR_DICA)
            minLines = linhas
            if (linhas > 1) gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = fundoDeCampo()
        }
        bloco.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        return bloco to e
    }

    fun rotulo(t: String) = TextView(activity).apply {
        text = t
        textSize = 12.5f
        setTextColor(COR_ROTULO)
        setPadding(dp(4), 0, 0, 0)
    }

    fun aviso(t: String = "") = TextView(activity).apply {
        text = t
        textSize = 12.5f
        setTextColor(COR_APAGADA)
        setPadding(dp(4), dp(2), dp(4), 0)
    }

    /** Botão em pílula: primário (rosa cheio) ou secundário (contorno). */
    fun botao(t: String, primario: Boolean = false, aoTocar: () -> Unit): TextView = TextView(activity).apply {
        text = t
        textSize = 14.5f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        setPadding(dp(16), dp(13), dp(16), dp(13))
        setTextColor(if (primario) (if (Tema.escuraDemais(Aparencia.destaque and 0xFFFFFF)) Color.WHITE else Color.parseColor("#06081A")) else DESTAQUE)
        background = if (primario) pilulaCheia() else pilulaContorno()
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isEnabled) aoTocar() }
    }

    /** Escolha entre opções (ex.: Post no feed / Story). Devolve a linha e a função que diz qual está marcada. */
    fun escolha(opcoes: List<String>, marcada: Int, aoMudar: (Int) -> Unit = {}): Pair<View, () -> Int> {
        var atual = marcada
        val linha = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(CAMPO)
                setStroke(dp(1), BORDA)
            }
        }
        val botoes = opcoes.mapIndexed { i, t ->
            TextView(activity).apply {
                text = t
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
        }
        fun pintar() = botoes.forEachIndexed { i, b ->
            b.setTextColor(if (i == atual) Color.WHITE else COR_APAGADA)
            b.background = if (i == atual) pilulaCheia() else null
        }
        botoes.forEachIndexed { i, b ->
            b.setOnClickListener {
                atual = i
                pintar()
                aoMudar(i)
            }
            linha.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        pintar()
        return linha to { atual }
    }

    /** Interruptor com texto, nas cores da Vênus. */
    fun interruptor(t: String, ligado: Boolean): android.widget.Switch = android.widget.Switch(activity).apply {
        text = t
        textSize = 15f
        isChecked = ligado
        setTextColor(Color.WHITE)
        setPadding(dp(4), dp(8), 0, dp(4))
        val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(estados, intArrayOf(DESTAQUE, Color.parseColor("#9A93B5")))
        trackTintList = ColorStateList(estados, intArrayOf(Aparencia.comAlfa(120), Color.parseColor("#2A2440")))
    }

    /** Foto em destaque, com cantos arredondados (fica escondida até ter foto). */
    fun previa(): ImageView = ImageView(activity).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        maxHeight = dp(240)
        visibility = View.GONE
        clipToOutline = true
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(CAMPO)
        }
    }

    /** Seletor (lista) com o mesmo visual dos campos. */
    fun seletor(opcoes: List<String>, marcada: Int): android.widget.Spinner = android.widget.Spinner(activity, android.widget.Spinner.MODE_DROPDOWN).apply {
        adapter = object : android.widget.ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, opcoes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15.5f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setBackgroundColor(Color.parseColor("#17122A"))
                }
        }
        setSelection(marcada.coerceIn(0, (opcoes.size - 1).coerceAtLeast(0)))
        background = fundoDeCampo()
        setPopupBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#17122A"))
            setStroke(dp(1), BORDA)
        })
    }

    // ------------------------------------------------------------- ações
    /** Botões de baixo: secundário (opcional) e o principal, sempre visíveis. */
    fun acoes(principal: TextView, secundario: TextView? = null) {
        rodape.removeAllViews()
        if (secundario != null) rodape.addView(secundario, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        rodape.addView(principal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (secundario != null) 1.4f else 1f))
    }

    fun aoFechar(f: () -> Unit) = dialogo.setOnDismissListener { f() }
    fun mostrar() = dialogo.show()
    fun fechar() = dialogo.dismiss()

    // ------------------------------------------------------------- fundos
    private fun fundoDeCampo() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(CAMPO)
            setStroke(dp(2), DESTAQUE)
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(CAMPO)
            setStroke(dp(1), BORDA)
        })
    }

    private fun pilulaCheia() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Aparencia.destaque, Aparencia.par())).apply {
        cornerRadius = dp(26).toFloat()
    }

    private fun pilulaContorno() = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat()
        setColor(Aparencia.comAlfa(26))
        setStroke(dp(1), Aparencia.comAlfa(150))
    }

    companion object {
        val DESTAQUE: Int get() = Aparencia.destaque
        val FUNDO_1 = Color.parseColor("#0D0A1F")
        val FUNDO_2 = Color.parseColor("#05040D")
        val CARTAO = Color.argb(150, 24, 19, 44)
        val CAMPO = Color.parseColor("#120E22")
        val BORDA = Color.parseColor("#2C2546")
        val COR_ROTULO = Color.parseColor("#C9C2E0")
        val COR_APAGADA = Color.parseColor("#8E87A8")
        val COR_DICA = Color.parseColor("#5E5878")
    }
}
