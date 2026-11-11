package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Os valores esperados vêm do mesmo código no servidor (qr.js), para os dois lados gerarem o mesmo Pix. */
class QrLocalTest {
    @Test fun crcIgualAoDoServidor() = assertEquals("29B1", QrLocal.crc16("123456789"))

    @Test fun pixComValor() = assertEquals(
        "00020101021226360014br.gov.bcb.pix0114+5551999998888520400005303986540549.905802BR5920Desejo de Intimidade6012Porto Alegre62070503***630488FA",
        QrLocal.pixCopiaECola("51999998888", "Desejo de Intimidade", "Porto Alegre", 4990),
    )

    @Test fun pixSemValorEComAcento() = assertEquals(
        "00020101021126380014br.gov.bcb.pix0116loja@exemplo.com5204000053039865802BR5906Loja A6009Sao Paulo62070503***63040F42",
        QrLocal.pixCopiaECola("loja@exemplo.com", "Loja Á", "São Paulo"),
    )

    @Test fun chavesPix() {
        assertEquals("+5551999998888", QrLocal.chavePix("(51) 99999-8888"))
        assertEquals("12345678901", QrLocal.chavePix("12345678901"))
        assertNull(QrLocal.chavePix("abc"))
    }
}
