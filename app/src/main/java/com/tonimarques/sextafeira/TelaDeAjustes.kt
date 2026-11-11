package com.tonimarques.sextafeira

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela de ajustes com a identidade da Vênus: fundo escuro, cartões de vidro, orbe pequeno no topo,
 * cor de destaque que acompanha o orbe e textos inteiros (nada cortado).
 */
class TelaDeAjustes(
    private val activity: Activity,
    private val versao: String,
    private val secoes: List<Secao>,
    private val titulo: String = "AJUSTES",
) : Dialog(activity, R.style.Tema) {

    /** [ligado] != null mostra um interruptor; senão, uma setinha. [descricao] é recalculada ao atualizar. */
    class Ajuste(
        val titulo: String,
        val descricao: () -> String = { "" },
        val ligado: (() -> Boolean)? = null,
        val acao: () -> Unit,
    )

    class Secao(val titulo: String, val itens: List<Ajuste>)

    private var cor = Color.parseColor("#E36BD0")
    private val cabecalhos = mutableListOf<TextView>()
    private val atualizadores = mutableListOf<() -> Unit>()
    private val interruptores = mutableListOf<Interruptor>()
    private lateinit var tituloTela: TextView
    /** Uma linha da lista, com o que a busca precisa para escondê-la. */
    private class Entrada(val cabecalho: TextView, val cartao: LinearLayout, val linhas: List<Pair<View?, View>>, val textos: List<() -> String>)
    private val entradas = mutableListOf<Entrada>()
    private lateinit var orbe: Orbe

    init {
        setContentView(montar())
        window?.setWindowAnimations(android.R.style.Animation_Activity)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        atualizar()
    }

    /** Relê os estados (interruptores e descrições). */
    fun atualizar() {
        for (a in atualizadores) a()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * context.resources.displayMetrics.density

    private fun montar(): View {
        val raiz = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0A0F24"), Color.parseColor("#04050C"), Color.parseColor("#000000"))
            )
        }
        val rolagem = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val coluna = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(36))
        }

        // Cabeçalho: voltar + título
        val topo = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val voltar = ImageButton(context).apply {
            setImageResource(R.drawable.ic_voltar)
            setColorFilter(COR_APAGADA)
            background = null
            contentDescription = "Voltar"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dismiss() }
        }
        topo.addView(voltar)
        val nomes = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        tituloTela = TextView(context).apply {
            text = titulo
            textSize = 15f
            letterSpacing = 0.35f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cor)
        }
        nomes.addView(tituloTela)
        nomes.addView(TextView(context).apply {
            text = "${Marca.nomeMaiusculo} · VERSÃO $versao"
            textSize = 9.5f
            letterSpacing = 0.3f
            setTextColor(COR_APAGADA)
        })
        topo.addView(nomes)
        coluna.addView(topo)

        // Orbe pequeno: a cor dele comanda a cor de destaque da tela
        orbe = Orbe(context).apply {
            aoMudarCor = { c -> recolorir(c) }
            isClickable = false
        }
        coluna.addView(orbe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))

        // Busca rápida: digite "escuta contínua", "tema"… e só ficam os ajustes que combinam
        val busca = android.widget.EditText(context).apply {
            hint = "Buscar ajuste (ex.: escuta contínua, tema, notificações)"
            setHintTextColor(COR_APAGADA)
            setTextColor(Color.WHITE)
            textSize = 14.5f
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(22, 255, 255, 255)); setStroke(dp(1).coerceAtLeast(1), Color.argb(40, 255, 255, 255)); cornerRadius = dpf(18f)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = filtrar(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        coluna.addView(busca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        for (secao in secoes) {
            val cab = TextView(context).apply {
                text = secao.titulo.uppercase()
                textSize = 10.5f
                letterSpacing = 0.3f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(cor)
                setPadding(dp(8), dp(20), dp(8), dp(8))
            }
            cabecalhos += cab
            coluna.addView(cab)

            val cartao = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.argb(16, 255, 255, 255))
                    setStroke(dp(1).coerceAtLeast(1), Color.argb(30, 255, 255, 255))
                    cornerRadius = dpf(20f)
                }
                clipToOutline = true
            }
            val linhasDaSecao = mutableListOf<Pair<View?, View>>()
            secao.itens.forEachIndexed { i, ajuste ->
                var separador: View? = null
                if (i > 0) {
                    separador = View(context).apply { setBackgroundColor(Color.argb(20, 255, 255, 255)) }
                    cartao.addView(separador,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                            leftMargin = dp(18)
                            rightMargin = dp(18)
                        })
                }
                val l = linha(ajuste)
                cartao.addView(l)
                linhasDaSecao += separador to l
            }
            coluna.addView(cartao)
            entradas += Entrada(cab, cartao, linhasDaSecao, secao.itens.map { { it.titulo + " " + it.descricao() + " " + secao.titulo } })
        }

        coluna.addView(TextView(context).apply {
            text = "${Marca.nome} · feita para o seu dia a dia e para a sua loja"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(110, 138, 147, 184))
            setPadding(0, dp(28), 0, 0)
        })

        rolagem.addView(coluna)
        raiz.addView(rolagem, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return raiz
    }

    /** Esconde o que não combina com [termo] (sem acento e sem maiúsculas; todas as palavras precisam aparecer). */
    private fun filtrar(termo: String) {
        val palavras = ComandosLocais.normalizar(termo).split(" ").filter { it.isNotBlank() }
        for (e in entradas) {
            var primeiro = true
            var algum = false
            e.linhas.forEachIndexed { i, (sep, linha) ->
                val texto = ComandosLocais.normalizar(e.textos[i]())
                val ok = palavras.all { texto.contains(it) }
                linha.visibility = if (ok) View.VISIBLE else View.GONE
                sep?.visibility = if (ok && !primeiro) View.VISIBLE else View.GONE
                if (ok) { primeiro = false; algum = true }
            }
            e.cabecalho.visibility = if (algum) View.VISIBLE else View.GONE
            e.cartao.visibility = if (algum) View.VISIBLE else View.GONE
        }
    }

    private fun linha(ajuste: Ajuste): View {
        val linha = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(14))
            minimumHeight = dp(60)
            val toque = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, toque, true)
            if (toque.resourceId != 0) foreground = context.getDrawable(toque.resourceId)
            isClickable = true
        }
        val textos = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(TextView(context).apply {
            text = ajuste.titulo
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(0f, 1.1f)
        })
        val descricao = TextView(context).apply {
            textSize = 12.5f
            setTextColor(COR_APAGADA)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, 0)
        }
        textos.addView(descricao)
        linha.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })

        val ligado = ajuste.ligado
        if (ligado != null) {
            val chave = Interruptor(context, cor)
            interruptores += chave
            linha.addView(chave, LinearLayout.LayoutParams(dp(46), dp(28)))
            atualizadores += { chave.definir(ligado()) }
        } else {
            linha.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_seta)
                setColorFilter(Color.argb(140, 138, 147, 184))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
        }
        atualizadores += {
            val d = ajuste.descricao()
            descricao.text = d
            descricao.visibility = if (d.isBlank()) View.GONE else View.VISIBLE
        }
        linha.setOnClickListener {
            ajuste.acao()
            // Algumas ações pedem permissão ou abrem janelas: relê o estado logo depois.
            linha.postDelayed({ atualizar() }, 250)
        }
        return linha
    }

    private fun recolorir(c: Int) {
        cor = c
        tituloTela.setTextColor(c)
        for (t in cabecalhos) t.setTextColor(c)
        for (i in interruptores) i.cor = c
    }

    /** Interruptor desenhado à mão, com brilho na cor da Vênus. */
    private class Interruptor(contexto: Context, corInicial: Int) : View(contexto) {
        var cor = corInicial
            set(v) {
                field = v
                invalidate()
            }
        private var posicao = 0f
        private var ligado: Boolean? = null
        private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
        private val caixa = RectF()

        fun definir(valor: Boolean) {
            if (ligado == valor) return
            val primeira = ligado == null
            ligado = valor
            val alvo = if (valor) 1f else 0f
            if (primeira) {
                posicao = alvo
                invalidate()
                return
            }
            ValueAnimator.ofFloat(posicao, alvo).apply {
                duration = 180
                addUpdateListener {
                    posicao = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val h = height.toFloat()
            val w = width.toFloat()
            val trilhoAltura = 22 * d
            val topo = (h - trilhoAltura) / 2
            caixa.set(2 * d, topo, w - 2 * d, topo + trilhoAltura)
            // trilho: cinza quando desligado, cor da Vênus quando ligado
            val desligado = Color.argb(46, 255, 255, 255)
            pincel.style = Paint.Style.FILL
            pincel.color = misturar(desligado, Color.argb(150, Color.red(cor), Color.green(cor), Color.blue(cor)), posicao)
            canvas.drawRoundRect(caixa, trilhoAltura / 2, trilhoAltura / 2, pincel)
            // bolinha
            val raio = trilhoAltura / 2 - 3 * d
            val x = caixa.left + trilhoAltura / 2 + (caixa.width() - trilhoAltura) * posicao
            val y = h / 2
            if (posicao > 0.01f) {
                pincel.color = Color.argb((90 * posicao).toInt(), Color.red(cor), Color.green(cor), Color.blue(cor))
                canvas.drawCircle(x, y, raio + 4 * d, pincel)
            }
            pincel.color = misturar(Color.parseColor("#B8BED6"), Color.WHITE, posicao)
            canvas.drawCircle(x, y, raio, pincel)
        }

        private fun misturar(a: Int, b: Int, t: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }

    companion object {
        private val COR_APAGADA = Color.parseColor("#8A93B8")
    }
}
