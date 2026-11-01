package com.tonimarques.sextafeira

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Abre arquivos que chegam pelo "Compartilhar" ou "Abrir com": PDF, imagens, áudios (inclusive os do WhatsApp),
 * Word, Excel, PowerPoint, CSV e textos. PDF, imagem e áudio vão inteiros para o Gemini ler;
 * dos documentos do Office a Vênus tira o texto no próprio celular.
 */
object Arquivos {

    /** [bytes] para o Gemini ler direto (PDF, imagem, áudio) ou [texto] já extraído. */
    data class Documento(val nome: String, val mime: String, val bytes: ByteArray?, val texto: String?) {
        val tipoFalado: String
            get() = when {
                mime == "application/pdf" -> "PDF"
                mime.startsWith("image/") -> "imagem"
                mime.startsWith("audio/") -> "áudio"
                nome.endsWith(".xlsx", true) || nome.endsWith(".csv", true) -> "planilha"
                nome.endsWith(".pptx", true) -> "apresentação"
                else -> "documento"
            }
    }

    class ErroDeArquivo(mensagem: String) : Exception(mensagem)

    /** O Gemini aceita até ~20 MB por pedido; deixa folga. */
    const val LIMITE_BYTES = 18 * 1024 * 1024
    private const val LIMITE_TEXTO = 80_000

    private val DIRETOS = setOf(
        "application/pdf", "audio/ogg", "audio/opus", "audio/mpeg", "audio/mp3", "audio/mp4", "audio/m4a", "audio/x-m4a",
        "audio/aac", "audio/wav", "audio/x-wav", "audio/amr", "audio/3gpp", "audio/webm", "audio/flac",
    )

    fun abrir(ctx: Context, uri: Uri): Documento {
        var nome = "arquivo"
        var tamanho = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    nome = c.getString(0) ?: nome
                    if (!c.isNull(1)) tamanho = c.getLong(1)
                }
            }
        } catch (e: Exception) {
        }
        if (tamanho > LIMITE_BYTES) throw ErroDeArquivo("Esse arquivo é grande demais (${tamanho / 1_048_576} MB). O limite é 18 MB.")
        val mime = tipo(ctx.contentResolver.getType(uri), nome)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ErroDeArquivo("Não consegui abrir o arquivo.")
        if (bytes.size > LIMITE_BYTES) throw ErroDeArquivo("Esse arquivo é grande demais. O limite é 18 MB.")
        return montar(nome, mime, bytes)
    }

    /** Decide o tipo pelo que o Android disse ou, se vier genérico, pela extensão. */
    fun tipo(informado: String?, nome: String): String {
        val ext = nome.substringAfterLast('.', "").lowercase()
        val porExtensao = mapOf(
            "pdf" to "application/pdf", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
            "heic" to "image/heic", "opus" to "audio/ogg", "ogg" to "audio/ogg", "mp3" to "audio/mpeg", "m4a" to "audio/mp4",
            "aac" to "audio/aac", "wav" to "audio/wav", "amr" to "audio/amr",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "csv" to "text/csv", "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
            "html" to "text/html", "htm" to "text/html", "xml" to "text/xml",
        )[ext]
        val i = informado?.lowercase()
        return if (i.isNullOrBlank() || i == "application/octet-stream" || i == "*/*") porExtensao ?: "application/octet-stream" else i
    }

    /** Monta o documento: o que o Gemini lê direto fica em bytes; Office e texto viram texto. */
    fun montar(nome: String, mime: String, bytes: ByteArray): Documento = when {
        mime in DIRETOS || mime.startsWith("audio/") -> Documento(nome, if (mime == "audio/opus") "audio/ogg" else mime, bytes, null)
        mime.startsWith("image/") -> Documento(nome, mime, bytes, null)
        mime.contains("wordprocessingml") || nome.endsWith(".docx", true) -> Documento(nome, mime, null, cortar(textoDoWord(bytes)))
        mime.contains("spreadsheetml") || nome.endsWith(".xlsx", true) -> Documento(nome, mime, null, cortar(textoDoExcel(bytes)))
        mime.contains("presentationml") || nome.endsWith(".pptx", true) -> Documento(nome, mime, null, cortar(textoDoPowerPoint(bytes)))
        mime.startsWith("text/") || mime == "application/json" || mime.endsWith("+xml") ->
            Documento(nome, mime, null, cortar(String(bytes, Charsets.UTF_8).let { if (mime == "text/html") semTags(it) else it }))
        nome.endsWith(".doc", true) || nome.endsWith(".xls", true) || nome.endsWith(".ppt", true) ->
            throw ErroDeArquivo("Esse é o formato antigo do Office (.doc, .xls, .ppt), que eu não consigo abrir. Salve como .docx, .xlsx ou PDF.")
        else -> throw ErroDeArquivo("Ainda não sei ler esse tipo de arquivo ($mime). Eu leio PDF, imagens, áudios, Word, Excel, PowerPoint, CSV e textos.")
    }

    private fun cortar(t: String): String {
        val limpo = t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (limpo.isBlank()) throw ErroDeArquivo("O arquivo está vazio ou só tem imagens. Se for escaneado, mande como PDF ou foto.")
        return if (limpo.length > LIMITE_TEXTO) limpo.take(LIMITE_TEXTO) + "\n[… arquivo cortado: muito longo]" else limpo
    }

    // ------------------------------------------------------------------ Office (são arquivos .zip com XML dentro)
    private fun arquivosDoZip(bytes: ByteArray, quer: (String) -> Boolean): Map<String, String> {
        val saida = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && quer(e.name)) saida[e.name] = String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        return saida
    }

    fun desescapar(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&amp;", "&")

    private fun semTags(xml: String) = desescapar(xml.replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>"), " ").replace(Regex("<[^>]+>"), " "))

    /** Word: o texto fica em word/document.xml, um parágrafo por <w:p>. */
    fun textoDoWord(bytes: ByteArray): String {
        val xml = arquivosDoZip(bytes) { it == "word/document.xml" }.values.firstOrNull() ?: throw ErroDeArquivo("Esse Word parece estar corrompido.")
        return desescapar(
            xml.replace(Regex("<w:tab/>"), "\t").replace(Regex("</w:p>"), "\n").replace(Regex("<w:br[^>]*/>"), "\n").replace(Regex("<[^>]+>"), "")
        )
    }

    /** PowerPoint: o texto de cada slide fica em <a:t>. */
    fun textoDoPowerPoint(bytes: ByteArray): String {
        val slides = arquivosDoZip(bytes) { Regex("^ppt/slides/slide\\d+\\.xml$").matches(it) }
            .toSortedMap(compareBy { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 })
        return slides.values.mapIndexed { i, xml ->
            "Slide ${i + 1}: " + Regex("<a:t>([^<]*)</a:t>").findAll(xml).joinToString(" ") { desescapar(it.groupValues[1]) }
        }.joinToString("\n")
    }

    /** Excel: cada aba vira linhas separadas por ";" (textos vêm de sharedStrings.xml). */
    fun textoDoExcel(bytes: ByteArray): String {
        val partes = arquivosDoZip(bytes) { it == "xl/sharedStrings.xml" || it == "xl/workbook.xml" || Regex("^xl/worksheets/sheet\\d+\\.xml$").matches(it) }
        val compartilhados = partes["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si>([\\s\\S]*?)</si>").findAll(xml).map { si ->
                desescapar(Regex("<t[^>]*>([^<]*)</t>").findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] })
            }.toList()
        }.orEmpty()
        val nomes = partes["xl/workbook.xml"]?.let { xml -> Regex("<sheet [^>]*name=\"([^\"]+)\"").findAll(xml).map { desescapar(it.groupValues[1]) }.toList() }.orEmpty()
        val abas = partes.filterKeys { it.startsWith("xl/worksheets/") }
            .toSortedMap(compareBy { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 })
        val saida = StringBuilder()
        abas.values.forEachIndexed { i, xml ->
            saida.append("Aba ").append(nomes.getOrNull(i) ?: "${i + 1}").append(":\n")
            for (linha in Regex("<row[^>]*>([\\s\\S]*?)</row>").findAll(xml)) {
                val celulas = Regex("<c ([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(linha.groupValues[1]).map { c ->
                    val atributos = c.groupValues[1]
                    val corpo = c.groupValues[2]
                    val valor = Regex("<v>([^<]*)</v>").find(corpo)?.groupValues?.get(1)
                    when {
                        "t=\"s\"" in atributos -> valor?.toIntOrNull()?.let { compartilhados.getOrNull(it) }.orEmpty()
                        "t=\"inlineStr\"" in atributos -> desescapar(Regex("<t[^>]*>([^<]*)</t>").find(corpo)?.groupValues?.get(1).orEmpty())
                        else -> desescapar(valor.orEmpty())
                    }
                }.toList()
                if (celulas.any { it.isNotBlank() }) saida.append(celulas.joinToString(" ; ")).append('\n')
            }
            saida.append('\n')
        }
        return saida.toString()
    }
}
