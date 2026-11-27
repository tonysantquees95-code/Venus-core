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
import com.tonimarques.sextafeira.Caderninho.Fiado
import com.tonimarques.sextafeira.Caderninho.aReceber
import com.tonimarques.sextafeira.Caderninho.adicionar
import com.tonimarques.sextafeira.Caderninho.deJson
import com.tonimarques.sextafeira.Caderninho.paraJson
import com.tonimarques.sextafeira.Caderninho.pagar

/** A parte do caderninho que fala com o Android: guarda no celular e avisa no dia do vencimento. A lógica pura está em [Caderninho]. */
internal object CaderninhoNoCelular {
    private const val CANAL = "caderninho"
    const val ACAO_ALARME = "com.tonimarques.sextafeira.CADERNINHO_VENCE"

    private fun arquivo(loja: String) = "caderninho_" + loja.filter { it.isLetterOrDigit() }.ifBlank { "p" }

    /** [loja] = a loja de onde o alarme saiu (vazio = a principal); sem nada, a loja aberta agora. */
    @Synchronized
    fun ler(c: Context, loja: String = Lojas.aberta(c)): List<Fiado> = deJson(c.getSharedPreferences(arquivo(loja), Context.MODE_PRIVATE).getString("lista", "[]"))

    @Synchronized
    fun gravar(c: Context, lista: List<Fiado>) { c.getSharedPreferences(arquivo(Lojas.aberta(c)), Context.MODE_PRIVATE).edit().putString("lista", paraJson(lista)).apply() }

    /** Anota um fiado novo e agenda o aviso do dia. */
    @Synchronized
    fun anotar(c: Context, f: Fiado): Fiado {
        val nova = adicionar(ler(c), f)
        gravar(c, nova)
        val salvo = nova.last { it.criado == f.criado && it.numero == f.numero }
        agendar(c, salvo)
        return salvo
    }

    /** Marca como pago de verdade (guardado e sem aviso do dia). Null se não existe ou já estava pago: nunca conta duas vezes. */
    @Synchronized
    fun darBaixa(c: Context, id: Int, agora: Long = System.currentTimeMillis()): Fiado? {
        val r = pagar(ler(c), id, agora) ?: return null
        gravar(c, r.first)
        cancelar(c, r.second)
        c.getSystemService(NotificationManager::class.java)?.cancel(9500 + id)
        return r.second
    }

    private fun intencao(c: Context, f: Fiado) = PendingIntent.getBroadcast(
        c, 9100 + f.id, Intent(c, AvisoDoCaderninho::class.java).setAction(ACAO_ALARME).putExtra("id", f.id).putExtra("loja", Lojas.aberta(c)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun agendar(c: Context, f: Fiado, agora: Long = System.currentTimeMillis()) {
        if (f.pago || f.venceEm <= agora) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = intencao(c, f)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.venceEm, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.venceEm, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.venceEm, pi) }
    }

    fun cancelar(c: Context, f: Fiado) { c.getSystemService(AlarmManager::class.java)?.cancel(intencao(c, f)) }

    /** Depois de reiniciar o celular ou atualizar o app: refaz os avisos (só da loja aberta; as outras refazem quando forem abertas). */
    fun reagendarTodos(c: Context) { for (f in aReceber(ler(c))) agendar(c, f) }

    internal fun disparou(c: Context, id: Int, loja: String = Lojas.aberta(c)) {
        val f = ler(c, loja).firstOrNull { it.id == id } ?: return
        if (f.pago) return
        val gerente = c.getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(NotificationChannel(CANAL, "Caderninho", NotificationManager.IMPORTANCE_HIGH).apply { description = "Aviso no dia em que o cliente combinou de pagar" })
        val abrir = PendingIntent.getActivity(
            c, 9500 + f.id, Intent(c, CaixaActivity::class.java).putExtra("receber", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CANAL).setSmallIcon(R.drawable.ic_venus_simbolo)
            .setContentTitle("Hoje vence no caderninho")
            .setContentText("${f.cliente} combinou de pagar ${Documentos.dinheiro(f.total)} hoje. Toque para cobrar.")
            .setContentIntent(abrir).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).build()
        try { gerente.notify(9500 + f.id, n) } catch (e: SecurityException) { }
    }
}

/** Recebe o alarme do dia do vencimento (e refaz os alarmes depois de reiniciar o celular ou atualizar o app). */
class AvisoDoCaderninho : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> CaderninhoNoCelular.reagendarTodos(ctx)
            else -> CaderninhoNoCelular.disparou(ctx, intent.getIntExtra("id", 0), intent.getStringExtra("loja") ?: Lojas.aberta(ctx))
        }
    }
}
