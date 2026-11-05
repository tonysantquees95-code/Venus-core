package com.tonimarques.sextafeira

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Período de testes para outros lojistas (fase 1.5, sem servidor): 30 dias a partir do primeiro uso.
 * Depois disso, a IA, o estudo, as publicações no Instagram e o robô param até entrar um código de liberação.
 *
 * O código é assinado (ECDSA P-256) por uma chave que fica só no chip de segurança do celular do dono da Vênus
 * (o administrador). O APK traz apenas a chave pública ([CHAVE_PUBLICA]), que confere o código mas não cria códigos:
 * quem abrir o APK não consegue fabricar um. Formato: VENUS-<instalação>-<validade aaaammdd>-<assinatura>.
 *
 * Quem já usava a Vênus antes (a instalação do dono) e o administrador ficam isentos.
 * Limites sem servidor: apagar os dados do app recomeça o teste; e um APK modificado pode tirar a trava.
 */
object Licenca {

    const val DIAS_DE_TESTE = 30
    /** Chave pública do administrador (X.509, base64). Vazia até o dono criar a chave nos ajustes e mandar para embutir. */
    const val CHAVE_PUBLICA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJxSy4bs6oprS9X4ke9cu0JJgq0L39UCjjA9aCh/SYDdelBVC2UfLF8idqbXbKdw7CZlHGYYMFrgiUwK5jDP9ew=="
    const val SEM_PRAZO = "99991231"

    private const val PREFS = "licenca"
    private const val ALIAS = "venus_licencas"
    private const val DIA = 86_400_000L

    sealed class Estado {
        object Isenta : Estado()
        data class Teste(val diasRestantes: Int) : Estado()
        data class Liberada(val ate: String) : Estado()
        object Encerrada : Estado()
    }

    // ------------------------------------------------------------------ estado
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Código curto desta instalação (8 letras e números, sem 0/O/1/I para não confundir ao ditar). */
    fun instalacao(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("id", null)?.let { return it }
        val letras = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        val id = (1..8).map { letras[r.nextInt(letras.length)] }.joinToString("")
        p.edit().putString("id", id).putLong("inicio", System.currentTimeMillis()).apply()
        return id
    }

    fun isenta(ctx: Context): Boolean =
        ctx.getSharedPreferences("perfil", Context.MODE_PRIVATE).getString("origem", null) == "herdado" || ehAdministrador(ctx)

    fun estado(ctx: Context, agora: Long = System.currentTimeMillis()): Estado {
        if (isenta(ctx)) return Estado.Isenta
        val id = instalacao(ctx)
        val p = prefs(ctx)
        // relógio voltado para trás (mais de 2 dias): não conta como dias a menos
        val ultimo = p.getLong("ultimo", 0)
        if (agora > ultimo) p.edit().putLong("ultimo", agora).apply()
        val voltou = ultimo - agora > 2 * DIA
        val hoje = dataDe(agora)
        p.getString("codigo", null)?.let { c ->
            ler(c)?.takeIf { it.instalacao == id && it.validade >= hoje && verificar(c, CHAVE_PUBLICA) && !voltou }?.let { return Estado.Liberada(it.validade) }
        }
        val inicio = p.getLong("inicio", agora)
        val restantes = DIAS_DE_TESTE - ((agora - inicio) / DIA).toInt()
        return if (restantes > 0 && !voltou) Estado.Teste(restantes) else Estado.Encerrada
    }

    fun liberada(ctx: Context): Boolean = estado(ctx) !is Estado.Encerrada

    fun descricao(ctx: Context): String = when (val e = estado(ctx)) {
        Estado.Isenta -> "Sem trava (instalação do dono)."
        is Estado.Teste -> "Período de testes: ${if (e.diasRestantes == 1) "falta 1 dia" else "faltam ${e.diasRestantes} dias"}. Instalação ${instalacao(ctx)}."
        is Estado.Liberada -> if (e.ate == SEM_PRAZO) "Liberada sem prazo." else "Liberada até ${e.ate.substring(6, 8)}/${e.ate.substring(4, 6)}/${e.ate.substring(0, 4)}."
        Estado.Encerrada -> "Período de testes encerrado. Instalação ${instalacao(ctx)}: peça o código de liberação."
    }

    /** Guarda o código se ele for desta instalação, válido e assinado pelo administrador. Devolve o erro ou null. */
    fun aplicarCodigo(ctx: Context, codigo: String): String? {
        val c = codigo.trim().replace(Regex("\\s+"), "")
        val d = ler(c) ?: return "Esse código não está no formato certo. Ele começa com VENUS-."
        if (d.instalacao != instalacao(ctx)) return "Esse código é de outra instalação (${d.instalacao}). O desta é ${instalacao(ctx)}."
        if (d.validade < dataDe(System.currentTimeMillis())) return "Esse código já venceu."
        if (CHAVE_PUBLICA.isBlank()) return "Esta versão ainda não confere códigos. Atualize a ${Marca.nome}."
        if (!verificar(c, CHAVE_PUBLICA)) return "Esse código não é válido."
        prefs(ctx).edit().putString("codigo", c).apply()
        Diario.anotar(ctx, "licenca", "Código de liberação aplicado (até ${d.validade})", "tela")
        return null
    }

    // ------------------------------------------------------------------ código (testável sem Android)
    data class Dados(val instalacao: String, val validade: String, val assinatura: String)

    fun ler(codigo: String): Dados? {
        val m = Regex("^VENUS-([A-Z2-9]{8})-(\\d{8})-([A-Za-z0-9_-]{40,})$").find(codigo.trim()) ?: return null
        return Dados(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    fun mensagem(instalacao: String, validade: String) = "venus-licenca|$instalacao|$validade"

    fun montar(instalacao: String, validade: String, assinatura: ByteArray) =
        "VENUS-$instalacao-$validade-" + Base64.getUrlEncoder().withoutPadding().encodeToString(assinatura)

    fun verificar(codigo: String, chavePublica: String): Boolean = try {
        val d = ler(codigo) ?: throw IllegalArgumentException()
        val chave = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(chavePublica)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(chave)
            update(mensagem(d.instalacao, d.validade).toByteArray())
            verify(Base64.getUrlDecoder().decode(d.assinatura))
        }
    } catch (e: Exception) {
        false
    }

    fun assinar(chavePrivada: PrivateKey, instalacao: String, validade: String): String {
        val s = Signature.getInstance("SHA256withECDSA").run {
            initSign(chavePrivada)
            update(mensagem(instalacao, validade).toByteArray())
            sign()
        }
        return montar(instalacao, validade, s)
    }

    fun dataDe(t: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }.format(Date(t))

    // ------------------------------------------------------------------ administrador (o celular do dono)
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Chave pública do administrador (base64), se ele já criou a chave neste celular. */
    fun chavePublicaAdmin(): String? = try {
        keystore().getCertificate(ALIAS)?.publicKey?.let { Base64.getEncoder().encodeToString(it.encoded) }
    } catch (e: Exception) {
        null
    }

    /** Este celular tem a chave que assina os códigos e ela é a que vai nos APKs. */
    fun ehAdministrador(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = CHAVE_PUBLICA.isNotBlank() && chavePublicaAdmin() == CHAVE_PUBLICA

    /** Cria (uma vez) a chave do administrador no chip de segurança. Devolve a chave pública para embutir no app. */
    fun criarChaveAdmin(ctx: Context): String {
        chavePublicaAdmin()?.let { return it }
        val gerador = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gerador.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gerador.generateKeyPair()
        Diario.anotar(ctx, "licenca", "Chave de administrador das licenças criada", "tela")
        return chavePublicaAdmin() ?: throw IllegalStateException("Não consegui criar a chave.")
    }

    /** Gera o código de liberação de uma instalação, por [dias] a partir de hoje (0 = sem prazo). */
    fun gerarCodigo(ctx: Context, instalacao: String, dias: Int): String {
        val id = instalacao.trim().uppercase()
        require(Regex("^[A-Z2-9]{8}$").matches(id)) { "O código da instalação tem 8 letras e números (ex.: K7M2QX9A)." }
        val privada = keystore().getKey(ALIAS, null) as? PrivateKey ?: throw IllegalStateException("Crie a chave de administrador primeiro.")
        val validade = if (dias <= 0) SEM_PRAZO else dataDe(System.currentTimeMillis() + dias * DIA)
        Diario.anotar(ctx, "licenca", "Código de liberação gerado para $id (até $validade)", "tela")
        return assinar(privada, id, validade)
    }
}
