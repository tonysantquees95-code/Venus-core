package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gera o "vetor semântico" de uma frase com o Gemini (embedContent / batchEmbedContents).
 * Sem bibliotecas extras: usa só HttpURLConnection e org.json, que já vêm no Android.
 * Tudo aqui é bloqueante: chame fora da thread principal.
 *
 * O text-embedding-004 foi desligado pelo Google em janeiro de 2026. A Vênus tenta os modelos novos em ordem
 * e fica com o primeiro que responder. Os vetores são pedidos com [DIMENSOES] números (em vez de 3072):
 * quase a mesma qualidade, banco 4 vezes menor e busca mais rápida no celular.
 * O nome guardado em cada linha ([MODELO]) inclui o tamanho; se mudar, Memoria refaz os vetores sozinha.
 */
object EmbeddingsGemini {

    /** Do mais novo para o mais antigo; o primeiro que funcionar fica em uso enquanto o app estiver aberto. */
    val MODELOS = listOf("gemini-embedding-2", "gemini-embedding-2-preview", "gemini-embedding-001")
    const val DIMENSOES = 768

    @Volatile
    private var modeloAtivo = MODELOS.first()

    @Volatile
    private var semTipoDeTarefa = false

    /** Nome gravado junto de cada vetor: só se comparam vetores do mesmo modelo e tamanho. */
    val MODELO: String get() = "$modeloAtivo@$DIMENSOES"

    private const val ENDERECO = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MAXIMO_DE_LETRAS = 2000
    private const val LOTE = 100

    /** O vetor da frase (já com comprimento 1), ou null se faltar chave/internet ou o Gemini recusar. */
    fun gerar(texto: String, chaveApi: String): FloatArray? = gerarVarios(listOf(texto), chaveApi)?.firstOrNull()

    /**
     * Vetores de várias frases de uma vez (até 100 por chamada), na mesma ordem.
     * Null se não deu (sem chave, sem internet, nenhum modelo aceitou).
     */
    fun gerarVarios(textos: List<String>, chaveApi: String): List<FloatArray>? {
        val limpos = textos.map { it.trim().take(MAXIMO_DE_LETRAS).ifEmpty { "." } }
        if (limpos.isEmpty() || chaveApi.isBlank()) return null
        val saida = mutableListOf<FloatArray>()
        for (lote in limpos.chunked(LOTE)) saida += loteComReserva(lote, chaveApi) ?: return null
        return saida
    }

    private fun loteComReserva(lote: List<String>, chave: String): List<FloatArray>? {
        val ordem = listOf(modeloAtivo) + MODELOS.filter { it != modeloAtivo }
        for (modelo in ordem) {
            when (val r = pedir(modelo, lote, chave)) {
                is Resultado.Ok -> {
                    modeloAtivo = modelo
                    return r.vetores
                }
                is Resultado.ModeloIndisponivel -> continue
                is Resultado.Falha -> return null
            }
        }
        return null
    }

    private sealed class Resultado {
        class Ok(val vetores: List<FloatArray>) : Resultado()
        object ModeloIndisponivel : Resultado()
        object Falha : Resultado()
    }

    private fun pedir(modelo: String, lote: List<String>, chave: String, comTarefa: Boolean = !semTipoDeTarefa): Resultado {
        var conexao: HttpURLConnection? = null
        return try {
            val corpo = corpoDoLote(modelo, lote, comTarefa)
            conexao = (URL("$ENDERECO$modelo:batchEmbedContents").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Connection", "close")
                setRequestProperty("x-goog-api-key", chave)
            }
            conexao.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = conexao.responseCode
            val texto = (if (codigo in 200..299) conexao.inputStream else conexao.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                codigo in 200..299 -> lerLote(texto, lote.size)?.let { Resultado.Ok(it) } ?: Resultado.Falha
                // modelo que não aceita o tipo de tarefa: tenta sem ele
                codigo == 400 && comTarefa && texto.contains("task", ignoreCase = true) -> {
                    semTipoDeTarefa = true
                    pedir(modelo, lote, chave, comTarefa = false)
                }
                codigo == 404 || (codigo == 400 && texto.contains("model", ignoreCase = true)) -> Resultado.ModeloIndisponivel
                else -> Resultado.Falha
            }
        } catch (e: Exception) {
            Resultado.Falha
        } finally {
            conexao?.disconnect()
        }
    }

    fun corpoDoLote(modelo: String, lote: List<String>, comTarefa: Boolean): JSONObject {
        val pedidos = JSONArray()
        for (t in lote) {
            val p = JSONObject()
                .put("model", "models/$modelo")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t))))
                .put("outputDimensionality", DIMENSOES)
            if (comTarefa) p.put("taskType", "SEMANTIC_SIMILARITY")
            pedidos.put(p)
        }
        return JSONObject().put("requests", pedidos)
    }

    /** Lê {"embeddings":[{"values":[...]}, ...]}; null se faltar algum. */
    fun lerLote(json: String, esperados: Int): List<FloatArray>? = try {
        val lista = JSONObject(json).optJSONArray("embeddings") ?: JSONArray()
        if (lista.length() != esperados) null
        else (0 until lista.length()).map { i ->
            val valores = lista.getJSONObject(i).getJSONArray("values")
            normalizar(FloatArray(valores.length()) { valores.getDouble(it).toFloat() }) ?: return null
        }
    } catch (e: Exception) {
        null
    }

    /** Deixa o vetor com comprimento 1 (com menos de 3072 números o Gemini não devolve normalizado). */
    fun normalizar(vetor: FloatArray): FloatArray? {
        var soma = 0.0
        for (v in vetor) soma += v.toDouble() * v.toDouble()
        if (soma == 0.0) return null
        val norma = Math.sqrt(soma).toFloat()
        return FloatArray(vetor.size) { vetor[it] / norma }
    }
}
