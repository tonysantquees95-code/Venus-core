package com.tonimarques.sextafeira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A memória da Sexta-feira, guardada só no celular (banco SQLite do app):
 *  - mensagens: todas as conversas, cada uma com um tópico;
 *  - memorias: fatos e preferências sobre você ("gosto de rock", cidade, apelido, favoritos);
 *  - aprendidas: frases novas que ela aprendeu quando você confirmou o que queria dizer;
 *  - uso: o que você mais usa (apps, músicas, contatos), para entender "o de sempre".
 */
class Memoria(contexto: Context) : SQLiteOpenHelper(contexto, "sexta.db", null, 2) {

    data class Mensagem(val quando: Long, val doUsuario: Boolean, val texto: String, val topico: String)
    data class ResumoTopico(val topico: String, val quantidade: Int, val ultimo: Long, val previa: String)
    data class Fato(val id: Long, val quando: Long, val texto: String, val categoria: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mensagens (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, autor TEXT, texto TEXT, topico TEXT)")
        db.execSQL("CREATE INDEX idx_topico ON mensagens(topico)")
        db.execSQL("CREATE TABLE memorias (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, texto TEXT, categoria TEXT)")
        db.execSQL("CREATE TABLE aprendidas (id INTEGER PRIMARY KEY AUTOINCREMENT, frase TEXT UNIQUE, intencao TEXT)")
        db.execSQL("CREATE TABLE uso (chave TEXT PRIMARY KEY, contagem INTEGER, ultimo INTEGER)")
        criarTabelasDeConhecimento(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
        if (antiga < 2) criarTabelasDeConhecimento(db)
    }

    /** Versão 2: novidades acompanhadas, assuntos, respostas guardadas e informações de controle. */
    private fun criarTabelasDeConhecimento(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS noticias (id INTEGER PRIMARY KEY AUTOINCREMENT, tema TEXT, titulo TEXT, " +
                "fonte TEXT, link TEXT UNIQUE, data INTEGER, coletado INTEGER)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS temas (nome TEXT PRIMARY KEY)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS respostas (id INTEGER PRIMARY KEY AUTOINCREMENT, pergunta TEXT, " +
                "resposta TEXT, quando INTEGER)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS controle (chave TEXT PRIMARY KEY, valor TEXT)")
    }

    // ------------------------------------------------------------- conversas
    fun salvarMensagem(doUsuario: Boolean, texto: String, topico: String) {
        val v = ContentValues().apply {
            put("quando", System.currentTimeMillis())
            put("autor", if (doUsuario) "eu" else "sexta")
            put("texto", texto)
            put("topico", topico)
        }
        writableDatabase.insert("mensagens", null, v)
    }

    fun topicos(): List<Pair<String, Int>> {
        val lista = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT topico, COUNT(*) FROM mensagens GROUP BY topico ORDER BY MAX(quando) DESC", null
        ).use { c -> while (c.moveToNext()) lista += c.getString(0) to c.getInt(1) }
        return lista
    }

    fun mensagens(topico: String? = null, limite: Int = 200): List<Mensagem> {
        val lista = mutableListOf<Mensagem>()
        val (onde, args) = if (topico == null) "" to null else "WHERE topico = ?" to arrayOf(topico)
        readableDatabase.rawQuery(
            "SELECT quando, autor, texto, topico FROM mensagens $onde ORDER BY id DESC LIMIT $limite", args
        ).use { c ->
            while (c.moveToNext()) lista += Mensagem(c.getLong(0), c.getString(1) == "eu", c.getString(2), c.getString(3))
        }
        return lista.reversed()
    }

    /** Tópicos com quantidade, data da última mensagem e uma prévia (mais recentes primeiro). */
    fun resumoTopicos(): List<ResumoTopico> {
        val lista = mutableListOf<ResumoTopico>()
        readableDatabase.rawQuery(
            "SELECT topico, COUNT(*), MAX(quando) FROM mensagens WHERE texto NOT LIKE 'Fontes: %' " +
                "GROUP BY topico ORDER BY MAX(quando) DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val topico = c.getString(0)
                val previa = readableDatabase.rawQuery(
                    "SELECT texto FROM mensagens WHERE topico = ? AND autor = 'eu' ORDER BY id DESC LIMIT 1", arrayOf(topico)
                ).use { p -> if (p.moveToFirst()) p.getString(0) else "" }
                lista += ResumoTopico(topico, c.getInt(1), c.getLong(2), previa)
            }
        }
        return lista
    }

    /** Mensagens que contêm o trecho (mais recentes primeiro). */
    fun buscarMensagens(trecho: String, limite: Int = 80): List<Mensagem> {
        val lista = mutableListOf<Mensagem>()
        readableDatabase.rawQuery(
            "SELECT quando, autor, texto, topico FROM mensagens WHERE texto LIKE ? AND texto NOT LIKE 'Fontes: %' " +
                "ORDER BY id DESC LIMIT $limite",
            arrayOf("%${trecho.trim()}%")
        ).use { c ->
            while (c.moveToNext()) lista += Mensagem(c.getLong(0), c.getString(1) == "eu", c.getString(2), c.getString(3))
        }
        return lista
    }

    fun apagarTopico(topico: String) {
        writableDatabase.delete("mensagens", "topico = ?", arrayOf(topico))
    }

    fun apagarHistorico() {
        writableDatabase.delete("mensagens", null, null)
    }

    // ----------------------------------------------------- fatos e preferências
    fun lembrar(texto: String, categoria: String = "fato"): Boolean {
        val limpo = texto.trim()
        if (limpo.isBlank()) return false
        val repetido = readableDatabase.rawQuery(
            "SELECT 1 FROM memorias WHERE lower(texto) = lower(?)", arrayOf(limpo)
        ).use { it.moveToFirst() }
        if (repetido) return false
        writableDatabase.insert("memorias", null, ContentValues().apply {
            put("quando", System.currentTimeMillis())
            put("texto", limpo)
            put("categoria", categoria)
        })
        return true
    }

    /** Guarda um valor único por categoria (ex.: "cidade", "apelido", "favorito:musica"). */
    fun definir(categoria: String, valor: String) {
        writableDatabase.delete("memorias", "categoria = ?", arrayOf(categoria))
        writableDatabase.insert("memorias", null, ContentValues().apply {
            put("quando", System.currentTimeMillis())
            put("texto", valor.trim())
            put("categoria", categoria)
        })
    }

    fun valor(categoria: String): String? = readableDatabase.rawQuery(
        "SELECT texto FROM memorias WHERE categoria = ? ORDER BY id DESC LIMIT 1", arrayOf(categoria)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun fatos(): List<Fato> {
        val lista = mutableListOf<Fato>()
        readableDatabase.rawQuery("SELECT id, quando, texto, categoria FROM memorias ORDER BY id", null).use { c ->
            while (c.moveToNext()) lista += Fato(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))
        }
        return lista
    }

    /** Apaga as memórias que contêm o trecho (sem diferenciar acentos e maiúsculas). Devolve quantas. */
    fun esquecer(trecho: String): Int {
        val alvo = ComandosLocais.normalizar(trecho).trim()
        if (alvo.length < 2) return 0
        var apagadas = 0
        for (f in fatos()) {
            if (ComandosLocais.normalizar(f.texto).contains(alvo) || ComandosLocais.normalizar(f.categoria).contains(alvo)) {
                apagadas += writableDatabase.delete("memorias", "id = ?", arrayOf(f.id.toString()))
            }
        }
        return apagadas
    }

    fun esquecerId(id: Long) {
        writableDatabase.delete("memorias", "id = ?", arrayOf(id.toString()))
    }

    fun apagarMemorias() {
        writableDatabase.delete("memorias", null, null)
        writableDatabase.delete("aprendidas", null, null)
        writableDatabase.delete("uso", null, null)
    }

    // ------------------------------------------------------ frases aprendidas
    fun aprender(frase: String, intencao: String) {
        writableDatabase.insertWithOnConflict("aprendidas", null, ContentValues().apply {
            put("frase", frase)
            put("intencao", intencao)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun aprendidas(): List<Pair<String, String>> {
        val lista = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT frase, intencao FROM aprendidas", null).use { c ->
            while (c.moveToNext()) lista += c.getString(0) to c.getString(1)
        }
        return lista
    }

    // --------------------------------------------------------------- uso
    fun registrarUso(tipo: String, valor: String) {
        if (valor.isBlank()) return
        val chave = "$tipo:${valor.trim()}"
        val agora = System.currentTimeMillis()
        // Compatível com Android 8: atualiza; se não existia, insere.
        val atualizar = writableDatabase.compileStatement("UPDATE uso SET contagem = contagem + 1, ultimo = ? WHERE chave = ?")
        atualizar.bindLong(1, agora)
        atualizar.bindString(2, chave)
        if (atualizar.executeUpdateDelete() == 0) {
            writableDatabase.insert("uso", null, ContentValues().apply {
                put("chave", chave)
                put("contagem", 1)
                put("ultimo", agora)
            })
        }
    }

    fun maisUsados(tipo: String, quantos: Int = 3): List<String> {
        val lista = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT chave FROM uso WHERE chave LIKE ? ORDER BY contagem DESC, ultimo DESC LIMIT $quantos",
            arrayOf("$tipo:%")
        ).use { c -> while (c.moveToNext()) lista += c.getString(0).removePrefix("$tipo:") }
        return lista
    }

    /** Resumo do que sabe sobre o usuário, para a IA personalizar as respostas. */
    fun resumoParaIA(): String {
        val partes = mutableListOf<String>()
        valor("apelido")?.let { partes += "Chame o usuário de: $it" }
        valor("cidade")?.let { partes += "Cidade do usuário: $it" }
        val favoritos = fatos().filter { it.categoria.startsWith("favorito:") }
            .joinToString("; ") { "${it.categoria.removePrefix("favorito:")}: ${it.texto}" }
        if (favoritos.isNotBlank()) partes += "Favoritos: $favoritos"
        val fatos = fatos().filter { it.categoria == "fato" || it.categoria == "gosto" || it.categoria == "ia" }
            .takeLast(40).joinToString("\n") { "- ${it.texto}" }
        if (fatos.isNotBlank()) partes += "O que o usuário contou:\n$fatos"
        val apps = maisUsados("app")
        if (apps.isNotEmpty()) partes += "Apps mais usados: ${apps.joinToString(", ")}"
        val musicas = maisUsados("musica")
        if (musicas.isNotEmpty()) partes += "Músicas mais pedidas: ${musicas.joinToString(", ")}"
        val contatos = maisUsados("contato")
        if (contatos.isNotEmpty()) partes += "Contatos mais frequentes: ${contatos.joinToString(", ")}"
        return partes.joinToString("\n").take(2500)
    }
}
