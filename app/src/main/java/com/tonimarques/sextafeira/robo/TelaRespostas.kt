package com.tonimarques.sextafeira.robo

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * As respostas prontas do Instagram: a Vênus da loja escreve, você confere, ajusta se quiser e copia
 * para as "Perguntas frequentes" e as "Respostas salvas" do próprio Instagram.
 */
class TelaRespostas : TelaBase() {

    private val campos = HashMap<String, EditText>()
    private val avisos = HashMap<String, TextView>()
    private var situacao: TextView? = null
    private var botaoGerar: TextView? = null
    @Volatile private var gerando = false
    private var carregando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        montar()
        // primeira vez: já começa a escrever
        if (Respostas.lista(this).all { it.texto.isBlank() }) gerarTodas()
    }

    private fun montar() {
        novaTela()
        campos.clear()
        avisos.clear()
        coluna.addView(TextView(this).apply {
            text = "RESPOSTAS DO INSTAGRAM"
            textSize = 18f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(destaque)
        })
        coluna.addView(texto("A Vênus da loja escreve com os produtos e as regras do site. Você confere, ajusta se quiser e cola no Instagram uma vez só. Depois o Instagram responde sozinho.", 13f, apagado))

        // ---- escrever
        cartao("Escrever com a Vênus da loja")
        situacao = texto(resumo(), 13.5f, Color.WHITE)
        adicionar(situacao!!)
        botaoGerar = botao("Gerar todas de novo", true) { gerarTodas() }
        adicionar(botaoGerar!!)
        adicionar(texto("As que você editou ficam como estão.", 12f, apagado))

        // ---- como colar
        cartao("Como colar no Instagram")
        adicionar(texto(
            "1. Perguntas frequentes: no Instagram, abra seu perfil → ☰ → Ferramentas para empresas (ou Ferramentas e controles) → Perguntas frequentes. " +
                "Adicione as 4 perguntas abaixo e cole a resposta automática de cada uma. Elas aparecem para a cliente quando ela abre o Direct.\n\n" +
                "2. Respostas salvas: na mesma área, toque em Respostas salvas → +. Cole o atalho e a mensagem. No Direct, é só digitar o atalho e tocar na resposta.\n\n" +
                "Os nomes podem mudar um pouco conforme a versão do Instagram.",
            13.5f, Color.WHITE,
        ))
        adicionar(botao("Abrir o Instagram", false) {
            val abrir = packageManager.getLaunchIntentForPackage(Robo.INSTAGRAM)
            if (abrir != null) startActivity(abrir) else Toast.makeText(this, "Não achei o Instagram neste celular.", Toast.LENGTH_SHORT).show()
        })
        adicionar(botao("Copiar tudo num texto só", false) {
            val tudo = Respostas.tudoEmTexto(Respostas.lista(this))
            if (tudo.isBlank()) Toast.makeText(this, "Ainda não há respostas.", Toast.LENGTH_SHORT).show() else copiar("Respostas do Instagram", tudo)
        })

        // ---- as respostas
        val prontas = Respostas.lista(this)
        cartao("Perguntas frequentes (até 4)")
        prontas.filter { it.modelo.faq }.forEach { item(it, mostrarPergunta = true) }
        cartao("Respostas salvas")
        adicionar(texto("As 4 de cima também servem como resposta salva, com o atalho indicado.", 12f, apagado))
        prontas.filter { !it.modelo.faq }.forEach { item(it, mostrarPergunta = false) }
    }

    private fun item(p: Respostas.Pronta, mostrarPergunta: Boolean) {
        val m = p.modelo
        adicionar(texto(if (mostrarPergunta) "Pergunta: ${m.pergunta}" else "Quando perguntam: “${m.pergunta}”", 13f, apagado).apply {
            setPadding(dp(2), dp(14), dp(2), 0)
        })
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (mostrarPergunta) linha.addView(pilula("Copiar pergunta") { copiar("Pergunta", m.pergunta) })
        linha.addView(pilula("Atalho: ${m.atalho}") { copiar("Atalho", m.atalho) })
        adicionar(linha)

        val campo = EditText(this).apply {
            setText(p.texto)
            hint = "A Vênus ainda não escreveu esta."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            textSize = 14.5f
            setTextColor(Color.WHITE)
            setHintTextColor(apagado)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#120E22"))
                setStroke(dp(1), Color.parseColor("#2C2546"))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (carregando) return
                    Respostas.guardar(this@TelaRespostas, m.id, s?.toString().orEmpty().trim(), editada = true)
                    avisos[m.id]?.text = "Editada por você."
                    situacao?.text = resumo()
                }
            })
        }
        campos[m.id] = campo
        adicionar(campo)
        val aviso = texto(aviso(p), 12f, Color.parseColor("#FFB86B"))
        avisos[m.id] = aviso
        adicionar(aviso)

        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        botoes.addView(pilula("Copiar resposta") {
            val t = campo.text.toString().trim()
            if (t.isBlank()) Toast.makeText(this, "Esta ainda está vazia.", Toast.LENGTH_SHORT).show() else copiar("Resposta", t)
        })
        botoes.addView(pilula("Gerar de novo") { gerar(listOf(m), forcar = true) })
        adicionar(botoes)
    }

    private fun aviso(p: Respostas.Pronta) = when {
        p.editada -> "Editada por você."
        p.conferir -> "A Vênus da loja passaria esta conversa para vocês. Leia e ajuste antes de colar."
        else -> ""
    }

    private fun resumo(): String {
        val l = Respostas.lista(this)
        val feitas = l.count { it.texto.isNotBlank() }
        val conferir = l.count { it.conferir && it.texto.isNotBlank() }
        return when {
            feitas == 0 -> "Nenhuma resposta escrita ainda."
            else -> "$feitas de ${l.size} respostas prontas" +
                (if (conferir > 0) ", $conferir para conferir" else "") +
                (Respostas.quando(this).takeIf { it > 0 }?.let { " · ${Robo.hora(it)}" } ?: "") + "."
        }
    }

    private fun gerarTodas() {
        val pular = Respostas.lista(this).filter { it.editada }.map { it.modelo.id }.toSet()
        gerar(Respostas.MODELOS.filter { it.id !in pular }, forcar = false)
    }

    /** Pergunta uma a uma à Vênus da loja e vai preenchendo os campos. */
    private fun gerar(modelos: List<Respostas.Modelo>, forcar: Boolean) {
        if (gerando) return Toast.makeText(this, "Já estou escrevendo, um momento…", Toast.LENGTH_SHORT).show()
        if (modelos.isEmpty()) return Toast.makeText(this, "Todas foram editadas por você; use \"Gerar de novo\" em cada uma.", Toast.LENGTH_LONG).show()
        gerando = true
        botaoGerar?.alpha = 0.5f
        val app = applicationContext
        Thread {
            var erro: String? = null
            var semTexto = 0
            for ((i, m) in modelos.withIndex()) {
                runOnUiThread { situacao?.text = "Escrevendo ${i + 1} de ${modelos.size}: ${m.pergunta}" }
                val g = try {
                    Respostas.gerar(app, m)
                } catch (e: Exception) {
                    erro = e.message ?: "não consegui falar com o site."
                    break
                }
                if (g == null) {
                    semTexto++
                    continue
                }
                Respostas.guardar(app, m.id, g.texto, editada = false, conferir = g.conferir)
                runOnUiThread {
                    campos[m.id]?.let { c ->
                        carregando = true
                        c.setText(g.texto)
                        carregando = false
                    }
                    avisos[m.id]?.text = aviso(Respostas.Pronta(m, g.texto, false, g.conferir))
                }
            }
            runOnUiThread {
                gerando = false
                botaoGerar?.alpha = 1f
                situacao?.text = resumo()
                when {
                    erro != null -> AlertDialog.Builder(this).setTitle("Não deu certo").setMessage(erro).setPositiveButton("OK", null).show()
                    semTexto > 0 -> Toast.makeText(this, "$semTexto ficaram sem texto; tente \"Gerar de novo\" nelas.", Toast.LENGTH_LONG).show()
                    forcar -> Toast.makeText(this, "Pronto.", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, "Pronto! Confira e copie para o Instagram.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copiar(rotulo: String, t: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(rotulo, t))
        // o Android 13 em diante já mostra o aviso de copiado
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copiado.", Toast.LENGTH_SHORT).show()
    }

    private fun pilula(t: String, aoTocar: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(destaque)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.parseColor("#5A3D70"))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        setOnClickListener { aoTocar() }
    }
}
