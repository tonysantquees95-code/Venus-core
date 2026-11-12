package com.tonimarques.sextafeira

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Aparência: modelo do orbe, pacote de ícones e cor (cores prontas ou seletor RGB completo).
 * Cada toque vale na hora, no app inteiro ([aoMudar] reaplica o tema na tela principal).
 */
class TelaAparencia(
    private val a: MainActivity,
    private val aoMudar: () -> Unit,
) : Dialog(a, R.style.Tema) {
    private val prefs = a.prefs
    private lateinit var conteudo: LinearLayout
    private var atualizandoBarras = false
    private lateinit var barras: List<SeekBar>
    private lateinit var campoHex: EditText
    private lateinit var amostra: View
    private lateinit var previaOrbe: Orbe
    private lateinit var previaIcones: LinearLayout

    init {
        setContentView(montar())
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun destaque(): Int = a.corAtual or 0xFF000000.toInt()

    private fun montar(): View {
        val raiz = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05060F"))
        }
        val topo = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(8), a.dp(28), a.dp(16), a.dp(8))
        }
        topo.addView(TextView(a).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(a.dp(14), 0, a.dp(14), a.dp(4))
            setOnClickListener { dismiss() }
        })
        topo.addView(TextView(a).apply {
            text = "APARÊNCIA"
            textSize = 15f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        raiz.addView(topo)
        raiz.addView(montarPrevia(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(a.dp(18), a.dp(4), a.dp(18), a.dp(4))
        })
        conteudo = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(18), a.dp(6), a.dp(18), a.dp(30))
        }
        raiz.addView(ScrollView(a).apply { addView(conteudo) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        desenhar()
        return raiz
    }

    /** Prévia fixa no alto: o orbe e três botões já com o modelo, os ícones e a cor escolhidos. */
    private fun montarPrevia(): View {
        val cartao = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = a.dp(20).toFloat(); setColor(Color.argb(16, 255, 255, 255)); setStroke(a.dp(1), Color.argb(30, 255, 255, 255))
            }
        }
        previaOrbe = Orbe(a)
        cartao.addView(previaOrbe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(150)))
        previaIcones = LinearLayout(a).apply { gravity = Gravity.CENTER }
        cartao.addView(previaIcones)
        atualizarPrevia()
        return cartao
    }

    private fun atualizarPrevia() {
        val rgb = Tema.cor(prefs)
        previaOrbe.aplicarTema(Tema.modelo(prefs), rgb)
        val cor = if (rgb != 0) 0xFF000000.toInt() or Tema.trio(rgb)[0] else a.corAtual
        previaIcones.removeAllViews()
        val pacote = Tema.icones(prefs)
        listOf(
            Triple(R.drawable.ic_gaveta, "Menu", true), Triple(R.drawable.ic_microfone, "Microfone", false),
            Triple(R.drawable.ic_enviar, "Enviar", false), Triple(R.drawable.ic_publicacoes, "Publicações", true),
        ).forEach { (res, nome, fundo) ->
            val b = android.widget.ImageButton(a).apply {
                setImageResource(res)
                contentDescription = nome
                setPadding(a.dp(11), a.dp(11), a.dp(11), a.dp(11))
                isClickable = false
            }
            EstiloDeIcone.aplicar(b, pacote, cor, fundo, false, a::dp)
            previaIcones.addView(b, LinearLayout.LayoutParams(a.dp(48), a.dp(48)).apply { marginStart = a.dp(6); marginEnd = a.dp(6) })
        }
    }

    private fun desenhar() {
        conteudo.removeAllViews()
        val destaqueAtual = destaque()
        secao("MOVIMENTO DO ORBE")
        grade(Tema.Modelo.values().size) { i ->
            val m = Tema.Modelo.values()[i]
            val mini = Orbe(a).apply { aplicarTema(m, Tema.cor(prefs)) }
            cartaoDeOpcao(mini, a.dp(78), m.rotulo, m.descricao, Tema.modelo(prefs) == m, destaqueAtual) {
                Tema.definirModelo(prefs, m); Diario.anotar(a, "Aparência", "Movimento do orbe: ${m.rotulo}.", "tela", true); aoMudar(); atualizarPrevia(); desenhar()
            }
        }
        secao("PACOTE DE ÍCONES")
        val corIcone = if (Tema.cor(prefs) != 0) 0xFF000000.toInt() or Tema.cor(prefs) else a.corAtual
        grade(Tema.Icones.values().size) { i ->
            val p = Tema.Icones.values()[i]
            val linha = LinearLayout(a).apply { gravity = Gravity.CENTER }
            listOf(R.drawable.ic_conversas, R.drawable.ic_robo, R.drawable.ic_seta).forEach { res ->
                val b = android.widget.ImageButton(a).apply { setImageResource(res); setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6)); isClickable = false }
                EstiloDeIcone.aplicar(b, p, corIcone, true, false, a::dp)
                linha.addView(b, LinearLayout.LayoutParams(a.dp(30), a.dp(30)).apply { marginStart = a.dp(2); marginEnd = a.dp(2) })
            }
            cartaoDeOpcao(linha, a.dp(40), p.rotulo, p.descricao, Tema.icones(prefs) == p, destaqueAtual) {
                Tema.definirIcones(prefs, p); Diario.anotar(a, "Aparência", "Ícones: ${p.rotulo}.", "tela", true); aoMudar(); atualizarPrevia(); desenhar()
            }
        }

        secao("PALETA DE CORES")
        val atual = Tema.cor(prefs)
        val pilulas = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rolagem = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(pilulas) }
        for (p in Tema.PREDEFINIDAS) {
            val escolhida = p.cor == atual
            val pilula = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(12), 0, a.dp(16), 0)
                background = GradientDrawable().apply {
                    cornerRadius = a.dp(22).toFloat()
                    setColor(Color.argb(if (escolhida) 34 else 14, 255, 255, 255))
                    setStroke(a.dp(if (escolhida) 2 else 1), if (escolhida) destaqueAtual else Color.argb(60, 255, 255, 255))
                }
                contentDescription = p.nome
                setOnClickListener { Tema.definirCor(prefs, p.cor); Diario.anotar(a, "Aparência", "Cor: ${p.nome}.", "tela", true); aoMudar(); atualizarPrevia(); desenhar() }
            }
            pilula.addView(View(a).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (p.cor == 0) setColors(intArrayOf(Color.parseColor("#E36BD0"), Color.parseColor("#36D6FF"), Color.parseColor("#F5B93A")))
                    else setColor(0xFF000000.toInt() or p.cor)
                }
            }, LinearLayout.LayoutParams(a.dp(22), a.dp(22)).apply { marginEnd = a.dp(8) })
            pilula.addView(TextView(a).apply { text = p.nome; textSize = 13.5f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            pilulas.addView(pilula, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(44)).apply { marginEnd = a.dp(8) })
        }
        conteudo.addView(rolagem)
        conteudo.addView(TextView(a).apply {
            text = "Automática: as cores mudam devagar, nos tons da Vênus."
            textSize = 11.5f
            setTextColor(MainActivity.COR_TEXTO_APAGADO)
            setPadding(0, a.dp(6), 0, 0)
        })

        // painel do seletor RGB (mesmo formato do app do computador)
        val base = if (atual != 0) atual else Tema.PREDEFINIDAS[2].cor
        val painel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14))
            background = GradientDrawable().apply { cornerRadius = a.dp(20).toFloat(); setColor(Color.argb(16, 255, 255, 255)); setStroke(a.dp(1), Color.argb(40, 255, 255, 255)) }
        }
        painel.addView(TextView(a).apply {
            text = "Destaque"
            textSize = 13f
            setTextColor(Color.parseColor("#06081A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(a.dp(16), a.dp(7), a.dp(16), a.dp(7))
            background = GradientDrawable().apply { cornerRadius = a.dp(20).toFloat(); setColor(destaqueAtual) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val letras = listOf("R", "G", "B")
        val tons = listOf("#FF5A6E", "#4ADE80", "#5AA9FF")
        val valores = listOf(Tema.vermelho(base), Tema.verde(base), Tema.azul(base))
        valoresRgb = mutableListOf()
        barras = letras.mapIndexed { i, letra ->
            val linha = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            linha.addView(TextView(a).apply { text = letra; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(tons[i])) }, LinearLayout.LayoutParams(a.dp(18), ViewGroup.LayoutParams.WRAP_CONTENT))
            val barra = SeekBar(a).apply {
                max = 255
                progress = valores[i]
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(tons[i]))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(tons[i]))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, doUsuario: Boolean) {
                        if (doUsuario && !atualizandoBarras) aplicarRgb(Tema.rgb(barras[0].progress, barras[1].progress, barras[2].progress))
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            linha.addView(barra, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val caixa = TextView(a).apply {
                text = valores[i].toString(); textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = a.dp(10).toFloat(); setColor(Color.argb(20, 255, 255, 255)); setStroke(a.dp(1), Color.argb(50, 255, 255, 255)) }
            }
            valoresRgb.add(caixa)
            linha.addView(caixa, LinearLayout.LayoutParams(a.dp(48), a.dp(34)).apply { marginStart = a.dp(8) })
            painel.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
            barra
        }
        val rodape = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        amostra = View(a).apply { background = GradientDrawable().apply { cornerRadius = a.dp(10).toFloat(); setColor(0xFF000000.toInt() or base) } }
        rodape.addView(amostra, LinearLayout.LayoutParams(a.dp(52), a.dp(40)))
        campoHex = EditText(a).apply {
            setText(Tema.hex(base))
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            hint = "#RRGGBB"
            setHintTextColor(MainActivity.COR_TEXTO_APAGADO)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!hasFocus()) return
                    Tema.lerHex(s.toString())?.let { rgb -> aplicarRgb(rgb, atualizarCampo = false) }
                }
            })
        }
        rodape.addView(campoHex, LinearLayout.LayoutParams(a.dp(110), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = a.dp(12) })
        textoRgb = TextView(a).apply { textSize = 12f; setTextColor(MainActivity.COR_TEXTO_APAGADO); text = "rgb(${valores[0]}, ${valores[1]}, ${valores[2]})" }
        rodape.addView(textoRgb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = a.dp(10) })
        painel.addView(rodape, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(14) })
        conteudo.addView(painel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(12) })
    }

    private lateinit var valoresRgb: MutableList<TextView>
    private lateinit var textoRgb: TextView

    /** Cartão de opção: visual no alto, nome e descrição; o escolhido ganha o contorno na cor de destaque. */
    private fun cartaoDeOpcao(visual: View, altura: Int, nome: String, descricao: String, marcada: Boolean, realce: Int, aoClicar: () -> Unit): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = a.dp(18).toFloat()
                setColor(if (marcada) Color.argb(40, Color.red(realce), Color.green(realce), Color.blue(realce)) else Color.argb(14, 255, 255, 255))
                setStroke(a.dp(if (marcada) 2 else 1), if (marcada) realce else Color.argb(50, 255, 255, 255))
            }
            isClickable = true
            contentDescription = "$nome. $descricao" + if (marcada) ". Escolhido" else ""
            setOnClickListener { aoClicar() }
            addView(visual, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, altura))
            addView(TextView(a).apply { text = nome; textSize = 14.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, a.dp(8), 0, 0) })
            addView(TextView(a).apply { text = descricao; textSize = 11f; gravity = Gravity.CENTER; setTextColor(MainActivity.COR_TEXTO_APAGADO); setPadding(0, a.dp(2), 0, 0) })
        }

    /** Grade de 3 colunas com `total` cartões. */
    private fun grade(total: Int, criar: (Int) -> View) {
        val g = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        (0 until total).chunked(3).forEach { bloco ->
            val linha = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val i = bloco.getOrNull(k)
                linha.addView(if (i != null) criar(i) else View(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = if (k < 2) a.dp(8) else 0 })
            }
            g.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(8) })
        }
        conteudo.addView(g)
    }

    private fun aplicarRgb(rgb: Int, atualizarCampo: Boolean = true) {
        val rgbFinal = if (rgb == 0) 0x010101 else rgb // 0 significa "automática"; preto puro vira quase preto
        Tema.definirCor(prefs, rgbFinal)
        (amostra.background as? GradientDrawable)?.setColor(0xFF000000.toInt() or rgbFinal)
        if (atualizarCampo) campoHex.setText(Tema.hex(rgbFinal))
        atualizandoBarras = true
        barras[0].progress = Tema.vermelho(rgbFinal); barras[1].progress = Tema.verde(rgbFinal); barras[2].progress = Tema.azul(rgbFinal)
        atualizandoBarras = false
        valoresRgb[0].text = Tema.vermelho(rgbFinal).toString(); valoresRgb[1].text = Tema.verde(rgbFinal).toString(); valoresRgb[2].text = Tema.azul(rgbFinal).toString()
        textoRgb.text = "rgb(${Tema.vermelho(rgbFinal)}, ${Tema.verde(rgbFinal)}, ${Tema.azul(rgbFinal)})"
        aoMudar()
        atualizarPrevia()
    }

    private fun secao(titulo: String) {
        conteudo.addView(TextView(a).apply {
            text = titulo
            textSize = 10.5f
            letterSpacing = 0.25f
            setTextColor(MainActivity.COR_TEXTO_APAGADO)
            setPadding(0, a.dp(20), 0, a.dp(8))
        })
    }
}
