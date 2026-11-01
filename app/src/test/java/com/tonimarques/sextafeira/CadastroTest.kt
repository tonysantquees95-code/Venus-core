package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadastroTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    @Test fun entendeOCadastro() {
        val a = acao("cadastra o produto Gel de Morango 60ml por 29,90 com 10 unidades")!!
        assertEquals("loja_cadastrar", a.nome)
        assertEquals("Gel de Morango 60ml", a.args["nome"])
        assertEquals(2990, a.args["preco"])
        assertEquals(10, a.args["estoque"])
        val b = acao("novo produto")!!
        assertEquals("loja_cadastrar", b.nome)
        assertNull(b.args["nome"])
        assertEquals("Vibrador bullet rosa", acao("cadastrar um produto novo chamado vibrador bullet rosa")!!.args["nome"])
    }

    @Test fun categoriaECorpo() {
        assertEquals("geis_lubrificantes", Loja.categoriaProvavel("Gel de massagem morango"))
        assertEquals("vibradores", Loja.categoriaProvavel("Vibrador Bullet"))
        assertEquals("fantasias_lingerie", Loja.categoriaProvavel("Conjunto de renda preto"))
        val c = Loja.corpoDoNovo(Loja.NovoProduto("Gel X", "geis_lubrificantes", null, 2990, 5, "https://img", false))
        assertTrue(!c.has("id"))
        assertEquals(2990, c.getInt("priceCents"))
        assertTrue(c.isNull("description"))
        assertTrue(c.isNull("weightGrams"))
        assertEquals(false, c.getBoolean("active"))
    }

    @Test fun textoDaIA() {
        val json = org.json.JSONObject("""{"candidates":[{"content":{"parts":[{"text":"**Gel** de massagem \"morango\"\n com aroma."}]}}]}""")
        assertEquals("Gel de massagem morango com aroma.", Gemini.textoDaResposta(json))
    }
}
