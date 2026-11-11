package com.tonimarques.sextafeira.robo

import android.content.Context
import org.json.JSONArray

/**
 * Lê as regras de venda que o dono preencheu na Vênus (pagamento, frete, retirada…). As respostas prontas dessas perguntas saem daqui, exatas, sem passar pela IA.
 * Se a Vênus não estiver instalada (ou for antiga), devolve null e o robô pergunta à Vênus da loja no site.
 */
object RegrasDaVenus {

    /** [liberada] = false quando o acesso da conta está suspenso: o robô para também. */
    data class Regras(val loja: String, val texto: String, val respostas: Map<String, String>, val configuradas: Boolean, val liberada: Boolean = true)

    /** As regras de venda do Perfil deste mesmo app (nada de provedor entre apps). */
    fun ler(ctx: Context): Regras? = try {
        com.tonimarques.sextafeira.Perfil.carregar(ctx)
        val p = com.tonimarques.sextafeira.Perfil
        val certas = if (p.regrasConfiguradas) p.regras.perguntasComId(p.loja).mapNotNull { (id, _, r) -> r.takeIf { it.isNotBlank() }?.let { id to it } }.toMap() else emptyMap()
        Regras(p.loja, p.regrasDeVenda, certas, p.regrasConfiguradas, com.tonimarques.sextafeira.Licenca.liberada(ctx))
    } catch (e: Exception) {
        null
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
