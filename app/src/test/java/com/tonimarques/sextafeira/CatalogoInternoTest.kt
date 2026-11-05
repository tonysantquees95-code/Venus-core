package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoInternoTest {
    private val raiz = JSONObject(
        """{"proximoId":3,"produtos":[
          {"id":1,"name":"Vestido Floral","category":"Vestidos","description":"Leve","priceCents":8990,"compareAtCents":11990,"costCents":4000,"stockQuantity":3,"active":true,"imageUrl":"file:///data/x.jpg"},
          {"id":2,"name":"Bolsa Couro","category":"","priceCents":null,"stockQuantity":null,"active":true,"imageUrl":null}
        ],"vendas":[
          {"id":1,"cliente":"Maria","itens":[{"produtoId":1,"nome":"Vestido Floral","quantidade":2,"precoCents":8990}],"totalCents":17980,"status":"paid","criado":1000,"pago":1000},
          {"id":2,"cliente":"Ana","itens":[],"totalCents":0,"status":"canceled","criado":2000}
        ]}""",
    )

    @Test fun leProdutos() {
        val p = CatalogoInterno.lerProdutos(raiz)
        assertEquals(2, p.size)
        assertEquals(8990, p[0].precoCentavos)
        assertEquals(11990, p[0].precoAntigo)
        assertEquals(4000, p[0].custoCentavos)
        assertEquals(3, p[0].estoque)
        assertEquals("", p[0].link)
        assertNull(p[1].precoCentavos)
        assertNull(p[1].estoque)
        // o bruto sempre tem o campo de custo: a Vênus sabe que este catálogo guarda custo
        assertTrue(p[1].bruto!!.has(Loja.CAMPO_CUSTO))
    }

    @Test fun oQueFalta() {
        val p = CatalogoInterno.lerProdutos(raiz)
        assertTrue(CatalogoInterno.faltando(p[0]).isEmpty())
        assertEquals(listOf("sem preço", "sem foto"), CatalogoInterno.faltando(p[1]))
    }

    @Test fun leVendasComoPedidos() {
        val v = CatalogoInterno.lerVendas(raiz)
        assertEquals(listOf(2, 1), v.map { it.id })
        assertEquals("paid", v[1].status)
        assertEquals(1000L, v[1].pago)
        assertEquals(2, v[1].itens[0].quantidade)
        assertNull(v[0].pago)
    }

    @Test fun vendaPeloChat() {
        val produtos = CatalogoInterno.lerProdutos(raiz)
        val p = Loja.montarAlteracao(produtos, "venda", "vestido floral", 2)
        assertEquals("venda", p.alteracao?.tipo)
        assertTrue(p.texto.contains("R$ 179,80") || p.texto.contains("179,80"))
        assertTrue(p.texto.contains("fica com 1"))
        assertNull(Loja.montarAlteracao(produtos, "venda", "vestido floral", 5).alteracao)
    }

    @Test fun catalogoEmPdfSoComPreco() {
        val lista = GeradorDeCatalogo.produtosDoCatalogo(CatalogoInterno.lerProdutos(raiz), comEsgotados = false)
        assertEquals(listOf("Vestido Floral"), lista.map { it.nome })
        assertEquals("Vestidos", GeradorDeCatalogo.categoria(lista[0]))
    }

    @Test fun comandos() {
        assertEquals("catalogo", (ComandosLocais.instagram("abre o catalogo") as ComandosLocais.Resultado.Acao).nome)
        assertEquals("catalogo", (ComandosLocais.instagram("gera o catalogo em pdf") as ComandosLocais.Resultado.Acao).nome)
        assertEquals("catalogo_venda", (ComandosLocais.instagram("registrar venda") as ComandosLocais.Resultado.Acao).nome)
        assertNull(ComandosLocais.instagram("esconde o vestido do catalogo"))
    }
}
