package com.tonimarques.sextafeira

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Trava de segurança: o servidor entrega um selo de licença assinado (Ed25519). Aqui ele é conferido sem internet,
 * com a chave pública embutida no app: editar o selo ou inventar um derruba a assinatura.
 */
object Selo {
    sealed class Estado {
        data class Livre(val teste: Boolean, val fim: Long, val dias: Int) : Estado()
        data class TesteAcabou(val fim: Long) : Estado()
        data class ValidarOnline(val motivo: String) : Estado()
    }

    private const val TOLERANCIA_RELOGIO_MS = 10 * 60_000L
    private const val DIA = 86_400_000L

    private fun deB64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    /** Confere a assinatura e devolve o conteúdo (ou null). Não olha datas. */
    fun ler(texto: String?, chave: String = BuildConfig.SELO_CHAVE): JSONObject? {
        val partes = texto.orEmpty().split(".")
        if (partes.size != 2 || chave.isBlank()) return null
        return try {
            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val pub = EdDSAPublicKey(EdDSAPublicKeySpec(deB64u(chave), spec))
            val motor = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
            motor.initVerify(pub)
            motor.update(partes[0].toByteArray(Charsets.US_ASCII))
            if (!motor.verify(deB64u(partes[1]))) return null
            val c = JSONObject(String(deB64u(partes[0]), Charsets.UTF_8))
            if (c.optInt("v") == 1 && c.has("exp")) c else null
        } catch (e: Exception) {
            null
        }
    }

    /** `visto` é o maior horário já observado neste aparelho: se o relógio voltou, exige falar com o servidor. */
    fun avaliar(texto: String?, agora: Long, visto: Long, usuarioId: String? = null, chave: String = BuildConfig.SELO_CHAVE): Estado {
        if (texto.isNullOrBlank()) return Estado.ValidarOnline("sem_selo")
        val s = ler(texto, chave)
        if (s == null || (!usuarioId.isNullOrBlank() && s.optString("u") != usuarioId)) return Estado.ValidarOnline("invalido")
        if (agora < visto - TOLERANCIA_RELOGIO_MS || agora < s.optLong("iat") - TOLERANCIA_RELOGIO_MS) return Estado.ValidarOnline("relogio")
        val fim = s.optLong("fim")
        if (!s.optBoolean("ac") || (fim > 0 && agora >= fim)) return Estado.TesteAcabou(fim)
        if (agora >= s.optLong("exp")) return Estado.ValidarOnline("vencido")
        return Estado.Livre(s.optBoolean("teste"), fim, maxOf(0L, (fim - agora + DIA - 1) / DIA).toInt())
    }
}
