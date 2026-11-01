package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PanoramaTest {
    private fun prod(id: Int, nome: String, cat: String, preco: Int, estoque: Int, custo: Int?): Loja.Produto {
        val b = JSONObject().put("id", id).put("name", nome).put("category", cat).put("priceCents", preco)
            .put(Loja.CAMPO_CUSTO, custo ?: JSONObject.NULL)
        return Loja.Produto(id, nome, true, estoque, preco, null, b)
    }

    @Test fun retratoSemDadosDeClientes() {
        val agora = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 15, 12, 0) }
        val t = agora.timeInMillis
        val dia = 86_400_000L
        val produtos = listOf(
            prod(1, "Conjunto Renda Noir", "lingerie", 8990, 4, 4500),
            prod(2, "Gel KGEL", "geis_lubrificantes", 2990, 10, 1200),
            prod(3, "Vela Massagem", "cosmeticos", 3990, 6, null),
        )
        val pedidos = listOf(
            Loja.Pedido(1, "paid", 8990, "Ana Souza", "pickup", t - 2 * dia, t - 2 * dia, listOf(Loja.Item("Conjunto Renda Noir", 1, 8990, 1)), telefone = "51999990000"),
            Loja.Pedido(2, "delivered", 5980, "Ana Souza", "pac", t - 40 * dia, t - 40 * dia, listOf(Loja.Item("Gel KGEL", 2, 2990, 2)), telefone = "51999990000"),
            Loja.Pedido(3, "paid", 8990, "Bia Lima", "sedex", t - 5 * dia, t - 5 * dia, listOf(Loja.Item("Conjunto Renda Noir", 1, 8990, 1)), telefone = "51988887777"),
        )
        val p = Panorama.montar(pedidos, produtos, emptyList(), listOf(Financas.Fixa(1, "Floot", 10000, "sistemas e apps", 10, 0)), agora)
        assertTrue(p, p.contains("nov R$ 179,80 em 2 pedidos"))
        assertTrue(p, p.contains("margem média de 55% sobre o preço (2 com custo cadastrado)"))
        assertTrue(p, p.contains("lingerie R$ 179,80"))
        assertTrue(p, p.contains("Conjunto Renda Noir: 2 un., R$ 179,80, margem 50%"))
        assertTrue(p, p.contains("Sem venda há 30 dias (2): Gel KGEL (10 un.), Vela Massagem (6 un.)"))
        assertTrue(p, p.contains("2 compradores; 1 compraram mais de uma vez (50% de recompra)"))
        assertTrue(p, p.contains("Despesas fixas por mês: R$ 100,00 (Floot)"))
        assertTrue(p, p.contains("Black Friday em 12 dias"))
        // nada de cliente
        for (proibido in listOf("Ana", "Bia", "99999", "88887")) assertFalse(proibido, p.contains(proibido))
    }

    @Test fun reconhecePerguntaDeNegocio() {
        assertTrue(Panorama.sobreNegocio("Vale a pena investir 3 mil reais em conjuntos de lingerie antes do Natal?"))
        assertTrue(Panorama.sobreNegocio("qual a melhor estratégia para a Black Friday"))
        assertFalse(Panorama.sobreNegocio("Se 5 máquinas fazem 5 peças em 5 minutos, quanto tempo 100 máquinas levam?"))
        assertFalse(Panorama.sobreNegocio("por que o céu é azul"))
    }
}
