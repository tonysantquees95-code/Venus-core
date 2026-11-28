package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasDeVendaTest {
    private val padrao = RegrasDeVenda()

    @Test fun textoPadraoEhODaLoja() {
        val t = padrao.texto()
        assertTrue(t.contains("Pix com 5% de desconto (exceto produtos em promoção)"))
        assertTrue(t.contains("cartão em até 3x sem juros"))
        assertTrue(t.contains("PAC ou SEDEX"))
        assertTrue(t.contains("retirada grátis em Porto Alegre"))
        assertTrue(t.contains("maiores de 18 anos"))
        assertFalse(t.contains("frete grátis"))
        assertEquals(t, Perfil.regrasDeVenda)
    }

    @Test fun contas() {
        assertEquals(4740, padrao.precoNoPix(4990))
        assertNull(padrao.precoNoPix(4990, emPromocao = true))
        assertEquals(4740, padrao.copy(pixValeEmPromocao = true).precoNoPix(4990, emPromocao = true))
        assertEquals("3x de R$ 16,64 sem juros", padrao.parcelas(4990))
        assertEquals("6x de R$ 10,00 (sem juros até 3x)", padrao.copy(cartaoParcelasMax = 6).parcelas(6000))
        assertNull(padrao.copy(cartaoParcelasMax = 1).parcelas(6000))
        assertNull(padrao.freteGratis(50000))
        assertEquals(true, padrao.copy(freteGratisAcimaDe = 19900).freteGratis(19900))
    }

    @Test fun jsonIdaEVolta() {
        val r = padrao.copy(freteGratisAcimaDe = 19900, prazoPostagemDias = 2, horarioAtendimento = "seg a sex, 9h às 18h", observacoes = "Brinde no Dia dos Namorados")
        assertEquals(r, RegrasDeVenda.ler(r.json("moda íntima").toString()))
        assertEquals(padrao, RegrasDeVenda.ler(null))
        assertEquals(padrao, RegrasDeVenda.ler("lixo"))
        assertEquals(10, RegrasDeVenda.ler("""{"pagamento":{"pix_desconto_pct":10}}""").pixDescontoPct)
        assertEquals(7, RegrasDeVenda.ler("""{"trocas":{"arrependimento_dias":3}}""").arrependimentoDias)
    }

    @Test fun leituraDoFormulario() {
        assertEquals(listOf("PAC", "SEDEX", "motoboy"), RegrasDeVenda.separar("PAC, SEDEX e motoboy"))
        assertEquals(19990, RegrasDeVenda.centavosDe("199,90"))
        assertEquals(19990, RegrasDeVenda.centavosDe("199.90"))
        assertEquals(129900, RegrasDeVenda.centavosDe("R$ 1.299,00"))
        assertNull(RegrasDeVenda.centavosDe(""))
    }

    @Test fun perguntasParaAMemoria() {
        val p = padrao.copy(freteGratisAcimaDe = 19900).perguntas("Loja X")
        assertTrue(p.any { it.first.contains("frete grátis") && it.second.contains("R$ 199,00") })
        assertTrue(p.any { it.first.contains("formas de pagamento") && it.second.contains("Pix com 5%") })
        assertFalse(padrao.perguntas("Loja X").any { it.first.contains("frete grátis") })
        assertFalse(padrao.perguntas("Loja X").any { it.first.contains("horário") })
    }

    @Test fun lojaSemCartaoNaoFalaEmCartao() {
        val r = padrao.copy(aceitaCartao = false)
        assertEquals("somente por Pix, com 5% de desconto (exceto produtos em promoção)", r.textoPagamento())
        assertNull(r.parcelas(4990))
        assertEquals("Pix", r.formasDoLink)
        assertFalse(r.perguntas("Loja X").any { it.second.contains("cartão") })
        assertEquals("somente por Pix", RegrasDeVenda.VAZIA.copy(aceitaCartao = false).textoPagamento())
    }

    @Test fun lojaSemPixNaoFalaEmPixNemDaDesconto() {
        val r = padrao.copy(aceitaPix = false)
        assertEquals("somente por cartão em até 3x sem juros", r.textoPagamento())
        assertNull(r.precoNoPix(4990))
        assertEquals(0, r.descontoDoPix)
        assertEquals("cartão", r.formasDoLink)
        assertFalse(r.texto().contains("Pix"))
        assertEquals("somente por cartão à vista", RegrasDeVenda.VAZIA.copy(aceitaPix = false).textoPagamento())
    }

    @Test fun formasDePagamentoGuardadas() {
        for (r in listOf(padrao.copy(aceitaPix = false), padrao.copy(aceitaCartao = false))) assertEquals(r, RegrasDeVenda.ler(r.json("x").toString()))
        val antiga = RegrasDeVenda.ler("""{"pagamento":{"pix_desconto_pct":10}}""")
        assertTrue(antiga.aceitaPix && antiga.aceitaCartao)
        val estragada = RegrasDeVenda.ler("""{"pagamento":{"aceita_pix":false,"aceita_cartao":false}}""")
        assertTrue(estragada.aceitaPix && estragada.aceitaCartao)
    }

    @Test fun linkDoMercadoPagoEscondeOQueALojaNaoAceita() {
        assertFalse(Cobranca.meiosDoMercadoPago(padrao).has("excluded_payment_types"))
        assertTrue(Cobranca.meiosDoMercadoPago(padrao.copy(aceitaPix = false)).toString().contains("bank_transfer"))
        val semCartao = Cobranca.meiosDoMercadoPago(padrao.copy(aceitaCartao = false)).toString()
        assertTrue(semCartao.contains("credit_card") && semCartao.contains("debit_card") && !semCartao.contains("bank_transfer"))
    }

    @Test fun injecaoNoPrompt() {
        val t = Perfil.injecao()
        assertTrue(t.startsWith("Perfil da loja"))
        assertTrue(t.contains(Perfil.loja) && t.contains("Regras de venda: " + padrao.texto()))
        assertTrue(t.contains("Não informado pela loja: frete grátis, prazo de postagem, horário de atendimento"))
        assertTrue(t.contains("Nunca prometa"))
        assertEquals(listOf("frete grátis", "prazo de postagem", "horário de atendimento"), padrao.faltando())
        assertTrue(RegrasDeVenda.VAZIA.faltando().containsAll(listOf("formas de envio", "canais de atendimento")))
        assertEquals(listOf("pagamento", "frete", "retirada", "discreto", "troca", "idade"), padrao.perguntasComId("X").map { it.first })
    }
}
