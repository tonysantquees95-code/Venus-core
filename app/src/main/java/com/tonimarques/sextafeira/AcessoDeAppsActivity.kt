package com.tonimarques.sextafeira

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

/**
 * Sessão entre apps no mesmo celular: o Vindex, o Vortex e os portais da Vetorial Tech pedem a conta aqui
 * (startActivityForResult) e recebem um token do app, sem pedir a senha de novo. Só pacotes da suíte são atendidos
 * e o usuário confirma. O token usa a mesma sessão deste aparelho: não ocupa outra vaga das 2 ativas.
 */
class AcessoDeAppsActivity : Activity() {
    companion object {
        const val ACAO = "tech.vetorial.id.ENTRAR"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_EMAIL = "email"
        const val EXTRA_NOME = "nome"
        const val EXTRA_ERRO = "erro"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = appDaSuite(callingActivity?.packageName)
        when {
            app == null -> terminar(RESULT_CANCELED, "app_nao_autorizado")
            !VetorialId.logado(this) -> {
                terminar(RESULT_CANCELED, "sem_login")
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> CaixaBuilder(this).setTitle("Entrar com o Vetorial ID")
                .setMessage("O ${app.replaceFirstChar { it.uppercase() }} quer entrar com a conta ${VetorialId.email(this)}. Permitir?")
                .setPositiveButton("Permitir") { _, _ -> entregar(app) }
                .setNegativeButton("Negar") { _, _ -> terminar(RESULT_CANCELED, "negado") }
                .setOnCancelListener { terminar(RESULT_CANCELED, "negado") }.show()
        }
    }

    private fun entregar(app: String) {
        Thread {
            val r = try { Nuvem.bruto(this, "POST", "/id/app-token", JSONObject().put("app", app), VetorialId.token(this)) } catch (e: Exception) { null }
            runOnUiThread {
                if (r != null && r.ok) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, r.json.optString("token")).putExtra(EXTRA_EMAIL, VetorialId.email(this)).putExtra(EXTRA_NOME, VetorialId.nomeDaConta(this)))
                    finish()
                } else terminar(RESULT_CANCELED, r?.json?.optString("codigo").orEmpty().ifBlank { "sem_conexao" })
            }
        }.start()
    }

    private fun terminar(codigo: Int, erro: String) {
        setResult(codigo, Intent().putExtra(EXTRA_ERRO, erro))
        finish()
    }
}
