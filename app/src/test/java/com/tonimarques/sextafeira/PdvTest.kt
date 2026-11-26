package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdvTest {
    private fun prod(id: Int, nome: String, preco: Int?, estoque: Int?) =
        Loja.Produto(id, nome, true, estoque, preco, null, org.json.JSONObject(), link = "")
    private val produtos = listOf(prod(1, "Água", 300, 10), prod(2, "Pastel", 850, 2), prod(3, "Sem preço", null, null))

    @Test fun somaERemoveUnidades() {
        var c = Pdv.Carrinho().somar(1).somar(1).somar(2)
        assertEquals(3, c.unidades())
        c = c.somar(1, -2)
        assertEquals(setOf(2), c.linhas.keys)
        assertTrue(c.somar(2, -1).vazio())
    }

    @Test fun totalEmCentavos() {
        val c = Pdv.Carrinho().somar(1, 3).somar(2, 2).somar(3, 1)
        assertEquals(3 * 300 + 2 * 850, Pdv.total(c, produtos))
    }

    @Test fun produtoQueSumiuDoCatalogoNaoEntra() {
        val c = Pdv.Carrinho().somar(99).somar(1)
        assertEquals(listOf(1), Pdv.itens(c, produtos).map { it.first.id })
    }

    @Test fun avisaQuandoPassaDoEstoque() {
        assertEquals(listOf("Pastel"), Pdv.faltando(Pdv.Carrinho().somar(2, 3).somar(1, 10), produtos))
        assertTrue(Pdv.faltando(Pdv.Carrinho().somar(3, 50), produtos).isEmpty())
    }

    @Test fun descontoDoPixSeguraAsRegrasDaLoja() {
        val normal = Loja.Produto(1, "Gel", true, 10, 10_000)
        val promo = Loja.Produto(2, "Vibrador", true, 10, 8_000, precoAntigo = 10_000)
        val produtos = listOf(normal, promo)
        val c = Pdv.Carrinho().somar(1, 2).somar(2, 1)
        val regras = RegrasDeVenda() // Pix 5%, não vale em promoção
        // o normal ganha 5% (9.500 cada); o de promoção fica no preço de hoje
        assertEquals(9_500, Pdv.precoNoPix(normal, regras))
        assertEquals(8_000, Pdv.precoNoPix(promo, regras))
        assertEquals(2 * 9_500 + 8_000, Pdv.totalNoPix(c, produtos, regras))
        assertEquals(2 * 10_000 + 8_000, Pdv.total(c, produtos))
        assertEquals(listOf("Vibrador"), Pdv.semDescontoNoPix(c, produtos, regras))
        // a regra deixando, a promoção também ganha
        assertEquals(7_600, Pdv.precoNoPix(promo, RegrasDeVenda(pixValeEmPromocao = true)))
        // loja sem desconto no Pix: nada muda e nada é avisado
        val sem = RegrasDeVenda(pixDescontoPct = 0)
        assertEquals(Pdv.total(c, produtos), Pdv.totalNoPix(c, produtos, sem))
        assertTrue(Pdv.semDescontoNoPix(c, produtos, sem).isEmpty())
        // preço antigo igual ou menor que o atual não é promoção
        assertEquals(false, Pdv.emPromocao(Loja.Produto(3, "X", true, null, 5_000, precoAntigo = 5_000)))
    }

    @Test fun calculadoraDoDinheiro() {
        var v = 0L
        for (t in listOf("1", "5", "0", "0")) v = Pdv.teclar(v, t)
        assertEquals(1500L, v)
        assertEquals(150L, Pdv.teclar(v, "apagar"))
        assertEquals(150000L, Pdv.teclar(v, "00"))
        assertEquals(0L, Pdv.teclar(v, "limpar"))
        assertEquals(0L, Pdv.teclar(0L, "00"))
        assertEquals(99_999_999L, Pdv.teclar(99_999_999L, "7"))
        assertEquals(99_999_999L, Pdv.teclar(99_999_999L, "00"))
        assertEquals(1500L, Pdv.teclar(1500L, "x"))
    }

    @Test fun troco() {
        assertEquals(150, Pdv.troco(850, 1000))
        assertEquals(0, Pdv.troco(850, 850))
        assertNull(Pdv.troco(850, 800))
    }

    @Test fun liquidoDoCartao() {
        assertEquals(Pair(35, 965), Pdv.liquido(1000, 3.49))
        assertEquals(Pair(0, 1000), Pdv.liquido(1000, 0.0))
        assertEquals(Pair(1000, 0), Pdv.liquido(1000, 150.0))
    }

    @Test fun lePorcentagem() {
        assertEquals(3.49, Pdv.lerPct("3,49")!!, 0.0001)
        assertNull(Pdv.lerPct("abc"))
        assertNull(Pdv.lerPct("-1"))
    }

    @Test fun codigoDaEtiquetaVaiEVolta() {
        assertEquals("venus:p:42", Pdv.codigoDoProduto(42))
        assertEquals(42, Pdv.lerCodigo(Pdv.codigoDoProduto(42)))
        assertEquals(7, Pdv.lerCodigo("  venus:p:7 "))
    }

    @Test fun qrDeOutraCoisaNaoEEtiqueta() {
        assertNull(Pdv.lerCodigo("https://exemplo.com"))
        assertNull(Pdv.lerCodigo("venus:p:abc"))
        assertNull(Pdv.lerCodigo("venus:p:0"))
        assertNull(Pdv.lerCodigo(null))
    }

    @Test fun gradeDeEtiquetas() {
        val g = Pdv.grade(600, 800, 3, 5, 20)
        assertEquals(15, g.size)
        assertEquals(20f, g[0].x, 0.01f)
        assertEquals(20f, g[0].y, 0.01f)
        assertEquals(560f / 3, g[0].largura, 0.01f)
        assertTrue(g[1].x > g[0].x && g[3].y > g[0].y)
        assertTrue(g.all { it.x + it.largura <= 580.01f && it.y + it.altura <= 780.01f })
    }

    @Test fun vendaDeBalcaoEmLojaComSiteViraPedidoPago() {
        val itens = listOf(PdvVendas.Linha(5, "Água", 2, 300), PdvVendas.Linha(6, "Pastel", 1, 850))
        val v1 = PdvVendas.nova(emptyList(), itens, "Pix", 1_000L)
        assertEquals(1_000_000, v1.id)
        assertEquals(1450, v1.total)
        val v2 = PdvVendas.nova(listOf(v1), itens, "Dinheiro", 2_000L)
        assertEquals(1_000_001, v2.id)
        val p = PdvVendas.comoPedido(v1)
        assertEquals("paid", p.status)
        assertEquals(1450, p.totalCentavos)
        assertEquals(listOf("Água", "Pastel"), p.itens.map { it.nome })
    }

    @Test fun cpfConfereOsDoisDigitos() {
        assertEquals("52998224725", Pdv.cpfValido("529.982.247-25"))
        assertEquals("52998224725", Pdv.cpfValido(" 52998224725 "))
        assertNull(Pdv.cpfValido("529.982.247-24")) // dígito errado
        assertNull(Pdv.cpfValido("111.111.111-11")) // todos iguais não vale
        assertNull(Pdv.cpfValido("1234"))
        assertNull(Pdv.cpfValido(""))
        assertEquals("529.982.247-25", Pdv.cpfBonito("52998224725"))
    }

    @Test fun telefoneParaWhatsApp() {
        assertEquals("5551999990000", Pdv.telefoneParaWhatsApp("(51) 99999-0000"))
        assertEquals("5551999990000", Pdv.telefoneParaWhatsApp("+55 51 99999 0000"))
        assertEquals("555133334444", Pdv.telefoneParaWhatsApp("51 3333-4444")) // fixo de 10 dígitos
        assertNull(Pdv.telefoneParaWhatsApp("99999-0000")) // sem DDD
        assertNull(Pdv.telefoneParaWhatsApp("(51) 89999-0000")) // celular de 11 dígitos tem de começar com 9
        assertNull(Pdv.telefoneParaWhatsApp(""))
    }

    @Test fun textoDoReciboEntraCpfSoSeOClienteQuis() {
        val itens = listOf(Pdv.LinhaDoRecibo("Camisa", 2, 4000), Pdv.LinhaDoRecibo("Boné", 1, 1990))
        val com = Pdv.textoDoRecibo("Loja Teste", "B7", "26/11/2026", itens, "Pix", "Obrigada!", "52998224725")
        assertTrue(com.contains("*Loja Teste*"))
        assertTrue(com.contains("2x Camisa — R\$ 80,00"))
        assertTrue(com.contains("*Total: R\$ 99,90*"))
        assertTrue(com.contains("CPF na nota: 529.982.247-25"))
        val sem = Pdv.textoDoRecibo("Loja Teste", "B7", "26/11/2026", itens, "Pix", "", null)
        assertTrue(!sem.contains("CPF"))
        assertTrue(sem.contains("sem valor fiscal"))
    }

    @Test fun linkDoWhatsAppCodificaOTexto() {
        val l = Pdv.linkWhatsApp("5551999990000", "Total: R\$ 10,00 & obrigado")
        assertTrue(l.startsWith("https://wa.me/5551999990000?text="))
        assertTrue(!l.contains(" ") && !l.contains("+") && l.contains("%26"))
        assertTrue(Pdv.linkWhatsApp(null, "oi").startsWith("https://wa.me/?text="))
    }

    @Test fun numeroDaVendaDeBalcaoComecaEmUm() {
        assertEquals(1, PdvVendas.numeroAmigavel(1_000_000))
        assertEquals(12, PdvVendas.numeroAmigavel(1_000_011))
        assertEquals(5, PdvVendas.numeroAmigavel(5)) // venda do catálogo interno já tem número pequeno
    }
}
