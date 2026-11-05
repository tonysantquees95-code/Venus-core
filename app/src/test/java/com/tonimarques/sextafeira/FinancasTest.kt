package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FinancasTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    @Test fun despesasPorVoz() {
        val a = acao("paguei 120 de internet")!!
        assertEquals("caixa_lancar", a.nome)
        assertEquals("saida", a.args["tipo"])
        assertEquals(12000, a.args["valor"])
        assertEquals("internet", a.args["descricao"])
        assertEquals(4590, acao("gastei 45,90 com embalagens")!!.args["valor"])
        assertEquals("embalagens", acao("gastei 45,90 com embalagens")!!.args["descricao"])
        assertEquals(150000, acao("paguei 1.500 de aluguel")!!.args["valor"])
        assertEquals("Motoboy", acao("Paguei R$ 25 de Motoboy.")!!.args["descricao"])
        assertEquals("caixa_lancar", acao("anota uma despesa de 30 reais de gasolina")!!.nome)
        val b = acao("paguei a conta de luz de 180")!!
        assertEquals("caixa_lancar", b.nome)
        assertEquals(18000, b.args["valor"])
        assertEquals("conta de luz", b.args["descricao"])
    }

    @Test fun entradasEEstoqueNaoSeMisturam() {
        val e = acao("recebi 200 reais de uma venda no pix")!!
        assertEquals("caixa_lancar", e.nome)
        assertEquals("entrada", e.args["tipo"])
        assertEquals(20000, e.args["valor"])
        assertEquals("caixa_lancar", acao("vendi 80 reais no pix")!!.nome)
        // vender unidades continua sendo baixa no estoque
        val v = acao("vendi 3 gel kgel")!!
        assertEquals("loja_alterar", v.nome)
        assertEquals("venda", v.args["tipo"]) // baixa no estoque (com site) ou venda registrada (sem site)
        // custo de produto continua sendo custo; "paguei X no Y" pode virar despesa se não for produto
        val c = acao("paguei 50 no gás")!!
        assertEquals("loja_alterar", c.nome)
        assertEquals("despesa", c.args["se_nao_achar"])
        assertEquals("custo", acao("o custo do gel kgel é 2,60")!!.args["tipo"])
    }

    @Test fun fixasEConsultas() {
        val f = acao("despesa fixa de internet de 120 todo dia 10")!!
        assertEquals("caixa_fixa", f.nome)
        assertEquals(12000, f.args["valor"])
        assertEquals(10, f.args["dia"])
        assertEquals("internet", f.args["descricao"])
        val g = acao("o aluguel é 900 por mês")!!
        assertEquals("caixa_fixa", g.nome)
        assertEquals("aluguel", g.args["descricao"])
        assertEquals("caixa_remover_fixa", acao("tira a despesa fixa do floot")!!.nome)
        assertEquals("caixa_apagar", acao("apaga o último lançamento")!!.nome)
        assertEquals("caixa_resultado", acao("como está o caixa")!!.nome)
        assertEquals("mes_passado", acao("como fechou o mês passado")!!.args["periodo"])
        assertEquals("caixa_resultado", acao("qual o lucro líquido do mês")!!.nome)
        assertEquals("caixa_despesas", acao("quanto eu gastei esse mês")!!.nome)
        assertEquals("caixa_equilibrio", acao("qual o meu ponto de equilíbrio")!!.nome)
        assertEquals("caixa_equilibrio", acao("quanto preciso vender pra pagar as contas")!!.nome)
        assertEquals("caixa_previsao", acao("quanto vou faturar no próximo mês")!!.nome)
        assertEquals("caixa_previsao", acao("previsão de faturamento")!!.nome)
        assertEquals("caixa_grafico", acao("mostra o gráfico de vendas")!!.nome)
        assertEquals("caixa_relatorio", acao("gera o relatório financeiro")!!.nome)
        assertEquals("mes_passado", acao("relatório do mês passado")!!.args["periodo"])
        // o que já existia continua igual
        assertEquals("loja_lucro", acao("qual foi meu lucro este mês")!!.nome)
        assertEquals("previsao_do_tempo", acao("previsão do tempo para amanhã")?.nome)
    }

    @Test fun contasFinanceiras() {
        val j = acao("quanto rende 1000 reais a 1% ao mês por 12 meses")!!
        assertEquals("financeiro_calculo", j.nome)
        assertEquals("juros_compostos", j.args["tipo"])
        assertEquals(1000.0, j.args["a"])
        assertEquals(12.0, j.args["c"])
        val p = acao("qual a parcela de 1.200 em 12x com 2%")!!
        assertEquals("parcela", p.args["tipo"])
        assertEquals(1200.0, p.args["a"])
        assertEquals("desconto_venda", acao("se eu der 10% de desconto quanto preciso vender a mais")!!.args["tipo"])
        val m = acao("que preço eu cobro para ter margem de 60% com custo de 20")!!
        assertEquals("markup", m.args["tipo"])
        assertEquals(20.0, m.args["b"])

        assertEquals(1126.83, Financas.jurosCompostos(1000.0, 1.0, 12), 0.01)
        assertEquals(113.47, Financas.parcela(1200.0, 2.0, 12), 0.01)
        assertEquals(33.33, Financas.vendasAMais(10.0, 40.0)!!, 0.01)
        assertNull(Financas.vendasAMais(50.0, 40.0))
        assertEquals(50.0, Financas.precoParaMargem(20.0, 60.0)!!, 0.001)
        assertTrue(Financas.textoCalculo("parcela", 1200.0, 2.0, 12.0).contains("R$ 113,47"))
        assertTrue(Financas.textoCalculo("markup", 60.0, 20.0, 0.0).contains("R$ 50,00"))
        assertEquals(2.5, ComandosLocais.num("2,5"), 0.0)
        assertEquals(2.5, ComandosLocais.num("2.5"), 0.0)
        assertEquals(1500.0, ComandosLocais.num("1.500"), 0.0)
    }

    private fun prod(id: Int, nome: String, preco: Int, custo: Int?): Loja.Produto {
        val b = JSONObject().put("id", id).put("name", nome).put("priceCents", preco).put(Loja.CAMPO_CUSTO, custo ?: JSONObject.NULL)
        return Loja.Produto(id, nome, true, 5, preco, null, b)
    }

    @Test fun resultadoDoMes() {
        val agora = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 20, 12, 0) }
        val t = agora.timeInMillis
        val dia = 86_400_000L
        val produtos = listOf(prod(1, "Gel", 3000, 1000), prod(2, "Vibrador", 10000, null))
        val pedidos = listOf(
            Loja.Pedido(1, "paid", 6000, "A", "pac", t - dia, t - dia, listOf(Loja.Item("Gel", 2, 3000, 1)), freteCentavos = 1500),
            Loja.Pedido(2, "delivered", 10000, "B", "pickup", t - 2 * dia, t - 2 * dia, listOf(Loja.Item("Vibrador", 1, 10000, 2))),
            Loja.Pedido(3, "canceled", 3000, "C", "pac", t - dia, null, listOf(Loja.Item("Gel", 1, 3000, 1))),
            Loja.Pedido(4, "paid", 3000, "D", "pac", t - 40 * dia, t - 40 * dia, listOf(Loja.Item("Gel", 1, 3000, 1))),
        )
        val lanc = listOf(
            Financas.Lancamento(1, t - dia, "saida", 12000, "Internet", "internet e telefone"),
            Financas.Lancamento(2, t - dia, "saida", 50000, "Compra de géis", "mercadoria"),
            Financas.Lancamento(3, t - dia, "entrada", 4000, "Venda por fora", "vendas por fora"),
        )
        val desde = t - 90 * dia
        val fixas = listOf(Financas.Fixa(1, "Aluguel", 30000, "aluguel", 5, desde), Financas.Fixa(2, "Floot", 10000, "sistemas e apps", 25, desde))
        val p = Financas.periodo("mes", agora)
        val r = Financas.resultado(pedidos, produtos, lanc, fixas, p)
        assertEquals(16000L, r.vendasSite)
        assertEquals(2, r.pedidos)
        assertEquals(20000L, r.receita)
        assertEquals(2000L, r.custoMercadoria)
        assertEquals(1, r.itensSemCusto)
        assertEquals(12000L, r.despesas)
        assertEquals(30000L, r.despesasFixas) // o Floot (dia 25) ainda não venceu
        assertEquals(50000L, r.comprasMercadoria)
        assertEquals(-24000L, r.lucro)
        assertEquals(20000L - 12000 - 30000 - 50000, r.fluxoDeCaixa)
        assertEquals(1500L, r.fretesCobrados)
        val texto = Financas.textoResultado(r, p)
        assertTrue(texto, texto.contains("Prejuízo de R$ 240,00"))
        assertTrue(texto, texto.contains("sem custo cadastrado"))
        assertEquals(listOf("Gel" to 2, "Vibrador" to 1), Financas.maisVendidos(pedidos, p))

        val hist = Financas.historico(pedidos, produtos, lanc, fixas, 3, agora)
        assertEquals(listOf("set", "out", "nov"), hist.map { it.first })
        assertEquals(3000L, hist[1].second.vendasSite)
        val prev = Financas.textoPrevisao(hist, 40000, agora)
        assertTrue(prev, prev.startsWith("No ritmo atual, este mês deve fechar com vendas de uns"))

        val desp = Financas.textoDespesas(lanc, fixas, p)
        assertTrue(desp, desp.startsWith("Despesas neste mês: R$ 920,00"))
        val eq = Financas.textoEquilibrio(fixas, 12000, 0.5)
        assertTrue(eq, eq.contains("R$ 1.040,00"))
    }

    @Test fun categoriasEPrevisao() {
        assertEquals("internet e telefone", Financas.categoria("internet"))
        assertEquals("embalagem", Financas.categoria("embalagens"))
        assertEquals("frete e entrega", Financas.categoria("Motoboy"))
        assertEquals("mercadoria", Financas.categoria("compra de produtos do fornecedor"))
        assertEquals("sistemas e apps", Financas.categoria("Floot"))
        assertEquals("outros", Financas.categoria("presente"))
        assertEquals(1000L, Financas.prever(listOf(1000L)))
        val subindo = Financas.prever(listOf(1000L, 2000L, 3000L))!!
        assertTrue("$subindo", subindo in 3000..4000)
        assertNull(Financas.prever(listOf(0L, 0L)))
        assertNotEquals(0L, Financas.prever(listOf(0L, 500L, 500L)))
    }
}
