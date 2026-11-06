package com.tonimarques.sextafeira

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Cobrança por link para a loja sem site: a Vênus cria o link de pagamento da venda (Pix ou cartão) na
 * plataforma que o lojista escolheu, o lojista manda para a cliente no WhatsApp, e a Vênus confere sozinha
 * (a cada 2 minutos enquanto houver cobrança aberta, por até 7 dias) e dá baixa na venda quando o pagamento entra.
 *
 * - Mercado Pago: o lojista cola o Access Token de produção da conta dele. Link = Checkout Pro (Pix e cartão);
 *   a conferência é pela busca de pagamentos com a referência da venda.
 * - InfinitePay: só a InfiniteTag. O aviso de pagamento (webhook) chega no servidor da Vênus, e a conferência
 *   passa por lá (o servidor confirma com a InfinitePay antes de responder).
 */
object Cobranca {

    const val ACAO = "com.tonimarques.sextafeira.COBRANCAS"
    const val INTERVALO_MS = 2 * 60_000L
    private const val PREFS = "cobranca"
    /** Quem recebe o aviso de pagamento da InfinitePay: o servidor da Vênus (Cloudflare) ou, sem ele, o site da loja. */
    private val SERVIDOR_AVISOS get() = if (Servidor.configurado()) Servidor.ENDERECO + "/cobranca" else "https://desejo-de-intimidade.floot.app/_api/venus/cobranca"
    private const val MP = "https://api.mercadopago.com"
    private const val DIAS_ABERTA = 7

    data class Provedor(val id: String, val nome: String, val resumo: String, val site: String)

    val PROVEDORES = listOf(
        Provedor("mercadopago", "Mercado Pago", "Pix e cartão parcelado por link. Precisa da chave (Access Token) da sua conta.",
            "https://www.mercadopago.com.br/developers/panel/app"),
        Provedor("infinitepay", "InfinitePay", "Pix e cartão por link, com as taxas da sua conta. Só precisa da sua InfiniteTag.",
            "https://www.infinitepay.io/checkout"),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun provedor(ctx: Context): String = prefs(ctx).getString("provedor", "").orEmpty()
    fun nomeDoProvedor(ctx: Context): String = PROVEDORES.firstOrNull { it.id == provedor(ctx) }?.nome ?: "nenhuma"
    fun tokenMercadoPago(ctx: Context): String = Cofre.ler(prefs(ctx), "mp_token")
    fun infiniteTag(ctx: Context): String = prefs(ctx).getString("infinitetag", "").orEmpty()

    fun configurada(ctx: Context): Boolean = when (provedor(ctx)) {
        "mercadopago" -> tokenMercadoPago(ctx).isNotBlank()
        "infinitepay" -> infiniteTag(ctx).isNotBlank()
        else -> false
    }

    fun salvarMercadoPago(ctx: Context, token: String) {
        prefs(ctx).edit().putString("provedor", "mercadopago").apply()
        Cofre.guardar(prefs(ctx), "mp_token", token.trim())
        Diario.anotar(ctx, "conexao", "Cobrança por link: Mercado Pago configurado", "tela")
    }

    fun salvarInfinitePay(ctx: Context, tag: String) {
        prefs(ctx).edit().putString("provedor", "infinitepay").putString("infinitetag", limparTag(tag)).apply()
        Diario.anotar(ctx, "conexao", "Cobrança por link: InfinitePay configurada (\$${limparTag(tag)})", "tela")
    }

    fun desligar(ctx: Context) {
        prefs(ctx).edit().remove("provedor").apply()
        Diario.anotar(ctx, "conexao", "Cobrança por link desligada", "tela")
    }

    fun limparTag(t: String): String = t.trim().removePrefix("$").trim().lowercase(Locale.ROOT)

    // ------------------------------------------------------------------ HTTP
    private fun pedir(metodo: String, url: String, corpo: JSONObject? = null, cabecalhos: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in cabecalhos) c.setRequestProperty(k, v)
            if (corpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(corpo.toString().toByteArray()) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return codigo to texto
        } finally {
            c.disconnect()
        }
    }

    private fun mp(ctx: Context) = mapOf("Authorization" to "Bearer ${tokenMercadoPago(ctx)}")

    /** Confere a configuração. Devolve uma frase para o lojista; lança erro com a explicação se não funcionar. Bloqueante. */
    fun testar(ctx: Context): String = when (provedor(ctx)) {
        "mercadopago" -> {
            val (codigo, texto) = pedir("GET", "$MP/users/me", cabecalhos = mp(ctx))
            if (codigo == 401 || codigo == 403) throw IllegalStateException("O Mercado Pago recusou a chave. Confira se copiou o Access Token de produção inteiro (começa com APP_USR-).")
            if (codigo !in 200..299) throw IllegalStateException("O Mercado Pago respondeu $codigo. Tente de novo em instantes.")
            val o = JSONObject(texto)
            val nome = o.optString("nickname").ifBlank { o.optString("email") }
            "Conectado à conta do Mercado Pago" + (if (nome.isNotBlank()) " ($nome)" else "") + "."
        }
        "infinitepay" -> {
            val tag = infiniteTag(ctx)
            if (!tag.matches(Regex("[a-z0-9._-]{3,40}"))) throw IllegalStateException("Essa InfiniteTag não parece certa. Use só letras, números, ponto, traço ou _ (sem o $).")
            "InfiniteTag \$$tag salva. Faça uma venda-teste de R$ 1,00 para conferir o link e o aviso de pagamento."
        }
        else -> throw IllegalStateException("Escolha a plataforma de cobrança.")
    }

    // ------------------------------------------------------------------ criar o link
    data class Link(val ref: String, val url: String, val provedor: String)

    /** Cria o link de pagamento da venda. Bloqueante; lança erro com a explicação. */
    fun criarLink(ctx: Context, vendaId: Int, itens: List<Loja.Item>, cliente: String): Link {
        require(itens.isNotEmpty()) { "A venda não tem itens." }
        val ref = "venus-" + UUID.randomUUID().toString().replace("-", "")
        return when (provedor(ctx)) {
            "mercadopago" -> {
                val lista = JSONArray()
                for (i in itens) lista.put(
                    JSONObject().put("title", i.nome.take(250)).put("quantity", i.quantidade)
                        .put("unit_price", i.precoCentavos / 100.0).put("currency_id", "BRL"),
                )
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }
                val corpo = JSONObject()
                    .put("items", lista)
                    .put("external_reference", ref)
                    .put("statement_descriptor", Perfil.loja.replace(Regex("[^A-Za-z0-9 ]"), "").take(13).ifBlank { "LOJA" })
                    .put("payment_methods", JSONObject().put("installments", Perfil.regras.cartaoParcelasMax.coerceIn(1, 12)))
                    .put("expires", true)
                    .put("expiration_date_to", iso.format(System.currentTimeMillis() + DIAS_ABERTA * 86_400_000L))
                    .put("metadata", JSONObject().put("venda", vendaId).put("cliente", cliente.take(60)))
                val (codigo, texto) = pedir("POST", "$MP/checkout/preferences", corpo, mp(ctx))
                if (codigo == 401 || codigo == 403) throw IllegalStateException("O Mercado Pago recusou a chave. Confira em Menu → Cobrança.")
                if (codigo !in 200..299) throw IllegalStateException("O Mercado Pago não criou o link ($codigo): ${erroDe(texto)}")
                val url = JSONObject(texto).optString("init_point")
                if (url.isBlank()) throw IllegalStateException("O Mercado Pago não devolveu o link.")
                Link(ref, url, "mercadopago")
            }
            "infinitepay" -> {
                val lista = JSONArray()
                for (i in itens) lista.put(JSONObject().put("quantity", i.quantidade).put("price", i.precoCentavos).put("description", i.nome.take(120)))
                val total = itens.sumOf { it.precoCentavos * it.quantidade }
                if (total < 100) throw IllegalStateException("A InfinitePay só cobra a partir de R$ 1,00.")
                val corpo = JSONObject()
                    .put("handle", infiniteTag(ctx))
                    .put("order_nsu", ref)
                    .put("items", lista)
                    .put("webhook_url", "${SERVIDOR_AVISOS}${if (Servidor.configurado()) "/aviso" else "_aviso"}")
                val (codigo, texto) = pedir("POST", "https://api.checkout.infinitepay.io/links", corpo)
                if (codigo !in 200..299) throw IllegalStateException("A InfinitePay não criou o link ($codigo): ${erroDe(texto)}. Confira a InfiniteTag e se o checkout integrado está ligado.")
                val url = urlDe(texto) ?: throw IllegalStateException("A InfinitePay não devolveu o link.")
                Link(ref, url, "infinitepay")
            }
            else -> throw IllegalStateException("Configure a cobrança primeiro (Menu → Cobrança por link).")
        }
    }

    private fun erroDe(texto: String): String = try {
        val o = JSONObject(texto)
        o.optString("message").ifBlank { o.optString("error") }.ifBlank { texto.take(120) }
    } catch (e: Exception) {
        texto.take(120)
    }

    /** O endereço do link na resposta (o nome do campo pode variar): "url", "link", "checkout_url" ou o primeiro https. */
    internal fun urlDe(texto: String): String? {
        val o = try {
            JSONObject(texto)
        } catch (e: Exception) {
            return Regex("https://[^\"\\s]+").find(texto)?.value
        }
        for (k in listOf("url", "link", "checkout_url", "payment_url")) o.optString(k).takeIf { it.startsWith("https://") }?.let { return it }
        o.optJSONObject("data")?.let { d -> for (k in listOf("url", "link", "checkout_url")) d.optString(k).takeIf { it.startsWith("https://") }?.let { return it } }
        return Regex("https://[^\"\\s]+").find(texto)?.value
    }

    // ------------------------------------------------------------------ conferir
    data class Situacao(val pago: Boolean, val valorCentavos: Int?, val forma: String?)

    /** Pergunta à plataforma se o link foi pago. Bloqueante. */
    fun conferir(ctx: Context, ref: String, provedor: String): Situacao = when (provedor) {
        "mercadopago" -> {
            val (codigo, texto) = pedir("GET", "$MP/v1/payments/search?sort=date_created&criteria=desc&external_reference=" + URLEncoder.encode(ref, "UTF-8"), cabecalhos = mp(ctx))
            if (codigo !in 200..299) throw IllegalStateException("Mercado Pago $codigo")
            val r = JSONObject(texto).optJSONArray("results") ?: JSONArray()
            val aprovado = (0 until r.length()).mapNotNull { r.optJSONObject(it) }.firstOrNull { it.optString("status") == "approved" }
            if (aprovado == null) Situacao(false, null, null)
            else Situacao(
                true, Math.round(aprovado.optDouble("transaction_amount", 0.0) * 100).toInt(),
                when (aprovado.optString("payment_type_id")) {
                    "bank_transfer" -> "Pix"
                    "credit_card" -> "Cartão" + aprovado.optInt("installments", 1).let { if (it > 1) " em ${it}x" else "" }
                    "debit_card" -> "Débito"
                    "account_money" -> "Saldo Mercado Pago"
                    else -> "Mercado Pago"
                },
            )
        }
        "infinitepay" -> {
            val url = "${SERVIDOR_AVISOS}${if (Servidor.configurado()) "/status" else "_status"}?ref=" + URLEncoder.encode(ref, "UTF-8") + "&handle=" + URLEncoder.encode(infiniteTag(ctx), "UTF-8")
            val (codigo, texto) = pedir("GET", url)
            if (codigo !in 200..299) throw IllegalStateException("Servidor $codigo")
            val o = JSONObject(texto)
            val forma = o.optString("forma").takeIf { it.isNotBlank() && it != "null" }
            Situacao(
                o.optBoolean("pago"), if (o.isNull("valorCentavos")) null else o.optInt("valorCentavos"),
                when (forma) {
                    "pix" -> "Pix"
                    "credit_card" -> "Cartão"
                    null -> "InfinitePay"
                    else -> forma
                },
            )
        }
        else -> Situacao(false, null, null)
    }

    // ------------------------------------------------------------------ vendas com link aberto
    /** As vendas do catálogo esperando pagamento por link, abertas há até 7 dias: (id da venda, ref, provedor). */
    fun pendentes(ctx: Context): List<Triple<Int, String, String>> {
        val vendas = CatalogoInterno.ler(ctx).optJSONArray("vendas") ?: return emptyList()
        val limite = System.currentTimeMillis() - DIAS_ABERTA * 86_400_000L
        return (0 until vendas.length()).mapNotNull { vendas.optJSONObject(it) }
            .filter { it.optString("status") == "awaiting_payment" && it.optLong("criado") >= limite }
            .mapNotNull { v -> v.optJSONObject("cobranca")?.let { c -> Triple(v.optInt("id"), c.optString("ref"), c.optString("provedor")) } }
            .filter { it.second.isNotBlank() }
    }

    // ------------------------------------------------------------------ teste de R$ 1,00 (fora do catálogo)
    fun testeAberto(ctx: Context): Pair<String, String>? {
        val p = prefs(ctx)
        val ref = p.getString("teste_ref", null) ?: return null
        return ref to p.getString("teste_provedor", "").orEmpty()
    }

    /** Cria um link de R$ 1,00 só para conferir a configuração. Bloqueante. */
    fun criarTeste(ctx: Context): Link {
        val link = criarLink(ctx, 0, listOf(Loja.Item("Teste de cobrança ${Marca.nome}", 1, 100)), "Teste")
        prefs(ctx).edit().putString("teste_ref", link.ref).putString("teste_provedor", link.provedor).apply()
        Diario.anotar(ctx, "conexao", "Link de teste de R$ 1,00 criado (${nomeDoProvedor(ctx)})", "tela")
        return link
    }

    /** Null se não houver teste aberto. Bloqueante. */
    fun conferirTeste(ctx: Context): Situacao? {
        val (ref, prov) = testeAberto(ctx) ?: return null
        val s = conferir(ctx, ref, prov)
        if (s.pago) {
            prefs(ctx).edit().remove("teste_ref").remove("teste_provedor").apply()
            Diario.anotar(ctx, "conexao", "Teste de cobrança pago e conferido (${s.forma ?: prov})", "tela")
        }
        return s
    }

    /** O link guardado de uma venda (para reenviar). */
    fun linkDaVenda(ctx: Context, id: Int): String? {
        val vendas = CatalogoInterno.ler(ctx).optJSONArray("vendas") ?: return null
        return (0 until vendas.length()).mapNotNull { vendas.optJSONObject(it) }.firstOrNull { it.optInt("id") == id }
            ?.optJSONObject("cobranca")?.optString("url")?.takeIf { it.isNotBlank() }
    }

    private val trava = Any()

    /** Confere todas as cobranças abertas; dá baixa e avisa as pagas. Devolve as vendas que foram pagas agora. Bloqueante. */
    fun conferirPendentes(ctx: Context): List<Loja.Pedido> = synchronized(trava) {
        val pagas = mutableListOf<Loja.Pedido>()
        for ((id, ref, prov) in pendentes(ctx)) {
            val s = try {
                conferir(ctx, ref, prov)
            } catch (e: Exception) {
                continue
            }
            if (!s.pago) continue
            val venda = CatalogoInterno(ctx).marcarPaga(id, s.forma ?: "Link de pagamento") ?: continue
            pagas += venda
            val valor = Loja.dinheiro(s.valorCentavos ?: venda.totalCentavos)
            Diario.anotar(ctx, "loja", "Pagamento recebido pelo link: venda nº $id, $valor (${s.forma ?: prov})", "cobranca")
            VigiaDaLoja.avisar(ctx, 80_000 + id, "Pagamento recebido: $valor",
                "${venda.cliente} pagou a venda nº $id (${s.forma ?: "link"}). Já dei baixa: é só separar e combinar a entrega.")
        }
        if (pendentes(ctx).isNotEmpty()) agendar(ctx)
        pagas
    }

    fun conferirEmSegundoPlano(ctx: Context) {
        val app = ctx.applicationContext
        if (pendentes(app).isEmpty()) return
        Thread {
            try {
                conferirPendentes(app)
            } catch (e: Exception) {
            }
        }.start()
    }

    // ------------------------------------------------------------------ alarme de 2 minutos (só enquanto houver cobrança aberta)
    private fun alarme(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 74_700, Intent(ctx, AlarmeDasCobrancas::class.java).setAction(ACAO), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun agendar(ctx: Context, daqui: Long = INTERVALO_MS) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val quando = System.currentTimeMillis() + daqui
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
        }
    }

    /** Mensagem pronta para mandar à cliente com o link. */
    fun mensagemParaCliente(cliente: String, totalCentavos: Int, url: String): String {
        val nome = cliente.trim().substringBefore(" ").substringBefore("(").trim()
        return (if (nome.isNotBlank() && nome != "Cliente") "Oi, $nome! " else "Oi! ") +
            "Segue o link para pagar sua compra na ${Perfil.loja} (${Loja.dinheiro(totalCentavos)}). Pode pagar por Pix ou cartão:\n$url\n\n" +
            "Assim que o pagamento entrar, a gente separa tudo e te avisa por aqui 💖"
    }
}

/** Confere as cobranças por link a cada 2 minutos (e volta a agendar depois de reiniciar o celular). */
class AlarmeDasCobrancas : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (Cobranca.pendentes(ctx.applicationContext).isNotEmpty()) Cobranca.agendar(ctx, 30_000L)
            Cobranca.ACAO -> {
                val pendente = goAsync()
                Thread {
                    try {
                        Cobranca.conferirPendentes(ctx.applicationContext)
                    } catch (e: Exception) {
                        Cobranca.agendar(ctx.applicationContext)
                    } finally {
                        pendente.finish()
                    }
                }.start()
            }
        }
    }
}
