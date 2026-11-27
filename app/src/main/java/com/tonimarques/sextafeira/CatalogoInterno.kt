package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Loja sem site: o catálogo fica dentro da Vênus, no celular (produtos, fotos, estoque, custo e as vendas
 * registradas na conversa). É mais uma "plataforma" para a Vênus: estoque, avisos, relatórios, margem,
 * robô do WhatsApp, stories e ideias de post funcionam igual. As fotos ficam em arquivos do app.
 *
 * Formato (catalogo.json): {"proximoId", "proximaVenda", "produtos":[{id, name, category, description, priceCents,
 * compareAtCents, costCents, stockQuantity, active, imageUrl, criado, atualizado}], "vendas":[{id, cliente, itens:[{produtoId,
 * nome, quantidade, precoCents}], totalCents, status, pagamento, entrega, criado, pago}]}.
 */
class CatalogoInterno(private val ctx: Context) : Plataformas.Adaptador {

    override val nome = "loja"

    override fun produtos(): List<Loja.Produto> = lerProdutos(ler(ctx))
    override fun pedidos(): List<Loja.Pedido> = lerVendas(ler(ctx))

    override fun definirEstoque(p: Loja.Produto, quantidade: Int) = mudar(p.id) { it.put("stockQuantity", quantidade.coerceAtLeast(0)) }

    override fun mudarProduto(p: Loja.Produto, tipo: String, valor: Int?) = mudar(p.id) { o ->
        when (tipo) {
            "preco" -> o.put("priceCents", valor).put("compareAtCents", JSONObject.NULL)
            "promocao" -> {
                val atual = o.optInt("priceCents")
                if (o.isNull("compareAtCents") && atual > 0) o.put("compareAtCents", atual)
                o.put("priceCents", valor)
            }
            "fim_promocao" -> if (!o.isNull("compareAtCents")) o.put("priceCents", o.optInt("compareAtCents")).put("compareAtCents", JSONObject.NULL)
            "ativar" -> o.put("active", true)
            "desativar" -> o.put("active", false)
            "custo" -> o.put("costCents", valor)
            else -> throw Loja.ErroDaLoja("Não sei fazer essa mudança no catálogo.")
        }
    }

    override fun mudarPedido(o: Loja.Pedido, status: String, rastreio: String?) = editar { raiz ->
        val vendas = raiz.optJSONArray("vendas") ?: JSONArray()
        for (i in 0 until vendas.length()) {
            val v = vendas.optJSONObject(i) ?: continue
            if (v.optInt("id") == o.id) {
                v.put("status", status)
                if (rastreio != null) v.put("rastreio", rastreio)
                // venda cancelada devolve as unidades ao estoque
                if (status == "canceled" && !v.optBoolean("devolvido")) {
                    devolverEstoque(raiz, v)
                    v.put("devolvido", true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ cadastro e edição
    fun adicionar(n: Loja.NovoProduto): Loja.Produto {
        var novo: JSONObject? = null
        editar { raiz ->
            val id = raiz.optInt("proximoId", 1)
            raiz.put("proximoId", id + 1)
            novo = JSONObject()
                .put("id", id).put("name", n.nome.trim()).put("category", n.categoria.trim())
                .put("description", n.descricao ?: JSONObject.NULL)
                .put("priceCents", n.precoCentavos ?: JSONObject.NULL).put("compareAtCents", JSONObject.NULL)
                .put("costCents", n.custoCentavos ?: JSONObject.NULL)
                .put("stockQuantity", n.estoque ?: JSONObject.NULL).put("active", n.ativo)
                .put("imageUrl", n.imagemUrl ?: JSONObject.NULL)
                .put("criado", System.currentTimeMillis()).put("atualizado", System.currentTimeMillis())
            (raiz.optJSONArray("produtos") ?: JSONArray().also { raiz.put("produtos", it) }).put(novo)
        }
        return produtoDe(novo!!)
    }

    /** Muda os campos de um produto (nulos ficam como estavam; use [limpar] para apagar a foto ou o custo). */
    fun salvar(
        id: Int, nome: String? = null, categoria: String? = null, descricao: String? = null, precoCentavos: Int? = null,
        custoCentavos: Int? = null, estoque: Int? = null, ativo: Boolean? = null, imagemUrl: String? = null,
        semEstoque: Boolean = false,
    ) = mudar(id) { o ->
        nome?.let { o.put("name", it.trim()) }
        categoria?.let { o.put("category", it.trim()) }
        descricao?.let { o.put("description", it.trim().ifBlank { null } ?: JSONObject.NULL) }
        precoCentavos?.let { o.put("priceCents", it) }
        custoCentavos?.let { o.put("costCents", it) }
        if (semEstoque) o.put("stockQuantity", JSONObject.NULL) else estoque?.let { o.put("stockQuantity", it.coerceAtLeast(0)) }
        ativo?.let { o.put("active", it) }
        imagemUrl?.let { novo ->
            apagarFoto(o.optString("imageUrl"))
            o.put("imageUrl", novo)
        }
    }

    fun apagar(id: Int) = editar { raiz ->
        val lista = raiz.optJSONArray("produtos") ?: return@editar
        val nova = JSONArray()
        for (i in 0 until lista.length()) {
            val o = lista.optJSONObject(i) ?: continue
            if (o.optInt("id") == id) apagarFoto(o.optString("imageUrl")) else nova.put(o)
        }
        raiz.put("produtos", nova)
    }

    /** Guarda a foto (JPEG) nos arquivos do app e devolve o endereço "file://…". */
    fun guardarFoto(jpeg: ByteArray): String {
        val f = File(pastaFotos(ctx), "${UUID.randomUUID()}.jpg")
        f.writeBytes(jpeg)
        return "file://" + f.absolutePath
    }

    private fun apagarFoto(url: String?) {
        val caminho = url?.takeIf { it.startsWith("file://") }?.removePrefix("file://") ?: return
        val f = File(caminho)
        if (f.parentFile?.absolutePath == pastaFotos(ctx).absolutePath) f.delete()
    }

    // ------------------------------------------------------------------ vendas registradas na conversa
    /**
     * Registra uma venda feita pelo WhatsApp, Instagram ou pessoalmente: baixa o estoque e entra nos relatórios.
     * [itens] = produto e quantidade; [precoCentavos] opcional por item (padrão: o preço do produto).
     */
    fun registrarVenda(
        cliente: String, itens: List<Triple<Loja.Produto, Int, Int?>>, pagamento: String, entrega: String,
        aguardandoPagamento: Boolean = false,
    ): Loja.Pedido {
        require(itens.isNotEmpty()) { "Escolha pelo menos um produto." }
        var venda: JSONObject? = null
        editar { raiz ->
            val id = raiz.optInt("proximaVenda", 1)
            raiz.put("proximaVenda", id + 1)
            val agora = System.currentTimeMillis()
            val linhas = JSONArray()
            var total = 0
            for ((p, qtd, preco) in itens) {
                val unit = preco ?: p.precoCentavos ?: 0
                total += unit * qtd
                linhas.put(JSONObject().put("produtoId", p.id).put("nome", p.nome).put("quantidade", qtd).put("precoCents", unit))
                acharProduto(raiz, p.id)?.let { o ->
                    if (!o.isNull("stockQuantity")) o.put("stockQuantity", (o.optInt("stockQuantity") - qtd).coerceAtLeast(0))
                }
            }
            venda = JSONObject().put("id", id).put("cliente", cliente.trim().ifBlank { "Cliente" }).put("itens", linhas)
                .put("totalCents", total).put("status", if (aguardandoPagamento) "awaiting_payment" else "paid").put("pagamento", pagamento).put("entrega", entrega)
                .put("criado", agora).put("pago", if (aguardandoPagamento) JSONObject.NULL else agora)
            (raiz.optJSONArray("vendas") ?: JSONArray().also { raiz.put("vendas", it) }).put(venda)
        }
        return vendaDe(venda!!)
    }

    /** Guarda o link de cobrança criado para a venda (a Vênus confere o pagamento por ele). */
    fun anexarCobranca(id: Int, ref: String, url: String, provedor: String) = editar { raiz ->
        vendaJson(raiz, id)?.put("cobranca", JSONObject().put("ref", ref).put("url", url).put("provedor", provedor).put("criado", System.currentTimeMillis()))
    }

    /** O pagamento do link entrou: a venda passa a paga (o estoque já tinha sido baixado ao registrar). Null se não achar ou já estava paga. */
    fun marcarPaga(id: Int, forma: String): Loja.Pedido? {
        var paga: JSONObject? = null
        editar { raiz ->
            val v = vendaJson(raiz, id) ?: return@editar
            if (v.optString("status") != "awaiting_payment") return@editar
            v.put("status", "paid").put("pago", System.currentTimeMillis()).put("pagamento", forma)
            paga = v
        }
        return paga?.let { vendaDe(it) }
    }

    private fun itensDe(v: JSONObject): Map<Int, Int> {
        val l = v.optJSONArray("itens") ?: return emptyMap()
        return (0 until l.length()).mapNotNull { l.optJSONObject(it) }.groupBy { it.optInt("produtoId") }.mapValues { e -> e.value.sumOf { it.optInt("quantidade") } }
    }

    private fun moverEstoque(raiz: JSONObject, delta: Map<Int, Int>) {
        for ((pid, d) in delta) acharProduto(raiz, pid)?.let { o ->
            if (!o.isNull("stockQuantity")) o.put("stockQuantity", (o.optInt("stockQuantity") - d).coerceAtLeast(0))
        }
    }

    /** Corrige uma venda do caderninho ainda não paga (cliente e itens) e acerta o estoque só pela diferença. */
    fun editarVendaPendente(id: Int, cliente: String, itens: List<PdvVendas.Linha>) = editar { raiz ->
        val v = vendaJson(raiz, id) ?: throw Loja.ErroDaLoja("Não achei essa venda no catálogo.")
        if (v.optString("status") != "awaiting_payment") throw Loja.ErroDaLoja("Essa venda já foi paga.")
        val antes = itensDe(v)
        val depois = itens.filter { it.produtoId > 0 }.groupBy { it.produtoId }.mapValues { e -> e.value.sumOf { it.quantidade } }
        moverEstoque(raiz, (antes.keys + depois.keys).associateWith { (depois[it] ?: 0) - (antes[it] ?: 0) }.filterValues { it != 0 })
        val linhas = JSONArray()
        for (i in itens) linhas.put(JSONObject().put("produtoId", i.produtoId).put("nome", i.nome).put("quantidade", i.quantidade).put("precoCents", i.precoCentavos))
        v.put("itens", linhas).put("totalCents", itens.sumOf { it.quantidade * it.precoCentavos }).put("cliente", cliente.trim().ifBlank { "Cliente" })
    }

    /** Apaga uma venda do caderninho ainda não paga; [devolverEstoque] põe os itens de volta no estoque. */
    fun removerVendaPendente(id: Int, devolverEstoque: Boolean) = editar { raiz ->
        val vendas = raiz.optJSONArray("vendas") ?: return@editar
        val pos = (0 until vendas.length()).firstOrNull { vendas.optJSONObject(it)?.optInt("id") == id } ?: return@editar
        val v = vendas.getJSONObject(pos)
        if (v.optString("status") != "awaiting_payment") throw Loja.ErroDaLoja("Essa venda já foi paga.")
        if (devolverEstoque) moverEstoque(raiz, itensDe(v).mapValues { -it.value })
        vendas.remove(pos)
    }

    private fun vendaJson(raiz: JSONObject, id: Int): JSONObject? {
        val vendas = raiz.optJSONArray("vendas") ?: return null
        return (0 until vendas.length()).mapNotNull { vendas.optJSONObject(it) }.firstOrNull { it.optInt("id") == id }
    }

    // ------------------------------------------------------------------ arquivo
    private fun mudar(id: Int, f: (JSONObject) -> Unit): Unit = editar { raiz ->
        val o = acharProduto(raiz, id) ?: throw Loja.ErroDaLoja("Não achei esse produto no catálogo.")
        f(o)
        o.put("atualizado", System.currentTimeMillis())
    }

    private fun editar(f: (JSONObject) -> Unit) {
        synchronized(TRAVA) {
            val raiz = ler(ctx)
            f(raiz)
            gravar(ctx, raiz)
        }
    }

    companion object {
        private val TRAVA = Any()

        fun arquivo(ctx: Context) = File(ctx.filesDir, "catalogo.json")
        fun pastaFotos(ctx: Context) = File(ctx.filesDir, "catalogo_fotos").apply { mkdirs() }

        fun ler(ctx: Context): JSONObject = synchronized(TRAVA) {
            try {
                arquivo(ctx).takeIf { it.exists() }?.readText()?.let { JSONObject(it) } ?: JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
        }

        fun gravar(ctx: Context, raiz: JSONObject) {
            synchronized(TRAVA) {
                // grava num temporário e troca: um desligamento no meio não corrompe o catálogo
                val tmp = File(ctx.filesDir, "catalogo.json.tmp")
                tmp.writeText(raiz.toString())
                if (!tmp.renameTo(arquivo(ctx))) {
                    arquivo(ctx).writeText(raiz.toString())
                    tmp.delete()
                }
            }
        }

        fun acharProduto(raiz: JSONObject, id: Int): JSONObject? {
            val lista = raiz.optJSONArray("produtos") ?: return null
            return (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }.firstOrNull { it.optInt("id") == id }
        }

        private fun devolverEstoque(raiz: JSONObject, venda: JSONObject) {
            val itens = venda.optJSONArray("itens") ?: return
            for (i in 0 until itens.length()) {
                val it = itens.optJSONObject(i) ?: continue
                acharProduto(raiz, it.optInt("produtoId"))?.let { o ->
                    if (!o.isNull("stockQuantity")) o.put("stockQuantity", o.optInt("stockQuantity") + it.optInt("quantidade"))
                }
            }
        }

        private fun int(o: JSONObject, campo: String): Int? = if (o.has(campo) && !o.isNull(campo)) o.optInt(campo) else null

        /** Um produto do arquivo no formato que o resto da Vênus usa (o bruto tem imageUrl e costCents). */
        fun produtoDe(o: JSONObject): Loja.Produto {
            val preco = int(o, "priceCents")
            val antigo = int(o, "compareAtCents")?.takeIf { preco != null && it > preco }
            val bruto = JSONObject(o.toString())
            if (!bruto.has(Loja.CAMPO_CUSTO)) bruto.put(Loja.CAMPO_CUSTO, JSONObject.NULL)
            return Loja.Produto(o.optInt("id"), o.optString("name"), o.optBoolean("active", true), int(o, "stockQuantity"), preco, antigo, bruto, link = "")
        }

        fun lerProdutos(raiz: JSONObject): List<Loja.Produto> {
            val lista = raiz.optJSONArray("produtos") ?: return emptyList()
            return (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }.map { produtoDe(it) }
        }

        fun vendaDe(v: JSONObject): Loja.Pedido {
            val a = v.optJSONArray("itens") ?: JSONArray()
            val itens = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Loja.Item(it.optString("nome"), it.optInt("quantidade"), it.optInt("precoCents"), it.optInt("produtoId"))
            }
            val pago = if (v.has("pago") && !v.isNull("pago")) v.optLong("pago") else null
            return Loja.Pedido(
                v.optInt("id"), v.optString("status", "paid"), v.optInt("totalCents"), v.optString("cliente"),
                v.optString("entrega", "combinada"), v.optLong("criado"), pago, itens, rastreio = v.optString("rastreio").takeIf { it.isNotBlank() },
            )
        }

        fun lerVendas(raiz: JSONObject): List<Loja.Pedido> {
            val lista = raiz.optJSONArray("vendas") ?: return emptyList()
            return (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }.map { vendaDe(it) }.sortedByDescending { it.criado }
        }

        /** Produtos para completar: sem foto ou sem preço (os que vieram da nota fiscal, por exemplo). */
        fun faltando(p: Loja.Produto): List<String> = listOfNotNull(
            if (p.precoCentavos == null) "sem preço" else null,
            if (p.bruto?.optString("imageUrl").isNullOrBlank() || p.bruto?.isNull("imageUrl") == true) "sem foto" else null,
        )
    }
}
