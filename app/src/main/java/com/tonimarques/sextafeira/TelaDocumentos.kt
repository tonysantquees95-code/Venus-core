package com.tonimarques.sextafeira

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.text.InputType
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/*
 * Recibos e etiquetas: a partir de um pedido (ou avulso), gera recibo A4, notinha 80 mm e etiqueta de envio 10x15,
 * com o nome, o logo e os dados da loja. Prévia na tela; compartilhar (WhatsApp), abrir ou imprimir.
 * Sem valor fiscal. Plano grátis: 15 documentos por mês e a marca da Vênus no rodapé; plano pago: sem limite e sem marca.
 * Tudo fica no aparelho: nada é enviado a nenhum servidor.
 */

private const val PREFS_DOCS = "documentos"

private fun Context.prefsDocs() = getSharedPreferences(PREFS_DOCS, Context.MODE_PRIVATE)

internal fun Context.semLimiteDeDocumentos(): Boolean = when (Licenca.estado(this)) {
    is Licenca.Estado.Isenta, is Licenca.Estado.Liberada -> true
    else -> false
}

internal fun Context.cotaDeDocumentos(): Documentos.Cota? {
    val p = prefsDocs()
    val mes = p.getString("mes", null) ?: return null
    return Documentos.Cota(mes, p.getStringSet("feitos", emptySet()).orEmpty().toSet())
}

internal fun Context.guardarCota(c: Documentos.Cota) {
    prefsDocs().edit().putString("mes", c.mes).putStringSet("feitos", c.feitos).apply()
}

internal fun mesAgora() = Documentos.mesDe(System.currentTimeMillis())

/** Os dados da loja que saem nos documentos: nome vem do perfil; o resto a pessoa preenche uma vez. */
private fun MainActivity.emissor(): Documentos.Emissor = emissorDosDocumentos(prefs)

/** Os dados da loja dos documentos a partir de qualquer tela (o Caixa usa o mesmo cabeçalho da notinha). */
internal fun Context.emissorDosDocumentos(prefs: android.content.SharedPreferences): Documentos.Emissor {
    val p = prefsDocs()
    val contato = p.getString("contato", null)?.takeIf { it.isNotBlank() }
        ?: DadosDaLoja.telefoneBonito(DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_WHATSAPP))
    val endereco = p.getString("endereco", "").orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf("${Perfil.cidade}/${Perfil.uf}") }
    return Documentos.Emissor(
        nome = Perfil.loja,
        documento = p.getString("documento", "").orEmpty().trim(),
        contato = contato,
        enderecoLinhas = endereco,
        mensagem = p.getString("mensagem", "Obrigada pela compra! Volte sempre.").orEmpty().trim(),
    )
}

internal fun MainActivity.abrirDocumentos() {
    val loja = ferramentas.loja
    if (!loja.conectada) return mostrarDocumentos(emptyList())
    aviso("Buscando os pedidos…")
    Thread {
        val pedidos = try { loja.pedidos() } catch (e: Exception) { emptyList() }
        runOnUiThread { mostrarDocumentos(pedidos) }
    }.start()
}

private fun MainActivity.mostrarDocumentos(todos: List<Loja.Pedido>) {
    val f = Formulario(this, "Recibos e etiquetas", "Sem valor fiscal · tudo fica no seu aparelho")
    val livre = semLimiteDeDocumentos()
    f.cartao("Seu uso")
    f.adicionar(f.aviso(Documentos.resumoDoUso(cotaDeDocumentos(), mesAgora(), livre) + if (livre) "" else " Nos planos pagos não tem limite nem a marca da Vênus."), 2)
    f.cartao("Sua loja nos documentos")
    val e = emissor()
    f.adicionar(f.aviso(listOf(e.nome, e.documento, e.contato).filter { it.isNotBlank() }.joinToString(" · ") + "\n" + e.enderecoLinhas.joinToString(" · ") +
        (if (GeradorDeCatalogo.arquivoDoLogo(this).exists()) "\nLogo da loja ✓" else "\nSem logo (escolha em Minha loja › Catálogo)")), 2)
    f.lado(
        f.botao("Dados da loja") { f.fechar(); editarDadosDosDocumentos() },
        f.botao("Recibo avulso") { f.fechar(); reciboAvulso() },
    )
    val pedidos = todos.filter { it.status != "canceled" && it.status != "awaiting_payment" }.sortedByDescending { it.criado }.take(12)
    f.cartao("Pedidos recentes")
    if (pedidos.isEmpty()) {
        f.adicionar(f.aviso(if (ferramentas.loja.conectada) "Nenhum pedido pago ainda. Use o recibo avulso." else "A loja ainda não está conectada. Use o recibo avulso ou conecte em Minha loja."), 2)
    }
    for (p in pedidos) {
        f.cartao("Nº ${p.id} · ${p.cliente.take(28)}")
        f.adicionar(f.aviso("${Documentos.dataBR(p.pago ?: p.criado)} · ${Loja.dinheiro(p.totalCentavos)} · " +
            p.itens.joinToString(", ") { "${it.quantidade}x ${it.nome}" }.take(60)), 2)
        f.lado(
            f.botao("Recibo") { f.fechar(); previaDeDocumento(Documentos.Tipo.RECIBO, Documentos.doPedido(p, emissor())) },
            f.botao("Notinha") { f.fechar(); previaDeDocumento(Documentos.Tipo.NOTINHA, Documentos.doPedido(p, emissor())) },
        )
        f.adicionar(f.botao("Etiqueta de envio") { f.fechar(); previaDeDocumento(Documentos.Tipo.ETIQUETA, Documentos.doPedido(p, emissor())) }, 8)
    }
    f.acoes(f.botao("Fechar", primario = true) { f.fechar() })
    f.mostrar()
}

private fun MainActivity.editarDadosDosDocumentos() {
    val f = Formulario(this, "Dados da loja", "Saem no topo do recibo e da notinha e no remetente da etiqueta")
    val p = prefsDocs()
    f.cartao("Identificação")
    f.adicionar(f.aviso("Nome: ${Perfil.loja} (muda em Ajustes › Perfil da loja)."), 2)
    val (bDoc, doc) = f.campo("CNPJ ou CPF (opcional)", p.getString("documento", "").orEmpty(), "00.000.000/0001-00", InputType.TYPE_CLASS_TEXT)
    f.adicionar(bDoc, 8)
    val (bContato, contato) = f.campo("Contato (WhatsApp ou Instagram)", emissor().contato, "(51) 99999-9999", InputType.TYPE_CLASS_TEXT)
    f.adicionar(bContato, 8)
    f.cartao("Endereço do remetente")
    val (bEnd, end) = f.campo("Uma linha por item", p.getString("endereco", "").orEmpty(), "Rua das Flores, 100\nCentro\nSua cidade/UF\nCEP 00000-000",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 4)
    f.adicionar(bEnd, 2)
    f.cartao("Mensagem de agradecimento")
    val (bMsg, msg) = f.campo("Aparece no fim do recibo e da notinha", p.getString("mensagem", "Obrigada pela compra! Volte sempre.").orEmpty(), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 2)
    f.adicionar(bMsg, 2)
    f.acoes(
        f.botao("Salvar", primario = true) {
            p.edit().putString("documento", doc.text.toString().trim()).putString("contato", contato.text.toString().trim())
                .putString("endereco", end.text.toString().trim()).putString("mensagem", msg.text.toString().trim()).apply()
            Diario.anotar(this, "loja", "Dados da loja nos recibos e etiquetas atualizados", "tela")
            f.fechar(); abrirDocumentos()
        },
        f.botao("Voltar") { f.fechar(); abrirDocumentos() },
    )
    f.mostrar()
}

private fun MainActivity.reciboAvulso() {
    val f = Formulario(this, "Recibo avulso", "Para uma venda que não está nos pedidos")
    f.cartao("O que foi pago")
    val (bCli, cli) = f.campo("Nome de quem pagou", "", "Maria Souza")
    f.adicionar(bCli, 2)
    val (bDesc, desc) = f.campo("Descrição", "", "Ex.: Conjunto renda preto")
    f.adicionar(bDesc, 8)
    val (bValor, valor) = f.campo("Valor (R$)", "", "89,90", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    f.adicionar(bValor, 8)
    val aviso = f.aviso("")
    f.adicionar(aviso, 6)
    f.acoes(
        f.botao("Ver o recibo", primario = true) {
            val centavos = lerReais(valor.text.toString())
            if (centavos == null || centavos <= 0) { aviso.text = "Informe o valor, por exemplo 89,90."; return@botao }
            val numero = "A" + java.text.SimpleDateFormat("yyMMddHHmm", java.util.Locale.US).format(java.util.Date())
            f.fechar()
            previaDeDocumento(Documentos.Tipo.RECIBO, Documentos.avulso(emissor(), numero, System.currentTimeMillis(), cli.text.toString(), desc.text.toString(), centavos))
        },
        f.botao("Voltar") { f.fechar(); abrirDocumentos() },
    )
    f.mostrar()
}

/** "89,90" ou "89.90" ou "1.234,50" em centavos; null se não for número. */
internal fun lerReais(texto: String): Int? {
    var t = texto.trim().replace("R$", "").replace(" ", "")
    if (t.isEmpty()) return null
    if (t.contains(',')) t = t.replace(".", "").replace(',', '.')
    val v = t.toBigDecimalOrNull() ?: return null
    return v.multiply(java.math.BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP).toInt()
}

private fun MainActivity.completarDados(d: Documentos.Dados, tipo: Documentos.Tipo) {
    val f = Formulario(this, "Completar dados", "Só vale para este documento")
    f.cartao("Cliente")
    val (bCli, cli) = f.campo("Nome", d.cliente)
    f.adicionar(bCli, 2)
    val (bTel, tel) = f.campo("Telefone", d.telefone, "", InputType.TYPE_CLASS_PHONE)
    f.adicionar(bTel, 8)
    f.cartao("Entrega")
    val (bEnd, end) = f.campo("Endereço (uma linha por item)", d.enderecoLinhas.joinToString("\n"), "Rua, número\nBairro\nCidade/UF\nCEP",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 4)
    f.adicionar(bEnd, 2)
    val (bRast, rast) = f.campo("Código de rastreio (opcional)", d.rastreio.orEmpty(), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
    f.adicionar(bRast, 8)
    val (bObs, obs) = f.campo("Observação (opcional)", d.observacao, "Ex.: frágil", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    f.adicionar(bObs, 8)
    f.acoes(
        f.botao("Usar estes dados", primario = true) {
            val novo = d.copy(
                cliente = cli.text.toString().trim(), telefone = tel.text.toString().trim(),
                enderecoLinhas = end.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() },
                rastreio = rast.text.toString().trim().ifBlank { null }, observacao = obs.text.toString().trim(),
                retirada = d.retirada && end.text.isNullOrBlank(),
            )
            f.fechar(); previaDeDocumento(tipo, novo)
        },
        f.botao("Voltar") { f.fechar(); previaDeDocumento(tipo, d) },
    )
    f.mostrar()
}

private fun MainActivity.previaDeDocumento(tipo: Documentos.Tipo, d: Documentos.Dados) {
    val livre = semLimiteDeDocumentos()
    val marca = Documentos.comMarca(livre)
    val f = Formulario(this, tipo.rotulo, "Nº ${d.numero} · ${d.cliente.ifBlank { "sem nome" }}")
    f.cartao("Prévia")
    val imagem = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(android.graphics.Color.WHITE) }
    f.adicionar(imagem, 2)
    var bitmap: Bitmap? = null
    Thread {
        val b = try { GeradorDeDocumentos.previa(tipo, d, this, marca) } catch (e: Exception) { null }
        runOnUiThread { if (b != null) { bitmap = b; imagem.setImageBitmap(b) } }
    }.start()
    f.aoFechar { bitmap = null }
    val estado = f.aviso(Documentos.resumoDoUso(cotaDeDocumentos(), mesAgora(), livre))
    f.adicionar(estado, 8)
    if (tipo == Documentos.Tipo.ETIQUETA && d.enderecoLinhas.isEmpty() && !d.retirada) {
        f.adicionar(f.aviso("Este pedido não trouxe o endereço. Toque em Completar dados para digitar."), 4)
    }

    /** Gera o PDF (gasta 1 do limite grátis, só se for um documento novo no mês) e entrega o arquivo à ação. */
    fun comArquivo(acao: (File) -> Unit) {
        val agora = mesAgora()
        val cota = cotaDeDocumentos()
        if (!Documentos.podeGerar(cota, agora, tipo, d.numero, livre)) {
            estado.text = "Você usou os ${Documentos.LIMITE_GRATIS} documentos grátis deste mês. Volte no mês que vem ou assine um plano para não ter limite."
            return
        }
        Thread {
            val arquivo = try { GeradorDeDocumentos.gerarPdf(this, tipo, d, marca) } catch (e: Exception) { null }
            runOnUiThread {
                if (arquivo == null) { estado.text = "Não consegui montar o PDF. Tente de novo."; return@runOnUiThread }
                if (!livre) guardarCota(Documentos.registrar(cota, agora, tipo, d.numero))
                Diario.anotar(this, "loja", "${tipo.rotulo} nº ${d.numero} gerado", "tela")
                estado.text = Documentos.resumoDoUso(cotaDeDocumentos(), agora, livre)
                acao(arquivo)
            }
        }.start()
    }

    f.lado(
        f.botao("Abrir") {
            comArquivo { arquivo ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriDoDocumento(arquivo), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Nenhum app de PDF encontrado. Use Compartilhar.", Toast.LENGTH_LONG).show()
                }
            }
        },
        f.botao("Imprimir") { comArquivo { imprimirDocumento(it, tipo, d) } },
    )
    f.adicionar(f.botao("Completar dados") { f.fechar(); completarDados(d, tipo) }, 8)
    f.acoes(
        f.botao("Compartilhar", primario = true) {
            comArquivo { arquivo ->
                val uri = uriDoDocumento(arquivo)
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = android.content.ClipData.newRawUri(tipo.arquivo, uri)
                startActivity(Intent.createChooser(i, "Enviar ${tipo.rotulo.lowercase()}"))
            }
        },
        f.botao("Voltar") { f.fechar(); abrirDocumentos() },
    )
    f.mostrar()
}

private fun uriDoDocumento(arquivo: File): Uri = Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")

private fun MainActivity.imprimirDocumento(arquivo: File, tipo: Documentos.Tipo, d: Documentos.Dados) {
    val gerente = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
    val nome = "${tipo.rotulo} ${d.numero}"
    gerente.print(nome, object : PrintDocumentAdapter() {
        override fun onLayout(antigo: PrintAttributes?, novo: PrintAttributes?, cancelar: CancellationSignal?, retorno: LayoutResultCallback, extras: Bundle?) {
            if (cancelar?.isCanceled == true) { retorno.onLayoutCancelled(); return }
            retorno.onLayoutFinished(
                PrintDocumentInfo.Builder(nome).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true,
            )
        }

        override fun onWrite(paginas: Array<out PageRange>?, destino: ParcelFileDescriptor, cancelar: CancellationSignal?, retorno: WriteResultCallback) {
            try {
                FileInputStream(arquivo).use { entrada -> FileOutputStream(destino.fileDescriptor).use { saida -> entrada.copyTo(saida) } }
                retorno.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                retorno.onWriteFailed(e.message)
            }
        }
    }, null)
}
