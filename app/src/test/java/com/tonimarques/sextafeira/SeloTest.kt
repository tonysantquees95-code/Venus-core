package com.tonimarques.sextafeira

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SeloTest {
    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private fun par(semente: Int): Pair<EdDSAPrivateKeySpec, String> {
        val priv = EdDSAPrivateKeySpec(ByteArray(32) { (it + semente).toByte() }, spec)
        return priv to Base64.getUrlEncoder().withoutPadding().encodeToString(priv.a.toByteArray())
    }
    private val parA = par(1)
    private val privada = parA.first
    private val chave = parA.second
    private val dia = 86_400_000L
    private val t = 1_800_000_000_000L

    private fun selo(campos: Map<String, Any>, k: EdDSAPrivateKeySpec = privada): String {
        val j = JSONObject().put("v", 1).put("u", "u1").put("ac", true).put("teste", true).put("fim", 0L).put("iat", 0L).put("exp", 0L)
        for ((a, b) in campos) j.put(a, b)
        val p = Base64.getUrlEncoder().withoutPadding().encodeToString(j.toString().toByteArray())
        val m = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm)); m.initSign(net.i2p.crypto.eddsa.EdDSAPrivateKey(k)); m.update(p.toByteArray(Charsets.US_ASCII))
        return p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(m.sign())
    }

    @Test fun seloLegitimoLiberaEContaOsDias() {
        val e = Selo.avaliar(selo(mapOf("iat" to t, "exp" to t + 3 * dia, "fim" to t + 5 * dia)), t + 1000, t, "u1", chave)
        assertTrue(e is Selo.Estado.Livre)
        assertEquals(5, (e as Selo.Estado.Livre).dias)
    }

    @Test fun seloAdulteradoOuDeOutraChaveERecusado() {
        val bom = selo(mapOf("iat" to t, "exp" to t + dia, "fim" to t + dia))
        val (p, a) = bom.split(".")
        val falso = JSONObject(String(Base64.getUrlDecoder().decode(p))).put("fim", t + 400 * dia)
        assertNull(Selo.ler(Base64.getUrlEncoder().withoutPadding().encodeToString(falso.toString().toByteArray()) + "." + a, chave))
        assertTrue(Selo.avaliar(selo(mapOf("iat" to t, "exp" to t + dia), par(7).first), t, t, "u1", chave) is Selo.Estado.ValidarOnline)
        assertEquals("sem_selo", (Selo.avaliar(null, t, t, "u1", chave) as Selo.Estado.ValidarOnline).motivo)
        assertEquals("invalido", (Selo.avaliar(selo(mapOf("u" to "outro", "iat" to t, "exp" to t + dia)), t, t, "u1", chave) as Selo.Estado.ValidarOnline).motivo)
    }

    @Test fun fimDoTesteBloqueiaMesmoComSeloNaValidade() {
        val s = selo(mapOf("iat" to t, "exp" to t + 3 * dia, "fim" to t + dia))
        assertTrue(Selo.avaliar(s, t + dia + 1, t, "u1", chave) is Selo.Estado.TesteAcabou)
        assertTrue(Selo.avaliar(selo(mapOf("ac" to false, "iat" to t, "exp" to t + dia)), t, t, "u1", chave) is Selo.Estado.TesteAcabou)
    }

    @Test fun muitoTempoOfflineOuRelogioVoltadoPedemValidacao() {
        val s = selo(mapOf("iat" to t, "exp" to t + 3 * dia, "fim" to t + 30 * dia))
        assertEquals("vencido", (Selo.avaliar(s, t + 4 * dia, t, "u1", chave) as Selo.Estado.ValidarOnline).motivo)
        assertEquals("relogio", (Selo.avaliar(s, t + dia, t + 2 * dia, "u1", chave) as Selo.Estado.ValidarOnline).motivo)
    }
}
