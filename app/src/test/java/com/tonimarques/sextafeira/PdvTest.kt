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
}
