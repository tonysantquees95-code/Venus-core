package com.tonimarques.sextafeira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ligação da Vênus com a loja Desejo de Intimidade (site no Floot).
 * Entra como o administrador da loja (o mesmo login do painel), pela internet, e lê pedidos e estoque.
 * A senha fica guardada só no celular, criptografada pela chave segura do Android.
 * Os dados dos clientes ficam no celular: nada disso é enviado ao Gemini.
 * As funções com rede rodam fora da thread principal.
 */
class Loja(private val contexto: Context) {

    private val prefs = contexto.getSharedPreferences("loja", Context.MODE_PRIVATE)

    class ErroDaLoja(mensagem: String) : Exception(mensagem)

    // ------------------------------------------------------------- conta
    val conectada get() = prefs.getString(P_EMAIL, null) != null && prefs.getString(P_SENHA, null) != null
    val email get() = prefs.getString(P_EMAIL, null)

    /** Testa o login e, se der certo, guarda o e-mail e a senha (criptografada). Devolve null ou o erro. */
    fun conectar(email: String, senha: String): String? {
        val r = try {
            entrar(email, senha)
        } catch (e: ErroDaLoja) {
            return e.message
        } catch (e: Exception) {
            return "Não consegui falar com o site agora. Confira a internet."
        }
        if (r != "admin") return "Essa conta não é a de administrador da loja."
        prefs.edit().putString(P_EMAIL, email.trim()).putString(P_SENHA, cifrar(senha)).apply()
        return null
    }

    fun desconectar() {
        prefs.edit().clear().apply()
    }

    /** Faz o login no site e guarda o cookie da sessão. Devolve o papel da conta ("admin" ou "user"). */
    private fun entrar(email: String, senha: String): String {
        val corpo = JSONObject().put("json", JSONObject().put("identifier", email.trim()).put("password", senha)).toString()
        val c = abrir("/_api/auth/login_with_password", "POST")
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(corpo.toByteArray()) }
        val codigo = c.responseCode
        val texto = ler(c)
        if (codigo == 401 || codigo == 429) {
            throw ErroDaLoja(dados(texto).optString("message").ifBlank { "E-mail ou senha incorretos." })
        }
        if (codigo !in 200..299) throw ErroDaLoja("O site respondeu com erro $codigo.")
        val cookie = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
            .firstOrNull { it.startsWith("$COOKIE=") }?.substringBefore(";")
            ?: throw ErroDaLoja("O site não abriu a sessão.")
        prefs.edit().putString(P_COOKIE, cookie).apply()
        return dados(texto).optJSONObject("user")?.optString("role").orEmpty()
    }

    /** GET num endereço do painel, entrando de novo sozinha quando a sessão vence (ela dura 1 dia). */
    private fun buscar(caminho: String): JSONObject {
        repeat(2) { tentativa ->
            val cookie = prefs.getString(P_COOKIE, null)
            if (cookie == null || tentativa == 1) reentrar()
            val c = abrir(caminho, "GET")
            c.setRequestProperty("Cookie", prefs.getString(P_COOKIE, "").orEmpty())
            val codigo = c.responseCode
            val texto = ler(c)
            if (codigo == 401 || codigo == 403) {
                if (tentativa == 0) return@repeat
                throw ErroDaLoja(if (codigo == 403) "A conta salva não é de administrador." else "Não consegui entrar na loja. Confira o login nos ajustes.")
            }
            if (codigo !in 200..299) throw ErroDaLoja("O site respondeu com erro $codigo.")
            return dados(texto)
        }
        throw ErroDaLoja("Não consegui entrar na loja.")
    }

    /** POST num endereço do painel (formato superjson), entrando de novo quando a sessão vence. */
    private fun enviar(caminho: String, corpo: JSONObject): JSONObject {
        val texto = JSONObject().put("json", corpo).toString()
        repeat(2) { tentativa ->
            val cookie = prefs.getString(P_COOKIE, null)
            if (cookie == null || tentativa == 1) reentrar()
            val c = abrir(caminho, "POST")
            c.setRequestProperty("Cookie", prefs.getString(P_COOKIE, "").orEmpty())
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(texto.toByteArray()) }
            val codigo = c.responseCode
            val resposta = ler(c)
            if (codigo == 401 || codigo == 403) {
                if (tentativa == 0) return@repeat
                throw ErroDaLoja(if (codigo == 403) "A conta salva não é de administrador." else "Não consegui entrar na loja. Confira o login nos ajustes.")
            }
            if (codigo !in 200..299) {
                val erro = dados(resposta).optString("error").ifBlank { dados(resposta).optString("message") }
                throw ErroDaLoja(if (erro.isNotBlank()) "O site recusou: $erro" else "O site respondeu com erro $codigo. Nada foi mudado.")
            }
            return dados(resposta)
        }
        throw ErroDaLoja("Não consegui entrar na loja.")
    }

    private fun reentrar() {
        val email = prefs.getString(P_EMAIL, null) ?: throw ErroDaLoja(SEM_CONTA)
        val senha = prefs.getString(P_SENHA, null)?.let { decifrar(it) } ?: throw ErroDaLoja(SEM_CONTA)
        entrar(email, senha)
    }

    private fun abrir(caminho: String, metodo: String): HttpURLConnection =
        (URL(SITE + caminho).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Venus/1.0 (Android)")
        }

    private fun ler(c: HttpURLConnection): String = try {
        (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } finally {
        c.disconnect()
    }

    // ------------------------------------------------------------- dados
    fun pedidos(): List<Pedido> {
        val lista = buscar("/_api/admin/orders").optJSONArray("orders") ?: JSONArray()
        return lerPedidos(lista)
    }

    fun produtos(): List<Produto> {
        val lista = buscar("/_api/admin/products").optJSONArray("products") ?: JSONArray()
        return lerProdutos(lista)
    }

    // ------------------------------------------------------------- respostas
    fun resumo(periodo: String): String = montarResumo(pedidos(), periodo, Calendar.getInstance())

    fun pedidosPendentes(): String = montarPendentes(pedidos())

    fun estoqueDe(produto: String): String = montarEstoque(produtos(), produto)

    fun acabando(): String = montarAcabando(produtos())

    fun estoqueGeral(): String = montarEstoqueGeral(produtos())

    fun retiradas(): String = montarRetiradas(pedidos())

    fun maisVendidos(periodo: String): String = montarMaisVendidos(pedidos(), periodo, Calendar.getInstance())

    fun comparar(): String = montarComparacao(pedidos(), Calendar.getInstance())

    fun parados(): String = montarParados(pedidos(), produtos(), Calendar.getInstance())

    fun reposicao(periodo: String): String = montarReposicao(pedidos(), produtos(), periodo, Calendar.getInstance())

    fun pedido(id: Int): Pedido? = pedidos().firstOrNull { it.id == id }

    /** Monta a pergunta de confirmação (nada muda no site ainda). */
    fun prepararAlteracao(tipo: String, falado: String, valor: Int?): Preparo = montarAlteracao(produtos(), tipo, falado, valor)

    fun prepararEntregue(id: Int): Preparo = montarEntregue(pedidos(), id)

    fun prepararStatus(id: Int, status: String, rastreio: String?): Preparo = montarStatus(pedidos(), id, status, rastreio)

    /** Aplica uma alteração já confirmada. */
    fun aplicar(a: Alteracao): String {
        when (a.tipo) {
            "estoque_somar", "estoque_tirar", "estoque_definir" -> {
                val p = a.produto!!
                enviar("/_api/admin/stock_update", JSONObject().put("productId", p.id).put("quantity", a.valor!!).put("mode", "set"))
                return "Pronto: ${p.nome} agora tem ${plural(a.valor, "unidade", "unidades")} no estoque."
            }
            "preco", "ativar", "desativar", "promocao", "fim_promocao" -> {
                val p = a.produto!!
                // relê o produto na hora, para não desfazer outra mudança feita pelo painel
                val atual = produtos().firstOrNull { it.id == p.id } ?: throw ErroDaLoja("Não achei mais ${p.nome} na loja.")
                val corpo = corpoDoProduto(atual.bruto, a.tipo, a.valor)
                    ?: throw ErroDaLoja("Não consegui ler todos os dados de ${p.nome}. Faça essa mudança pelo painel do site.")
                enviar("/_api/admin/product_save", corpo)
                return when (a.tipo) {
                    "preco" -> "Pronto: ${p.nome} agora custa ${dinheiro(a.valor!!)} no site."
                    "promocao" -> "Pronto: ${p.nome} está em promoção por ${dinheiro(a.valor!!)}, com o preço antigo riscado no site."
                    "fim_promocao" -> "Pronto: ${p.nome} saiu da promoção e voltou a custar ${dinheiro(corpo.optInt("priceCents"))}."
                    "ativar" -> "Pronto: ${p.nome} voltou a aparecer no site."
                    else -> "Pronto: ${p.nome} foi escondido do site."
                }
            }
            "entregue" -> {
                val o = a.pedido!!
                enviar("/_api/admin/order_update", JSONObject().put("id", o.id).put("status", "delivered").put("trackingCode", o.rastreio ?: JSONObject.NULL))
                return "Pronto: o pedido ${o.id} está marcado como entregue."
            }
            "status" -> {
                val o = a.pedido!!
                val rastreio = a.rastreio ?: o.rastreio
                enviar("/_api/admin/order_update", JSONObject().put("id", o.id).put("status", a.status!!).put("trackingCode", rastreio ?: JSONObject.NULL))
                return "Pronto: o pedido ${o.id} está ${NOME_STATUS[a.status] ?: a.status}" + (if (a.rastreio != null) ", com o rastreio ${a.rastreio}." else ".")
            }
        }
        throw ErroDaLoja("Não sei fazer essa alteração.")
    }

    // ------------------------------------------------------------- criptografia
    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gerador.generateKey()
    }

    private fun cifrar(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave())
        val dados = c.doFinal(texto.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(dados, Base64.NO_WRAP)
    }

    private fun decifrar(guardado: String): String? = try {
        val (iv, dados) = guardado.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, iv))
        String(c.doFinal(dados))
    } catch (e: Exception) {
        null
    }

    // ================================================================ parte testável
    data class Item(val nome: String, val quantidade: Int, val precoCentavos: Int)
    data class Pedido(
        val id: Int, val status: String, val totalCentavos: Int, val cliente: String,
        val entrega: String, val criado: Long, val pago: Long?, val itens: List<Item>,
        val telefone: String = "", val rastreio: String? = null,
    )
    data class Produto(
        val id: Int, val nome: String, val ativo: Boolean, val estoque: Int?, val precoCentavos: Int?,
        val precoAntigo: Int? = null, val bruto: JSONObject? = null,
    )

    /** Uma mudança esperando o "sim". [valor]: quantidade final do estoque ou preço em centavos. */
    data class Alteracao(
        val tipo: String, val produto: Produto? = null, val pedido: Pedido? = null, val valor: Int? = null,
        val status: String? = null, val rastreio: String? = null,
    )

    /** O que falar e, se der para seguir, a alteração que aguarda confirmação. */
    data class Preparo(val texto: String, val alteracao: Alteracao?)

    companion object {
        const val SITE = "https://desejo-de-intimidade.floot.app"
        private const val COOKIE = "floot_built_app_session"
        private const val ALIAS = "venus_loja"
        private const val P_EMAIL = "email"
        private const val P_SENHA = "senha"
        private const val P_COOKIE = "cookie"
        private const val SEM_CONTA = "A loja ainda não está conectada. Conecte nos ajustes: Conectar à loja."

        /** Igual ao site: com 2 unidades ou menos o produto está acabando. */
        const val ESTOQUE_BAIXO = 2

        private val BR = Locale("pt", "BR")
        private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")
        private val STATUS = mapOf(
            "awaiting_payment" to "aguardando pagamento", "paid" to "pago", "preparing" to "em separação",
            "shipped" to "enviado", "delivered" to "entregue", "canceled" to "cancelado",
        )

        /** O site responde no formato superjson: {"json": {...}, "meta": {...}}. */
        fun dados(texto: String): JSONObject = try {
            val raiz = JSONObject(texto)
            raiz.optJSONObject("json") ?: raiz
        } catch (e: Exception) {
            JSONObject()
        }

        fun dinheiro(centavos: Int): String =
            NumberFormat.getCurrencyInstance(BR).format(centavos / 100.0).replace(' ', ' ')

        private fun data(iso: String?): Long? {
            if (iso.isNullOrBlank() || iso == "null") return null
            val formatos = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
            for (f in formatos) {
                try {
                    return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)?.time
                } catch (e: Exception) {
                }
            }
            return null
        }

        fun lerPedidos(lista: JSONArray): List<Pedido> = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }.map { o ->
            val itens = o.optJSONArray("items") ?: JSONArray()
            Pedido(
                id = o.optInt("id"),
                status = o.optString("status"),
                totalCentavos = o.optInt("totalCents"),
                cliente = o.optString("customerName"),
                entrega = o.optString("deliveryMethod"),
                criado = data(o.optString("createdAt")) ?: 0L,
                pago = data(o.optString("paidAt")),
                itens = (0 until itens.length()).mapNotNull { itens.optJSONObject(it) }.map { i ->
                    Item(i.optString("name"), i.optInt("quantity", 1), i.optInt("unitPriceCents"))
                },
                telefone = o.optString("customerPhone"),
                rastreio = if (o.isNull("trackingCode")) null else o.optString("trackingCode").ifBlank { null },
            )
        }

        fun lerProdutos(lista: JSONArray): List<Produto> = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }.map { p ->
            Produto(
                id = p.optInt("id"),
                nome = p.optString("name"),
                ativo = p.optBoolean("active", true),
                estoque = if (p.isNull("stockQuantity")) null else p.optInt("stockQuantity"),
                precoCentavos = if (p.isNull("priceCents")) null else p.optInt("priceCents"),
                precoAntigo = if (p.isNull("compareAtCents")) null else p.optInt("compareAtCents"),
                bruto = p,
            )
        }

        /** Início do período: "hoje", "ontem", "semana" (7 dias), "mes" (mês atual). */
        fun inicioDoPeriodo(periodo: String, agora: Calendar): Pair<Long, Long> {
            fun zero(c: Calendar) = c.apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val hoje = zero(agora.clone() as Calendar).timeInMillis
            val dia = 24L * 60 * 60 * 1000
            return when (periodo) {
                "ontem" -> (hoje - dia) to hoje
                "semana" -> (hoje - 6 * dia) to Long.MAX_VALUE
                "mes" -> zero((agora.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }).timeInMillis to Long.MAX_VALUE
                else -> hoje to Long.MAX_VALUE
            }
        }

        private fun nomeDoPeriodo(periodo: String) = when (periodo) {
            "ontem" -> "Ontem"
            "semana" -> "Nos últimos 7 dias"
            "mes" -> "Neste mês"
            else -> "Hoje"
        }

        fun montarResumo(pedidos: List<Pedido>, periodo: String, agora: Calendar): String {
            val (ini, fim) = inicioDoPeriodo(periodo, agora)
            fun noPeriodo(t: Long) = t in ini until fim
            val pagos = pedidos.filter { it.status in PAGOS && noPeriodo(it.pago ?: it.criado) }
            val aguardando = pedidos.filter { it.status == "awaiting_payment" && noPeriodo(it.criado) }
            val quando = nomeDoPeriodo(periodo)
            if (pagos.isEmpty()) {
                val extra = if (aguardando.isNotEmpty()) " ${plural(aguardando.size, "pedido aguardando", "pedidos aguardando")} pagamento." else ""
                return "$quando ainda não teve venda paga.$extra".replace("Ontem ainda não teve", "Ontem não teve")
            }
            val total = pagos.sumOf { it.totalCentavos }
            val maisVendido = pagos.flatMap { it.itens }.groupBy { it.nome }
                .mapValues { (_, v) -> v.sumOf { it.quantidade } }.maxByOrNull { it.value }
            var texto = "$quando: ${plural(pagos.size, "venda paga", "vendas pagas")}, total de ${dinheiro(total)}."
            if (pagos.size > 1) texto += " Ticket médio de ${dinheiro(total / pagos.size)}."
            if (maisVendido != null && pagos.size > 1) texto += " Mais vendido: ${maisVendido.key} (${maisVendido.value} un.)."
            if (aguardando.isNotEmpty()) texto += " E ${plural(aguardando.size, "pedido aguardando", "pedidos aguardando")} pagamento."
            return texto
        }

        fun montarPendentes(pedidos: List<Pedido>): String {
            val separar = pedidos.filter { it.status == "paid" || it.status == "preparing" }.sortedBy { it.id }
            val aguardando = pedidos.count { it.status == "awaiting_payment" }
            if (separar.isEmpty()) {
                return "Nenhum pedido para separar agora." +
                    if (aguardando > 0) " Há ${plural(aguardando, "pedido aguardando", "pedidos aguardando")} pagamento." else ""
            }
            val linhas = separar.take(5).joinToString(" ") { p ->
                val itens = p.itens.sumOf { it.quantidade }
                val primeiroNome = p.cliente.trim().split(" ").firstOrNull().orEmpty()
                val entrega = if (p.entrega == "pickup") "retirada" else "envio"
                "Pedido ${p.id}${if (primeiroNome.isNotBlank()) " de $primeiroNome" else ""}: ${plural(itens, "item", "itens")}, " +
                    "${dinheiro(p.totalCentavos)}, ${STATUS[p.status] ?: p.status}, $entrega."
            }
            val mais = if (separar.size > 5) " E mais ${separar.size - 5}." else ""
            return "Você tem ${plural(separar.size, "pedido para separar", "pedidos para separar")}. $linhas$mais"
        }

        /** Acha o produto pelo nome falado (palavras em comum, sem acento). */
        fun acharProdutos(produtos: List<Produto>, falado: String): List<Produto> {
            val chaves = Corretor.palavras(falado).filter { it.length >= 3 && it !in setOf("estoque", "unidades", "produto", "tem", "quantos", "quantas") }
            if (chaves.isEmpty()) return emptyList()
            val notas = produtos.map { p ->
                val nome = ComandosLocais.normalizar(p.nome)
                p to chaves.count { k -> Regex("\\b${Regex.escape(Pesquisa.raiz(k))}").containsMatchIn(nome) }
            }
            val melhor = notas.maxOfOrNull { it.second } ?: 0
            if (melhor == 0) return emptyList()
            return notas.filter { it.second == melhor }.map { it.first }
        }

        fun montarEstoque(produtos: List<Produto>, falado: String): String {
            val achados = acharProdutos(produtos, falado)
            if (achados.isEmpty()) return "Não achei nenhum produto com \"$falado\" na loja."
            fun linha(p: Produto) = when {
                p.estoque == null -> "${p.nome}: sem controle de estoque"
                p.estoque <= 0 -> "${p.nome}: esgotado"
                else -> "${p.nome}: ${plural(p.estoque, "unidade", "unidades")}"
            } + if (!p.ativo) " (escondido do site)" else ""
            if (achados.size == 1) return linha(achados[0]) + "."
            val lista = achados.take(4).joinToString("; ") { linha(it) }
            val mais = if (achados.size > 4) " E mais ${achados.size - 4} parecidos." else ""
            return "Achei ${achados.size} produtos: $lista.$mais"
        }

        fun montarAcabando(produtos: List<Produto>): String {
            val ativos = produtos.filter { it.ativo && it.estoque != null }
            val esgotados = ativos.filter { it.estoque!! <= 0 }
            val baixos = ativos.filter { it.estoque!! in 1..ESTOQUE_BAIXO }
            if (esgotados.isEmpty() && baixos.isEmpty()) return "Nenhum produto acabando. O estoque está em dia."
            val partes = mutableListOf<String>()
            if (esgotados.isNotEmpty()) partes += "Esgotados: " + esgotados.take(6).joinToString(", ") { it.nome } +
                if (esgotados.size > 6) " e mais ${esgotados.size - 6}" else ""
            if (baixos.isNotEmpty()) partes += "Acabando: " + baixos.take(6).joinToString(", ") { "${it.nome} (${it.estoque})" } +
                if (baixos.size > 6) " e mais ${baixos.size - 6}" else ""
            return partes.joinToString(". ") + "."
        }

        /** Visão geral: quantos produtos, quantas unidades, o que está esgotado ou acabando. */
        fun montarEstoqueGeral(produtos: List<Produto>): String {
            if (produtos.isEmpty()) return "Ainda não há produtos cadastrados na loja."
            val ativos = produtos.filter { it.ativo }
            val escondidos = produtos.size - ativos.size
            val controlados = ativos.filter { it.estoque != null }
            val unidades = controlados.sumOf { maxOf(0, it.estoque!!) }
            val valor = controlados.sumOf { maxOf(0, it.estoque!!) * (it.precoCentavos ?: 0) }
            val esgotados = controlados.filter { it.estoque!! <= 0 }
            val baixos = controlados.filter { it.estoque!! in 1..ESTOQUE_BAIXO }
            val semControle = ativos.size - controlados.size

            var texto = "Você tem ${plural(ativos.size, "produto", "produtos")} no site"
            texto += if (escondidos > 0) " e ${plural(escondidos, "escondido", "escondidos")}." else "."
            if (controlados.isNotEmpty()) {
                texto += " No estoque são ${plural(unidades, "unidade", "unidades")}"
                texto += if (valor > 0) ", que valem ${dinheiro(valor)} pelo preço de venda." else "."
            }
            texto += when {
                esgotados.isEmpty() && baixos.isEmpty() -> " Nada esgotado nem acabando."
                else -> {
                    val partes = mutableListOf<String>()
                    if (esgotados.isNotEmpty()) partes += "${plural(esgotados.size, "esgotado", "esgotados")}: " +
                        esgotados.take(4).joinToString(", ") { it.nome } + if (esgotados.size > 4) " e mais ${esgotados.size - 4}" else ""
                    if (baixos.isNotEmpty()) partes += "${plural(baixos.size, "acabando", "acabando")}: " +
                        baixos.take(4).joinToString(", ") { "${it.nome} (${it.estoque})" } + if (baixos.size > 4) " e mais ${baixos.size - 4}" else ""
                    " " + partes.joinToString(". ").replaceFirstChar { it.uppercase() } + "."
                }
            }
            if (semControle > 0) texto += " ${plural(semControle, "produto está", "produtos estão")} sem controle de estoque."
            return texto
        }

        private const val CONFIRMA = " Diga sim para confirmar ou não para cancelar."

        /** Campos que o painel exige para salvar um produto (o site troca o produto inteiro). */
        private val CAMPOS_PRODUTO = listOf(
            "id", "category", "name", "description", "priceCents", "compareAtCents", "specifications", "stockQuantity",
            "weightGrams", "widthCm", "heightCm", "lengthCm", "imageUrl", "active",
        )

        /**
         * Monta o corpo do product_save a partir do produto como veio do site, mudando só preço ou ativo.
         * Se faltar algum campo, devolve null: melhor não salvar do que apagar algo sem querer.
         */
        fun corpoDoProduto(bruto: JSONObject?, tipo: String, valor: Int?): JSONObject? {
            if (bruto == null || CAMPOS_PRODUTO.any { !bruto.has(it) }) return null
            val corpo = JSONObject()
            for (k in CAMPOS_PRODUTO) corpo.put(k, bruto.get(k))
            when (tipo) {
                "preco" -> corpo.put("priceCents", valor ?: return null)
                "promocao" -> {
                    val novo = valor ?: return null
                    val preco = if (bruto.isNull("priceCents")) return null else bruto.getInt("priceCents")
                    val antigo = if (bruto.isNull("compareAtCents")) null else bruto.getInt("compareAtCents")
                    // o preço "de" é o preço cheio: o antigo, se já estava em promoção, ou o atual
                    val cheio = if (antigo != null && antigo > preco) antigo else preco
                    if (novo >= cheio) return null
                    corpo.put("priceCents", novo).put("compareAtCents", cheio)
                }
                "fim_promocao" -> {
                    val preco = if (bruto.isNull("priceCents")) return null else bruto.getInt("priceCents")
                    val antigo = if (bruto.isNull("compareAtCents")) return null else bruto.getInt("compareAtCents")
                    if (antigo <= preco) return null
                    corpo.put("priceCents", antigo).put("compareAtCents", JSONObject.NULL)
                }
                "ativar" -> corpo.put("active", true)
                "desativar" -> corpo.put("active", false)
                else -> return null
            }
            return corpo
        }

        fun montarAlteracao(produtos: List<Produto>, tipo: String, falado: String, valor: Int?): Preparo {
            val achados = acharProdutos(produtos, falado)
            if (achados.isEmpty()) return Preparo("Não achei nenhum produto com \"$falado\" na loja. Nada foi mudado.", null)
            if (achados.size > 1) {
                val nomes = achados.take(4).joinToString(", ") { it.nome }
                return Preparo("Achei ${achados.size} produtos parecidos: $nomes. Repita com o nome mais completo, para eu não mudar o errado.", null)
            }
            val p = achados[0]
            val atual = p.estoque
            return when (tipo) {
                "estoque_somar", "estoque_tirar" -> {
                    val n = valor ?: return Preparo("Quantas unidades?", null)
                    if (n <= 0) return Preparo("Me diga uma quantidade maior que zero.", null)
                    if (atual == null) return Preparo(
                        "${p.nome} está sem controle de estoque. Para começar a controlar, diga: o estoque de ${p.nome} é, e a quantidade.", null
                    )
                    if (tipo == "estoque_tirar" && n > atual) {
                        return Preparo("${p.nome} só tem ${plural(atual, "unidade", "unidades")}, não dá para tirar $n. Nada foi mudado.", null)
                    }
                    val novo = if (tipo == "estoque_somar") atual + n else atual - n
                    val verbo = if (tipo == "estoque_somar") "Somar ${plural(n, "unidade", "unidades")} ao estoque de" else "Tirar ${plural(n, "unidade", "unidades")} do estoque de"
                    Preparo("$verbo ${p.nome}? Fica com $novo (hoje tem $atual).$CONFIRMA", Alteracao("estoque_definir", p, valor = novo))
                }
                "estoque_definir" -> {
                    val n = valor ?: return Preparo("Qual a quantidade?", null)
                    if (atual == n) return Preparo("${p.nome} já está com ${plural(n, "unidade", "unidades")}. Nada para mudar.", null)
                    val de = if (atual == null) "(hoje sem controle de estoque)" else "de $atual"
                    Preparo("Mudar o estoque de ${p.nome} $de para ${plural(n, "unidade", "unidades")}?$CONFIRMA", Alteracao("estoque_definir", p, valor = n))
                }
                "preco" -> {
                    val novo = valor ?: return Preparo("Qual o novo preço?", null)
                    if (novo <= 0) return Preparo("O preço precisa ser maior que zero.", null)
                    if (corpoDoProduto(p.bruto, "preco", novo) == null) {
                        return Preparo("Não consegui ler todos os dados de ${p.nome}. Mude o preço pelo painel do site.", null)
                    }
                    val antes = p.precoCentavos
                    if (antes == novo) return Preparo("${p.nome} já custa ${dinheiro(novo)}.", null)
                    var texto = if (antes == null) "Definir o preço de ${p.nome} em ${dinheiro(novo)}?"
                    else "Mudar o preço de ${p.nome} de ${dinheiro(antes)} para ${dinheiro(novo)}?"
                    if (antes != null && (novo * 2 < antes || novo > antes * 2)) texto += " É bem diferente do preço atual, confira."
                    val promo = p.precoAntigo
                    if (promo != null && antes != null && promo > antes && novo >= promo) {
                        texto += " Ele está em promoção (de ${dinheiro(promo)}); com esse preço a promoção deixa de aparecer."
                    }
                    Preparo(texto + CONFIRMA, Alteracao("preco", p, valor = novo))
                }
                "promocao", "promocao_pct" -> {
                    val preco = p.precoCentavos ?: return Preparo("${p.nome} está sem preço. Defina o preço antes da promoção.", null)
                    val antigo = p.precoAntigo
                    val emPromocao = antigo != null && antigo > preco
                    val cheio = if (emPromocao) antigo!! else preco
                    val novo = if (tipo == "promocao_pct") {
                        val pct = valor ?: return Preparo("De quantos por cento é o desconto?", null)
                        if (pct !in 1..90) return Preparo("O desconto precisa ser entre 1 e 90 por cento.", null)
                        ((cheio.toLong() * (100 - pct) + 50) / 100).toInt()
                    } else valor ?: return Preparo("Por quanto fica na promoção?", null)
                    if (novo <= 0) return Preparo("O preço da promoção precisa ser maior que zero.", null)
                    if (novo >= cheio) return Preparo(
                        "Na promoção o preço precisa ficar abaixo de ${dinheiro(cheio)}, que é o preço cheio de ${p.nome}. Nada foi mudado.", null
                    )
                    if (corpoDoProduto(p.bruto, "promocao", novo) == null) {
                        return Preparo("Não consegui ler todos os dados de ${p.nome}. Faça a promoção pelo painel do site.", null)
                    }
                    val off = ((cheio - novo) * 100 + cheio / 2) / cheio
                    var texto = if (emPromocao) "${p.nome} já está em promoção por ${dinheiro(preco)}. Mudar para de ${dinheiro(cheio)} por ${dinheiro(novo)} ($off% a menos)?"
                    else "Colocar ${p.nome} em promoção: de ${dinheiro(cheio)} por ${dinheiro(novo)} ($off% a menos)?"
                    texto += " No site aparece o preço antigo riscado. Produto em promoção não tem o desconto extra do Pix."
                    if (off >= 50) texto += " É um desconto grande, confira."
                    Preparo(texto + CONFIRMA, Alteracao("promocao", p, valor = novo))
                }
                "fim_promocao" -> {
                    val preco = p.precoCentavos
                    val antigo = p.precoAntigo
                    if (preco == null || antigo == null || antigo <= preco) return Preparo("${p.nome} não está em promoção.", null)
                    if (corpoDoProduto(p.bruto, "fim_promocao", null) == null) {
                        return Preparo("Não consegui ler todos os dados de ${p.nome}. Tire a promoção pelo painel do site.", null)
                    }
                    Preparo("Tirar ${p.nome} da promoção? Ele volta a custar ${dinheiro(antigo)} (hoje está ${dinheiro(preco)}).$CONFIRMA", Alteracao("fim_promocao", p))
                }
                "desativar" -> {
                    if (!p.ativo) return Preparo("${p.nome} já está escondido do site.", null)
                    if (corpoDoProduto(p.bruto, "desativar", null) == null) {
                        return Preparo("Não consegui ler todos os dados de ${p.nome}. Esconda pelo painel do site.", null)
                    }
                    Preparo("Esconder ${p.nome} do site? Ele deixa de aparecer para os clientes.$CONFIRMA", Alteracao("desativar", p))
                }
                "ativar" -> {
                    if (p.ativo) return Preparo("${p.nome} já está aparecendo no site.", null)
                    if (corpoDoProduto(p.bruto, "ativar", null) == null) {
                        return Preparo("Não consegui ler todos os dados de ${p.nome}. Ative pelo painel do site.", null)
                    }
                    val aviso = when {
                        p.precoCentavos == null -> " Atenção: ele está sem preço."
                        p.estoque != null && p.estoque <= 0 -> " Atenção: ele está sem estoque e vai aparecer como esgotado."
                        else -> ""
                    }
                    Preparo("Mostrar ${p.nome} no site de novo?$aviso$CONFIRMA", Alteracao("ativar", p))
                }
                else -> Preparo("Não entendi o que mudar.", null)
            }
        }

        /** Como o status aparece nas frases: "o pedido 15 está enviado". */
        private val NOME_STATUS = mapOf(
            "paid" to "como pago", "preparing" to "em separação", "shipped" to "como enviado", "delivered" to "como entregue",
        )

        /** Mudar o status de um pedido (pago, em separação, enviado com rastreio). Entregue usa [montarEntregue]. */
        fun montarStatus(pedidos: List<Pedido>, id: Int, status: String, rastreio: String?): Preparo {
            if (status == "delivered") return montarEntregue(pedidos, id)
            val o = pedidos.firstOrNull { it.id == id } ?: return Preparo("Não achei o pedido $id.", null)
            val codigo = rastreio?.uppercase()?.replace(Regex("[^A-Z0-9]"), "")?.takeIf { it.length in 8..40 }
            if (rastreio != null && codigo == null) {
                return Preparo("Não entendi o código de rastreio. Escreva ele aqui, por exemplo: pedido $id enviado, rastreio AB123456789BR.", null)
            }
            when (o.status) {
                "canceled" -> return Preparo("O pedido $id está cancelado. Nada foi mudado.", null)
                "delivered" -> return Preparo("O pedido $id já foi entregue. Nada foi mudado.", null)
                "awaiting_payment" -> if (status != "paid") return Preparo(
                    "O pedido $id ainda está aguardando pagamento. Se a cliente pagou por fora, diga primeiro: marca o pedido $id como pago.", null
                )
            }
            val ordem = listOf("awaiting_payment", "paid", "preparing", "shipped", "delivered")
            if (o.status == status && (status != "shipped" || codigo == null || codigo == o.rastreio)) {
                return Preparo("O pedido $id já está ${NOME_STATUS[status] ?: status}.", null)
            }
            val voltando = ordem.indexOf(status) < ordem.indexOf(o.status)
            val nome = o.cliente.trim().split(" ").firstOrNull().orEmpty()
            val de = if (nome.isNotBlank()) " de $nome" else ""
            var texto = when (status) {
                "paid" -> "Marcar o pedido $id$de (${dinheiro(o.totalCentavos)}) como pago?"
                "preparing" -> "Marcar o pedido $id$de como em separação?"
                "shipped" -> if (codigo != null) "Marcar o pedido $id$de como enviado, com o rastreio $codigo?"
                else "Marcar o pedido $id$de como enviado, sem código de rastreio?"
                else -> return Preparo("Não sei mudar para esse status.", null)
            }
            if (voltando) texto += " Atenção: ele está ${STATUS[o.status]}, isso volta uma etapa."
            if (status == "shipped" && codigo == null && o.entrega != "pickup" && o.rastreio == null) {
                texto += " Depois dá para dizer: rastreio do pedido $id é, e o código."
            }
            if (o.telefone.replace(Regex("\\D"), "").length >= 10) texto += " Depois eu abro o WhatsApp da cliente com o aviso pronto."
            return Preparo(texto + CONFIRMA, Alteracao("status", pedido = o, status = status, rastreio = codigo))
        }

        /** Link do WhatsApp da cliente com o aviso da nova etapa do pedido (null se não houver telefone). */
        fun linkAviso(a: Alteracao): String? {
            val p = a.pedido ?: return null
            val status = if (a.tipo == "entregue") "delivered" else a.status ?: return null
            val tel = p.telefone.replace(Regex("\\D"), "")
            if (tel.length < 10) return null
            val numero = if (tel.startsWith("55") && tel.length > 11) tel else "55$tel"
            val nome = p.cliente.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            val ola = "Olá${if (nome.isNotBlank()) ", $nome" else ""}!"
            val link = "$SITE/pedido/${p.id}"
            val rastreio = a.rastreio ?: p.rastreio
            val texto = when (status) {
                "paid" -> "$ola Recebemos o pagamento do seu pedido #${p.id} (${dinheiro(p.totalCentavos)}). Já estamos separando tudo com muito cuidado e discrição. 💖\nAcompanhe aqui: $link"
                "preparing" -> "$ola Seu pedido #${p.id} já está sendo separado, com embalagem neutra e discreta. Em breve ele segue para " +
                    (if (p.entrega == "pickup") "a retirada." else "o envio.") + " 💖\nAcompanhe aqui: $link"
                "shipped" -> "$ola Seu pedido #${p.id} foi enviado! 📦\n" +
                    (if (rastreio != null) "Código de rastreio: $rastreio\n" else "Assim que o código de rastreio sair, ele aparece na página do pedido.\n") +
                    "Acompanhe aqui: $link"
                "delivered" -> "$ola Seu pedido #${p.id} foi entregue. Esperamos que você ame! 💖\n" +
                    "Conta pra gente o que achou do produto e do atendimento (leva 1 minuto): $SITE/avaliar/${p.id}"
                else -> return null
            }
            return "https://wa.me/$numero?text=" + java.net.URLEncoder.encode(texto, "UTF-8").replace("+", "%20")
        }

        fun montarEntregue(pedidos: List<Pedido>, id: Int): Preparo {
            val o = pedidos.firstOrNull { it.id == id } ?: return Preparo("Não achei o pedido $id.", null)
            return when (o.status) {
                "delivered" -> Preparo("O pedido $id já está como entregue.", null)
                "canceled" -> Preparo("O pedido $id está cancelado. Nada foi mudado.", null)
                "awaiting_payment" -> Preparo("O pedido $id ainda está aguardando pagamento. Nada foi mudado.", null)
                else -> {
                    val nome = o.cliente.trim().split(" ").firstOrNull().orEmpty()
                    val entrega = if (o.entrega == "pickup") "retirada" else "envio"
                    Preparo(
                        "Marcar o pedido $id${if (nome.isNotBlank()) " de $nome" else ""} ($entrega, ${dinheiro(o.totalCentavos)}) como entregue?$CONFIRMA",
                        Alteracao("entregue", pedido = o),
                    )
                }
            }
        }

        private fun vendidosNoIntervalo(pedidos: List<Pedido>, ini: Long, fim: Long) =
            pedidos.filter { it.status in PAGOS && (it.pago ?: it.criado) in ini until fim }

        private fun nomeDoPeriodoMinusculo(periodo: String) = when (periodo) {
            "hoje" -> "hoje"
            "ontem" -> "ontem"
            "semana" -> "nos últimos 7 dias"
            else -> "neste mês"
        }

        /** Os campeões de venda do período (padrão: mês), por unidades. */
        fun montarMaisVendidos(pedidos: List<Pedido>, periodo: String, agora: Calendar): String {
            val (ini, fim) = inicioDoPeriodo(periodo, agora)
            val pagos = vendidosNoIntervalo(pedidos, ini, fim)
            val quando = nomeDoPeriodoMinusculo(periodo)
            if (pagos.isEmpty()) return "Ainda não teve venda paga $quando."
            val ranking = pagos.flatMap { it.itens }.groupBy { it.nome.trim() }
                .map { (nome, itens) -> Triple(nome, itens.sumOf { it.quantidade }, itens.sumOf { it.quantidade * it.precoCentavos }) }
                .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            val linhas = ranking.take(5).mapIndexed { i, (nome, qtd, valor) ->
                "${i + 1}. $nome: ${plural(qtd, "unidade", "unidades")}, ${dinheiro(valor)}"
            }.joinToString("\n")
            return "Mais vendidos $quando:\n$linhas"
        }

        /** Este mês até hoje comparado com o mesmo período do mês passado. */
        fun montarComparacao(pedidos: List<Pedido>, agora: Calendar): String {
            fun zero(c: Calendar) = c.apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val inicioMes = zero((agora.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) })
            val inicioPassado = (inicioMes.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            val dia = agora.get(Calendar.DAY_OF_MONTH)
            // mesmo ponto do mês passado (dia de hoje, ou o último dia se o mês passado for mais curto)
            val mesmoPonto = (inicioPassado.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, minOf(dia, getActualMaximum(Calendar.DAY_OF_MONTH)))
                set(Calendar.HOUR_OF_DAY, agora.get(Calendar.HOUR_OF_DAY)); set(Calendar.MINUTE, agora.get(Calendar.MINUTE))
            }
            val agoraMs = agora.timeInMillis
            val este = vendidosNoIntervalo(pedidos, inicioMes.timeInMillis, agoraMs + 1)
            val passadoAteAqui = vendidosNoIntervalo(pedidos, inicioPassado.timeInMillis, mesmoPonto.timeInMillis + 1)
            val passadoTodo = vendidosNoIntervalo(pedidos, inicioPassado.timeInMillis, inicioMes.timeInMillis)
            val total = este.sumOf { it.totalCentavos }
            val antes = passadoAteAqui.sumOf { it.totalCentavos }
            var texto = "Neste mês até agora: ${plural(este.size, "venda", "vendas")}, ${dinheiro(total)}. " +
                "No mesmo período do mês passado: ${plural(passadoAteAqui.size, "venda", "vendas")}, ${dinheiro(antes)}."
            texto += when {
                antes == 0 && total == 0 -> ""
                antes == 0 -> " Melhor que o mês passado, que ainda não tinha vendas a essa altura."
                else -> {
                    val pct = ((total - antes) * 100L / antes).toInt()
                    when {
                        pct > 0 -> " Está $pct% acima. 📈"
                        pct < 0 -> " Está ${-pct}% abaixo."
                        else -> " Está igual."
                    }
                }
            }
            if (passadoTodo.isNotEmpty()) texto += " O mês passado inteiro fechou em ${dinheiro(passadoTodo.sumOf { it.totalCentavos })}."
            return texto
        }

        /** Produtos à venda, com estoque, que não vendem há [dias] dias ou mais. */
        fun montarParados(pedidos: List<Pedido>, produtos: List<Produto>, agora: Calendar, dias: Int = 30): String {
            val desde = agora.timeInMillis - dias * 24L * 3600_000
            val vendidos = vendidosNoIntervalo(pedidos, desde, Long.MAX_VALUE).flatMap { it.itens }
                .map { ComandosLocais.normalizar(it.nome.trim()) }.toSet()
            val parados = produtos.filter { it.ativo && (it.estoque == null || it.estoque > 0) && ComandosLocais.normalizar(it.nome.trim()) !in vendidos }
            if (parados.isEmpty()) return "Todos os produtos à venda tiveram saída nos últimos $dias dias. 👏"
            val lista = parados.sortedByDescending { (it.estoque ?: 0) * (it.precoCentavos ?: 0) }
            val linhas = lista.take(8).joinToString("\n") { p ->
                "• ${p.nome}" + (if (p.estoque != null) " (${p.estoque} em estoque)" else "")
            }
            val mais = if (lista.size > 8) "\nE mais ${lista.size - 8}." else ""
            return "${plural(parados.size, "produto sem venda", "produtos sem venda")} nos últimos $dias dias:\n$linhas$mais\n" +
                "Dá para colocar em promoção, por exemplo: dá 10% de desconto no, e o nome do produto."
        }

        /** Resumo da noite: como foi o dia e o que fica para amanhã. */
        fun montarResumoDoDia(pedidos: List<Pedido>, produtos: List<Produto>, agora: Calendar): String {
            val dia = montarResumo(pedidos, "hoje", agora)
            val separar = pedidos.count { it.status == "paid" || it.status == "preparing" }
            val retirar = pedidos.count { it.entrega == "pickup" && it.status in setOf("paid", "preparing", "shipped") }
            val ativos = produtos.filter { it.ativo && it.estoque != null }
            val esgotados = ativos.count { it.estoque!! <= 0 }
            val baixos = ativos.count { it.estoque!! in 1..ESTOQUE_BAIXO }
            val tarefas = mutableListOf<String>()
            if (separar > 0) tarefas += plural(separar, "pedido para separar", "pedidos para separar")
            if (retirar > 0) tarefas += plural(retirar, "retirada para agendar", "retiradas para agendar")
            if (esgotados > 0) tarefas += plural(esgotados, "produto esgotado", "produtos esgotados")
            if (baixos > 0) tarefas += plural(baixos, "produto acabando", "produtos acabando")
            return dia + if (tarefas.isEmpty()) " Nada pendente para amanhã." else " Para amanhã: ${tarefas.joinToString(", ")}."
        }

        /** O que foi vendido no período, com quantas unidades restam de cada um: a lista para repor. */
        fun montarReposicao(pedidos: List<Pedido>, produtos: List<Produto>, periodo: String, agora: Calendar): String {
            val (ini, fim) = inicioDoPeriodo(periodo, agora)
            val pagos = pedidos.filter { it.status in PAGOS && (it.pago ?: it.criado) in ini until fim }
            val quando = when (periodo) {
                "hoje" -> "hoje"
                "ontem" -> "ontem"
                "mes" -> "neste mês"
                else -> "nos últimos 7 dias"
            }
            if (pagos.isEmpty()) return "Nada vendido $quando, então nada para repor."
            val vendidos = pagos.flatMap { it.itens }.groupBy { it.nome.trim() }
                .mapValues { (_, v) -> v.sumOf { it.quantidade } }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            val porNome = produtos.associateBy { ComandosLocais.normalizar(it.nome.trim()) }
            val total = vendidos.sumOf { it.value }
            val linhas = vendidos.take(12).joinToString("\n") { (nome, qtd) ->
                val p = porNome[ComandosLocais.normalizar(nome)]
                val resta = when {
                    p == null || p.estoque == null -> ""
                    p.estoque <= 0 -> " (esgotado)"
                    else -> " (restam ${p.estoque})"
                }
                "• $nome: $qtd$resta"
            }
            val mais = if (vendidos.size > 12) "\nE mais ${vendidos.size - 12} produtos." else ""
            return "Vendidos $quando: ${plural(total, "item", "itens")} em ${plural(pagos.size, "pedido", "pedidos")}.\n$linhas$mais"
        }

        /** Pedidos pagos para retirar que ainda não foram entregues. */
        fun montarRetiradas(pedidos: List<Pedido>): String {
            val lista = pedidos.filter { it.entrega == "pickup" && it.status in setOf("paid", "preparing", "shipped") }.sortedBy { it.id }
            val aguardando = pedidos.count { it.entrega == "pickup" && it.status == "awaiting_payment" }
            if (lista.isEmpty()) {
                return "Nenhuma retirada para combinar agora." +
                    if (aguardando > 0) " Há ${plural(aguardando, "pedido de retirada aguardando", "pedidos de retirada aguardando")} pagamento." else ""
            }
            val linhas = lista.take(5).joinToString(" ") { p ->
                val nome = p.cliente.trim().split(" ").firstOrNull().orEmpty()
                val itens = p.itens.sumOf { it.quantidade }
                "Pedido ${p.id}${if (nome.isNotBlank()) " de $nome" else ""}: ${plural(itens, "item", "itens")}, ${dinheiro(p.totalCentavos)}, ${STATUS[p.status] ?: p.status}."
            }
            val mais = if (lista.size > 5) " E mais ${lista.size - 5}." else ""
            return "Você tem ${plural(lista.size, "retirada para combinar", "retiradas para combinar")}. $linhas$mais " +
                "Para chamar o cliente, diga: agendar retirada do pedido, e o número."
        }

        /** Link do WhatsApp do cliente com a mensagem de agendamento pronta (null se não houver telefone). */
        fun linkAgendar(p: Pedido): String? {
            val tel = p.telefone.replace(Regex("\\D"), "")
            if (tel.length < 10) return null
            val numero = if (tel.startsWith("55") && tel.length > 11) tel else "55$tel"
            val nome = p.cliente.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            val texto = "Olá${if (nome.isNotBlank()) ", $nome" else ""}! Aqui é da Desejo de Intimidade. " +
                "Seu pedido #${p.id} já está separado para retirada em Porto Alegre. " +
                "Qual dia e horário ficam melhor para você? Assim que combinarmos, te passo o local certinho. 💖"
            return "https://wa.me/$numero?text=" + java.net.URLEncoder.encode(texto, "UTF-8").replace("+", "%20")
        }

        /** "sim", "pode", "confirma" → true; "não", "cancela" → false; outra coisa → null. */
        fun simOuNao(fala: String): Boolean? {
            val c = ComandosLocais.normalizar(fala).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (Regex("\\b(?:nao|cancela|cancelar|cancele|esquece|negativo)\\b").containsMatchIn(c) ||
                Regex("^(?:no|para|pare|deixa|deixa pra la|nem|errado)\\b").containsMatchIn(c)) return false
            if (Regex("^(?:sim|s|pode|pode sim|pode ser|confirma|confirmo|confirmar|isso|isso mesmo|ok|okay|beleza|claro|com certeza|certo|manda|faz|faca|bora|exato|positivo|correto|ta|ta bom|esta bom|aham|uhum|quero)\\b").containsMatchIn(c)) return true
            return null
        }

        private fun plural(n: Int, um: String, varios: String) = "$n ${if (n == 1) um else varios}"
    }
}
