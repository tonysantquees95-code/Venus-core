package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CaderninhoTest {
    private fun brasilia(a: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance(TimeZone.getTimeZone("GMT-03:00")).apply { clear(); set(a, m - 1, d, h, 0, 0) }.timeInMillis

    private val hoje = brasilia(2026, 11, 26)
    private fun fiado(id: Int = 1, venceEm: Long = brasilia(2026, 12, 3, 9), forma: String = Caderninho.FORMA_PIX, tel: String = "5551999990000") = Caderninho.Fiado(
        id, "B7", "Maria Souza", tel, listOf(Caderninho.Linha("Pastel", 2, 850), Caderninho.Linha("Água", 1, 300)), forma, hoje, venceEm,
    )

    @Test fun vencimentoEmDiasContaOFusoDeBrasiliaEAvisaAsNove() {
        val v = Caderninho.vencimentoEmDias(hoje, 7)
        assertEquals("03/12/2026", Caderninho.dataBR(v))
        assertEquals(brasilia(2026, 12, 3, 9), v)
        // 23h50 em Brasília ainda é dia 26, mesmo sendo dia 27 em UTC
        val tarde = brasilia(2026, 11, 26, 23)
        assertEquals("27/11/2026", Caderninho.dataBR(Caderninho.vencimentoEmDias(tarde, 1)))
    }

    @Test fun dataDigitadaValeDeHojeEmDiante() {
        assertEquals("15/12/2026", Caderninho.dataBR(Caderninho.dataDeTexto("15/12/2026", hoje)!!))
        assertEquals("15/12/2026", Caderninho.dataBR(Caderninho.dataDeTexto("15122026", hoje)!!))
        assertNotNull(Caderninho.dataDeTexto("26/11/2026", hoje)) // hoje vale
        assertNull(Caderninho.dataDeTexto("25/11/2026", hoje))     // ontem não
        assertNull(Caderninho.dataDeTexto("31/02/2027", hoje))     // não existe
        assertNull(Caderninho.dataDeTexto("15/12/26", hoje))
        assertNull(Caderninho.dataDeTexto("", hoje))
    }

    @Test fun situacaoETextoDoPrazo() {
        assertEquals(Caderninho.Situacao.ABERTO, Caderninho.situacao(fiado(), hoje))
        assertEquals("vence em 7 dias (03/12)", Caderninho.prazoEmTexto(fiado(), hoje))
        val hojeVence = fiado(venceEm = brasilia(2026, 11, 26, 9))
        assertEquals(Caderninho.Situacao.VENCE_HOJE, Caderninho.situacao(hojeVence, hoje))
        assertEquals("vence hoje", Caderninho.prazoEmTexto(hojeVence, hoje))
        val atrasado = fiado(venceEm = brasilia(2026, 11, 24, 9))
        assertEquals(Caderninho.Situacao.ATRASADO, Caderninho.situacao(atrasado, hoje))
        assertEquals("atrasado há 2 dias", Caderninho.prazoEmTexto(atrasado, hoje))
        assertEquals("atrasado há 1 dia", Caderninho.prazoEmTexto(fiado(venceEm = brasilia(2026, 11, 25, 9)), hoje))
        assertEquals(Caderninho.Situacao.PAGO, Caderninho.situacao(fiado().copy(pagoEm = hoje), hoje))
    }

    @Test fun naoPagaDuasVezes() {
        val lista = Caderninho.adicionar(emptyList(), fiado(0))
        val id = lista.first().id
        val (nova, pago) = Caderninho.pagar(lista, id, hoje)!!
        assertTrue(pago.pago)
        assertTrue(nova.first().pago)
        assertNull(Caderninho.pagar(nova, id, hoje + 1000))       // segunda vez: nada
        assertNull(Caderninho.pagar(nova, 999, hoje))             // não existe
        assertTrue(Caderninho.aReceber(nova).isEmpty())
        assertEquals(0, Caderninho.totalAReceber(nova))
    }

    @Test fun idsCrescemEListaOrdenaPeloVencimento() {
        var l = Caderninho.adicionar(emptyList(), fiado(0, venceEm = brasilia(2026, 12, 20, 9)))
        l = Caderninho.adicionar(l, fiado(0, venceEm = brasilia(2026, 11, 30, 9)))
        assertEquals(listOf(1, 2), l.map { it.id })
        assertEquals(listOf(2, 1), Caderninho.aReceber(l).map { it.id })
        assertEquals(2 * (2 * 850 + 300), Caderninho.totalAReceber(l))
    }

    @Test fun pagosAntigosSaemEOsAbertosFicam() {
        val velho = fiado(1).copy(pagoEm = hoje - 61L * 86_400_000)
        val recente = fiado(2).copy(pagoEm = hoje - 5L * 86_400_000)
        val aberto = fiado(3, venceEm = brasilia(2026, 10, 1, 9)) // atrasadíssimo, mas não pago: nunca some
        assertEquals(listOf(2, 3), Caderninho.limpar(listOf(velho, recente, aberto), hoje).map { it.id })
    }

    @Test fun cobrarDeNovoContaSoParaQuemNaoPagou() {
        val l = Caderninho.cobrou(Caderninho.cobrou(listOf(fiado(1), fiado(2).copy(pagoEm = hoje)), 1), 2)
        assertEquals(1, l[0].cobrancas); assertEquals(0, l[1].cobrancas)
    }

    @Test fun cobrancaNoPixLevaOCodigoEEducada() {
        val t = Caderninho.textoDaCobranca("Desejo", fiado(), "000201PIXCODE", false, hoje)
        assertTrue(t.startsWith("Oi, Maria!"))
        assertTrue(t.contains("2x Pastel — R$ 17,00"))
        assertTrue(t.contains("*Total: R$ 20,00*"))
        assertTrue(t.contains("03/12/2026"))
        assertTrue(t.contains("000201PIXCODE"))
    }

    @Test fun cobrancaEmDinheiroNaoLevaPixEPodeSerLembrete() {
        val t = Caderninho.textoDaCobranca("Desejo", fiado(forma = Caderninho.FORMA_DINHEIRO), "000201PIXCODE", false, hoje)
        assertFalse(t.contains("PIXCODE"))
        assertTrue(t.contains("em dinheiro"))
        val l = Caderninho.textoDaCobranca("Desejo", fiado(venceEm = brasilia(2026, 11, 24, 9)), null, true, hoje)
        assertTrue(l.contains("venceu em 24/11/2026"))
        assertTrue(l.contains("R$ 20,00"))
        assertFalse(l.contains("Pix copia e cola"))
    }

    @Test fun cobrancaCompactaCabeNaLegendaDoWhatsApp() {
        val muitos = fiado().copy(linhas = (1..40).map { Caderninho.Linha("Produto com nome bem comprido número $it", 1, 1000) })
        val cheia = Caderninho.textoDaCobranca("Desejo", muitos, "PIX".repeat(80), false, hoje)
        val curta = Caderninho.textoDaCobranca("Desejo", muitos, "PIX".repeat(80), false, hoje, compacto = true)
        assertTrue(cheia.length > 1000)
        assertTrue(curta.length < 1000)
        assertTrue(curta.contains("R$ 400,00")); assertTrue(curta.contains("PIXPIX")); assertFalse(curta.contains("Produto com nome"))
    }

    @Test fun confirmacaoDoPagamentoECaprichadaEComprovanteAvisa() {
        val t = Caderninho.textoDeAgradecimento("Desejo", fiado())
        assertTrue(t.contains("Oi, Maria!")); assertTrue(t.contains("Pagamento confirmado")); assertTrue(t.contains("R$ 20,00"))
        assertTrue(t.contains("26/11/2026")); assertTrue(t.contains("2x Pastel")); assertTrue(t.contains("obrigado pela compra"))
        val c = Caderninho.textoDeComprovante("Desejo", fiado())
        assertTrue(c.contains("Oi, Maria!")); assertTrue(c.contains("comprovante")); assertTrue(c.contains("R$ 20,00"))
        assertFalse(c.contains("Pagamento confirmado")) // só avisa que chegou; confirmar é depois do "Pago"
    }

    @Test fun cobrancaComLinkLevaOLinkEFalaQuantosDiasFaltam() {
        val f = fiado().copy(linkUrl = "https://pague.exemplo/abc", linkRef = "venus-1", linkProvedor = "mercadopago")
        assertTrue(f.temLink)
        val inicial = Caderninho.textoDaCobranca("Desejo", f, null, false, hoje, link = f.linkUrl)
        assertTrue(inicial.contains("https://pague.exemplo/abc")); assertTrue(inicial.contains("2x Pastel")); assertTrue(inicial.contains("03/12/2026"))
        assertFalse(inicial.contains("copia e cola"))
        val doisDias = fiado(venceEm = brasilia(2026, 11, 28, 9)).copy(linkUrl = "https://pague.exemplo/abc")
        val lembrete = Caderninho.textoDaCobranca("Desejo", doisDias, null, true, hoje, link = doisDias.linkUrl)
        assertTrue(lembrete.contains("falta 2 dias")); assertTrue(lembrete.contains("https://pague.exemplo/abc"))
        val umDia = doisDias.copy(venceEm = brasilia(2026, 11, 27, 9))
        assertTrue(Caderninho.textoDaCobranca("Desejo", umDia, null, true, hoje, link = "x").contains("falta 1 dia,").not() )
        assertTrue(Caderninho.textoDaCobranca("Desejo", umDia, null, true, hoje, link = "x").contains("falta 1 dia"))
        val perdi = Caderninho.textoDoLink("Desejo", f)
        assertTrue(perdi.contains("R$ 20,00")); assertTrue(perdi.contains("https://pague.exemplo/abc")); assertTrue(perdi.startsWith("Oi, Maria!"))
    }

    @Test fun pagamentoNoLinkSoViraBaixaQuandoConfirmado() {
        val f = fiado().copy(linkUrl = "u", linkRef = "r", linkPagoEm = hoje, linkForma = "Pix")
        assertTrue(f.pagoNoLink); assertFalse(f.pago)
        assertEquals("Pix", f.formaReal)
        assertEquals(1, Caderninho.aReceber(listOf(f)).size)            // continua devendo até confirmar
        val (_, pago) = Caderninho.pagar(listOf(f.copy(id = 1)), 1, hoje + 10)!!
        assertTrue(pago.pago); assertFalse(pago.pagoNoLink)
        assertEquals("Dinheiro", fiado(forma = Caderninho.FORMA_DINHEIRO).formaReal)
    }

    @Test fun guardaELeDeVolta() {
        val f = fiado().copy(id = 4, pagoEm = 5L, pedidoId = 12, interno = false, cobrancas = 2, linkRef = "venus-9", linkUrl = "https://x/y", linkProvedor = "infinitepay", linkPagoEm = 7L, linkForma = "Pix")
        assertEquals(listOf(f), Caderninho.deJson(Caderninho.paraJson(listOf(f))))
        assertTrue(Caderninho.deJson("lixo").isEmpty())
        assertTrue(Caderninho.deJson(null).isEmpty())
    }

    @Test fun formaReal() {
        assertEquals("Pix", fiado().formaReal)
        assertEquals("Dinheiro", fiado(forma = Caderninho.FORMA_DINHEIRO).formaReal)
        assertTrue(Caderninho.ehCaderninho("Caderninho Pix")); assertFalse(Caderninho.ehCaderninho("Pix"))
    }

    @Test fun editarCorrigeSoOQueEstaAberto() {
        val l = Caderninho.adicionar(emptyList(), fiado(0))
        val id = l.first().id
        val novas = listOf(Caderninho.Linha("Pastel", 3, 850, 5))
        val e = Caderninho.editar(l, id, " Joana ", "5551988887777", brasilia(2026, 12, 10, 9), novas)!!
        assertEquals("Joana", e[0].cliente); assertEquals(2550, e[0].total); assertEquals("5551988887777", e[0].telefone)
        assertNull(Caderninho.editar(l, id, "Joana", "", hoje, emptyList()))            // sem itens: é excluir
        assertNull(Caderninho.editar(l, id, " ", "", hoje, novas))                     // sem nome
        assertNull(Caderninho.editar(l, 99, "Joana", "", hoje, novas))                 // não existe
        val paga = Caderninho.pagar(l, id, hoje)!!.first
        assertNull(Caderninho.editar(paga, id, "Joana", "", hoje, novas))              // pago não se edita
    }

    @Test fun excluirSoDoQueNaoFoiPago() {
        val l = Caderninho.adicionar(Caderninho.adicionar(emptyList(), fiado(0)), fiado(0))
        assertEquals(listOf(2), Caderninho.excluir(l, 1)!!.map { it.id })
        assertNull(Caderninho.excluir(l, 99))
        assertNull(Caderninho.excluir(Caderninho.pagar(l, 1, hoje)!!.first, 1))
    }

    @Test fun diferencaDeEstoque() {
        val antes = listOf(Caderninho.Linha("A", 2, 100, 1), Caderninho.Linha("B", 1, 100, 2), Caderninho.Linha("Velho", 4, 100, 0))
        val depois = listOf(Caderninho.Linha("A", 5, 100, 1), Caderninho.Linha("C", 1, 100, 3))
        // A sai mais 3, B volta 1, C sai 1; sem id (0) não mexe
        assertEquals(mapOf(1 to 3, 2 to -1, 3 to 1), Caderninho.diferencaDeEstoque(antes, depois))
        assertTrue(Caderninho.diferencaDeEstoque(antes, antes).isEmpty())
    }

    // a venda pendente do caixa de loja com site: não vira dinheiro antes do "Pago"
    @Test fun vendaPendenteNaoEntraNoVyntemAteSerPaga() {
        val v = PdvVendas.nova(emptyList(), listOf(PdvVendas.Linha(1, "Pastel", 1, 850)), Caderninho.FORMA_PIX, hoje, pendente = true)
        val pedido = PdvVendas.comoPedido(v)
        assertEquals("awaiting_payment", pedido.status)
        assertNull(pedido.pago)
        val paga = PdvVendas.comoPedido(v.copy(pendente = false, pagoEm = hoje + 5000))
        assertEquals("paid", paga.status)
        assertEquals(hoje + 5000, paga.pago)
        val lote0 = CaixaDoVyntem.montarLote(listOf(pedido), emptySet(), emptySet(), 0L, "p", hoje)
        assertTrue(lote0.itens.isEmpty())
        val lote1 = CaixaDoVyntem.montarLote(listOf(paga), emptySet(), emptySet(), 0L, "p", hoje)
        assertEquals(1, lote1.itens.size)
        // marcar pago de novo não duplica
        val lote2 = CaixaDoVyntem.montarLote(listOf(paga), lote1.enviados, emptySet(), 0L, "p", hoje)
        assertTrue(lote2.itens.isEmpty())
    }
}
