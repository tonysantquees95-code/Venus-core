package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArquivosTest {
    private fun zip(vararg arquivos: Pair<String, String>): ByteArray {
        val saida = ByteArrayOutputStream()
        ZipOutputStream(saida).use { z ->
            for ((nome, conteudo) in arquivos) {
                z.putNextEntry(ZipEntry(nome))
                z.write(conteudo.toByteArray())
                z.closeEntry()
            }
        }
        return saida.toByteArray()
    }

    @Test fun word() {
        val docx = zip("word/document.xml" to """<w:document><w:body><w:p><w:r><w:t>Contrato de fornecimento</w:t></w:r></w:p>
            <w:p><w:r><w:t>Multa de 10% &amp; juros</w:t></w:r></w:p></w:body></w:document>""")
        val t = Arquivos.textoDoWord(docx)
        assertTrue(t, t.contains("Contrato de fornecimento\n"))
        assertTrue(t, t.contains("Multa de 10% & juros"))
        val d = Arquivos.montar("contrato.docx", Arquivos.tipo(null, "contrato.docx"), docx)
        assertNull(d.bytes)
        assertEquals("documento", d.tipoFalado)
    }

    @Test fun excel() {
        val xlsx = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Vendas" sheetId="1"/></sheets></workbook>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Produto</t></si><si><t>Qtd</t></si><si><t>Gel KGEL</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>5</v></c></row></sheetData></worksheet>""",
        )
        val t = Arquivos.textoDoExcel(xlsx)
        assertTrue(t, t.startsWith("Aba Vendas:\nProduto ; Qtd\nGel KGEL ; 5"))
    }

    @Test fun powerPoint() {
        val pptx = zip("ppt/slides/slide2.xml" to "<p:sld><a:t>Segundo</a:t></p:sld>", "ppt/slides/slide1.xml" to "<p:sld><a:t>Primeiro</a:t><a:t>slide</a:t></p:sld>")
        assertEquals("Slide 1: Primeiro slide\nSlide 2: Segundo", Arquivos.textoDoPowerPoint(pptx))
    }

    @Test fun tipos() {
        assertEquals("application/pdf", Arquivos.tipo("application/octet-stream", "Boleto.PDF"))
        assertEquals("audio/ogg", Arquivos.tipo(null, "PTT-20261031.opus"))
        val audio = Arquivos.montar("x.opus", "audio/opus", byteArrayOf(1, 2))
        assertEquals("audio/ogg", audio.mime)
        assertEquals("áudio", audio.tipoFalado)
        val html = Arquivos.montar("p.html", "text/html", "<html><style>x{}</style><p>Olá &amp; tchau</p></html>".toByteArray())
        assertEquals("Olá & tchau", html.texto)
    }

    @Test fun analise() {
        val a = Gemini.lerAnalise("""{"tipo":"boleto","resumo":"Boleto da Enel de R$ 187,40.","vencimento":"2026-11-10","valor":187.4,
            "linha_digitavel":"83640000001 8 74000123456 7 89012345678 9 01234567890 1"}""")!!
        assertEquals("boleto", a.tipo)
        assertEquals("2026-11-10", a.vencimento)
        assertEquals(18740, a.valorCentavos)
        assertTrue(a.linhaDigitavel!!.length in 44..48)
        assertEquals("outro", Gemini.lerAnalise("Só um texto solto")!!.tipo)
        assertNull(Gemini.lerAnalise("""{"tipo":"outro","resumo":""}"""))
    }

    @Test fun comando() {
        assertEquals("abrir_arquivo", (ComandosLocais.interpretar("abre um arquivo") as ComandosLocais.Resultado.Acao).nome)
        assertEquals("abrir_arquivo", (ComandosLocais.interpretar("ler um pdf") as ComandosLocais.Resultado.Acao).nome)
    }
}
