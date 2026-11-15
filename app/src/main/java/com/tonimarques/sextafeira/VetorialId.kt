package com.tonimarques.sextafeira

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * Vetorial ID no Android: login único (e-mail e senha) que vale na Vênus, no Windows, no iPhone, no Vindex e no Vortex.
 * Até 3 aparelhos vinculados e 2 usando ao mesmo tempo; o app dá um batimento (ping) a cada minuto.
 */
object VetorialId {
    private const val PREFS = "config"
    const val PREF_TOKEN = "vid_token"
    const val PREF_SESSAO = "vid_sessao"
    const val PREF_EMAIL = "vid_email"
    const val PREF_AVISO = "vid_aviso"
    const val PREF_VERSOES = "vid_versoes"
    const val PREF_ENVIADO = "vid_enviado"
    const val PREF_DOC_PREFS = "vid_doc_preferencias"
    const val PREF_MODO = "modo_uso"
    const val PREF_NOME_CONTA = "vid_nome"
    const val PREF_FOTO = "vid_foto"
    const val PREF_PERFIL_V = "vid_perfil_v"
    const val PREF_ASSINATURA = "vid_assinatura"
    const val PREF_ADMIN = "vid_admin"
    const val PREF_SELO = "vid_selo"
    const val PREF_VISTO = "vid_visto"
    const val PREF_USUARIO_ID = "vid_usuario_id"

    val MODOS = listOf("conversacional", "orbe", "bolha", "silencioso")
    val ROTULOS_MODO = mapOf(
        "conversacional" to "Conversa direta", "orbe" to "Orbe por voz",
        "bolha" to "Bolha flutuante", "silencioso" to "Silencioso (segundo plano)",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun token(ctx: Context): String = prefs(ctx).getString(PREF_TOKEN, "").orEmpty()
    fun nomeDaConta(ctx: Context): String = prefs(ctx).getString(PREF_NOME_CONTA, "").orEmpty()
    fun foto(ctx: Context): String = prefs(ctx).getString(PREF_FOTO, "").orEmpty()
    fun assinatura(ctx: Context): String = prefs(ctx).getString(PREF_ASSINATURA, "").orEmpty()
    /** Só a conta do dono (o servidor diz): libera as telas de escolha de IA, chaves e modelo. */
    fun admin(ctx: Context): Boolean = logado(ctx) && prefs(ctx).getBoolean(PREF_ADMIN, false)
    /** O servidor (batimento) diz se esta conta é a do administrador: atualiza sozinho, sem precisar sair e entrar de novo. */
    fun guardarAdmin(ctx: Context, admin: Boolean) { if (prefs(ctx).getBoolean(PREF_ADMIN, false) != admin) prefs(ctx).edit().putBoolean(PREF_ADMIN, admin).apply() }
    fun perfilVersao(ctx: Context): Long = prefs(ctx).getLong(PREF_PERFIL_V, -1L)

    /** Puxa nome, foto e assinatura do servidor (o avatar do menu lê daqui). Chamar fora da tela principal. */
    fun atualizarPerfil(ctx: Context): Boolean {
        val r = Nuvem.bruto(ctx, "GET", "/id/conta", null, token(ctx))
        if (!r.ok) return false
        val u = r.json.optJSONObject("usuario") ?: return false
        prefs(ctx).edit().putString(PREF_NOME_CONTA, u.optString("nome")).putString(PREF_FOTO, u.optString("foto"))
            .putBoolean(PREF_ADMIN, r.json.optBoolean("admin", false)).putLong(PREF_PERFIL_V, u.optLong("atualizado")).putString(PREF_ASSINATURA, textoDaAssinatura(r.json.optJSONObject("licenca"))).apply()
        return true
    }

    /** Logo depois de entrar: traz o perfil e a marca de administrador (sem isso as telas do dono ficavam escondidas até o próximo ping). */
    private fun puxarPerfilSemErro(ctx: Context) {
        try { atualizarPerfil(ctx) } catch (e: Exception) { /* sem rede agora: o próximo ping tenta de novo */ }
    }

    fun logado(ctx: Context) = token(ctx).isNotBlank()
    fun email(ctx: Context): String = prefs(ctx).getString(PREF_EMAIL, "").orEmpty()
    fun sessao(ctx: Context): String = prefs(ctx).getString(PREF_SESSAO, "").orEmpty()
    fun modo(ctx: Context): String = prefs(ctx).getString(PREF_MODO, "conversacional").orEmpty().let { if (it in MODOS) it else "conversacional" }

    /** Aviso pendente (ex.: sessão encerrada em outro aparelho); lido uma vez. */
    fun pegarAviso(ctx: Context): String {
        val a = prefs(ctx).getString(PREF_AVISO, "").orEmpty()
        if (a.isNotBlank()) prefs(ctx).edit().remove(PREF_AVISO).apply()
        return a
    }

    fun limpar(ctx: Context, aviso: String = "") {
        prefs(ctx).edit().remove(PREF_TOKEN).remove(PREF_SESSAO).remove(PREF_EMAIL).remove(PREF_NOME_CONTA).remove(PREF_FOTO).remove(PREF_PERFIL_V).remove(PREF_ASSINATURA).remove(PREF_ADMIN).remove(PREF_SELO).remove(PREF_USUARIO_ID).remove(PREF_VERSOES).remove(PREF_ENVIADO).remove(PREF_DOC_PREFS)
            .apply { if (aviso.isNotBlank()) putString(PREF_AVISO, aviso) }.apply()
    }

    private fun guardar(ctx: Context, j: JSONObject) {
        prefs(ctx).edit().putString(PREF_TOKEN, j.optString("token")).putString(PREF_SESSAO, j.optString("sessao"))
            .putString(PREF_EMAIL, j.optJSONObject("usuario")?.optString("email").orEmpty())
            .putString(PREF_USUARIO_ID, j.optJSONObject("usuario")?.optString("id").orEmpty()).remove(PREF_AVISO).apply()
        guardarSelo(ctx, j.optString("selo"))
    }

    // ------------------------------------------------------------------ trava de segurança (selo de licença assinado)
    fun guardarSelo(ctx: Context, selo: String?) {
        if (!selo.isNullOrBlank()) prefs(ctx).edit().putString(PREF_SELO, selo).apply()
    }

    /** O que o selo guardado autoriza agora (conferido sem internet). Sem conta ⇒ ValidarOnline("sem_conta"). */
    fun estado(ctx: Context, agora: Long = System.currentTimeMillis()): Selo.Estado {
        if (!logado(ctx)) return Selo.Estado.ValidarOnline("sem_conta")
        val visto = prefs(ctx).getLong(PREF_VISTO, 0L)
        val e = Selo.avaliar(prefs(ctx).getString(PREF_SELO, ""), agora, visto, prefs(ctx).getString(PREF_USUARIO_ID, "").orEmpty())
        if (!(e is Selo.Estado.ValidarOnline && e.motivo == "relogio") && agora > visto) prefs(ctx).edit().putLong(PREF_VISTO, agora).apply()
        return e
    }

    /** Pergunta ao servidor (fora da tela principal): renova o selo. Devolve o novo estado. */
    fun validar(ctx: Context): Selo.Estado {
        val r = licenca(ctx, false)
        if (r.ok) {
            guardarSelo(ctx, r.json.optString("selo"))
            if (perfilVersao(ctx) == -1L) puxarPerfilSemErro(ctx) // conta ainda sem perfil local: traz também o aviso de administrador
            // o servidor confirmou a hora: o relógio deste aparelho volta a valer
            val ag = System.currentTimeMillis()
            if (ag < prefs(ctx).getLong(PREF_VISTO, 0L)) prefs(ctx).edit().putLong(PREF_VISTO, ag).apply()
        } else if (sessaoPerdida(r.status, r.json.optString("codigo"))) limpar(ctx, "Entre de novo com o seu Vetorial ID.")
        return estado(ctx)
    }

    // ------------------------------------------------------------------ recuperação de senha
    fun recuperarCanais(ctx: Context) = Nuvem.bruto(ctx, "GET", "/id/recuperar/canais", null, null)
    fun recuperarPedir(ctx: Context, email: String, canal: String) = Nuvem.bruto(ctx, "POST", "/id/recuperar/pedir", JSONObject().put("email", email).put("canal", canal), null)
    fun recuperarConfirmar(ctx: Context, email: String, codigo: String, novaSenha: String) =
        Nuvem.bruto(ctx, "POST", "/id/recuperar/confirmar", JSONObject().put("email", email).put("codigo", codigo).put("novaSenha", novaSenha), null)

    private fun aparelho(ctx: Context) = JSONObject().put("id", Nuvem.dispositivo(ctx).ifBlank { "android-${Build.MODEL}-${Build.ID}".padEnd(8, 'x') })
        .put("nome", Build.MODEL.orEmpty().ifBlank { "Android" }).put("plataforma", "android")

    // ------------------------------------------------------------------ rede (chamar fora da tela principal)
    fun entrar(ctx: Context, email: String, senha: String, encerrar: String? = null, remover: String? = null): Nuvem.Resposta {
        val corpo = JSONObject().put("email", email).put("senha", senha).put("dispositivo", aparelho(ctx))
        if (encerrar != null) corpo.put("encerrar", encerrar)
        if (remover != null) corpo.put("remover", remover)
        return Nuvem.bruto(ctx, "POST", "/id/entrar", corpo, null).also { if (it.ok) { guardar(ctx, it.json); puxarPerfilSemErro(ctx) } }
    }

    /** Cria a conta; se este celular já usa a loja (token vl_…), a loja é ligada à conta. */
    fun cadastrar(ctx: Context, email: String, senha: String, nome: String, telefone: String = ""): Nuvem.Resposta {
        val corpo = JSONObject().put("email", email).put("senha", senha).put("nome", nome).put("telefone", telefone).put("dispositivo", aparelho(ctx))
        val antigo = Nuvem.tokenDaLoja(ctx).takeIf { it.startsWith("vl_") }
        return Nuvem.bruto(ctx, "POST", "/id/cadastrar", corpo, antigo).also { if (it.ok) { guardar(ctx, it.json); puxarPerfilSemErro(ctx) } }
    }

    fun ping(ctx: Context) = Nuvem.bruto(ctx, "POST", "/id/ping", JSONObject(), token(ctx))
    fun dispositivos(ctx: Context) = Nuvem.bruto(ctx, "GET", "/id/dispositivos", null, token(ctx))
    fun encerrarSessao(ctx: Context, sessao: String) = Nuvem.bruto(ctx, "POST", "/id/sessoes/$sessao/encerrar", JSONObject(), token(ctx))
    fun removerDispositivo(ctx: Context, id: String) = Nuvem.bruto(ctx, "DELETE", "/id/dispositivos/$id", null, token(ctx))
    fun reativar(ctx: Context, encerrar: String) = Nuvem.bruto(ctx, "POST", "/id/reativar", JSONObject().put("encerrar", encerrar), token(ctx))
    fun licenca(ctx: Context, gerar: Boolean) = Nuvem.bruto(ctx, "GET", "/id/licenca" + if (gerar) "?gerar=1" else "", null, token(ctx))
    fun sair(ctx: Context) {
        try { Nuvem.bruto(ctx, "POST", "/id/sair", JSONObject(), token(ctx)) } catch (e: Exception) { }
        limpar(ctx)
    }

    // ------------------------------------------------------------------ regras puras (testadas)
    fun sessaoPerdida(status: Int, codigo: String) = status == 401 && codigo in setOf("sessao_encerrada", "sessao_inexistente", "token_invalido")

    // Tema compartilhado com o app web/Windows/iPhone: o mesmo do celular (cinco movimentos e cinco pacotes de ícones).
    private val ORBE_WEB = listOf("respirar", "giro", "batida", "onda", "flutuar")
    private val ICONES_WEB = listOf("venus", "linha", "tecnico", "neon", "duotone")
    // nomes antigos (versões do Windows anteriores à 1.19) ainda são entendidos
    private val ORBE_ANTIGO = listOf("aurora", "prisma", "aneis")
    private val ICONES_ANTIGO = listOf("duotone", "linha", "tecnico")

    private fun rgbJson(rgb: Int) = JSONObject().put("r", (rgb shr 16) and 0xFF).put("g", (rgb shr 8) and 0xFF).put("b", rgb and 0xFF)
    private fun rgbDe(j: JSONObject?): Int? {
        if (j == null) return null
        val v = listOf("r", "g", "b").map { if (j.has(it)) j.optInt(it, -1) else -1 }
        return if (v.all { it in 0..255 }) (v[0] shl 16) or (v[1] shl 8) or v[2] else null
    }

    fun temaParaJson(modelo: Int, icones: Int, cor: Int): JSONObject {
        val destaque = if (cor == 0) 0xE36BD0 else cor
        val secundaria = Tema.trio(destaque)[1] and 0xFFFFFF
        return JSONObject().put("orbe", ORBE_WEB.getOrElse(modelo) { "respirar" }).put("icones", ICONES_WEB.getOrElse(icones) { "venus" })
            .put("auto", cor == 0)
            .put("cores", JSONObject().put("destaque", rgbJson(destaque)).put("secundaria", rgbJson(secundaria)))
    }

    /** (modelo, ícones, cor) vindos de outro aparelho; null se o documento não for válido. */
    fun temaDoJson(j: JSONObject?): Triple<Int, Int, Int>? {
        if (j == null) return null
        val novo = j.has("auto") // documentos novos usam os nomes do celular; os antigos, os nomes do primeiro Windows
        val m = ORBE_WEB.indexOf(j.optString("orbe")).takeIf { it >= 0 } ?: ORBE_ANTIGO.indexOf(j.optString("orbe"))
        val i = (if (novo) ICONES_WEB else ICONES_ANTIGO).indexOf(j.optString("icones")).takeIf { it >= 0 } ?: ICONES_WEB.indexOf(j.optString("icones"))
        val cor = rgbDe(j.optJSONObject("cores")?.optJSONObject("destaque"))
        return if (m >= 0 && i >= 0 && cor != null) Triple(m, i, if (j.optBoolean("auto", false)) 0 else cor) else null
    }

    /** O que fazer com um documento que chegou do servidor. */
    fun deveAplicar(versaoRemota: Int, versaoConhecida: Int) = versaoRemota > versaoConhecida

    /** Preferências compartilhadas: mexe só nas chaves que o Android conhece e preserva as do web. */
    fun mesclarPreferencias(base: JSONObject?, modo: String, acionarPeloNome: Boolean, falar: Boolean): JSONObject {
        val j = JSONObject(base?.toString() ?: "{}")
        return j.put("modo", modo).put("acionarPeloNome", acionarPeloNome).put("falarRespostas", falar)
    }

    fun lerPreferencias(j: JSONObject?): Triple<String, Boolean, Boolean>? {
        if (j == null) return null
        val modo = j.optString("modo")
        if (modo !in MODOS) return null
        return Triple(modo, j.optBoolean("acionarPeloNome", true), j.optBoolean("falarRespostas", true))
    }
}

/** "há 5 min", "há 3 h", "há 2 d" (lista de dispositivos). */
fun haQuantoTempo(quando: Long, agora: Long = System.currentTimeMillis()): String {
    val s = ((agora - quando) / 1000).coerceAtLeast(0)
    return when {
        s < 90 -> "agora"
        s < 3600 -> "há ${(s + 30) / 60} min"
        s < 86_400 -> "há ${(s + 1800) / 3600} h"
        else -> "há ${(s + 43_200) / 86_400} d"
    }
}

/** "TM" para Toni Marques; sem nome, a primeira letra do e-mail. */
fun iniciaisDoNome(nome: String, email: String = ""): String {
    val p = nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        p.size >= 2 -> "${p.first().first()}${p.last().first()}".uppercase()
        p.size == 1 -> p[0].take(2).uppercase()
        else -> (email.trim().firstOrNull() ?: '?').uppercase()
    }
}

/** Texto do status da assinatura (o mesmo do app web). [l] é o objeto "licenca" devolvido por /id/conta. */
fun textoDaAssinatura(l: JSONObject?): String {
    if (l == null) return "Assinatura indisponível"
    fun dias(n: Int) = "$n dia${if (n == 1) "" else "s"} restante${if (n == 1) "" else "s"}"
    return when {
        !l.optBoolean("acesso") -> "Acesso vencido · renove o Plano Fundadores"
        l.optBoolean("emTeste") -> "Plano Fundadores · teste grátis, ${dias(l.optInt("diasDeTesteRestantes"))}"
        else -> "Plano Fundadores · ${dias(l.optInt("diasPagosRestantes"))}"
    }
}

/** Qual app da suíte está pedindo para entrar com a conta do aparelho (pelo pacote que chamou); null = não autorizado. */
fun appDaSuite(pacote: String?): String? = mapOf(
    "tech.vetorial.vindex" to "vindex", "tech.vetorial.vortex" to "vortex",
    "com.vetorialtech.venus.doisemum" to "venus", "com.vetorialtech.venus.assistente" to "venus",
    "tech.vetorial.portal" to "portal",
)[pacote]
