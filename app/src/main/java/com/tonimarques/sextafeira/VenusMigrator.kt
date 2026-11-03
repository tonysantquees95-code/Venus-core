package com.tonimarques.sextafeira

import android.content.Context
import android.database.sqlite.SQLiteDatabase

class VenusMigrator(private val context: Context, private val apiKey: String) {

    suspend fun migrarBancoAntigo() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val dbHelper = Memoria(context)
            val db = dbHelper.writableDatabase

            val tabelaAntiga = "aprendidas" 
            val colunaChave = "trecho"   
            val colunaTexto = "texto"   

            // Abre a tabela antiga para ler o que a Vênus já sabia
            val cursor = db.rawQuery("SELECT $colunaChave, $colunaTexto FROM $tabelaAntiga", null)
            
            if (cursor != null) {
                val ai = com.google.generativeai.GoogleGenAI(apiKey = apiKey)
                val embeddingModel = ai.embeddingModel("models/text-embedding-004")

                while (cursor.moveToNext()) {
                    val chave = cursor.getString(0) ?: continue
                    val textoOriginal = cursor.getString(1) ?: continue

                    // Evita duplicados conferindo se já existe no banco semântico
                    val checkCursor = db.rawQuery("SELECT 1 FROM memoria_venus WHERE chave = ?", arrayOf(chave))
                    val jaMigrado = checkCursor.count > 0
                    checkCursor.close()

                    if (!jaMigrado) {
                        try {
                            // Gera o vetor semântico de graça no Gemini
                            val resposta = embeddingModel.embedContent(textoOriginal)
                            val vetorEmTexto = resposta.embedding.values.joinToString(",")

                            // Insere na tabela nova memoria_venus
                            val valores = android.content.ContentValues().apply {
                                put("chave", chave)
                                put("texto", textoOriginal)
                                put("vetor", vetorEmTexto)
                            }
                            db.insertWithOnConflict("memoria_venus", null, valores, SQLiteDatabase.CONFLICT_REPLACE)
                            
                            // Pausa para respeitar o limite de requisições por minuto da API gráti
                            kotlinx.coroutines.delay(150)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                cursor.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
