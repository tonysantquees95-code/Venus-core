package com.tonimarques.sextafeira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * O diário de ajustes da Vênus: tudo o que ela mudou de verdade (no site, no Instagram, no caixa, nas conexões)
 * e o que aconteceu de importante (estudo diário, erros consertados), com data, hora e de onde veio o pedido.
 * Serve para conferir "o que mudou hoje?", desfazer algo à mão se preciso, e mostrar a um cliente da Vênus
 * tudo o que ela fez por ele (transparência).
 * Fica só no celular (banco SQLite próprio, até 2.000 registros).
 */
class Diario private constructor(contexto: Context) : SQLiteOpenHelper(contexto.applicationContext, "diario.db", null, 1) {

    data class Ajuste(val id: Long, val quando: Long, val area: String, val texto: String, val origem: String, val ok: Boolean)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ajustes (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, area TEXT, texto TEXT, origem TEXT, ok INTEGER)")
        db.execSQL("CREATE INDEX idx_ajustes_quando ON ajustes(quando)")
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {}

    fun anotar(area: String, texto: String, origem: String = "voz", ok: Boolean = true) {
        try {
            writableDatabase.insert("ajustes", null, ContentValues().apply {
                put("quando", System.currentTimeMillis())
                put("area", area)
                put("texto", Registro.limpar(texto).take(400))
                put("origem", origem)
                put("ok", if (ok) 1 else 0)
            })
            writableDatabase.execSQL("DELETE FROM ajustes WHERE id NOT IN (SELECT id FROM ajustes ORDER BY id DESC LIMIT 2000)")
        } catch (e: Exception) {
        }
    }

    fun ultimos(quantos: Int = 100, desde: Long = 0, area: String? = null): List<Ajuste> {
        val lista = mutableListOf<Ajuste>()
        val filtro = if (area != null) " AND area = ?" else ""
        val args = listOfNotNull(desde.toString(), area).toTypedArray()
        readableDatabase.rawQuery("SELECT id, quando, area, texto, origem, ok FROM ajustes WHERE quando >= ?$filtro ORDER BY id DESC LIMIT $quantos", args).use { c ->
            while (c.moveToNext()) lista += Ajuste(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) == 1)
        }
        return lista
    }

    fun limpar() = writableDatabase.execSQL("DELETE FROM ajustes")

    companion object {
        @Volatile
        private var instancia: Diario? = null

        fun de(ctx: Context): Diario = instancia ?: synchronized(this) { instancia ?: Diario(ctx).also { instancia = it } }

        /** Atalho: anota sem precisar guardar a instância. */
        fun anotar(ctx: Context, area: String, texto: String, origem: String = "voz", ok: Boolean = true) = de(ctx).anotar(area, texto, origem, ok)

        val NOMES_AREA = mapOf(
            "loja" to "Loja", "instagram" to "Instagram", "caixa" to "Caixa", "conexao" to "Conexões",
            "estudo" to "Estudo", "erro" to "Erros", "app" to "App",
        )

        /** Ações que mudam algo de verdade: entram no diário quando dão certo. */
        val ACOES_QUE_MUDAM = mapOf(
            "loja_confirmar" to "loja", "loja_promo_parados" to "loja", "caixa_lancar" to "caixa", "caixa_fixa" to "caixa",
            "caixa_remover_fixa" to "caixa", "confirmar_resposta" to "loja", "robo_ligar" to "loja",
        )

        /** "o que mudou hoje" em uma frase. */
        fun resumo(lista: List<Ajuste>, periodo: String): String {
            if (lista.isEmpty()) return "Nenhum ajuste $periodo."
            val porArea = lista.groupBy { it.area }.entries.sortedByDescending { it.value.size }
                .joinToString(", ") { (a, v) -> "${v.size} ${NOMES_AREA[a]?.lowercase() ?: a}" }
            val falhas = lista.count { !it.ok }
            return "${lista.size} ${if (lista.size == 1) "registro" else "registros"} $periodo ($porArea)" +
                (if (falhas > 0) ", $falhas com falha" else "") + ". O último: ${lista.first().texto}"
        }

        fun inicioDoDia(agora: Calendar = Calendar.getInstance()): Long = (agora.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun hora(quando: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(quando))
    }
}
