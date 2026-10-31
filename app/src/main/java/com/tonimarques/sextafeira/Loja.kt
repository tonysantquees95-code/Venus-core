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
    )
    data class Produto(val id: Int, val nome: String, val ativo: Boolean, val estoque: Int?, val precoCentavos: Int?)

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
            )
        }

        fun lerProdutos(lista: JSONArray): List<Produto> = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }.map { p ->
            Produto(
                id = p.optInt("id"),
                nome = p.optString("name"),
                ativo = p.optBoolean("active", true),
                estoque = if (p.isNull("stockQuantity")) null else p.optInt("stockQuantity"),
                precoCentavos = if (p.isNull("priceCents")) null else p.optInt("priceCents"),
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

        private fun plural(n: Int, um: String, varios: String) = "$n ${if (n == 1) um else varios}"
    }
}
