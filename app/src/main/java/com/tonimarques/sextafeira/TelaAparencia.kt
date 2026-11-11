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
        secao("MODELO DO ORBE")
        fichas(Tema.Modelo.values().map { it.rotulo }, Tema.modelo(prefs).ordinal) {
            Tema.definirModelo(prefs, Tema.Modelo.values()[it]); Diario.anotar(a, "Aparência", "Orbe: ${Tema.Modelo.values()[it].rotulo}.", "tela", true); aoMudar(); atualizarPrevia(); desenhar()
        }
        secao("ÍCONES")
        fichas(Tema.Icones.values().map { it.rotulo }, Tema.icones(prefs).ordinal) {
            Tema.definirIcones(prefs, Tema.Icones.values()[it]); Diario.anotar(a, "Aparência", "Ícones: ${Tema.Icones.values()[it].rotulo}.", "tela", true); aoMudar(); atualizarPrevia(); desenhar()
        }
        secao("COR")
        val atual = Tema.cor(prefs)
        val grade = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rolagem = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(grade) }
        for (p in Tema.PREDEFINIDAS) {
            val escolhida = p.cor == atual
            val bolha = TextView(a).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
                text = if (p.cor == 0) "A" else ""
                contentDescription = p.nome
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (p.cor == 0) setColors(intArrayOf(Color.parseColor("#E36BD0"), Color.parseColor("#36D6FF"), Color.parseColor("#F5B93A")))
                    else setColor(0xFF000000.toInt() or p.cor)
                    setStroke(a.dp(if (escolhida) 3 else 1), if (escolhida) Color.WHITE else Color.argb(80, 255, 255, 255))
                }
                setOnClickListener { Tema.definirCor(prefs, p.cor); Diario.anotar(a, "Aparência", "Cor: ${p.nome}.", "tela", true); aoMudar(); atualizarPrevia(); desenhar() }
            }
            grade.addView(bolha, LinearLayout.LayoutParams(a.dp(46), a.dp(46)).apply { marginEnd = a.dp(10) })
        }
        conteudo.addView(rolagem)
        conteudo.addView(TextView(a).apply {
            text = "Automática: as cores mudam devagar, nos tons da Vênus."
            textSize = 11.5f
            setTextColor(MainActivity.COR_TEXTO_APAGADO)
            setPadding(0, a.dp(6), 0, 0)
        })

        secao("COR PERSONALIZADA")
        val base = if (atual != 0) atual else Tema.PREDEFINIDAS[1].cor
        amostra = View(a).apply {
            background = GradientDrawable().apply { cornerRadius = a.dp(14).toFloat(); setColor(0xFF000000.toInt() or base) }
        }
        conteudo.addView(amostra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(44)))
        val nomes = listOf("Vermelho", "Verde", "Azul")
        val valores = listOf(Tema.vermelho(base), Tema.verde(base), Tema.azul(base))
        barras = nomes.mapIndexed { i, nome ->
            conteudo.addView(TextView(a).apply {
                text = nome
                textSize = 12f
                setTextColor(MainActivity.COR_TEXTO_APAGADO)
                setPadding(0, a.dp(10), 0, 0)
            })
            SeekBar(a).apply {
                max = 255
                progress = valores[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, doUsuario: Boolean) {
                        if (doUsuario && !atualizandoBarras) aplicarRgb(Tema.rgb(barras[0].progress, barras[1].progress, barras[2].progress))
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                conteudo.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
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
        conteudo.addView(campoHex, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
    }

    private fun aplicarRgb(rgb: Int, atualizarCampo: Boolean = true) {
        val rgbFinal = if (rgb == 0) 0x010101 else rgb // 0 significa "automática"; preto puro vira quase preto
        Tema.definirCor(prefs, rgbFinal)
        (amostra.background as? GradientDrawable)?.setColor(0xFF000000.toInt() or rgbFinal)
        if (atualizarCampo) campoHex.setText(Tema.hex(rgbFinal))
        atualizandoBarras = true
        barras[0].progress = Tema.vermelho(rgbFinal); barras[1].progress = Tema.verde(rgbFinal); barras[2].progress = Tema.azul(rgbFinal)
        atualizandoBarras = false
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

    private fun fichas(rotulos: List<String>, escolhida: Int, aoEscolher: (Int) -> Unit) {
        val grade = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        rotulos.chunked(3).forEachIndexed { bloco, parte ->
        val linha = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        parte.forEachIndexed { k, r ->
            val i = bloco * 3 + k
            val marcada = i == escolhida
            linha.addView(TextView(a).apply {
                text = r
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (marcada) Color.parseColor("#06081A") else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = a.dp(22).toFloat()
                    if (marcada) setColor(destaque()) else { setColor(Color.argb(14, 255, 255, 255)); setStroke(a.dp(1), Color.argb(60, 255, 255, 255)) }
                }
                setOnClickListener { aoEscolher(i) }
            }, LinearLayout.LayoutParams(0, a.dp(44), 1f).apply { marginEnd = if (k < 2) a.dp(8) else 0 })
        }
        repeat(3 - parte.size) { linha.addView(View(a), LinearLayout.LayoutParams(0, a.dp(44), 1f).apply { marginEnd = a.dp(8) }) }
        grade.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = a.dp(8) })
        }
        conteudo.addView(grade)
    }
}
