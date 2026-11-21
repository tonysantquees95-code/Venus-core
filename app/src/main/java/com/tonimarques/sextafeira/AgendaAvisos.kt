package com.tonimarques.sextafeira

import android.content.Context
import java.time.LocalDate

/**
 * Avisa no celular do dono quando um cliente marca horário pela página de agendamento (sem API da Meta e sem WhatsApp oficial).
 * Roda junto do vigia da loja (a cada ~15 min). A primeira olhada só guarda o ponto de partida: não avisa o que já existia.
 * Só fica ativo depois que a pessoa abriu a Agenda de serviços pela primeira vez.
 */
object AgendaAvisos {
    private const val PREFS = "agenda_avisos"
    private const val P_ATIVO = "ativo"

    fun ativo(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(P_ATIVO, false)

    fun ativar(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(P_ATIVO, true).apply()
        VigiaDaLoja.garantir(c)
    }

    private fun chave(c: Context) = "ultimo_" + Lojas.aberta(c).ifBlank { "principal" }

    /** Olha o servidor e manda uma notificação para cada marcação online nova (no máximo 5 por vez). */
    fun conferir(c: Context) {
        if (!ativo(c) || !Nuvem.registrada(c)) return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = chave(c)
        val desde = if (prefs.contains(k)) prefs.getLong(k, 0L) else null
        val r = Nuvem.pedir(c, "GET", "/loja/agenda/novos" + if (desde != null) "?desde=$desde" else "")
        if (!r.ok) return
        val ultimo = r.json.optLong("ultimo", desde ?: 0L)
        val lista = r.json.optJSONArray("reservas")
        if (desde != null && lista != null) {
            for (i in 0 until minOf(lista.length(), 5)) {
                val o = lista.getJSONObject(i)
                VigiaDaLoja.avisar(c, 5000 + (o.optLong("id") % 1000).toInt(), "Novo horário marcado", texto(o))
            }
            if (lista.length() > 5) VigiaDaLoja.avisar(c, 4999, "Novos horários marcados", "Mais ${lista.length() - 5} marcações. Abra a Agenda de serviços para ver.")
        }
        prefs.edit().putLong(k, ultimo).apply()
    }

    internal fun texto(o: org.json.JSONObject): String {
        val cliente = o.optString("cliente").trim()
        val servico = o.optString("servico_nome")
        val prof = o.optString("profissional_nome")
        val ini = o.optInt("ini")
        val quando = try {
            val d = LocalDate.parse(o.optString("data"))
            val dia = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")[d.dayOfWeek.value - 1]
            "$dia, %02d/%02d às %02d:%02d".format(d.dayOfMonth, d.monthValue, ini / 60, ini % 60)
        } catch (e: Exception) { o.optString("data") }
        return "$cliente · $servico · $quando" + if (prof.isNotBlank()) " · com $prof" else ""
    }
}
