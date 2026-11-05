package com.tonimarques.venusrobo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * A tela do Vênus Robô: liga e desliga, mostra se tem acesso às notificações, guarda o endereço e a chave
 * da Vênus da loja, escolhe em quais apps responder, testa a conversa e mostra as últimas respostas.
 */
class TelaRobo : TelaBase() {

    override fun onResume() {
        super.onResume()
        montar()
    }

    private fun montar() {
        novaTela()

        coluna.addView(TextView(this).apply {
            text = "VÊNUS ROBÔ"
            textSize = 18f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(destaque)
        })
        coluna.addView(texto("Responde as clientes no WhatsApp Business com a Vênus da loja (se ela não souber, passa para você) e prepara as respostas prontas do Instagram.", 13f, apagado))

        // ---- respostas prontas do Instagram
        cartao("Respostas do Instagram")
        val prontas = Respostas.lista(this).count { it.texto.isNotBlank() }
        adicionar(texto(
            if (prontas == 0) "A Vênus da loja escreve as respostas para as perguntas mais comuns, e você cola nas ferramentas oficiais do Instagram. O Instagram responde sozinho, sem risco de bloqueio."
            else "$prontas de ${Respostas.MODELOS.size} respostas prontas para colar no Instagram.",
            13.5f, Color.WHITE,
        ))
        adicionar(botao(if (prontas == 0) "Gerar as respostas do Instagram" else "Ver e copiar as respostas", true) {
            startActivity(Intent(this, TelaRespostas::class.java))
        })

        // ---- situação
        val acesso = OuvinteRobo.temAcesso(this)
        cartao("Situação")
        val ligado = Switch(this).apply {
            text = "Robô ligado"
            isChecked = Robo.ligado(this@TelaRobo)
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnCheckedChangeListener { _, v -> Robo.definirLigado(this@TelaRobo, v) }
        }
        adicionar(ligado)
        adicionar(texto(if (acesso) "✓ Acesso às notificações liberado." else "✗ Falta liberar o acesso às notificações.", 14f, if (acesso) Color.parseColor("#7CE0A3") else Color.parseColor("#FF8A80")))
        if (!acesso) {
            adicionar(texto("Se o Android não deixar ligar: Configurações → Apps → Vênus Robô → ⋮ (canto de cima) → Permitir configurações restritas. Depois volte aqui.", 12.5f, apagado))
            adicionar(botao("Liberar acesso às notificações", true) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        }
        val energia = getSystemService(PowerManager::class.java)
        if (!energia.isIgnoringBatteryOptimizations(packageName)) {
            adicionar(texto("A economia de bateria pode pausar o robô com a tela apagada.", 12.5f, apagado))
            adicionar(botao("Deixar sem restrição de bateria", false) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            })
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // ---- onde responder
        cartao("Responder em")
        for ((pacote, nome) in Robo.NOMES) {
            adicionar(Switch(this).apply {
                text = nome
                isChecked = Robo.canalLigado(this@TelaRobo, pacote)
                setTextColor(Color.WHITE)
                textSize = 15f
                setOnCheckedChangeListener { _, v -> Robo.definirCanal(this@TelaRobo, pacote, v) }
            })
        }

        adicionar(texto("No Instagram, o melhor é usar as respostas prontas acima: o Instagram desconfia de respostas feitas pelas notificações do celular.", 12.5f, apagado))

        // ---- ligação com a Vênus da loja
        cartao("Vênus da loja")
        adicionar(texto("Endereço", 12.5f, apagado))
        val endereco = campo(Robo.endereco(this), InputType.TYPE_TEXT_VARIATION_URI)
        adicionar(endereco)
        adicionar(texto("Chave do robô (no painel da loja)", 12.5f, apagado))
        val chave = campo(Robo.chave(this), InputType.TYPE_TEXT_VARIATION_PASSWORD)
        adicionar(chave)
        adicionar(botao("Salvar e testar", true) {
            Robo.salvar(this, endereco.text.toString(), chave.text.toString())
            Toast.makeText(this, "Testando…", Toast.LENGTH_SHORT).show()
            Thread {
                val resultado = try {
                    val r = Robo.perguntar(this, Robo.WHATSAPP_BUSINESS, "teste", "oi", teste = true)
                    "A Vênus respondeu:\n\n" + r.mensagens.joinToString("\n\n").ifBlank { "(sem texto)" }
                } catch (e: Exception) {
                    "Não deu certo: ${e.message}"
                }
                runOnUiThread {
                    android.app.AlertDialog.Builder(this).setTitle("Teste").setMessage(resultado).setPositiveButton("OK", null).show()
                }
            }.start()
        })

        // ---- últimas respostas
        val registros = Robo.registros(this)
        cartao("Últimas conversas (${registros.size})")
        if (registros.isEmpty()) adicionar(texto("Ainda não respondeu ninguém.", 13f, apagado))
        for (r in registros.take(25)) {
            val titulo = "${Robo.hora(r.quando)} · ${r.canal} · ${r.cliente}" + when {
                r.erro -> " · falhou"
                r.humano -> " · passou para você"
                else -> ""
            }
            adicionar(texto(titulo, 11.5f, if (r.erro) Color.parseColor("#FF8A80") else apagado))
            adicionar(texto("“${r.mensagem}”\n→ ${r.resposta}", 14f, Color.WHITE))
        }
    }
}
