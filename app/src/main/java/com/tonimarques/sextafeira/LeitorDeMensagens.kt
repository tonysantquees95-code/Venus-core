package com.tonimarques.sextafeira

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lê as mensagens novas do WhatsApp (e do WhatsApp Business) pelas notificações e responde por voz.
 * Só olha as notificações do WhatsApp, só quando você pede, e não guarda nada: as mensagens
 * ficam onde já estão (na barra de notificações) e nunca vão para o Gemini.
 */
class LeitorDeMensagens : NotificationListenerService() {

    override fun onListenerConnected() {
        instancia = this
    }

    override fun onListenerDisconnected() {
        if (instancia === this) instancia = null
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        super.onDestroy()
    }

    /** Uma conversa com mensagens não lidas. */
    data class Conversa(val nome: String, val mensagens: List<String>, val grupo: Boolean, val acao: Notification.Action?)

    companion object {
        @Volatile
        private var instancia: LeitorDeMensagens? = null
        private val WHATSAPPS = setOf("com.whatsapp", "com.whatsapp.w4b")

        /** O usuário liberou o "acesso às notificações" para a Vênus? */
        fun liberado(ctx: Context): Boolean {
            val lista = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            val eu = ComponentName(ctx, LeitorDeMensagens::class.java)
            return lista.split(":").any { ComponentName.unflattenFromString(it) == eu }
        }

        fun abrirPermissao(ctx: Context) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** As conversas do WhatsApp que estão nas notificações agora. */
        fun conversas(): List<Conversa>? {
            val s = instancia ?: return null
            val ativas = try {
                s.activeNotifications ?: emptyArray()
            } catch (e: Exception) {
                return null
            }
            return ativas.filter { it.packageName in WHATSAPPS }.sortedByDescending { it.postTime }.mapNotNull { ler(it) }
                .groupBy { it.nome }.map { (_, v) -> v.first().copy(mensagens = v.flatMap { it.mensagens }.distinct()) }
        }

        private fun ler(sbn: StatusBarNotification): Conversa? {
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            // só conversas de verdade: elas têm o botão "Responder"
            val acao = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
            val ex = n.extras
            val conversa = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val titulo = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val grupo = conversa != null || ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val mensagens = mutableListOf<String>()
            @Suppress("DEPRECATION")
            val bundles = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (bundles != null) {
                for (b in bundles) {
                    val m = b as? Bundle ?: continue
                    val texto = m.getCharSequence("text")?.toString()?.trim().orEmpty()
                    if (texto.isBlank()) continue
                    val quem = m.getCharSequence("sender")?.toString()
                    mensagens += if (grupo && !quem.isNullOrBlank()) "$quem: $texto" else texto
                }
            }
            if (mensagens.isEmpty()) {
                val texto = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (texto.isNotBlank()) mensagens += texto
            }
            val nome = limparNome(conversa ?: titulo)
            if (nome.isBlank() || mensagens.isEmpty() || acao == null) return null
            return Conversa(nome, mensagens.takeLast(6), grupo, acao)
        }

        /** "Natália (3 mensagens)" → "Natália"; "Família: Ana" → "Família". */
        fun limparNome(t: String): String = t
            .replace(Regex("\\s*\\(\\d+ (?:mensage(?:m|ns)|messages?)\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*[:‎].*$"), "")
            .trim()

        /** Procura a conversa pelo nome falado ("natalia", "a Natália", "fornecedor"). */
        fun achar(conversas: List<Conversa>, falado: String): Conversa? {
            val chave = ComandosLocais.normalizar(falado).replace(Regex("^(?:o|a|os|as|do|da|pro|pra|para o|para a)\\s+"), "").trim()
            if (chave.isBlank()) return conversas.firstOrNull()
            return conversas.firstOrNull { ComandosLocais.normalizar(it.nome) == chave }
                ?: conversas.firstOrNull { ComandosLocais.normalizar(it.nome).startsWith(chave) }
                ?: conversas.firstOrNull { ComandosLocais.normalizar(it.nome).contains(chave) }
        }

        /** O que falar ao ler as mensagens (todas, ou só de uma conversa). */
        fun montarLeitura(conversas: List<Conversa>, de: String): String {
            if (de.isNotBlank()) {
                val c = achar(conversas, de) ?: return "Não tem mensagem nova de $de."
                return "${c.nome}${if (c.grupo) " (grupo)" else ""} mandou: " + c.mensagens.joinToString(". ") { it.trimEnd('.') } +
                    ". Para responder, diga: responde que, e a mensagem."
            }
            if (conversas.isEmpty()) return "Nenhuma mensagem nova no WhatsApp."
            val partes = conversas.take(5).map { c ->
                val ultimas = c.mensagens.takeLast(3).joinToString(". ") { it.trimEnd('.') }
                "${c.nome}${if (c.grupo) " (grupo)" else ""}: $ultimas"
            }
            val mais = if (conversas.size > 5) " E mais ${conversas.size - 5} conversas." else ""
            val quantas = if (conversas.size == 1) "Uma conversa com mensagem nova." else "${conversas.size} conversas com mensagens novas."
            return "$quantas ${partes.joinToString(". ")}.$mais Para responder, diga: responde para, o nome, e a mensagem."
        }

        /** Responde pela própria notificação (sem abrir o WhatsApp). Devolve false se a conversa sumiu. */
        fun responder(ctx: Context, nome: String, texto: String): Boolean {
            val c = conversas()?.let { achar(it, nome) } ?: return false
            val acao = c.acao ?: return false
            val entradas = acao.remoteInputs ?: return false
            val dados = Bundle()
            for (r in entradas) dados.putCharSequence(r.resultKey, texto)
            val intent = Intent()
            RemoteInput.addResultsToIntent(entradas, intent, dados)
            return try {
                acao.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
