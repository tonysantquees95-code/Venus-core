package com.tonimarques.sextafeira

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tonimarques.sextafeira.robo.TelaBase

private fun Int.brl() = Loja.dinheiro(this)

/**
 * O Caixa: a Vênus vira um ponto de venda, em etapas, como um caixa de loja:
 *   1. Loja (só se a conta tem mais de uma) → 2. Produtos e carrinho → 3. Pagamento (resumo para conferir + forma de pagar) → 4. Recibo (CPF e WhatsApp do cliente, os dois opcionais).
 * Tela cheia, tela sempre ligada, "Sair" em toda etapa. Funciona em qualquer loja (com ou sem site): usa o catálogo e os preços que ela já tem,
 * baixa o estoque (no site, se a loja tem site) e, com o Vyntem ligado, a venda vai para o caixa dele. O CPF do cliente nunca é guardado: só vai no recibo.
 */
class CaixaActivity : TelaBase() {
    private enum class Etapa { LOJA, PRODUTOS, PAGAMENTO, RECIBO }

    /** A venda que acabou de ser fechada (para o recibo). */
    private class Concluida(val numero: String, val linhas: List<Pdv.LinhaDoRecibo>, val forma: String, val nota: String, val quando: Long) {
        val total: Int get() = linhas.sumOf { it.quantidade * it.precoCentavos }
    }

    private var etapa = Etapa.PRODUTOS
    private var lojas: List<Lojas.Item> = emptyList()
    private var produtos: List<Loja.Produto> = emptyList()
    private var carrinho = Pdv.Carrinho()
    private var filtro = ""
    private var concluida: Concluida? = null
    private var gravando = false
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val verde = Color.parseColor("#35D07F")
    private val alerta = Color.parseColor("#FFB454")
    private val linhaCor = Color.parseColor("#2C2546")

    private val variasLojas get() = lojas.size > 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // não apaga a tela enquanto o caixa está aberto
        Perfil.carregar(this)
        carregando("Abrindo o caixa…")
        Thread {
            // a conta pode ter até 3 lojas: se tiver mais de uma, a primeira etapa é escolher onde está vendendo
            val todas = try { if (VetorialId.logado(this)) Lojas.listar(this) else emptyList() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                lojas = todas
                if (variasLojas) { etapa = Etapa.LOJA; desenhar() } else carregarEIr()
            }
        }.start()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (etapa) {
            Etapa.LOJA -> finish()
            Etapa.PRODUTOS -> sair()
            Etapa.PAGAMENTO -> { etapa = Etapa.PRODUTOS; desenhar() }
            Etapa.RECIBO -> novaVenda(perguntar = true)
        }
    }

    private fun sair() {
        if (carrinho.vazio() || etapa == Etapa.RECIBO || etapa == Etapa.LOJA) return finish()
        CaixaBuilder(this).setTitle("Sair do caixa?").setMessage("Tem uma venda em andamento. Se sair, ela é descartada.")
            .setPositiveButton("Sair") { _, _ -> finish() }.setNegativeButton("Continuar vendendo", null).show()
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun carregando(t: String) {
        setContentView(TextView(this).apply {
            text = t; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#03040A"))
        })
    }

    /** O catálogo da loja aberta (com ou sem site) só com o que dá para vender: ativo e com preço. */
    private fun carregarProdutos(): List<Loja.Produto> =
        Loja(this).produtos().filter { it.ativo && (it.precoCentavos ?: 0) > 0 }.sortedBy { it.nome.lowercase() }

    private fun carregarEIr() {
        carregando("Lendo o catálogo da loja…")
        Thread {
            val lido = try { Result.success(carregarProdutos()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                lido.onFailure {
                    aviso("Não consegui ler o catálogo da loja agora. Confira a internet e a conexão da loja.")
                    if (variasLojas) { etapa = Etapa.LOJA; desenhar() } else finish()
                }.onSuccess { lista ->
                    produtos = lista
                    carrinho = Pdv.Carrinho()
                    if (produtos.isEmpty()) {
                        aviso("Cadastre produtos com preço primeiro.")
                        if (variasLojas) { etapa = Etapa.LOJA; desenhar() } else finish()
                    } else { etapa = Etapa.PRODUTOS; desenhar() }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ peças visuais
    private fun fundo(cor: Int, raio: Int = 16, borda: Int = linhaCor) = GradientDrawable().apply {
        cornerRadius = dp(raio).toFloat(); setColor(cor); setStroke(dp(1), borda)
    }

    private fun pequeno(t: String, aoTocar: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; gravity = Gravity.CENTER; setTextColor(destaque)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), destaque) }
        setOnClickListener { aoTocar() }
    }

    private fun vertical(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }

    private fun cabecalhoDeSecao(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 11f; letterSpacing = 0.25f; setTextColor(destaque); setPadding(dp(6), dp(18), 0, dp(8))
    }

    private fun passoAtual(): Int = when (etapa) {
        Etapa.LOJA -> 1
        Etapa.PRODUTOS -> if (variasLojas) 2 else 1
        Etapa.PAGAMENTO -> if (variasLojas) 3 else 2
        Etapa.RECIBO -> if (variasLojas) 4 else 3
    }

    /** A moldura comum de toda etapa: barra de cima (voltar, título, sair), marcador de etapas, o conteúdo e o rodapé fixo. */
    private fun moldura(titulo: String, corpo: View, rodape: View?, voltar: (() -> Unit)?) {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0D0A1F"), Color.parseColor("#03040A")))
        }
        val topo = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(6)) }
        if (voltar != null) topo.addView(pequeno("‹ Voltar") { voltar() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        topo.addView(TextView(this).apply {
            text = "CAIXA · ${titulo.uppercase()}"; textSize = 14f; letterSpacing = 0.15f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topo.addView(pequeno("Sair do caixa") { sair() })
        raiz.addView(topo)

        val total = if (variasLojas) 4 else 3
        val marcador = LinearLayout(this).apply { setPadding(dp(14), dp(2), dp(14), dp(6)) }
        for (i in 1..total) {
            marcador.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(if (i <= passoAtual()) destaque else linhaCor) }
            }, LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (i > 1) marginStart = dp(4) })
        }
        raiz.addView(marcador)
        raiz.addView(ScrollView(this).apply { isFillViewport = true; addView(corpo) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (rodape != null) {
            rodape.background = fundo(Color.parseColor("#120E22"), 0)
            raiz.addView(rodape)
        }
        setContentView(raiz)
    }

    private fun desenhar() {
        when (etapa) {
            Etapa.LOJA -> telaLoja()
            Etapa.PRODUTOS -> telaProdutos()
            Etapa.PAGAMENTO -> telaPagamento()
            Etapa.RECIBO -> telaRecibo()
        }
    }

    // ------------------------------------------------------------------ etapa 1: escolher a loja
    private fun telaLoja() {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        corpo.addView(TextView(this).apply { text = "Onde você está vendendo agora?"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(4), dp(8), 0, dp(4)) })
        corpo.addView(TextView(this).apply { text = "Cada loja tem o seu catálogo, os seus preços e o seu estoque."; textSize = 13f; setTextColor(apagado); setPadding(dp(4), 0, 0, dp(12)) })
        val aberta = Lojas.aberta(this)
        for (l in lojas) {
            val idLoja = if (l.principal) "" else l.id
            val atual = idLoja == aberta
            corpo.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
                background = fundo(if (atual) Color.argb(60, Color.red(destaque), Color.green(destaque), Color.blue(destaque)) else Color.argb(150, 24, 19, 44), 18, if (atual) destaque else linhaCor)
                addView(TextView(this@CaixaActivity).apply { text = l.nome.ifBlank { "Minha loja" }; textSize = 17f; setTextColor(Color.WHITE) })
                addView(TextView(this@CaixaActivity).apply {
                    text = (if (l.principal) "Loja principal" else "Loja extra") + if (atual) " · ✓ Aberta agora" else ""; textSize = 12.5f; setTextColor(if (atual) verde else apagado)
                })
                setOnClickListener { escolherLoja(idLoja) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        moldura("Escolha a loja", corpo, null, null)
    }

    private fun escolherLoja(id: String) {
        carregando("Abrindo a loja…")
        Thread {
            if (id != Lojas.aberta(this)) try { Lojas.abrir(this, id) } catch (e: Exception) { }
            runOnUiThread { Perfil.carregar(this); carregarEIr() }
        }.start()
    }

    // ------------------------------------------------------------------ etapa 2: produtos e carrinho
    private lateinit var lista: LinearLayout
    private lateinit var resumoCarrinho: TextView
    private lateinit var totalCarrinho: TextView
    private lateinit var avisoEstoque: TextView

    private fun telaProdutos() {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(16)) }
        corpo.addView(TextView(this).apply {
            text = Perfil.loja.ifBlank { "Minha loja" }; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(2), dp(2), 0, dp(6))
        })
        val busca = EditText(this).apply {
            hint = "Buscar produto"; setHintTextColor(apagado); setText(filtro); setSingleLine(); textSize = 15f; setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(14), dp(11), dp(14), dp(11)); background = fundo(Color.parseColor("#120E22"), 14)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filtro = s?.toString().orEmpty(); desenharLista() }
            })
        }
        corpo.addView(busca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(LinearLayout(this).apply {
            addView(botao("Ler etiqueta (QR)", true) { lerEtiqueta() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(botao("Etiquetas", false) { gerarEtiquetas() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(cabecalhoDeSecao("Produtos · toque para pôr no carrinho"))
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        corpo.addView(lista)

        // rodapé: o carrinho (quantos itens e o total) e o botão de finalizar
        resumoCarrinho = TextView(this).apply { textSize = 13f; setTextColor(apagado) }
        totalCarrinho = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE) }
        avisoEstoque = TextView(this).apply { textSize = 12.5f; setTextColor(alerta); visibility = View.GONE }
        val rodape = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(LinearLayout(this@CaixaActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(vertical(TextView(this@CaixaActivity).apply { text = "CARRINHO"; textSize = 10.5f; letterSpacing = 0.25f; setTextColor(destaque) }, resumoCarrinho, totalCarrinho),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pequeno("Limpar") { carrinho = Pdv.Carrinho(); atualizarCarrinho(); desenharLista() })
            })
            addView(avisoEstoque)
            addView(botao("Finalizar venda", true) {
                if (carrinho.vazio()) aviso("Escolha um produto primeiro.") else { etapa = Etapa.PAGAMENTO; desenhar() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        moldura("Produtos", corpo, rodape, if (variasLojas) ({ etapa = Etapa.LOJA; desenhar() }) else null)
        desenharLista()
        atualizarCarrinho()
    }

    private fun desenharLista() {
        lista.removeAllViews()
        val f = filtro.trim().lowercase()
        val mostrados = produtos.filter { f.isEmpty() || it.nome.lowercase().contains(f) }
        if (mostrados.isEmpty()) {
            lista.addView(TextView(this).apply { text = "Nenhum produto com esse nome."; textSize = 14f; setTextColor(apagado); setPadding(dp(4), dp(8), 0, 0) })
            return
        }
        for (p in mostrados.take(200)) {
            val q = carrinho.linhas[p.id] ?: 0
            val sem = p.estoque != null && p.estoque <= 0
            val linha = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(10), dp(12))
                background = fundo(if (q > 0) Color.argb(60, Color.red(destaque), Color.green(destaque), Color.blue(destaque)) else Color.argb(150, 24, 19, 44), 16, if (q > 0) destaque else linhaCor)
                setOnClickListener { carrinho = carrinho.somar(p.id); atualizarCarrinho(); desenharLista() }
            }
            linha.addView(vertical(
                TextView(this).apply { text = p.nome; textSize = 15f; setTextColor(Color.WHITE); maxLines = 2 },
                TextView(this).apply {
                    text = (p.precoCentavos ?: 0).brl() + (p.estoque?.let { if (sem) " · sem estoque" else " · $it un." } ?: ""); textSize = 12.5f
                    setTextColor(if (sem) alerta else apagado)
                },
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (q > 0) {
                linha.addView(pequeno("−") { carrinho = carrinho.somar(p.id, -1); atualizarCarrinho(); desenharLista() })
                linha.addView(TextView(this).apply { text = q.toString(); textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = dp(34) })
                linha.addView(pequeno("+") { carrinho = carrinho.somar(p.id); atualizarCarrinho(); desenharLista() })
            } else {
                linha.addView(pequeno("Adicionar") { carrinho = carrinho.somar(p.id); atualizarCarrinho(); desenharLista() })
            }
            lista.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        if (mostrados.size > 200) lista.addView(TextView(this).apply { text = "Mostrando 200 de ${mostrados.size}. Use a busca para achar o resto."; textSize = 12.5f; setTextColor(apagado); setPadding(dp(4), dp(4), 0, 0) })
    }

    private fun atualizarCarrinho() {
        val total = Pdv.total(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        resumoCarrinho.text = if (carrinho.vazio()) "Nenhum produto ainda" else "${carrinho.unidades()} item(ns)"
        totalCarrinho.text = total.brl()
        avisoEstoque.visibility = if (sem.isEmpty()) View.GONE else View.VISIBLE
        avisoEstoque.text = if (sem.isEmpty()) "" else "Sem estoque suficiente: ${sem.joinToString(", ")}"
    }

    // ------------------------------------------------------------------ etapa 3: resumo para conferir e forma de pagamento
    private fun telaPagamento() {
        val itens = Pdv.itens(carrinho, produtos)
        if (itens.isEmpty()) { etapa = Etapa.PRODUTOS; return desenhar() }
        val total = Pdv.total(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        corpo.addView(cabecalhoDeSecao("Resumo da compra · confira"))
        val cartaoResumo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)); background = fundo(Color.argb(150, 24, 19, 44), 20) }
        for ((p, q, _) in itens) {
            val unit = p.precoCentavos ?: 0
            cartaoResumo.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                addView(vertical(
                    TextView(this@CaixaActivity).apply { text = p.nome; textSize = 15f; setTextColor(Color.WHITE); maxLines = 2 },
                    TextView(this@CaixaActivity).apply { text = "$q × ${unit.brl()}"; textSize = 12.5f; setTextColor(apagado) },
                ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pequeno("−") { carrinho = carrinho.somar(p.id, -1); desenhar() })
                addView(TextView(this@CaixaActivity).apply { text = q.toString(); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = dp(30) })
                addView(pequeno("+") { carrinho = carrinho.somar(p.id); desenhar() })
                addView(TextView(this@CaixaActivity).apply { text = (unit * q).brl(); textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.END; minWidth = dp(86) })
            })
            cartaoResumo.addView(View(this).apply { setBackgroundColor(linhaCor) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        cartaoResumo.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0)
            addView(TextView(this@CaixaActivity).apply { text = "TOTAL"; textSize = 13f; letterSpacing = 0.2f; setTextColor(destaque) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@CaixaActivity).apply { text = total.brl(); textSize = 26f; setTextColor(Color.WHITE) })
        })
        corpo.addView(cartaoResumo)
        if (sem.isNotEmpty()) corpo.addView(TextView(this).apply { text = "Atenção: sem estoque suficiente de ${sem.joinToString(", ")}. A venda vai deixar o estoque zerado."; textSize = 12.5f; setTextColor(alerta); setPadding(dp(4), dp(8), 0, 0) })

        corpo.addView(cabecalhoDeSecao("Forma de pagamento"))
        corpo.addView(botao("Pix (QR Code na hora)", true) { cobrarPix() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(LinearLayout(this).apply {
            addView(botao("Dinheiro", false) { cobrarDinheiro() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(botao("Cartão", false) { cobrarCartao() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(TextView(this).apply { text = "Só depois de escolher a forma e confirmar o recebimento a venda é registrada e o estoque baixa."; textSize = 12.5f; setTextColor(apagado); setPadding(dp(4), dp(12), 0, 0) })
        moldura("Pagamento", corpo, null) { etapa = Etapa.PRODUTOS; desenhar() }
    }

    private fun vazio(): Boolean {
        if (carrinho.vazio()) aviso("Escolha um produto primeiro.")
        return carrinho.vazio()
    }

    private fun pedirValor(titulo: String, dica: String, inicial: String, ok: String, aoOk: (String) -> Unit) {
        val campo = EditText(this).apply {
            hint = dica; setText(inicial); setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val caixa = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        CaixaBuilder(this).setTitle(titulo).setView(caixa)
            .setPositiveButton(ok) { _, _ -> aoOk(campo.text.toString().trim()) }.setNegativeButton("Cancelar", null).show()
    }

    private fun cobrarDinheiro() {
        if (vazio()) return
        val total = Pdv.total(carrinho, produtos)
        pedirValor("Dinheiro · total ${total.brl()}", "Quanto o cliente deu (R$) — vazio = valor exato", "", "Dar baixa") { v ->
            val recebido = if (v.isBlank()) total else ComandosLocais.centavos(v.replace(Regex("[^0-9,.]"), "")) ?: return@pedirValor aviso("Valor inválido.")
            val troco = Pdv.troco(total, recebido) ?: return@pedirValor aviso("O valor não cobre o total de ${total.brl()}.")
            fechar("Dinheiro", if (troco > 0) "Troco: ${troco.brl()}." else "Sem troco.")
        }
    }

    private fun cobrarCartao() {
        if (vazio()) return
        val total = Pdv.total(carrinho, produtos)
        pedirValor("Cartão · total ${total.brl()}", "Taxa da maquininha (%), opcional", prefs.getString("pdv_taxa_cartao", "").orEmpty(), "Já passei no cartão") { v ->
            val pct = if (v.isBlank()) 0.0 else Pdv.lerPct(v) ?: return@pedirValor aviso("Taxa inválida.")
            prefs.edit().putString("pdv_taxa_cartao", v).apply()
            val (taxa, liquido) = Pdv.liquido(total, pct)
            fechar("Cartão", if (taxa > 0) "Taxa ${taxa.brl()}, líquido ${liquido.brl()}." else "")
        }
    }

    private fun cobrarPix() {
        if (vazio()) return
        val total = Pdv.total(carrinho, produtos)
        val chave = DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_PIX)
        fun mostrar(k: String) {
            val codigo = try { QrLocal.pixCopiaECola(k, Perfil.loja, Perfil.cidade, total.toLong()) } catch (e: Exception) { return aviso(e.message ?: "Não consegui gerar o Pix.") }
            mostrarPix(total, codigo)
        }
        if (QrLocal.chavePix(chave) != null) return mostrar(chave)
        val campo = EditText(this).apply { hint = "CPF, CNPJ, celular, e-mail ou chave aleatória"; setText(chave); setSingleLine() }
        val caixa = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        CaixaBuilder(this).setTitle("Chave Pix da loja").setView(caixa).setPositiveButton("Usar") { _, _ ->
            val k = campo.text.toString().trim()
            if (QrLocal.chavePix(k) == null) return@setPositiveButton aviso("Chave Pix inválida.")
            DadosDaLoja.lembrar(prefs, DadosDaLoja.PREF_PIX, k)
            mostrar(k)
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun mostrarPix(total: Int, codigo: String) {
        val m = QrLocal.matriz(codigo)
        val px = 720
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val modulo = px / (m.tamanho + 8) // 4 módulos de silêncio de cada lado
        val x0 = (px - m.tamanho * modulo) / 2
        val tinta = Paint().apply { color = Color.BLACK }
        for (y in 0 until m.tamanho) for (x in 0 until m.tamanho) {
            if (m.modulos[y * m.tamanho + x] == '1') c.drawRect((x0 + x * modulo).toFloat(), (x0 + y * modulo).toFloat(), (x0 + (x + 1) * modulo).toFloat(), (x0 + (y + 1) * modulo).toFloat(), tinta)
        }
        val lado = dp(280)
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(ImageView(this@CaixaActivity).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(lado, lado))
            addView(TextView(this@CaixaActivity).apply {
                text = "Mostre ao cliente. Quando o dinheiro cair na sua conta, toque em “Pix recebido”."
                textSize = 13f; setTextColor(Color.parseColor("#D7DAEE")); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6))
            })
            addView(botao("Copiar código Pix", false) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pix", codigo))
                Toast.makeText(this@CaixaActivity, "Código copiado.", Toast.LENGTH_SHORT).show()
            })
        }
        CaixaBuilder(this).setTitle("Pix de ${total.brl()}").setView(ScrollView(this).apply { addView(corpo) })
            .setPositiveButton("Pix recebido") { _, _ -> fechar("Pix", "Estoque atualizado.") }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Registra a venda atual, zera o carrinho e vai para o recibo. Loja sem site: registro do catálogo. Loja com site: baixa o estoque no site e guarda a venda aqui. */
    private fun fechar(forma: String, nota: String) {
        if (gravando) return
        val total = Pdv.total(carrinho, produtos)
        val itens = Pdv.itens(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        if (itens.isEmpty()) return
        gravando = true
        val loja = Loja(this)
        Thread {
            var numero = ""
            var falha: String? = null
            var avisoExtra: String? = null
            try {
                if (loja.ehInterno) {
                    val pedido = CatalogoInterno(this).registrarVenda("Cliente do balcão", itens, forma, "Em mãos ou retirada")
                    numero = "B" + pedido.id
                } else {
                    // estoque lido na hora (outro aparelho ou o site podem ter vendido nesse meio tempo)
                    val frescos = loja.produtos()
                    val falhas = mutableListOf<String>()
                    for ((p, q, _) in itens) {
                        try { loja.baixarEstoque(frescos.firstOrNull { it.id == p.id } ?: p, q) } catch (e: Exception) { falhas += p.nome }
                    }
                    val venda = PdvVendas.registrar(this, itens.map { (p, q, _) -> PdvVendas.Linha(p.id, p.nome, q, p.precoCentavos ?: 0) }, forma)
                    numero = "B" + PdvVendas.numeroAmigavel(venda.id)
                    if (falhas.isNotEmpty()) avisoExtra = "Venda registrada, mas não consegui baixar o estoque de ${falhas.joinToString(", ")} no site. Ajuste lá."
                }
            } catch (e: Exception) {
                falha = "Não consegui registrar: ${e.message}"
            }
            val atualizados = try { carregarProdutos() } catch (e: Exception) { produtos }
            val quando = System.currentTimeMillis()
            val erroFinal = falha
            val extraFinal = avisoExtra
            runOnUiThread {
                gravando = false
                if (erroFinal != null) return@runOnUiThread aviso(erroFinal)
                Diario.anotar(this, "loja", "Caixa: ${itens.joinToString(", ") { "${it.second}x ${it.first.nome}" }} (${total.brl()}) no $forma", "tela")
                if (CaixaDoVyntem.ligado(this)) Thread { try { CaixaDoVyntem.conferir(applicationContext) } catch (e: Exception) { } }.start()
                val extra = (extraFinal?.let { " $it" } ?: "") + if (sem.isNotEmpty()) " Atenção: o estoque de ${sem.joinToString(", ")} ficou zerado." else ""
                concluida = Concluida(numero, itens.map { (p, q, _) -> Pdv.LinhaDoRecibo(p.nome, q, p.precoCentavos ?: 0) }, forma, nota + extra, quando)
                carrinho = Pdv.Carrinho()
                produtos = atualizados
                etapa = Etapa.RECIBO
                desenhar()
            }
        }.start()
    }

    // ------------------------------------------------------------------ etapa 4: recibo (CPF e WhatsApp, os dois opcionais)
    private var semCpf = false

    private fun telaRecibo() {
        val v = concluida ?: return novaVenda(perguntar = false)
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        corpo.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(18), dp(14), dp(18)); background = fundo(Color.argb(150, 24, 19, 44), 20, verde)
            addView(TextView(this@CaixaActivity).apply { text = "✓ Venda registrada"; textSize = 18f; setTextColor(verde) })
            addView(TextView(this@CaixaActivity).apply { text = v.total.brl(); textSize = 32f; setTextColor(Color.WHITE) })
            addView(TextView(this@CaixaActivity).apply { text = "Compra nº ${v.numero} · ${v.forma}"; textSize = 13f; setTextColor(apagado) })
            if (v.nota.isNotBlank()) addView(TextView(this@CaixaActivity).apply { text = v.nota; textSize = 13.5f; setTextColor(Color.parseColor("#D7DAEE")); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        })

        corpo.addView(cabecalhoDeSecao("Enviar o recibo ao cliente"))
        val zap = EditText(this).apply {
            hint = "WhatsApp do cliente, com DDD (opcional)"; setHintTextColor(apagado); setSingleLine(); textSize = 15f; setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE; setPadding(dp(14), dp(12), dp(14), dp(12)); background = fundo(Color.parseColor("#120E22"), 14)
        }
        corpo.addView(zap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val cpf = EditText(this).apply {
            hint = "CPF na nota (opcional)"; setHintTextColor(apagado); setSingleLine(); textSize = 15f; setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER; setPadding(dp(14), dp(12), dp(14), dp(12)); background = fundo(Color.parseColor("#120E22"), 14)
        }
        corpo.addView(cpf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val alternar = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        fun marcar() {
            alternar.text = if (semCpf) "✓ Cliente não quer CPF na nota" else "Cliente não quer CPF na nota"
            alternar.setTextColor(if (semCpf) Color.WHITE else destaque)
            alternar.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); if (semCpf) setColor(destaque) else setStroke(dp(1), destaque) }
            cpf.isEnabled = !semCpf; cpf.alpha = if (semCpf) 0.4f else 1f
        }
        alternar.setOnClickListener { semCpf = !semCpf; if (semCpf) cpf.setText(""); marcar() }
        marcar()
        corpo.addView(alternar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(TextView(this).apply {
            text = "O CPF só aparece no recibo deste cliente e não fica guardado na Vênus. Recibo sem valor fiscal."
            textSize = 12.5f; setTextColor(apagado); setPadding(dp(4), dp(8), 0, 0)
        })

        /** Lê e confere os dois campos; null = tem erro (já avisou). Vazio é permitido nos dois. */
        fun lerCampos(): Pair<String?, String?>? {
            val digitosCpf = if (semCpf || cpf.text.isNullOrBlank()) null else (Pdv.cpfValido(cpf.text.toString()) ?: run { aviso("O CPF não confere. Confira os números ou marque “Cliente não quer CPF na nota”."); return null })
            val tel = if (zap.text.isNullOrBlank()) null else (Pdv.telefoneParaWhatsApp(zap.text.toString()) ?: run { aviso("O WhatsApp não parece certo. Use DDD + número, ex.: (51) 99999-0000."); return null })
            return Pair(digitosCpf, tel)
        }

        corpo.addView(botao("Enviar recibo no WhatsApp", true) {
            val (digitosCpf, tel) = lerCampos() ?: return@botao
            val e = emissorDosDocumentos(prefs)
            val texto = Pdv.textoDoRecibo(Perfil.loja, v.numero, Documentos.dataBR(v.quando), v.linhas, v.forma, e.mensagem, digitosCpf)
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Pdv.linkWhatsApp(tel, texto)))) }
            catch (ex: ActivityNotFoundException) { aviso("Não achei o WhatsApp neste celular.") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        corpo.addView(botao("Compartilhar notinha em PDF", false) {
            val (digitosCpf, _) = lerCampos() ?: return@botao
            compartilharNotinha(v, digitosCpf)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val rodape = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(botao("Nova venda", true) { novaVenda(perguntar = true) })
        }
        moldura("Recibo", corpo, rodape, null)
    }

    private fun compartilharNotinha(v: Concluida, cpfDigitos: String?) {
        val livre = semLimiteDeDocumentos()
        val mes = mesAgora()
        val cota = cotaDeDocumentos()
        if (!Documentos.podeGerar(cota, mes, Documentos.Tipo.NOTINHA, v.numero, livre)) {
            return aviso("Você usou os ${Documentos.LIMITE_GRATIS} documentos grátis deste mês. O recibo no WhatsApp continua sem limite.")
        }
        aviso("Montando a notinha…")
        Thread {
            val dados = Documentos.Dados(
                loja = emissorDosDocumentos(prefs), numero = v.numero, data = Documentos.dataBR(v.quando),
                cliente = if (cpfDigitos != null) "CPF " + Pdv.cpfBonito(cpfDigitos) else "Cliente do balcão", telefone = "",
                enderecoLinhas = emptyList(), itens = v.linhas.map { Documentos.Linha(it.nome, it.quantidade, it.precoCentavos) },
                freteCentavos = 0, descontoCentavos = 0, totalCentavos = v.total, retirada = true, rastreio = null,
            )
            val arquivo = try { GeradorDeDocumentos.gerarPdf(this, Documentos.Tipo.NOTINHA, dados, Documentos.comMarca(livre)) } catch (e: Exception) { null }
            runOnUiThread {
                if (arquivo == null) return@runOnUiThread aviso("Não consegui montar a notinha. Tente de novo.")
                if (!livre) guardarCota(Documentos.registrar(cota, mes, Documentos.Tipo.NOTINHA, v.numero))
                val uri = Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri("notinha", uri)
                startActivity(Intent.createChooser(i, "Enviar a notinha"))
            }
        }.start()
    }

    private fun novaVenda(perguntar: Boolean) {
        fun ir() { concluida = null; semCpf = false; carrinho = Pdv.Carrinho(); filtro = ""; etapa = Etapa.PRODUTOS; desenhar() }
        if (!perguntar) return ir()
        CaixaBuilder(this).setTitle("Começar outra venda?").setMessage("O recibo desta venda some da tela. Se o cliente ainda quer o recibo, envie antes.")
            .setPositiveButton("Nova venda") { _, _ -> ir() }.setNegativeButton("Voltar ao recibo", null).show()
    }

    // ------------------------------------------------------------------ etiquetas QR
    private fun lerEtiqueta() {
        val opcoes = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opcoes).startScan()
            .addOnSuccessListener { lido ->
                val p = produtos.firstOrNull { it.id == Pdv.lerCodigo(lido.rawValue) }
                if (p == null) { aviso("Esta etiqueta não é de um produto do seu catálogo."); return@addOnSuccessListener }
                carrinho = carrinho.somar(p.id)
                if (etapa == Etapa.PRODUTOS) { atualizarCarrinho(); desenharLista() }
                Toast.makeText(this, "+1 ${p.nome}", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { aviso("Não consegui abrir o leitor de QR Code. Confira se o Google Play Services está atualizado.") }
    }

    private fun gerarEtiquetas() {
        val todos = produtos
        if (todos.isEmpty()) return aviso("Cadastre produtos primeiro.")
        aviso("Montando as etiquetas…")
        Thread {
            val r = try { Result.success(GeradorDeEtiquetasQr.gerar(this, todos)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onFailure { aviso("Não consegui montar as etiquetas: ${it.message}") }.onSuccess { arquivo ->
                    val uri = Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")
                    CaixaBuilder(this).setTitle("Etiquetas prontas")
                        .setMessage("${todos.size} etiqueta(s), 15 por página. Imprima e cole em cada produto.")
                        .setPositiveButton("Compartilhar") { _, _ ->
                            val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            i.clipData = ClipData.newRawUri("etiquetas", uri)
                            startActivity(Intent.createChooser(i, "Enviar etiquetas"))
                        }
                        .setNeutralButton("Abrir") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (e: ActivityNotFoundException) { aviso("Nenhum app de PDF encontrado. Use Compartilhar.") }
                        }
                        .setNegativeButton("Fechar", null).show()
                }
            }
        }.start()
    }
}
