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
    fun perfilVersao(ctx: Context): Long = prefs(ctx).getLong(PREF_PERFIL_V, -1L)

    /** Puxa nome, foto e assinatura do servidor (o avatar do menu lê daqui). Chamar fora da tela principal. */
    fun atualizarPerfil(ctx: Context): Boolean {
        val r = Nuvem.bruto(ctx, "GET", "/id/conta", null, token(ctx))
        if (!r.ok) return false
        val u = r.json.optJSONObject("usuario") ?: return false
        prefs(ctx).edit().putString(PREF_NOME_CONTA, u.optString("nome")).putString(PREF_FOTO, u.optString("foto"))
            .putLong(PREF_PERFIL_V, u.optLong("atualizado")).putString(PREF_ASSINATURA, textoDaAssinatura(r.json.optJSONObject("licenca"))).apply()
        return true
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
        prefs(ctx).edit().remove(PREF_TOKEN).remove(PREF_SESSAO).remove(PREF_EMAIL).remove(PREF_NOME_CONTA).remove(PREF_FOTO).remove(PREF_PERFIL_V).remove(PREF_ASSINATURA).remove(PREF_VERSOES).remove(PREF_ENVIADO).remove(PREF_DOC_PREFS)
            .apply { if (aviso.isNotBlank()) putString(PREF_AVISO, aviso) }.apply()
    }

    private fun guardar(ctx: Context, j: JSONObject) {
        prefs(ctx).edit().putString(PREF_TOKEN, j.optString("token")).putString(PREF_SESSAO, j.optString("sessao"))
            .putString(PREF_EMAIL, j.optJSONObject("usuario")?.optString("email").orEmpty()).remove(PREF_AVISO).apply()
    }

    private fun aparelho(ctx: Context) = JSONObject().put("id", Nuvem.dispositivo(ctx).ifBlank { "android-${Build.MODEL}-${Build.ID}".padEnd(8, 'x') })
        .put("nome", Build.MODEL.orEmpty().ifBlank { "Android" }).put("plataforma", "android")

    // ------------------------------------------------------------------ rede (chamar fora da tela principal)
    fun entrar(ctx: Context, email: String, senha: String, encerrar: String? = null, remover: String? = null): Nuvem.Resposta {
        val corpo = JSONObject().put("email", email).put("senha", senha).put("dispositivo", aparelho(ctx))
        if (encerrar != null) corpo.put("encerrar", encerrar)
        if (remover != null) corpo.put("remover", remover)
        return Nuvem.bruto(ctx, "POST", "/id/entrar", corpo, null).also { if (it.ok) guardar(ctx, it.json) }
    }

    /** Cria a conta; se este celular já usa a loja (token vl_…), a loja é ligada à conta. */
    fun cadastrar(ctx: Context, email: String, senha: String, nome: String): Nuvem.Resposta {
        val corpo = JSONObject().put("email", email).put("senha", senha).put("nome", nome).put("dispositivo", aparelho(ctx))
        val antigo = Nuvem.tokenDaLoja(ctx).takeIf { it.startsWith("vl_") }
        return Nuvem.bruto(ctx, "POST", "/id/cadastrar", corpo, antigo).also { if (it.ok) guardar(ctx, it.json) }
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

    // Tema compartilhado com o app web/Windows/iPhone: orbe aurora|aneis|prisma, ícones linha|duotone|tecnico, cores {destaque, secundaria}.
    private val ORBE_WEB = listOf("aurora", "prisma", "aneis")      // Líquido, Cristal, Pulso
    private val ICONES_WEB = listOf("duotone", "linha", "tecnico")  // Vênus, Linha, Sólido

    private fun rgbJson(rgb: Int) = JSONObject().put("r", (rgb shr 16) and 0xFF).put("g", (rgb shr 8) and 0xFF).put("b", rgb and 0xFF)
    private fun rgbDe(j: JSONObject?): Int? {
        if (j == null) return null
        val v = listOf("r", "g", "b").map { if (j.has(it)) j.optInt(it, -1) else -1 }
        return if (v.all { it in 0..255 }) (v[0] shl 16) or (v[1] shl 8) or v[2] else null
    }

    fun temaParaJson(modelo: Int, icones: Int, cor: Int): JSONObject {
        val destaque = if (cor == 0) 0xEC6FD6 else cor
        val secundaria = Tema.trio(destaque)[1] and 0xFFFFFF
        return JSONObject().put("orbe", ORBE_WEB.getOrElse(modelo) { "aurora" }).put("icones", ICONES_WEB.getOrElse(icones) { "duotone" })
            .put("cores", JSONObject().put("destaque", rgbJson(destaque)).put("secundaria", rgbJson(secundaria)))
    }

    /** (modelo, ícones, cor) vindos de outro aparelho; null se o documento não for válido. */
    fun temaDoJson(j: JSONObject?): Triple<Int, Int, Int>? {
        if (j == null) return null
        val m = ORBE_WEB.indexOf(j.optString("orbe"))
        val i = ICONES_WEB.indexOf(j.optString("icones"))
        val cor = rgbDe(j.optJSONObject("cores")?.optJSONObject("destaque"))
        return if (m >= 0 && i >= 0 && cor != null) Triple(m, i, cor) else null
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
