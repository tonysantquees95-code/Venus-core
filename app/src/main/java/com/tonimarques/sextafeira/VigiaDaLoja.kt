package com.tonimarques.sextafeira

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.os.Build

/**
 * Avisos automáticos da loja: a cada ~15 minutos (o mínimo que o Android permite em segundo plano)
 * a Vênus olha a loja e manda uma notificação quando entra um pedido pago ou um produto fica acabando.
 * Usa o mesmo login de administrador dos ajustes. Nada sai do celular além da consulta ao site.
 */
class VigiaDaLoja : JobService() {

    @Volatile
    private var parar = false

    override fun onStartJob(params: JobParameters): Boolean {
        parar = false
        Thread {
            try {
                verificar(this)
            } catch (e: Exception) {
                // sem internet ou site fora do ar: tenta de novo na próxima rodada
            }
            if (!parar) jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        parar = true
        return true
    }

    /** O que mudou desde a última olhada. */
    data class Novidades(
        val pedidosPagos: List<Loja.Pedido>,
        val acabando: List<Loja.Produto>,
        val pagosVistos: Set<Int>,
        val baixosVistos: Set<Int>,
    )

    companion object {
        private const val JOB_ID = 4711
        private const val CANAL = "loja_avisos"
        private const val PREFS = "loja_avisos"
        const val PREF_LIGADO = "ligado"
        private const val P_PAGOS = "pagos"
        private const val P_BAIXOS = "baixos"
        private const val P_INICIADO = "iniciado"
        private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")

        fun ligado(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_LIGADO, false)

        /** Liga ou desliga os avisos. Ao ligar, a primeira olhada só registra o que já existe (sem avisar o que é antigo). */
        fun definir(c: Context, ligar: Boolean) {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val agendador = c.getSystemService(JobScheduler::class.java)
            if (!ligar) {
                prefs.edit().clear().apply()
                agendador.cancel(JOB_ID)
                return
            }
            prefs.edit().putBoolean(PREF_LIGADO, true).remove(P_INICIADO).apply()
            agendar(c)
        }

        /** Garante que está agendado (chamado ao abrir o app, caso o Android tenha esquecido). */
        fun garantir(c: Context) {
            if (!ligado(c)) return
            val agendador = c.getSystemService(JobScheduler::class.java)
            if (agendador.allPendingJobs.none { it.id == JOB_ID }) agendar(c)
        }

        private fun agendar(c: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(c, VigiaDaLoja::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            c.getSystemService(JobScheduler::class.java).schedule(info)
        }

        fun verificar(c: Context) {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_LIGADO, false)) return
            val loja = Loja(c)
            if (!loja.conectada) return
            val pedidos = loja.pedidos()
            val produtos = loja.produtos()
            val primeira = !prefs.getBoolean(P_INICIADO, false)
            val n = novidades(
                pedidos, produtos,
                ler(prefs.getString(P_PAGOS, "")), ler(prefs.getString(P_BAIXOS, "")),
            )
            prefs.edit()
                .putString(P_PAGOS, n.pagosVistos.joinToString(","))
                .putString(P_BAIXOS, n.baixosVistos.joinToString(","))
                .putBoolean(P_INICIADO, true)
                .apply()
            if (primeira) return
            for (p in n.pedidosPagos.take(5)) avisar(c, 1000 + p.id, "Pedido #${p.id} pago", textoDoPedido(p))
            if (n.acabando.isNotEmpty()) avisar(c, 900, "Estoque acabando", textoDoEstoque(n.acabando))
        }

        private fun ler(s: String?): Set<Int> = s.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

        /**
         * Compara a loja agora com o que já foi visto.
         * Pedido pago novo: pago e ainda não avisado. Estoque: avisa ao entrar na faixa baixa (≤ 2) ou esgotar;
         * quando o produto é reposto ele sai da lista e pode avisar de novo no futuro.
         */
        fun novidades(pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, pagosVistos: Set<Int>, baixosVistos: Set<Int>): Novidades {
            val pagos = pedidos.filter { it.status in PAGOS }
            val novosPagos = pagos.filter { it.id !in pagosVistos }.sortedBy { it.id }
            val idsPagos = pagos.map { it.id }.toSet()
            // guarda só os pedidos ainda na lista do site (evita a lista crescer para sempre)
            val vistos = (pagosVistos.filter { it in idsPagos } + idsPagos).toSet()

            val baixos = produtos.filter { it.ativo && it.estoque != null && it.estoque <= Loja.ESTOQUE_BAIXO }
            val idsBaixos = baixos.map { it.id }.toSet()
            val novosBaixos = baixos.filter { it.id !in baixosVistos }
            return Novidades(novosPagos, novosBaixos, vistos, idsBaixos)
        }

        fun textoDoPedido(p: Loja.Pedido): String {
            val itens = p.itens.joinToString(", ") { "${it.quantidade}x ${it.nome}" }
            val entrega = if (p.entrega == "pickup") "retirada (agendar pelo WhatsApp)" else "envio pelos Correios"
            val nome = p.cliente.trim().split(" ").firstOrNull().orEmpty()
            return "${Loja.dinheiro(p.totalCentavos)}${if (nome.isNotBlank()) " · $nome" else ""} · $entrega. $itens"
        }

        fun textoDoEstoque(produtos: List<Loja.Produto>): String =
            produtos.take(6).joinToString("; ") { p ->
                if ((p.estoque ?: 0) <= 0) "${p.nome}: esgotado" else "${p.nome}: ${p.estoque} un."
            } + if (produtos.size > 6) " e mais ${produtos.size - 6}." else "."

        private fun avisar(c: Context, id: Int, titulo: String, texto: String) {
            val gerente = c.getSystemService(NotificationManager::class.java)
            gerente.createNotificationChannel(
                NotificationChannel(CANAL, "Avisos da loja", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pedidos pagos e produtos acabando na Desejo de Intimidade"
                }
            )
            val abrir = PendingIntent.getActivity(
                c, id, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val aviso = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CANAL) else @Suppress("DEPRECATION") Notification.Builder(c))
                .setSmallIcon(R.drawable.ic_venus_simbolo)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText(texto))
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            try {
                gerente.notify(id, aviso)
            } catch (e: SecurityException) {
                // sem permissão de notificação
            }
        }
    }
}
