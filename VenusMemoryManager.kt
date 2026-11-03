package com.venus.assistant // Ajuste para o pacote real do seu app se necessário

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.google.generativeai.GoogleGenAI
import com.google.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Gerenciador de Memória Vetorial da Vênus (Roda 100% no celular via SQLite)
 */
class VenusMemoryManager(context: Context, apiKey: String) {

    private val ai = GoogleGenAI(apiKey = apiKey)
    private val embeddingModel = ai.embeddingModel("models/text-embedding-004") // Modelo ultra-barato do plano pago
    private val dbHelper = VenusDbHelper(context)

    /**
     * 1. Transforma qualquer texto em um Vetor usando a API Paga do Gemini
     */
    private suspend fun obterEmbedding(texto: String): List<Float> = withContext(Dispatchers.IO) {
        val resposta = embeddingModel.embedContent(content { text(texto) })
        return@withContext resposta.embedding.values
    }

    /**
     * 2. Faz a Vênus APRENDER e gravar no banco local do celular
     */
    suspend fun aprenderEGravar(chaveContexto: String, informacaoOriginal: String) = withContext(Dispatchers.IO) {
        try {
            val vetor = obterEmbedding(informacaoOriginal)
            val vetorEmTexto = vetor.joinToString(",") // Salva os números como texto separado por vírgula

            val db = dbHelper.writableDatabase
            val valores = ContentValues().apply {
                put("chave", chaveContexto)
                put("texto", informacaoOriginal)
                put("vetor", vetorEmTexto)
            }
            // Insere ou atualiza se a chave já existir
            db.insertWithOnConflict("memoria_venus", null, valores, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 3. BUSCA INTELIGENTE: Encontra sinônimos calculando a proximidade matemática no celular
     */
    suspend fun buscarSinonimoLocal(perguntaCliente: String): String? = withContext(Dispatchers.IO) {
        try {
            val vetorPergunta = obterEmbedding(perguntaCliente)
            val db = dbHelper.readableDatabase
            
            var melhorTexto: String? = null
            var maiorSimilaridade = -1.0f
            val limiteCorte = 0.65f // 65% de proximidade (ajuste se ela ficar muito rígida ou muito solta)

            val cursor = db.rawQuery("SELECT texto, vetor FROM memoria_venus", null)
            while (cursor.moveToNext()) {
                val textoSalvo = cursor.getString(0)
                val vetorSalvoTexto = cursor.getString(1)
                
                val vetorSalvo = vetorSalvoTexto.split(",").map { it.toFloat() }
                
                // Calcula a Similaridade de Cosseno entre a pergunta e o dado salvo
                val similaridade = calcularSimilaridadeCosseno(vetorPergunta, vetorSalvo)
                
                if (similaridade > maiorSimilaridade && similaridade >= limiteCorte) {
                    maiorSimilaridade = similaridade
                    melhorTexto = textoSalvo
                }
            }
            cursor.close()
            return@withContext melhorTexto // Retorna o texto que tem o mesmo significado da pergunta
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    /**
     * Função matemática nativa para similaridade (Evita usar APIs extras de busca)
     */
    private fun calcularSimilaridadeCosseno(v1: List<Float>, v2: List<Float>): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}

/**
 * Criação da tabela SQLite interna do Android para guardar os vetores
 */
class VenusDbHelper(context: Context) : SQLiteOpenHelper(context, "VenusData.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memoria_venus (" +
                    "chave TEXT PRIMARY KEY, " +
                    "texto TEXT, " +
                    "vetor TEXT)"
        )
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS memoria_venus")
        onCreate(db)
    }
}
