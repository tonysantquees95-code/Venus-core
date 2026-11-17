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

    /** Loja em outra plataforma (Shopify, WooCommerce, Nuvemshop); null = site no Floot (o padrão). */
    private val externa: Plataformas.Adaptador? get() = Plataformas.ativa(contexto)
    private val ehFloot get() = Plataformas.tipo(contexto) == "floot"
    /** Sem site: catálogo dentro da Vênus. */
    val ehInterno get() = Plataformas.tipo(contexto) == "interno"

    // ------------------------------------------------------------- conta
    val conectada get() = if (ehFloot) prefs.getString(P_EMAIL, null) != null && prefs.getString(P_SENHA, null) != null else externa != null
    val email get() = if (ehFloot) prefs.getString(P_EMAIL, null) else externa?.nome

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

    /** E-mail e senha do administrador do site (Floot) para acompanhar a conta em outro aparelho; null se não estiver conectado. */
    fun loginParaSincronia(): Pair<String, String>? {
        val email = prefs.getString(P_EMAIL, null) ?: return null
        val senha = prefs.getString(P_SENHA, null)?.let { decifrar(it) } ?: return null
        return email to senha
    }

    /** Guarda o login que veio da conta (sem testar de novo na rede), só se este aparelho ainda não tem um. */
    fun guardarLoginDaConta(email: String, senha: String) {
        if (email.isBlank() || senha.isBlank() || prefs.getString(P_EMAIL, null) != null) return
        prefs.edit().putString(P_EMAIL, email.trim()).putString(P_SENHA, cifrar(senha)).apply()
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
        if (!ehFloot) throw ErroDaLoja(SO_FLOOT)
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
        if (!ehFloot) throw ErroDaLoja(SO_FLOOT)
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
            CacheDoSite.invalidar() // mudou algo no site: a próxima leitura busca de novo
            return dados(resposta)
        }
        throw ErroDaLoja("Não consegui entrar na loja.")
    }

    /**
     * GET com cópia local: dentro da [validade] nem vai ao site; se o site falhar (fora do ar, pausado),
     * devolve a última cópia em vez do erro.
     */
    private fun buscarComCopia(nome: String, caminho: String, validade: Long): JSONObject {
        CacheDoSite.fresca(contexto, nome, validade)?.let { t -> try { return JSONObject(t) } catch (e: Exception) { } }
        return try {
            buscar(caminho).also { CacheDoSite.guardar(contexto, nome, it.toString()) }
        } catch (e: Exception) {
            val velha = CacheDoSite.velha(contexto, nome) ?: throw e
            JSONObject(velha)
        }
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
        if (!ehFloot) return (externa ?: throw ErroDaLoja(SEM_PLATAFORMA)).pedidos()
        val lista = buscarComCopia("pedidos", "/_api/admin/orders", VALIDADE_PEDIDOS).optJSONArray("orders") ?: JSONArray()
        return lerPedidos(lista)
    }

    fun produtos(): List<Produto> {
        if (!ehFloot) return (externa ?: throw ErroDaLoja(SEM_PLATAFORMA)).produtos()
        val lista = buscarComCopia("produtos", "/_api/admin/products", VALIDADE_PRODUTOS).optJSONArray("products") ?: JSONArray()
        return lerProdutos(lista)
    }

    // ------------------------------------------------------------- respostas
    fun resumo(periodo: String): String = montarResumo(pedidos(), periodo, Calendar.getInstance())

    fun pedidosPendentes(): String = montarPendentes(pedidos())

    fun estoqueDe(produto: String): String = montarEstoque(produtos(), produto)

    fun acabando(): String = montarAcabando(produtos())

    fun estoqueGeral(): String = montarEstoqueGeral(produtos())

    fun retiradas(): String = montarRetiradas(pedidos())

    fun infoProduto(produto: String): String = montarInfoProduto(produtos(), produto)

    fun margem(produto: String): String = montarMargem(produtos(), produto)

    fun margens(): String = montarMargens(produtos())

    fun lucro(periodo: String): String = montarLucro(pedidos(), produtos(), periodo, Calendar.getInstance())

    /** Liga as linhas da nota de compra aos produtos da loja. */
    fun casarCompra(linhas: List<Triple<String, Int, Int>>): List<ItemDeCompra> = casarCompra(produtos(), linhas)

    /** Aplica a compra confirmada: soma ao estoque e (se o site já guarda) salva o custo. Devolve o resumo. */
    fun aplicarCompra(itens: List<ItemDeCompra>, somarEstoque: Boolean, salvarCusto: Boolean): String {
        var estoque = 0
        var custos = 0
        val erros = mutableListOf<String>()
        for (i in itens) {
            val p = i.produto ?: continue
            try {
                if (somarEstoque && i.quantidade > 0 && !ehFloot) {
                    (externa ?: throw ErroDaLoja(SEM_PLATAFORMA)).definirEstoque(p, (p.estoque ?: 0) + i.quantidade)
                    estoque++
                } else if (somarEstoque && i.quantidade > 0) {
                    val atual = p.estoque
                    if (atual == null) enviar("/_api/admin/stock_update", JSONObject().put("productId", p.id).put("quantity", i.quantidade).put("mode", "set"))
                    else enviar("/_api/admin/stock_update", JSONObject().put("productId", p.id).put("quantity", i.quantidade).put("mode", "add"))
                    estoque++
                }
                if (salvarCusto && ehInterno) {
                    CatalogoInterno(contexto).mudarProduto(p, "custo", i.custoUnitario)
                    custos++
                } else if (salvarCusto && ehFloot) {
                    val atual = produtos().firstOrNull { it.id == p.id }
                    val corpo = corpoDoProduto(atual?.bruto, "custo", i.custoUnitario)
                    if (corpo != null) {
                        enviar("/_api/admin/product_save", corpo)
                        custos++
                    }
                }
            } catch (e: Exception) {
                erros += p.nome
            }
        }
        var texto = "Pronto:"
        if (somarEstoque) texto += " estoque somado em ${plural(estoque, "produto", "produtos")}."
        if (salvarCusto) texto += " Custo salvo em ${plural(custos, "produto", "produtos")}."
        if (erros.isNotEmpty()) texto += " Não consegui atualizar: ${erros.joinToString(", ")}."
        return texto
    }

    /** A mesma alteração confirmada, feita na plataforma da loja (Shopify, WooCommerce, Nuvemshop). */
    private fun aplicarFora(x: Plataformas.Adaptador, a: Alteracao): String {
        when (a.tipo) {
            "estoque_somar", "estoque_tirar", "estoque_definir" -> {
                val p = a.produto!!
                x.definirEstoque(p, a.valor!!)
                return "Pronto: ${p.nome} agora tem ${plural(a.valor, "unidade", "unidades")} no estoque da ${x.nome}."
            }
            "preco", "ativar", "desativar", "promocao", "fim_promocao" -> {
                val p = a.produto!!
                x.mudarProduto(p, a.tipo, a.valor)
                return when (a.tipo) {
                    "preco" -> "Pronto: ${p.nome} agora custa ${dinheiro(a.valor!!)} na ${x.nome}."
                    "promocao" -> "Pronto: ${p.nome} está em promoção por ${dinheiro(a.valor!!)} na ${x.nome}."
                    "fim_promocao" -> "Pronto: ${p.nome} saiu da promoção."
                    "ativar" -> "Pronto: ${p.nome} voltou a aparecer na loja."
                    else -> "Pronto: ${p.nome} foi escondido da loja."
                }
            }
            "venda" -> {
                val p = a.produto!!
                if (x !is CatalogoInterno) {
                    val atual = p.estoque ?: throw ErroDaLoja("${p.nome} está sem controle de estoque.")
                    x.definirEstoque(p, (atual - a.valor!!).coerceAtLeast(0))
                    return "Pronto: ${p.nome} agora tem ${plural((atual - a.valor).coerceAtLeast(0), "unidade", "unidades")} no estoque da ${x.nome}."
                }
                val v = x.registrarVenda("Cliente", listOf(Triple(p, a.valor!!, null)), "não informado", "não informada")
                return "Pronto: venda registrada (${dinheiro(v.totalCentavos)})." + (p.estoque?.let { " ${p.nome} fica com ${(it - a.valor).coerceAtLeast(0)} no estoque." } ?: "")
            }
            "custo" -> {
                if (x !is CatalogoInterno) throw ErroDaLoja("O custo do produto só fica guardado no site próprio ou no catálogo da Vênus por enquanto.")
                val p = a.produto!!
                x.mudarProduto(p, "custo", a.valor)
                return "Pronto: custo de ${p.nome} cadastrado em ${dinheiro(a.valor!!)}."
            }
            "entregue", "status" -> {
                val o = a.pedido!!
                val status = if (a.tipo == "entregue") "delivered" else a.status!!
                x.mudarPedido(o, status, a.rastreio ?: o.rastreio)
                return "Pronto: o pedido ${o.id} está ${NOME_STATUS[status] ?: status} na ${x.nome}."
            }
        }
        throw ErroDaLoja("Não sei fazer essa mudança na ${x.nome}.")
    }

    fun maisVendidos(periodo: String): String = montarMaisVendidos(pedidos(), periodo, Calendar.getInstance())

    fun resumoDaManha(): String = montarResumoDaManha(pedidos(), produtos(), Calendar.getInstance())

    fun previsao(produto: String): String = montarPrevisao(pedidos(), produtos(), produto, Calendar.getInstance())

    fun comparar(): String = montarComparacao(pedidos(), Calendar.getInstance())

    fun parados(): String = montarParados(pedidos(), produtos(), Calendar.getInstance())

    fun reposicao(periodo: String): String = montarReposicao(pedidos(), produtos(), periodo, Calendar.getInstance())

    fun pedido(id: Int): Pedido? = pedidos().firstOrNull { it.id == id }

    /** Monta a pergunta de confirmação (nada muda no site ainda). */
    fun prepararAlteracao(tipo: String, falado: String, valor: Int?): Preparo =
        montarAlteracao(produtos(), if (tipo == "venda" && !ehInterno) "estoque_tirar" else tipo, falado, valor)

    fun prepararEntregue(id: Int): Preparo = montarEntregue(pedidos(), id)

    fun prepararStatus(id: Int, status: String, rastreio: String?): Preparo = montarStatus(pedidos(), id, status, rastreio)

    // ------------------------------------------------------------- Vênus Robô (WhatsApp e Instagram)
    /** Como está o robô: ligado, atendimentos de hoje e clientes esperando a loja. */
    fun robo(): String = try {
        montarRobo(buscar("/_api/admin/robo"))
    } catch (e: ErroDaLoja) {
        if (e.message?.contains("404") == true) "O site ainda não tem o painel do robô. Ele entra na próxima atualização do site." else throw e
    }

    fun ligarRobo(ligado: Boolean): String {
        enviar("/_api/admin/robo_ligar", JSONObject().put("ligado", ligado))
        return if (ligado) "Robô ligado: a Vênus volta a responder o WhatsApp e o Instagram da loja."
        else "Robô desligado: a Vênus parou de responder o WhatsApp e o Instagram até você pedir para ligar."
    }

    /** Envia a foto do produto para o site e devolve o endereço dela. */
    // ------------------------------------------------------------------ stories do dia (página Stories do painel)
    /** Guarda o story no painel e avisa a equipe nos celulares. Devolve o número do story. */
    fun registrarStory(turno: String, titulo: String, texto: String, pergunta: String?, imagemUrl: String, enquete: Boolean, publicado: Boolean): Int =
        enviar(
            "/_api/admin/stories_novo",
            JSONObject().put("turno", turno).put("titulo", titulo.take(120)).put("texto", texto.take(400))
                .put("pergunta", pergunta ?: JSONObject.NULL).put("imagemUrl", imagemUrl).put("enquete", enquete).put("publicadoInstagram", publicado),
        ).optInt("id")

    /** Segundo aviso para a equipe, se ninguém marcou que postou. */
    fun lembrarStory(id: Int): Boolean = enviar("/_api/admin/stories_lembrar", JSONObject().put("id", id)).optBoolean("lembrou")

    /** Marca como publicado (instagram ou whatsapp) em nome da conta da loja. Devolve quem publicou. */
    fun marcarStory(id: Int, onde: String): String = enviar("/_api/admin/stories_marcar", JSONObject().put("id", id).put("onde", onde)).optString("por")

    /**
     * Entrega ao site as regras de venda (pagamento, frete, retirada…), as mesmas que a Vênus usa no app,
     * com o resumo e as respostas prontas (o robô e o Direct do Instagram respondem com elas).
     */
    fun enviarRegrasDeVenda(regras: RegrasDeVenda, loja: String) {
        val respostas = JSONArray()
        for ((id, pergunta, resposta) in regras.perguntasComId(loja)) {
            respostas.put(JSONObject().put("id", id).put("pergunta", pergunta.take(300)).put("resposta", resposta.take(1500)))
        }
        enviar("/_api/admin/regras_venda", JSONObject().put("regras", regras.json()).put("texto", regras.texto().take(4000)).put("respostas", respostas))
    }

    // ------------------------------------------------------------------ pedidos da página Publicações do site
    /** Pedidos novos da página Publicações (o site já marca como "fazendo"). */
    fun pedidosDePublicacao(): List<PedidosDoSite.Pedido> {
        val json = buscar("/_api/admin/pedidos_publicacao")
        // o site manda junto o canal de aviso (ntfy): com ele, a Vênus só pergunta ao site quando há novidade
        PedidosDoSite.guardarCanal(contexto, json.optString("aviso").takeIf { it.isNotBlank() && it != "null" })
        return PedidosDoSite.lerPedidos(json)
    }

    /** Um pedido pelo número, da cópia local; se não estiver nela (pedido feito agora), busca de novo uma vez. */
    fun pedidoPorNumero(id: Int): Pedido? {
        pedidos().firstOrNull { it.id == id }?.let { return it }
        CacheDoSite.invalidar()
        return pedidos().firstOrNull { it.id == id }
    }

    fun responderPedidoDePublicacao(id: Int, ok: Boolean, resultado: String, imagemUrl: String?) {
        enviar(
            "/_api/admin/pedidos_publicacao",
            JSONObject().put("id", id).put("ok", ok).put("resultado", resultado.take(500)).put("imagemUrl", imagemUrl ?: JSONObject.NULL),
        )
    }

    /** As ideias de post da Vênus, para a página Publicações do site. */
    fun enviarIdeias(ideias: JSONArray) {
        enviar("/_api/admin/ideias", JSONObject().put("ideias", ideias))
    }

    /** Entrega ao site o token do Instagram (para o Direct automático responder pela API oficial). */
    fun enviarTokenDoInstagram(token: String, contaId: String, usuario: String) {
        enviar("/_api/admin/instagram_token", JSONObject().put("token", token).put("contaId", contaId).put("usuario", usuario))
    }

    fun enviarFoto(jpeg: ByteArray): String {
        if (ehInterno) return CatalogoInterno(contexto).guardarFoto(jpeg)
        val r = enviar("/_api/admin/upload", JSONObject().put("contentType", "image/jpeg").put("sizeBytes", jpeg.size))
        val destino = r.optString("presignedUrl").ifBlank { throw ErroDaLoja("O site não aceitou a foto.") }
        val url = r.optString("url").ifBlank { throw ErroDaLoja("O site não devolveu o endereço da foto.") }
        val c = URL(destino).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setFixedLengthStreamingMode(jpeg.size)
            val cabecalhos = r.optJSONObject("headers")
            var temTipo = false
            cabecalhos?.keys()?.forEach { k ->
                c.setRequestProperty(k, cabecalhos.optString(k))
                if (k.equals("Content-Type", true)) temTipo = true
            }
            if (!temTipo) c.setRequestProperty("Content-Type", "image/jpeg")
            c.outputStream.use { it.write(jpeg) }
            if (c.responseCode !in 200..299) throw ErroDaLoja("A foto não subiu (erro ${c.responseCode}).")
        } finally {
            c.disconnect()
        }
        return enderecoCompleto(url)
    }

    /** Cria um produto novo no site. */
    fun cadastrar(p: NovoProduto): String {
        if (ehInterno) {
            CatalogoInterno(contexto).adicionar(p)
            val onde = if (p.ativo) "já aparece para as clientes (robô e catálogo)" else "ficou escondido até você mostrar"
            return "Pronto: ${p.nome} cadastrado no catálogo" + (p.precoCentavos?.let { " por ${dinheiro(it)}" } ?: "") + " e $onde."
        }
        enviar("/_api/admin/product_save", corpoDoNovo(p))
        val onde = if (p.ativo) "já aparece no site" else "ficou escondido do site até você mostrar (diga: mostra o ${p.nome} no site)"
        return "Pronto: ${p.nome} cadastrado" + (p.precoCentavos?.let { " por ${dinheiro(it)}" } ?: "") + " e $onde."
    }

    /** Aplica uma alteração já confirmada. */
    fun aplicar(a: Alteracao): String {
        if (!ehFloot && a.tipo != "lote") return aplicarFora(externa ?: throw ErroDaLoja(SEM_PLATAFORMA), a)
        when (a.tipo) {
            "lote" -> {
                var ok = 0
                val falhas = mutableListOf<String>()
                for (x in a.lote) {
                    try {
                        aplicar(x)
                        ok++
                    } catch (e: Exception) {
                        falhas += x.produto?.nome ?: x.pedido?.id?.toString() ?: "?"
                    }
                }
                return "Pronto: ${plural(ok, "produto alterado", "produtos alterados")} no site." +
                    if (falhas.isNotEmpty()) " Não consegui mudar: ${falhas.joinToString(", ")}." else ""
            }
            "estoque_somar", "estoque_tirar", "estoque_definir" -> {
                val p = a.produto!!
                enviar("/_api/admin/stock_update", JSONObject().put("productId", p.id).put("quantity", a.valor!!).put("mode", "set"))
                return "Pronto: ${p.nome} agora tem ${plural(a.valor, "unidade", "unidades")} no estoque."
            }
            "preco", "ativar", "desativar", "promocao", "fim_promocao", "custo" -> {
                val p = a.produto!!
                // relê o produto na hora, para não desfazer outra mudança feita pelo painel
                val atual = produtos().firstOrNull { it.id == p.id } ?: throw ErroDaLoja("Não achei mais ${p.nome} na loja.")
                val corpo = corpoDoProduto(atual.bruto, a.tipo, a.valor)
                    ?: throw ErroDaLoja("Não consegui ler todos os dados de ${p.nome}. Faça essa mudança pelo painel do site.")
                enviar("/_api/admin/product_save", corpo)
                return when (a.tipo) {
                    "preco" -> "Pronto: ${p.nome} agora custa ${dinheiro(a.valor!!)} no site."
                    "custo" -> "Pronto: custo de ${p.nome} cadastrado em ${dinheiro(a.valor!!)}."
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
    data class Item(val nome: String, val quantidade: Int, val precoCentavos: Int, val produtoId: Int? = null)
    data class Pedido(
        val id: Int, val status: String, val totalCentavos: Int, val cliente: String,
        val entrega: String, val criado: Long, val pago: Long?, val itens: List<Item>,
        val telefone: String = "", val rastreio: String? = null,
        val descontoCentavos: Int = 0, val freteCentavos: Int = 0,
    )
    data class Produto(
        val id: Int, val nome: String, val ativo: Boolean, val estoque: Int?, val precoCentavos: Int?,
        val precoAntigo: Int? = null, val bruto: JSONObject? = null,
        /** Endereço do produto na loja (outras plataformas); null = SITE/produto/id (Floot). */
        val link: String? = null,
    ) {
        /** Quanto o produto custou para a loja (null se ainda não cadastrado ou se o site ainda não guarda custo). */
        val custoCentavos: Int? get() = bruto?.takeIf { it.has(CAMPO_CUSTO) && !it.isNull(CAMPO_CUSTO) }?.optInt(CAMPO_CUSTO)
    }

    /** Uma linha da nota de compra já ligada a um produto da loja. */
    data class ItemDeCompra(val descricao: String, val quantidade: Int, val custoUnitario: Int, val produto: Produto?)

    /** Produto novo para cadastrar (o que veio da fala e do formulário). */
    data class NovoProduto(
        val nome: String, val categoria: String, val descricao: String?, val precoCentavos: Int?,
        val estoque: Int?, val imagemUrl: String?, val ativo: Boolean,
        /** Preço de custo (só o painel vê); null se não informado. */
        val custoCentavos: Int? = null,
    )

    /** Uma mudança esperando o "sim". [valor]: quantidade final do estoque ou preço em centavos. */
    data class Alteracao(
        val tipo: String, val produto: Produto? = null, val pedido: Pedido? = null, val valor: Int? = null,
        val status: String? = null, val rastreio: String? = null, val lote: List<Alteracao> = emptyList(),
    )

    /** O que falar e, se der para seguir, a alteração que aguarda confirmação. */
    data class Preparo(val texto: String, val alteracao: Alteracao?)

    companion object {
        /** Quanto tempo a cópia local vale antes de ir ao site de novo. */
        const val VALIDADE_PEDIDOS = 3 * 60_000L
        const val VALIDADE_PRODUTOS = 20 * 60_000L

        /**
         * {ligado, hoje:{atendimentos, clientes, passadas}, esperando:[{canal, ultimaMensagem, quando}]} → frase.
         * Separado para poder ser testado.
         */
        fun montarRobo(o: JSONObject, agora: Long = System.currentTimeMillis()): String {
            val hoje = o.optJSONObject("hoje") ?: JSONObject()
            val esperando = o.optJSONArray("esperando") ?: JSONArray()
            val partes = mutableListOf<String>()
            partes += if (o.optBoolean("ligado", true)) "O robô está ligado." else "O robô está desligado."
            val atend = hoje.optInt("atendimentos")
            partes += if (atend == 0) "Hoje ainda não respondeu ninguém."
            else "Hoje respondeu $atend ${if (atend == 1) "mensagem" else "mensagens"} de ${hoje.optInt("clientes")} ${if (hoje.optInt("clientes") == 1) "cliente" else "clientes"}" +
                (if (hoje.optInt("passadas") > 0) " e passou ${hoje.optInt("passadas")} para você" else "") + "."
            if (esperando.length() == 0) partes += "Nenhuma cliente esperando você."
            else {
                val linhas = (0 until minOf(esperando.length(), 5)).mapNotNull { esperando.optJSONObject(it) }.map { e ->
                    val quando = lerData(e.opt("quando"))
                    val min = if (quando > 0) ((agora - quando) / 60_000).toInt() else -1
                    val ha = when {
                        min < 0 -> ""
                        min < 60 -> " (há $min min)"
                        else -> " (há ${min / 60} h)"
                    }
                    "${e.optString("canal").replaceFirstChar { it.uppercase() }}$ha: “${e.optString("ultimaMensagem").take(80)}”"
                }
                partes += "${esperando.length()} ${if (esperando.length() == 1) "cliente esperando" else "clientes esperando"} você: " + linhas.joinToString("; ") + "."
            }
            return partes.joinToString(" ")
        }

        /** "/_cdn/…" → "https://desejo-de-intimidade.tonysantquees95.workers.dev/_cdn/…" (o Instagram precisa do endereço completo). */
        fun enderecoCompleto(url: String): String = if (url.startsWith("/")) SITE + url else url
        /** O site da loja (vem do Perfil; o padrão é o da Desejo de Intimidade). */
        val SITE: String get() = Perfil.site
        private const val COOKIE = "floot_built_app_session"
        private const val ALIAS = "venus_loja"
        private const val P_EMAIL = "email"
        private const val P_SENHA = "senha"
        private const val P_COOKIE = "cookie"
        private const val SEM_CONTA = "A loja ainda não está conectada. Conecte nos ajustes: Conectar à loja."

        /** Igual ao site: com 2 unidades ou menos o produto está acabando. */
        const val ESTOQUE_BAIXO = 2
        const val SO_FLOOT = "Isso só funciona com site próprio por enquanto (robô, stories com foto, cadastro e custo de produto)."
        const val SEM_PLATAFORMA = "A loja ainda não está conectada. Nos ajustes, toque em Plataforma da loja."

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

        private fun lerData(v: Any?): Long = when (v) {
            is Number -> v.toLong()
            is String -> data(v) ?: 0L
            else -> 0L
        }

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
                    Item(i.optString("name"), i.optInt("quantity", 1), i.optInt("unitPriceCents"),
                        if (i.isNull("productId") || !i.has("productId")) null else i.optInt("productId"))
                },
                descontoCentavos = o.optInt("discountCents"),
                freteCentavos = o.optInt("shippingCents"),
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

        /** Nome do campo de custo no site (criado no painel). */
        const val CAMPO_CUSTO = "costCents"
        const val SEM_CAMPO_CUSTO = "O site ainda não tem o campo de preço de custo. Assim que ele for criado no painel, eu passo a guardar e calcular a margem."

        private fun pct(parte: Long, todo: Long) = if (todo == 0L) 0 else ((parte * 100 + todo / 2) / todo).toInt()

        /** Tudo sobre um produto: preço, Pix, promoção, estoque, custo e margem. */
        fun montarInfoProduto(produtos: List<Produto>, falado: String): String {
            val achados = acharProdutos(produtos, falado)
            if (achados.isEmpty()) return "Não achei nenhum produto com \"$falado\" na loja."
            if (achados.size > 1) return "Achei ${achados.size} produtos: ${achados.take(4).joinToString(", ") { it.nome }}. De qual você quer saber?"
            val p = achados[0]
            val partes = mutableListOf<String>()
            val preco = p.precoCentavos
            if (preco == null) partes += "${p.nome} está sem preço"
            else {
                val promo = p.precoAntigo?.takeIf { it > preco }
                val r = Perfil.regras
                val pix = r.precoNoPix(preco, emPromocao = promo != null)?.let { "${dinheiro(it)} no Pix" }
                val vezes = r.parcelas(preco)?.let { "ou $it" }
                partes += (if (promo != null) "${p.nome} está em promoção: de ${dinheiro(promo)} por ${dinheiro(preco)}" else "${p.nome} custa ${dinheiro(preco)}") +
                    listOfNotNull(pix, vezes).joinToString(" ", prefix = if (pix != null || vezes != null) " (" else "", postfix = if (pix != null || vezes != null) ")" else "")
            }
            partes += when {
                p.estoque == null -> "sem controle de estoque"
                p.estoque <= 0 -> "esgotado"
                else -> plural(p.estoque, "unidade", "unidades") + " em estoque"
            }
            val custo = p.custoCentavos
            if (custo != null && preco != null) partes += "custou ${dinheiro(custo)}, lucro de ${dinheiro(preco - custo)} por unidade (${pct((preco - custo).toLong(), preco.toLong())}%)"
            if (!p.ativo) partes += "está escondido do site"
            return partes.joinToString("; ") + "."
        }

        /** Margem de um produto: preço, custo, lucro por unidade. */
        fun montarMargem(produtos: List<Produto>, falado: String): String {
            val achados = acharProdutos(produtos, falado)
            if (achados.isEmpty()) return "Não achei nenhum produto com \"$falado\" na loja."
            if (achados.size > 1) return "Achei ${achados.size} produtos parecidos: ${achados.take(4).joinToString(", ") { it.nome }}. Diga o nome mais completo."
            val p = achados[0]
            if (produtos.none { it.bruto?.has(CAMPO_CUSTO) == true }) return SEM_CAMPO_CUSTO
            val custo = p.custoCentavos ?: return "${p.nome} ainda não tem custo cadastrado. Diga, por exemplo: o custo do ${p.nome} é 15 reais."
            val preco = p.precoCentavos ?: return "${p.nome} está sem preço de venda."
            val lucro = preco - custo
            var texto = "${p.nome}: vende por ${dinheiro(preco)}, custou ${dinheiro(custo)}. Lucro de ${dinheiro(lucro)} por unidade, " +
                "${pct(lucro.toLong(), preco.toLong())}% do preço (markup de ${pct(lucro.toLong(), custo.toLong())}% sobre o custo)."
            val promo = p.precoAntigo
            if ((promo == null || promo <= preco) && Perfil.pixPorcento > 0) {
                val pix = preco - (preco * Perfil.pixPorcento + 50) / 100
                texto += " No Pix, com ${Perfil.pixPorcento}% de desconto, o lucro fica ${dinheiro(pix - custo)}."
            }
            return texto
        }

        /** Margem média e os produtos que menos dão lucro. */
        fun montarMargens(produtos: List<Produto>): String {
            if (produtos.none { it.bruto?.has(CAMPO_CUSTO) == true }) return SEM_CAMPO_CUSTO
            val ativos = produtos.filter { it.ativo && it.precoCentavos != null && it.precoCentavos > 0 }
            val comCusto = ativos.filter { it.custoCentavos != null }
            if (comCusto.isEmpty()) return "Nenhum produto tem custo cadastrado ainda. Diga, por exemplo: o custo do gel é 15 reais, ou me mande a foto da nota de compra."
            val margens = comCusto.map { it to pct((it.precoCentavos!! - it.custoCentavos!!).toLong(), it.precoCentavos.toLong()) }
            val media = margens.sumOf { it.second } / margens.size
            val piores = margens.sortedBy { it.second }.take(3)
            var texto = "Margem média de ${media}% do preço, em ${plural(comCusto.size, "produto", "produtos")} com custo cadastrado. " +
                "Menor margem: " + piores.joinToString(", ") { "${it.first.nome} (${it.second}%)" } + "."
            val sem = ativos.size - comCusto.size
            if (sem > 0) texto += " Faltam ${plural(sem, "produto", "produtos")} sem custo: " + ativos.filter { it.custoCentavos == null }.take(4).joinToString(", ") { it.nome } +
                (if (sem > 4) " e mais ${sem - 4}" else "") + "."
            return texto
        }

        /** Lucro das vendas do período: valor vendido (já com o desconto do Pix) menos o custo dos itens. Frete só informativo. */
        fun montarLucro(pedidos: List<Pedido>, produtos: List<Produto>, periodo: String, agora: Calendar): String {
            if (produtos.none { it.bruto?.has(CAMPO_CUSTO) == true }) return SEM_CAMPO_CUSTO
            val (ini, fim) = inicioDoPeriodo(periodo, agora)
            val pagos = pedidos.filter { it.status in PAGOS && (it.pago ?: it.criado) in ini until fim }
            val quando = when (periodo) { "hoje" -> "hoje"; "ontem" -> "ontem"; "semana" -> "nos últimos 7 dias"; else -> "neste mês" }
            if (pagos.isEmpty()) return "Ainda não teve venda paga $quando."
            val porId = produtos.associateBy { it.id }
            val porNome = produtos.associateBy { ComandosLocais.normalizar(it.nome.trim()) }
            var vendido = 0L
            var custo = 0L
            var semCusto = 0
            val faltando = mutableSetOf<String>()
            for (o in pagos) {
                val bruto = o.itens.sumOf { it.precoCentavos.toLong() * it.quantidade }
                for (i in o.itens) {
                    val linha = i.precoCentavos.toLong() * i.quantidade
                    // o desconto do Pix do pedido é repartido entre os itens pelo valor de cada um
                    val desconto = if (bruto > 0) o.descontoCentavos * linha / bruto else 0
                    val p = i.produtoId?.let { porId[it] } ?: porNome[ComandosLocais.normalizar(i.nome.trim())]
                    val c = p?.custoCentavos
                    if (c == null) {
                        semCusto += i.quantidade
                        faltando += i.nome
                        continue
                    }
                    vendido += linha - desconto
                    custo += c.toLong() * i.quantidade
                }
            }
            val frete = pagos.sumOf { it.freteCentavos }
            if (vendido == 0L) return "As vendas $quando são de produtos sem custo cadastrado (${faltando.take(4).joinToString(", ")}). Cadastre o custo para eu calcular o lucro."
            val lucro = vendido - custo
            var texto = "Lucro $quando: ${dinheiro(lucro.toInt())} em ${plural(pagos.size, "pedido", "pedidos")} " +
                "(vendido ${dinheiro(vendido.toInt())}, custo ${dinheiro(custo.toInt())}, margem de ${pct(lucro, vendido)}%)."
            if (frete > 0) texto += " Frete cobrado dos clientes: ${dinheiro(frete)}, repassado ao envio."
            if (semCusto > 0) texto += " Ficaram de fora ${plural(semCusto, "unidade", "unidades")} sem custo cadastrado: ${faltando.take(4).joinToString(", ")}."
            return texto
        }

        /** Liga cada linha (descrição, quantidade, custo unitário) ao produto mais parecido da loja. */
        fun casarCompra(produtos: List<Produto>, linhas: List<Triple<String, Int, Int>>): List<ItemDeCompra> = linhas.map { (desc, qtd, unit) ->
            val achados = acharProdutos(produtos, desc)
            ItemDeCompra(desc, qtd, unit, if (achados.size == 1) achados[0] else null)
        }

        /** Catálogo em texto curto (para a IA responder clientes): só produtos à venda, com preço, Pix, estoque e link. */
        fun catalogoParaIA(produtos: List<Produto>): String = produtos.filter { it.ativo && it.precoCentavos != null }.take(80).joinToString("\n") { p ->
            val preco = p.precoCentavos!!
            val promo = p.precoAntigo?.takeIf { it > preco }
            val pix = Perfil.regras.precoNoPix(preco, emPromocao = promo != null) ?: preco
            val estoque = when {
                p.estoque == null -> "disponível"
                p.estoque <= 0 -> "esgotado"
                else -> "${p.estoque} un."
            }
            "${p.nome} | ${dinheiro(preco)}${if (promo != null) " (promoção, de ${dinheiro(promo)})" else ""} | ${dinheiro(pix)} | $estoque | ${p.link?.ifBlank { "sem site: a compra é combinada na conversa" } ?: "$SITE/produto/${p.id}"}"
        }

        /** As categorias do site (id, nome). */
        val CATEGORIAS = listOf(
            "geis_lubrificantes" to "Géis e lubrificantes",
            "excitantes_retardantes" to "Excitantes e retardantes",
            "vibradores" to "Vibradores",
            "masturbadores" to "Masturbadores",
            "fantasias_lingerie" to "Fantasias e lingerie",
            "acessorios_bdsm" to "Acessórios BDSM",
            "kits_casais" to "Kits para casais",
        )

        /** Adivinha a categoria pelo nome do produto (o formulário deixa trocar). */
        fun categoriaProvavel(nome: String): String {
            val n = ComandosLocais.normalizar(nome)
            return when {
                Regex("\\b(gel|geis|lubrificante|oleo|massagem|hidratante)").containsMatchIn(n) -> "geis_lubrificantes"
                Regex("\\b(excitante|retardante|afrodisiaco|spray|esquenta|esfria|vibrante)").containsMatchIn(n) -> "excitantes_retardantes"
                Regex("\\b(vibrador|bullet|protese|estimulador|sugador|varinha|ovo)").containsMatchIn(n) -> "vibradores"
                Regex("\\b(masturbador|anel|egg|lanterna)").containsMatchIn(n) -> "masturbadores"
                Regex("\\b(lingerie|calcinha|sutia|body|camisola|fantasia|conjunto|cinta|meia|baby ?doll|corset|espartilho)").containsMatchIn(n) -> "fantasias_lingerie"
                Regex("\\b(algema|chicote|venda|mordaca|coleira|plug|bdsm|corda|palmatoria)").containsMatchIn(n) -> "acessorios_bdsm"
                Regex("\\b(kit|casal|casais|presente|jogo|dado|baralho)").containsMatchIn(n) -> "kits_casais"
                else -> "geis_lubrificantes"
            }
        }

        /** Corpo do product_save para um produto novo (sem id). */
        fun corpoDoNovo(p: NovoProduto): JSONObject = JSONObject()
            .put("category", p.categoria)
            .put("name", p.nome.trim().take(120))
            .put("description", p.descricao?.trim()?.take(300)?.ifBlank { null } ?: JSONObject.NULL)
            .put("priceCents", p.precoCentavos ?: JSONObject.NULL)
            .put("compareAtCents", JSONObject.NULL)
            .put("specifications", JSONObject.NULL)
            .put("stockQuantity", p.estoque?.coerceAtLeast(0) ?: JSONObject.NULL)
            .put("weightGrams", JSONObject.NULL)
            .put("widthCm", JSONObject.NULL)
            .put("heightCm", JSONObject.NULL)
            .put("lengthCm", JSONObject.NULL)
            .put("imageUrl", p.imagemUrl ?: JSONObject.NULL)
            .put("active", p.ativo)
            .apply { if (p.custoCentavos != null) put(CAMPO_CUSTO, p.custoCentavos) }

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
            if (bruto.has(CAMPO_CUSTO)) corpo.put(CAMPO_CUSTO, bruto.get(CAMPO_CUSTO)) // não apaga o custo ao mudar outra coisa
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
                "custo" -> {
                    if (!bruto.has(CAMPO_CUSTO)) return null // o site ainda não guarda custo
                    corpo.put(CAMPO_CUSTO, valor ?: return null)
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
                "venda" -> {
                    val n = valor ?: return Preparo("Quantas unidades?", null)
                    if (n <= 0) return Preparo("Me diga uma quantidade maior que zero.", null)
                    if (atual != null && n > atual) {
                        return Preparo("${p.nome} só tem ${plural(atual, "unidade", "unidades")} no estoque. Confira a quantidade. Nada foi mudado.", null)
                    }
                    val total = p.precoCentavos?.let { " por ${dinheiro(it * n)}" } ?: ""
                    val resto = if (atual != null) " O estoque fica com ${atual - n}." else ""
                    Preparo("Registrar a venda de $n ${if (n == 1) "unidade" else "unidades"} de ${p.nome}$total?$resto$CONFIRMA", Alteracao("venda", p, valor = n))
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
                "custo" -> {
                    val custo = valor ?: return Preparo("Quanto custou?", null)
                    if (custo <= 0) return Preparo("O custo precisa ser maior que zero.", null)
                    if (p.bruto?.has(CAMPO_CUSTO) != true) return Preparo(SEM_CAMPO_CUSTO, null)
                    val antes = p.custoCentavos
                    var texto = if (antes == null) "Cadastrar o custo de ${p.nome} em ${dinheiro(custo)}?"
                    else "Mudar o custo de ${p.nome} de ${dinheiro(antes)} para ${dinheiro(custo)}?"
                    p.precoCentavos?.let { preco ->
                        texto += if (custo < preco) " Com o preço de ${dinheiro(preco)}, o lucro fica ${dinheiro(preco - custo)} por unidade (${(preco - custo) * 100 / preco}% do preço)."
                        else " Atenção: o custo fica igual ou acima do preço de venda (${dinheiro(preco)})."
                    }
                    Preparo(texto + CONFIRMA, Alteracao("custo", p, valor = custo))
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

        /** Para o "bom dia": o que entrou desde ontem à noite e o que tem para fazer hoje. */
        fun montarResumoDaManha(pedidos: List<Pedido>, produtos: List<Produto>, agora: Calendar): String {
            val desde = (agora.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            val novos = vendidosNoIntervalo(pedidos, desde, Long.MAX_VALUE)
            var texto = if (novos.isEmpty()) "Na loja, nenhuma venda nova desde ontem à noite."
            else "Na loja, desde ontem à noite: ${plural(novos.size, "venda", "vendas")}, ${dinheiro(novos.sumOf { it.totalCentavos })}."
            val separar = pedidos.count { it.status == "paid" || it.status == "preparing" }
            val retirar = pedidos.count { it.entrega == "pickup" && it.status in setOf("paid", "preparing", "shipped") }
            val acabando = produtos.count { it.ativo && it.estoque != null && it.estoque <= ESTOQUE_BAIXO }
            val tarefas = mutableListOf<String>()
            if (separar > 0) tarefas += plural(separar, "pedido para separar", "pedidos para separar")
            if (retirar > 0) tarefas += plural(retirar, "retirada para agendar", "retiradas para agendar")
            if (acabando > 0) tarefas += plural(acabando, "produto acabando ou esgotado", "produtos acabando ou esgotados")
            if (tarefas.isNotEmpty()) texto += " Para hoje: ${tarefas.joinToString(", ")}."
            return texto
        }

        /** Unidades vendidas por dia de cada produto, nos últimos [dias] dias. */
        fun ritmoDeVendas(pedidos: List<Pedido>, produtos: List<Produto>, agora: Calendar, dias: Int = 30): Map<Int, Double> {
            val desde = agora.timeInMillis - dias * 24L * 3600_000
            val porNome = produtos.associateBy { ComandosLocais.normalizar(it.nome.trim()) }
            val total = mutableMapOf<Int, Int>()
            for (o in vendidosNoIntervalo(pedidos, desde, Long.MAX_VALUE)) for (i in o.itens) {
                val p = i.produtoId?.let { id -> produtos.firstOrNull { it.id == id } } ?: porNome[ComandosLocais.normalizar(i.nome.trim())] ?: continue
                total[p.id] = (total[p.id] ?: 0) + i.quantidade
            }
            return total.mapValues { it.value.toDouble() / dias }
        }

        /** Em quantos dias acaba, no ritmo de venda atual, e quanto repor para 30 dias. Sem produto: os que acabam em até 14 dias. */
        fun montarPrevisao(pedidos: List<Pedido>, produtos: List<Produto>, falado: String, agora: Calendar): String {
            val ritmo = ritmoDeVendas(pedidos, produtos, agora)
            fun linha(p: Produto): String {
                val r = ritmo[p.id] ?: 0.0
                val e = p.estoque
                return when {
                    e == null -> "${p.nome}: sem controle de estoque"
                    e <= 0 -> "${p.nome}: já está esgotado" + if (r > 0) "; para 30 dias, repor ${kotlin.math.ceil(r * 30).toInt()}" else ""
                    r == 0.0 -> "${p.nome}: ${plural(e, "unidade", "unidades")} e nenhuma venda nos últimos 30 dias"
                    else -> {
                        val dias = (e / r).toInt()
                        val repor = maxOf(0, kotlin.math.ceil(r * 30).toInt() - e)
                        "${p.nome}: ${plural(e, "unidade", "unidades")}, vende ${"%.1f".format(r * 7).replace('.', ',')} por semana, " +
                            (if (dias <= 0) "acaba a qualquer momento" else "acaba em uns ${plural(dias, "dia", "dias")}") +
                            if (repor > 0) "; para 30 dias, repor $repor" else ""
                    }
                }
            }
            if (falado.isNotBlank()) {
                val achados = acharProdutos(produtos, falado)
                if (achados.isEmpty()) return "Não achei nenhum produto com \"$falado\" na loja."
                return achados.take(3).joinToString(". ") { linha(it) } + "."
            }
            val logo = produtos.filter { it.ativo && it.estoque != null && it.estoque > 0 && (ritmo[it.id] ?: 0.0) > 0 }
                .map { it to it.estoque!! / ritmo[it.id]!! }
                .filter { it.second <= 14 }
                .sortedBy { it.second }
            if (logo.isEmpty()) return "No ritmo atual, nenhum produto acaba nas próximas 2 semanas."
            return "Vão acabar nas próximas 2 semanas: " + logo.take(6).joinToString("; ") { linha(it.first) } + "."
        }

        /** Relatório da semana passada (segunda a domingo) para a notificação de segunda-feira. */
        fun montarRelatorioSemanal(pedidos: List<Pedido>, produtos: List<Produto>, agora: Calendar): String {
            fun zero(c: Calendar) = c.apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val estaSegunda = zero((agora.clone() as Calendar).apply {
                val volta = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                add(Calendar.DAY_OF_YEAR, -volta)
            })
            val ini = (estaSegunda.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
            val antes = (estaSegunda.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -14) }.timeInMillis
            val semana = vendidosNoIntervalo(pedidos, ini, estaSegunda.timeInMillis)
            val anterior = vendidosNoIntervalo(pedidos, antes, ini)
            val total = semana.sumOf { it.totalCentavos }
            var texto = "Semana passada: ${plural(semana.size, "venda", "vendas")}, ${dinheiro(total)}"
            val t0 = anterior.sumOf { it.totalCentavos }
            texto += if (t0 > 0) " (${if (total >= t0) "+" else ""}${((total - t0) * 100L / t0)}% sobre a semana anterior)." else "."
            val top = semana.flatMap { it.itens }.groupBy { it.nome.trim() }.mapValues { (_, v) -> v.sumOf { it.quantidade } }
                .entries.sortedByDescending { it.value }.take(3)
            if (top.isNotEmpty()) texto += " Mais vendidos: " + top.joinToString(", ") { "${it.key} (${it.value})" } + "."
            if (produtos.any { it.bruto?.has(CAMPO_CUSTO) == true }) {
                val l = montarLucro(semana, produtos, "mes", agora)
                if (l.startsWith("Lucro")) texto += " " + l.substringAfter(": ").substringBefore(" em ").let { "Lucro: $it." }
            }
            val prev = montarPrevisao(pedidos, produtos, "", agora)
            if (!prev.startsWith("No ritmo")) texto += " " + prev
            return texto
        }

        /** Pedidos de retirada pagos há mais de [horas] horas que ainda não foram entregues (provavelmente sem agendar). */
        fun retiradasEsquecidas(pedidos: List<Pedido>, agora: Long, horas: Int = 48): List<Pedido> =
            pedidos.filter { it.entrega == "pickup" && it.status in setOf("paid", "preparing") && agora - (it.pago ?: it.criado) > horas * 3600_000L }

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
            val texto = "Olá${if (nome.isNotBlank()) ", $nome" else ""}! Aqui é da ${Perfil.loja}. " +
                "Seu pedido #${p.id} já está separado para retirada em ${Perfil.cidade}. " +
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
