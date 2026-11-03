package com.tonimarques.sextafeira // Pacote real do seu aplicativo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import com.google.generativeai.GoogleGenAI
import com.google.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Classe responsável por transformar a memória antiga em semântica
 */
class VenusMigrator(context: Context, apiKey: String) {

    private val ai = GoogleGenAI(apiKey = apiKey)
    private val embeddingModel = ai.embeddingModel("models/text-embedding-004")
    private val dbHelper = Memoria(context) // Usa a sua classe Memoria existente

    /**
     * Função principal que roda a migração em segundo plano
     */
    suspend fun migrarBancoAntigo() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase

        try {
            // Nomes reais mapeados do seu arquivo Memoria.kt
            val tabelaAntiga = "aprendidas" 
            val colunaChave = "trecho"   
            val colunaTexto = "texto"   

            // Abre o banco antigo para ler as informações que a Vênus aprendeu
            val cursor = db.rawQuery("SELECT $colunaChave, $colunaTexto FROM $tabelaAntiga", null)
            
            while (cursor.moveToNext()) {
                val chave = cursor.getString(0) ?: continue
                val textoOriginal = cursor.getString(1) ?: continue

                // Verifica se essa informação já foi migrada para a tabela nova para poupar sua API
                val checkCursor = db.rawQuery("SELECT 1 FROM memoria_venus WHERE chave = ?", arrayOf(chave))
                val jaMigrado = checkCursor.count > 0
                checkCursor.close()

                if (!jaMigrado) {
                    try {
                        // 1. Gera o vetor do texto antigo de graça no Gemini
                        val resposta = embeddingModel.embedContent(content { text(textoOriginal) })
                        val vetorEmTexto = resposta.embedding.values.joinToString(",")

                        // 2. Grava na tabela semântica nova (memoria_venus)
                        val valores = ContentValues().apply {
                            put("chave", chave)
                            put("texto", textoOriginal)
                            put("vetor", vetorEmTexto)
                        }
                        db.insertWithOnConflict("memoria_venus", null, valores, SQLiteDatabase.CONFLICT_REPLACE)
                        
                        println("Vênus migrou com sucesso a informação: $chave")
                        
                        // Pausa de 150ms para respeitar a taxa limite da API gratuita
                        kotlinx.coroutines.delay(150)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            cursor.close()
            println("Migração semântica da Vênus concluída com sucesso!")
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
