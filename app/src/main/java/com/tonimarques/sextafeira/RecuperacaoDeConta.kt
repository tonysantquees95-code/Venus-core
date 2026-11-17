package com.tonimarques.sextafeira

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Recuperar a senha do Vetorial ID sem e-mail: senha de 6 números ou pergunta secreta (criadas no cadastro). */
object RecuperacaoDeConta {
    /** Campos da recuperação no cadastro, para as duas telas de criar conta (Trava e Ajustes). */
    class Campos(val pin: EditText, val pin2: EditText, val perg: EditText, val resp: EditText, val resp2: EditText) {
        fun todos() = listOf(pin, pin2, perg, resp, resp2)
        fun preenchidos() = todos().none { it.text.isBlank() }
        fun json(): JSONObject = JSONObject().put("pin", pin.text.toString()).put("pinConfirma", pin2.text.toString())
            .put("pergunta", perg.text.toString()).put("resposta", resp.text.toString()).put("respostaConfirma", resp2.text.toString())
    }

    const val EXPLICACAO = "Recuperação da conta: se esquecer a senha, você troca por uma destas duas. Guarde bem."

    fun camposDeCadastro(ctx: Activity, novoCampo: (String, Int) -> EditText): Campos {
        val numero = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val seis = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        return Campos(
            novoCampo("Senha de recuperação (6 números)", numero).also { it.filters = seis },
            novoCampo("Repita a senha de recuperação", numero).also { it.filters = seis },
            novoCampo("Pergunta secreta (escreva a sua)", InputType.TYPE_CLASS_TEXT),
            novoCampo("Resposta da pergunta", InputType.TYPE_CLASS_TEXT),
            novoCampo("Repita a resposta", InputType.TYPE_CLASS_TEXT),
        )
    }

    /** Esqueci a senha: e-mail → escolhe o jeito → senha nova. [aoTrocar] recebe o e-mail quando a senha foi trocada. */
    fun iniciar(a: Activity, emailInicial: String, aoTrocar: (String) -> Unit) {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * a.resources.displayMetrics.density).toInt(), 8, (20 * a.resources.displayMetrics.density).toInt(), 0) }
        val email = EditText(a).apply { hint = "E-mail da conta"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine(); setText(emailInicial); col.addView(this) }
        CaixaBuilder(a).setTitle("Recuperar a senha").setMessage("Digite o e-mail da sua conta.").setView(col)
            .setPositiveButton("Continuar") { _, _ -> perguntar(a, email.text.toString().trim(), aoTrocar) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun perguntar(a: Activity, email: String, aoTrocar: (String) -> Unit) {
        Thread {
            val r = try { VetorialId.recuperarPergunta(a, email) } catch (e: Throwable) { null }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                when {
                    r == null -> aviso(a, "Sem conexão com o servidor. Tente de novo.")
                    !r.ok -> aviso(a, r.erro())
                    !r.json.optBoolean("existe") -> aviso(a, "Não encontramos uma conta com esse e-mail.")
                    !r.json.optBoolean("configurada") -> aviso(a, "Essa conta foi criada antes da recuperação por senha de 6 números. Entre com a senha e cadastre a recuperação no site do Vetorial ID.")
                    else -> escolherJeito(a, email, r.json.optString("pergunta"), aoTrocar)
                }
            }
        }.start()
    }

    private fun escolherJeito(a: Activity, email: String, pergunta: String, aoTrocar: (String) -> Unit) {
        val nomes = arrayOf("Usar a senha de recuperação (6 números)", "Responder a pergunta secreta", "Cancelar")
        CaixaBuilder(a).setTitle("Como provar que é você?").setItems(nomes) { _, i ->
            if (i == 0) senhaNova(a, email, "pin", pergunta, aoTrocar) else if (i == 1) senhaNova(a, email, "pergunta", pergunta, aoTrocar)
        }.show()
    }

    private fun senhaNova(a: Activity, email: String, metodo: String, pergunta: String, aoTrocar: (String) -> Unit) {
        val pin = metodo == "pin"
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * a.resources.displayMetrics.density).toInt(), 8, (20 * a.resources.displayMetrics.density).toInt(), 0) }
        fun campo(dica: String, tipo: Int) = EditText(a).also { it.hint = dica; it.inputType = tipo; it.setSingleLine(); col.addView(it) }
        val valor = campo(if (pin) "Senha de recuperação (6 números)" else "Sua resposta", if (pin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT)
        if (pin) valor.filters = arrayOf(InputFilter.LengthFilter(6))
        val nova = campo("Senha nova (8 ou mais caracteres)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val nova2 = campo("Repita a senha nova", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        CaixaBuilder(a).setTitle(if (pin) "Senha de recuperação" else "Pergunta secreta")
            .setMessage(if (pin) "Digite a senha de 6 números que você criou no cadastro." else pergunta)
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Trocar a senha") { _, _ ->
                val n = nova.text.toString()
                when {
                    valor.text.isBlank() -> { aviso(a, "Digite a senha de recuperação ou a resposta."); senhaNova(a, email, metodo, pergunta, aoTrocar) }
                    n.length < 8 -> { aviso(a, "A senha nova precisa ter pelo menos 8 caracteres."); senhaNova(a, email, metodo, pergunta, aoTrocar) }
                    n != nova2.text.toString() -> { aviso(a, "As duas senhas novas não são iguais."); senhaNova(a, email, metodo, pergunta, aoTrocar) }
                    else -> Thread {
                        val r = try { VetorialId.recuperarSegredo(a, email, metodo, valor.text.toString(), n) } catch (e: Throwable) { null }
                        a.runOnUiThread {
                            when {
                                r == null -> aviso(a, "Sem conexão com o servidor. Tente de novo.")
                                r.ok -> { aviso(a, "Senha trocada. Entre com a nova senha."); aoTrocar(email) }
                                else -> { aviso(a, r.erro()); senhaNova(a, email, metodo, pergunta, aoTrocar) }
                            }
                        }
                    }.start()
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun aviso(a: Activity, t: String) = android.widget.Toast.makeText(a, t, android.widget.Toast.LENGTH_LONG).show()
}
