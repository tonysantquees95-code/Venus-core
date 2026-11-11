package com.tonimarques.sextafeira

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * O "ouvido offline" da Vênus: um modelo de reconhecimento de voz em português (Vosk, ~31 MB)
 * baixado uma vez e guardado no celular. Com ele, a Vênus espera "Vênus, ativar" mantendo o
 * microfone aberto de forma contínua, sem ficar ligando e desligando (o que fazia o ícone
 * do microfone piscar na barra de status).
 */
object ModeloDeVoz {

    private const val NOME = "vosk-model-small-pt-0.3"
    private val ENDERECOS = listOf(
        "https://github.com/tonysantquees95-code/Venus-core/releases/download/voz-pt/$NOME.zip",
        "https://alphacephei.com/vosk/models/$NOME.zip",
        "https://huggingface.co/rhasspy/vosk-models/resolve/main/pt/$NOME.zip",
    )

    @Volatile
    var baixando = false
        private set

    fun pasta(contexto: Context) = File(contexto.filesDir, "voz-pt")

    fun pronto(contexto: Context) = File(pasta(contexto), ".pronto").exists()

    /**
     * Baixa e instala o modelo. Rodar fora da thread principal.
     * [progresso] recebe de 0 a 100. Devolve null se deu certo, ou a mensagem de erro.
     */
    fun baixar(contexto: Context, progresso: (Int) -> Unit): String? {
        if (pronto(contexto)) return null
        if (baixando) return "O download já está em andamento."
        baixando = true
        val zip = File(contexto.cacheDir, "$NOME.zip")
        try {
            var ultimoErro = "sem conexão"
            for (endereco in ENDERECOS) {
                try {
                    baixarArquivo(endereco, zip) { progresso((it * 0.9).toInt()) }
                    instalar(zip, pasta(contexto))
                    progresso(100)
                    return null
                } catch (e: Exception) {
                    ultimoErro = e.message ?: e.javaClass.simpleName
                }
            }
            return "Não consegui baixar a voz offline ($ultimoErro). Confira a internet e tente de novo."
        } finally {
            zip.delete()
            baixando = false
        }
    }

    fun apagar(contexto: Context) {
        pasta(contexto).deleteRecursively()
    }

    private fun baixarArquivo(endereco: String, destino: File, progresso: (Int) -> Unit) {
        var url = URL(endereco)
        var conexao: HttpURLConnection
        var saltos = 0
        while (true) {
            conexao = url.openConnection() as HttpURLConnection
            conexao.connectTimeout = 15_000
            conexao.readTimeout = 30_000
            conexao.instanceFollowRedirects = false
            conexao.setRequestProperty("User-Agent", "Venus/1.0 (Android)")
            val codigo = conexao.responseCode
            if (codigo in 300..399 && saltos < 6) {
                val proximo = conexao.getHeaderField("Location") ?: throw java.io.IOException("HTTP $codigo")
                conexao.disconnect()
                url = URL(url, proximo)
                saltos++
                continue
            }
            if (codigo !in 200..299) {
                conexao.disconnect()
                throw java.io.IOException("HTTP $codigo")
            }
            break
        }
        try {
            val total = conexao.contentLengthLong
            var lidos = 0L
            var ultimo = -1
            conexao.inputStream.use { entrada ->
                FileOutputStream(destino).use { saida ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        saida.write(buffer, 0, n)
                        lidos += n
                        if (total > 0) {
                            val p = (lidos * 100 / total).toInt()
                            if (p != ultimo) {
                                ultimo = p
                                progresso(p)
                            }
                        }
                    }
                }
            }
            if (lidos < 5_000_000) throw java.io.IOException("arquivo incompleto")
        } finally {
            conexao.disconnect()
        }
    }

    /** Descompacta tirando a pasta de cima ("vosk-model-small-pt-0.3/am/..." → "am/..."). */
    private fun instalar(zip: File, destino: File) {
        val temporaria = File(destino.parentFile, destino.name + ".tmp")
        temporaria.deleteRecursively()
        temporaria.mkdirs()
        val base = temporaria.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val item = z.nextEntry ?: break
                val partes = item.name.replace('\\', '/').split('/').filter { it.isNotBlank() }
                val relativo = (if (partes.size > 1) partes.drop(1) else partes).joinToString(File.separator)
                if (relativo.isBlank()) continue
                val arquivo = File(temporaria, relativo)
                if (!arquivo.canonicalPath.startsWith(base)) continue // proteção contra caminhos estranhos
                if (item.isDirectory) {
                    arquivo.mkdirs()
                } else {
                    arquivo.parentFile?.mkdirs()
                    FileOutputStream(arquivo).use { z.copyTo(it) }
                }
            }
        }
        if (!File(temporaria, "am").exists() && !File(temporaria, "final.mdl").exists()) {
            temporaria.deleteRecursively()
            throw java.io.IOException("modelo inválido")
        }
        File(temporaria, ".pronto").writeText("1")
        destino.deleteRecursively()
        if (!temporaria.renameTo(destino)) {
            temporaria.copyRecursively(destino, overwrite = true)
            temporaria.deleteRecursively()
        }
    }
}
