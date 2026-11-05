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
        assertEquals("venda", acao("vendi 1 gel kgel na mão")!!.args["tipo"])
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

    @Test fun visaoGeralDoEstoque() {
        for (frase in listOf(
            "Como está o meu estoque ?", "Quantos produtos tenho em estoque ?", "como tá o estoque", "me dá um resumo do estoque",
            "quantos itens eu tenho na loja", "o que tem no estoque", "meu estoque", "quantos produtos diferentes eu tenho",
            "e o estoque, como anda?",
        )) {
            assertEquals(frase, "loja_estoque_geral", acao(frase)?.nome)
        }
        // de um produto continua sendo de um produto
        assertEquals("loja_estoque", acao("quantos géis KGEL eu tenho no estoque")!!.nome)
        assertEquals("loja_estoque", acao("qual o estoque do vibrador rosa")!!.nome)
    }

    @Test fun textoDaVisaoGeral() {
        val t = Loja.montarEstoqueGeral(produtos)
        assertTrue(t, t.startsWith("Você tem 3 produtos no site e 1 escondido."))
        assertTrue(t, t.contains("8 unidades"))
        assertTrue(t, t.contains("1 esgotado: Vibrador Rosa Bullet"))
        assertFalse(t, t.contains("sem controle"))
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

    // ------------------------------------------------------------ status do pedido
    @Test fun statusPorVoz() {
        val e = acao("pedido 15 enviado, rastreio AB123456789BR")!!
        assertEquals("loja_status", e.nome)
        assertEquals("shipped", e.args["status"])
        assertEquals(15, e.args["pedido"])
        assertEquals("ab123456789br", e.args["rastreio"])
        assertEquals("shipped", acao("o pedido 15 foi enviado")!!.args["status"])
        assertEquals(null, acao("o pedido 15 foi enviado")!!.args["rastreio"])
        assertEquals("ab 123 456 789 br", acao("enviei o pedido 15 com o código de rastreio AB 123 456 789 BR")!!.args["rastreio"])
        assertEquals("shipped", acao("o rastreio do pedido 15 é AB123456789BR")!!.args["status"])
        assertEquals("preparing", acao("marca o pedido 15 como em separação")!!.args["status"])
        assertEquals("preparing", acao("estou separando o pedido 15")!!.args["status"])
        assertEquals("paid", acao("marca o pedido 12 como pago")!!.args["status"])
        assertEquals("loja_entregue", acao("marca o pedido 15 como entregue")!!.nome)
        assertEquals(15, acao("pedido quinze enviado")!!.args["pedido"])
    }

    @Test fun confirmacaoDeStatus() {
        val p = listOf(
            Loja.Pedido(15, "paid", 8990, "Maria Souza", "sedex", 0, 0, emptyList(), telefone = "51999990000"),
            Loja.Pedido(16, "awaiting_payment", 1990, "Ana", "sedex", 0, null, emptyList()),
            Loja.Pedido(17, "shipped", 1990, "Bia", "sedex", 0, 0, emptyList(), rastreio = "QQ111111111BR"),
        )
        val env = Loja.montarStatus(p, 15, "shipped", "ab 123 456 789 br")
        assertEquals("AB123456789BR", env.alteracao!!.rastreio)
        assertTrue(env.texto, env.texto.contains("WhatsApp"))
        assertNull(Loja.montarStatus(p, 16, "shipped", null).alteracao)
        assertNotNull(Loja.montarStatus(p, 16, "paid", null).alteracao)
        assertNull(Loja.montarStatus(p, 17, "shipped", null).alteracao)
        assertNotNull(Loja.montarStatus(p, 17, "shipped", "AB123456789BR").alteracao)
        assertNull(Loja.montarStatus(p, 15, "shipped", "x1").alteracao)
        val link = Loja.linkAviso(env.alteracao!!)!!
        assertTrue(link, link.startsWith("https://wa.me/5551999990000?text="))
        assertTrue(link.contains("AB123456789BR"))
        assertNull(Loja.linkAviso(Loja.montarStatus(p, 16, "paid", null).alteracao!!))
    }

    // ------------------------------------------------------------ reposição
    @Test fun reposicao() {
        assertEquals("loja_reposicao", acao("o que preciso repor")!!.nome)
        assertEquals("loja_reposicao", acao("Quais produtos eu preciso repor")!!.nome)
        assertEquals("loja_reposicao", acao("tem algo para repor no estoque?")!!.nome)
        assertEquals("estoque_somar", acao("repõe 5 unidades do gel kgel")!!.args["tipo"])
        assertEquals("semana", acao("lista de reposição")!!.args["periodo"])
        assertEquals("hoje", acao("o que foi vendido hoje")!!.args["periodo"])
        assertEquals("mes", acao("quais itens foram vendidos no mês")!!.args["periodo"])
        assertEquals("loja_acabando", acao("o que está acabando")!!.nome)
        assertEquals("loja_resumo", acao("como foram as vendas hoje")!!.nome)

        val agora = java.util.Calendar.getInstance()
        val ped = listOf(
            Loja.Pedido(1, "paid", 0, "A", "sedex", agora.timeInMillis, agora.timeInMillis,
                listOf(Loja.Item("Gel KGEL Morango 60ml", 2, 0), Loja.Item("Vibrador Rosa Bullet", 1, 0))),
            Loja.Pedido(2, "delivered", 0, "B", "sedex", agora.timeInMillis, agora.timeInMillis, listOf(Loja.Item("Gel KGEL Morango 60ml", 1, 0))),
            Loja.Pedido(3, "awaiting_payment", 0, "C", "sedex", agora.timeInMillis, null, listOf(Loja.Item("Kit Dois Corações", 5, 0))),
        )
        val r = Loja.montarReposicao(ped, produtos, "semana", agora)
        assertTrue(r, r.startsWith("Vendidos nos últimos 7 dias: 4 itens em 2 pedidos."))
        assertTrue(r, r.contains("• Gel KGEL Morango 60ml: 3 (restam 3)"))
        assertTrue(r, r.contains("• Vibrador Rosa Bullet: 1 (esgotado)"))
        assertFalse(r, r.contains("Kit"))
    }

    // ------------------------------------------------------------ promoção
    @Test fun promocaoPorVoz() {
        val a = acao("coloca o gel kgel em promoção por 24,90")!!
        assertEquals("promocao", a.args["tipo"])
        assertEquals(2490, a.args["valor"])
        assertEquals("gel kgel", a.args["produto"])
        assertEquals(2490, acao("coloca o gel kgel em promoção de 29,90 por 24,90")!!.args["valor"])
        assertEquals("promocao", acao("faz uma promoção do vibrador rosa por 79 reais")!!.args["tipo"])
        val pct = acao("dá 10% de desconto no gel kgel")!!
        assertEquals("promocao_pct", pct.args["tipo"])
        assertEquals(10, pct.args["valor"])
        assertEquals("promocao_pct", acao("dá 15 por cento de desconto no vibrador")!!.args["tipo"])
        assertEquals("fim_promocao", acao("tira o vibrador rosa da promoção")!!.args["tipo"])
        assertEquals("vibrador rosa", acao("encerra a promoção do vibrador rosa")!!.args["produto"])
        assertEquals("preco", acao("muda o preço do gel kgel para 30")!!.args["tipo"])
    }

    @Test fun confirmacaoDePromocao() {
        val p = Loja.montarAlteracao(produtos, "promocao", "gel kgel", 2490)
        assertTrue(p.texto, p.texto.contains("de R$ 29,90 por R$ 24,90 (17% a menos)"))
        val corpo = Loja.corpoDoProduto(produtos[0].bruto, "promocao", 2490)!!
        assertEquals(2490, corpo.getInt("priceCents"))
        assertEquals(2990, corpo.getInt("compareAtCents"))
        assertNull(Loja.montarAlteracao(produtos, "promocao", "gel kgel", 2990).alteracao)
        val pct = Loja.montarAlteracao(produtos, "promocao_pct", "gel kgel", 10)
        assertEquals(2691, pct.alteracao!!.valor)
        // vibrador já em promoção (de 149,90 por 99,90): nova promoção usa o preço cheio
        assertEquals(14990, Loja.corpoDoProduto(produtos[1].bruto, "promocao", 8990)!!.getInt("compareAtCents"))
        val fim = Loja.montarAlteracao(produtos, "fim_promocao", "vibrador rosa", null)
        assertTrue(fim.texto, fim.texto.contains("volta a custar R$ 149,90"))
        val corpoFim = Loja.corpoDoProduto(produtos[1].bruto, "fim_promocao", null)!!
        assertEquals(14990, corpoFim.getInt("priceCents"))
        assertTrue(corpoFim.isNull("compareAtCents"))
        assertNull(Loja.montarAlteracao(produtos, "fim_promocao", "gel kgel", null).alteracao)
    }

    // ------------------------------------------------------------ relatórios e resumo da noite
    @Test fun relatoriosPorVoz() {
        assertEquals("loja_mais_vendidos", acao("quais os produtos mais vendidos")!!.nome)
        assertEquals("mes", acao("quais os produtos mais vendidos")!!.args["periodo"])
        assertEquals("semana", acao("o que mais vendeu essa semana")!!.args["periodo"])
        assertEquals("loja_comparar", acao("compara as vendas com o mês passado")!!.nome)
        assertEquals("loja_comparar", acao("quanto vendi no mês passado")!!.nome)
        assertEquals("loja_parados", acao("quais produtos estão parados")!!.nome)
        assertEquals("loja_parados", acao("o que não está vendendo")!!.nome)
        assertEquals("loja_resumo", acao("como foram as vendas hoje")!!.nome)
    }

    private fun em(dia: Int, mes: Int, hora: Int = 12) = java.util.Calendar.getInstance().apply { clear(); set(2026, mes, dia, hora, 0) }

    @Test fun relatorios() {
        val agora = em(15, java.util.Calendar.OCTOBER, 18)
        fun ped(id: Int, total: Int, quando: java.util.Calendar, itens: List<Loja.Item>) =
            Loja.Pedido(id, "paid", total, "X", "sedex", quando.timeInMillis, quando.timeInMillis, itens)
        val ped = listOf(
            ped(1, 6000, em(2, java.util.Calendar.OCTOBER), listOf(Loja.Item("Gel KGEL Morango 60ml", 2, 3000))),
            ped(2, 10000, em(10, java.util.Calendar.OCTOBER), listOf(Loja.Item("Vibrador Rosa Bullet", 1, 10000), Loja.Item("Gel KGEL Morango 60ml", 1, 3000))),
            ped(3, 4000, em(5, java.util.Calendar.SEPTEMBER), listOf(Loja.Item("Kit Dois Corações", 1, 4000))),
            ped(4, 9000, em(25, java.util.Calendar.SEPTEMBER), listOf(Loja.Item("Kit Dois Corações", 1, 9000))),
        )
        val top = Loja.montarMaisVendidos(ped, "mes", agora)
        assertTrue(top, top.startsWith("Mais vendidos neste mês:\n1. Gel KGEL Morango 60ml: 3 unidades"))
        val comp = Loja.montarComparacao(ped, agora)
        assertTrue(comp, comp.contains("Neste mês até agora: 2 vendas, R$ 160,00"))
        assertTrue(comp, comp.contains("mesmo período do mês passado: 1 venda, R$ 40,00"))
        assertTrue(comp, comp.contains("300% acima"))
        assertTrue(comp, comp.contains("fechou em R$ 130,00"))
        assertTrue(Loja.montarParados(ped, produtos, agora).startsWith("Todos os produtos"))
        val parados = Loja.montarParados(ped, produtos, agora, 15)
        assertTrue(parados, parados.contains("Kit Dois Corações"))
        assertFalse(parados, parados.contains("Gel KGEL"))
        assertFalse(parados, parados.contains("Vibrador")) // vendeu (e está esgotado)
        assertFalse(parados, parados.contains("Óleo")) // escondido
        val resumo = Loja.montarResumoDoDia(ped, produtos, agora)
        assertTrue(resumo, resumo.contains("Para amanhã: 4 pedidos para separar, 1 produto esgotado"))
    }
}
