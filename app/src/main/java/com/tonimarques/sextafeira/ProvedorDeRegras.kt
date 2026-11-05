package com.tonimarques.sextafeira

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Entrega as regras de venda ao Vênus Robô (outro app), lendo na hora do Perfil: uma linha com o JSON das regras,
 * o texto para as IAs e as perguntas e respostas prontas (pagamento, frete, retirada…).
 * Só apps assinados com a mesma chave da Vênus podem ler (permissão de assinatura LER_REGRAS).
 * Somente leitura: as regras mudam só nos ajustes da Vênus.
 */
class ProvedorDeRegras : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { Perfil.carregar(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        context?.let { Perfil.carregar(it) }
        val c = MatrixCursor(COLUNAS)
        c.addRow(arrayOf<Any?>(Perfil.loja, Perfil.regras.json().toString(), Perfil.regrasDeVenda, perguntas().toString(), if (Perfil.regrasConfiguradas) 1 else 0))
        return c
    }

    private fun perguntas(): JSONArray {
        val a = JSONArray()
        if (!Perfil.regrasConfiguradas) return a
        for ((id, p, r) in Perfil.regras.perguntasComId(Perfil.loja)) a.put(JSONObject().put("id", id).put("pergunta", p).put("resposta", r))
        return a
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.venus.regras"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTORIDADE = "com.tonimarques.sextafeira.regras"
        val COLUNAS = arrayOf("loja", "json", "texto", "perguntas", "configuradas")
    }
}
