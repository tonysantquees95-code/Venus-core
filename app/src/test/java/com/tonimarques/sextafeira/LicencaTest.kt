package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class LicencaTest {
    private val par = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publica = Base64.getEncoder().encodeToString(par.public.encoded)

    @Test fun codigoAssinadoConfere() {
        val c = Licenca.assinar(par.private, "K7M2QX9A", "20261231")
        assertTrue(c.startsWith("VENUS-K7M2QX9A-20261231-"))
        assertTrue(Licenca.verificar(c, publica))
        assertEquals("K7M2QX9A", Licenca.ler(c)!!.instalacao)
    }

    @Test fun codigoAdulteradoNaoPassa() {
        val c = Licenca.assinar(par.private, "K7M2QX9A", "20261231")
        // trocar a validade ou a instalação invalida a assinatura
        assertFalse(Licenca.verificar(c.replace("20261231", "20991231"), publica))
        assertFalse(Licenca.verificar(c.replace("K7M2QX9A", "K7M2QX9B"), publica))
        // assinado por outra chave
        val outra = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertFalse(Licenca.verificar(Licenca.assinar(outra.private, "K7M2QX9A", "20261231"), publica))
        assertFalse(Licenca.verificar(c, ""))
    }

    @Test fun formato() {
        assertNull(Licenca.ler("VENUS-123-20261231-abc"))
        assertNull(Licenca.ler("qualquer coisa"))
        assertEquals("20251105", Licenca.dataDe(1762349400000L))
    }
}
