package com.tonimarques.sextafeira

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Trava de segurança: o Vênus só abre com Vetorial ID e licença válida (teste de 7 dias ou plano).
 * Tela única com três estados: entrar/criar conta (com recuperação de senha), "o seu teste acabou" e "confirmando a conta".
 */
class TravaActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var corpo: LinearLayout
    private var criar = false
    private var ocupado = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(8, 8, 15)); isFillViewport = true; addView(corpo) })
        decidir()
    }

    override fun onResume() { super.onResume(); if (::corpo.isInitialized) decidir() }

    private fun decidir() {
        if (!VetorialId.logado(this)) return mostrarLogin()
        when (val e = VetorialId.estado(this)) {
            is Selo.Estado.Livre -> abrirApp()
            // nunca mostra "o teste acabou" só pelo que ficou guardado no aparelho: confirma com o servidor antes
            is Selo.Estado.TesteAcabou -> { mostrarValidando("confirmando"); validar() }
            is Selo.Estado.ValidarOnline -> { mostrarValidando(e.motivo); validar() }
        }
    }

    private fun abrirApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------------ peças
    private fun titulo(t: String) = TextView(this).apply { text = t; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
    private fun texto(t: String, cor: Int = Color.rgb(170, 170, 190)) = TextView(this).apply { text = t; textSize = 14f; setTextColor(cor); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(12)) }
    private fun campo(dica: String, tipo: Int) = EditText(this).apply {
        hint = dica; inputType = tipo; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120, 120, 140))
        background = GradientDrawable().apply { setColor(Color.argb(40, 255, 255, 255)); cornerRadius = dp(14).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }.also { corpo.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
    private fun botao(rotulo: String, principal: Boolean, acao: () -> Unit) = Button(this).apply {
        text = rotulo; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = GradientDrawable().apply { setColor(if (principal) Color.rgb(236, 111, 214) else Color.argb(50, 255, 255, 255)); cornerRadius = dp(16).toFloat() }
        setOnClickListener { acao() }
    }.also { corpo.addView(it, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) }) }
    private fun link(rotulo: String, acao: () -> Unit) = TextView(this).apply {
        text = rotulo; textSize = 13f; setTextColor(Color.rgb(170, 170, 190)); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); setOnClickListener { acao() }
    }.also { corpo.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    private fun limparTela() { corpo.removeAllViews(); corpo.addView(titulo("Vênus").also { it.textSize = 30f }) }
    private fun aviso(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
    private fun emFundo(trabalho: () -> Nuvem.Resposta, pronto: (Nuvem.Resposta?) -> Unit) {
        if (ocupado) return
        ocupado = true
        Thread {
            val r = try { trabalho() } catch (e: Throwable) { null }
            h.post { ocupado = false; if (!isFinishing) pronto(r) }
        }.start()
    }

    // ------------------------------------------------------------------ entrar / criar conta
    private fun mostrarLogin(email0: String = "") {
        limparTela()
        corpo.addView(texto(VetorialId.pegarAviso(this).ifBlank { if (criar) "Crie o seu Vetorial ID. Conta nova ganha 7 dias de teste grátis." else "Entre com o seu Vetorial ID para usar a Vênus." }))
        val nome = if (criar) campo("Nome da loja ou o seu nome", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) else null
        val fone = if (criar) campo("Celular com DDD (para recuperar a senha)", InputType.TYPE_CLASS_PHONE) else null
        val email = campo("E-mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also { it.setText(email0) }
        val senha = campo("Senha (8 ou mais caracteres)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (criar) corpo.addView(texto(RecuperacaoDeConta.EXPLICACAO))
        val rec = if (criar) RecuperacaoDeConta.camposDeCadastro(this) { dica, tipo -> campo(dica, tipo) } else null
        botao(if (criar) "Criar conta" else "Entrar", true) {
            if (rec != null && !rec.preenchidos()) return@botao aviso("Preencha a recuperação: senha de 6 números e pergunta secreta, cada uma duas vezes.")
            enviarLogin(email.text.toString().trim(), senha.text.toString(), nome?.text?.toString().orEmpty().trim(), fone?.text?.toString().orEmpty().trim(), null, null, rec?.json())
        }
        link(if (criar) "Já tenho conta" else "Criar conta") { criar = !criar; mostrarLogin(email.text.toString().trim()) }
        if (!criar) link("Esqueci minha senha") { mostrarRecuperacao(email.text.toString().trim()) }
    }

    private fun enviarLogin(email: String, senha: String, nome: String, fone: String, encerrar: String?, remover: String?, recuperacao: JSONObject? = null) {
        aviso(if (criar && encerrar == null && remover == null) "Criando a conta…" else "Entrando…")
        emFundo({ if (criar && encerrar == null && remover == null) VetorialId.cadastrar(this, email, senha, nome, fone, recuperacao) else VetorialId.entrar(this, email, senha, encerrar, remover) }) { r ->
            if (r == null) return@emFundo aviso("Sem conexão com o servidor. Tente de novo.")
            val codigo = r.json.optString("codigo")
            when {
                r.ok -> decidir()
                codigo == "limite_simultaneo" -> escolher("Já há 2 aparelhos usando a conta agora. Encerre um para entrar aqui:",
                    r.json.optJSONArray("ativas")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { a -> "Encerrar ${a.optString("nome")}" to { criar = false; enviarLogin(email, senha, nome, fone, a.optString("sessao"), null) } })
                codigo == "limite_dispositivos" -> escolher("Você já tem 3 aparelhos vinculados. Remova um para vincular este:",
                    r.json.optJSONArray("dispositivos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { d -> "Remover ${d.optString("nome")}" to { criar = false; enviarLogin(email, senha, nome, fone, null, d.optString("id")) } })
                else -> aviso(r.erro())
            }
        }
    }

    private fun escolherFormaDePagar(chave: String, nome: String) = escolher("Como pagar o plano $nome?", listOf(
        "Cartão de crédito, todo mês (renova sozinho)" to { gerarLinkDePagamento(chave, "recorrente") },
        "Pix ou débito, 30 dias (paga só este mês; vencendo, paga mais 30 dias)" to { gerarLinkDePagamento(chave, "avulso") },
    ) + (if (chave == "venus") listOf<Pair<String, () -> Unit>>("Plano anual: R$ 499 no Pix ou débito, 365 dias (2 meses grátis, sem renovar sozinho)" to { gerarLinkDePagamento(chave, "anual") }) else emptyList()))

    private fun gerarLinkDePagamento(chave: String, forma: String) {
        emFundo({ VetorialId.licenca(this, true, chave, forma) }) { r ->
            val url = r?.json?.optJSONObject("cobranca")?.optString("url").orEmpty()
            if (r != null && r.ok && url.startsWith("https://")) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { aviso(url) }
            else aviso(r?.erro() ?: "Sem conexão com o servidor.")
        }
    }

    private fun escolher(titulo: String, itens: List<Pair<String, () -> Unit>>) {
        val nomes = (itens.map { it.first } + "Cancelar").toTypedArray()
        CaixaBuilder(this).setTitle(titulo).setItems(nomes) { _, i -> if (i < itens.size) itens[i].second() }.show()
    }

    // ------------------------------------------------------------------ recuperação de senha
    private fun mostrarRecuperacao(email0: String, enviado: Boolean = false, canal0: String = "email") {
        limparTela()
        corpo.addView(texto("Recuperar a senha"))
        val email = campo("E-mail da conta", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also { it.setText(email0) }
        if (!enviado) {
            botao("Usar a senha de 6 números ou a pergunta secreta", true) { RecuperacaoDeConta.iniciar(this, email.text.toString().trim()) { mail -> criar = false; mostrarLogin(mail) } }
            var canal = canal0
            botao("Receber o código por e-mail", false) { canal = "email"; pedirCodigo(email.text.toString().trim(), canal) }
            botao("Receber o código por SMS", false) { canal = "sms"; pedirCodigo(email.text.toString().trim(), canal) }
        } else {
            val codigo = campo("Código de 6 dígitos", InputType.TYPE_CLASS_NUMBER)
            val nova = campo("Nova senha (8 ou mais caracteres)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            botao("Trocar a senha", true) {
                emFundo({ VetorialId.recuperarConfirmar(this, email.text.toString().trim(), codigo.text.toString().trim(), nova.text.toString()) }) { r ->
                    if (r == null) aviso("Sem conexão com o servidor.")
                    else if (r.ok) { aviso("Senha trocada. Entre com a nova senha."); criar = false; mostrarLogin(email.text.toString().trim()) }
                    else aviso(r.erro())
                }
            }
        }
        link("Voltar") { mostrarLogin(email.text.toString().trim()) }
    }

    private fun pedirCodigo(email: String, canal: String) {
        emFundo({ VetorialId.recuperarPedir(this, email, canal) }) { r ->
            if (r == null) aviso("Sem conexão com o servidor.")
            else if (r.ok) { aviso(r.json.optString("mensagem")); mostrarRecuperacao(email, true, canal) }
            else aviso(r.erro())
        }
    }

    // ------------------------------------------------------------------ teste acabou
    private fun mostrarBloqueio() {
        limparTela()
        corpo.addView(titulo("O seu teste acabou"))
        corpo.addView(texto("Para continuar com os serviços, adicione uma forma de pagamento e escolha um plano. Os seus dados continuam guardados."))
        botao("Escolher plano e pagar", true) {
            // os planos vêm do servidor; depois a pessoa escolhe cartão de crédito (todo mês) ou Pix/débito (30 dias)
            emFundo({ VetorialId.licenca(this, false) }) { r ->
                val planos = r?.json?.optJSONArray("pacotes")
                if (planos == null || planos.length() == 0) escolherFormaDePagar("venus", "Vênus")
                else escolher("Escolha o plano", (0 until planos.length()).map { i ->
                    val p = planos.getJSONObject(i)
                    "${p.optString("nome")} · R$ " + String.format(java.util.Locale("pt", "BR"), "%.2f", p.optInt("precoCentavos") / 100.0) + "/mês" + (if (p.optBoolean("promocao")) " (promoção)" else "") to { escolherFormaDePagar(p.optString("chave"), p.optString("nome")) }
                })
            }
        }
        botao("Já paguei: verificar de novo", false) { validar(manual = true) }
        link("Sair desta conta") { emFundo({ VetorialId.sair(this); Nuvem.Resposta(200, JSONObject()) }) { mostrarLogin() } }
    }

    // ------------------------------------------------------------------ confirmando
    private fun mostrarValidando(motivo: String) {
        limparTela()
        corpo.addView(titulo("Confirmando a sua conta…"))
        corpo.addView(texto(when (motivo) {
            "relogio" -> "A data e a hora deste aparelho não batem com a do servidor. Ajuste o relógio e conecte-se à internet."
            "vencido" -> "Faz tempo que este aparelho não confirma a licença. Conecte-se à internet para continuar."
            else -> "Conecte-se à internet para confirmar o seu Vetorial ID."
        }))
        botao("Tentar agora", true) { validar(manual = true) }
        link("Sair desta conta") { emFundo({ VetorialId.sair(this); Nuvem.Resposta(200, JSONObject()) }) { mostrarLogin() } }
    }

    private fun validar(manual: Boolean = false) {
        if (ocupado) return
        ocupado = true
        Thread {
            val e = try { VetorialId.validar(this) } catch (x: Throwable) { null }
            h.post {
                ocupado = false
                if (isFinishing) return@post
                when (e) {
                    is Selo.Estado.Livre -> abrirApp()
                    is Selo.Estado.TesteAcabou -> mostrarBloqueio().also { if (manual) aviso("Ainda sem plano ativo.") }
                    else -> { if (!VetorialId.logado(this)) mostrarLogin() else if (manual) aviso("Não consegui confirmar agora. Verifique a internet.") }
                }
            }
        }.start()
    }
}
