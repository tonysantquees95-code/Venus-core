package com.tonimarques.venusrobo

import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * Lê as regras de venda que o dono preencheu na Vênus (pagamento, frete, retirada…), pelo provedor protegido
 * por assinatura. As respostas prontas dessas perguntas saem daqui, exatas, sem passar pela IA.
 * Se a Vênus não estiver instalada (ou for antiga), devolve null e o robô pergunta à Vênus da loja no site.
 */
object RegrasDaVenus {

    private val ENDERECO: Uri = Uri.parse("content://com.tonimarques.sextafeira.regras")

    data class Regras(val loja: String, val texto: String, val respostas: Map<String, String>, val configuradas: Boolean)

    fun ler(ctx: Context): Regras? = try {
        ctx.contentResolver.query(ENDERECO, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            fun col(nome: String) = c.getColumnIndex(nome).takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
            Regras(col("loja"), col("texto"), lerRespostas(col("perguntas")), col("configuradas") == "1")
        }
    } catch (e: Exception) {
        null // sem permissão (robô instalado antes da Vênus nova) ou Vênus ausente
    }

    /** [{"id":"frete","pergunta":"…","resposta":"…"}] → id → resposta. */
    fun lerRespostas(json: String): Map<String, String> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .mapNotNull { o -> o.optString("id").takeIf { it.isNotBlank() }?.let { it to o.optString("resposta") } }
            .filter { it.second.isNotBlank() }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}
