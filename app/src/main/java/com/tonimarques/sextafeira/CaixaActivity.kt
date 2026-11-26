package com.tonimarques.sextafeira

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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
 * O Caixa: a Vênus vira um ponto de venda portátil. Tela cheia, tela sempre ligada, botões grandes para somar produtos,
 * "Ler etiqueta" (QR Code colado no produto) e Pix, dinheiro ou cartão. Cada venda usa o mesmo registro de venda da loja
 * Funciona em qualquer loja: usa o catálogo e os preços que ela já tem, baixa o estoque (no site, se a loja tem site) e, com o Vyntem ligado, a venda vai para o caixa dele.
 * "Sair do caixa" (ou voltar) devolve para a Vênus.
 */
class CaixaActivity : TelaBase() {
    private var produtos: List<Loja.Produto> = emptyList()
    private var carrinho = Pdv.Carrinho()
    private val botoes = mutableMapOf<Int, TextView>()
    private lateinit var resumo: TextView
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // não apaga a tela enquanto o caixa está aberto
        Perfil.carregar(this)
        setContentView(TextView(this).apply {
            text = "Abrindo o caixa…"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#03040A"))
        })
        Thread {
            val lido = try { Result.success(carregarProdutos()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                lido.onFailure {
                    Toast.makeText(this, "Não consegui ler o catálogo da loja agora. Confira a internet e a conexão da loja.", Toast.LENGTH_LONG).show(); finish()
                }.onSuccess { lista ->
                    produtos = lista
                    if (produtos.isEmpty()) { Toast.makeText(this, "Cadastre produtos com preço primeiro.", Toast.LENGTH_LONG).show(); finish() } else montar()
                }
            }
        }.start()
    }

    /** O catálogo da loja conectada (com ou sem site) só com o que dá para vender: ativo e com preço. */
    private fun carregarProdutos(): List<Loja.Produto> =
        Loja(this).produtos().filter { it.ativo && (it.precoCentavos ?: 0) > 0 }.sortedBy { it.nome.lowercase() }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = sair()

    private fun sair() {
        if (carrinho.vazio()) return finish()
        CaixaBuilder(this).setTitle("Sair do caixa?").setMessage("Tem uma venda em andamento. Se sair, ela é descartada.")
            .setPositiveButton("Sair") { _, _ -> finish() }.setNegativeButton("Continuar vendendo", null).show()
    }

    private fun linha(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x ->
            addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun montar() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0D0A1F"), Color.parseColor("#03040A")))
        }
        // barra de cima: nome do módulo, sair e ler etiqueta
        val topo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(6)) }
        topo.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CaixaActivity).apply {
                text = "CAIXA"; textSize = 18f; letterSpacing = 0.3f; setTextColor(destaque)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(botao("Sair do caixa", false) { sair() })
        })
        topo.addView(botao("Ler etiqueta (QR Code)", true) { lerEtiqueta() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        raiz.addView(topo)

        // produtos (2 por linha) e etiquetas
        coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(0), dp(14), dp(16)) }
        cartao("Produtos · toque para somar")
        produtos.chunked(2).forEach { par ->
            val bs = par.map { p -> botao("", false) { carrinho = carrinho.somar(p.id); atualizar() }.also { b -> b.textSize = 14f; b.maxLines = 3; botoes[p.id] = b } }
            val vazio = if (bs.size == 1) listOf(View(this)) else emptyList()
            adicionar(linha(*(bs + vazio).toTypedArray()))
        }
        cartao("Etiquetas")
        adicionar(texto("Gere um PDF com um QR Code e o nome de cada produto para colar neles. Depois é só ler a etiqueta aqui no caixa.", 13f, apagado))
        adicionar(botao("Gerar etiquetas com QR Code", false) { gerarEtiquetas() })
        raiz.addView(ScrollView(this).apply { addView(coluna) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // barra de baixo: total e formas de receber
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#120E22")); setStroke(dp(1), Color.parseColor("#2C2546")) }
        }
        resumo = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE) }
        base.addView(resumo)
        base.addView(linha(botao("Pix", true) { cobrarPix() }, botao("Dinheiro", false) { cobrarDinheiro() }, botao("Cartão", false) { cobrarCartao() }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        base.addView(linha(botao("Tirar o último", false) {
            carrinho.linhas.keys.lastOrNull()?.let { carrinho = carrinho.somar(it, -1) }; atualizar()
        }, botao("Limpar", false) { carrinho = Pdv.Carrinho(); atualizar() }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        raiz.addView(base)
        setContentView(raiz)
        atualizar()
    }

    private fun atualizar() {
        produtos.forEach { p ->
            val q = carrinho.linhas[p.id] ?: 0
            botoes[p.id]?.text = (if (q > 0) "$q× " else "") + p.nome + "\n" + (p.precoCentavos ?: 0).brl() + (p.estoque?.let { " · $it un." } ?: "")
        }
        val total = Pdv.total(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        resumo.text = if (carrinho.vazio()) "Nenhum produto ainda" else "${carrinho.unidades()} item(ns) · ${total.brl()}" +
            if (sem.isNotEmpty()) "\nSem estoque suficiente: ${sem.joinToString(", ")}" else ""
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private var gravando = false

    /** Registra a venda atual e zera o carrinho. Loja sem site: registro do catálogo. Loja com site: baixa o estoque no site e guarda a venda aqui. */
    private fun fechar(forma: String, nota: String) {
        if (gravando) return
        val total = Pdv.total(carrinho, produtos)
        val itens = Pdv.itens(carrinho, produtos)
        val sem = Pdv.faltando(carrinho, produtos)
        if (itens.isEmpty()) return
        gravando = true
        val loja = Loja(this)
        Thread {
            val erro: String? = try {
                if (loja.ehInterno) {
                    CatalogoInterno(this).registrarVenda("Cliente do balcão", itens, forma, "Em mãos ou retirada")
                    null
                } else {
                    // estoque lido na hora (outro aparelho ou o site podem ter vendido nesse meio tempo)
                    val frescos = loja.produtos()
                    val falhas = mutableListOf<String>()
                    for ((p, q, _) in itens) {
                        try { loja.baixarEstoque(frescos.firstOrNull { it.id == p.id } ?: p, q) } catch (e: Exception) { falhas += p.nome }
                    }
                    PdvVendas.registrar(this, itens.map { (p, q, _) -> PdvVendas.Linha(p.id, p.nome, q, p.precoCentavos ?: 0) }, forma)
                    if (falhas.isNotEmpty()) "Venda registrada, mas não consegui baixar o estoque de ${falhas.joinToString(", ")} no site. Ajuste lá." else null
                }
                null
            } catch (e: Exception) {
                "Não consegui registrar: ${e.message}"
            }
            val atualizados = try { carregarProdutos() } catch (e: Exception) { produtos }
            runOnUiThread {
                gravando = false
                if (erro != null && erro.startsWith("Não consegui registrar")) return@runOnUiThread aviso(erro)
                Diario.anotar(this, "loja", "Caixa: ${itens.joinToString(", ") { "${it.second}x ${it.first.nome}" }} (${total.brl()}) no $forma", "tela")
                if (CaixaDoVyntem.ligado(this)) Thread { try { CaixaDoVyntem.conferir(applicationContext) } catch (e: Exception) { } }.start()
                carrinho = Pdv.Carrinho()
                produtos = atualizados
                atualizar()
                aviso("Venda de ${total.brl()} registrada. $nota" + (erro?.let { " $it" } ?: "") +
                    if (sem.isNotEmpty()) " Atenção: o estoque de ${sem.joinToString(", ")} ficou zerado." else "")
            }
        }.start()
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

    // ------------------------------------------------------------------ formas de receber
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

    // ------------------------------------------------------------------ etiquetas QR
    private fun lerEtiqueta() {
        val opcoes = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opcoes).startScan()
            .addOnSuccessListener { lido ->
                val p = produtos.firstOrNull { it.id == Pdv.lerCodigo(lido.rawValue) }
                if (p == null) { aviso("Esta etiqueta não é de um produto do seu catálogo."); return@addOnSuccessListener }
                carrinho = carrinho.somar(p.id)
                atualizar()
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
                    val uri = android.net.Uri.parse("content://${FotoProvider.AUTORIDADE}/${arquivo.name}")
                    CaixaBuilder(this).setTitle("Etiquetas prontas")
                        .setMessage("${todos.size} etiqueta(s), 15 por página. Imprima e cole em cada produto.")
                        .setPositiveButton("Compartilhar") { _, _ ->
                            val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            i.clipData = ClipData.newRawUri("etiquetas", uri)
                            startActivity(Intent.createChooser(i, "Enviar etiquetas"))
                        }
                        .setNeutralButton("Abrir") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (e: android.content.ActivityNotFoundException) { aviso("Nenhum app de PDF encontrado. Use Compartilhar.") }
                        }
                        .setNegativeButton("Fechar", null).show()
                }
            }
        }.start()
    }
}
