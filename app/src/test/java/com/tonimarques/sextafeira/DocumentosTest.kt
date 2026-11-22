package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentosTest {
    private val loja = Documentos.Emissor("Loja Teste")

    private fun pedido(endereco: String = "", entrega: String = "shipping", rastreio: String? = null) = Loja.Pedido(
        id = 1042, status = "paid", totalCentavos = 20990, cliente = " Maria Souza ", entrega = entrega,
        criado = 1_700_000_000_000L, pago = 1_700_000_500_000L,
        itens = listOf(Loja.Item("Conjunto renda", 2, 8990), Loja.Item("Meia", 1, 3010)),
        telefone = "5551999998888", rastreio = rastreio, descontoCentavos = 0, freteCentavos = 0, endereco = endereco,
    )

    @Test fun dinheiroEmReaisComMilhar() {
        assertEquals("R$ 0,05", Documentos.dinheiro(5))
        assertEquals("R$ 89,90", Documentos.dinheiro(8990))
        assertEquals("R$ 1.234,56", Documentos.dinheiro(123456))
        assertEquals("R$ 1.000.000,00", Documentos.dinheiro(100_000_000))
        assertEquals("-R$ 10,00", Documentos.dinheiro(-1000))
    }

    @Test fun dataEMesNoFusoDeBrasilia() {
        // 2023-11-02 02:30 UTC ainda é 1º de novembro à noite em Brasília
        val t = 1_698_892_200_000L
        assertEquals("01/11/2023", Documentos.dataBR(t))
        assertEquals("2023-11", Documentos.mesDe(t))
        assertEquals("", Documentos.dataBR(0))
    }

    @Test fun telefoneBonito() {
        assertEquals("(51) 99999-8888", Documentos.telefoneBonito("5551999998888"))
        assertEquals("(51) 3333-4444", Documentos.telefoneBonito("(51) 3333-4444"))
        assertEquals("abc", Documentos.telefoneBonito("abc"))
    }

    @Test fun enderecoEmLinhasSemBuracos() {
        assertEquals(
            listOf("Rua das Flores, 100 - ap 2", "Centro", "Porto Alegre/RS", "CEP 90000-000"),
            Documentos.enderecoEmLinhas("Rua das Flores", "100", "ap 2", "Centro", "Porto Alegre", "rs", "90000000"),
        )
        assertEquals(listOf("Rua A, 5", "Porto Alegre/RS"), Documentos.enderecoEmLinhas("Rua A", "5", "", "", "Porto Alegre", "RS", "123"))
        assertTrue(Documentos.enderecoEmLinhas("", "", "", "", "", "", "").isEmpty())
    }

    @Test fun doPedidoLevaNumeroClienteEEndereco() {
        val d = Documentos.doPedido(pedido("Rua A, 5\nCentro\n\nPorto Alegre/RS"), loja)
        assertEquals("1042", d.numero)
        assertEquals("Maria Souza", d.cliente)
        assertEquals("(51) 99999-8888", d.telefone)
        assertEquals(listOf("Rua A, 5", "Centro", "Porto Alegre/RS"), d.enderecoLinhas)
        assertEquals(2 * 8990 + 3010, d.subtotalCentavos)
        assertFalse(d.retirada)
        assertEquals("14/11/2023", d.data)
    }

    @Test fun retiradaERastreio() {
        val d = Documentos.doPedido(pedido(entrega = "pickup", rastreio = " AB123 "), loja)
        assertTrue(d.retirada)
        assertEquals("AB123", d.rastreio)
        assertNull(Documentos.doPedido(pedido(rastreio = "  "), loja).rastreio)
    }

    @Test fun avulsoTemUmItemComOValor() {
        val d = Documentos.avulso(loja, "A1", 1_700_000_000_000L, " Ana ", "", 5000)
        assertEquals("Ana", d.cliente)
        assertEquals(1, d.itens.size)
        assertEquals("Pagamento", d.itens[0].nome)
        assertEquals(5000, d.totalCentavos)
    }

    @Test fun nomeDoArquivoPassaNoFotoProvider() {
        val n = Documentos.nomeDoArquivo(Documentos.Tipo.ETIQUETA, "A-1042/Ç")
        assertTrue(Regex("^[a-z0-9_-]+\\.(?:jpg|png|pdf)$").matches(n))
        assertEquals("recibo-1042.pdf", Documentos.nomeDoArquivo(Documentos.Tipo.RECIBO, "1042"))
        assertEquals("notinha-avulso.pdf", Documentos.nomeDoArquivo(Documentos.Tipo.NOTINHA, "///"))
    }

    @Test fun limiteDe15PorMesNoPlanoGratis() {
        var cota: Documentos.Cota? = null
        for (i in 1..15) {
            assertTrue(Documentos.podeGerar(cota, "2026-11", Documentos.Tipo.RECIBO, "$i", false))
            cota = Documentos.registrar(cota, "2026-11", Documentos.Tipo.RECIBO, "$i")
        }
        assertEquals(15, Documentos.usados(cota, "2026-11"))
        assertEquals(0, Documentos.restantes(cota, "2026-11"))
        assertFalse(Documentos.podeGerar(cota, "2026-11", Documentos.Tipo.RECIBO, "16", false))
        assertFalse(Documentos.podeGerar(cota, "2026-11", Documentos.Tipo.NOTINHA, "1", false)) // outro tipo do mesmo pedido é outro documento
    }

    @Test fun refazerOMesmoDocumentoNaoGastaOutro() {
        var cota: Documentos.Cota? = null
        for (i in 1..15) cota = Documentos.registrar(cota, "2026-11", Documentos.Tipo.RECIBO, "$i")
        assertTrue(Documentos.podeGerar(cota, "2026-11", Documentos.Tipo.RECIBO, "7", false))
        cota = Documentos.registrar(cota, "2026-11", Documentos.Tipo.RECIBO, "7")
        assertEquals(15, Documentos.usados(cota, "2026-11"))
    }

    @Test fun viradaDoMesZera() {
        var cota: Documentos.Cota? = null
        for (i in 1..15) cota = Documentos.registrar(cota, "2026-11", Documentos.Tipo.RECIBO, "$i")
        assertTrue(Documentos.podeGerar(cota, "2026-12", Documentos.Tipo.RECIBO, "99", false))
        assertEquals(0, Documentos.usados(cota, "2026-12"))
        assertEquals(1, Documentos.usados(Documentos.registrar(cota, "2026-12", Documentos.Tipo.RECIBO, "99"), "2026-12"))
    }

    @Test fun planoPagoSemLimiteESemMarca() {
        var cota: Documentos.Cota? = null
        for (i in 1..40) cota = Documentos.registrar(cota, "2026-11", Documentos.Tipo.RECIBO, "$i")
        assertTrue(Documentos.podeGerar(cota, "2026-11", Documentos.Tipo.RECIBO, "41", true))
        assertFalse(Documentos.comMarca(true))
        assertTrue(Documentos.comMarca(false))
        assertEquals("Sem limite no seu plano.", Documentos.resumoDoUso(cota, "2026-11", true))
        assertEquals("Você usou 3 de 15 documentos grátis neste mês.", Documentos.resumoDoUso(Documentos.Cota("2026-11", setOf("a", "b", "c")), "2026-11", false))
    }

    @Test fun lerReaisAceitaVirgulaEPonto() {
        assertEquals(8990, lerReais("89,90"))
        assertEquals(8990, lerReais("R$ 89.90"))
        assertEquals(123450, lerReais("1.234,50"))
        assertEquals(5000, lerReais("50"))
        assertNull(lerReais(""))
        assertNull(lerReais("abc"))
    }
}
