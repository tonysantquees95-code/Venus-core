package com.tonimarques.sextafeira

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.Normalizer

/**
 * QR Code e Pix copia-e-cola gerados no próprio celular: o cartão, o panfleto e os QR da Central da loja
 * funcionam mesmo sem servidor ou sem login. [modulos] tem o mesmo formato do servidor ("0101…", linha por linha).
 */
object QrLocal {
    class Matriz(val tamanho: Int, val modulos: String)

    fun matriz(texto: String): Matriz {
        require(texto.isNotBlank() && texto.length <= 1000) { "O conteúdo do QR Code deve ter de 1 a 1000 caracteres." }
        val dicas = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, 0, 0, dicas)
        val n = m.width
        val sb = StringBuilder(n * n)
        for (y in 0 until n) for (x in 0 until n) sb.append(if (m.get(x, y)) '1' else '0')
        return Matriz(n, sb.toString())
    }

    // ------------------------------------------------------------------ Pix (BR Code estático), igual ao do servidor
    fun crc16(texto: String): String {
        var crc = 0xFFFF
        for (b in texto.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return "%04X".format(crc)
    }

    private fun campo(id: String, valor: String) = id + valor.length.toString().padStart(2, '0') + valor

    private fun semAcento(t: String?, max: Int): String =
        Normalizer.normalize(t.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^A-Za-z0-9 .\\-]"), "").trim().take(max)

    /** CPF/CNPJ (só números), celular (+55…), e-mail ou chave aleatória; null se não for uma chave. */
    fun chavePix(chave: String): String? {
        val c = chave.trim()
        if (Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(c)) return c.lowercase()
        if (Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE).matches(c)) return c.lowercase()
        val d = c.filter { it.isDigit() }
        if (c.startsWith("+") && d.length >= 12) return "+$d"
        if (d.length == 11 && Regex("^[1-9]{2}9").containsMatchIn(d) && !Regex("^(\\d)\\1{10}$").matches(d)) return "+55$d"
        if (d.length == 11 || d.length == 14) return d
        return null
    }

    fun pixCopiaECola(chave: String, nome: String, cidade: String, valorCentavos: Long? = null, descricao: String? = null): String {
        val k = chavePix(chave) ?: throw IllegalArgumentException("Chave Pix inválida. Use CPF, CNPJ, celular com DDD, e-mail ou chave aleatória.")
        val conta = campo("00", "br.gov.bcb.pix") + campo("01", k) + (if (!descricao.isNullOrBlank()) campo("02", semAcento(descricao, 40)) else "")
        val valor = if (valorCentavos != null && valorCentavos > 0) "%d.%02d".format(valorCentavos / 100, valorCentavos % 100) else ""
        val corpo = campo("00", "01") + campo("01", if (valor.isNotEmpty()) "12" else "11") + campo("26", conta) + campo("52", "0000") + campo("53", "986") +
            (if (valor.isNotEmpty()) campo("54", valor) else "") + campo("58", "BR") + campo("59", semAcento(nome, 25).ifBlank { "LOJA" }) +
            campo("60", semAcento(cidade, 15).ifBlank { "BRASIL" }) + campo("62", campo("05", "***")) + "6304"
        return corpo + crc16(corpo)
    }
}
