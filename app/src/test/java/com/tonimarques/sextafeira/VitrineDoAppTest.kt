package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VitrineDoAppTest {
    private fun produto(id: Int, nome: String, ativo: Boolean = true, estoque: Int? = 3, preco: Int? = 5000, antigo: Int? = null, bruto: JSONObject? = null) =
        Loja.Produto(id, nome, ativo, estoque, preco, antigo, bruto)

    @Test
    fun soEntraOQueDaParaVender() {
        val itens = VitrineDoApp.itens(listOf(produto(1, "Conjunto"), produto(2, "Oculto", ativo = false), produto(3, "Sem preço", preco = null), produto(4, "Zerado", preco = 0)))
        assertEquals(1, itens.length())
        assertEquals("1", itens.getJSONObject(0).getString("sku"))
    }

    @Test
    fun precoAntigoMaiorViraPromocao() {
        val i = VitrineDoApp.itens(listOf(produto(1, "Vela", preco = 3990, antigo = 4990))).getJSONObject(0)
        assertEquals(4990, i.getInt("preco_centavos"))
        assertEquals(3990, i.getInt("promocao_centavos"))
        val j = VitrineDoApp.itens(listOf(produto(2, "Vela", preco = 4990, antigo = 3990))).getJSONObject(0)
        assertEquals(4990, j.getInt("preco_centavos"))
        assertFalse(j.has("promocao_centavos"))
    }

    @Test
    fun estoqueDesconhecidoContaComoDisponivelEFotoSoHttps() {
        val bruto = JSONObject().put("imageUrl", "https://img.exemplo.com/a.jpg").put("description", "Renda   fina\n  toque suave").put("category", "acessorios_bdsm")
        val i = VitrineDoApp.itens(listOf(produto(7, "Algema", estoque = null, bruto = bruto))).getJSONObject(0)
        assertEquals(1, i.getInt("estoque"))
        assertEquals("https://img.exemplo.com/a.jpg", i.getString("imagem"))
        assertEquals("Renda fina toque suave", i.getString("descricao"))
        assertTrue(i.getString("categoria").isNotBlank())
        val sem = VitrineDoApp.itens(listOf(produto(8, "X", bruto = JSONObject().put("imageUrl", "http://x.com/a.jpg")))).getJSONObject(0)
        assertFalse(sem.has("imagem"))
    }

    @Test
    fun descricaoDaVitrine() {
        assertEquals("Moda íntima e sex shop · Porto Alegre/RS", VitrineDoApp.descricao("moda íntima e sex shop", "Porto Alegre", "RS"))
        assertEquals("Moda", VitrineDoApp.descricao("moda", "", ""))
    }

    @Test
    fun fotoSoNoCelularVaiParaOServidorMasHttpsNao() {
        val local = JSONObject().put("imageUrl", "file:///data/user/0/app/files/catalogo_fotos/a.jpg")
        val nuvem = JSONObject().put("imageUrl", "https://img.exemplo.com/b.jpg")
        val l = VitrineDoApp.fotosLocais(listOf(produto(1, "A", bruto = local), produto(2, "B", bruto = nuvem), produto(3, "C", ativo = false, bruto = local), produto(4, "D")))
        assertEquals(listOf("1" to "/data/user/0/app/files/catalogo_fotos/a.jpg"), l)
    }

    @Test
    fun fotoComCaminhoDoSiteViraEnderecoCompleto() {
        val b = JSONObject().put("imageUrl", "/_cdn/produtos/a.jpg")
        val i = VitrineDoApp.itens(listOf(produto(1, "A", bruto = b)), "https://loja.exemplo.com/").getJSONObject(0)
        assertEquals("https://loja.exemplo.com/_cdn/produtos/a.jpg", i.getString("imagem"))
        assertFalse(VitrineDoApp.itens(listOf(produto(1, "A", bruto = b))).getJSONObject(0).has("imagem"))
        assertEquals(null, VitrineDoApp.endereco("http://x.com/a.jpg", "https://loja.exemplo.com"))
    }
}
