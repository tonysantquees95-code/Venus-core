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
import java.io.File

/**
 * Fila de posts do Instagram: foto, legenda e horário. Na hora marcada chega uma notificação;
 * tocando nela a Vênus copia a legenda e abre o Instagram com a foto pronta.
 * (Quando a conta estiver ligada à API da Meta, a publicação passa a ser automática.)
 */
object Postagens {

    data class Post(val id: Int, val quando: Long, val tipo: String, val legenda: String, val foto: String, val publicado: Boolean = false) {
        val ehStory get() = tipo == "story"
    }

    private const val PREFS = "postagens"
    const val ACAO_POSTAR = "com.tonimarques.sextafeira.POSTAR"
    private const val ACAO_ALARME = "com.tonimarques.sextafeira.ALARME_POST"
    private const val CANAL = "postagens"

    fun pasta(ctx: Context) = File(ctx.filesDir, "posts").apply { mkdirs() }

    private fun ler(ctx: Context): MutableList<Post> {
        val arr = try {
            JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Post(it.optInt("id"), it.optLong("quando"), it.optString("tipo", "feed"), it.optString("legenda"), it.optString("foto"), it.optBoolean("publicado"))
        }.toMutableList()
    }

    private fun gravar(ctx: Context, lista: List<Post>) {
        val arr = JSONArray()
        for (p in lista) arr.put(
            JSONObject().put("id", p.id).put("quando", p.quando).put("tipo", p.tipo).put("legenda", p.legenda).put("foto", p.foto).put("publicado", p.publicado)
        )
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", arr.toString()).apply()
    }

    /** Guarda a foto e agenda o post. */
    fun criar(ctx: Context, quando: Long, tipo: String, legenda: String, jpeg: ByteArray): Post {
        val lista = ler(ctx)
        val id = (lista.maxOfOrNull { it.id } ?: 0) + 1
        val arquivo = File(pasta(ctx), "post_$id.jpg").apply { writeBytes(jpeg) }
        val p = Post(id, quando, tipo, legenda.trim(), arquivo.absolutePath)
        lista += p
        gravar(ctx, lista)
        agendar(ctx, p)
        return p
    }

    fun pendentes(ctx: Context): List<Post> = ler(ctx).filter { !it.publicado }.sortedBy { it.quando }

    fun achar(ctx: Context, id: Int): Post? = ler(ctx).firstOrNull { it.id == id }

    fun marcarPublicado(ctx: Context, id: Int) {
        gravar(ctx, ler(ctx).map { if (it.id == id) it.copy(publicado = true) else it })
        // apaga posts publicados há mais de 7 dias (e as fotos)
        val limite = System.currentTimeMillis() - 7 * 24 * 3600_000L
        val (velhos, ficam) = ler(ctx).partition { it.publicado && it.quando < limite }
        velhos.forEach { File(it.foto).delete() }
        if (velhos.isNotEmpty()) gravar(ctx, ficam)
    }

    fun cancelar(ctx: Context, id: Int): Boolean {
        val lista = ler(ctx)
        val p = lista.firstOrNull { it.id == id } ?: return false
        ctx.getSystemService(AlarmManager::class.java).cancel(intencao(ctx, p))
        File(p.foto).delete()
        gravar(ctx, lista.filter { it.id != id })
        return true
    }

    /** Próximo horário livre sugerido: 19h do dia seguinte ao último post da fila (ou amanhã). */
    fun proximoHorario(ctx: Context, agora: Long = System.currentTimeMillis()): Long {
        val ultimo = pendentes(ctx).maxOfOrNull { it.quando }
        val base = java.util.Calendar.getInstance().apply { timeInMillis = maxOf(agora, ultimo ?: agora) }
        base.add(java.util.Calendar.DAY_OF_YEAR, 1)
        base.set(java.util.Calendar.HOUR_OF_DAY, 19)
        base.set(java.util.Calendar.MINUTE, 0)
        base.set(java.util.Calendar.SECOND, 0)
        base.set(java.util.Calendar.MILLISECOND, 0)
        return base.timeInMillis
    }

    private fun intencao(ctx: Context, p: Post) = PendingIntent.getBroadcast(
        ctx, 7000 + p.id, Intent(ctx, AvisoDePost::class.java).setAction(ACAO_ALARME).putExtra("id", p.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun agendar(ctx: Context, p: Post) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intencao(ctx, p)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.quando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.quando, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.quando, pi)
        }
    }

    fun reagendarTodos(ctx: Context) {
        val agora = System.currentTimeMillis()
        for (p in pendentes(ctx)) {
            if (p.quando > agora) agendar(ctx, p) else avisar(ctx, p, atrasado = true)
        }
    }

    fun avisar(ctx: Context, p: Post, atrasado: Boolean = false) {
        val gerente = ctx.getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(
            NotificationChannel(CANAL, "Posts do Instagram", NotificationManager.IMPORTANCE_HIGH).apply { description = "Hora de publicar os posts programados" }
        )
        val abrir = PendingIntent.getActivity(
            ctx, 8000 + p.id,
            Intent(ctx, MainActivity::class.java).setAction(ACAO_POSTAR).putExtra("id", p.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bitmap = try {
            android.graphics.BitmapFactory.decodeFile(p.foto, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
        } catch (e: Exception) {
            null
        }
        val titulo = (if (atrasado) "Post atrasado" else "Hora de postar") + if (p.ehStory) " (story)" else " no Instagram"
        val b = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_venus_simbolo)
            .setContentTitle(titulo)
            .setContentText("Toque para abrir o Instagram com a foto. " + p.legenda.take(80))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (bitmap != null) b.setLargeIcon(bitmap).setStyle(Notification.BigPictureStyle().bigPicture(bitmap))
        try {
            gerente.notify(8000 + p.id, b.build())
        } catch (e: SecurityException) {
        }
    }

    internal fun disparou(ctx: Context, id: Int) {
        val p = achar(ctx, id) ?: return
        if (!p.publicado) avisar(ctx, p)
    }
}

/** Recebe o alarme do post programado (e reagenda tudo depois de reiniciar o celular). */
class AvisoDePost : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Postagens.reagendarTodos(ctx)
            else -> Postagens.disparou(ctx, intent.getIntExtra("id", 0))
        }
    }
}
