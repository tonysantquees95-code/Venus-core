package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar

/**
 * A pesquisa "vai assinar?": uma telinha que aparece ao abrir o app para quem está no teste grátis (a partir do 2º dia) ou já ficou sem acesso.
 * A pessoa diz se vai assinar, se não vai ou se ainda não sabe, e o motivo. Vai para o Painel Dev › Feedback.
 * Respondeu: some por 7 dias. Fechou sem responder: volta no dia seguinte. A lógica é pura (sem Android) para ser testada.
 */
object PesquisaDeAssinatura {
    const val PREF_RESPONDIDA = "pesquisa_assinatura_respondida"
    const val PREF_ULTIMO_DIA = "pesquisa_assinatura_ultimo_dia"
    const val DIAS_DE_TESTE = 7
    const val DIA_MS = 86_400_000L
    const val ESPERA_DEPOIS_DE_RESPONDER_DIAS = 7
    const val PRIMEIRO_DIA = 2

    /** "teste", "pago" ou "vencido" (a licença que o servidor devolve em /id/conta); vazio quando não sabe. */
    fun faseDaLicenca(l: JSONObject?): String = when {
        l == null -> ""
        !l.optBoolean("acesso") -> "vencido"
        l.optBoolean("emTeste") -> "teste"
        else -> "pago"
    }

    fun diasUsados(l: JSONObject?): Int =
        if (l != null && l.optBoolean("acesso") && l.optBoolean("emTeste")) (DIAS_DE_TESTE - l.optInt("diasDeTesteRestantes")).coerceIn(0, DIAS_DE_TESTE) else -1

    fun chaveDoDia(agora: Calendar): String =
        "%04d-%02d-%02d".format(agora.get(Calendar.YEAR), agora.get(Calendar.MONTH) + 1, agora.get(Calendar.DAY_OF_MONTH))

    /**
     * Mostra se: está em teste desde o 2º dia ou já ficou sem acesso; não mostrou hoje; já passaram 7 dias da última resposta; e é um horário razoável (8h às 21h).
     * [respondidaEm] = quando a pessoa respondeu pela última vez (0 = nunca).
     */
    fun deveMostrar(fase: String, diasDeTeste: Int, ultimoDia: String?, respondidaEm: Long, agora: Calendar): Boolean {
        val elegivel = (fase == "teste" && diasDeTeste >= PRIMEIRO_DIA) || fase == "vencido"
        if (!elegivel) return false
        if (ultimoDia == chaveDoDia(agora)) return false
        if (respondidaEm > 0 && agora.timeInMillis - respondidaEm < ESPERA_DEPOIS_DE_RESPONDER_DIAS * DIA_MS) return false
        return agora.get(Calendar.HOUR_OF_DAY) in 8..21
    }

    /** Pergunta do 2º passo, de acordo com a decisão. */
    fun perguntaDoMotivo(decisao: String): String = when (decisao) {
        "vai" -> "O que a Vênus trouxe para você querer assinar?"
        "nao" -> "Por que você não vai assinar?"
        else -> "O que falta para você decidir?"
    }

    /** Motivos que a pessoa pode marcar (para "vai" não há lista: só o texto livre). */
    fun motivos(decisao: String): List<Pair<String, String>> = when (decisao) {
        "nao" -> listOf("preco" to "O preço", "falta" to "Falta alguma função", "nao_uso" to "Não uso o bastante", "outro" to "Outro motivo")
        "talvez" -> listOf("testar_mais" to "Preciso testar mais", "preco" to "O preço", "falta" to "Falta alguma função", "outro" to "Outro motivo")
        else -> emptyList()
    }

    fun corpo(decisao: String, motivo: String, texto: String, fase: String, dias: Int, versao: String): JSONObject = JSONObject().apply {
        put("app", "venus"); put("tipo", "assinatura"); put("plataforma", "android"); put("versao", versao); put("texto", texto.trim().take(600))
        put("respostas", JSONObject().put("decisao", decisao).put("motivo", motivo).put("plano", fase).apply { if (dias >= 0) put("dias_de_uso", dias) })
    }

    // ---------------------------------------------------------------------------------------------------------- tela
    /** Chamada ao abrir o app: se for a hora, mostra a telinha. */
    fun mostrarSeForAHora(a: MainActivity) {
        if (a.isFinishing) return
        val agora = Calendar.getInstance()
        val ultimo = a.prefs.getString(PREF_ULTIMO_DIA, null)
        val respondida = a.prefs.getLong(PREF_RESPONDIDA, 0L)
        if (!deveMostrar(VetorialId.fase(a), VetorialId.diasDeTesteUsados(a), ultimo, respondida, agora)) return
        a.prefs.edit().putString(PREF_ULTIMO_DIA, chaveDoDia(agora)).apply() // fechou sem responder: volta amanhã
        passo1(a)
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun passo1(a: MainActivity) {
        val opcoes = listOf("vai" to "Vou assinar", "talvez" to "Ainda não sei", "nao" to "Não vou assinar")
        var d: AlertDialog? = null
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 22), dp(a, 4), dp(a, 22), dp(a, 8)) }
        col.addView(TextView(a).apply { text = "Você pretende assinar a Vênus? Sua resposta nos ajuda a melhorar."; textSize = 15f; setTextColor(Color.parseColor("#D7DAEE")); setPadding(0, 0, 0, dp(a, 10)) })
        opcoes.forEach { (id, nome) ->
            col.addView(TextView(a).apply {
                text = nome; textSize = 16f; gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE)
                minHeight = dp(a, 52); setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#1AFFFFFF")); setStroke(dp(a, 1), Aparencia.comAlfa(95)); cornerRadius = dp(a, 14).toFloat() }
                setOnClickListener { d?.dismiss(); passo2(a, id) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(a, 8) })
        }
        d = CaixaBuilder(a).setTitle("COMO ESTÁ SENDO?").setView(col).setNegativeButton("Agora não", null).show()
    }

    private fun passo2(a: MainActivity, decisao: String) {
        val lista = motivos(decisao)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 22), dp(a, 4), dp(a, 22), dp(a, 8)) }
        col.addView(TextView(a).apply { text = perguntaDoMotivo(decisao); textSize = 15f; setTextColor(Color.parseColor("#D7DAEE")); setPadding(0, 0, 0, dp(a, 8)) })
        var motivo = ""
        if (lista.isNotEmpty()) {
            val grupo = RadioGroup(a)
            lista.forEach { (id, nome) ->
                grupo.addView(RadioButton(a).apply { text = "  $nome"; textSize = 15f; setTextColor(Color.WHITE); minHeight = dp(a, 48); tag = id; this.id = View.generateViewId() },
                    RadioGroup.LayoutParams(-1, -2).apply { topMargin = dp(a, 4) })
            }
            grupo.setOnCheckedChangeListener { g, marcado -> motivo = g.findViewById<View>(marcado)?.tag as? String ?: "" }
            col.addView(grupo)
        }
        val campo = EditText(a).apply {
            hint = if (decisao == "vai") "Conte em poucas palavras (opcional)" else "Quer contar mais? (opcional)"
            setHintTextColor(Color.parseColor("#8C90B0")); setTextColor(Color.WHITE); textSize = 15f; minLines = 2; maxLines = 4; filters = arrayOf(android.text.InputFilter.LengthFilter(600))
            setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#14FFFFFF")); setStroke(dp(a, 1), Aparencia.comAlfa(95)); cornerRadius = dp(a, 12).toFloat() }
        }
        col.addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(a, 10) })
        CaixaBuilder(a).setTitle("SÓ MAIS UMA").setView(col).setCancelable(true)
            .setPositiveButton("Enviar") { _, _ -> enviar(a, decisao, motivo, campo.text.toString()) }
            .setNegativeButton("Agora não", null).show()
    }

    private fun enviar(a: MainActivity, decisao: String, motivo: String, texto: String) {
        val versao = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
        val corpo = corpo(decisao, motivo, texto, VetorialId.fase(a), VetorialId.diasDeTesteUsados(a), versao)
        Thread {
            val ok = try { Nuvem.bruto(a, "POST", "/feedback", corpo, null).ok } catch (e: Exception) { false }
            a.runOnUiThread {
                if (ok) {
                    a.prefs.edit().putLong(PREF_RESPONDIDA, System.currentTimeMillis()).apply()
                    android.widget.Toast.makeText(a, "Obrigado! Isso ajuda muito.", android.widget.Toast.LENGTH_LONG).show()
                } else android.widget.Toast.makeText(a, "Não consegui enviar agora. Vamos tentar de novo amanhã.", android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
