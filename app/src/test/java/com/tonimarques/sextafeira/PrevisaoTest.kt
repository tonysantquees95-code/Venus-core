package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PrevisaoTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao
    private val agora: Calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 2, 10, 0) } // segunda
    private fun diasAtras(d: Int) = agora.timeInMillis - d * 24L * 3600_000

    private val produtos = listOf(
        Loja.Produto(1, "Gel KGEL Morango", true, 6, 2990),
        Loja.Produto(2, "Vibrador Rosa", true, 10, 9990),
        Loja.Produto(3, "Kit Casal", true, 0, 5990),
    )
    // gel: 15 vendidos em 30 dias (0,5/dia) → 6 un. acabam em 12 dias
    private val pedidos = (1..15).map { k ->
        Loja.Pedido(k, "paid", 2990, "C$k", "sedex", diasAtras(k * 2 - 1), diasAtras(k * 2 - 1), listOf(Loja.Item("Gel KGEL Morango", 1, 2990, 1)))
    } + Loja.Pedido(50, "paid", 5990, "Ana", "pickup", diasAtras(3), diasAtras(3), listOf(Loja.Item("Kit Casal", 1, 5990, 3)))

    @Test fun comandos() {
        assertEquals("gel", acao("quando o gel vai acabar")!!.args["produto"])
        assertEquals("loja_previsao", acao("previsão de estoque")!!.nome)
        assertEquals("", acao("o que vai acabar logo")!!.args["produto"])
        assertEquals("loja_acabando", acao("o que está acabando")!!.nome)
    }

    @Test fun previsao() {
        val g = Loja.montarPrevisao(pedidos, produtos, "gel", agora)
        assertTrue(g, g.contains("Gel KGEL Morango: 6 unidades, vende 3,5 por semana, acaba em uns 12 dias; para 30 dias, repor 9"))
        val geral = Loja.montarPrevisao(pedidos, produtos, "", agora)
        assertTrue(geral, geral.startsWith("Vão acabar nas próximas 2 semanas: Gel KGEL Morango"))
        assertTrue(Loja.montarPrevisao(pedidos, produtos, "vibrador", agora).contains("nenhuma venda"))
    }

    @Test fun semanalERetiradas() {
        val r = Loja.montarRelatorioSemanal(pedidos, produtos, agora)
        assertTrue(r, r.startsWith("Semana passada: 5 vendas"))
        assertTrue(r, r.contains("Mais vendidos: Gel KGEL Morango (4)"))
        assertEquals(listOf(50), Loja.retiradasEsquecidas(pedidos, agora.timeInMillis).map { it.id })
    }
}
