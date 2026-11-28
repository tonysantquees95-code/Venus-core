package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/*
 * Catálogo da loja: para quem vende sem site (Instagram, WhatsApp, Facebook), os produtos ficam na Vênus.
 * Lista com foto, preço, estoque e o que falta completar; novo produto, nota de compra, venda, logo e o catálogo em PDF.
 * Para quem tem site, a mesma tela gera o catálogo em PDF com os produtos do site.
 */

private fun Int.reais(): String = "%d,%02d".format(this / 100, this % 100)

/** Miniatura de uma foto do catálogo (reduzida, para a lista não pesar). */
private fun miniatura(url: String?, lado: Int): android.graphics.Bitmap? {
    val caminho = url?.takeIf { it.startsWith("file://") }?.removePrefix("file://") ?: return null
    val so = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(caminho, so)
    if (so.outWidth <= 0) return null
    var amostra = 1
    while (so.outWidth / (amostra * 2) >= lado) amostra *= 2
    return BitmapFactory.decodeFile(caminho, BitmapFactory.Options().apply { inSampleSize = amostra })
}

internal fun MainActivity.abrirCatalogo() {
    val loja = ferramentas.loja
    if (!loja.conectada) return responder("A loja ainda não está configurada. Nos ajustes, toque em Plataforma da loja (para quem não tem site: Sem site, catálogo na Vênus).")
    Thread {
        val r = try {
            Result.success(loja.produtos() to (if (loja.ehInterno) loja.pedidos() else emptyList()))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread { r.onSuccess { (p, v) -> mostrarCatalogo(p, v) }.onFailure { responder("Não consegui ler os produtos: ${it.message}") } }
    }.start()
}

private fun MainActivity.mostrarCatalogo(produtos: List<Loja.Produto>, vendas: List<Loja.Pedido>) {
    val interno = ferramentas.loja.ehInterno
    val f = Formulario(this, "Catálogo da loja", if (interno) "Seus produtos, aqui na ${Marca.nome}" else "Catálogo em PDF com os produtos do site")
    val ativos = produtos.filter { it.ativo }
    val semFoto = produtos.count { "sem foto" in CatalogoInterno.faltando(it) }
    val semPreco = produtos.count { it.precoCentavos == null }
    val inicioMes = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
    val doMes = vendas.filter { it.status != "canceled" && it.criado >= inicioMes }

    f.cartao("Resumo")
    f.adicionar(f.aviso(
        "${produtos.size} produtos (${ativos.size} à mostra)" +
            (if (semPreco > 0) " · $semPreco sem preço" else "") + (if (interno && semFoto > 0) " · $semFoto sem foto" else "") +
            (if (interno) " · ${doMes.size} vendas este mês (${Loja.dinheiro(doMes.sumOf { it.totalCentavos })})" else ""),
    ), 2)
    if (interno) {
        f.lado(
            f.botao("Novo produto", primario = true) { f.fechar(); abrirCadastro(emptyMap()) },
            f.botao("Ler nota de compra") { f.fechar(); lerNota(null) },
        )
        f.adicionar(f.aviso("Na nota de compra, a ${Marca.nome} cadastra os itens novos com quantidade e custo; depois você só completa o preço e a foto."), 6)
        f.lado(
            f.botao("Registrar venda") { f.fechar(); registrarVenda(null) },
            f.botao("Catálogo em PDF") { f.fechar(); gerarCatalogoPdf(produtos) },
        )
    } else {
        f.adicionar(f.botao("Gerar catálogo em PDF", primario = true) { f.fechar(); gerarCatalogoPdf(produtos) }, 8)
    }
    f.adicionar(f.botao(if (GeradorDeCatalogo.arquivoDoLogo(this).exists()) "Trocar o logo da loja" else "Escolher o logo da loja") { escolherLogo() }, 8)
    if (interno) f.adicionar(f.botao(if (Cobranca.configurada(this)) "Cobrança por link: ${Cobranca.nomeDoProvedor(this)} ✓" else "Ligar a cobrança por link (Pix e cartão)") {
        f.fechar(); configurarCobranca()
    }, 8)

    // vendas com link de pagamento ainda não pago: reenviar o link ou cancelar (o estoque volta)
    val aguardando = if (interno) vendas.filter { it.status == "awaiting_payment" } else emptyList()
    if (aguardando.isNotEmpty()) {
        f.cartao("Aguardando pagamento")
        f.adicionar(f.aviso("Eu confiro a cada 2 minutos e dou baixa sozinha quando o pagamento entrar."), 2)
        for (v in aguardando.take(10)) {
            f.adicionar(f.aviso("Nº ${v.id} · ${v.cliente} · ${Loja.dinheiro(v.totalCentavos)} · ${v.itens.joinToString(", ") { "${it.quantidade}x ${it.nome}" }.take(50)}"), 10)
            f.lado(
                f.botao("Reenviar link") {
                    val url = Cobranca.linkDaVenda(this, v.id)
                    if (url == null) {
                        f.fechar(); cobrarVenda(v)
                    } else try {
                        startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, Cobranca.mensagemParaCliente(v.cliente, v.totalCentavos, url)), "Enviar o link para a cliente"))
                    } catch (e: Exception) {
                    }
                },
                f.botao("Cancelar venda") {
                    f.fechar()
                    try {
                        CatalogoInterno(this).mudarPedido(v, "canceled", null)
                        Diario.anotar(this, "loja", "Venda nº ${v.id} cancelada (link não pago); estoque devolvido", "tela")
                        responder("Venda nº ${v.id} cancelada e o estoque voltou. Se a cliente pagar o link depois, me avise que eu registro de novo.")
                    } catch (e: Exception) {
                        responder("Não consegui cancelar: ${e.message}")
                    }
                },
            )
        }
    }

    if (interno) {
        val ordem = produtos.sortedWith(compareBy({ CatalogoInterno.faltando(it).isEmpty() }, { it.nome.lowercase() }))
        if (ordem.isEmpty()) {
            f.cartao("Produtos")
            f.adicionar(f.aviso("Nenhum produto ainda. Toque em Novo produto ou leia uma nota de compra."), 2)
        }
        for (p in ordem) {
            f.cartao(p.nome.take(42))
            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                miniatura(p.bruto?.optString("imageUrl"), 160)?.let { setImageBitmap(it) } ?: setBackgroundColor(android.graphics.Color.argb(30, 255, 255, 255))
            }
            linha.addView(img, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(12) })
            val falta = CatalogoInterno.faltando(p)
            val info = f.aviso(
                listOfNotNull(
                    p.precoCentavos?.let { Loja.dinheiro(it) + (p.precoAntigo?.let { a -> " (de ${Loja.dinheiro(a)})" } ?: "") },
                    p.estoque?.let { if (it <= 0) "esgotado" else "$it em estoque" } ?: "estoque livre",
                    p.custoCentavos?.let { "custo ${Loja.dinheiro(it)}" },
                    if (!p.ativo) "escondido" else null,
                ).joinToString(" · ") + if (falta.isNotEmpty()) "\nFalta: ${falta.joinToString(" e ")}" else "",
            )
            linha.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            f.adicionar(linha, 2)
            f.lado(
                f.botao("Editar") { f.fechar(); editarProdutoInterno(p) },
                f.botao("Vendi") { f.fechar(); registrarVenda(p) },
            )
        }
        if (vendas.isNotEmpty()) {
            f.cartao("Últimas vendas")
            val dia = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR"))
            f.adicionar(f.aviso(vendas.take(8).joinToString("\n") { v ->
                "${dia.format(java.util.Date(v.criado))} · ${v.cliente} · ${v.itens.joinToString(", ") { "${it.quantidade}x ${it.nome}" }.take(60)} · ${Loja.dinheiro(v.totalCentavos)}" +
                    if (v.status == "canceled") " (cancelada)" else if (v.status == "awaiting_payment") " (aguardando pagamento)" else ""
            }), 2)
        }
    }
    f.acoes(f.botao("Fechar", primario = true) { f.fechar() })
    f.mostrar()
}

/** Editar um produto do catálogo da Vênus: dados, foto (câmera, galeria, trocar o fundo), mostrar ou esconder, apagar. */
internal fun MainActivity.editarProdutoInterno(p: Loja.Produto) {
    val cat = CatalogoInterno(this)
    val f = Formulario(this, "Editar produto", p.nome)
    var foto: ByteArray? = null
    f.cartao("Produto")
    val (bNome, nome) = f.campo("Nome", p.nome)
    f.adicionar(bNome, 2)
    val (bCat, categoria) = f.campo("Categoria", p.bruto?.optString("category")?.takeIf { it != "null" }.orEmpty(), "Ex.: Vestidos, Bolsas")
    f.adicionar(bCat, 8)
    f.cartao("Preço e estoque")
    val numero = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    val (bPreco, preco) = f.campo("Preço de venda (R$)", p.precoCentavos?.reais().orEmpty(), "29,90", numero)
    val (bCusto, custo) = f.campo("Preço de custo (R$)", p.custoCentavos?.reais().orEmpty(), "15,00", numero)
    f.lado(bPreco, bCusto)
    val (bEstoque, estoque) = f.campo("Estoque (unidades)", p.estoque?.toString().orEmpty(), "Vazio se não controla", InputType.TYPE_CLASS_NUMBER)
    f.adicionar(bEstoque, 8)
    f.cartao("Foto")
    val previa = f.previa()
    f.adicionar(previa, 2)
    miniatura(p.bruto?.optString("imageUrl"), 900)?.let {
        previa.setImageBitmap(it)
        previa.visibility = View.VISIBLE
    }
    val avisoFoto = f.aviso(if (previa.visibility == View.VISIBLE) "Foto atual." else "Sem foto ainda: uma foto boa vende mais.")
    f.adicionar(avisoFoto, 6)
    fun mostrarFoto(bytes: ByteArray) {
        foto = bytes
        previa.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        previa.visibility = View.VISIBLE
        avisoFoto.text = "Foto nova pronta (${bytes.size / 1024} KB)."
    }
    f.lado(f.botao("Câmera") { pedirFotoCamera() }, f.botao("Galeria") { pedirFotoGaleria() })
    f.adicionar(f.botao("Trocar o fundo da foto") {
        val atual = foto ?: p.bruto?.optString("imageUrl")?.takeIf { it.startsWith("file://") }?.let { java.io.File(it.removePrefix("file://")).takeIf { a -> a.exists() }?.readBytes() }
        if (atual == null) Toast.makeText(this, "Escolha a foto primeiro.", Toast.LENGTH_SHORT).show()
        else editarFundo(atual) { nova -> mostrarFoto(nova) }
    }, 10)
    cadastroFoto = { uri ->
        avisoFoto.text = "Preparando a foto…"
        Thread {
            val bytes = FotoProvider.prepararParaEnviar(this, uri)
            runOnUiThread { if (bytes == null) avisoFoto.text = "Não consegui ler essa foto. Tente outra." else mostrarFoto(bytes) }
        }.start()
    }
    f.aoFechar { cadastroFoto = null }
    f.cartao("Descrição")
    val (bDesc, descricao) = f.campo("Descrição (vai para o catálogo)", p.bruto?.optString("description")?.takeIf { it != "null" }.orEmpty(), "Descrição curta",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, linhas = 3)
    f.adicionar(bDesc, 2)
    val mostrar = f.interruptor("Mostrar para as clientes (robô e catálogo)", p.ativo)
    f.adicionar(mostrar, 10)
    f.adicionar(f.botao("Apagar este produto") {
        CaixaBuilder(this).setTitle("Apagar ${p.nome}?").setMessage("Some do catálogo. As vendas já registradas continuam nos relatórios.")
            .setPositiveButton("Apagar") { _, _ ->
                cat.apagar(p.id)
                Diario.anotar(this, "loja", "Produto apagado do catálogo: ${p.nome}", "tela")
                f.fechar()
                responder("Pronto: ${p.nome} saiu do catálogo.")
            }.setNegativeButton("Cancelar", null).show()
    }, 14)

    fun centavos(e: EditText): Int? = e.text.toString().trim().takeIf { it.isNotBlank() }?.let { ComandosLocais.centavos(it.replace(Regex("[^0-9,.]"), "")) }
    f.acoes(f.botao("Salvar", primario = true) {
        val n = nome.text.toString().trim()
        if (n.isBlank()) return@botao Toast.makeText(this, "Informe o nome.", Toast.LENGTH_SHORT).show()
        val v = centavos(preco)
        if (preco.text.isNotBlank() && (v == null || v <= 0)) return@botao Toast.makeText(this, "Confira o preço (ex.: 29,90).", Toast.LENGTH_SHORT).show()
        val c = centavos(custo)
        val qtd = estoque.text.toString().trim().toIntOrNull()
        val fotoNova = foto
        f.fechar()
        Thread {
            val r = try {
                val url = fotoNova?.let { cat.guardarFoto(it) }
                cat.salvar(
                    p.id, nome = n, categoria = categoria.text.toString(), descricao = descricao.text.toString(), precoCentavos = v,
                    custoCentavos = c, estoque = qtd, semEstoque = estoque.text.isBlank(), ativo = mostrar.isChecked, imagemUrl = url,
                )
                Diario.anotar(this, "loja", "Produto do catálogo atualizado: $n" + (v?.let { " (${Loja.dinheiro(it)})" } ?: ""), "tela")
                "Pronto: $n atualizado no catálogo."
            } catch (e: Exception) {
                "Não consegui salvar: ${e.message}"
            }
            runOnUiThread { responder(r) }
        }.start()
    }, f.botao("Cancelar") { f.fechar() })
    f.mostrar()
}

/** Registrar uma venda feita pelo WhatsApp, Instagram ou pessoalmente: baixa o estoque e entra nos relatórios. */
internal fun MainActivity.registrarVenda(inicial: Loja.Produto?) {
    val loja = ferramentas.loja
    if (!loja.ehInterno) return responder("Registrar venda é para a loja sem site (catálogo na ${Marca.nome}). As vendas do site entram sozinhas.")
    val produtos = CatalogoInterno(this).produtos().sortedBy { it.nome.lowercase() }
    if (produtos.isEmpty()) return responder("Cadastre os produtos primeiro (Catálogo da loja → Novo produto).")
    val f = Formulario(this, "Registrar venda", "Baixa o estoque e entra nos relatórios")
    data class Linha(val produto: android.widget.Spinner, val qtd: EditText, val preco: EditText)
    val linhas = mutableListOf<Linha>()
    val nomes = produtos.map { p -> p.nome + (p.precoCentavos?.let { " · ${Loja.dinheiro(it)}" } ?: "") + (p.estoque?.let { " · $it un." } ?: "") }
    fun novaLinha(p: Loja.Produto?) {
        f.cartao(if (linhas.isEmpty()) "Produto" else "Outro produto")
        val sp = f.seletor(nomes, p?.let { x -> produtos.indexOfFirst { it.id == x.id } }?.coerceAtLeast(0) ?: 0)
        f.adicionar(sp, 2)
        val (bQ, q) = f.campo("Quantidade", "1", "", InputType.TYPE_CLASS_NUMBER)
        val (bP, pr) = f.campo("Preço por unidade (R$)", (p ?: produtos.first()).precoCentavos?.reais().orEmpty(), "29,90",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        f.lado(bQ, bP)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                produtos[pos].precoCentavos?.let { pr.setText(it.reais()) }
            }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        linhas += Linha(sp, q, pr)
    }
    novaLinha(inicial)
    f.cartao("Cliente e pagamento")
    val (bCli, cliente) = f.campo("Cliente (opcional)", "", "Ex.: Maria (WhatsApp)")
    f.adicionar(bCli, 2)
    // com a cobrança ligada, o link de pagamento vem primeiro (a Vênus confere e dá baixa sozinha)
    val aceita = Perfil.regras
    val LINK = "Link de pagamento (${aceita.formasDoLink})"
    val pagamentos = (if (Cobranca.configurada(this)) listOf(LINK, "Pix (já pago)", "Cartão (já pago)", "Dinheiro", "Outro")
    else listOf("Pix", "Cartão", "Dinheiro", "Outro", "$LINK: configurar"))
        .filterNot { (!aceita.aceitaPix && it.startsWith("Pix")) || (!aceita.aceitaCartao && it.startsWith("Cartão")) }
    f.adicionar(f.rotulo("Pagamento"), 10)
    val pagamento = f.seletor(pagamentos, 0)
    f.adicionar(pagamento, 4)
    val entregas = listOf("Em mãos ou retirada", "Envio pelos Correios", "Motoboy", "Outro")
    f.adicionar(f.rotulo("Entrega"), 10)
    val entrega = f.seletor(entregas, 0)
    f.adicionar(entrega, 4)
    f.adicionar(f.botao("+ Adicionar outro produto") { novaLinha(null) }, 12)
    f.acoes(f.botao("Registrar", primario = true) {
        val itens = linhas.mapNotNull { l ->
            val p = produtos[l.produto.selectedItemPosition]
            val q = l.qtd.text.toString().trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val v = ComandosLocais.centavos(l.preco.text.toString().replace(Regex("[^0-9,.]"), ""))
            Triple(p, q, v)
        }
        if (itens.isEmpty()) return@botao Toast.makeText(this, "Informe a quantidade.", Toast.LENGTH_SHORT).show()
        val forma = pagamentos[pagamento.selectedItemPosition]
        if (forma.startsWith(LINK) && !Cobranca.configurada(this)) {
            Toast.makeText(this, "Primeiro escolha a plataforma de cobrança.", Toast.LENGTH_SHORT).show()
            return@botao configurarCobranca()
        }
        val porLink = forma == LINK
        f.fechar()
        val venda = try {
            CatalogoInterno(this).registrarVenda(cliente.text.toString(), itens, if (porLink) "Link de pagamento" else forma.removeSuffix(" (já pago)"),
                entregas[entrega.selectedItemPosition], aguardandoPagamento = porLink)
        } catch (e: Exception) {
            return@botao responder("Não consegui registrar: ${e.message}")
        }
        if (porLink) {
            Diario.anotar(this, "loja", "Venda registrada aguardando pagamento por link: ${venda.itens.joinToString(", ") { "${it.quantidade}x ${it.nome}" }} (${Loja.dinheiro(venda.totalCentavos)})", "tela")
            return@botao cobrarVenda(venda)
        }
        Diario.anotar(this, "loja", "Venda registrada: ${venda.itens.joinToString(", ") { "${it.quantidade}x ${it.nome}" }} (${Loja.dinheiro(venda.totalCentavos)})", "tela")
        val faltando = itens.filter { (p, q, _) -> p.estoque != null && p.estoque - q <= 0 }.map { it.first.nome }
        responder("Venda registrada: ${Loja.dinheiro(venda.totalCentavos)} no ${pagamentos[pagamento.selectedItemPosition]}." +
            if (faltando.isNotEmpty()) " Atenção: ${faltando.joinToString(", ")} ${if (faltando.size == 1) "acabou" else "acabaram"} no estoque." else " O estoque já foi atualizado.")
    }, f.botao("Cancelar") { f.fechar() })
    f.mostrar()
}

/** Monta o catálogo em PDF e oferece compartilhar (WhatsApp, Direct, e-mail) ou abrir. */
internal fun MainActivity.gerarCatalogoPdf(produtos: List<Loja.Produto>) {
    responder("Montando o catálogo em PDF com ${GeradorDeCatalogo.produtosDoCatalogo(produtos, false).size} produtos… (com as fotos do site, leva uns segundos)")
    Thread {
        val r = try {
            Result.success(GeradorDeCatalogo.gerar(this, produtos))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onFailure { responder("Não consegui montar o catálogo: ${it.message}") }.onSuccess { arquivo ->
                Diario.anotar(this, "loja", "Catálogo em PDF gerado (${arquivo.length() / 1024} KB)", "tela")
                val uri = Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")
                responder("Catálogo pronto (${arquivo.length() / 1024} KB). Toque em Compartilhar para mandar no WhatsApp ou no Direct.")
                CaixaBuilder(this).setTitle("Catálogo em PDF").setMessage("O que você quer fazer com o catálogo?")
                    .setPositiveButton("Compartilhar") { _, _ ->
                        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        i.clipData = android.content.ClipData.newRawUri("catalogo", uri)
                        startActivity(Intent.createChooser(i, "Enviar catálogo"))
                    }
                    .setNeutralButton("Abrir") { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (e: android.content.ActivityNotFoundException) {
                            Toast.makeText(this, "Nenhum app de PDF encontrado. Use Compartilhar.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Fechar", null).show()
            }
        }
    }.start()
}

/** O logo da loja (para o catálogo em PDF e os stories): escolhido da galeria. */
internal fun MainActivity.escolherLogo() {
    cadastroFoto = { uri ->
        cadastroFoto = null
        Thread {
            val r = try {
                val bytes = FotoProvider.prepararParaEnviar(this, uri, 800) ?: throw IllegalArgumentException("Não consegui ler essa imagem.")
                GeradorDeCatalogo.guardarLogo(this, bytes)
                Diario.anotar(this, "loja", "Logo da loja trocado", "tela")
                "Logo guardado: ele aparece na capa do catálogo e nos stories."
            } catch (e: Exception) {
                "Não consegui usar essa imagem: ${e.message}"
            }
            runOnUiThread { responder(r) }
        }.start()
    }
    responder("Escolha o logo da loja na galeria.")
    pedirFotoGaleria()
}
