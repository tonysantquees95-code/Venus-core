package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central da loja: canais de venda com estoque único, painel ao vivo, auditoria, vitrine e links curtos, marketing
 * e assinatura. Tudo conversa com o servidor da Vênus (Nuvem.kt); o token da loja fica só neste celular.
 */

internal fun JSONArray?.lista(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private val DATA = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
private fun quando(ms: Long) = if (ms <= 0) "nunca" else DATA.format(Date(ms))

internal fun MainActivity.telaDeLista(titulo: String, secoes: List<TelaDeAjustes.Secao>): TelaDeAjustes =
    TelaDeAjustes(this, versaoDoApp(), secoes, titulo).also { it.show() }

/** Faz o pedido ao servidor, mostra o erro se houver e entrega o JSON quando deu certo. */
internal fun MainActivity.pedirNuvem(metodo: String, caminho: String, corpo: JSONObject? = null, carregando: String? = null, ok: (JSONObject) -> Unit) {
    if (carregando != null) aviso(carregando)
    emFundo({ Nuvem.pedir(this, metodo, caminho, corpo) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { resp -> if (resp.ok) ok(resp.json) else aviso(resp.erro()) }
    }
}

internal fun MainActivity.abrirCentral() {
    atualizarIdentidadeDoSite()
    val insta = Instagram.usuario(this).takeIf { Instagram.conectado(this) }
    val pref = prefs.getString(DadosDaLoja.PREF_LINK, "site").orEmpty()
    val whats = DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_WHATSAPP)
    val usa = "Usa: " + listOfNotNull(
        DadosDaLoja.nomeDoLink(pref, Perfil.site).lowercase(), Perfil.site.takeIf { it.isNotBlank() && pref != "vitrine" },
        DadosDaLoja.arroba(insta).takeIf { it.isNotBlank() }, DadosDaLoja.telefoneBonito(whats).takeIf { whats.isNotBlank() },
    ).joinToString(" · ") + "."
    val s = listOf(
        TelaDeAjustes.Secao(
            "Atalhos prontos", listOf(
                TelaDeAjustes.Ajuste("QR Code da minha loja", { usa }) { qrDaLoja() },
                TelaDeAjustes.Ajuste("Panfleto da minha loja", { "Ofertas do catálogo, contato e QR Code, já preenchidos." }) { criarPanfleto() },
                TelaDeAjustes.Ajuste("Cartão de visita", { "Nome, WhatsApp, Instagram e QR Code da loja já preenchidos." }) { criarCartao() },
                TelaDeAjustes.Ajuste("Criar link curto da loja", { "Cria o link curto do endereço da loja, ou reaproveita o que já existe, e já oferece QR, panfleto e cartão." }) { criarLinkCurtoDaLoja() },
                TelaDeAjustes.Ajuste("Meus links curtos", { "Os links que você já criou, cada um com QR Code, panfleto e cartão em um toque." }) { abrirLinksCurtos() },
            ) + (if (insta != null) listOf(TelaDeAjustes.Ajuste("QR Code do Instagram ${DadosDaLoja.arroba(insta)}", { "Já conectado: abre o perfil de quem ler." }) { qrDoInstagram(insta) }) else emptyList()) +
                listOf(TelaDeAjustes.Ajuste("QR Code do Pix", { if (DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_PIX).isBlank()) "Informe a chave Pix uma vez; depois ela fica guardada." else "Usa a chave Pix que você já informou." }) { qrPix() })
        ),
        TelaDeAjustes.Secao(
            "Loja online", listOf(
                TelaDeAjustes.Ajuste("Canais de venda", { "Nuvemshop, Mercado Livre e site próprio (até 3) com um estoque só: vendeu em um, baixa em todos; cancelou, devolveu ou reembolsou, o estoque volta sozinho." }) { abrirCanais() },
                TelaDeAjustes.Ajuste("Painel ao vivo", { "Faturamento bruto contra taxas das plataformas e auditoria do catálogo, atualizando sozinho." }) { abrirPainel() },
                TelaDeAjustes.Ajuste("Auditoria do catálogo", { "Produtos ocultos, sem preço, sem foto e as características de cada um." }) { abrirAuditoria() },
                TelaDeAjustes.Ajuste("Vitrine e links curtos", { "Sua loja numa página só, com link para divulgar e links curtos de produtos." }) { abrirVitrine() },
            )
        ),
        TelaDeAjustes.Secao(
            "Marketing", listOf(
                TelaDeAjustes.Ajuste("Central de Marketing", { "Sugestões de legendas, chamadas e hashtags para os produtos em promoção, numa fila que você controla." }) { abrirMarketing() },
                TelaDeAjustes.Ajuste("Logotipo e cores da marca", { "Cria conceitos de logotipo e mostra os códigos Hex das cores. Também tira as cores de uma foto." }) { abrirLogo() },
                TelaDeAjustes.Ajuste("Cartão de visita e panfleto", { "Cartão com QR Code da vitrine e encarte de ofertas prontos para compartilhar ou imprimir." }) { abrirMateriais() },
                TelaDeAjustes.Ajuste("Dados da loja nos materiais", {
                    val pref = prefs.getString(DadosDaLoja.PREF_LINK, "site").orEmpty()
                    val insta = DadosDaLoja.arroba(Instagram.usuario(this).takeIf { Instagram.conectado(this) })
                    "Link: ${DadosDaLoja.nomeDoLink(pref, Perfil.site)}. Instagram: ${insta.ifBlank { "não conectado" }}. Tudo que já está cadastrado vem pronto no QR Code, no cartão e no panfleto. Toque para trocar o link."
                }) { escolherLinkDosMateriais() },
                TelaDeAjustes.Ajuste("QR Codes", { "Pix, link de pagamento, redes sociais e vitrine, na cor da sua marca." }) { abrirQr() },
            )
        ),
        TelaDeAjustes.Secao(
            "Plano", listOf(
                TelaDeAjustes.Ajuste("Assinatura Plano Fundadores", { "R\$ 49,90 por mês, com 7 dias grátis. Cobrança mensal automática pelo Mercado Pago da Vênus: você não precisa informar nenhuma chave." }) { abrirAssinatura() },
            )
        ),
    )
    telaDeLista("CENTRAL DA LOJA", s)
}

// ------------------------------------------------------------------ canais
internal fun MainActivity.abrirCanais(anterior: TelaDeAjustes? = null) {
    pedirNuvem("GET", "/canais", carregando = if (anterior == null) "Carregando canais…" else null) { json ->
        anterior?.dismiss()
        val canais = json.optJSONArray("canais").lista()
        var tela: TelaDeAjustes? = null
        val conectados = canais.map { c ->
            TelaDeAjustes.Ajuste("${c.optString("nome")} · ${c.optString("conta")}", {
                val status = c.optString("status")
                "Última sincronização: ${quando(c.optLong("ultimoSync"))}. " + if (status == "ok") "Funcionando." else "Atenção: $status"
            }) {
                escolher(c.optString("nome"), listOf(
                    "Sincronizar agora" to { sincronizarCanal(c.optString("id"), tela) },
                    "Taxa da plataforma (para o painel)" to { definirTaxa(c.optString("id"), tela) },
                    "Desconectar" to { desconectarCanal(c.optString("id"), c.optString("nome"), tela) },
                ))
            }
        }
        val conectar = mutableListOf<TelaDeAjustes.Ajuste>()
        if (canais.size < 3) {
            conectar += TelaDeAjustes.Ajuste("Conectar Nuvemshop", { "Você autoriza no navegador e volta para a Vênus." }) { conectarPeloNavegador("nuvemshop") }
            conectar += TelaDeAjustes.Ajuste("Conectar Mercado Livre", { "Você autoriza no navegador e volta para a Vênus." }) { conectarPeloNavegador("mercadolivre") }
            conectar += TelaDeAjustes.Ajuste("Conectar site próprio", { "Qualquer loja com a API simples da Vênus (produtos, pedidos e estoque) e uma chave de acesso." }) { conectarSiteProprio(tela) }
        } else {
            conectar += TelaDeAjustes.Ajuste("Limite de 3 canais atingido", { "Desconecte um canal para conectar outro." }) { }
        }
        val secoes = listOf(
            TelaDeAjustes.Secao("Canais conectados (${canais.size}/3)", if (conectados.isEmpty()) listOf(TelaDeAjustes.Ajuste("Nenhum canal ainda", { "Conecte abaixo. O catálogo e os pedidos entram sozinhos." }) { }) else conectados),
            TelaDeAjustes.Secao("Conectar", conectar),
            TelaDeAjustes.Secao("Estoque", listOf(TelaDeAjustes.Ajuste("Movimentos de estoque", { "Baixas por venda e reposições por cancelamento, devolução ou reembolso." }) { mostrarMovimentos() })),
        )
        tela = telaDeLista("CANAIS DE VENDA", secoes)
    }
}

private fun MainActivity.conectarPeloNavegador(plataforma: String) {
    pedirNuvem("GET", "/canais/oauth/$plataforma/iniciar") { json ->
        val url = json.optString("url")
        if (url.startsWith("https://")) {
            aviso("Autorize no navegador e depois volte para a ${Marca.nome}.")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { aviso("Não achei um navegador.") }
        } else aviso("O servidor não devolveu o endereço de autorização.")
    }
}

private fun MainActivity.conectarSiteProprio(tela: TelaDeAjustes?) {
    perguntar("Site próprio", listOf("Endereço https do site" to "https://", "Chave de acesso" to "", "Apelido do canal (opcional)" to ""), "Conectar") { v ->
        val corpo = JSONObject().put("plataforma", "proprio").put("url", v[0]).put("chave", v[1]).put("nome", v[2])
        pedirNuvem("POST", "/canais", corpo, "Conectando…") { json ->
            val s = json.optJSONObject("sincronizacao")
            aviso("Conectado! " + (s?.let { "${it.optInt("total")} produtos importados." } ?: "") + json.optString("aviso"))
            abrirCanais(tela)
        }
    }
}

private fun MainActivity.sincronizarCanal(id: String, tela: TelaDeAjustes?) {
    pedirNuvem("POST", "/canais/$id/sincronizar", JSONObject(), "Sincronizando…") { json ->
        val p = json.optJSONObject("produtos")
        val o = json.optJSONObject("pedidos")
        aviso("Pronto: ${p?.optInt("total") ?: 0} produtos, ${o?.optInt("total") ?: 0} pedidos, ${p?.optInt("divergentes") ?: 0} com saldo diferente entre canais.")
        abrirCanais(tela)
    }
}

private fun MainActivity.definirTaxa(id: String, tela: TelaDeAjustes?) {
    perguntar("Taxa da plataforma (%)", listOf("Percentual médio cobrado nas vendas" to ""), "Salvar", numerico = true) { v ->
        val pct = v[0].replace(",", ".").toDoubleOrNull()
        if (pct == null) { aviso("Digite um número, por exemplo 12."); return@perguntar }
        pedirNuvem("PUT", "/bi/canais/$id/taxa", JSONObject().put("percentual", pct)) { aviso("Taxa salva. O painel usa quando a plataforma não informa a taxa real."); abrirCanais(tela) }
    }
}

private fun MainActivity.desconectarCanal(id: String, nome: String, tela: TelaDeAjustes?) {
    AlertDialog.Builder(this).setTitle("Desconectar $nome?")
        .setMessage("O estoque deste canal deixa de ser atualizado pela ${Marca.nome}. Os produtos continuam no catálogo.")
        .setPositiveButton("Desconectar") { _, _ -> pedirNuvem("DELETE", "/canais/$id") { aviso("Canal desconectado."); abrirCanais(tela) } }
        .setNegativeButton("Cancelar", null).show()
}

private fun MainActivity.mostrarMovimentos() {
    pedirNuvem("GET", "/estoque/eventos", carregando = "Buscando…") { json ->
        val nomes = mapOf("venda" to "Baixa por venda", "cancelamento" to "Reposição (cancelamento)", "devolucao" to "Reposição (devolução)", "reembolso" to "Reposição (reembolso)")
        val linhas = json.optJSONArray("eventos").lista().take(40).joinToString("\n") {
            "${quando(it.optLong("criado"))} · ${nomes[it.optString("tipo")] ?: it.optString("tipo")} · ${it.optInt("quantidade")} un."
        }
        AlertDialog.Builder(this).setTitle("Movimentos de estoque").setMessage(linhas.ifBlank { "Nenhum movimento ainda." }).setPositiveButton("Ok", null).show()
    }
}

// ------------------------------------------------------------------ painel e auditoria
internal fun MainActivity.abrirPainel() {
    emFundo({ Nuvem.garantirConta(this) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Nuvem.enderecoDoPainel(this)))) } catch (e: Exception) { aviso("Não achei um navegador.") }
        }
    }
}

internal fun MainActivity.abrirAuditoria() {
    pedirNuvem("GET", "/bi/auditoria", carregando = "Varrendo o catálogo…") { j ->
        val r = j.optJSONObject("resumo") ?: JSONObject()
        val txt = StringBuilder()
        txt.append("${r.optInt("total")} produtos: ${r.optInt("visiveis")} visíveis e ${r.optInt("ocultos")} ocultos.\n")
        txt.append("Com preço: ${r.optInt("com_preco")}. Sem preço: ${r.optInt("sem_preco")}.\n")
        txt.append("Sem estoque: ${r.optInt("sem_estoque")}. Sem foto: ${r.optInt("sem_imagem")}. Sem categoria: ${r.optInt("sem_categoria")}.\n")
        txt.append("Sem características: ${r.optInt("sem_atributos")}. Fora de qualquer canal: ${r.optInt("sem_canal")}.\n")
        fun secao(titulo: String, chave: String) {
            val itens = j.optJSONArray(chave).lista()
            if (itens.isNotEmpty()) txt.append("\n$titulo:\n").append(itens.take(8).joinToString("\n") { "• ${it.optString("nome")}" })
        }
        secao("Ocultos com estoque (vale mostrar)", "escondidos_com_estoque")
        secao("Sem preço definido", "sem_preco")
        val carac = j.optJSONArray("caracteristicas").lista()
        if (carac.isNotEmpty()) txt.append("\n\nCaracterísticas mapeadas:\n").append(carac.take(10).joinToString("\n") { "• ${it.optString("nome")}: ${it.optInt("produtos")} produtos" })
        AlertDialog.Builder(this).setTitle("Auditoria do catálogo").setMessage(txt.toString())
            .setPositiveButton("Ok", null).setNeutralButton("Painel ao vivo") { _, _ -> abrirPainel() }.show()
    }
}

// ------------------------------------------------------------------ vitrine e links curtos
internal fun MainActivity.abrirVitrine(anterior: TelaDeAjustes? = null) {
    pedirNuvem("GET", "/loja/vitrine", carregando = if (anterior == null) "Abrindo…" else null) { v ->
        pedirNuvem("GET", "/links") { l ->
            anterior?.dismiss()
            val endereco = v.optString("url")
            var tela: TelaDeAjustes? = null
            val links = l.optJSONArray("links").lista().map { k ->
                TelaDeAjustes.Ajuste(k.optString("rotulo").ifBlank { k.optString("curto") }, { "${k.optString("curto")} · ${k.optInt("cliques")} cliques" }) {
                    acoesDoLink(k.optString("curto"), k.optString("curto"), listOf(
                        "Apagar" to { pedirNuvem("DELETE", "/links/${k.optString("codigo")}") { abrirVitrine(tela) } },
                    ))
                }
            }
            val secoes = listOf(
                TelaDeAjustes.Secao("Sua vitrine", listOf(
                    TelaDeAjustes.Ajuste("Abrir a vitrine", { endereco }) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endereco))) } catch (e: Exception) { aviso("Não achei um navegador.") } },
                    TelaDeAjustes.Ajuste("Copiar e compartilhar o link", { "Mostra só produtos com preço, sem ocultos e com estoque." }) { compartilharTexto(endereco) },
                    TelaDeAjustes.Ajuste("Personalizar", { "Título, descrição, cor, logo e WhatsApp dos pedidos." }) { personalizarVitrine(v, tela) },
                )),
                TelaDeAjustes.Secao("Links curtos", listOf(
                    TelaDeAjustes.Ajuste("Criar link curto", { "De um produto, da vitrine ou de qualquer endereço https, com o código que você escolher." }) { criarLink(tela) },
                ) + links),
            )
            tela = telaDeLista("VITRINE E LINKS", secoes)
        }
    }
}

private fun MainActivity.compartilharTexto(texto: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartilhar"))
}

private fun MainActivity.personalizarVitrine(v: JSONObject, tela: TelaDeAjustes?) {
    perguntar("Personalizar a vitrine", listOf(
        "Título" to v.optString("titulo").ifBlank { Perfil.loja },
        "Descrição curta" to v.optString("descricao"),
        "Cor (#RRGGBB)" to v.optString("cor").ifBlank { Paleta.hex(marcaAtual().cor) },
        "WhatsApp com DDD" to v.optString("whatsapp"),
        "Logo (endereço https, opcional)" to v.optString("logo"),
    ), "Salvar") { c ->
        val corpo = JSONObject().put("titulo", c[0]).put("descricao", c[1]).put("cor", c[2]).put("whatsapp", c[3]).put("logo", c[4])
        pedirNuvem("PUT", "/loja/vitrine", corpo) { aviso("Vitrine atualizada."); abrirVitrine(tela) }
    }
}

private fun MainActivity.criarLink(tela: TelaDeAjustes?) {
    perguntar("Novo link curto", listOf("Código (3 a 32 letras, ex.: promo)" to "", "Rótulo (opcional)" to "", "Destino: https://… ou SKU do produto (vazio = vitrine)" to ""), "Criar") { v ->
        val corpo = JSONObject().put("codigo", v[0]).put("rotulo", v[1])
        when {
            v[2].startsWith("https://") -> corpo.put("destino", v[2])
            v[2].isNotBlank() -> corpo.put("sku", v[2])
            else -> corpo.put("vitrine", true)
        }
        if (v[0].isBlank()) corpo.remove("codigo")
        pedirNuvem("POST", "/links", corpo) { j -> copiar("Link", j.optString("curto")); abrirVitrine(tela) }
    }
}

// ------------------------------------------------------------------ assinatura e 2FA
internal fun MainActivity.abrirAssinatura(anterior: TelaDeAjustes? = null) {
    pedirNuvem("GET", "/assinatura", carregando = if (anterior == null) "Consultando…" else null) { a ->
        anterior?.dismiss()
        var tela: TelaDeAjustes? = null
        val estado = when {
            a.optInt("diasPagosRestantes") > 0 -> "Plano Fundadores ativo: ${a.optInt("diasPagosRestantes")} dias restantes."
            a.optBoolean("emTeste") -> "Teste grátis: ${a.optInt("diasDeTesteRestantes")} dias restantes."
            else -> "Sem acesso ativo. Assine para continuar usando a Central da loja."
        }
        val itens = mutableListOf<TelaDeAjustes.Ajuste>()
        itens += TelaDeAjustes.Ajuste("Situação", { estado }) { }
        itens += TelaDeAjustes.Ajuste("Pagar o Plano Fundadores", { "Assinatura mensal de R\$ 49,90, renovada sozinha. O link já abre com o valor preenchido; não precisa de chave nem de conta sua." }) { cobrar("mercadopago") }
        itens += TelaDeAjustes.Ajuste("Verificação em duas etapas", {
            if (a.optBoolean("doisFatores")) "Ativa. O app autenticador gera o código que libera o acesso." else "Ainda não ativada: necessária para liberar o plano pago."
        }) { if (a.optBoolean("doisFatores")) aviso("A verificação em duas etapas já está ativa.") else ativar2fa(tela) }
        if (a.optBoolean("pagamentoAguardandoLiberacao")) {
            itens += TelaDeAjustes.Ajuste("Liberar meu acesso", { "Pagamento confirmado! Digite o código do app autenticador." }) { liberarAcesso(tela) }
        }
        tela = telaDeLista("ASSINATURA", listOf(TelaDeAjustes.Secao("Plano Fundadores", itens)))
    }
}

private fun MainActivity.cobrar(gateway: String) {
    pedirNuvem("POST", "/assinatura/cobrar", JSONObject().put("gateway", gateway), "Gerando o link…") { j ->
        val url = j.optString("url")
        aviso("Confirme a assinatura no Mercado Pago e volte para a ${Marca.nome}: o acesso é liberado com o código de duas etapas.")
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { copiar("Link de pagamento", url) }
    }
}

private fun MainActivity.ativar2fa(tela: TelaDeAjustes?) {
    pedirNuvem("POST", "/seguranca/2fa/iniciar", JSONObject(), "Preparando…") { j ->
        val segredo = j.optString("segredo")
        val uri = j.optString("otpauth")
        AlertDialog.Builder(this).setTitle("Ativar duas etapas")
            .setMessage("1. Abra seu app autenticador (Google Authenticator, Microsoft Authenticator…).\n2. Adicione uma conta com esta chave:\n\n$segredo\n\n3. Digite o código de 6 números que ele mostrar.")
            .setPositiveButton("Já adicionei") { _, _ ->
                perguntar("Código de 6 números", listOf("Código" to ""), "Confirmar", numerico = true) { v ->
                    pedirNuvem("POST", "/seguranca/2fa/confirmar", JSONObject().put("codigo", v[0])) { aviso("Duas etapas ativadas!"); abrirAssinatura(tela) }
                }
            }
            .setNeutralButton("Copiar chave") { _, _ -> copiar("Chave", segredo) }
            .setNegativeButton("Abrir no autenticador") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (e: Exception) { copiar("Chave", segredo) }
            }.show()
    }
}

private fun MainActivity.liberarAcesso(tela: TelaDeAjustes?) {
    perguntar("Liberar acesso", listOf("Código do app autenticador" to ""), "Liberar", numerico = true) { v ->
        pedirNuvem("POST", "/assinatura/liberar", JSONObject().put("codigo", v[0])) { aviso("Acesso liberado por 30 dias. Obrigada por ser fundador!"); abrirAssinatura(tela) }
    }
}
