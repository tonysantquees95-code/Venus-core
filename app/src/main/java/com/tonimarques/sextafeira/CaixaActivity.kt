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
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.os.Build
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
    private enum class Etapa { LOJA, PRODUTOS, PAGAMENTO, RECIBO, RECEBER }

    /** A venda que acabou de ser fechada (para o recibo). */
    private class Concluida(val numero: String, val linhas: List<Pdv.LinhaDoRecibo>, val forma: String, val nota: String, val quando: Long, val fiado: Caderninho.Fiado? = null) {
        val total: Int get() = linhas.sumOf { it.quantidade * it.precoCentavos }
    }

    private var etapa = Etapa.PRODUTOS
    private var lojas: List<Lojas.Item> = emptyList()
    private var produtos: List<Loja.Produto> = emptyList()
    private var carrinho = Pdv.Carrinho()
    private var filtro = ""
    private var concluida: Concluida? = null
    private var gravando = false
    private var abrirReceber = false
    private var baixando = false
    /** Dados que a pessoa digitou ao fechar no caderninho. */
    private class DadosDoFiado(val cliente: String, val telefone: String, val venceEm: Long)
    private var campoNome: EditText? = null
    private var campoTel: EditText? = null
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val verde = Color.parseColor("#35D07F")
    private val alerta = Color.parseColor("#FFB454")
    private val linhaCor = Color.parseColor("#2C2546")

    private val variasLojas get() = lojas.size > 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // não apaga a tela enquanto o caixa está aberto
        Perfil.carregar(this)
        abrirReceber = intent?.getBooleanExtra("receber", false) == true
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
            Etapa.RECIBO -> novaVenda(perguntar = concluida?.fiado == null)
            Etapa.RECEBER -> { etapa = if (concluida != null) Etapa.RECIBO else Etapa.PRODUTOS; desenhar() }
        }
    }

    private fun sair() {
        if (carrinho.vazio() || etapa == Etapa.RECIBO || etapa == Etapa.LOJA || etapa == Etapa.RECEBER) return finish()
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
                    } else { etapa = if (abrirReceber) Etapa.RECEBER else Etapa.PRODUTOS; abrirReceber = false; desenhar() }
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
        Etapa.RECEBER -> if (variasLojas) 2 else 1
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
            Etapa.RECEBER -> telaReceber()
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
        val abertos = Caderninho.aReceber(CaderninhoNoCelular.ler(this))
        if (abertos.isNotEmpty()) {
            val atras = Caderninho.atrasados(CaderninhoNoCelular.ler(this), System.currentTimeMillis()).size
            corpo.addView(botao("Caderninho · falta receber ${Caderninho.totalAReceber(CaderninhoNoCelular.ler(this)).brl()}" + if (atras > 0) " · $atras atrasado" + (if (atras > 1) "s" else "") else "", false) {
                etapa = Etapa.RECEBER; desenhar()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
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
        val regras = Perfil.regras
        val totalPix = Pdv.totalNoPix(carrinho, produtos, regras)
        val semDescontoPix = Pdv.semDescontoNoPix(carrinho, produtos, regras)
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
                    TextView(this@CaixaActivity).apply {
                        val semPix = p.nome in semDescontoPix
                        text = if (semPix) "em promoção · sem desconto no Pix" else ""
                        visibility = if (semPix) View.VISIBLE else View.GONE
                        textSize = 11.5f; setTextColor(destaque)
                    },
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
        if (totalPix < total) {
            cartaoResumo.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0)
                addView(TextView(this@CaixaActivity).apply { text = "NO PIX (${regras.pixDescontoPct}% DE DESCONTO)"; textSize = 12f; letterSpacing = 0.12f; setTextColor(destaque) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@CaixaActivity).apply { text = totalPix.brl(); textSize = 20f; setTextColor(Color.WHITE) })
            })
            cartaoResumo.addView(TextView(this).apply { text = "Você economiza ${(total - totalPix).brl()} pagando no Pix"; textSize = 12f; setTextColor(apagado) })
        }
        corpo.addView(cartaoResumo)
        if (sem.isNotEmpty()) corpo.addView(TextView(this).apply { text = "Atenção: sem estoque suficiente de ${sem.joinToString(", ")}. A venda vai deixar o estoque zerado."; textSize = 12.5f; setTextColor(alerta); setPadding(dp(4), dp(8), 0, 0) })

        corpo.addView(cabecalhoDeSecao("Forma de pagamento"))
        corpo.addView(botao(if (totalPix < total) "Pix · ${totalPix.brl()} (${regras.pixDescontoPct}% de desconto)" else "Pix (QR Code na hora)", true) { cobrarPix() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(LinearLayout(this).apply {
            addView(botao("Dinheiro", false) { cobrarDinheiro() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(botao("Cartão", false) { cobrarCartao() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (Cobranca.provedor(this) == "infinitepay" && Cobranca.configurada(this)) {
            corpo.addView(botao("Cartão por aproximação (celular)", false) { cobrarAproximacao() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        corpo.addView(cabecalhoDeSecao("Caderninho · o cliente paga depois"))
        corpo.addView(LinearLayout(this).apply {
            addView(botao("Caderninho Pix", false) { pedirCaderninho(Caderninho.FORMA_PIX) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(botao("Caderninho dinheiro", false) { pedirCaderninho(Caderninho.FORMA_DINHEIRO) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(TextView(this).apply { text = "No caderninho o estoque baixa na hora, mas a venda só entra no seu caixa (e no Vyntem) quando você marcar “Pago”. Preço cheio, sem o desconto do Pix."; textSize = 12.5f; setTextColor(apagado); setPadding(dp(4), dp(8), 0, 0) })
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

    // ------------------------------------------------------------------ dinheiro: calculadora de troco
    private fun cobrarDinheiro() {
        if (vazio()) return
        val total = Pdv.total(carrinho, produtos)
        var recebido = 0L
        val visor = TextView(this).apply { textSize = 38f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(2)) }
        val situacao = TextView(this).apply { textSize = 17f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        val dar = botao("Dar baixa", true) { }
        val dialogo = CaixaBuilder(this).setNegativeButton("Cancelar", null).create()
        fun atualizar() {
            visor.text = recebido.toInt().brl()
            val troco = Pdv.troco(total, recebido.toInt())
            when {
                recebido == 0L -> { situacao.text = "Digite o valor que o cliente deu"; situacao.setTextColor(apagado) }
                troco == null -> { situacao.text = "Faltam ${(total - recebido.toInt()).brl()}"; situacao.setTextColor(alerta) }
                troco == 0 -> { situacao.text = "Valor exato, sem troco"; situacao.setTextColor(Color.parseColor("#4ADE80")) }
                else -> { situacao.text = "TROCO  ${troco.brl()}"; situacao.setTextColor(Color.parseColor("#4ADE80")) }
            }
            situacao.textSize = if (troco != null && troco > 0) 24f else 17f
            dar.alpha = if (troco != null && recebido > 0) 1f else 0.4f
        }
        fun tecla(rotulo: String, codigo: String) = TextView(this).apply {
            text = rotulo; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = fundo(Color.parseColor("#1A1D33"), 14)
            setOnClickListener { recebido = Pdv.teclar(recebido, codigo); atualizar() }
            if (codigo == "apagar") setOnLongClickListener { recebido = 0L; atualizar(); true }
        }
        fun linha(vararg t: Pair<String, String>) = LinearLayout(this).apply {
            for ((r, c) in t) addView(tecla(r, c), LinearLayout.LayoutParams(0, dp(66), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        dar.setOnClickListener {
            val troco = Pdv.troco(total, recebido.toInt())
            if (recebido == 0L || troco == null) return@setOnClickListener aviso("O valor recebido não cobre o total de ${total.brl()}.")
            dialogo.dismiss()
            fechar("Dinheiro", if (troco > 0) "Recebido ${recebido.toInt().brl()}, troco ${troco.brl()}." else "Valor exato.")
        }
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), 0)
            addView(TextView(this@CaixaActivity).apply { text = "TOTAL DA VENDA  ${total.brl()}"; textSize = 13f; letterSpacing = 0.15f; setTextColor(destaque); gravity = Gravity.CENTER })
            addView(TextView(this@CaixaActivity).apply { text = "RECEBIDO"; textSize = 11f; letterSpacing = 0.25f; setTextColor(apagado); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
            addView(visor); addView(situacao)
            addView(linha("1" to "1", "2" to "2", "3" to "3"))
            addView(linha("4" to "4", "5" to "5", "6" to "6"))
            addView(linha("7" to "7", "8" to "8", "9" to "9"))
            addView(linha("00" to "00", "0" to "0", "⌫" to "apagar"))
            addView(LinearLayout(this@CaixaActivity).apply {
                addView(botao("Valor exato", false) {
                    dialogo.dismiss(); fechar("Dinheiro", "Valor exato.")
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(dar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        atualizar()
        dialogo.setTitle("Dinheiro")
        dialogo.setView(ScrollView(this).apply { addView(corpo) })
        dialogo.show()
    }

    // ------------------------------------------------------------------ cartão: página segura do provedor
    private fun cobrarCartao() {
        if (vazio()) return
        val total = Pdv.total(carrinho, produtos)
        if (!Cobranca.configurada(this)) return cartaoNaMaquininha(total)
        val itens = Pdv.itens(carrinho, produtos).map { (p, q, _) -> Loja.Item(p.nome, q, p.precoCentavos ?: 0, p.id) }
        Toast.makeText(this, "Preparando o pagamento seguro…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val link = Cobranca.criarLink(this, 0, itens, "Cliente do balcão")
                runOnUiThread { cartaoPorLink(total, link) }
            } catch (e: Exception) {
                runOnUiThread { aviso((e.message ?: "Não consegui criar o link.") + " Use a maquininha."); cartaoNaMaquininha(total) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ cartão por aproximação: o app da InfinitePay (InfiniteTap) cobra e devolve o resultado
    private var tapPendente: String? = null

    private fun cobrarAproximacao() {
        if (vazio()) return
        val total = Pdv.total(carrinho, produtos) // cartão: preço cheio, sem o desconto do Pix
        if (total < 100) return aviso("O cartão por aproximação cobra a partir de R$ 1,00.")
        val max = Perfil.regras.cartaoParcelasMax.coerceIn(1, 12)
        val nomes = mutableListOf("Débito")
        for (n in 1..max) nomes += if (n == 1) "Crédito à vista" else "Crédito em ${n}x"
        CaixaBuilder(this).setTitle("Cartão por aproximação · ${total.brl()}")
            .setItems(nomes.toTypedArray()) { _, i ->
                val debito = i == 0
                iniciarAproximacao(total, debito, if (debito) 1 else i)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun iniciarAproximacao(total: Int, debito: Boolean, parcelas: Int) {
        val ref = "balcao-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val url = try { Pdv.enderecoDoTap(total, debito, parcelas, ref, Cobranca.infiniteTag(this)) } catch (e: Exception) { return aviso(e.message ?: "Não consegui preparar a cobrança.") }
        tapPendente = ref
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            tapPendente = null
            aviso("Não achei o app da InfinitePay neste aparelho. Instale e entre na sua conta (e faça uma venda-teste de R$ 1,00 por lá antes).")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("receber", false) && produtos.isNotEmpty() && etapa != Etapa.PAGAMENTO && etapa != Etapa.LOJA) { etapa = Etapa.RECEBER; desenhar(); return }
        val u = intent.data ?: return
        if (u.scheme != "venus-tap") return
        when (val r = Pdv.resultadoDoTap({ u.getQueryParameter(it) }, tapPendente)) {
            is Pdv.ResultadoDoTap.Ignorar -> {}
            is Pdv.ResultadoDoTap.Falhou -> { tapPendente = null; aviso(r.motivo + " Escolha outra forma de pagamento ou tente de novo.") }
            is Pdv.ResultadoDoTap.Aprovado -> {
                tapPendente = null
                fechar("Cartão por aproximação", "InfinitePay · NSU ${r.nsu} · aut. ${r.autorizacao}" + if (r.bandeira.isNotBlank()) " · ${r.bandeira}" else "", comDescontoPix = false)
            }
        }
    }

    private fun abrirLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: ActivityNotFoundException) { aviso("Não achei um navegador. Copie o link e abra onde quiser.") }
    }

    private fun cartaoPorLink(total: Int, link: Cobranca.Link) {
        val nome = Cobranca.PROVEDORES.firstOrNull { it.id == link.provedor }?.nome ?: "provedor de pagamento"
        var vivo = true
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@CaixaActivity).apply {
                text = "A página segura do $nome abre para o cliente digitar os dados do cartão. Entregue o aparelho a ele (os dados não passam pela Vênus). A venda fecha sozinha quando o pagamento for aprovado."
                textSize = 13.5f; setTextColor(Color.parseColor("#D7DAEE")); setPadding(0, 0, 0, dp(10))
            })
            addView(botao("Abrir a página de pagamento", true) { abrirLink(link.url) })
            addView(botao("Compartilhar o link com o cliente", false) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Cobranca.mensagemParaCliente("", total, link.url)), "Enviar link de pagamento"))
            })
            addView(TextView(this@CaixaActivity).apply { text = "Aguardando o pagamento…"; textSize = 12.5f; setTextColor(apagado); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        }
        val d = CaixaBuilder(this).setTitle("Cartão · ${total.brl()}").setView(ScrollView(this).apply { addView(corpo) })
            .setPositiveButton("Confirmar à mão") { _, _ -> fechar("Cartão", "Confirmado à mão (link do $nome).") }
            .setNegativeButton("Cancelar", null).show()
        d.setOnDismissListener { vivo = false }
        abrirLink(link.url)
        Thread {
            var n = 0
            while (vivo && n < 300) {
                try {
                    val s = Cobranca.conferir(this, link.ref, link.provedor)
                    if (s.pago) {
                        runOnUiThread { if (vivo) { vivo = false; d.dismiss(); fechar(s.forma ?: "Cartão", "Aprovado pelo $nome.") } }
                        return@Thread
                    }
                } catch (e: Exception) { /* tenta de novo */ }
                n++; Thread.sleep(4000)
            }
        }.start()
    }

    /** Sem provedor de cobrança cadastrado: a pessoa passa o cartão na própria maquininha e só registra. */
    private fun cartaoNaMaquininha(total: Int) {
        pedirValor("Cartão · total ${total.brl()}", "Taxa da maquininha (%), opcional", prefs.getString("pdv_taxa_cartao", "").orEmpty(), "Já passei no cartão") { v ->
            val pct = if (v.isBlank()) 0.0 else Pdv.lerPct(v) ?: return@pedirValor aviso("Taxa inválida.")
            prefs.edit().putString("pdv_taxa_cartao", v).apply()
            val (taxa, liquido) = Pdv.liquido(total, pct)
            fechar("Cartão", if (taxa > 0) "Taxa ${taxa.brl()}, líquido ${liquido.brl()}." else "")
        }
    }

    // ------------------------------------------------------------------ Pix: automático (Mercado Pago) ou pela chave da loja
    private fun cobrarPix() {
        if (vazio()) return
        val total = Pdv.totalNoPix(carrinho, produtos, Perfil.regras)
        if (Cobranca.provedor(this) == "infinitepay" && Cobranca.configurada(this)) return pixPelaInfinitePay(total)
        if (!Cobranca.pixAutomaticoPronto(this)) return pixDaChave(total)
        Toast.makeText(this, "Gerando o Pix…", Toast.LENGTH_SHORT).show()
        val ref = "balcao-" + java.util.UUID.randomUUID().toString().replace("-", "")
        Thread {
            try {
                val px = Cobranca.criarPixMp(this, total, "Venda no balcão · ${Perfil.loja}", ref)
                runOnUiThread { mostrarPix(total, px.codigo, px.id) }
            } catch (e: Exception) {
                runOnUiThread { aviso(e.message ?: "Não consegui gerar o Pix automático."); pixDaChave(total) }
            }
        }.start()
    }

    /** InfinitePay: o QR é o do link de pagamento (a página dela mostra o Pix). Sem chave Pix para digitar; a venda fecha quando a InfinitePay confirma. */
    private fun pixPelaInfinitePay(total: Int) {
        val itens = Pdv.itensNoPix(carrinho, produtos, Perfil.regras).map { (p, q, preco) -> Loja.Item(p.nome, q, preco ?: p.precoCentavos ?: 0, p.id) }
        Toast.makeText(this, "Gerando o Pix…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val link = Cobranca.criarLink(this, 0, itens, "Cliente do balcão")
                runOnUiThread { mostrarPix(total, link.url, null, link) }
            } catch (e: Exception) {
                runOnUiThread { aviso((e.message ?: "Não consegui gerar o Pix pela InfinitePay.") + " Usando a chave Pix da loja."); pixDaChave(total) }
            }
        }.start()
    }

    private fun pixDaChave(total: Int) {
        val chave = DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_PIX)
        fun mostrar(k: String) {
            val codigo = try { QrLocal.pixCopiaECola(k, Perfil.loja, Perfil.cidade, total.toLong()) } catch (e: Exception) { return aviso(e.message ?: "Não consegui gerar o Pix.") }
            mostrarPix(total, codigo, null)
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

    /** [mpId] != null: Pix criado no Mercado Pago, conferido a cada 3 s; a venda fecha sozinha quando cai. */
    private fun mostrarPix(total: Int, codigo: String, mpId: Long?, link: Cobranca.Link? = null) {
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
        var vivo = true
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(ImageView(this@CaixaActivity).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(lado, lado))
            addView(TextView(this@CaixaActivity).apply {
                text = if (link != null) "O cliente aponta a câmera para o QR, abre a página da InfinitePay e escolhe Pix. Assim que a InfinitePay confirmar, a venda fecha sozinha."
                else if (mpId != null) "Mostre ao cliente. Assim que o Pix cair no Mercado Pago, a venda fecha sozinha."
                else "Mostre ao cliente. Quando o dinheiro cair na sua conta, toque em “Pix recebido”."
                textSize = 13f; setTextColor(Color.parseColor("#D7DAEE")); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6))
            })
            addView(botao(if (link != null) "Copiar o link de pagamento" else "Copiar código Pix", false) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pix", codigo))
                Toast.makeText(this@CaixaActivity, if (link != null) "Link copiado." else "Código copiado.", Toast.LENGTH_SHORT).show()
            })
            if (mpId != null || link != null) addView(TextView(this@CaixaActivity).apply { text = "Aguardando o pagamento…"; textSize = 12.5f; setTextColor(apagado); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        }
        val d = CaixaBuilder(this).setTitle("Pix de ${total.brl()}").setView(ScrollView(this).apply { addView(corpo) })
            .setPositiveButton(if (mpId != null || link != null) "Confirmar à mão" else "Pix recebido") { _, _ -> fechar("Pix", if (mpId != null || link != null) "Confirmado à mão." else "Estoque atualizado.") }
            .setNegativeButton("Cancelar", null).show()
        d.setOnDismissListener { vivo = false }
        if (link != null) Thread {
            var n = 0
            while (vivo && n < 300) {
                try {
                    val sit = Cobranca.conferir(this, link.ref, link.provedor)
                    if (sit.pago) {
                        runOnUiThread {
                            if (vivo) {
                                vivo = false; d.dismiss()
                                // o link do Pix também aceita cartão: se o cliente pagou no cartão, a venda sai como cartão (e avisa que o desconto do Pix foi dado)
                                val noCartao = sit.forma != null && sit.forma != "Pix"
                                fechar(if (noCartao) sit.forma!! else "Pix", "Aprovado pela InfinitePay." + if (noCartao) " Atenção: pagou no cartão no link do Pix (com o desconto do Pix)." else "", comDescontoPix = true)
                            }
                        }
                        return@Thread
                    }
                } catch (e: Exception) { /* tenta de novo */ }
                n++; Thread.sleep(3000)
            }
        }.start()
        if (mpId != null) Thread {
            var n = 0
            while (vivo && n < 200) {
                try {
                    if (Cobranca.statusDoPixMp(this, mpId) == "approved") {
                        runOnUiThread { if (vivo) { vivo = false; d.dismiss(); fechar("Pix", "Confirmado pelo Mercado Pago.") } }
                        return@Thread
                    }
                } catch (e: Exception) { /* tenta de novo */ }
                n++; Thread.sleep(3000)
            }
        }.start()
    }

    /** Registra a venda atual, zera o carrinho e vai para o recibo. Loja sem site: registro do catálogo. Loja com site: baixa o estoque no site e guarda a venda aqui. */
    private fun fechar(forma: String, nota: String, comDescontoPix: Boolean = forma == "Pix", fiado: DadosDoFiado? = null) {
        if (gravando) return
        // no Pix vale o preço com o desconto da loja (promoção fica de fora); nas outras formas, o preço cheio de hoje
        val itens = if (comDescontoPix) Pdv.itensNoPix(carrinho, produtos, Perfil.regras) else Pdv.itens(carrinho, produtos)
        val total = itens.sumOf { (p, q, preco) -> (preco ?: p.precoCentavos ?: 0) * q }
        val sem = Pdv.faltando(carrinho, produtos)
        if (itens.isEmpty()) return
        gravando = true
        val loja = Loja(this)
        Thread {
            var numero = ""
            var falha: String? = null
            var avisoExtra: String? = null
            var pedidoId = 0
            var interno = true
            try {
                if (loja.ehInterno) {
                    val pedido = CatalogoInterno(this).registrarVenda(fiado?.cliente ?: "Cliente do balcão", itens, forma, "Em mãos ou retirada", aguardandoPagamento = fiado != null)
                    numero = "B" + pedido.id
                    pedidoId = pedido.id
                } else {
                    // estoque lido na hora (outro aparelho ou o site podem ter vendido nesse meio tempo)
                    val frescos = loja.produtos()
                    val falhas = mutableListOf<String>()
                    for ((p, q, _) in itens) {
                        try { loja.baixarEstoque(frescos.firstOrNull { it.id == p.id } ?: p, q) } catch (e: Exception) { falhas += p.nome }
                    }
                    val venda = PdvVendas.registrar(this, itens.map { (p, q, preco) -> PdvVendas.Linha(p.id, p.nome, q, preco ?: p.precoCentavos ?: 0) }, forma, pendente = fiado != null)
                    numero = "B" + PdvVendas.numeroAmigavel(venda.id)
                    pedidoId = venda.id; interno = false
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
                val anotado = fiado?.let {
                    CaderninhoNoCelular.anotar(this, Caderninho.Fiado(
                        0, numero, it.cliente, it.telefone, itens.map { (p, q, preco) -> Caderninho.Linha(p.nome, q, preco ?: p.precoCentavos ?: 0, p.id) },
                        forma, quando, it.venceEm, 0L, pedidoId, interno,
                    ))
                }
                concluida = Concluida(numero, itens.map { (p, q, preco) -> Pdv.LinhaDoRecibo(p.nome, q, preco ?: p.precoCentavos ?: 0) }, forma, nota + extra, quando, anotado)
                carrinho = Pdv.Carrinho()
                produtos = atualizados
                etapa = Etapa.RECIBO
                desenhar()
                if (anotado != null) pedirPermissaoDeAviso()
            }
        }.start()
    }

    // ------------------------------------------------------------------ etapa 4: recibo (CPF e WhatsApp, os dois opcionais)
    private var semCpf = false
    private val PEDIDO_CONTATO = 4710

    private fun telaRecibo() {
        val v = concluida ?: return novaVenda(perguntar = false)
        v.fiado?.let { return telaReciboDoFiado(v, it) }
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

    // ------------------------------------------------------------------ caderninho (fiado)
    private fun primeiroPix(total: Int): String? {
        val chave = DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_PIX)
        if (QrLocal.chavePix(chave) == null) return null
        return try { QrLocal.pixCopiaECola(chave, Perfil.loja, Perfil.cidade, total.toLong()) } catch (e: Exception) { null }
    }

    /** Abre o WhatsApp Business se houver; senão o WhatsApp comum; senão o que o celular tiver para o link. Sem número, a pessoa escolhe o contato lá. */
    private fun enviarZap(telefone55: String?, texto: String) {
        val uri = Uri.parse(Pdv.linkWhatsApp(telefone55?.ifBlank { null }, texto))
        for (pacote in listOf("com.whatsapp.w4b", "com.whatsapp")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pacote)); return } catch (e: ActivityNotFoundException) { }
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: ActivityNotFoundException) { aviso("Não achei o WhatsApp neste celular.") }
    }

    private fun campoDeTexto(dica: String, tipo: Int) = EditText(this).apply {
        hint = dica; setHintTextColor(apagado); setSingleLine(); textSize = 15f; setTextColor(Color.WHITE); inputType = tipo
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = fundo(Color.parseColor("#120E22"), 14)
    }

    /** A janelinha do caderninho: quem é o cliente (contato ou número digitado) e até quando ele combinou de pagar. */
    private fun pedirCaderninho(forma: String) {
        if (vazio()) return
        val agora = System.currentTimeMillis()
        val total = Pdv.total(carrinho, produtos)
        var escolha = 7
        val nome = campoDeTexto("Nome do cliente", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val tel = campoDeTexto("WhatsApp com DDD (ou escolha dos contatos)", InputType.TYPE_CLASS_PHONE)
        val data = campoDeTexto("dd/mm/aaaa", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL)
        campoNome = nome; campoTel = tel
        val rotulos = linkedMapOf(7 to "7 dias", 15 to "15 dias", 30 to "30 dias", -1 to "Outra data")
        val chips = mutableMapOf<Int, TextView>()
        val vencimento = TextView(this).apply { textSize = 12.5f; setTextColor(apagado); setPadding(dp(4), dp(8), 0, 0) }
        fun marcar() {
            for ((valor, v) in chips) {
                val sel = valor == escolha
                v.text = (if (sel) "✓ " else "") + rotulos[valor]
                v.setTextColor(if (sel) Color.WHITE else destaque)
                v.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); if (sel) setColor(destaque) else setStroke(dp(1), destaque) }
            }
            data.visibility = if (escolha == -1) View.VISIBLE else View.GONE
            vencimento.text = if (escolha == -1) "Digite o dia combinado para o pagamento." else "Vence em ${Caderninho.dataBR(Caderninho.vencimentoEmDias(agora, escolha))}. A Vênus te avisa nesse dia."
        }
        fun chip(valor: Int) = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(10), dp(11), dp(10), dp(11)); setOnClickListener { escolha = valor; marcar() }
        }.also { chips[valor] = it }
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        corpo.addView(TextView(this).apply { text = "${total.brl()} · $forma"; textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(10)) })
        corpo.addView(nome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(tel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(pequeno("Escolher dos contatos") {
            try { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PEDIDO_CONTATO) }
            catch (e: ActivityNotFoundException) { aviso("Não achei a lista de contatos. Digite o número.") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(cabecalhoDeSecao("Até quando ele paga?"))
        corpo.addView(LinearLayout(this).apply {
            for ((i, valor) in listOf(7, 15, 30).withIndex()) addView(chip(valor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        })
        corpo.addView(chip(-1), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        corpo.addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        corpo.addView(vencimento)
        marcar()
        val d = CaixaBuilder(this).setTitle(forma).setView(ScrollView(this).apply { addView(corpo) })
            .setPositiveButton("Fechar venda", null).setNegativeButton("Cancelar", null).show()
        d.setOnDismissListener { campoNome = null; campoTel = null }
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = nome.text.toString().trim()
            if (n.isBlank()) return@setOnClickListener aviso("Escreva o nome do cliente.")
            val bruto = tel.text.toString().trim()
            val tel55 = if (bruto.isBlank()) "" else (Pdv.telefoneParaWhatsApp(bruto) ?: return@setOnClickListener aviso("O WhatsApp não parece certo. Use DDD + número, ex.: (51) 99999-0000."))
            val venceEm = if (escolha == -1) (Caderninho.dataDeTexto(data.text.toString(), agora) ?: return@setOnClickListener aviso("Essa data não vale. Use dd/mm/aaaa, de hoje em diante.")) else Caderninho.vencimentoEmDias(agora, escolha)
            d.dismiss()
            fechar(forma, "", false, DadosDoFiado(n, tel55, venceEm))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIDO_CONTATO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return
                campoTel?.setText(c.getString(0).orEmpty())
                if (campoNome?.text.isNullOrBlank()) campoNome?.setText(c.getString(1).orEmpty())
            }
        } catch (e: Exception) { aviso("Não consegui ler esse contato. Digite o número.") }
    }

    /** Uma vez só, explica por que precisa avisar e pede a permissão de notificação (Android 13+). */
    private fun pedirPermissaoDeAviso() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("caderninho_pediu_aviso", false)) return
        prefs.edit().putBoolean("caderninho_pediu_aviso", true).apply()
        CaixaBuilder(this).setTitle("Avisar no dia do pagamento?")
            .setMessage("Para te lembrar no dia em que o cliente combinou de pagar, a Vênus precisa de permissão para mostrar notificações. Ela só usa isso para o caderninho.")
            .setPositiveButton("Permitir") { _, _ -> requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711) }
            .setNegativeButton("Agora não", null).show()
    }

    private fun telaReciboDoFiado(v: Concluida, f: Caderninho.Fiado) {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        corpo.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(18), dp(14), dp(18)); background = fundo(Color.argb(150, 24, 19, 44), 20, verde)
            addView(TextView(this@CaixaActivity).apply { text = "✓ Anotado no caderninho"; textSize = 18f; setTextColor(verde) })
            addView(TextView(this@CaixaActivity).apply { text = v.total.brl(); textSize = 32f; setTextColor(Color.WHITE) })
            addView(TextView(this@CaixaActivity).apply { text = "Compra nº ${v.numero} · ${v.forma}"; textSize = 13f; setTextColor(apagado) })
            addView(TextView(this@CaixaActivity).apply { text = "${f.cliente} paga até ${Caderninho.dataBR(f.venceEm)}"; textSize = 14.5f; setTextColor(Color.parseColor("#D7DAEE")); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        })
        corpo.addView(cabecalhoDeSecao("Mandar a cobrança"))
        val pix = if (f.viaPix) primeiroPix(f.total) else null
        corpo.addView(botao("Enviar cobrança no WhatsApp", true) {
            enviarZap(f.telefone, Caderninho.textoDaCobranca(Perfil.loja, f, pix, false, System.currentTimeMillis()))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dica = buildString {
            append(if (f.telefone.isBlank()) "Sem número: o WhatsApp vai pedir para você escolher o contato. " else "Abre o WhatsApp Business (ou o comum) já com a mensagem pronta. ")
            if (f.viaPix && pix == null) append("Cadastre a chave Pix da loja para a cobrança levar o Pix copia e cola.")
        }
        corpo.addView(TextView(this).apply { text = dica; textSize = 12.5f; setTextColor(apagado); setPadding(dp(4), dp(8), 0, 0) })
        corpo.addView(botao("Ver o caderninho", false) { etapa = Etapa.RECEBER; desenhar() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val rodape = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(botao("Nova venda", true) { novaVenda(perguntar = false) })
        }
        moldura("Caderninho", corpo, rodape, null)
    }

    // ------------------------------------------------------------------ A receber: a lista do caderninho
    private fun telaReceber() {
        val agora = System.currentTimeMillis()
        val todos = CaderninhoNoCelular.ler(this)
        val abertos = Caderninho.aReceber(todos)
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        corpo.addView(TextView(this).apply { text = "Falta receber"; textSize = 13f; setTextColor(apagado); setPadding(dp(4), dp(4), 0, 0) })
        corpo.addView(TextView(this).apply { text = Caderninho.totalAReceber(todos).brl(); textSize = 32f; setTextColor(Color.WHITE); setPadding(dp(4), 0, 0, dp(4)) })
        corpo.addView(TextView(this).apply {
            text = if (abertos.isEmpty()) "Ninguém deve nada agora." else "${abertos.size} cliente" + (if (abertos.size > 1) "s" else "") + " no caderninho"
            textSize = 13f; setTextColor(apagado); setPadding(dp(4), 0, 0, dp(8))
        })
        for (f in abertos) {
            val sit = Caderninho.situacao(f, agora)
            val corPrazo = when (sit) { Caderninho.Situacao.ATRASADO -> Color.parseColor("#FF6B6B"); Caderninho.Situacao.VENCE_HOJE -> alerta; else -> apagado }
            val cartao = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = fundo(Color.argb(150, 24, 19, 44), 18, if (sit == Caderninho.Situacao.PAGO) linhaCor else corPrazo.takeIf { sit != Caderninho.Situacao.ABERTO } ?: linhaCor) }
            cartao.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@CaixaActivity).apply { text = f.cliente; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@CaixaActivity).apply { text = f.total.brl(); textSize = 18f; setTextColor(Color.WHITE) })
            })
            cartao.addView(TextView(this).apply { text = Caderninho.prazoEmTexto(f, agora) + " · " + (if (f.viaPix) "Pix" else "dinheiro"); textSize = 13f; setTextColor(corPrazo) })
            cartao.addView(TextView(this).apply { text = f.linhas.joinToString(", ") { "${it.quantidade}x ${it.nome}" }; textSize = 12.5f; setTextColor(apagado); maxLines = 2; setPadding(0, dp(2), 0, 0) })
            cartao.addView(LinearLayout(this).apply {
                setPadding(0, dp(10), 0, 0)
                addView(botao(if (f.cobrancas > 0) "Cobrar de novo" else "Cobrar", false) { cobrarDeNovo(f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(botao("Pago", true) { confirmarPago(f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            })
            cartao.addView(pequeno("Editar ou excluir") { editarFiado(f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); gravity = Gravity.END })
            corpo.addView(cartao, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        val voltar = { etapa = if (concluida != null) Etapa.RECIBO else Etapa.PRODUTOS; desenhar() }
        moldura("A receber", corpo, null, voltar)
    }

    private fun cobrarDeNovo(f: Caderninho.Fiado) {
        val agora = System.currentTimeMillis()
        enviarZap(f.telefone, Caderninho.textoDaCobranca(Perfil.loja, f, if (f.viaPix) primeiroPix(f.total) else null, true, agora))
        CaderninhoNoCelular.gravar(this, Caderninho.cobrou(CaderninhoNoCelular.ler(this), f.id))
    }

    private fun confirmarPago(f: Caderninho.Fiado) {
        CaixaBuilder(this).setTitle("Marcar como pago?")
            .setMessage("${f.cliente} pagou ${f.total.brl()}. A venda entra no seu caixa agora.")
            .setPositiveButton("Sim, recebi") { _, _ -> darBaixa(f) }.setNegativeButton("Ainda não", null).show()
    }

    /** O dinheiro entrou: a venda vira paga (e vai para o Vyntem uma vez só) e a Vênus oferece o "recebi, obrigado". */
    private fun darBaixa(f: Caderninho.Fiado) {
        if (baixando) return
        baixando = true
        Thread {
            var erro: String? = null
            var pago: Caderninho.Fiado? = null
            try {
                // primeiro a venda (se já estava paga, nada muda), depois o caderninho: se falhar no meio, dá para tentar de novo sem contar duas vezes
                if (f.interno) CatalogoInterno(this).marcarPaga(f.pedidoId, f.formaReal) else PdvVendas.marcarPaga(this, f.pedidoId)
                pago = CaderninhoNoCelular.darBaixa(this, f.id)
            } catch (e: Exception) { erro = "Não consegui dar baixa agora: ${e.message}" }
            if (pago != null && CaixaDoVyntem.ligado(this)) try { CaixaDoVyntem.conferir(applicationContext) } catch (e: Exception) { }
            runOnUiThread {
                baixando = false
                if (erro != null) return@runOnUiThread aviso(erro)
                Diario.anotar(this, "loja", "Caderninho: ${f.cliente} pagou ${f.total.brl()}", "tela")
                desenhar()
                val quem = pago ?: return@runOnUiThread
                CaixaBuilder(this).setTitle("Pago ✓")
                    .setMessage("${quem.total.brl()} de ${quem.cliente} entrou no caixa. Quer mandar um “recebi, obrigado”?")
                    .setPositiveButton("Mandar agradecimento") { _, _ -> enviarZap(quem.telefone, Caderninho.textoDeAgradecimento(Perfil.loja, quem)) }
                    .setNegativeButton("Agora não", null).show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ editar ou excluir um fiado
    /** Corrige o que foi anotado errado: nome, telefone, prazo e itens (tirar, trocar quantidade, acrescentar). Ou exclui. */
    private fun editarFiado(f: Caderninho.Fiado) {
        val agora = System.currentTimeMillis()
        // fiados antigos não guardavam o produto: acha pelo nome no catálogo
        val linhas = f.linhas.map { l -> if (l.produtoId > 0) l else l.copy(produtoId = produtos.firstOrNull { it.nome == l.nome }?.id ?: 0) }.toMutableList()
        val nome = campoDeTexto("Nome do cliente", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply { setText(f.cliente) }
        val tel = campoDeTexto("WhatsApp com DDD", InputType.TYPE_CLASS_PHONE).apply { setText(f.telefone.removePrefix("55")) }
        val data = campoDeTexto("dd/mm/aaaa", InputType.TYPE_CLASS_NUMBER).apply { setText(Caderninho.dataBR(f.venceEm)) }
        campoNome = nome; campoTel = tel
        val itens = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val totalTxt = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(4), dp(8), 0, 0) }
        fun desenharItens() {
            itens.removeAllViews()
            for (l in linhas.toList()) {
                itens.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
                    addView(vertical(
                        TextView(this@CaixaActivity).apply { text = l.nome; textSize = 14.5f; setTextColor(Color.WHITE); maxLines = 2 },
                        TextView(this@CaixaActivity).apply { text = l.precoCentavos.brl() + " cada"; textSize = 12f; setTextColor(apagado) },
                    ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(pequeno("−") {
                        val i = linhas.indexOf(l); if (i >= 0) { if (l.quantidade > 1) linhas[i] = l.copy(quantidade = l.quantidade - 1) else linhas.removeAt(i); desenharItens() }
                    })
                    addView(TextView(this@CaixaActivity).apply { text = l.quantidade.toString(); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = dp(30) })
                    addView(pequeno("+") { val i = linhas.indexOf(l); if (i >= 0) { linhas[i] = l.copy(quantidade = l.quantidade + 1); desenharItens() } })
                    addView(pequeno("Tirar") { linhas.remove(l); desenharItens() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                })
            }
            totalTxt.text = "Total: " + linhas.sumOf { it.quantidade * it.precoCentavos }.brl()
        }
        fun adicionarProduto() {
            if (produtos.isEmpty()) return aviso("Não há produtos com preço no catálogo.")
            CaixaBuilder(this).setTitle("Acrescentar produto")
                .setItems(produtos.map { it.nome + " · " + (it.precoCentavos ?: 0).brl() }.toTypedArray()) { _, i ->
                    val p = produtos[i]
                    val j = linhas.indexOfFirst { it.produtoId == p.id }
                    if (j >= 0) linhas[j] = linhas[j].copy(quantidade = linhas[j].quantidade + 1)
                    else linhas += Caderninho.Linha(p.nome, 1, p.precoCentavos ?: 0, p.id)
                    desenharItens()
                }.setNegativeButton("Cancelar", null).show()
        }
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        corpo.addView(nome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(tel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(pequeno("Escolher dos contatos") {
            try { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PEDIDO_CONTATO) }
            catch (e: ActivityNotFoundException) { aviso("Não achei a lista de contatos. Digite o número.") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        corpo.addView(cabecalhoDeSecao("Paga até"))
        corpo.addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(LinearLayout(this).apply {
            for ((i, dias) in listOf(7, 15, 30).withIndex()) addView(pequeno("+$dias dias") { data.setText(Caderninho.dataBR(Caderninho.vencimentoEmDias(agora, dias))) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        corpo.addView(cabecalhoDeSecao("Itens"))
        corpo.addView(itens)
        corpo.addView(pequeno("+ Acrescentar produto") { adicionarProduto() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        corpo.addView(totalTxt)
        desenharItens()
        val d = CaixaBuilder(this).setTitle("Editar · ${f.forma}").setView(ScrollView(this).apply { addView(corpo) })
            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).setNeutralButton("Excluir", null).show()
        d.setOnDismissListener { campoNome = null; campoTel = null }
        d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { confirmarExclusao(f, d) }
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = nome.text.toString().trim()
            if (n.isBlank()) return@setOnClickListener aviso("Escreva o nome do cliente.")
            if (linhas.isEmpty()) return@setOnClickListener aviso("Sem itens não dá para manter a venda. Acrescente um produto ou use Excluir.")
            val bruto = tel.text.toString().trim()
            val tel55 = if (bruto.isBlank()) "" else (Pdv.telefoneParaWhatsApp(bruto) ?: return@setOnClickListener aviso("O WhatsApp não parece certo. Use DDD + número."))
            val texto = data.text.toString()
            val venceEm = if (texto.filter { it.isDigit() } == Caderninho.dataBR(f.venceEm).filter { it.isDigit() }) f.venceEm
                else (Caderninho.dataDeTexto(texto, agora) ?: return@setOnClickListener aviso("Essa data não vale. Use dd/mm/aaaa, de hoje em diante."))
            d.dismiss()
            salvarEdicao(f, n, tel55, venceEm, linhas.toList())
        }
    }

    /** Grava a correção: primeiro a venda (itens, total e estoque pela diferença), depois o caderninho. */
    private fun salvarEdicao(f: Caderninho.Fiado, cliente: String, telefone: String, venceEm: Long, linhas: List<Caderninho.Linha>) {
        if (baixando) return
        baixando = true
        Thread {
            var erro: String? = null
            try {
                val vendaLinhas = linhas.map { PdvVendas.Linha(it.produtoId, it.nome, it.quantidade, it.precoCentavos) }
                if (f.interno) CatalogoInterno(this).editarVendaPendente(f.pedidoId, cliente, vendaLinhas)
                else {
                    if (!PdvVendas.atualizarPendente(this, f.pedidoId, vendaLinhas)) throw Loja.ErroDaLoja("Essa venda já foi paga.")
                    val loja = Loja(this)
                    val frescos = loja.produtos()
                    val antes = f.linhas.map { l -> if (l.produtoId > 0) l else l.copy(produtoId = frescos.firstOrNull { it.nome == l.nome }?.id ?: 0) }
                    for ((pid, delta) in Caderninho.diferencaDeEstoque(antes, linhas)) frescos.firstOrNull { it.id == pid }?.let { try { loja.baixarEstoque(it, delta) } catch (e: Exception) { } }
                }
                if (!CaderninhoNoCelular.atualizar(this, f.id, cliente, telefone, venceEm, linhas)) throw Loja.ErroDaLoja("Não consegui salvar a correção.")
            } catch (e: Exception) { erro = e.message ?: "Não consegui salvar agora." }
            val atualizados = try { carregarProdutos() } catch (e: Exception) { produtos }
            runOnUiThread {
                baixando = false
                produtos = atualizados
                if (erro != null) return@runOnUiThread aviso(erro)
                Diario.anotar(this, "loja", "Caderninho: corrigiu a venda de $cliente", "tela")
                aviso("Correção salva.")
                desenhar()
            }
        }.start()
    }

    private fun confirmarExclusao(f: Caderninho.Fiado, janela: android.app.AlertDialog) {
        CaixaBuilder(this).setTitle("Excluir do caderninho?")
            .setMessage("A venda de ${f.cliente} (${f.total.brl()}) sai do caderninho e deixa de contar como venda. Quer devolver os itens ao estoque?")
            .setPositiveButton("Excluir e devolver ao estoque") { _, _ -> janela.dismiss(); excluirFiado(f, true) }
            .setNeutralButton("Excluir sem mexer no estoque") { _, _ -> janela.dismiss(); excluirFiado(f, false) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun excluirFiado(f: Caderninho.Fiado, devolver: Boolean) {
        if (baixando) return
        baixando = true
        Thread {
            var erro: String? = null
            try {
                if (f.interno) CatalogoInterno(this).removerVendaPendente(f.pedidoId, devolver)
                else {
                    if (!PdvVendas.removerPendente(this, f.pedidoId)) throw Loja.ErroDaLoja("Essa venda já foi paga.")
                    if (devolver) {
                        val loja = Loja(this)
                        val frescos = loja.produtos()
                        // fiados antigos não guardavam o produto: acha pelo nome
                        for (l in f.linhas) {
                            val p = frescos.firstOrNull { it.id == l.produtoId } ?: frescos.firstOrNull { it.nome == l.nome } ?: continue
                            try { loja.baixarEstoque(p, -l.quantidade) } catch (e: Exception) { }
                        }
                    }
                }
                CaderninhoNoCelular.excluir(this, f.id)
            } catch (e: Exception) { erro = e.message ?: "Não consegui excluir agora." }
            val atualizados = try { carregarProdutos() } catch (e: Exception) { produtos }
            runOnUiThread {
                baixando = false
                produtos = atualizados
                if (erro != null) return@runOnUiThread aviso(erro)
                Diario.anotar(this, "loja", "Caderninho: excluiu a venda de ${f.cliente} (${f.total.brl()})", "tela")
                aviso("Excluído do caderninho.")
                desenhar()
            }
        }.start()
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
