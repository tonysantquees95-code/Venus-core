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
                StoriesDoDia.recuperarAtrasado(applicationContext)
            } catch (e: Exception) {
            }
            try {
                verificar(this)
            } catch (e: Exception) {
                // sem internet ou site fora do ar: tenta de novo na próxima rodada
            }
            try {
                // rede de segurança dos pedidos da página Publicações (o normal é o alarme de 2 minutos)
                PedidosDoSite.conferir(applicationContext)
            } catch (e: Exception) {
            }
            try {
                // horários marcados pelos clientes na agenda de serviços
                AgendaAvisos.conferir(applicationContext)
            } catch (e: Exception) {
            }
            try {
                // vendas pagas vão para o caixa do Vyntem (só se a pessoa ligou)
                CaixaDoVyntem.conferir(applicationContext)
            } catch (e: Exception) {
            }
            try {
                // rede de segurança das cobranças por link (loja sem site)
                Cobranca.conferirPendentes(applicationContext)
            } catch (e: Exception) {
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
        private const val P_ULTIMA = "ultima_verificacao"
        const val PREF_RESUMO = "resumo_noite"
        const val PREF_SEMANAL = "relatorio_semanal"
        private const val P_SEMANAL_DIA = "semanal_dia"
        private const val P_ESQUECIDAS = "retiradas_avisadas"

        fun semanalLigado(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SEMANAL, true)

        fun definirSemanal(c: Context, ligar: Boolean) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SEMANAL, ligar).apply()
        }
        private const val P_RESUMO_DIA = "resumo_dia"

        fun resumoLigado(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_RESUMO, true)

        fun definirResumo(c: Context, ligar: Boolean) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_RESUMO, ligar).apply()
        }
        private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")

        fun ligado(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_LIGADO, false)

        /** Liga ou desliga os avisos. Ao ligar, a primeira olhada só registra o que já existe (sem avisar o que é antigo). */
        fun definir(c: Context, ligar: Boolean) {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val agendador = c.getSystemService(JobScheduler::class.java)
            if (!ligar) {
                val resumo = prefs.getBoolean(PREF_RESUMO, true)
                prefs.edit().clear().putBoolean(PREF_RESUMO, resumo).apply()
                agendador.cancel(JOB_ID)
                return
            }
            prefs.edit().putBoolean(PREF_LIGADO, true).remove(P_INICIADO).apply()
            agendar(c)
        }

        /** Garante que está agendado (chamado ao abrir o app, caso o Android tenha esquecido). */
        fun garantir(c: Context) {
            // também serve de rede de segurança dos stories: a cada 15 minutos confere se algum ficou para trás
            if (!ligado(c) && !StoriesDoDia.ligado(c) && !PedidosDoSite.ativo(c) && !AgendaAvisos.ativo(c) && !CaixaDoVyntem.ligado(c) && Cobranca.pendentes(c).isEmpty()) return
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

        /**
         * Olha pedidos pagos e estoque. Para usar pouco o site: no máximo a cada 30 min de dia e 1 h de noite
         * (o aviso de pedido pago pelo canal ntfy chama com [forcar]).
         */
        fun verificar(c: Context, forcar: Boolean = false) {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_LIGADO, false)) return
            val loja = Loja(c)
            if (!loja.conectada) return
            val agoraMs = System.currentTimeMillis()
            val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val espera = if (hora in 8..22) 30 * 60_000L else 60 * 60_000L
            if (!forcar && agoraMs - prefs.getLong(P_ULTIMA, 0L) < espera - 60_000L) return
            prefs.edit().putLong(P_ULTIMA, agoraMs).apply()
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
            // resumo da noite: uma vez por dia, a partir das 21h
            val agora = java.util.Calendar.getInstance()
            val hoje = "%04d-%02d-%02d".format(agora.get(java.util.Calendar.YEAR), agora.get(java.util.Calendar.MONTH) + 1, agora.get(java.util.Calendar.DAY_OF_MONTH))
            if (prefs.getBoolean(PREF_RESUMO, true) && agora.get(java.util.Calendar.HOUR_OF_DAY) >= 21 && prefs.getString(P_RESUMO_DIA, "") != hoje) {
                prefs.edit().putString(P_RESUMO_DIA, hoje).apply()
                avisar(c, 901, "Resumo da loja de hoje", Loja.montarResumoDoDia(pedidos, produtos, agora) + (Gestao.avisoDeData(agora)?.let { " $it" } ?: ""))
            }
            // relatório semanal: segunda-feira, a partir das 9h
            if (prefs.getBoolean(PREF_SEMANAL, true) && agora.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.MONDAY &&
                agora.get(java.util.Calendar.HOUR_OF_DAY) >= 9 && prefs.getString(P_SEMANAL_DIA, "") != hoje
            ) {
                prefs.edit().putString(P_SEMANAL_DIA, hoje).apply()
                avisar(c, 902, "Relatório da semana", Loja.montarRelatorioSemanal(pedidos, produtos, agora))
            }
            // retirada paga há mais de 2 dias e ainda não entregue: lembra de agendar (uma vez por pedido)
            val jaAvisadas = ler(prefs.getString(P_ESQUECIDAS, ""))
            val esquecidas = Loja.retiradasEsquecidas(pedidos, agora.timeInMillis).filter { it.id !in jaAvisadas }
            if (esquecidas.isNotEmpty() && !primeira) {
                avisar(
                    c, 903, "Retirada sem agendar",
                    esquecidas.take(4).joinToString("; ") { "Pedido #${it.id} de ${it.cliente.trim().split(" ").firstOrNull().orEmpty()}" } +
                        ". Pagos há mais de 2 dias. Diga: agendar retirada do pedido, e o número.",
                )
            }
            if (esquecidas.isNotEmpty() || primeira) {
                val ids = (jaAvisadas + Loja.retiradasEsquecidas(pedidos, agora.timeInMillis).map { it.id }).filter { id -> pedidos.any { it.id == id } }
                prefs.edit().putString(P_ESQUECIDAS, ids.joinToString(",")).apply()
            }
            if (primeira) return
            for (p in n.pedidosPagos.take(5)) avisar(c, 1000 + p.id, "Pedido #${p.id} pago", textoDoPedido(p))
            if (n.acabando.isNotEmpty()) avisar(c, 900, "Estoque acabando", textoDoEstoqueComPrevisao(pedidos, produtos, n.acabando))
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

        fun textoDoEstoqueComPrevisao(pedidos: List<Loja.Pedido>, produtos: List<Loja.Produto>, acabando: List<Loja.Produto>): String {
            val ritmo = Loja.ritmoDeVendas(pedidos, produtos, java.util.Calendar.getInstance())
            return acabando.take(6).joinToString("; ") { p ->
                val r = ritmo[p.id] ?: 0.0
                val base = if ((p.estoque ?: 0) <= 0) "${p.nome}: esgotado" else "${p.nome}: ${p.estoque} un."
                if (r > 0 && (p.estoque ?: 0) > 0) "$base (acaba em ~${maxOf(1, ((p.estoque ?: 0) / r).toInt())} dias)" else base
            } + if (acabando.size > 6) " e mais ${acabando.size - 6}." else "."
        }

        fun textoDoEstoque(produtos: List<Loja.Produto>): String =
            produtos.take(6).joinToString("; ") { p ->
                if ((p.estoque ?: 0) <= 0) "${p.nome}: esgotado" else "${p.nome}: ${p.estoque} un."
            } + if (produtos.size > 6) " e mais ${produtos.size - 6}." else "."

        internal fun avisar(c: Context, id: Int, titulo: String, texto: String) {
            val gerente = c.getSystemService(NotificationManager::class.java)
            gerente.createNotificationChannel(
                NotificationChannel(CANAL, "Avisos da loja", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pedidos pagos e produtos acabando na ${Perfil.loja}"
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
