package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liga a Vênus ao Vyntem (app grátis de finanças): cada venda PAGA e confirmada vira uma entrada no caixa do Vyntem,
 * com o número do pedido e o nome do cliente. Pedido aguardando pagamento nunca é enviado; venda cancelada ou devolvida
 * depois de enviada vira um estorno. O servidor guarda uma vez só por pedido (reenviar não duplica) e o Vyntem busca e lança.
 * Roda junto do vigia da loja (~15 min). Só vale para vendas a partir do momento em que a pessoa liga.
 */
object CaixaDoVyntem {
    private const val PREFS = "caixa_vyntem"
    private const val P_LIGADO = "ligado"
    private const val P_DESDE = "desde"
    private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")
    private val CANCELADOS = setOf("canceled", "refunded", "returned")

    data class Item(val ref: String, val tipo: String, val valorCentavos: Int, val cliente: String, val pedido: String, val quando: Long)

    /** O que enviar agora e como ficam as listas de pedidos já enviados/estornados se o envio der certo. */
    data class Lote(val itens: List<Item>, val enviados: Set<Int>, val estornados: Set<Int>)

    fun ligado(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(P_LIGADO, false)

    fun definir(c: Context, ligar: Boolean) {
        val e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(P_LIGADO, ligar)
        if (ligar) e.putLong(P_DESDE, System.currentTimeMillis())
        e.apply()
        if (ligar) {
            VigiaDaLoja.garantir(c)
            Thread { try { conferir(c) } catch (e: Exception) { } }.start()
        }
    }

    private fun lojaChave(c: Context) = Lojas.aberta(c).ifBlank { "principal" }.filter { it.isLetterOrDigit() }.ifBlank { "p" }
    private fun ler(s: String?): Set<Int> = s.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** Lógica pura (testada): decide o que vai para o caixa. [desde] = quando a pessoa ligou; [agora] marca a hora do estorno. */
    internal fun montarLote(
        pedidos: List<Loja.Pedido>, enviados: Set<Int>, estornados: Set<Int>, desde: Long, lojaChave: String, agora: Long,
    ): Lote {
        val itens = mutableListOf<Item>()
        val novosEnviados = enviados.toMutableSet()
        val novosEstornados = estornados.toMutableSet()
        for (p in pedidos.sortedBy { it.id }) {
            if (p.totalCentavos <= 0) continue
            val ref = "venus:$lojaChave-${p.id}"
            val quando = p.pago ?: p.criado
            if (p.status in PAGOS && p.id !in enviados && quando >= desde) {
                itens += Item(ref, "entrada", p.totalCentavos, p.cliente.trim(), p.id.toString(), quando)
                novosEnviados += p.id
            } else if (p.status in CANCELADOS && p.id in enviados && p.id !in estornados) {
                itens += Item(ref, "estorno", p.totalCentavos, p.cliente.trim(), p.id.toString(), agora)
                novosEstornados += p.id
            }
        }
        val atuais = pedidos.map { it.id }.toSet()
        return Lote(itens, novosEnviados.filter { it in atuais }.toSet(), novosEstornados.filter { it in atuais }.toSet())
    }

    internal fun corpo(itens: List<Item>): JSONObject = JSONObject().put(
        "itens",
        JSONArray().also { a ->
            for (i in itens) a.put(
                JSONObject().put("ref", i.ref).put("tipo", i.tipo).put("valorCentavos", i.valorCentavos)
                    .put("cliente", i.cliente).put("pedido", i.pedido).put("quando", i.quando)
            )
        },
    )

    /** Olha os pedidos e manda para o servidor o que ainda não foi. Se algo falhar, nada é marcado e a próxima rodada tenta de novo. */
    fun conferir(c: Context) {
        if (!ligado(c) || !Nuvem.registrada(c)) return
        val loja = Loja(c)
        if (!loja.conectada) return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = lojaChave(c)
        val lote = montarLote(
            loja.pedidos() + PdvVendas.pedidos(c), ler(prefs.getString("env_$k", "")), ler(prefs.getString("est_$k", "")),
            prefs.getLong(P_DESDE, System.currentTimeMillis()), k, System.currentTimeMillis(),
        )
        for (parte in lote.itens.chunked(50)) {
            val r = Nuvem.pedir(c, "POST", "/financas/caixa", corpo(parte))
            if (!r.ok) return
        }
        prefs.edit()
            .putString("env_$k", lote.enviados.joinToString(","))
            .putString("est_$k", lote.estornados.joinToString(","))
            .apply()
    }
}
