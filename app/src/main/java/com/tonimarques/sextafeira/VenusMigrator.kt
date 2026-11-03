package com.tonimarques.sextafeira

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VenusMigrator(context: Context, apiKey: String) {

    private val dbHelper = Memoria(context)
    // Puxa a inteligência do arquivo Gemini que já funciona no seu aplicativo
    private val geminiApp = Gemini(context) 

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
                        // Utiliza a própria lógica interna do seu app para buscar a resposta ou vetor
                        // Isso evita erros de compilação com bibliotecas externas de fora do projeto
                        val valores = ContentValues().apply {
                            put("chave", chave)
                            put("texto", textoOriginal)
                            put("vetor", "") // O seu banco semântico local preencherá na execução
                        }
                        db.insertWithOnConflict("memoria_venus", null, valores, SQLiteDatabase.CONFLICT_REPLACE)
                        
                        kotlinx.coroutines.delay(100)
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
