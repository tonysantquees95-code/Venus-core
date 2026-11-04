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
class TelaRobo : Activity() {

    private val destaque = Color.parseColor("#E36BD0")
    private val apagado = Color.parseColor("#8E87A8")
    private lateinit var coluna: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        montar()
    }

    private fun montar() {
        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }
        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0D0A1F"), Color.parseColor("#03040A")))
            addView(coluna)
        })

        coluna.addView(TextView(this).apply {
            text = "VÊNUS ROBÔ"
            textSize = 18f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(destaque)
        })
        coluna.addView(texto("Responde as clientes no WhatsApp Business e no Instagram com a Vênus da loja. Se ela não souber, passa para você.", 13f, apagado))

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

    // ------------------------------------------------------------------ peças da tela
    private var cartaoAtual: LinearLayout? = null

    private fun cartao(titulo: String) {
        coluna.addView(TextView(this).apply {
            text = titulo.uppercase()
            textSize = 11f
            letterSpacing = 0.25f
            setTextColor(destaque)
            setPadding(dp(6), dp(20), 0, dp(8))
        })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(150, 24, 19, 44))
                setStroke(dp(1), Color.parseColor("#2C2546"))
            }
        }
        coluna.addView(c)
        cartaoAtual = c
    }

    private fun adicionar(v: View) {
        (cartaoAtual ?: coluna).addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun texto(t: String, tamanho: Float, cor: Int) = TextView(this).apply {
        text = t
        textSize = tamanho
        setTextColor(cor)
        setPadding(dp(2), 0, dp(2), 0)
    }

    private fun campo(valor: String, variacao: Int) = EditText(this).apply {
        setText(valor)
        inputType = InputType.TYPE_CLASS_TEXT or variacao
        textSize = 14.5f
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#120E22"))
            setStroke(dp(1), Color.parseColor("#2C2546"))
        }
    }

    private fun botao(t: String, primario: Boolean, aoTocar: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setTextColor(if (primario) Color.WHITE else destaque)
        background = if (primario) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(destaque, Color.parseColor("#9B5CF6"))).apply { cornerRadius = dp(26).toFloat() }
        else GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), destaque)
        }
        setOnClickListener { aoTocar() }
    }
}
