package com.tonimarques.sextafeira.robo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * O ouvido do robô: quando chega mensagem de uma cliente no WhatsApp Business ou no Instagram, pega o texto
 * da notificação, espera uns segundos (se ela mandar várias mensagens seguidas, junta tudo), pergunta à
 * Vênus da loja e responde pelo botão "Responder" da própria notificação.
 * Não responde em grupos, nem mídia (foto, áudio), nem as próprias mensagens.
 * Quando a Vênus passa a conversa para a loja, avisa com uma notificação "cliente esperando você".
 */
class OuvinteRobo : NotificationListenerService() {

    private val principal = Handler(Looper.getMainLooper())

    /** Conversas esperando a pausa de juntar mensagens: chave → (pacote, cliente, textos, ação de responder). */
    private class Pendente(val pacote: String, val cliente: String, val textos: MutableList<String>, var acao: Notification.Action, var trabalho: Runnable? = null)

    private val pendentes = HashMap<String, Pendente>()
    private val vistasEm = HashMap<String, Long>()
    private val minhasRespostas = HashMap<String, MutableList<String>>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            tratar(sbn)
        } catch (e: Exception) {
        }
    }

    private fun tratar(sbn: StatusBarNotification) {
        val pacote = sbn.packageName
        if (pacote !in Robo.NOMES.keys || !Robo.ligado(this) || !Robo.canalLigado(this, pacote)) return
        // período de testes da Vênus encerrado sem código de liberação: o robô também pausa
        if (RegrasDaVenus.ler(this)?.liberada == false) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return
        // grupo do WhatsApp também vem com o nome da conversa (e o nome de quem mandou separado)
        val tituloConversa = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        if (!tituloConversa.isNullOrBlank() && pacote != Robo.INSTAGRAM) return
        val acao = acaoDeResponder(n) ?: return

        // a última mensagem da conversa (no estilo de conversa do Android), senão o texto simples
        var cliente = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        // a conversa é identificada pelo título da notificação (o nome do contato), não por quem mandou a última
        val chat = cliente
        // quem é "eu" nessa conversa (a loja): mensagens dela não são de cliente
        val eu = extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        var texto = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // a hora em que a cliente mandou (vem em cada mensagem da conversa): separa "1" e "1" de novo de uma notificação repetida
        var hora = 0L
        val mensagens = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (mensagens != null && mensagens.isNotEmpty()) {
            val ultima = mensagens.last() as? Bundle
            if (ultima != null) {
                val quem = ultima.getParcelable<android.app.Person>("sender_person")?.name?.toString() ?: ultima.getCharSequence("sender")?.toString()
                // sem remetente, ou o próprio "eu" = mensagem que saiu deste celular (a loja ou o próprio robô)
                if (quem.isNullOrBlank() || (eu != null && quem == eu)) return
                cliente = quem
                texto = ultima.getCharSequence("text")?.toString()
                hora = ultima.getLong("time", 0L)
            }
        }
        val limpo = Robo.textoDaCliente(texto) ?: return
        if (cliente.isBlank()) return
        val conversa = "$pacote|${chat.ifBlank { cliente }}"
        // o próprio robô mandou isso há pouco (o WhatsApp Business mostra a resposta na notificação com o nome da loja)
        if (ehMinhaResposta(limpo)) return
        // notificação reapresentada (mesma mensagem, mesma hora de envio) não conta de novo. Com a hora, a cliente pode
        // mandar o mesmo texto outra vez (ex.: "1" no menu e "1" na lista de categorias) e o robô responde.
        // Sem a hora (app que não manda), só ignora o mesmo texto repetido em até 20 segundos.
        val agora = System.currentTimeMillis()
        val marca = "$conversa|$limpo|$hora"
        vistasEm[marca]?.let { if (hora > 0L || agora - it < 20_000L) return }
        vistasEm[marca] = agora
        if (vistasEm.size > 300) vistasEm.entries.removeAll { agora - it.value > 10 * 60_000L }

        principal.post { agendar(conversa, pacote, cliente, limpo, acao) }
    }

    /** Junta as mensagens que chegarem em até 4 segundos e responde uma vez só. */
    private fun agendar(conversa: String, pacote: String, cliente: String, texto: String, acao: Notification.Action) {
        val p = pendentes.getOrPut(conversa) { Pendente(pacote, cliente, mutableListOf(), acao) }
        if (p.textos.lastOrNull() != texto) p.textos += texto
        p.acao = acao
        p.trabalho?.let { principal.removeCallbacks(it) }
        val r = Runnable {
            pendentes.remove(conversa)
            responderAgora(conversa, p)
        }
        p.trabalho = r
        principal.postDelayed(r, 4_000)
    }

    private fun responderAgora(conversa: String, p: Pendente) {
        val pergunta = p.textos.joinToString("\n")
        Thread {
            val inicio = System.currentTimeMillis()
            val canal = Robo.NOMES[p.pacote] ?: p.pacote
            try {
                val r = Robo.perguntar(this, p.pacote, p.cliente, pergunta)
                if (r.mensagens.isEmpty()) {
                    Robo.anotar(this, Robo.Registro(inicio, canal, p.cliente, pergunta, "(sem resposta: conversa com a loja)", r.humano, false))
                    return@Thread
                }
                val min = r.atrasoMin.coerceIn(0, 12)
                val atraso = (min..r.atrasoMax.coerceIn(min, 12)).random() * 1000L
                val falta = (atraso - (System.currentTimeMillis() - inicio)).coerceAtLeast(500)
                principal.postDelayed({
                    for (m in r.mensagens) enviar(conversa, p.acao, m)
                    if (r.humano) avisarLoja(p.cliente, canal, pergunta)
                }, falta)
                Robo.anotar(this, Robo.Registro(inicio, canal, p.cliente, pergunta, r.mensagens.joinToString("\n"), r.humano, false))
            } catch (e: Exception) {
                Robo.anotar(this, Robo.Registro(inicio, canal, p.cliente, pergunta, e.message ?: "erro", false, true))
            }
        }.start()
    }

    private fun enviar(conversa: String, acao: Notification.Action, texto: String) {
        val entradas = acao.remoteInputs ?: return
        val intent = Intent()
        val dados = Bundle()
        for (e in entradas) dados.putCharSequence(e.resultKey, texto)
        RemoteInput.addResultsToIntent(entradas, intent, dados)
        try {
            acao.actionIntent.send(this, 0, intent)
            val lista = minhasRespostas.getOrPut(conversa) { mutableListOf() }
            lista += texto.trim()
            while (lista.size > 5) lista.removeAt(0)
            enviadas += System.currentTimeMillis() to texto.trim()
            while (enviadas.size > 40) enviadas.removeAt(0)
        } catch (e: PendingIntent.CanceledException) {
        }
    }

    /** Respostas que o robô mandou nos últimos 15 minutos, em qualquer conversa. */
    private val enviadas = mutableListOf<Pair<Long, String>>()

    private fun ehMinhaResposta(texto: String): Boolean {
        val limite = System.currentTimeMillis() - 15 * 60_000L
        return enviadas.any { (quando, r) -> quando >= limite && Robo.mesmoTexto(r, texto) }
    }

    private fun acaoDeResponder(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** "Cliente esperando você" quando a Vênus passa a conversa para a loja. */
    private fun avisarLoja(cliente: String, canal: String, pergunta: String) {
        val gerente = getSystemService(NotificationManager::class.java)
        gerente.createNotificationChannel(NotificationChannel(CANAL, "Clientes esperando você", NotificationManager.IMPORTANCE_HIGH))
        val abrir = packageManager.getLaunchIntentForPackage(
            if (canal == "Instagram") Robo.INSTAGRAM else if (canal == "WhatsApp") Robo.WHATSAPP else Robo.WHATSAPP_BUSINESS
        )?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("$cliente está esperando você ($canal)")
            .setContentText(pergunta.take(120))
            .setStyle(Notification.BigTextStyle().bigText("A Vênus passou essa conversa para você e fica 12 horas sem responder essa cliente.\n\n“${pergunta.take(300)}”"))
            .setAutoCancel(true)
        if (abrir != null) b.setContentIntent(abrir)
        try {
            gerente.notify(cliente.hashCode(), b.build())
        } catch (e: SecurityException) {
        }
    }

    companion object {
        private const val CANAL = "esperando"

        fun temAcesso(ctx: Context): Boolean {
            val ativos = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            return ativos.contains(ctx.packageName)
        }
    }
}
