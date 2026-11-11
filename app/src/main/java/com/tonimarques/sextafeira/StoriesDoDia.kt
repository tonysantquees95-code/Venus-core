package com.tonimarques.sextafeira

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Stories do dia: duas vezes por dia (manhã e tarde) a Vênus publica sozinha no Instagram da loja um story de
 * dica ou curiosidade sobre bem-estar, autoestima e intimidade, com a cara da loja (fundo preto, rosa neon,
 * logo, foto de um produto da loja ao fundo).
 *
 * Os textos vêm do Gemini uma vez por semana (14 de uma vez, gastando só 1 pedido da cota); se o Gemini
 * não responder, usa uma reserva fixa, então o story nunca falha por falta de texto.
 * WhatsApp: não existe jeito oficial de publicar status sozinho; depois de cada story chega uma notificação
 * que abre o WhatsApp Business com a imagem pronta (é só escolher "Meu status" e enviar).
 */
object StoriesDoDia {

    data class Tema(val tipo: String, val titulo: String, val texto: String, val enquete: String = "") {
        /** Pergunta de sim/não para o adesivo de enquete do Instagram. */
        val pergunta get() = enquete.ifBlank { if (tipo == "curiosidade") "Você já sabia disso?" else "Você já faz isso?" }
    }

    private const val PREFS = "stories_dia"
    private const val CANAL = "stories_dia"
    private const val JOB_ID = 74_100
    const val ACAO = "com.tonimarques.sextafeira.STORY_DO_DIA"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val ACAO_INTERATIVO = "com.tonimarques.sextafeira.STORY_INTERATIVO"
    const val ACAO_LEMBRETE = "com.tonimarques.sextafeira.STORY_LEMBRETE"
    const val ACAO_MARCAR = "com.tonimarques.sextafeira.STORY_MARCAR"

    /** 1 hora depois do story com enquete: o site lembra a equipe, se ninguém marcou que postou. */
    fun agendarLembrete(ctx: Context, id: Int) {
        val pi = PendingIntent.getBroadcast(
            ctx, 74_400, Intent(ctx, AlarmeDoStory::class.java).setAction(ACAO_LEMBRETE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60 * 60_000L, pi)
    }
    private val ROSA = Color.parseColor("#FF2E97")
    private val LILAS = Color.parseColor("#9B5CF6")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ligado(ctx: Context) = prefs(ctx).getBoolean("ligado", true)

    fun definirLigado(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("ligado", v).apply()
        if (v) agendar(ctx) else cancelar(ctx)
        Diario.anotar(ctx, "instagram", if (v) "Stories automáticos ligados (manhã e tarde)" else "Stories automáticos desligados", "voz")
    }

    /** Horários em minutos do dia: manhã 9h30 e tarde 15h30, se não mudar. */
    fun horario(ctx: Context, turno: String): Int = prefs(ctx).getInt("hora_$turno", if (turno == "manha") 9 * 60 + 30 else 15 * 60 + 30)

    fun definirHorario(ctx: Context, turno: String, minutos: Int) {
        prefs(ctx).edit().putInt("hora_$turno", minutos.coerceIn(0, 24 * 60 - 1)).apply()
        agendar(ctx)
    }

    /**
     * Story da tarde com enquete: a API do Instagram não coloca adesivos, então a Vênus prepara a imagem
     * (com espaço para a enquete), copia a pergunta e abre o Instagram; você põe o adesivo e publica.
     */
    fun tardeInterativa(ctx: Context) = prefs(ctx).getBoolean("tarde_interativa", false)

    fun definirTardeInterativa(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("tarde_interativa", v).apply()

    fun horaTexto(minutos: Int) = "%d:%02d".format(minutos / 60, minutos % 60)

    fun descricao(ctx: Context): String {
        val base = "Às ${horaTexto(horario(ctx, "manha"))} publica sozinha um story de dica ou curiosidade sobre bem-estar e autoestima, com a cara da loja" +
            (if (tardeInterativa(ctx)) "; às ${horaTexto(horario(ctx, "tarde"))} prepara o story com enquete (você só põe o adesivo e publica)."
            else " e às ${horaTexto(horario(ctx, "tarde"))} também.") + " Depois chega um aviso para pôr no status do WhatsApp."
        val ultimo = prefs(ctx).getString("ultimo", null)?.let { " Último: $it" }.orEmpty()
        val falta = when {
            !Instagram.conectado(ctx) -> " Falta conectar o Instagram da loja."
            !Loja(ctx).conectada -> " Falta conectar a loja (a imagem sobe pelo site)."
            else -> ""
        }
        val p = prefs(ctx)
        val rastro = listOfNotNull(
            p.getLong("disparou_em", 0).takeIf { it > 0 }?.let { "último alarme ${Diario.hora(it)}" },
            p.getLong("trabalho_em", 0).takeIf { it > 0 }?.let { "última tentativa ${Diario.hora(it)}" },
        ).joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
        return base + ultimo + falta + rastro
    }

    // ------------------------------------------------------------------ agenda
    private fun proximo(ctx: Context, agora: Calendar = Calendar.getInstance()): Pair<String, Long> {
        val opcoes = mutableListOf<Pair<String, Long>>()
        for (dias in 0..1) for (turno in listOf("manha", "tarde")) {
            val m = horario(ctx, turno)
            val c = (agora.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dias)
                set(Calendar.HOUR_OF_DAY, m / 60)
                set(Calendar.MINUTE, m % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > agora.timeInMillis + 30_000) opcoes += turno to c.timeInMillis
        }
        return opcoes.minByOrNull { it.second }!!
    }

    private fun alarme(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 74_000, Intent(ctx, AlarmeDoStory::class.java).setAction(ACAO), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Marca o próximo story (manhã ou tarde). Chamado ao abrir o app, ao ligar o celular e depois de cada story. */
    fun agendar(ctx: Context) {
        if (!ligado(ctx)) return
        val (turno, quando) = proximo(ctx)
        prefs(ctx).edit().putString("proximo_turno", turno).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
        }
    }

    fun cancelar(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(alarme(ctx))

    const val ACAO_RECUPERAR = "com.tonimarques.sextafeira.STORY_RECUPERAR"

    /**
     * Na hora: publica direto (com o celular acordado por um WakeLock), sem esperar o Android liberar um "trabalho em
     * segundo plano", que em economia de bateria/Doze pode atrasar por horas. Se falhar, o trabalho em segundo plano
     * e um alarme de segurança (25 min depois) tentam de novo.
     */
    internal fun disparou(ctx: Context, pendente: BroadcastReceiver.PendingResult? = null) {
        val turno = prefs(ctx).getString("proximo_turno", null) ?: turnoDeAgora()
        prefs(ctx).edit().putLong("disparou_em", System.currentTimeMillis()).apply()
        Diario.anotar(ctx, "instagram", "Alarme do story da ${nomeTurno(turno)} disparou", "automatico")
        agendar(ctx) // já marca o próximo
        agendarSeguranca(ctx)
        val trava = try {
            ctx.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "venus:story")
                .apply { acquire(4 * 60_000L) }
        } catch (e: Exception) {
            null
        }
        Thread {
            try {
                prefs(ctx).edit().putLong("trabalho_em", System.currentTimeMillis()).apply()
                publicarEAvisar(ctx, turno)
            } catch (e: Exception) {
                publicarEmSegundoPlano(ctx, turno) // não deu agora: o Android tenta quando puder
            } finally {
                try { trava?.release() } catch (e: Exception) {}
                try { pendente?.finish() } catch (e: Exception) {}
            }
        }.start()
    }

    /** Publica e, se der erro, anota e avisa (menos o "já foi publicado"). Lança o erro para quem chamou poder tentar de novo. */
    internal fun publicarEAvisar(ctx: Context, turno: String) {
        try {
            publicar(ctx, turno)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("já foi publicado")) return
            Registro.anotar(ctx, "erro", "story do dia → $msg")
            Diario.anotar(ctx, "instagram", "Story do dia (${nomeTurno(turno)}) não publicado: $msg", "automatico", ok = false)
            avisar(ctx, null, null, msg)
            throw e
        }
    }

    /** 25 minutos depois do horário confere se o story saiu; se não, publica (recuperarAtrasado). */
    private fun agendarSeguranca(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, 74_002, Intent(ctx, AlarmeDoStory::class.java).setAction(ACAO_RECUPERAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 25 * 60_000L, pi)
    }

    /**
     * Se o horário de um story já passou (mais de 20 minutos) e ele não saiu hoje, publica agora.
     * Cobre o Android que segurou o alarme ou o trabalho em segundo plano (economia de bateria).
     * Chamado ao abrir o app, ao ligar o celular, depois de atualizar e a cada rodada da vigia da loja.
     * No máximo 3 tentativas por turno no dia, com 30 minutos entre elas, e só até 6 horas depois do horário.
     */
    fun recuperarAtrasado(ctx: Context) {
        if (!ligado(ctx)) return
        val agora = Calendar.getInstance()
        val minutos = agora.get(Calendar.HOUR_OF_DAY) * 60 + agora.get(Calendar.MINUTE)
        val p = prefs(ctx)
        for (turno in listOf("manha", "tarde")) {
            val h = horario(ctx, turno)
            if (minutos < h + 20 || minutos > h + 6 * 60) continue
            if (p.getBoolean("feito_${hoje()}_$turno", false)) continue
            val chave = "tentativas_${hoje()}_$turno"
            val tentativas = p.getInt(chave, 0)
            if (tentativas >= 3 || System.currentTimeMillis() - p.getLong("ultima_tentativa", 0) < 30 * 60_000L) continue
            p.edit().putInt(chave, tentativas + 1).putLong("ultima_tentativa", System.currentTimeMillis()).apply()
            Diario.anotar(ctx, "instagram", "Story da ${nomeTurno(turno)} atrasado: publicando agora (tentativa ${tentativas + 1})", "automatico")
            Thread {
                try {
                    publicarEAvisar(ctx, turno)
                } catch (e: Exception) {
                    publicarEmSegundoPlano(ctx, turno)
                }
            }.start()
            return
        }
    }

    private fun publicarEmSegundoPlano(ctx: Context, turno: String) {
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, PublicadorDeStory::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(30 * 60_000L)
            .setExtras(android.os.PersistableBundle().apply { putString("turno", turno) })
            .build()
        try {
            ctx.getSystemService(JobScheduler::class.java).schedule(info)
        } catch (e: Exception) {
        }
    }

    private fun turnoDeAgora() = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) "manha" else "tarde"

    private fun hoje() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ------------------------------------------------------------------ publicar
    /**
     * Monta e publica o story. [forcar]: publica mesmo que este turno já tenha saído hoje ("posta a dica agora").
     * Bloqueante: chame fora da thread principal. Devolve o tema publicado.
     */
    fun publicar(ctx: Context, turno: String = turnoDeAgora(), forcar: Boolean = false): Tema {
        val marca = "feito_${hoje()}_$turno"
        if (!forcar && prefs(ctx).getBoolean(marca, false)) throw IllegalStateException("O story da ${nomeTurno(turno)} de hoje já foi publicado.")
        if (!forcar && turno == "tarde" && tardeInterativa(ctx)) return prepararComEnquete(ctx, marca)
        // sem o Instagram conectado (ou sem a loja para subir a imagem): a arte sai do mesmo jeito, para postar com um toque
        if (!Instagram.conectado(ctx)) return prepararManual(ctx, marca, turno, "o Instagram não está conectado")
        val loja = Loja(ctx)
        if (!loja.conectada) return prepararManual(ctx, marca, turno, "a loja não está conectada")
        val tema = proximoTema(ctx)
        val jpeg = arte(ctx, tema)
        val url = loja.enviarFoto(jpeg)
        try {
            Instagram.publicar(ctx, url, "", story = true)
        } catch (e: Instagram.ErroDoInstagram) {
            // as duas primeiras falhas do dia são tentadas de novo (rede, token); depois disso prepara para postar com um toque
            val falhas = prefs(ctx).getInt("falhas_${hoje()}_$turno", 0)
            if (!forcar && falhas < 2) {
                prefs(ctx).edit().putInt("falhas_${hoje()}_$turno", falhas + 1).apply()
                throw IllegalStateException("o Instagram recusou (${e.message ?: "sem detalhe"}); vou tentar de novo")
            }
            return prepararManual(ctx, marca, turno, e.message ?: "o Instagram recusou", tema, jpeg)
        }
        usar(ctx, tema)
        // aparece na página Stories do painel (para repostar no status) e avisa a equipe
        try {
            loja.registrarStory(turno, tema.titulo, tema.texto, null, Loja.enderecoCompleto(url), enquete = false, publicado = true)
        } catch (e: Exception) {
            Registro.anotar(ctx, "erro", "story no painel → ${e.message}")
        }
        val arquivo = guardarParaWhatsApp(ctx, jpeg)
        registrar(ctx, turno, arquivo, tema, automatico = true, enquete = false)
        prefs(ctx).edit().putBoolean(marca, true)
            .putString("ultimo", "${nomeTurno(turno)} de ${SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date())}: \"${tema.titulo}\"").apply()
        Diario.anotar(ctx, "instagram", "Story do dia (${nomeTurno(turno)}) publicado: ${tema.titulo}", "automatico")
        avisar(ctx, tema, arquivo, null)
        return tema
    }

    fun nomeTurno(turno: String) = if (turno == "manha") "manhã" else "tarde"

    // ------------------------------------------------------------------ registro do dia (página Stories do dia)
    /** O story de um turno de hoje: a imagem, o tema e onde já foi publicado. */
    data class StoryDeHoje(
        val turno: String, val arquivo: File, val titulo: String, val texto: String, val pergunta: String,
        val automatico: Boolean, val instagram: Boolean, val whatsapp: Boolean,
    )

    private fun chaveRegistro(turno: String) = "registro_${hoje()}_$turno"

    private fun registrar(ctx: Context, turno: String, arquivo: File, tema: Tema, automatico: Boolean, enquete: Boolean) {
        val p = prefs(ctx)
        val e = p.edit()
        // só guarda os de hoje
        p.all.keys.filter { it.startsWith("registro_") && !it.startsWith("registro_${hoje()}_") }.forEach { e.remove(it) }
        e.putString(chaveRegistro(turno), JSONObject()
            .put("arquivo", arquivo.name).put("titulo", tema.titulo).put("texto", tema.texto)
            .put("pergunta", if (enquete) tema.pergunta else "")
            .put("automatico", automatico).put("instagram", automatico).put("whatsapp", false).toString())
        e.apply()
    }

    fun registroDeHoje(ctx: Context, turno: String): StoryDeHoje? {
        val o = try {
            JSONObject(prefs(ctx).getString(chaveRegistro(turno), null) ?: return null)
        } catch (e: Exception) {
            return null
        }
        return StoryDeHoje(
            turno, File(FotoProvider.pasta(ctx), o.optString("arquivo")), o.optString("titulo"), o.optString("texto"), o.optString("pergunta"),
            o.optBoolean("automatico"), o.optBoolean("instagram"), o.optBoolean("whatsapp"),
        )
    }

    /** Marca que o story do turno foi aberto para publicar no [canal] ("instagram" ou "whatsapp"). */
    fun marcarCanal(ctx: Context, turno: String, canal: String) {
        val p = prefs(ctx)
        val o = try {
            JSONObject(p.getString(chaveRegistro(turno), null) ?: return)
        } catch (e: Exception) {
            return
        }
        p.edit().putString(chaveRegistro(turno), o.put(canal, true).toString()).apply()
        Diario.anotar(ctx, "instagram", "Story da ${nomeTurno(turno)} aberto para publicar no ${if (canal == "instagram") "Instagram" else "WhatsApp"}", "tela")
    }

    /**
     * Gera outro story para o turno (tema e arte novos), sem publicar: fica na página para você publicar.
     * Serve também para preparar antes da hora. Bloqueante: chame fora da thread principal.
     */
    fun refazer(ctx: Context, turno: String): StoryDeHoje {
        val enquete = turno == "tarde" && tardeInterativa(ctx)
        val tema = proximoTema(ctx)
        val jpeg = arte(ctx, tema, espacoEnquete = enquete)
        usar(ctx, tema)
        val arquivo = guardarParaWhatsApp(ctx, jpeg)
        registrar(ctx, turno, arquivo, tema, automatico = false, enquete = enquete)
        prefs(ctx).edit().putBoolean("feito_${hoje()}_$turno", true).apply()
        Diario.anotar(ctx, "instagram", "Story da ${nomeTurno(turno)} gerado de novo pela página: ${tema.titulo}", "tela")
        return registroDeHoje(ctx, turno)!!
    }

    /**
     * Quando a publicação automática não dá (Instagram desconectado ou recusando): monta a arte, guarda para o
     * status do WhatsApp e avisa "story pronto". O toque abre o Instagram com a imagem; é só escolher Story e publicar.
     */
    private fun prepararManual(ctx: Context, marca: String, turno: String, motivo: String, pronto: Tema? = null, jpegPronto: ByteArray? = null): Tema {
        val tema = pronto ?: proximoTema(ctx)
        val jpeg = jpegPronto ?: arte(ctx, tema)
        usar(ctx, tema)
        val arquivo = guardarParaWhatsApp(ctx, jpeg)
        registrar(ctx, turno, arquivo, tema, automatico = false, enquete = false)
        prefs(ctx).edit().putBoolean(marca, true)
            .putString("ultimo", "${nomeTurno(turno)} de ${SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date())} (para postar com um toque): \"${tema.titulo}\"").apply()
        Diario.anotar(ctx, "instagram", "Story da ${nomeTurno(turno)} preparado para postar com um toque ($motivo): ${tema.titulo}", "automatico", ok = false)
        val gerente = ctx.getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(NotificationChannel(CANAL, "Stories do dia", NotificationManager.IMPORTANCE_DEFAULT))
        // o toque passa pela Vênus: abre o Instagram e, quando você volta, o status do WhatsApp com a mesma imagem
        val emSequencia = PendingIntent.getActivity(
            ctx, 74_220,
            Intent(ctx, MainActivity::class.java).setAction(ACAO_INTERATIVO).putExtra("arquivo", arquivo.name).putExtra("pergunta", "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val whatsapp = PendingIntent.getActivity(ctx, 74_221, intencaoWhatsApp(ctx, arquivo), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_venus_simbolo)
            .setContentTitle("Story da ${nomeTurno(turno)}: Instagram e WhatsApp")
            .setContentText("Toque: abre o Instagram com a imagem; publique e volte, que eu abro o status do WhatsApp.")
            .setStyle(Notification.BigTextStyle().bigText("\"${tema.titulo}\"\nToque: abre o Instagram com a imagem (escolha Story e publique). Ao voltar, abro o WhatsApp para o status. ($motivo)"))
            .setContentIntent(emSequencia)
            .addAction(Notification.Action.Builder(null, "Story Instagram e WhatsApp", emSequencia).build())
            .addAction(Notification.Action.Builder(null, "Só WhatsApp", whatsapp).build())
            .setAutoCancel(true)
        BitmapFactory.decodeFile(arquivo.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })?.let {
            b.setLargeIcon(it).setStyle(Notification.BigPictureStyle().bigPicture(it).setSummaryText("Toque: Instagram e depois o status do WhatsApp"))
        }
        try {
            gerente.notify(74_320, b.build())
        } catch (e: SecurityException) {
        }
        return tema
    }

    /** Story com enquete: monta a imagem (com espaço para o adesivo) e avisa; quem publica é você. */
    fun prepararComEnquete(ctx: Context, marca: String = "feito_${hoje()}_tarde"): Tema {
        val tema = proximoTema(ctx)
        val jpeg = arte(ctx, tema, espacoEnquete = true)
        usar(ctx, tema)
        val arquivo = guardarParaWhatsApp(ctx, jpeg)
        registrar(ctx, "tarde", arquivo, tema, automatico = false, enquete = true)
        // manda para a página Stories do painel: o site avisa o celular da Natália (e de quem ligou os avisos)
        var idNoPainel = 0
        try {
            val loja = Loja(ctx)
            if (loja.conectada) {
                val url = Loja.enderecoCompleto(loja.enviarFoto(jpeg))
                idNoPainel = loja.registrarStory("tarde", tema.titulo, tema.texto, tema.pergunta, url, enquete = true, publicado = false)
                if (idNoPainel > 0) agendarLembrete(ctx, idNoPainel)
            }
        } catch (e: Exception) {
            Registro.anotar(ctx, "erro", "story com enquete no painel → ${e.message}")
        }
        prefs(ctx).edit().putInt("id_painel_${arquivo.name}", idNoPainel).apply()
        prefs(ctx).edit().putBoolean(marca, true)
            .putString("ultimo", "tarde de ${SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date())} (com enquete): \"${tema.titulo}\"").apply()
        Diario.anotar(ctx, "instagram", "Story com enquete preparado: ${tema.titulo} (pergunta: ${tema.pergunta})", "automatico")
        avisarEnquete(ctx, tema, arquivo)
        return tema
    }

    /** Abre o Instagram com a imagem (escolha "Story" na tela de compartilhar). */
    fun intencaoInstagram(arquivo: File): Intent {
        val uri = android.net.Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")
        return Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .setPackage("com.instagram.android")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun avisarEnquete(ctx: Context, tema: Tema, arquivo: File) {
        val gerente = ctx.getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(NotificationChannel(CANAL, "Stories do dia", NotificationManager.IMPORTANCE_DEFAULT))
        // o toque passa pela Vênus, que copia a pergunta e abre o Instagram
        val instagram = PendingIntent.getActivity(
            ctx, 74_210,
            Intent(ctx, MainActivity::class.java).setAction(ACAO_INTERATIVO).putExtra("arquivo", arquivo.name).putExtra("pergunta", tema.pergunta)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val whatsapp = PendingIntent.getActivity(ctx, 74_211, intencaoWhatsApp(ctx, arquivo), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_venus_simbolo)
            .setContentTitle("Story da tarde pronto para a enquete")
            .setContentText("Toque: abre o Instagram com a imagem. Ponha o adesivo Enquete com \"${tema.pergunta}\" (já copiado).")
            .setStyle(Notification.BigTextStyle().bigText("\"${tema.titulo}\"\nToque para abrir o Instagram com a imagem, escolha Story, " +
                "toque no adesivo Enquete e cole a pergunta: ${tema.pergunta}"))
            .setContentIntent(instagram)
            .addAction(Notification.Action.Builder(null, "Status do WhatsApp", whatsapp).build())
            .also { b ->
                val id = prefs(ctx).getInt("id_painel_${arquivo.name}", 0)
                if (id > 0) {
                    val feito = PendingIntent.getBroadcast(
                        ctx, 74_212, Intent(ctx, AlarmeDoStory::class.java).setAction(ACAO_MARCAR).putExtra("id", id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    b.addAction(Notification.Action.Builder(null, "Já publiquei", feito).build())
                }
            }
            .setAutoCancel(true)
        BitmapFactory.decodeFile(arquivo.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })?.let { b.setLargeIcon(it) }
        try {
            gerente.notify(74_310, b.build())
        } catch (e: SecurityException) {
        }
    }

    /** A imagem do próximo story, sem publicar (para "mostra o story de hoje"). */
    fun previa(ctx: Context): Pair<Tema, File> {
        val tema = proximoTema(ctx)
        val (arquivo, _) = FotoProvider.novoArquivo(ctx, "story_previa_${System.currentTimeMillis()}.jpg")
        arquivo.writeBytes(arte(ctx, tema))
        return tema to arquivo
    }

    private fun guardarParaWhatsApp(ctx: Context, jpeg: ByteArray): File {
        val (arquivo, _) = FotoProvider.novoArquivo(ctx, "story_${System.currentTimeMillis()}.jpg")
        arquivo.writeBytes(jpeg)
        return arquivo
    }

    /** Abre o WhatsApp Business (ou a escolha de app) com a imagem pronta para o status. */
    fun intencaoWhatsApp(ctx: Context, arquivo: File): Intent {
        val uri = android.net.Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")
        val envio = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val temBusiness = try {
            ctx.packageManager.getPackageInfo(WHATSAPP_BUSINESS, 0)
            true
        } catch (e: Exception) {
            false
        }
        return if (temBusiness) envio.setPackage(WHATSAPP_BUSINESS)
        else Intent.createChooser(envio, "Pôr no status").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun avisar(ctx: Context, tema: Tema?, arquivo: File?, erro: String?) {
        val gerente = ctx.getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(NotificationChannel(CANAL, "Stories do dia", NotificationManager.IMPORTANCE_DEFAULT))
        val toque = if (arquivo != null) PendingIntent.getActivity(ctx, 74_200, intencaoWhatsApp(ctx, arquivo), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else PendingIntent.getActivity(ctx, 74_201, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_venus_simbolo)
            .setContentTitle(if (erro == null) "Story do dia publicado ✓" else "Não consegui publicar o story do dia")
            .setContentText(if (erro == null) "\"${tema?.titulo.orEmpty()}\". Toque para pôr no status do WhatsApp." else erro)
            .setContentIntent(toque)
            .setAutoCancel(true)
        if (arquivo != null) BitmapFactory.decodeFile(arquivo.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })?.let {
            b.setLargeIcon(it).setStyle(Notification.BigPictureStyle().bigPicture(it))
        }
        try {
            gerente.notify(74_300, b.build())
        } catch (e: SecurityException) {
        }
    }

    // ------------------------------------------------------------------ temas (textos)
    private fun fila(ctx: Context): MutableList<Tema> {
        val a = try {
            JSONArray(prefs(ctx).getString("fila", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Tema(it.optString("tipo"), it.optString("titulo"), it.optString("texto"), it.optString("enquete")) }.toMutableList()
    }

    private fun salvarFila(ctx: Context, l: List<Tema>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("tipo", it.tipo).put("titulo", it.titulo).put("texto", it.texto).put("enquete", it.enquete)) }
        prefs(ctx).edit().putString("fila", a.toString()).apply()
    }

    private fun usados(ctx: Context): List<String> = try {
        JSONArray(prefs(ctx).getString("usados", "[]")).let { a -> (0 until a.length()).map { a.optString(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    private fun usar(ctx: Context, t: Tema) {
        salvarFila(ctx, fila(ctx).filter { it.titulo != t.titulo })
        val u = (usados(ctx) + t.titulo).takeLast(120)
        prefs(ctx).edit().putString("usados", JSONArray(u).toString()).apply()
    }

    /** O próximo tema: da fila; se acabando, pede mais 14 ao Gemini; sem Gemini, da reserva. */
    fun proximoTema(ctx: Context): Tema {
        var f = fila(ctx)
        if (f.size < 3) {
            val novos = try {
                gerarTemas(ctx)
            } catch (e: Exception) {
                emptyList()
            }
            if (novos.isNotEmpty()) {
                f = (f + novos).distinctBy { ComandosLocais.normalizar(it.titulo) }.toMutableList()
                salvarFila(ctx, f)
            }
        }
        f.firstOrNull()?.let { return it }
        val ja = usados(ctx).map { ComandosLocais.normalizar(it) }.toSet()
        return RESERVA.firstOrNull { ComandosLocais.normalizar(it.titulo) !in ja } ?: RESERVA[(System.currentTimeMillis() / 86_400_000L % RESERVA.size).toInt()]
    }

    private fun gerarTemas(ctx: Context): List<Tema> {
        val config = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val chave = Cofre.ler(config, "chave")
        if (chave.isBlank()) return emptyList()
        val modelo = config.getString("modelo", "").orEmpty().ifBlank { "gemini-flash-latest" }
        val json = Gemini.gerarJson(chave, modelo, pedidoDeTemas(usados(ctx).takeLast(60)), temperatura = 0.9) ?: return emptyList()
        return lerTemas(json)
    }

    fun pedidoDeTemas(jaUsados: List<String>): String =
        "Você é a social media da ${Perfil.loja}, loja online de ${Perfil.ramo} de ${Perfil.cidade}. " +
            "Crie 14 textos curtos para stories do Instagram, metade \"dica\" e metade \"curiosidade\", sobre: autoestima, amor-próprio, " +
            "autocuidado, bem-estar íntimo, relacionamento e comunicação a dois, conhecer o próprio corpo, sono, estresse e libido, " +
            "romance e datas especiais. Tom acolhedor, elegante, inclusivo e sem julgamento, em português do Brasil. " +
            "Regras: nada explícito nem vulgar (regras do Instagram); sem nomes de produtos nem preços; sem promessas de saúde nem " +
            "conselho médico; curiosidades só se forem verdadeiras e bem conhecidas (na dúvida, faça uma dica). " +
            "Título com até 45 caracteres; texto com até 170 caracteres; no máximo 1 emoji por texto. " +
            "Para cada um, uma pergunta de sim ou não para a enquete do story, com até 40 caracteres (ex.: \"Você já sabia disso?\", \"Já fez isso hoje?\"). " +
            (if (jaUsados.isNotEmpty()) "Não repita estes títulos: ${jaUsados.joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"temas\":[{\"tipo\":\"dica|curiosidade\",\"titulo\":\"...\",\"texto\":\"...\",\"enquete\":\"...\"}]}"

    fun lerTemas(json: String): List<Tema> {
        val o = try {
            val i = json.indexOf('{')
            val f = json.lastIndexOf('}')
            if (i < 0 || f < i) return emptyList()
            JSONObject(json.substring(i, f + 1))
        } catch (e: Exception) {
            return emptyList()
        }
        val a = o.optJSONArray("temas") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
            val titulo = it.optString("titulo").trim().take(60)
            val texto = it.optString("texto").trim().take(220)
            val tipo = if (it.optString("tipo").contains("curio", true)) "curiosidade" else "dica"
            val enquete = it.optString("enquete").trim().take(60).let { q -> if (q.isNotBlank() && !q.endsWith("?")) "$q?" else q }
            if (titulo.length < 4 || texto.length < 20) null else Tema(tipo, titulo, texto, enquete)
        }
    }

    /** Textos de reserva (sem Gemini). */
    val RESERVA = listOf(
        Tema("dica", "Comece pelo espelho", "Antes de sair, diga a si mesma uma qualidade que você admira em você. Autoestima também se treina, um dia de cada vez ✨"),
        Tema("curiosidade", "O abraço que acalma", "Abraços longos e carinho liberam ocitocina, o hormônio ligado ao vínculo e à sensação de segurança."),
        Tema("dica", "Converse sem pressa", "Falar sobre desejos e limites fora do quarto, num momento tranquilo, deixa a intimidade mais leve e cheia de confiança."),
        Tema("dica", "Seu corpo, seu tempo", "Não existe jeito certo de sentir prazer. Conhecer o próprio corpo, sem pressa e sem cobrança, é um ato de amor-próprio 💗"),
        Tema("curiosidade", "Dormir bem é autocuidado", "Noites mal dormidas mexem com o humor, a energia e até a vontade. Cuidar do sono também é cuidar do bem-estar íntimo."),
        Tema("dica", "Um ritual só seu", "Banho demorado, uma música que você ama, um creme cheiroso. Separar um momento só para você muda o seu dia."),
        Tema("curiosidade", "Riso aproxima", "Casais que riem juntos costumam se sentir mais próximos. Bom humor também é parte do romance."),
        Tema("dica", "Elogie de verdade", "Um elogio sincero e específico vale mais que mil genéricos. Que tal elogiar alguém (ou você mesma) hoje?"),
        Tema("dica", "Lingerie é para você", "Usar algo bonito por baixo da roupa, só porque você gosta, é um lembrete diário de que você merece se sentir incrível."),
        Tema("curiosidade", "Estresse e desejo", "O estresse do dia a dia pode diminuir a libido. Respirar fundo, desacelerar e se cuidar ajudam o corpo a relaxar."),
        Tema("dica", "Surpreenda no simples", "Um bilhete, uma mensagem carinhosa no meio do dia ou um jantar diferente: pequenas surpresas mantêm a chama acesa 🔥"),
        Tema("dica", "Respeite seus limites", "Dizer não também é amor-próprio. Intimidade boa é aquela em que as duas pessoas se sentem seguras e à vontade."),
        Tema("curiosidade", "Toque que conecta", "A pele é o maior órgão do corpo e é cheia de terminações nervosas. Carinho sem pressa é uma forma poderosa de conexão."),
        Tema("dica", "Desconecte para conectar", "Que tal uma noite sem celular? Atenção total ao outro (ou a você mesma) muda tudo."),
        Tema("dica", "Celebre seu corpo", "Troque a crítica no espelho por gratidão: pelo que seu corpo faz, sente e vive todos os dias."),
        Tema("curiosidade", "Perfume e memória", "O olfato é muito ligado às emoções e às lembranças. Um perfume especial pode virar a marca de um momento a dois."),
    )

    // ------------------------------------------------------------------ arte 1080×1920 com a cara da loja
    fun arte(ctx: Context, tema: Tema, espacoEnquete: Boolean = false): ByteArray {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tela = Canvas(bmp)
        val suave = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // fundo: preto, com a foto de um produto da loja bem desfocada e escura (identidade sem "anúncio")
        tela.drawColor(Color.BLACK)
        fotoDeFundo(ctx)?.let { foto ->
            val cobre = maxOf(w.toFloat() / foto.width, h.toFloat() / foto.height)
            val fw = foto.width * cobre
            val fh = foto.height * cobre
            val mini = Bitmap.createBitmap(27, 48, Bitmap.Config.ARGB_8888)
            Canvas(mini).apply {
                scale(27f / w, 48f / h)
                drawBitmap(foto, null, RectF((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2), suave)
            }
            suave.alpha = 150
            tela.drawBitmap(mini, null, Rect(0, 0, w, h), suave)
            suave.alpha = 255
            tela.drawColor(Color.argb(170, 0, 0, 0))
        }
        // brilho neon rosa em cima e lilás embaixo
        val brilho = Paint(Paint.ANTI_ALIAS_FLAG)
        brilho.shader = RadialGradient(w * 0.8f, h * 0.18f, w * 0.8f, Color.argb(110, 255, 46, 151), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        tela.drawRect(0f, 0f, w.toFloat(), h.toFloat(), brilho)
        brilho.shader = RadialGradient(w * 0.15f, h * 0.9f, w * 0.9f, Color.argb(90, 155, 92, 246), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        tela.drawRect(0f, 0f, w.toFloat(), h.toFloat(), brilho)

        // moldura fina em rosa neon, com halo
        val moldura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(200, 255, 46, 151)
            setShadowLayer(18f, 0f, 0f, ROSA)
        }
        tela.drawRoundRect(RectF(48f, 48f, w - 48f, h - 48f), 22f, 22f, moldura)

        // logo da loja (redondo) no topo
        var y = 190f
        logo(ctx)?.let { l ->
            val lado = 190f
            val r = RectF(w / 2f - lado / 2, y, w / 2f + lado / 2, y + lado)
            tela.save()
            tela.clipPath(android.graphics.Path().apply { addOval(r, android.graphics.Path.Direction.CW) })
            tela.drawBitmap(l, null, r, suave)
            tela.restore()
            tela.drawOval(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = ROSA; setShadowLayer(14f, 0f, 0f, ROSA) })
            y += lado + 70f
        } ?: run { y += 60f }

        // rótulo pequeno em caixa alta espaçada
        val rotulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ROSA
            textSize = 36f
            letterSpacing = 0.32f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(16f, 0f, 0f, Color.argb(160, 255, 46, 151))
        }
        val textoRotulo = if (tema.tipo == "curiosidade") "VOCÊ SABIA?" else "DICA DE BEM-ESTAR"
        tela.drawText(textoRotulo, (w - rotulo.measureText(textoRotulo)) / 2, y, rotulo)
        y += 60f
        val filete = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
        filete.shader = LinearGradient(w / 2f - 120, 0f, w / 2f + 120, 0f, intArrayOf(Color.TRANSPARENT, ROSA, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        tela.drawLine(w / 2f - 120, y, w / 2f + 120, y, filete)

        // título (serifa, grande) e texto, centralizados no meio da tela
        val titulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (tema.titulo.length > 30) 88f else 100f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setShadowLayer(22f, 0f, 0f, Color.argb(120, 255, 46, 151))
        }
        val corpo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 240, 236, 242)
            textSize = 52f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val largura = w - 220
        val lTitulo = StaticLayout.Builder.obtain(tema.titulo, 0, tema.titulo.length, titulo, largura)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
        val lCorpo = StaticLayout.Builder.obtain(tema.texto, 0, tema.texto.length, corpo, largura)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.3f).build()
        val bloco = lTitulo.height + 70 + lCorpo.height
        // com enquete, o texto sobe e sobra espaço livre embaixo para o adesivo
        val topo = if (espacoEnquete) y + 90f else maxOf(y + 110f, (h - bloco) / 2f + 60f)
        tela.save()
        tela.translate(110f, topo)
        lTitulo.draw(tela)
        tela.translate(0f, lTitulo.height + 70f)
        lCorpo.draw(tela)
        tela.restore()

        // rodapé: @ da loja e site
        val usuario = Instagram.usuario(ctx)?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "${Perfil.loja}"
        val arroba = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        }
        tela.drawText(usuario, (w - arroba.measureText(usuario)) / 2, h - 230f, arroba)
        val site = Loja.SITE.removePrefix("https://")
        val pequeno = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 46, 151)
            textSize = 32f
            letterSpacing = 0.08f
        }
        tela.drawText(site, (w - pequeno.measureText(site)) / 2, h - 170f, pequeno)

        val saida = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, saida)
        bmp.recycle()
        return saida.toByteArray()
    }

    /** Uma foto de produto da loja (sorteada), para o fundo. Null sem loja/internet. */
    private fun fotoDeFundo(ctx: Context): Bitmap? = try {
        val loja = Loja(ctx)
        val fotos: List<String> = if (loja.conectada) {
            loja.produtos().filter { it.ativo }.mapNotNull { p -> p.bruto?.optString("imageUrl")?.takeIf { it.isNotBlank() && it != "null" } }
        } else emptyList()
        fotos.randomOrNull()?.let { baixarImagem(Loja.enderecoCompleto(it)) }
    } catch (e: Exception) {
        null
    }

    /** O logo da loja: o escolhido no Catálogo (logo_loja.png) ou o do site, baixado uma vez e guardado. */
    private fun logo(ctx: Context): Bitmap? {
        val arquivo = File(ctx.filesDir, "logo_loja.png")
        if (!arquivo.exists() || arquivo.length() == 0L) {
            try {
                val c = URL(Loja.enderecoCompleto(LOGO)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                if (c.responseCode in 200..299) c.inputStream.use { entrada -> arquivo.outputStream().use { entrada.copyTo(it) } }
                c.disconnect()
            } catch (e: Exception) {
                return null
            }
        }
        return BitmapFactory.decodeFile(arquivo.absolutePath)
    }

    private const val LOGO = "/_cdn/static/5fca2697-82ee-406c-9472-4a112f72f1b7-C-pia-de-C-pia-de-Logotipo-Boutique_20261026_013613_0000.png"

    private fun baixarImagem(url: String): Bitmap? {
        // foto do catálogo da Vênus (loja sem site): já está no celular
        if (url.startsWith("file://")) return BitmapFactory.decodeFile(url.removePrefix("file://"))
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        } finally {
            c.disconnect()
        }
    }
}

/** Alarme do story do dia (e reagenda ao ligar o celular ou atualizar o app). */
class AlarmeDoStory : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                StoriesDoDia.agendar(ctx)
                StoriesDoDia.recuperarAtrasado(ctx)
            }
            StoriesDoDia.ACAO_LEMBRETE, StoriesDoDia.ACAO_MARCAR -> {
                val id = intent.getIntExtra("id", 0)
                if (id <= 0) return
                val lembrete = intent.action == StoriesDoDia.ACAO_LEMBRETE
                val pendente = goAsync()
                Thread {
                    try {
                        val loja = Loja(ctx)
                        if (lembrete) loja.lembrarStory(id)
                        else {
                            val por = loja.marcarStory(id, "instagram")
                            Diario.anotar(ctx, "instagram", "Story com enquete marcado como publicado ($por)", "voz")
                            ctx.getSystemService(NotificationManager::class.java).cancel(74_310)
                        }
                    } catch (e: Exception) {
                        Registro.anotar(ctx, "erro", "story no painel → ${e.message}")
                    } finally {
                        pendente.finish()
                    }
                }.start()
            }
            StoriesDoDia.ACAO_RECUPERAR -> {
                val pendente = goAsync()
                Thread {
                    try { StoriesDoDia.recuperarAtrasado(ctx) } finally { pendente.finish() }
                }.start()
            }
            else -> if (StoriesDoDia.ligado(ctx)) StoriesDoDia.disparou(ctx, goAsync())
        }
    }
}

/** Publica o story do dia em segundo plano, quando tiver internet. */
class PublicadorDeStory : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val turno = params.extras.getString("turno") ?: "manha"
        getSharedPreferences("stories_dia", MODE_PRIVATE).edit().putLong("trabalho_em", System.currentTimeMillis()).apply()
        Thread {
            try {
                StoriesDoDia.publicarEAvisar(applicationContext, turno)
            } catch (e: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
