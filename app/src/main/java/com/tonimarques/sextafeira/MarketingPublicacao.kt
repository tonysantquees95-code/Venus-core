package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Central de Marketing: editor de postagem, exclusão em lote, destinos de publicação (Instagram/webhook) e registro de envios. */

/** "#Vela, casa  #vela" → [vela, casa] (sem #, sem repetição, até 12). */
fun hashtagsDoTexto(texto: String): List<String> =
    texto.split(Regex("[\\s,;]+")).map { it.trim().trimStart('#').lowercase() }
        .filter { it.length in 2..40 && it.all { c -> c.isLetterOrDigit() || c == '_' } }.distinct().take(12)

fun textoDeHashtags(tags: List<String>) = tags.joinToString(" ") { "#$it" }

/** Rótulo curto do estado de uma postagem na fila. */
fun estadoDaPostagem(status: String, agendadoPara: Long, erro: String, publicadoEm: Long): String {
    val f = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
    return when (status) {
        "agendado" -> "AGENDADA · ${f.format(Date(agendadoPara))}"
        "publicando" -> "PUBLICANDO…"
        "publicado" -> "PUBLICADA · ${f.format(Date(publicadoEm))}"
        "erro" -> "ERRO NO ENVIO" + if (erro.isNotBlank()) " · $erro" else ""
        else -> "SUGESTÃO"
    }
}

private fun JSONArray?.itens(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun MainActivity.editarPostagem(p: JSONObject, depois: () -> Unit) {
    val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
    fun campo(dica: String, valor: String, multilinha: Boolean = false) = EditText(this).apply {
        hint = dica; setText(valor)
        inputType = InputType.TYPE_CLASS_TEXT or if (multilinha) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        if (multilinha) { minLines = 3; maxLines = 8 } else setSingleLine()
        coluna.addView(this)
    }
    val tags = (p.optJSONArray("hashtags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
    val legenda = campo("Legenda", p.optString("legenda"), true)
    val chamada = campo("Chamada (copy)", p.optString("copy"))
    val hashtags = campo("Hashtags (separe com espaço)", textoDeHashtags(tags), true)
    val imagem = campo("Imagem (endereço https, opcional)", p.optString("imagem"))
    val id = p.optInt("id")
    fun salvar(aoSalvar: () -> Unit) {
        val corpo = JSONObject().put("legenda", legenda.text.toString().trim()).put("copy", chamada.text.toString().trim())
            .put("hashtags", JSONArray(hashtagsDoTexto(hashtags.text.toString()))).put("imagem", imagem.text.toString().trim())
        pedirNuvem("POST", "/marketing/fila/$id/editar", corpo) { aoSalvar() }
    }
    CaixaBuilder(this).setTitle(p.optString("produto_nome").ifBlank { "Editar postagem" }).setView(android.widget.ScrollView(this).apply { addView(coluna) })
        .setPositiveButton("Salvar") { _, _ -> salvar { aviso("Postagem atualizada."); depois() } }
        .setNeutralButton("Aprovar e agendar") { _, _ -> salvar { agendarPostagem(id, ajustesDeMarketing) } }
        .setNegativeButton("Cancelar", null).show()
}

/** Tela de marketing aberta no momento (para reabrir depois de uma ação). */
internal var ajustesDeMarketing: TelaDeAjustes? = null

internal fun MainActivity.excluirEmLote(fila: List<JSONObject>, depois: () -> Unit) {
    val editaveis = fila.filter { it.optString("status") != "publicando" }
    if (editaveis.isEmpty()) return aviso("Não há postagens para excluir.")
    val marcadas = BooleanArray(editaveis.size)
    CaixaBuilder(this).setTitle("Excluir postagens")
        .setMultiChoiceItems(editaveis.map { it.optString("copy").ifBlank { it.optString("produto_nome") }.take(60) }.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
        .setPositiveButton("Excluir selecionadas") { _, _ ->
            val ids = JSONArray(editaveis.filterIndexed { i, _ -> marcadas[i] }.map { it.optInt("id") })
            if (ids.length() == 0) aviso("Nada selecionado.")
            else pedirNuvem("POST", "/marketing/excluir", JSONObject().put("ids", ids)) { r -> aviso("${r.optInt("removidos")} excluídas."); depois() }
        }
        .setNegativeButton("Cancelar", null).show()
}

internal fun MainActivity.publicarAgora(p: JSONObject, depois: () -> Unit) {
    aviso("Publicando…")
    emFundo({ Nuvem.pedir(this, "POST", "/marketing/fila/${p.optInt("id")}/publicar", JSONObject()) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { resp ->
            when {
                !resp.ok -> aviso(resp.erro())
                resp.json.optBoolean("ok") -> aviso("Publicada! ${resp.json.optString("detalhe")}")
                else -> aviso("Não saiu: ${resp.json.optString("erro")}")
            }
            depois()
        }
    }
}

internal fun MainActivity.abrirDestinos() {
    pedirNuvem("GET", "/marketing/destinos") { j ->
        val conectado = j.optJSONArray("destinos").itens().associate { it.optString("tipo") to it.optBoolean("conectado") }
        fun marca(t: String) = if (conectado[t] == true) "✓ conectado" else "não conectado"
        escolher("Onde publicar", listOf(
            "Instagram (${marca("instagram")})" to {
                perguntar("Instagram (API oficial da Meta)", listOf("ID da conta profissional" to "", "Token de acesso" to "")) { v ->
                    pedirNuvem("PUT", "/marketing/destinos/instagram", JSONObject().put("igUserId", v[0]).put("token", v[1])) { aviso("Instagram conectado.") }
                }
            },
            "Webhook / Vênus Robô (${marca("webhook")})" to {
                perguntar("Webhook (https)", listOf("Endereço do webhook" to "")) { v ->
                    pedirNuvem("PUT", "/marketing/destinos/webhook", JSONObject().put("url", v[0])) { r ->
                        val seg = r.optString("segredo")
                        CaixaBuilder(this).setTitle("Webhook conectado").setMessage("Cada envio vem assinado (cabeçalho x-venus-assinatura, HMAC-SHA256 do corpo) com este segredo. Guarde-o no Robô:\n\n$seg")
                            .setPositiveButton("Copiar segredo") { _, _ -> copiar("Segredo do webhook", seg) }.setNegativeButton("Fechar", null).show()
                    }
                }
            },
            "Desconectar Instagram" to { pedirNuvem("DELETE", "/marketing/destinos/instagram") { aviso("Instagram desconectado.") } },
            "Desconectar webhook" to { pedirNuvem("DELETE", "/marketing/destinos/webhook") { aviso("Webhook desconectado.") } },
        ))
    }
}

internal fun MainActivity.abrirRegistro() {
    pedirNuvem("GET", "/marketing/log") { j ->
        val f = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
        val itens = j.optJSONArray("log").itens().map { l ->
            val rotulo = when (l.optString("status")) { "publicado" -> "✓ Publicada"; "erro" -> "✗ Erro"; else -> "↻ Vai tentar de novo" }
            TelaDeAjustes.Ajuste("$rotulo · post ${l.optInt("post")}", { f.format(Date(l.optLong("quando"))) + "\n" + l.optString("detalhe") }) { }
        }
        telaDeLista("REGISTRO DE ENVIOS", listOf(TelaDeAjustes.Secao("Últimos envios", if (itens.isEmpty()) listOf(TelaDeAjustes.Ajuste("Nada enviado ainda", { "Agende uma postagem e conecte o Instagram ou o webhook." }) { }) else itens)))
    }
}
