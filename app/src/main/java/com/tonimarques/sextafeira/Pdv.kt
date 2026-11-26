package com.tonimarques.sextafeira

/**
 * Caixa (balcão): lógica pura da venda rápida. Dinheiro sempre em centavos inteiros.
 * O carrinho guarda só produto -> quantidade; preço e estoque vêm do catálogo no momento de fechar.
 */
internal object Pdv {
    data class Carrinho(val linhas: Map<Int, Int> = emptyMap()) {
        fun somar(id: Int, n: Int = 1): Carrinho {
            val q = (linhas[id] ?: 0) + n
            return Carrinho(if (q <= 0) linhas - id else linhas + (id to q))
        }
        fun vazio() = linhas.isEmpty()
        fun unidades() = linhas.values.sum()
    }

    /** Itens no formato de [CatalogoInterno.registrarVenda]; produto que sumiu do catálogo é ignorado. */
    fun itens(c: Carrinho, produtos: List<Loja.Produto>): List<Triple<Loja.Produto, Int, Int?>> =
        c.linhas.mapNotNull { (id, q) -> produtos.firstOrNull { it.id == id }?.let { Triple(it, q, null) } }

    fun total(c: Carrinho, produtos: List<Loja.Produto>): Int = itens(c, produtos).sumOf { (p, q, _) -> (p.precoCentavos ?: 0) * q }

    /** Produtos que passam do estoque (só os que têm controle de estoque). */
    fun faltando(c: Carrinho, produtos: List<Loja.Produto>): List<String> =
        itens(c, produtos).filter { (p, q, _) -> p.estoque != null && q > p.estoque }.map { it.first.nome }

    /** Troco em centavos; null se o que o cliente deu não cobre o total. */
    fun troco(total: Int, recebido: Int): Int? = if (recebido >= total) recebido - total else null

    /** Taxa e líquido de um pagamento com taxa em % (ex.: 3,49). Arredonda a taxa para o centavo mais próximo. */
    fun liquido(total: Int, taxaPct: Double): Pair<Int, Int> {
        val pct = taxaPct.coerceIn(0.0, 100.0)
        val taxa = Math.round(total * pct / 100.0).toInt()
        return Pair(taxa, total - taxa)
    }

    /** Lê "3,49" ou "3.49" como porcentagem; null se não for número. */
    fun lerPct(texto: String): Double? = texto.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
}
