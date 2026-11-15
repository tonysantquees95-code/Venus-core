package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/**
 * "O que há de novo": a tela que aparece depois de uma atualização.
 * REGRA: toda mudança que altere o que o app faz ou como se usa (função nova, fluxo melhor, correção visível) ganha uma linha AQUI,
 * com id = o maior id + 1. A tela mostra só o que a pessoa ainda não viu, então não depende do número da versão.
 * Na primeira instalação ela não aparece: quem explica é o Guia do primeiro acesso.
 */
internal object Novidades {
    const val PREF_VISTAS = "novidades_vistas"

    data class Item(val id: Int, val data: String, val tipo: String, val titulo: String, val texto: String)

    val LISTA = listOf(
        Item(1, "2026-11-14", "novo", "O que há de novo", "Depois de cada atualização, esta tela conta o que mudou, o que melhorou e o que foi corrigido. Você também a encontra em Menu › O que há de novo."),
        Item(2, "2026-11-15", "correcao", "Respostas mais certas", "Perguntar a temperatura mais fria de um lugar (como a Antártica) agora busca a resposta, em vez de mostrar o clima daí onde você está. E dizer \"eu sou o administrador\" ou \"eu sou a dona da loja\" não vira mais o seu apelido. Além disso, a conta de administrador agora é reconhecida sozinha, sem precisar sair e entrar de novo."),
        Item(3, "2026-11-15", "melhoria", "Aviso sobre o Pix no guia", "No guia do primeiro acesso, o passo de aparecer sobre outros apps agora é opcional e avisa que alguns bancos bloqueiam o Pix com essa permissão ligada, dizendo onde desligar."),
        Item(4, "2026-11-15", "correcao", "Cadastro da loja volta sozinho", "Depois de reinstalar a Vênus e entrar no Vetorial ID, o nome, a loja, o site e as regras de venda voltam sozinhos da sua conta, em vez de aparecerem como \"Falta fazer\"."),
    )

    fun rotulo(tipo: String): String = when (tipo) { "novo" -> "Novo"; "melhoria" -> "Melhorou"; "correcao" -> "Corrigido"; else -> tipo }
    fun ultimoId(lista: List<Item> = LISTA): Int = lista.maxOfOrNull { it.id } ?: 0
    /** O que a pessoa ainda não viu, da mais nova para a mais antiga. */
    fun novasDesde(visto: Int, lista: List<Item> = LISTA): List<Item> = lista.filter { it.id > visto }.sortedByDescending { it.id }
    fun texto(itens: List<Item>): String = itens.joinToString("\n\n") { "[${rotulo(it.tipo)}] ${it.titulo}\n${it.texto}" }

    /** Depois de uma atualização: mostra o que ainda não foi visto. Quem acabou de instalar já foi marcado como "em dia" na MainActivity. */
    fun mostrarSeHouver(a: MainActivity) {
        if (a.isFinishing) return
        val vistas = a.prefs.getInt(PREF_VISTAS, 0)
        val novas = novasDesde(vistas)
        if (novas.isNotEmpty()) mostrar(a, novas)
    }

    /** Menu › O que há de novo: o histórico todo. */
    fun mostrarTudo(a: MainActivity) = mostrar(a, novasDesde(0))

    private fun mostrar(a: MainActivity, itens: List<Item>) {
        val versao = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
        val t = TextView(a).apply {
            text = texto(itens)
            textSize = 15f
            val p = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        AlertDialog.Builder(a)
            .setTitle("O que há de novo" + if (versao.isNotBlank()) " · $versao" else "")
            .setView(ScrollView(a).apply { addView(t as View) })
            .setPositiveButton("Entendi") { _, _ -> a.prefs.edit().putInt(PREF_VISTAS, ultimoId()).apply() }
            .setCancelable(false)
            .show()
    }
}
