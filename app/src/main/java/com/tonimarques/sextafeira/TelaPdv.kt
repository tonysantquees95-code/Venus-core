package com.tonimarques.sextafeira

import android.widget.Toast

private fun Int.brl() = Loja.dinheiro(this)

private const val PREF_TAXA_CARTAO = "pdv_taxa_cartao"

/**
 * Minha loja › Caixa: venda rápida de balcão para quem vende na rua ou em feira. Toca nos produtos, escolhe Pix, dinheiro ou cartão.
 * A venda baixa o estoque, entra nos relatórios e (com o Vyntem ligado) vai para o caixa do Vyntem, tudo pelo mesmo registro de venda.
 */
internal fun MainActivity.abrirCaixa() {
    if (!ferramentas.loja.ehInterno) return responder("O Caixa é para a loja sem site (catálogo na ${Marca.nome}). Nas lojas com site, as vendas entram sozinhas.")
    val produtos = CatalogoInterno(this).produtos().filter { it.ativo && (it.precoCentavos ?: 0) > 0 }.sortedBy { it.nome.lowercase() }
    if (produtos.isEmpty()) return responder("Cadastre produtos com preço primeiro (Catálogo da loja → Novo produto).")

    var carrinho = Pdv.Carrinho()
    var pixMostrado = false
    val f = Formulario(this, "Caixa", "Toque no produto para somar")
    val resumo = f.aviso()
    lateinit var atualizar: () -> Unit
    val botoesPorProduto = mutableMapOf<Int, android.widget.TextView>()

    f.cartao("Produtos")
    produtos.forEach { p ->
        val b = f.botao("") { carrinho = carrinho.somar(p.id); pixMostrado = false; atualizar() }
        botoesPorProduto[p.id] = b
        f.adicionar(b, 6)
    }
    f.cartao("Total")
    f.adicionar(resumo, 4)
    val limpar = f.botao("Limpar") { carrinho = Pdv.Carrinho(); pixMostrado = false; atualizar() }
    val menos = f.botao("Tirar o último") {
        carrinho.linhas.keys.lastOrNull()?.let { carrinho = carrinho.somar(it, -1) }
        pixMostrado = false; atualizar()
    }
    f.lado(menos, limpar)

    fun fechar(forma: String, nota: String) {
        val total = Pdv.total(carrinho, produtos)
        val itens = Pdv.itens(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        val venda = try {
            CatalogoInterno(this).registrarVenda("Cliente do balcão", itens, forma, "Em mãos ou retirada")
        } catch (e: Exception) {
            return responder("Não consegui registrar: ${e.message}")
        }
        Diario.anotar(this, "loja", "Caixa: ${itens.joinToString(", ") { "${it.second}x ${it.first.nome}" }} (${total.brl()}) no $forma", "tela")
        if (CaixaDoVyntem.ligado(this)) Thread { try { CaixaDoVyntem.conferir(applicationContext) } catch (e: Exception) { } }.start()
        carrinho = Pdv.Carrinho(); pixMostrado = false; atualizar()
        Toast.makeText(this, "Venda ${venda.totalCentavos.brl()} registrada. $nota" + if (sem.isNotEmpty()) " Atenção: estoque de ${sem.joinToString(", ")} ficou zerado." else "", Toast.LENGTH_LONG).show()
    }

    val pix = f.botao("Pix", primario = true) {
        val total = Pdv.total(carrinho, produtos)
        if (carrinho.vazio()) return@botao Toast.makeText(this, "Escolha um produto.", Toast.LENGTH_SHORT).show()
        if (pixMostrado) return@botao fechar("Pix", "Estoque atualizado.")
        val chave = DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_PIX)
        fun gerar(k: String) {
            try {
                mostrarQrDoTexto("Pix de ${total.brl()}", QrLocal.pixCopiaECola(k, Perfil.loja, Perfil.cidade, total.toLong()))
                pixMostrado = true; atualizar()
            } catch (e: Exception) { aviso(e.message ?: "Não consegui gerar o Pix.") }
        }
        if (QrLocal.chavePix(chave) != null) gerar(chave)
        else perguntar("Chave Pix da loja", listOf("CPF, CNPJ, celular, e-mail ou chave aleatória" to chave), "Usar") { v ->
            if (QrLocal.chavePix(v[0]) == null) return@perguntar aviso("Chave Pix inválida.")
            DadosDaLoja.lembrar(prefs, DadosDaLoja.PREF_PIX, v[0]); gerar(v[0])
        }
    }
    val dinheiro = f.botao("Dinheiro") {
        val total = Pdv.total(carrinho, produtos)
        if (carrinho.vazio()) return@botao Toast.makeText(this, "Escolha um produto.", Toast.LENGTH_SHORT).show()
        perguntar("Dinheiro · total ${total.brl()}", listOf("Quanto o cliente deu (R$)" to ""), "Dar baixa", numerico = true) { v ->
            val recebido = if (v[0].isBlank()) total else ComandosLocais.centavos(v[0].replace(Regex("[^0-9,.]"), "")) ?: return@perguntar aviso("Valor inválido.")
            val troco = Pdv.troco(total, recebido) ?: return@perguntar aviso("O valor não cobre o total de ${total.brl()}.")
            fechar("Dinheiro", if (troco > 0) "Troco: ${troco.brl()}." else "Sem troco.")
        }
    }
    val cartao = f.botao("Cartão") {
        val total = Pdv.total(carrinho, produtos)
        if (carrinho.vazio()) return@botao Toast.makeText(this, "Escolha um produto.", Toast.LENGTH_SHORT).show()
        perguntar("Cartão · total ${total.brl()}", listOf("Taxa da maquininha (%), opcional" to prefs.getString(PREF_TAXA_CARTAO, "").orEmpty()), "Já passei no cartão", numerico = true) { v ->
            val pct = if (v[0].isBlank()) 0.0 else Pdv.lerPct(v[0]) ?: return@perguntar aviso("Taxa inválida.")
            prefs.edit().putString(PREF_TAXA_CARTAO, v[0]).apply()
            val (taxa, liquido) = Pdv.liquido(total, pct)
            fechar("Cartão", if (taxa > 0) "Taxa ${taxa.brl()}, líquido ${liquido.brl()}." else "")
        }
    }
    f.lado(pix, dinheiro)
    f.adicionar(cartao, 6)
    f.adicionar(f.aviso("Pix: mostre o QR, e quando o dinheiro cair toque em “Pix recebido”. A Vênus não vê o pagamento sozinha."), 6)
    f.acoes(f.botao("Fechar") { f.fechar() })

    atualizar = {
        produtos.forEach { p ->
            val q = carrinho.linhas[p.id] ?: 0
            botoesPorProduto[p.id]?.text = (if (q > 0) "$q× " else "") + p.nome + " · " + (p.precoCentavos ?: 0).brl() + (p.estoque?.let { " · $it un." } ?: "")
        }
        val total = Pdv.total(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        resumo.text = if (carrinho.vazio()) "Nenhum produto ainda." else "${carrinho.unidades()} item(ns) · ${total.brl()}" +
            if (sem.isNotEmpty()) "\nSem estoque suficiente: ${sem.joinToString(", ")}." else ""
        pix.text = if (pixMostrado) "Pix recebido" else "Pix"
    }
    atualizar()
    f.mostrar()
}
