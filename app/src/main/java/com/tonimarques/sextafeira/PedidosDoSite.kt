package com.tonimarques.sextafeira

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pedidos da página Publicações do site (a Natália no iPhone): "gerar outro story", "programar post" e
 * "programar story" de uma ideia. A Vênus confere a cada 2 minutos com um alarme exato que se reagenda;
 * a VigiaDaLoja (15 min) e a abertura do app servem de rede de segurança. Com o celular parado há muito
 * tempo (modo soneca), o Android pode espaçar os alarmes para uns 9 a 15 minutos.
 * Também manda para o site as ideias de post (quando mudam), para aparecerem na página.
 */
object PedidosDoSite {

    const val ACAO = "com.tonimarques.sextafeira.PEDIDOS_DO_SITE"
    const val INTERVALO_MS = 2 * 60_000L
    private const val PREFS = "pedidos_do_site"
    /** Sem o canal de aviso: pergunta ao site a cada 10 min de dia (8h às 23h) e a cada 1 h de noite. */
    private const val SITE_DIA_MS = 10 * 60_000L
    private const val SITE_NOITE_MS = 60 * 60_000L
    /** Com o canal de aviso: o site só é consultado quando chega aviso, ou a cada 30 min como rede de segurança. */
    private const val SITE_SEGURANCA_MS = 30 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** O canal de aviso (tópico do ntfy.sh) que o site manda junto com os pedidos. */
    fun canal(ctx: Context): String? = prefs(ctx).getString("canal", null)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,64}")) }

    fun guardarCanal(ctx: Context, canal: String?) {
        if (canal == null || canal == canal(ctx)) return
        prefs(ctx).edit().putString("canal", canal).remove("canal_ultimo").apply()
    }

    private fun deDia(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 8..22

    /** De quanto em quanto tempo o alarme toca: com canal, a cada 2 min (só olha o ntfy, que não usa o site); sem, 10 min de dia. */
    fun intervalo(ctx: Context): Long = when {
        canal(ctx) != null -> if (deDia()) INTERVALO_MS else 10 * 60_000L
        deDia() -> SITE_DIA_MS
        else -> SITE_NOITE_MS
    }

    /**
     * Olha o canal de aviso (ntfy.sh, grátis, fora do site). Devolve os avisos novos ("publicacao", "pago"...),
     * ou null se não deu para olhar (aí vale a regra do tempo).
     */
    fun avisosNovos(ctx: Context): List<String>? {
        val canal = canal(ctx) ?: return null
        val p = prefs(ctx)
        val desde = p.getString("canal_ultimo", null) ?: "30m"
        val c = java.net.URL("https://ntfy.sh/$canal/json?poll=1&since=" + java.net.URLEncoder.encode(desde, "UTF-8")).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            if (c.responseCode !in 200..299) return null
            val linhas = c.inputStream.bufferedReader().use { it.readLines() }
            val msgs = linhas.mapNotNull { l -> try { JSONObject(l) } catch (e: Exception) { null } }.filter { it.optString("event") == "message" }
            msgs.lastOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { p.edit().putString("canal_ultimo", it).apply() }
            if (msgs.isEmpty() && p.getString("canal_ultimo", null) == null) p.edit().putString("canal_ultimo", (System.currentTimeMillis() / 1000).toString()).apply()
            msgs.map { it.optString("message") }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    data class Pedido(
        val id: Int, val tipo: String, val turno: String?, val ideiaRef: String?, val ideiaTitulo: String?,
        val quando: Long?, val legenda: String?,
    )

    /** Só vale para a loja no Floot, conectada, com a licença liberada. */
    fun ativo(ctx: Context): Boolean = try {
        Plataformas.tipo(ctx) == "floot" && Loja(ctx).conectada && Licenca.liberada(ctx)
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ agendamento (corrente de alarmes de 2 min)
    private fun alarme(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 74_600, Intent(ctx, AlarmeDosPedidos::class.java).setAction(ACAO), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun agendar(ctx: Context, daqui: Long = intervalo(ctx)) {
        if (!ativo(ctx)) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val quando = System.currentTimeMillis() + daqui
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, alarme(ctx))
        }
    }

    /** Confere agora (fora da thread principal) e garante o próximo alarme. */
    fun conferirEmSegundoPlano(ctx: Context, forcar: Boolean = false) {
        val app = ctx.applicationContext
        Thread {
            try {
                conferir(app, forcar)
            } catch (e: Exception) {
            }
        }.start()
    }

    private val trava = Any()

    /**
     * Bloqueante: busca os pedidos, faz cada um e responde ao site. Também sincroniza as ideias de post.
     * Para usar pouco o site: com o canal de aviso, só vai ao site quando chega aviso (ou a cada 30 min);
     * sem ele, no máximo a cada 10 min de dia e 1 h de noite. [forcar] = abrir o app.
     */
    fun conferir(ctx: Context, forcar: Boolean = false) {
        if (!ativo(ctx)) return
        agendar(ctx)
        val agora = System.currentTimeMillis()
        val p = prefs(ctx)
        val ultimo = p.getLong("ultimo_site", 0L)
        val avisos = avisosNovos(ctx)
        // aviso de pedido pago: confere os pedidos da loja na hora (avisos no celular)
        if (avisos?.any { it.startsWith("pago") } == true) {
            CacheDoSite.invalidar()
            try {
                VigiaDaLoja.verificar(ctx, forcar = true)
            } catch (e: Exception) {
            }
        }
        // abrir o app conta como pedido de conferência, mas no máximo uma vez a cada 2 minutos
        val precisa = (forcar && agora - ultimo >= INTERVALO_MS) || when {
            avisos != null -> avisos.any { it.startsWith("publicacao") } || agora - ultimo >= SITE_SEGURANCA_MS
            else -> agora - ultimo >= (if (deDia()) SITE_DIA_MS else SITE_NOITE_MS) - 30_000L
        }
        if (!precisa) return
        p.edit().putLong("ultimo_site", agora).apply()
        synchronized(trava) {
            val loja = Loja(ctx)
            try {
                enviarIdeiasSeMudaram(ctx, loja)
            } catch (e: Exception) {
            }
            val pedidos = loja.pedidosDePublicacao()
            for (p in pedidos) executar(ctx, loja, p)
        }
    }

    // ------------------------------------------------------------------ execução
    fun executar(ctx: Context, loja: Loja, p: Pedido) {
        val r = try {
            when (p.tipo) {
                "story_outro" -> outroStory(ctx, loja, p.turno ?: "tarde")
                "post", "story" -> programarIdeia(ctx, loja, p)
                else -> Resultado(false, "Não sei fazer esse pedido (${p.tipo}). Atualize a Vênus app.", null)
            }
        } catch (e: Exception) {
            Resultado(false, "Não consegui: ${e.message ?: "erro"}", null)
        }
        Diario.anotar(ctx, "instagram", "Pedido do site: ${descricao(p)} — ${r.texto}", "site", r.ok)
        try {
            loja.responderPedidoDePublicacao(p.id, r.ok, r.texto, r.imagemUrl)
        } catch (e: Exception) {
            // o site não recebeu: depois de 15 min ele devolve o pedido para a fila e a Vênus tenta de novo
        }
    }

    data class Resultado(val ok: Boolean, val texto: String, val imagemUrl: String?)

    fun descricao(p: Pedido): String = when (p.tipo) {
        "story_outro" -> "outro story da ${StoriesDoDia.nomeTurno(p.turno ?: "tarde")}"
        "story" -> "programar story \"${p.ideiaTitulo.orEmpty()}\""
        else -> "programar post \"${p.ideiaTitulo.orEmpty()}\""
    }

    private fun outroStory(ctx: Context, loja: Loja, turno: String): Resultado {
        val s = StoriesDoDia.refazer(ctx, turno)
        val url = loja.enviarFoto(s.arquivo.readBytes())
        val enquete = s.pergunta.isNotBlank()
        loja.registrarStory(turno, s.titulo, s.texto, s.pergunta.ifBlank { null }, url, enquete, publicado = false)
        return Resultado(true, "Story novo da ${StoriesDoDia.nomeTurno(turno)}: ${s.titulo}. Já está na página para publicar.", url)
    }

    private fun programarIdeia(ctx: Context, loja: Loja, p: Pedido): Resultado {
        val id = p.ideiaRef?.toLongOrNull() ?: return Resultado(false, "A ideia não veio certa.", null)
        val quando = p.quando ?: return Resultado(false, "Faltou o horário.", null)
        if (quando < System.currentTimeMillis()) return Resultado(false, "O horário escolhido já passou. Peça de novo com outro horário.", null)
        val item = Memoria(ctx).itensDoTipo("ideia_post", 80).firstOrNull { it.id == id }
            ?: return Resultado(false, "Essa ideia não está mais na Vênus (o estudo da semana trocou as ideias).", null)
        val ideia = IdeiasDePost.ler(item)
        val tipo = if (p.tipo == "story") "story" else "feed"
        val produto = try {
            IdeiasDePost.produtoDaIdeia(ideia, loja.produtos())
        } catch (e: Exception) {
            null
        }
        val foto = produto?.let { IdeiasDePost.fotoDoProduto(it) }
        val jpeg = IdeiasDePost.arte(ideia, tipo, foto)
        val legenda = p.legenda?.takeIf { it.isNotBlank() } ?: ideia.legenda
        val post = Postagens.criar(ctx, quando, tipo, legenda, jpeg)
        val url = try {
            loja.enviarFoto(jpeg)
        } catch (e: Exception) {
            null
        }
        val hora = SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(quando)
        val como = if (Instagram.conectado(ctx)) "sai sozinho" else "na hora chega o aviso no celular do Toni para postar com um toque"
        Diario.anotar(ctx, "instagram", "Ideia programada pelo site como ${if (tipo == "story") "story" else "post"}: ${ideia.titulo} ($hora, post ${post.id})", "site")
        return Resultado(true, "Programado para $hora${if (produto != null) ", com a foto de ${produto.nome}" else ""}: $como.", url)
    }

    // ------------------------------------------------------------------ ideias de post para o site
    private fun enviarIdeiasSeMudaram(ctx: Context, loja: Loja) {
        val ideias = Memoria(ctx).itensDoTipo("ideia_post", Estudo.IDEIAS_POR_SEMANA * 2)
        val lista = JSONArray()
        for (item in ideias) lista.put(ideiaParaSite(item.id, IdeiasDePost.ler(item)))
        val marca = lista.toString().hashCode().toString()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("ideias_enviadas", null) == marca) return
        loja.enviarIdeias(lista)
        prefs.edit().putString("ideias_enviadas", marca).apply()
        Diario.anotar(ctx, "instagram", "Ideias de post enviadas ao site (${lista.length()})", "automatico")
    }

    /** Uma ideia no formato do site (data aaaa-mm-dd ou null). */
    fun ideiaParaSite(id: Long, i: IdeiasDePost.Ideia): JSONObject = JSONObject()
        .put("ref", id.toString())
        .put("titulo", i.titulo.take(200).ifBlank { "Ideia de post" })
        .put("data", i.data?.let { "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) } ?: JSONObject.NULL)
        .put("formato", i.formato)
        .put("legenda", i.legenda.take(2200))

    /** Lê os pedidos que o site devolveu (formato superjson: datas em "meta"). */
    fun lerPedidos(json: JSONObject): List<Pedido> {
        val lista = json.optJSONArray("pedidos") ?: return emptyList()
        return (0 until lista.length()).mapNotNull { i ->
            val o = lista.optJSONObject(i) ?: return@mapNotNull null
            Pedido(
                id = o.optInt("id"),
                tipo = o.optString("tipo"),
                turno = o.optString("turno").takeIf { it.isNotBlank() && it != "null" },
                ideiaRef = o.optString("ideiaRef").takeIf { it.isNotBlank() && it != "null" },
                ideiaTitulo = o.optString("ideiaTitulo").takeIf { it.isNotBlank() && it != "null" },
                quando = o.optString("quando").takeIf { it.isNotBlank() && it != "null" }?.let { dataIso(it) },
                legenda = o.optString("legenda").takeIf { it != "null" },
            )
        }.filter { it.id > 0 }
    }

    /** "2026-11-06T22:00:00.000Z" → milissegundos (null se não der). */
    fun dataIso(t: String): Long? = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        f.parse(t.take(19))?.time
    } catch (e: Exception) {
        null
    }
}

/** O alarme de 2 minutos (e o reinício depois de ligar o celular ou atualizar o app). */
class AlarmeDosPedidos : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PedidosDoSite.agendar(ctx, 30_000L)
            PedidosDoSite.ACAO -> {
                val pendente = goAsync()
                Thread {
                    try {
                        PedidosDoSite.conferir(ctx.applicationContext)
                    } catch (e: Exception) {
                        PedidosDoSite.agendar(ctx.applicationContext)
                    } finally {
                        pendente.finish()
                    }
                }.start()
            }
        }
    }
}
