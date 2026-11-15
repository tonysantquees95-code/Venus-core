package com.tonimarques.sextafeira

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

/*
 * Loja: perfil do dono, dados e regras de venda, plataforma do site, conexão com a loja e primeiros passos.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

/** Lista do que configurar, com ✓ no que já está pronto; cada item abre o seu ajuste. */
internal fun MainActivity.primeirosPassos() {
    val perfil = getSharedPreferences("perfil", Context.MODE_PRIVATE)
    val passos = listOf(
        Triple("Seu nome e o da loja", perfil.contains("dono")) { editarPerfil() },
        Triple("Site, ramo e regras de venda", perfil.contains("regras_json")) { editarDadosDaLoja() },
        Triple("Plataforma da loja (Shopify, WooCommerce, Nuvemshop ou site próprio)", ferramentas.loja.conectada) { escolherPlataforma() },
        Triple("Robô: acesso às notificações (responde o WhatsApp)", com.tonimarques.sextafeira.robo.OuvinteRobo.temAcesso(this)) { abrirRobo() },
        Triple("Entrar no Vetorial ID (a IA da ${Marca.nome} vem do servidor, sem chave nenhuma)", SegundaIA.doServidorDisponivel(this)) { abrirConta() },
        Triple("Instagram da loja (opcional, precisa da API da Meta)", Instagram.conectado(this)) { conectarInstagram() },
        Triple("Voz neural da Microsoft (opcional)", VozNeural.chave(this).isNotBlank()) { configurarVozNeural() },
    )
    val feitos = passos.count { it.second }
    val itens = passos.map { (nome, feito, acao) -> TelaDeAjustes.Ajuste(nome, { if (feito) "✓ Feito" else "Falta fazer" }) { acao() } }
    TelaDeAjustes(this, versaoDoApp(), listOf(TelaDeAjustes.Secao("Primeiros passos ($feitos de ${passos.size})", itens)), "PRIMEIROS PASSOS").show()
}

internal fun MainActivity.editarPerfil() {
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    fun campo(valor: String, dica: String) = campoDeDialogo(valor, dica).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    val dono = campo(Perfil.dono, "Seu nome")
    val loja = campo(Perfil.loja, "Nome da loja")
    val cidade = campo(Perfil.cidade + if (Perfil.uf.isNotBlank()) ", ${Perfil.uf}" else "", "Cidade e estado (ex.: Porto Alegre, RS)")
    val sobre = campoDeDialogo(Perfil.sobre, "Uma frase sobre você (ex.: trabalho num hotel)")
    listOf(dono, loja, cidade, sobre).forEach { caixa.addView(it) }
    CaixaBuilder(this)
        .setTitle("Perfil do dono e da loja")
        .setMessage("A ${Marca.nome} usa esses dados para falar com você, escrever para as clientes e estudar.")
        .setView(caixa)
        .setPositiveButton("Salvar") { _, _ ->
            if (dono.text.isBlank() || loja.text.isBlank()) return@setPositiveButton Toast.makeText(this, "Preencha seu nome e o da loja.", Toast.LENGTH_SHORT).show()
            Perfil.salvar(this, dono.text.toString(), loja.text.toString(), cidade.text.toString(), sobre.text.toString())
            ajustes?.atualizar()
            responder("Perfil salvo: ${Perfil.resumo()}.")
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

/** Site, ramo e as regras de venda em campos (a fonte única que vai para a memória e para o site). */
internal fun MainActivity.editarDadosDaLoja() {
    val r = Perfil.regras
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    fun secao(t: String) = caixa.addView(TextView(this).apply {
        text = t
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(18), 0, dp(2))
    })
    fun texto(valor: String, dica: String) = campoDeDialogo(valor, dica).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        caixa.addView(this)
    }
    fun numero(valor: Int?, dica: String, decimal: Boolean = false) = campoDeDialogo(valor?.toString().orEmpty(), dica).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        caixa.addView(this)
    }
    fun marcar(valor: Boolean, t: String) = android.widget.CheckBox(this).apply {
        text = t
        isChecked = valor
        Aparencia.pintar(this)
        caixa.addView(this)
    }
    fun reais(c: Int?) = c?.let { String.format(java.util.Locale("pt", "BR"), "%.2f", it / 100.0) }.orEmpty()

    secao("Loja")
    val site = texto(Perfil.site.removePrefix("https://"), if (Plataformas.tipo(this) == "interno") "Endereço do site (vazio = loja sem site)" else "Endereço do site (ex.: minhaloja.com.br)")
    val ramo = texto(Perfil.ramo, "Ramo (ex.: moda íntima e sex shop)")
    secao("Pagamento")
    val pix = numero(r.pixDescontoPct, "Desconto no Pix (%)")
    val pixPromo = marcar(r.pixValeEmPromocao, "Desconto do Pix vale também em produtos em promoção")
    val parcelas = numero(r.cartaoParcelasMax, "Parcelas no cartão (máximo)")
    val semJuros = numero(r.parcelasSemJuros, "Parcelas sem juros (até quantas)")
    secao("Frete e envio")
    val metodos = texto(r.freteMetodos.joinToString(", "), "Formas de envio (ex.: PAC, SEDEX, motoboy)")
    val calculo = texto(r.freteCalculo, "Como o frete é calculado (ex.: pelo CEP no site)")
    val gratis = campoDeDialogo(reais(r.freteGratisAcimaDe), "Frete grátis acima de R$ (vazio = não tem)").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        caixa.addView(this)
    }
    val postagem = numero(r.prazoPostagemDias, "Posta em até quantos dias úteis (vazio = não informar)")
    val abrangencia = texto(r.abrangencia, "Para onde entrega (ex.: todo o Brasil)")
    val discreta = marcar(r.embalagemDiscreta, "Embalagem discreta, sem identificação")
    secao("Retirada")
    val retirada = marcar(r.retiradaDisponivel, "Cliente pode retirar pessoalmente")
    val retCidade = texto(r.retiradaCidade.ifBlank { Perfil.cidade }, "Cidade da retirada")
    val retComo = texto(r.retiradaComo, "Como combina (ex.: dia e horário pelo WhatsApp)")
    val retCusto = campoDeDialogo(reais(r.retiradaCustoCentavos.takeIf { it > 0 }), "Custo da retirada em R$ (vazio = grátis)").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        caixa.addView(this)
    }
    secao("Trocas, atendimento e regras")
    val arrependimento = numero(r.arrependimentoDias, "Dias para desistir da compra (mínimo 7, pela lei)")
    val canais = texto(r.canais.joinToString(", "), "Canais de atendimento (ex.: site, WhatsApp, Instagram)")
    val horario = texto(r.horarioAtendimento.orEmpty(), "Horário de atendimento (ex.: seg a sex, 9h às 18h)")
    val idade = numero(r.idadeMinima, "Idade mínima para comprar (vazio = sem restrição)")
    val obs = EditText(this).apply {
        setText(r.observacoes)
        hint = "Outras regras (texto livre)"
        minLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(24), dp(16), dp(24), dp(16))
        caixa.addView(this)
    }
    CaixaBuilder(this)
        .setTitle("Site, ramo e regras de venda")
        // a área dos campos tem altura fixa e rola por dentro: assim Salvar e Cancelar ficam sempre à vista
        .setView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@editarDadosDaLoja).apply {
                text = "A ${Marca.nome} só promete às clientes o que estiver aqui. Deixe vazio o que a loja não quiser informar."
                textSize = 13f
                setPadding(dp(24), dp(4), dp(24), dp(4))
            })
            addView(ScrollView(this@editarDadosDaLoja).apply { addView(caixa) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt()))
        })
        .setPositiveButton("Salvar") { _, _ ->
            if (site.text.isBlank() && Plataformas.tipo(this) != "interno") return@setPositiveButton Toast.makeText(this, "Preencha o endereço do site.", Toast.LENGTH_SHORT).show()
            fun int(e: EditText) = e.text.toString().trim().toIntOrNull()
            val novas = RegrasDeVenda(
                pixDescontoPct = (int(pix) ?: 0).coerceIn(0, 50),
                pixValeEmPromocao = pixPromo.isChecked,
                cartaoParcelasMax = (int(parcelas) ?: 1).coerceIn(1, 24),
                parcelasSemJuros = (int(semJuros) ?: 1).coerceIn(1, 24),
                freteMetodos = RegrasDeVenda.separar(metodos.text.toString()),
                freteCalculo = calculo.text.toString().trim(),
                freteGratisAcimaDe = RegrasDeVenda.centavosDe(gratis.text.toString()),
                prazoPostagemDias = int(postagem),
                retiradaDisponivel = retirada.isChecked,
                retiradaCidade = retCidade.text.toString().trim(),
                retiradaComo = retComo.text.toString().trim(),
                retiradaCustoCentavos = RegrasDeVenda.centavosDe(retCusto.text.toString()) ?: 0,
                embalagemDiscreta = discreta.isChecked,
                abrangencia = abrangencia.text.toString().trim().ifBlank { "todo o Brasil" },
                arrependimentoDias = (int(arrependimento) ?: 7).coerceAtLeast(7),
                canais = RegrasDeVenda.separar(canais.text.toString()),
                horarioAtendimento = horario.text.toString().trim().ifBlank { null },
                idadeMinima = int(idade),
                observacoes = obs.text.toString().trim(),
            )
            Perfil.salvarLoja(this, site.text.toString(), ramo.text.toString(), novas)
            ajustes?.atualizar()
            Thread { RegrasDaLoja.sincronizarVenda(applicationContext, memoria) }.start()
            val faltam = novas.faltando()
            responder("Regras de venda salvas." + if (faltam.isEmpty()) " Tudo preenchido." else
                " Ficou sem informar: ${faltam.joinToString(", ")}. Se uma cliente perguntar, eu digo que a equipe confirma, sem inventar.")
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.escolherPlataforma() {
    val tipos = Plataformas.TIPOS
    val itens = tipos.map { t -> TelaDeAjustes.Ajuste(t.nome) { configurarPlataforma(t) } }
    TelaDeAjustes(this, versaoDoApp(), listOf(TelaDeAjustes.Secao("Onde fica a sua loja?", itens)), "SUA LOJA", compacto = true).show()
}

internal fun MainActivity.configurarPlataforma(t: Plataformas.Tipo) {
    if (t.id == "interno") {
        Plataformas.salvar(this, Plataformas.Config("interno", "", "", "", ""))
        ajustes?.atualizar()
        return responder("Pronto: loja sem site, com o catálogo aqui na ${Marca.nome}. Abra o Catálogo da loja para cadastrar os produtos " +
            "(pela foto, pela nota de compra ou à mão). O robô do WhatsApp já passa a usar esse catálogo.")
    }
    if (t.id == "floot") {
        Plataformas.salvar(this, Plataformas.Config("floot", "", "", "", ""))
        ajustes?.atualizar()
        return responder("Loja em site próprio. Para conectar, toque em Conectar à loja e entre com o login de administrador do site.")
    }
    val atual = Plataformas.config(this).takeIf { it.tipo == t.id }
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val endereco = campoDeDialogo(atual?.endereco?.removePrefix("https://").orEmpty(),
        if (t.id == "shopify") "Endereço da loja (ex.: minhaloja.myshopify.com)" else "Endereço do site (ex.: minhaloja.com.br)")
    val chave = campoDeDialogo("", if (atual?.chave.isNullOrBlank()) when (t.id) {
        "shopify" -> "Token da Admin API (shpat_…)"
        "woocommerce" -> "Chave (ck_…)"
        else -> "Token de acesso"
    } else "Chave salva (deixe vazio para manter)")
    val segredo = campoDeDialogo("", if (atual?.segredo.isNullOrBlank()) "Segredo (cs_…)" else "Segredo salvo (deixe vazio para manter)")
    val lojaId = campoDeDialogo(atual?.lojaId.orEmpty(), "Número da loja (user_id)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
    if (t.id != "nuvemshop") caixa.addView(endereco)
    caixa.addView(chave)
    if (t.id == "woocommerce") caixa.addView(segredo)
    if (t.id == "nuvemshop") caixa.addView(lojaId)
    CaixaBuilder(this)
        .setTitle(t.nome)
        .setMessage(t.ajuda)
        .setView(caixa)
        .setPositiveButton("Salvar e testar") { _, _ ->
            val c = Plataformas.Config(
                t.id, endereco.text.toString(),
                chave.text.toString().ifBlank { atual?.chave.orEmpty() },
                segredo.text.toString().ifBlank { atual?.segredo.orEmpty() },
                lojaId.text.toString(),
            )
            Plataformas.salvar(this, c)
            ajustes?.atualizar()
            responder("Testando a ${t.nome}…")
            Thread {
                val r = try {
                    val l = Loja(this)
                    Result.success("${l.produtos().size} produtos e ${l.pedidos().size} pedidos recentes")
                } catch (e: Exception) {
                    Result.failure(e)
                }
                runOnUiThread {
                    r.onSuccess { responder("Conectei na ${t.nome}: $it. Agora pergunte, por exemplo: como foram as vendas hoje?") }
                        .onFailure { responder("Não consegui conectar na ${t.nome}: ${it.message}") }
                    ajustes?.atualizar()
                }
            }.start()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.conectarLoja() {
    val loja = ferramentas.loja
    if (loja.conectada) {
        CaixaBuilder(this)
            .setTitle("Desconectar a loja?")
            .setMessage("A ${Marca.nome} deixa de consultar vendas, pedidos e estoque até você conectar de novo.")
            .setPositiveButton("Desconectar") { _, _ ->
                loja.desconectar()
                VigiaDaLoja.definir(this, false)
                ajustes?.atualizar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        return
    }
    val caixa = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), 0)
    }
    val email = campoDeDialogo("", "E-mail do administrador").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }
    val senha = campoDeDialogo("", "Senha").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    caixa.addView(email)
    caixa.addView(senha)
    CaixaBuilder(this)
        .setTitle("Conectar à loja")
        .setMessage("Use o mesmo login do painel do site (conta de administrador).")
        .setView(caixa)
        .setPositiveButton("Conectar") { _, _ ->
            val e = email.text.toString().trim()
            val s = senha.text.toString()
            if (e.isBlank() || s.isBlank()) {
                Toast.makeText(this, "Preencha o e-mail e a senha.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            Toast.makeText(this, "Conectando…", Toast.LENGTH_SHORT).show()
            Thread {
                val erro = loja.conectar(e, s)
                runOnUiThread {
                    ajustes?.atualizar()
                    if (erro == null) {
                        Toast.makeText(this, "Loja conectada!", Toast.LENGTH_SHORT).show()
                        responder("Loja conectada! Pode perguntar: como foram as vendas hoje, tem pedido novo, o que está acabando, ou quanto tem de estoque de um produto. Também posso mudar estoque e preço, esconder ou mostrar produtos, sempre pedindo confirmação, e listar as retiradas para agendar.")
                    } else {
                        Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
