package com.tonimarques.sextafeira

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.TextView
import android.widget.Toast

/*
 * Cobrança por link (loja sem site): escolher a plataforma (Mercado Pago ou InfinitePay), passo a passo para
 * pegar a chave, conferir, e mandar o link de uma venda para a cliente.
 */

/** Tela de configuração da cobrança por link. */
internal fun MainActivity.configurarCobranca() {
    val f = Formulario(this, "Cobrança por link", "A ${Marca.nome} cria o link, você manda, ela confere o pagamento")
    var escolhido = Cobranca.provedor(this).ifBlank { "" }

    fun abrir(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(this, "Não encontrei um navegador.", Toast.LENGTH_SHORT).show()
    }

    fun montar() {
        f.limpar()
        f.paragrafo("Quando você registra uma venda e escolhe \"Link de pagamento\", eu crio o link (Pix ou cartão), você manda para a cliente " +
            "no WhatsApp, e eu confiro sozinha a cada 2 minutos. Quando o pagamento entra, dou baixa na venda e te aviso.")
        for (p in Cobranca.PROVEDORES) {
            f.opcaoGrande(p.nome, p.resumo, escolhido == p.id) {
                escolhido = p.id
                montar()
            }
        }
        val estado = f.aviso("")
        when (escolhido) {
            "mercadopago" -> {
                f.cartao("Passo a passo: Mercado Pago")
                f.paragrafo("1. Toque em Abrir o Mercado Pago e entre com a conta da loja.\n" +
                    "2. Toque em Criar aplicação. Dê um nome (ex.: Vênus), escolha Pagamentos online e Checkout Pro, e confirme.\n" +
                    "3. Na aplicação, abra Credenciais de produção (se pedir, ative informando o ramo e o site ou Instagram da loja).\n" +
                    "4. Copie o Access Token (começa com APP_USR-), volte aqui, cole e toque em Salvar e conferir.", 14f, android.graphics.Color.WHITE)
                f.adicionar(f.botao("Abrir o Mercado Pago") { abrir(Cobranca.PROVEDORES.first { it.id == "mercadopago" }.site) }, 12)
                val tem = Cobranca.tokenMercadoPago(this).isNotBlank()
                val (b, campo) = f.campo("Access Token", "", if (tem) "Chave salva ✓ (cole outra só para trocar)" else "APP_USR-…",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                f.adicionar(b, 12)
                f.adicionar(estado)
                f.adicionar(f.aviso("As taxas são as da sua conta do Mercado Pago. O dinheiro cai na sua conta, não passa pela ${Marca.nome}."), 8)
                f.acoes(f.botao("Salvar e conferir", primario = true) {
                    val k = Instagram.limparToken(campo.text.toString()).ifBlank { Cobranca.tokenMercadoPago(this) }
                    if (k.isBlank()) return@botao Toast.makeText(this, "Cole o Access Token.", Toast.LENGTH_SHORT).show()
                    Cobranca.salvarMercadoPago(this, k)
                    conferirCobranca(estado)
                }, f.botao("Fechar") { f.fechar() })
            }
            "infinitepay" -> {
                f.cartao("Passo a passo: InfinitePay")
                f.paragrafo("1. No app da InfinitePay, veja a sua InfiniteTag (no seu perfil; começa com $).\n" +
                    "2. Ainda no app, em Checkout ou Link de pagamento, ligue o \"checkout integrado\" se aparecer essa opção.\n" +
                    "3. Escreva a InfiniteTag aqui (sem o $) e toque em Salvar e conferir.\n" +
                    "4. Faça uma venda-teste de R$ 1,00 com Link de pagamento e pague você mesma, para ver o aviso chegar.", 14f, android.graphics.Color.WHITE)
                val (b, campo) = f.campo("InfiniteTag", Cobranca.infiniteTag(this), "ex.: minhaloja", InputType.TYPE_CLASS_TEXT)
                f.adicionar(b, 12)
                f.adicionar(estado)
                f.adicionar(f.aviso("As taxas são as da sua conta InfinitePay. O dinheiro cai na sua conta, não passa pela ${Marca.nome}."), 8)
                f.acoes(f.botao("Salvar e conferir", primario = true) {
                    if (campo.text.isBlank()) return@botao Toast.makeText(this, "Escreva a InfiniteTag.", Toast.LENGTH_SHORT).show()
                    Cobranca.salvarInfinitePay(this, campo.text.toString())
                    conferirCobranca(estado)
                }, f.botao("Fechar") { f.fechar() })
            }
            else -> f.acoes(f.botao("Fechar", primario = true) { f.fechar() })
        }
        if (Cobranca.configurada(this) && escolhido == Cobranca.provedor(this)) {
            estado.text = "✓ Ligada: ${Cobranca.nomeDoProvedor(this)}."
            f.fecharCartao()
            f.adicionar(f.botao("Desligar a cobrança por link") {
                Cobranca.desligar(this)
                escolhido = ""
                montar()
            }, 16)
        }
    }
    montar()
    f.mostrar()
}

private fun MainActivity.conferirCobranca(estado: TextView) {
    estado.text = "Conferindo…"
    Thread {
        val r = try {
            Result.success(Cobranca.testar(applicationContext))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onSuccess {
                estado.text = "✓ $it"
                ajustes?.atualizar()
            }.onFailure { estado.text = "✗ ${it.message ?: "Não funcionou."}" }
        }
    }.start()
}

/** Cria o link da venda (já registrada como "aguardando pagamento") e abre o compartilhar com a mensagem pronta. */
internal fun MainActivity.cobrarVenda(venda: Loja.Pedido) {
    responder("Criando o link de pagamento de ${Loja.dinheiro(venda.totalCentavos)} no ${Cobranca.nomeDoProvedor(this)}…")
    Thread {
        val r = try {
            val link = Cobranca.criarLink(applicationContext, venda.id, venda.itens, venda.cliente)
            CatalogoInterno(applicationContext).anexarCobranca(venda.id, link.ref, link.url, link.provedor)
            Cobranca.agendar(applicationContext)
            Diario.anotar(applicationContext, "loja", "Link de pagamento criado para a venda nº ${venda.id} (${Loja.dinheiro(venda.totalCentavos)}, ${Cobranca.nomeDoProvedor(applicationContext)})", "tela")
            Result.success(link)
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            r.onFailure {
                responder("Não consegui criar o link: ${it.message}. A venda ficou registrada como aguardando pagamento; você pode cancelar no Catálogo da loja.")
            }.onSuccess { link ->
                val texto = Cobranca.mensagemParaCliente(venda.cliente, venda.totalCentavos, link.url)
                responder("Link pronto: ${link.url}\nManda para a cliente: eu confiro o pagamento a cada 2 minutos e te aviso quando entrar.")
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Enviar o link para a cliente"))
                } catch (e: Exception) {
                }
            }
        }
    }.start()
}
