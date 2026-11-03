package com.tonimarques.sextafeira

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import com.google.generativeai.GoogleGenAI
import com.google.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VenusMigrator(context: Context, apiKey: String) {

    private val ai = GoogleGenAI(apiKey = apiKey)
    private val embeddingModel = ai.embeddingModel("models/text-embedding-004")
    private val dbHelper = Memoria(context)

    suspend fun migrarBancoAntigo() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        try {
            val tabelaAntiga = "aprendidas" 
            val colunaChave = "trecho"   
            val colunaTexto = "texto"   

            val cursor = db.rawQuery("SELECT $colunaChave, $colunaTexto FROM $tabelaAntiga", null)
            
            while (cursor.moveToNext()) {
                val chave = cursor.getString(0) ?: continue
                val textoOriginal = cursor.getString(1) ?: continue

                val checkCursor = db.rawQuery("SELECT 1 FROM memoria_venus WHERE chave = ?", arrayOf(chave))
                val jaMigrado = checkCursor.count > 0
                checkCursor.close()

                if (!jaMigrado) {
                    try {
                        val resposta = embeddingModel.embedContent(textoOriginal)
                        val vetorEmTexto = resposta.embedding.values.joinToString(",")

                        val valores = ContentValues().apply {
                            put("chave", chave)
                            put("texto", textoOriginal)
                            put("vetor", vetorEmTexto)
                        }
                        db.insertWithOnConflict("memoria_venus", null, valores, SQLiteDatabase.CONFLICT_REPLACE)
                        
                        kotlinx.coroutines.delay(150)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
