package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vendas do Caixa em lojas COM site (Floot, Shopify, WooCommerce, Nuvemshop): o estoque baixa no site, mas o site não recebe
 * um pedido, então a Vênus guarda aqui, neste celular, cada venda de balcão. Elas viram pedidos pagos (ids a partir de
 * 1.000.000, que nunca colidem com os do site) para entrar no caixa do Vyntem e em "Sua loja hoje". Na loja sem site o
 * registro é o próprio catálogo interno ([CatalogoInterno.registrarVenda]).
 */
internal object PdvVendas {
    private const val PREFS = "pdv_vendas"
    private const val BASE_ID = 1_000_000

    /** O número que aparece para o cliente: a 1ª venda de balcão é a nº 1 (o id interno começa em 1.000.000 só para não colidir com os do site). */
    fun numeroAmigavel(id: Int): Int = if (id >= BASE_ID) id - BASE_ID + 1 else id
    private const val MAX_GUARDADAS = 500

    data class Linha(val produtoId: Int, val nome: String, val quantidade: Int, val precoCentavos: Int)
    data class Venda(val id: Int, val quando: Long, val forma: String, val itens: List<Linha>, val pendente: Boolean = false, val pagoEm: Long = 0L) {
        val total: Int get() = itens.sumOf { it.quantidade * it.precoCentavos }
    }

    /** Próxima venda da lista (lógica pura, testada). */
    internal fun nova(vendas: List<Venda>, itens: List<Linha>, forma: String, agora: Long, pendente: Boolean = false): Venda =
        Venda((vendas.maxOfOrNull { it.id } ?: (BASE_ID - 1)) + 1, agora, forma, itens, pendente)

    internal fun comoPedido(v: Venda): Loja.Pedido = Loja.Pedido(
        v.id, if (v.pendente) "awaiting_payment" else "paid", v.total, "Cliente do balcão", "pickup", v.quando, if (v.pendente) null else (if (v.pagoEm > 0) v.pagoEm else v.quando),
        v.itens.map { Loja.Item(it.nome, it.quantidade, it.precoCentavos, it.produtoId) },
    )

    private fun ler(c: Context): List<Venda> {
        val texto = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", "[]").orEmpty()
        return try {
            val a = JSONArray(texto)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                val l = o.optJSONArray("itens") ?: JSONArray()
                Venda(
                    o.optInt("id"), o.optLong("quando"), o.optString("forma"),
                    (0 until l.length()).mapNotNull { l.optJSONObject(it) }.map { Linha(it.optInt("p"), it.optString("n"), it.optInt("q"), it.optInt("c")) },
                    o.optBoolean("pend", false), o.optLong("pagoEm", 0L),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun gravar(c: Context, guardar: List<Venda>) {
        val a = JSONArray()
        for (x in guardar) a.put(
            JSONObject().put("id", x.id).put("quando", x.quando).put("forma", x.forma).put("pend", x.pendente).put("pagoEm", x.pagoEm).put(
                "itens", JSONArray().also { l -> x.itens.forEach { i -> l.put(JSONObject().put("p", i.produtoId).put("n", i.nome).put("q", i.quantidade).put("c", i.precoCentavos)) } },
            ),
        )
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", a.toString()).apply()
    }

    /** [pendente] = venda no caderninho: o estoque baixa, mas só conta como paga depois de [marcarPaga]. */
    @Synchronized
    fun registrar(c: Context, itens: List<Linha>, forma: String, agora: Long = System.currentTimeMillis(), pendente: Boolean = false): Venda {
        val todas = ler(c)
        val v = nova(todas, itens, forma, agora, pendente)
        gravar(c, (todas + v).takeLast(MAX_GUARDADAS))
        return v
    }

    /** O dinheiro do caderninho entrou. Falso se a venda não existe ou já estava paga (nunca conta duas vezes). */
    @Synchronized
    fun marcarPaga(c: Context, id: Int, agora: Long = System.currentTimeMillis()): Boolean {
        val todas = ler(c)
        val alvo = todas.firstOrNull { it.id == id } ?: return false
        if (!alvo.pendente) return false
        gravar(c, todas.map { if (it.id == id) it.copy(pendente = false, pagoEm = agora) else it })
        return true
    }

    /** As vendas de balcão como pedidos pagos (vazio em loja sem site, onde elas já estão no catálogo). */
    fun pedidos(c: Context): List<Loja.Pedido> = ler(c).map { comoPedido(it) }
}
