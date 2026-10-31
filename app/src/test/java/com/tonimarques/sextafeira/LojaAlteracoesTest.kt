package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segunda leva da loja: mudanças por voz com confirmação, retiradas e avisos automáticos. */
class LojaAlteracoesTest {

    private fun acao(frase: String): Acao? = ComandosLocais.interpretar(frase) as? Acao

    private fun bruto(id: Int, nome: String, preco: Int?, estoque: Int?, ativo: Boolean = true, promo: Int? = null) = JSONObject()
        .put("id", id).put("category", "geis_lubrificantes").put("name", nome).put("description", JSONObject.NULL)
        .put("priceCents", preco ?: JSONObject.NULL).put("compareAtCents", promo ?: JSONObject.NULL)
        .put("specifications", JSONObject.NULL).put("stockQuantity", estoque ?: JSONObject.NULL)
        .put("weightGrams", JSONObject.NULL).put("widthCm", JSONObject.NULL).put("heightCm", JSONObject.NULL)
        .put("lengthCm", JSONObject.NULL).put("imageUrl", "https://x/img.jpg").put("active", ativo)

    private fun produto(id: Int, nome: String, preco: Int?, estoque: Int?, ativo: Boolean = true, promo: Int? = null) =
        Loja.Produto(id, nome, ativo, estoque, preco, promo, bruto(id, nome, preco, estoque, ativo, promo))

    private val produtos = listOf(
        produto(1, "Gel KGEL Morango 60ml", 2990, 3),
        produto(2, "Vibrador Rosa Bullet", 9990, 0, promo = 14990),
        produto(3, "Óleo de Massagem Canela", 3990, null, ativo = false),
        produto(4, "Kit Dois Corações", 5990, 5),
    )

    // ------------------------------------------------------------ entender a fala
    @Test fun somarEstoque() {
        val a = acao("coloca mais 5 unidades do gel KGEL")!!
        assertEquals("loja_alterar", a.nome)
        assertEquals("estoque_somar", a.args["tipo"])
        assertEquals(5, a.args["valor"])
        assertEquals("gel kgel", a.args["produto"])
        assertEquals("estoque_somar", acao("chegaram 10 unidades do vibrador rosa")!!.args["tipo"])
        assertEquals(3, acao("adiciona três unidades no estoque do óleo de canela")!!.args["valor"])
    }

    @Test fun tirarEDefinirEstoque() {
        val t = acao("tira 2 unidades do estoque do gel KGEL")!!
        assertEquals("estoque_tirar", t.args["tipo"])
        assertEquals(2, t.args["valor"])
        assertEquals("estoque_tirar", acao("vendi 1 gel kgel na mão")!!.args["tipo"])
        val d = acao("muda o estoque do vibrador rosa para 8")!!
        assertEquals("estoque_definir", d.args["tipo"])
        assertEquals(8, d.args["valor"])
        assertEquals("vibrador rosa", d.args["produto"])
        assertEquals(12, acao("o estoque do gel KGEL agora é 12")!!.args["valor"])
        assertEquals("estoque_definir", acao("agora tenho 4 unidades do kit dois corações")!!.args["tipo"])
    }

    @Test fun nomeComNumeroPorExtensoFicaIntacto() {
        val a = acao("muda o estoque do kit dois corações para 7")!!
        assertEquals("kit dois coracoes", a.args["produto"])
        assertEquals(7, a.args["valor"])
    }

    @Test fun preco() {
        val a = acao("muda o preço do vibrador rosa para 129,90")!!
        assertEquals("preco", a.args["tipo"])
        assertEquals(12990, a.args["valor"])
        assertEquals(5000, acao("altera o valor do gel kgel para 50 reais")!!.args["valor"])
        assertEquals(4590, acao("o preço do gel kgel agora é 45 e 90")!!.args["valor"])
        assertEquals(3990, acao("o óleo de canela agora custa R$ 39,90")!!.args["valor"])
        assertEquals(12990, ComandosLocais.centavos("129", "90"))
        assertEquals(1250, ComandosLocais.centavos("12,5"))
    }

    @Test fun mostrarEEsconder() {
        assertEquals("desativar", acao("esconde o vibrador rosa do site")!!.args["tipo"])
        assertEquals("desativar", acao("desativa o produto gel kgel")!!.args["tipo"])
        assertEquals("ativar", acao("volta a mostrar o óleo de canela no site")!!.args["tipo"])
        assertEquals("ativar", acao("reativa o produto óleo de canela")!!.args["tipo"])
    }

    @Test fun naoConfundeComOutrosComandos() {
        assertFalse(acao("desativa o wifi")?.nome == "loja_alterar")
        assertFalse(acao("tira uma foto")?.nome == "loja_alterar")
        assertFalse(acao("coloca 10 minutos no timer")?.nome == "loja_alterar")
        assertEquals("loja_estoque", acao("quanto tem de estoque do gel kgel")!!.nome)
        assertEquals("loja_pedidos", acao("tem pedido novo")!!.nome)
    }

    @Test fun retiradasEPedidos() {
        assertEquals("loja_retiradas", acao("quais retiradas estão para agendar")!!.nome)
        assertEquals("loja_retiradas", acao("tem alguma retirada")!!.nome)
        val ag = acao("agendar retirada do pedido 14")!!
        assertEquals("loja_agendar", ag.nome)
        assertEquals(14, ag.args["pedido"])
        assertEquals("loja_agendar", acao("chama o cliente do pedido 22")!!.nome)
        val e = acao("marca o pedido 14 como entregue")!!
        assertEquals("loja_entregue", e.nome)
        assertEquals(14, e.args["pedido"])
        assertEquals("loja_entregue", acao("o cliente do pedido 9 já retirou")!!.nome)
    }

    // ------------------------------------------------------------ montar a confirmação
    @Test fun confirmacaoDeEstoque() {
        val p = Loja.montarAlteracao(produtos, "estoque_somar", "gel kgel", 5)
        assertTrue(p.texto, p.texto.contains("Fica com 8"))
        assertTrue(p.texto.contains("Diga sim"))
        assertEquals(8, p.alteracao!!.valor)
        val t = Loja.montarAlteracao(produtos, "estoque_tirar", "gel kgel", 5)
        assertNull(t.alteracao)
        assertTrue(t.texto.contains("só tem 3"))
        val semControle = Loja.montarAlteracao(produtos, "estoque_somar", "oleo canela", 2)
        assertNull(semControle.alteracao)
        val definir = Loja.montarAlteracao(produtos, "estoque_definir", "oleo canela", 6)
        assertEquals(6, definir.alteracao!!.valor)
    }

    @Test fun confirmacaoDePrecoAvisaPromocaoEDiferenca() {
        val p = Loja.montarAlteracao(produtos, "preco", "vibrador rosa", 15990)
        assertTrue(p.texto, p.texto.contains("promoção"))
        assertEquals(15990, p.alteracao!!.valor)
        val grande = Loja.montarAlteracao(produtos, "preco", "gel kgel", 990)
        assertTrue(grande.texto.contains("bem diferente"))
    }

    @Test fun produtoAmbiguoOuInexistenteNaoMudaNada() {
        val dois = listOf(produto(1, "Gel Morango", 1000, 2), produto(2, "Gel Menta", 1000, 2))
        assertNull(Loja.montarAlteracao(dois, "preco", "gel", 2000).alteracao)
        assertNull(Loja.montarAlteracao(produtos, "preco", "algema de pelúcia", 2000).alteracao)
    }

    @Test fun corpoDoProdutoMudaSoOCampoPedido() {
        val corpo = Loja.corpoDoProduto(produtos[0].bruto, "preco", 3490)!!
        assertEquals(3490, corpo.getInt("priceCents"))
        assertEquals("Gel KGEL Morango 60ml", corpo.getString("name"))
        assertEquals(3, corpo.getInt("stockQuantity"))
        assertTrue(corpo.getBoolean("active"))
        assertTrue(corpo.isNull("description"))
        assertFalse(Loja.corpoDoProduto(produtos[0].bruto, "desativar", null)!!.getBoolean("active"))
        // faltando campo: não salva
        val incompleto = JSONObject(produtos[0].bruto.toString()).apply { remove("specifications") }
        assertNull(Loja.corpoDoProduto(incompleto, "preco", 100))
    }

    @Test fun simOuNao() {
        assertEquals(true, Loja.simOuNao("sim"))
        assertEquals(true, Loja.simOuNao("Pode confirmar"))
        assertEquals(true, Loja.simOuNao("isso mesmo"))
        assertEquals(false, Loja.simOuNao("não"))
        assertEquals(false, Loja.simOuNao("pode cancelar"))
        assertEquals(false, Loja.simOuNao("deixa pra lá"))
        assertNull(Loja.simOuNao("como foram as vendas hoje"))
    }

    // ------------------------------------------------------------ retiradas
    private val pedidos = listOf(
        Loja.Pedido(11, "paid", 4990, "João Lima", "pickup", 0, 0, listOf(Loja.Item("Gel", 1, 4990)), telefone = "(51) 99876-5432"),
        Loja.Pedido(12, "preparing", 2990, "Maria", "sedex", 0, 0, emptyList()),
        Loja.Pedido(13, "awaiting_payment", 1990, "Ana", "pickup", 0, null, emptyList()),
        Loja.Pedido(14, "delivered", 1990, "Bia", "pickup", 0, 0, emptyList()),
    )

    @Test fun listaRetiradas() {
        val r = Loja.montarRetiradas(pedidos)
        assertTrue(r, r.startsWith("Você tem 1 retirada para combinar"))
        assertTrue(r.contains("Pedido 11 de João"))
        assertFalse(r.contains("Pedido 14"))
    }

    @Test fun linkDoWhatsappDoCliente() {
        val link = Loja.linkAgendar(pedidos[0])!!
        assertTrue(link, link.startsWith("https://wa.me/5551998765432?text="))
        assertTrue(link.contains("%2311"))
        assertNull(Loja.linkAgendar(pedidos[1]))
    }

    @Test fun marcarEntregue() {
        assertNotNull(Loja.montarEntregue(pedidos, 11).alteracao)
        assertNull(Loja.montarEntregue(pedidos, 13).alteracao)
        assertNull(Loja.montarEntregue(pedidos, 14).alteracao)
        assertNull(Loja.montarEntregue(pedidos, 99).alteracao)
    }

    // ------------------------------------------------------------ avisos automáticos
    @Test fun avisaSoONovo() {
        val n = VigiaDaLoja.novidades(pedidos, produtos, setOf(11), emptySet())
        assertEquals(listOf(12, 14), n.pedidosPagos.map { it.id })
        assertEquals(setOf(11, 12, 14), n.pagosVistos)
        // gel (3) não está baixo; vibrador (0) está; óleo está escondido; kit (5) não
        assertEquals(listOf(2), n.acabando.map { it.id })
        val depois = VigiaDaLoja.novidades(pedidos, produtos, n.pagosVistos, n.baixosVistos)
        assertTrue(depois.pedidosPagos.isEmpty())
        assertTrue(depois.acabando.isEmpty())
        // reposto e depois acabando de novo: avisa de novo
        val reposto = produtos.map { if (it.id == 2) it.copy(estoque = 10) else it }
        val limpo = VigiaDaLoja.novidades(pedidos, reposto, n.pagosVistos, n.baixosVistos)
        assertTrue(limpo.baixosVistos.isEmpty())
        assertEquals(listOf(2), VigiaDaLoja.novidades(pedidos, produtos, n.pagosVistos, limpo.baixosVistos).acabando.map { it.id })
    }

    @Test fun textoDoAviso() {
        val t = VigiaDaLoja.textoDoPedido(pedidos[0])
        assertTrue(t, t.contains("retirada"))
        assertTrue(t.contains("João"))
        assertTrue(t.contains("1x Gel"))
    }
}
