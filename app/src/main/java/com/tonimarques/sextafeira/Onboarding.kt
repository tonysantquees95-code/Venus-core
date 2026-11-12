package com.tonimarques.sextafeira

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Guia do primeiro acesso: o que ligar nas configurações do celular, um passo por vez, com atalho para cada tela. */
internal object Onboarding {
    const val PREF_VISTO = "onboarding_visto"

    class Passo(val titulo: String, val texto: String, val botao: String?, val acao: (MainActivity) -> Unit = {})

    fun passos(n: String): List<Passo> = listOf(
        Passo(
            "Bem-vindo à $n",
            "Vou te mostrar em poucos passos o que ligar no celular para eu ouvir você, avisar das vendas e trabalhar com a tela apagada. " +
                "Tudo é opcional e você pode refazer este guia depois, no menu.", null,
        ),
        Passo(
            "1. Microfone",
            "Permita o microfone para eu ouvir \"$n\" e os seus pedidos. A voz de ativação roda no próprio celular.",
            "Permitir microfone",
        ) { it.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 21) },
        Passo(
            "2. Notificações",
            "Permita as notificações para eu avisar de pedidos, estoque baixo e clientes esperando. No Android 13 ou mais novo aparece um pedido do sistema.",
            "Permitir notificações",
        ) {
            if (android.os.Build.VERSION.SDK_INT >= 33) it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
            else abrir(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName))
        },
        Passo(
            "3. Contatos",
            "Permita os contatos para eu achar o número pelo nome: \"ligar para a Maria\", \"mandar WhatsApp para o João\". Eu só leio o número na hora do pedido.",
            "Permitir contatos",
        ) { it.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), Ferramentas.PEDIDO_CONTATOS) },
        Passo(
            "4. Bateria sem limite",
            "Para eu continuar ouvindo com a tela apagada, tire a otimização de bateria deste app. Na tela que abrir, escolha \"Sem restrições\" ou \"Permitir\".",
            "Abrir bateria",
        ) {
            abrir(it, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
        Passo(
            "5. Aparecer sobre outros apps",
            "Permita \"Exibir sobre outros apps\" para o orbe abrir num pop-up quando você me chamar de qualquer tela.",
            "Abrir permissão",
        ) { abrir(it, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}"))) },
        Passo(
            "6. Assistente padrão (opcional)",
            "Em Apps padrão, escolha a $n como \"Assistente digital\" para chamá-la pelo botão de ligar ou pelo gesto do sistema.",
            "Abrir apps padrão",
        ) { abrir(it, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) },
        Passo(
            "7. Sobre o ícone verde do microfone",
            "Se você ligar a escuta contínua, o Android mostra um ponto/ícone verde de microfone na barra de status. É uma proteção do sistema e prova que só uso o microfone enquanto a escuta está ligada. " +
                "Você liga e desliga em Menu › Ativação por voz.", null,
        ),
        Passo("Pronto!", "Agora é só dizer \"$n\" ou tocar no orbe. Se faltar algo, abra Menu › Guia do primeiro acesso.", null),
    )

    private fun abrir(a: MainActivity, vararg tentativas: Intent) {
        for (i in tentativas) {
            try { a.startActivity(i); return } catch (e: Exception) { }
        }
    }

    fun mostrar(a: MainActivity, indice: Int = 0, aoFechar: () -> Unit = {}) {
        val lista = passos(Marca.nome)
        val p = lista[indice]
        val ultimo = indice == lista.lastIndex
        val b = AlertDialog.Builder(a)
            .setTitle(p.titulo)
            .setMessage(p.texto)
            .setCancelable(false)
        if (p.botao != null) b.setNeutralButton(p.botao) { _, _ ->
            p.acao(a)
            a.window.decorView.postDelayed({ if (!a.isFinishing) mostrar(a, indice + 1, aoFechar) }, 400)
        }
        b.setPositiveButton(if (ultimo) "Concluir" else if (p.botao != null) "Pular" else "Continuar") { _, _ ->
            if (ultimo) {
                a.prefs.edit().putBoolean(PREF_VISTO, true).apply()
                aoFechar()
            } else mostrar(a, indice + 1, aoFechar)
        }
        if (!ultimo) b.setNegativeButton("Fechar guia") { _, _ ->
            a.prefs.edit().putBoolean(PREF_VISTO, true).apply()
            aoFechar()
        }
        b.show()
    }
}
