package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Adaptadores de plataformas de loja: a Vênus fala com o site da loja por aqui quando ele não é do Floot.
 * Cada adaptador traduz produtos e pedidos para o formato da Vênus (Loja.Produto e Loja.Pedido) e faz as mudanças
 * que ela confirma com o dono: estoque, preço, promoção, mostrar/esconder produto e andamento do pedido.
 *  - Floot (padrão, o site da Desejo de Intimidade): continua no Loja.kt, com login de administrador.
 *  - Shopify: token de um app personalizado (Admin API), pela API GraphQL.
 *  - WooCommerce: chave e segredo da REST API (WooCommerce → Configurações → Avançado → REST API).
 *  - Nuvemshop: token de acesso e número da loja (de um app instalado na loja).
 * As funções que leem os formatos (ler…) são separadas para poderem ser testadas sem internet.
 */
object Plataformas {

    data class Tipo(val id: String, val nome: String, val ajuda: String)

    val TIPOS = listOf(
        Tipo("floot", "Site próprio", "Entre com o e-mail e a senha de administrador do site."),
        Tipo("shopify", "Shopify", "No painel da Shopify: Configurações → Apps → Desenvolver apps → criar app → dar acesso a produtos, estoque e pedidos → instalar e copiar o token da Admin API (começa com shpat_)."),
        Tipo("woocommerce", "WooCommerce", "No WordPress: WooCommerce → Configurações → Avançado → REST API → Adicionar chave, com permissão de leitura e escrita. Copie a chave (ck_…) e o segredo (cs_…)."),
        Tipo("interno", "Sem site (catálogo na Vênus)", "Para quem vende pelo WhatsApp, Instagram e Facebook: os produtos, fotos, estoque e vendas ficam aqui na Vênus."),
        Tipo("nuvemshop", "Nuvemshop", "Precisa do token de acesso e do número da loja (user_id), que vêm de um app instalado na sua loja pelo portal de parceiros da Nuvemshop."),
    )

    private const val PREFS = "plataforma"
    const val SHOPIFY_VERSAO = "2026-07"

    data class Config(val tipo: String, val endereco: String, val chave: String, val segredo: String, val lojaId: String)

    fun tipo(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tipo", "floot") ?: "floot"

    fun config(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(tipo(ctx), p.getString("endereco", "").orEmpty(), Cofre.ler(p, "chave"), Cofre.ler(p, "segredo"), p.getString("loja_id", "").orEmpty())
    }

    fun salvar(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tipo", c.tipo).putString("endereco", Perfil.normalizarSite(c.endereco).takeIf { c.endereco.isNotBlank() }.orEmpty())
            .putString("chave", Cofre.selar(c.chave.trim())).putString("segredo", Cofre.selar(c.segredo.trim()))
            .putString("loja_id", c.lojaId.trim())
            .apply()
        Diario.anotar(ctx, "conexao", "Plataforma da loja: ${TIPOS.firstOrNull { it.id == c.tipo }?.nome ?: c.tipo}", "tela")
    }

    /** O adaptador da plataforma escolhida; null para o Floot (que é o Loja.kt) ou se faltar configuração. */
    fun ativa(ctx: Context): Adaptador? {
        if (tipo(ctx) == "interno") return CatalogoInterno(ctx.applicationContext ?: ctx)
        val c = config(ctx)
        if (c.chave.isBlank()) return null
        return when (c.tipo) {
            "shopify" -> if (c.endereco.isNotBlank()) Shopify(c) else null
            "woocommerce" -> if (c.endereco.isNotBlank() && c.segredo.isNotBlank()) WooCommerce(c) else null
            "nuvemshop" -> if (c.lojaId.isNotBlank()) Nuvemshop(c) else null
            else -> null
        }
    }

    /** O que todas as plataformas sabem fazer. */
    interface Adaptador {
        val nome: String
        fun produtos(): List<Loja.Produto>
        fun pedidos(): List<Loja.Pedido>
        fun definirEstoque(p: Loja.Produto, quantidade: Int)
        /** [tipo]: preco, promocao (valor = preço novo), fim_promocao, ativar, desativar. */
        fun mudarProduto(p: Loja.Produto, tipo: String, valor: Int?)
        /** [status]: paid, preparing, shipped, delivered, canceled. */
        fun mudarPedido(o: Loja.Pedido, status: String, rastreio: String?)
    }

    // ================================================================== rede
    class Resposta(val codigo: Int, val texto: String)

    fun pedir(metodo: String, url: String, cabecalhos: Map<String, String>, corpo: String? = null): Resposta {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Venus/1.0 (assistente de loja)")
            for ((k, v) in cabecalhos) c.setRequestProperty(k, v)
            if (corpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Resposta(codigo, texto)
        } finally {
            c.disconnect()
        }
    }

    fun conferir(nome: String, r: Resposta): String {
        if (r.codigo == 401 || r.codigo == 403) throw Loja.ErroDaLoja("A $nome não aceitou a chave. Confira nos ajustes (Plataforma da loja).")
        if (r.codigo == 404) throw Loja.ErroDaLoja("A $nome não achou o endereço. Confira o site nos ajustes.")
        if (r.codigo == 429) throw Loja.ErroDaLoja("A $nome pediu para esperar um pouco (muitos pedidos seguidos).")
        if (r.codigo !in 200..299) throw Loja.ErroDaLoja("A $nome respondeu com erro ${r.codigo}.")
        return r.texto
    }

    // ================================================================== conversões comuns
    /** "12.90", 12.9 ou "12,90" → 1290 centavos; null se vazio. */
    fun centavos(v: Any?): Int? {
        val t = when (v) {
            null, JSONObject.NULL -> return null
            is Number -> return Math.round(v.toDouble() * 100).toInt()
            else -> v.toString().trim()
        }
        if (t.isEmpty() || t == "null") return null
        return t.replace(",", ".").toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
    }

    fun reais(centavos: Int): String = String.format(java.util.Locale.US, "%.2f", centavos / 100.0)

    /** Data ISO 8601 ("2026-11-05T10:30:00-03:00", "2026-11-05T13:30:00Z" ou "2026-11-05T10:30:00") → milissegundos. */
    fun data(v: String?): Long? {
        val t = v?.trim().orEmpty()
        if (t.isEmpty() || t == "null") return null
        return try {
            java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(t).atZone(java.time.ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()
            } catch (x: Exception) {
                try {
                    // Nuvemshop: "2026-11-05T10:30:00+0000"
                    java.time.OffsetDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant().toEpochMilli()
                } catch (y: Exception) {
                    null
                }
            }
        }
    }

    /** Número grande (id da Shopify) → Int estável para a Vênus. */
    fun idCurto(longo: String): Int = (longo.toLongOrNull() ?: longo.hashCode().toLong()).let { (it % Int.MAX_VALUE).toInt() }

    fun ehRetirada(texto: String): Boolean = Regex("retir|pickup|local_pickup|buscar na loja", RegexOption.IGNORE_CASE).containsMatchIn(texto)

    // ================================================================== WooCommerce
    class WooCommerce(private val c: Config) : Adaptador {
        override val nome = "WooCommerce"
        private fun url(caminho: String, consulta: String = ""): String =
            "${c.endereco}/wp-json/wc/v3/$caminho?consumer_key=${enc(c.chave)}&consumer_secret=${enc(c.segredo)}" + (if (consulta.isNotBlank()) "&$consulta" else "")

        private fun lista(caminho: String, paginas: Int): JSONArray {
            val tudo = JSONArray()
            for (pagina in 1..paginas) {
                val a = JSONArray(conferir(nome, pedir("GET", url(caminho, "per_page=100&page=$pagina"), emptyMap())))
                for (i in 0 until a.length()) tudo.put(a.get(i))
                if (a.length() < 100) break
            }
            return tudo
        }

        override fun produtos() = lerProdutosWoo(lista("products", 5))
        override fun pedidos() = lerPedidosWoo(lista("orders", 3))

        private fun atualizar(caminho: String, corpo: JSONObject) {
            conferir(nome, pedir("PUT", url(caminho), emptyMap(), corpo.toString()))
        }

        override fun definirEstoque(p: Loja.Produto, quantidade: Int) =
            atualizar("products/${p.id}", JSONObject().put("manage_stock", true).put("stock_quantity", quantidade))

        override fun mudarProduto(p: Loja.Produto, tipo: String, valor: Int?) = atualizar("products/${p.id}", corpoWoo(p, tipo, valor))

        override fun mudarPedido(o: Loja.Pedido, status: String, rastreio: String?) {
            val corpo = JSONObject().put("status", statusParaWoo(status))
            if (!rastreio.isNullOrBlank()) corpo.put("customer_note", "Código de rastreio: $rastreio")
            atualizar("orders/${o.id}", corpo)
        }
    }

    fun corpoWoo(p: Loja.Produto, tipo: String, valor: Int?): JSONObject = when (tipo) {
        "preco" -> JSONObject().put("regular_price", reais(valor!!)).put("sale_price", "")
        "promocao" -> JSONObject().put("regular_price", reais(p.precoAntigo ?: p.precoCentavos ?: valor!!)).put("sale_price", reais(valor!!))
        "fim_promocao" -> JSONObject().put("sale_price", "")
        "ativar" -> JSONObject().put("status", "publish")
        "desativar" -> JSONObject().put("status", "draft")
        else -> throw Loja.ErroDaLoja("Essa mudança ainda não funciona no WooCommerce. Faça pelo painel.")
    }

    fun statusParaWoo(s: String) = when (s) {
        "paid", "preparing" -> "processing"
        "shipped", "delivered" -> "completed"
        "canceled" -> "cancelled"
        else -> "pending"
    }

    fun lerProdutosWoo(a: JSONArray): List<Loja.Produto> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
        val regular = centavos(p.opt("regular_price"))
        val venda = centavos(p.opt("sale_price"))
        val preco = venda ?: centavos(p.opt("price")) ?: regular
        Loja.Produto(
            id = p.optInt("id"), nome = p.optString("name"), ativo = p.optString("status") == "publish",
            estoque = if (p.optBoolean("manage_stock") && !p.isNull("stock_quantity")) p.optInt("stock_quantity") else null,
            precoCentavos = preco, precoAntigo = if (venda != null && regular != null && regular > venda) regular else null,
            bruto = p, link = p.optString("permalink").ifBlank { null },
        )
    }

    fun lerPedidosWoo(a: JSONArray): List<Loja.Pedido> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        val itens = o.optJSONArray("line_items") ?: JSONArray()
        val frete = o.optJSONArray("shipping_lines")?.optJSONObject(0)
        val cobranca = o.optJSONObject("billing") ?: JSONObject()
        Loja.Pedido(
            id = o.optInt("id"),
            status = when (o.optString("status")) {
                "processing" -> "paid"
                "completed" -> "delivered"
                "cancelled", "refunded", "failed" -> "canceled"
                else -> "awaiting_payment"
            },
            totalCentavos = centavos(o.opt("total")) ?: 0,
            cliente = "${cobranca.optString("first_name")} ${cobranca.optString("last_name")}".trim(),
            entrega = if (frete != null && ehRetirada(frete.optString("method_id") + " " + frete.optString("method_title"))) "pickup" else "shipping",
            criado = data(o.optString("date_created_gmt").takeIf { it.isNotBlank() && it != "null" }?.let { it + "Z" }) ?: data(o.optString("date_created")) ?: 0L,
            pago = data(o.optString("date_paid_gmt").takeIf { it.isNotBlank() && it != "null" }?.let { it + "Z" }),
            itens = (0 until itens.length()).mapNotNull { itens.optJSONObject(it) }.map { i ->
                Loja.Item(i.optString("name"), i.optInt("quantity", 1), centavos(i.opt("price")) ?: 0, i.optInt("product_id").takeIf { it > 0 })
            },
            telefone = cobranca.optString("phone"),
            descontoCentavos = centavos(o.opt("discount_total")) ?: 0,
            freteCentavos = centavos(o.opt("shipping_total")) ?: 0,
        )
    }

    // ================================================================== Nuvemshop
    class Nuvemshop(private val c: Config) : Adaptador {
        override val nome = "Nuvemshop"
        private val base get() = "https://api.nuvemshop.com.br/v1/${c.lojaId}"
        private val cabecalhos get() = mapOf("Authentication" to "bearer ${c.chave}", "Authorization" to "bearer ${c.chave}")

        private fun lista(caminho: String, paginas: Int): JSONArray {
            val tudo = JSONArray()
            for (pagina in 1..paginas) {
                val r = pedir("GET", "$base/$caminho?per_page=200&page=$pagina", cabecalhos)
                if (r.codigo == 404 && pagina > 1) break // passou da última página
                val a = JSONArray(conferir(nome, r))
                for (i in 0 until a.length()) tudo.put(a.get(i))
                if (a.length() < 200) break
            }
            return tudo
        }

        override fun produtos() = lerProdutosNuvem(lista("products", 3))
        override fun pedidos() = lerPedidosNuvem(lista("orders", 2))

        private fun variante(p: Loja.Produto): Long =
            p.bruto?.optJSONArray("variants")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
                ?: throw Loja.ErroDaLoja("Não achei a variação de ${p.nome} na Nuvemshop.")

        override fun definirEstoque(p: Loja.Produto, quantidade: Int) {
            conferir(nome, pedir("PUT", "$base/products/${p.id}/variants/${variante(p)}", cabecalhos, JSONObject().put("stock", quantidade).toString()))
        }

        override fun mudarProduto(p: Loja.Produto, tipo: String, valor: Int?) {
            when (tipo) {
                "ativar", "desativar" -> conferir(nome, pedir("PUT", "$base/products/${p.id}", cabecalhos, JSONObject().put("published", tipo == "ativar").toString()))
                else -> conferir(nome, pedir("PUT", "$base/products/${p.id}/variants/${variante(p)}", cabecalhos, corpoNuvem(p, tipo, valor).toString()))
            }
        }

        override fun mudarPedido(o: Loja.Pedido, status: String, rastreio: String?) {
            val (caminho, corpo) = when (status) {
                "shipped" -> "fulfill" to JSONObject().put("notify_customer", true).apply { if (!rastreio.isNullOrBlank()) put("shipping_tracking_number", rastreio) }
                "delivered" -> "close" to JSONObject()
                "canceled" -> "cancel" to JSONObject().put("reason", "other").put("email", true)
                else -> throw Loja.ErroDaLoja("Na Nuvemshop, esse andamento muda pelo painel. Pela Vênus: enviado, entregue ou cancelado.")
            }
            conferir(nome, pedir("POST", "$base/orders/${o.id}/$caminho", cabecalhos, corpo.toString()))
        }
    }

    fun corpoNuvem(p: Loja.Produto, tipo: String, valor: Int?): JSONObject = when (tipo) {
        "preco" -> JSONObject().put("price", reais(valor!!)).put("promotional_price", JSONObject.NULL)
        "promocao" -> JSONObject().put("price", reais(p.precoAntigo ?: p.precoCentavos ?: valor!!)).put("promotional_price", reais(valor!!))
        "fim_promocao" -> JSONObject().put("promotional_price", JSONObject.NULL)
        else -> throw Loja.ErroDaLoja("Essa mudança ainda não funciona na Nuvemshop. Faça pelo painel.")
    }

    /** O nome vem por idioma: {"pt": "Gel", "es": "Gel"}. */
    private fun nomeNuvem(v: Any?): String = when (v) {
        is JSONObject -> v.optString("pt").ifBlank { v.keys().asSequence().map { v.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty() }
        null, JSONObject.NULL -> ""
        else -> v.toString()
    }

    fun lerProdutosNuvem(a: JSONArray): List<Loja.Produto> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
        val variantes = p.optJSONArray("variants") ?: JSONArray()
        val v = variantes.optJSONObject(0) ?: JSONObject()
        val preco = centavos(v.opt("price"))
        val promo = centavos(v.opt("promotional_price"))
        val estoque = (0 until variantes.length()).mapNotNull { variantes.optJSONObject(it) }.let { vs ->
            // estoque null = ilimitado na Nuvemshop
            if (vs.isEmpty() || vs.any { it.isNull("stock") || !it.has("stock") }) null else vs.sumOf { it.optInt("stock") }
        }
        Loja.Produto(
            id = p.optInt("id"), nome = nomeNuvem(p.opt("name")), ativo = p.optBoolean("published", true), estoque = estoque,
            precoCentavos = promo ?: preco, precoAntigo = if (promo != null && preco != null && preco > promo) preco else null,
            bruto = p, link = p.optString("canonical_url").ifBlank { null },
        )
    }

    fun lerPedidosNuvem(a: JSONArray): List<Loja.Pedido> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        val itens = o.optJSONArray("products") ?: JSONArray()
        val cliente = o.optJSONObject("customer") ?: JSONObject()
        val pagamento = o.optString("payment_status")
        val envio = o.optString("shipping_status")
        Loja.Pedido(
            id = o.optInt("id"),
            status = when {
                o.optString("status") == "cancelled" || pagamento in setOf("refunded", "voided", "abandoned") -> "canceled"
                o.optString("status") == "closed" -> "delivered"
                envio == "shipped" || envio == "fulfilled" -> "shipped"
                pagamento == "paid" -> "paid"
                else -> "awaiting_payment"
            },
            totalCentavos = centavos(o.opt("total")) ?: 0,
            cliente = o.optString("contact_name").ifBlank { cliente.optString("name") },
            entrega = if (o.optString("shipping_pickup_type") == "pickup" || ehRetirada(o.optString("shipping_option"))) "pickup" else "shipping",
            criado = data(o.optString("created_at")) ?: 0L,
            pago = data(o.optString("paid_at")),
            itens = (0 until itens.length()).mapNotNull { itens.optJSONObject(it) }.map { i ->
                Loja.Item(nomeNuvem(i.opt("name")), i.optInt("quantity", 1), centavos(i.opt("price")) ?: 0, i.optInt("product_id").takeIf { it > 0 })
            },
            telefone = o.optString("contact_phone").ifBlank { cliente.optString("phone") },
            descontoCentavos = centavos(o.opt("discount")) ?: 0,
            freteCentavos = centavos(o.opt("shipping_cost_customer")) ?: 0,
            rastreio = o.optString("shipping_tracking_number").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    // ================================================================== Shopify (GraphQL da Admin API)
    class Shopify(private val c: Config) : Adaptador {
        override val nome = "Shopify"
        private val endereco get() = c.endereco.removeSuffix("/") + "/admin/api/$SHOPIFY_VERSAO/graphql.json"

        private fun consultar(consulta: String, variaveis: JSONObject = JSONObject()): JSONObject {
            val texto = conferir(nome, pedir("POST", endereco, mapOf("X-Shopify-Access-Token" to c.chave),
                JSONObject().put("query", consulta).put("variables", variaveis).toString()))
            val o = JSONObject(texto)
            o.optJSONArray("errors")?.optJSONObject(0)?.let { throw Loja.ErroDaLoja("A Shopify recusou: ${it.optString("message").take(160)}") }
            return o.optJSONObject("data") ?: JSONObject()
        }

        /** Erros de mutação vêm em userErrors. */
        private fun mudar(consulta: String, variaveis: JSONObject, campo: String) {
            val r = consultar(consulta, variaveis).optJSONObject(campo)
            r?.optJSONArray("userErrors")?.optJSONObject(0)?.let { throw Loja.ErroDaLoja("A Shopify recusou: ${it.optString("message").take(160)}") }
        }

        override fun produtos(): List<Loja.Produto> = lerProdutosShopify(consultar(PRODUTOS))

        override fun pedidos(): List<Loja.Pedido> = try {
            lerPedidosShopify(consultar(PEDIDOS.replace("CLIENTE", "customer { displayName phone }")))
        } catch (e: Loja.ErroDaLoja) {
            // apps sem acesso aos dados protegidos de clientes: lê os pedidos sem nome e telefone
            lerPedidosShopify(consultar(PEDIDOS.replace("CLIENTE", "")))
        }

        private fun variante(p: Loja.Produto): JSONObject = p.bruto?.optJSONObject("variants")?.optJSONArray("nodes")?.optJSONObject(0)
            ?: throw Loja.ErroDaLoja("Não achei a variação de ${p.nome} na Shopify.")

        override fun definirEstoque(p: Loja.Produto, quantidade: Int) {
            val item = variante(p).optJSONObject("inventoryItem")?.optString("id") ?: throw Loja.ErroDaLoja("${p.nome} não controla estoque na Shopify.")
            val local = consultar("{ locations(first: 1) { nodes { id } } }").optJSONObject("locations")?.optJSONArray("nodes")?.optJSONObject(0)?.optString("id")
                ?: throw Loja.ErroDaLoja("Não achei o local de estoque na Shopify.")
            val q = JSONObject().put("inventoryItemId", item).put("locationId", local).put("quantity", quantidade).put("changeFromQuantity", JSONObject.NULL)
            mudar(
                "mutation(\$i: InventorySetQuantitiesInput!) { inventorySetQuantities(input: \$i) { userErrors { message } } }",
                JSONObject().put("i", JSONObject().put("name", "available").put("reason", "correction").put("quantities", JSONArray().put(q))),
                "inventorySetQuantities",
            )
        }

        override fun mudarProduto(p: Loja.Produto, tipo: String, valor: Int?) {
            val id = p.bruto?.optString("id") ?: throw Loja.ErroDaLoja("Não achei ${p.nome} na Shopify.")
            when (tipo) {
                "ativar", "desativar" -> mudar(
                    "mutation(\$p: ProductUpdateInput!) { productUpdate(product: \$p) { userErrors { message } } }",
                    JSONObject().put("p", JSONObject().put("id", id).put("status", if (tipo == "ativar") "ACTIVE" else "DRAFT")),
                    "productUpdate",
                )
                else -> {
                    val v = JSONObject().put("id", variante(p).optString("id"))
                    when (tipo) {
                        "preco" -> v.put("price", reais(valor!!)).put("compareAtPrice", JSONObject.NULL)
                        "promocao" -> v.put("price", reais(valor!!)).put("compareAtPrice", reais(p.precoAntigo ?: p.precoCentavos ?: valor))
                        "fim_promocao" -> v.put("price", reais(p.precoAntigo ?: p.precoCentavos ?: 0)).put("compareAtPrice", JSONObject.NULL)
                        else -> throw Loja.ErroDaLoja("Essa mudança ainda não funciona na Shopify. Faça pelo painel.")
                    }
                    mudar(
                        "mutation(\$id: ID!, \$v: [ProductVariantsBulkInput!]!) { productVariantsBulkUpdate(productId: \$id, variants: \$v) { userErrors { message } } }",
                        JSONObject().put("id", id).put("v", JSONArray().put(v)),
                        "productVariantsBulkUpdate",
                    )
                }
            }
        }

        override fun mudarPedido(o: Loja.Pedido, status: String, rastreio: String?) {
            throw Loja.ErroDaLoja("Na Shopify, o andamento do pedido (envio e entrega) muda pelo painel por enquanto.")
        }
    }

    private const val PRODUTOS = "{ products(first: 100, sortKey: UPDATED_AT, reverse: true) { nodes { id legacyResourceId title status onlineStoreUrl " +
        "variants(first: 20) { nodes { id price compareAtPrice inventoryQuantity inventoryItem { id tracked } } } } } }"

    private const val PEDIDOS = "{ orders(first: 100, sortKey: CREATED_AT, reverse: true) { nodes { legacyResourceId name createdAt processedAt cancelledAt " +
        "displayFinancialStatus displayFulfillmentStatus CLIENTE " +
        "totalPriceSet { shopMoney { amount } } totalDiscountsSet { shopMoney { amount } } totalShippingPriceSet { shopMoney { amount } } " +
        "shippingLine { title } lineItems(first: 50) { nodes { title quantity originalUnitPriceSet { shopMoney { amount } } product { legacyResourceId } } } } } }"

    private fun dinheiroShopify(o: JSONObject?, campo: String): Int? = centavos(o?.optJSONObject(campo)?.optJSONObject("shopMoney")?.opt("amount"))

    fun lerProdutosShopify(dados: JSONObject): List<Loja.Produto> {
        val nos = dados.optJSONObject("products")?.optJSONArray("nodes") ?: JSONArray()
        return (0 until nos.length()).mapNotNull { nos.optJSONObject(it) }.map { p ->
            val vs = p.optJSONObject("variants")?.optJSONArray("nodes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            val v = vs.firstOrNull() ?: JSONObject()
            val preco = centavos(v.opt("price"))
            val antes = centavos(v.opt("compareAtPrice"))
            val controla = vs.any { it.optJSONObject("inventoryItem")?.optBoolean("tracked") == true }
            Loja.Produto(
                id = idCurto(p.optString("legacyResourceId")), nome = p.optString("title"), ativo = p.optString("status") == "ACTIVE",
                estoque = if (controla) vs.sumOf { it.optInt("inventoryQuantity") } else null,
                precoCentavos = preco, precoAntigo = if (antes != null && preco != null && antes > preco) antes else null,
                bruto = p, link = p.optString("onlineStoreUrl").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    fun lerPedidosShopify(dados: JSONObject): List<Loja.Pedido> {
        val nos = dados.optJSONObject("orders")?.optJSONArray("nodes") ?: JSONArray()
        return (0 until nos.length()).mapNotNull { nos.optJSONObject(it) }.map { o ->
            val financeiro = o.optString("displayFinancialStatus")
            val envio = o.optString("displayFulfillmentStatus")
            val itens = o.optJSONObject("lineItems")?.optJSONArray("nodes") ?: JSONArray()
            val cliente = o.optJSONObject("customer")
            Loja.Pedido(
                id = o.optString("name").filter { it.isDigit() }.toIntOrNull() ?: idCurto(o.optString("legacyResourceId")),
                status = when {
                    !o.isNull("cancelledAt") && o.optString("cancelledAt").isNotBlank() -> "canceled"
                    financeiro in setOf("PENDING", "AUTHORIZED", "EXPIRED") -> "awaiting_payment"
                    financeiro in setOf("REFUNDED", "VOIDED") -> "canceled"
                    envio == "FULFILLED" -> "shipped"
                    envio == "PARTIALLY_FULFILLED" || envio == "IN_PROGRESS" -> "preparing"
                    else -> "paid"
                },
                totalCentavos = dinheiroShopify(o, "totalPriceSet") ?: 0,
                cliente = cliente?.optString("displayName").orEmpty(),
                entrega = if (ehRetirada(o.optJSONObject("shippingLine")?.optString("title").orEmpty())) "pickup" else "shipping",
                criado = data(o.optString("createdAt")) ?: 0L,
                pago = if (financeiro == "PAID" || financeiro == "PARTIALLY_REFUNDED") data(o.optString("processedAt")) else null,
                itens = (0 until itens.length()).mapNotNull { itens.optJSONObject(it) }.map { i ->
                    Loja.Item(i.optString("title"), i.optInt("quantity", 1), dinheiroShopify(i, "originalUnitPriceSet") ?: 0,
                        i.optJSONObject("product")?.optString("legacyResourceId")?.takeIf { it.isNotBlank() }?.let { idCurto(it) })
                },
                telefone = cliente?.optString("phone")?.takeIf { it != "null" }.orEmpty(),
                descontoCentavos = dinheiroShopify(o, "totalDiscountsSet") ?: 0,
                freteCentavos = dinheiroShopify(o, "totalShippingPriceSet") ?: 0,
            )
        }
    }

    private fun enc(t: String) = URLEncoder.encode(t, "UTF-8")
}
