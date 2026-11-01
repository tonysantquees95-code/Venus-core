package com.tonimarques.sextafeira

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Lembretes por voz: "me lembra amanhã às 10 de ligar para o fornecedor",
 * "me avisa daqui a 20 minutos de tirar o bolo". Na hora chega uma notificação.
 * Ficam guardados no celular e são reagendados se o celular reiniciar.
 */
object Lembretes {

    data class Pedido(val quando: Long, val texto: String)
    data class Lembrete(val id: Int, val quando: Long, val texto: String)

    // ================================================================ entender a frase
    private val GATILHO = Regex(
        "^(?:(?:por favor )?(?:me )?(?:lembra|lembre|lembrar|avisa|avise|chama|chame)(?:-me| me)?(?: de novo)?|" +
            "(?:cria|crie|coloca|coloque|faz|faca|marca|marque|bota|poe|anota|anote)(?: um| o)? lembrete|lembrete)\\b"
    )
    private const val NUM = "(\\d{1,2}|uma|um|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|quinze|vinte|trinta|quarenta|cinquenta|meia)"
    private val NUMEROS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7,
        "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "quinze" to 15, "vinte" to 20, "trinta" to 30,
        "quarenta" to 40, "cinquenta" to 50,
    )
    private val RELATIVO = Regex("\\b(?:daqui a|daqui|em|dentro de) $NUM (minutos?|min|horas?|h)(?: e $NUM minutos?)?\\b")
    private val MEIA_HORA = Regex("\\b(?:daqui a|daqui|em|dentro de) meia hora\\b")
    private val DIA_RELATIVO = Regex("\\b(depois de amanha|amanha|hoje)\\b")
    private val DIA_SEMANA = Regex("\\b(?:na |no |nesta |neste |nessa |nesse |essa |esse |esta |este |proxima |proximo |na proxima |no proximo )?(segunda|terca|quarta|quinta|sexta|sabado|domingo)(?:[- ]feira)?\\b")
    private val DIA_MES = Regex("\\b(?:no )?dia (\\d{1,2})(?: de (janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro))?\\b")
    private val HORA = Regex(
        "\\b(?:as|a|ao|pelas|pras|para as|lá pelas|la pelas)? ?(\\d{1,2})(?::(\\d{2})|h(\\d{2})?| ?horas?)?(?: e (\\d{1,2}|meia)(?: minutos?)?)?" +
            "(?: (da manha|da tarde|da noite|da madrugada))?\\b"
    )
    private val HORA_EXPLICITA = Regex("\\b(?:as|a|pelas|pras|para as|la pelas) (\\d{1,2})\\b|\\b\\d{1,2}(?::\\d{2}|h\\d{0,2}| horas?)\\b")
    private val MEIO = Regex("\\b(?:ao |as |a )?(meio[- ]dia|meia[- ]noite)(?: e (\\d{1,2}|meia))?\\b")
    private val PERIODO = Regex("\\b(?:de manha|pela manha|cedo|a tarde|de tarde|pela tarde|a noite|de noite|pela noite|no fim da tarde|no final da tarde)\\b")
    private val SEMANA = listOf("domingo", "segunda", "terca", "quarta", "quinta", "sexta", "sabado")
    private val MESES = listOf("janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    private fun numero(s: String): Int? = s.toIntOrNull() ?: NUMEROS[s]

    /** Entende um pedido de lembrete. Só devolve algo se houver um gatilho ("me lembra") e um horário. */
    fun interpretar(frase: String, agora: Calendar = Calendar.getInstance()): Pedido? {
        val original = frase.trim().trimEnd('.', '!', '?')
        // mesma quantidade de letras do original: dá para recortar a mensagem com as acentuações certas
        val c = StringBuilder(ComandosLocais.normalizar(original))
        val g = GATILHO.find(c) ?: return null
        if (g.range.first != 0) return null
        fun apagar(r: IntRange) {
            for (i in r) c.setCharAt(i, ' ')
        }
        apagar(g.range)

        val alvo = agora.clone() as Calendar
        alvo.set(Calendar.SECOND, 0)
        alvo.set(Calendar.MILLISECOND, 0)
        var temDia = false
        var temHora = false

        // 1) daqui a X minutos/horas
        MEIA_HORA.find(c)?.let { m ->
            apagar(m.range)
            return montar(original, c, agora.timeInMillis + 30 * 60_000L)
        }
        RELATIVO.find(c)?.let { m ->
            val n = if (m.groupValues[1] == "meia") 30 else numero(m.groupValues[1]) ?: return null
            val minutos = if (m.groupValues[2].startsWith("h")) n * 60 + (m.groupValues[3].takeIf { it.isNotBlank() }?.let { numero(it) } ?: 0) else n
            apagar(m.range)
            return montar(original, c, agora.timeInMillis + minutos * 60_000L)
        }

        // 2) o dia
        DIA_RELATIVO.find(c)?.let { m ->
            when (m.groupValues[1]) {
                "amanha" -> alvo.add(Calendar.DAY_OF_YEAR, 1)
                "depois de amanha" -> alvo.add(Calendar.DAY_OF_YEAR, 2)
            }
            temDia = true
            apagar(m.range)
        }
        var mesmoDiaDaSemana = false
        if (!temDia) DIA_SEMANA.find(c)?.let { m ->
            val desejado = SEMANA.indexOf(m.groupValues[1]) + 1 // Calendar.SUNDAY = 1
            var dif = (desejado - alvo.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (dif == 0) mesmoDiaDaSemana = true
            if (dif == 0 && Regex("\\bproxim").containsMatchIn(m.value)) dif = 7
            alvo.add(Calendar.DAY_OF_YEAR, dif)
            temDia = true
            apagar(m.range)
        }
        if (!temDia) DIA_MES.find(c)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d !in 1..31) return@let
            val mes = MESES.indexOf(m.groupValues[2])
            if (mes >= 0) {
                alvo.set(Calendar.MONTH, mes)
                if (alvo.get(Calendar.MONTH) < agora.get(Calendar.MONTH)) alvo.add(Calendar.YEAR, 1)
            }
            alvo.set(Calendar.DAY_OF_MONTH, minOf(d, alvo.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (mes < 0 && alvo.get(Calendar.DAY_OF_MONTH) < agora.get(Calendar.DAY_OF_MONTH)) alvo.add(Calendar.MONTH, 1)
            temDia = true
            apagar(m.range)
        }

        // 3) a hora
        var hora = -1
        var minuto = 0
        MEIO.find(c)?.let { m ->
            hora = if (m.groupValues[1].startsWith("meio")) 12 else 0
            minuto = when (val e = m.groupValues[2]) { "" -> 0; "meia" -> 30; else -> e.toIntOrNull() ?: 0 }
            temHora = true
            apagar(m.range)
        }
        if (!temHora && HORA_EXPLICITA.containsMatchIn(c)) {
            val m = HORA.findAll(c).firstOrNull { HORA_EXPLICITA.containsMatchIn(it.value) }
            if (m != null) {
                hora = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifBlank { m.groupValues[3] }.ifBlank { m.groupValues[4] }
                minuto = if (min == "meia") 30 else min.toIntOrNull() ?: 0
                when (m.groupValues[5]) {
                    "da tarde", "da noite" -> if (hora < 12) hora += 12
                    "da manha", "da madrugada" -> if (hora == 12) hora = 0
                    "" -> if (hora in 1..6) hora += 12 // "às 3" é de tarde
                }
                if (hora !in 0..23 || minuto !in 0..59) return null
                temHora = true
                apagar(m.range)
            }
        }
        PERIODO.find(c)?.let { m ->
            if (!temHora) {
                hora = when {
                    "manha" in m.value || "cedo" in m.value -> 9
                    "noite" in m.value -> 20
                    "fim" in m.value || "final" in m.value -> 17
                    else -> 15
                }
                temHora = true
            }
            apagar(m.range)
        }
        if (!temDia && !temHora) return null
        if (!temHora) hora = 9
        alvo.set(Calendar.HOUR_OF_DAY, hora)
        alvo.set(Calendar.MINUTE, minuto)
        if (alvo.timeInMillis <= agora.timeInMillis) {
            when {
                !temDia -> alvo.add(Calendar.DAY_OF_YEAR, 1) // "às 8" e já passou: amanhã
                mesmoDiaDaSemana -> alvo.add(Calendar.DAY_OF_YEAR, 7)
                else -> return null
            }
        }
        return montar(original, c, alvo.timeInMillis)
    }

    private fun montar(original: String, c: CharSequence, quando: Long): Pedido {
        val sb = StringBuilder()
        for (i in original.indices) sb.append(if (c[i] == ' ' && original[i] != ' ') ' ' else original[i])
        var texto = sb.toString().replace(Regex("\\s+"), " ").trim().trim(',', ' ')
        val conectores = Regex("^(?i)(?:de que|de|que|para|pra|pro|do|da|sobre|a|o|e|:|,)\\s+")
        repeat(3) { texto = texto.replace(conectores, "") }
        texto = texto.replace(Regex("(?i)\\s+(?:para mim|pra mim|por favor)$"), "").trim().trim(',', '.', ' ')
        if (texto.isBlank()) texto = "Lembrete"
        return Pedido(quando, texto.replaceFirstChar { it.uppercase() })
    }

    /** "hoje às 10:00", "amanhã às 9:30", "sexta, 7/11, às 15:00", "daqui a 20 minutos (às 21:47)". */
    fun quandoFalado(quando: Long, agora: Calendar = Calendar.getInstance()): String {
        val c = Calendar.getInstance().apply { timeInMillis = quando }
        val hora = String.format(Locale.US, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        // arredonda para cima: "daqui a 2 minutos" dito alguns segundos depois ainda é 2, não 1
        val minutos = ((quando - agora.timeInMillis + 59_999) / 60_000).toInt()
        if (minutos in 0..59) return "daqui a ${if (minutos <= 1) "1 minuto" else "$minutos minutos"} (às $hora)"
        val hoje = agora.clone() as Calendar
        fun mesmoDia(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (mesmoDia(c, hoje)) return "hoje às $hora"
        hoje.add(Calendar.DAY_OF_YEAR, 1)
        if (mesmoDia(c, hoje)) return "amanhã às $hora"
        val dia = listOf("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado")[c.get(Calendar.DAY_OF_WEEK) - 1]
        return "$dia, ${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}, às $hora"
    }

    // ================================================================ guardar e agendar
    private const val PREFS = "lembretes"
    private const val ACAO = "com.tonimarques.sextafeira.LEMBRETE"
    private const val CANAL = "lembretes"

    private fun ler(ctx: Context): MutableList<Lembrete> {
        val arr = try {
            JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Lembrete(it.optInt("id"), it.optLong("quando"), it.optString("texto")) }.toMutableList()
    }

    private fun gravar(ctx: Context, lista: List<Lembrete>) {
        val arr = JSONArray()
        for (l in lista) arr.put(JSONObject().put("id", l.id).put("quando", l.quando).put("texto", l.texto))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", arr.toString()).apply()
    }

    fun criar(ctx: Context, p: Pedido): Lembrete {
        val lista = ler(ctx)
        val l = Lembrete((lista.maxOfOrNull { it.id } ?: 0) + 1, p.quando, p.texto)
        lista += l
        gravar(ctx, lista)
        agendar(ctx, l)
        return l
    }

    fun futuros(ctx: Context): List<Lembrete> {
        val agora = System.currentTimeMillis()
        return ler(ctx).filter { it.quando > agora - 60_000 }.sortedBy { it.quando }
    }

    fun deHoje(ctx: Context): List<Lembrete> {
        val fim = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        return futuros(ctx).filter { it.quando < fim }
    }

    /** Cancela os lembretes que têm [trecho] no texto (ou todos, se vazio). Devolve quantos. */
    fun cancelar(ctx: Context, trecho: String): Int {
        val lista = ler(ctx)
        val chave = ComandosLocais.normalizar(trecho.trim())
        val sair = lista.filter { chave.isBlank() || ComandosLocais.normalizar(it.texto).contains(chave) }
        for (l in sair) ctx.getSystemService(AlarmManager::class.java).cancel(intencao(ctx, l))
        gravar(ctx, lista - sair.toSet())
        return sair.size
    }

    private fun intencao(ctx: Context, l: Lembrete): PendingIntent = PendingIntent.getBroadcast(
        ctx, 5000 + l.id,
        Intent(ctx, AvisoDeLembrete::class.java).setAction(ACAO).putExtra("id", l.id).putExtra("texto", l.texto),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun agendar(ctx: Context, l: Lembrete) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intencao(ctx, l)
        val exato = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exato) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, l.quando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, l.quando, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, l.quando, pi)
        }
    }

    /** Depois de reiniciar o celular ou atualizar o app, os alarmes somem: agenda de novo. */
    fun reagendarTodos(ctx: Context) {
        val agora = System.currentTimeMillis()
        val lista = ler(ctx)
        for (l in lista) {
            if (l.quando > agora) agendar(ctx, l)
            else if (agora - l.quando < 6 * 3600_000L) avisar(ctx, l.id, l.texto, atrasado = true)
        }
        gravar(ctx, lista.filter { it.quando > agora })
    }

    fun avisar(ctx: Context, id: Int, texto: String, atrasado: Boolean = false) {
        val gerente = ctx.getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(
            NotificationChannel(CANAL, "Lembretes", NotificationManager.IMPORTANCE_HIGH).apply { description = "Lembretes que você pediu para a ${Marca.nome}" }
        )
        val abrir = PendingIntent.getActivity(ctx, 6000 + id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val aviso = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_venus_simbolo)
            .setContentTitle(if (atrasado) "Lembrete (atrasado)" else "Lembrete")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        try {
            gerente.notify(6000 + id, aviso)
        } catch (e: SecurityException) {
        }
    }

    internal fun disparou(ctx: Context, id: Int, texto: String) {
        avisar(ctx, id, texto)
        gravar(ctx, ler(ctx).filter { it.id != id })
    }
}

/** Recebe o alarme do lembrete (e o aviso de que o celular reiniciou ou o app foi atualizado). */
class AvisoDeLembrete : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Lembretes.reagendarTodos(ctx)
                if (VigiaDaLoja.ligado(ctx)) VigiaDaLoja.garantir(ctx)
            }
            else -> Lembretes.disparou(ctx, intent.getIntExtra("id", 0), intent.getStringExtra("texto").orEmpty().ifBlank { "Lembrete" })
        }
    }
}
