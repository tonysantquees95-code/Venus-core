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
class Memoria(contexto: Context) : SQLiteOpenHelper(contexto, "sexta.db", null, 3) {

    data class Mensagem(val quando: Long, val doUsuario: Boolean, val texto: String, val topico: String, val conversa: Long = 0)

    /** Uma conversa: começa quando você pede "nova conversa" ou volta depois de umas horas sem falar. */
    data class Conversa(
        val id: Long, val inicio: Long, val ultimo: Long, val titulo: String, val resumo: String,
        val quantidade: Int, val previa: String, val topico: String, val trecho: String? = null, val nota: Double = 0.0,
    )
    data class ResumoTopico(val topico: String, val quantidade: Int, val ultimo: Long, val previa: String)
    data class Fato(val id: Long, val quando: Long, val texto: String, val categoria: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mensagens (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, autor TEXT, texto TEXT, topico TEXT, conversa INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_topico ON mensagens(topico)")
        db.execSQL("CREATE INDEX idx_conversa ON mensagens(conversa)")
        db.execSQL(TABELA_CONVERSAS)
        db.execSQL("CREATE TABLE memorias (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, texto TEXT, categoria TEXT)")
        db.execSQL("CREATE TABLE aprendidas (id INTEGER PRIMARY KEY AUTOINCREMENT, frase TEXT UNIQUE, intencao TEXT)")
        db.execSQL("CREATE TABLE uso (chave TEXT PRIMARY KEY, contagem INTEGER, ultimo INTEGER)")
        criarTabelasDeConhecimento(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
        if (antiga < 2) criarTabelasDeConhecimento(db)
        if (antiga < 3) separarEmConversas(db)
    }

    /** Versão 3: o histórico antigo (que era um só) é dividido em conversas pelas pausas de mais de 3 horas. */
    private fun separarEmConversas(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE mensagens ADD COLUMN conversa INTEGER DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversa ON mensagens(conversa)")
        db.execSQL(TABELA_CONVERSAS)
        var conversa = 0L
        var ultimo = 0L
        var titulo = ""
        val linhas = mutableListOf<Triple<Long, Long, Boolean>>()
        val textos = mutableMapOf<Long, String>()
        db.rawQuery("SELECT id, quando, autor, texto FROM mensagens ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                linhas += Triple(c.getLong(0), c.getLong(1), c.getString(2) == "eu")
                textos[c.getLong(0)] = c.getString(3).orEmpty()
            }
        }
        db.beginTransaction()
        try {
            for ((id, quando, doUsuario) in linhas) {
                if (conversa == 0L || quando - ultimo > PAUSA) {
                    conversa = db.insert("conversas", null, ContentValues().apply {
                        put("inicio", quando); put("ultimo", quando); put("titulo", ""); put("resumo", "")
                    })
                    titulo = ""
                }
                if (titulo.isBlank() && doUsuario) {
                    titulo = BuscaDeConversas.tituloProvisorio(textos[id].orEmpty())
                    db.execSQL("UPDATE conversas SET titulo = ? WHERE id = ?", arrayOf(titulo, conversa))
                }
                db.execSQL("UPDATE mensagens SET conversa = ? WHERE id = ?", arrayOf(conversa, id))
                db.execSQL("UPDATE conversas SET ultimo = ? WHERE id = ?", arrayOf(quando, conversa))
                ultimo = quando
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
    private fun controle(chave: String): String? = readableDatabase.rawQuery(
        "SELECT valor FROM controle WHERE chave = ?", arrayOf(chave)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun definirControle(chave: String, valor: String) {
        writableDatabase.insertWithOnConflict("controle", null, ContentValues().apply {
            put("chave", chave); put("valor", valor)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** A conversa em andamento (0 = a próxima mensagem começa uma conversa nova). */
    fun conversaAtual(): Long = controle("conversa_atual")?.toLongOrNull() ?: 0L

    private fun ultimoDa(id: Long): Long = readableDatabase.rawQuery(
        "SELECT ultimo FROM conversas WHERE id = ?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** A conversa atual, se ainda está "viva" (última mensagem há menos de 3 horas). */
    fun conversaRecente(agora: Long = System.currentTimeMillis()): Long? {
        val id = conversaAtual()
        if (id == 0L) return null
        return id.takeIf { agora - ultimoDa(it) < PAUSA }
    }

    private fun garantirConversa(agora: Long): Long {
        conversaRecente(agora)?.let { return it }
        val id = writableDatabase.insert("conversas", null, ContentValues().apply {
            put("inicio", agora); put("ultimo", agora); put("titulo", ""); put("resumo", "")
        })
        definirControle("conversa_atual", id.toString())
        return id
    }

    /** "Nova conversa": a atual fica arquivada e a próxima mensagem começa outra, em branco. */
    fun novaConversa() = definirControle("conversa_atual", "0")

    /** Volta para uma conversa antiga: as próximas mensagens entram nela. */
    fun continuarConversa(id: Long) {
        definirControle("conversa_atual", id.toString())
        writableDatabase.execSQL("UPDATE conversas SET ultimo = ? WHERE id = ?", arrayOf(System.currentTimeMillis(), id))
    }

    fun salvarMensagem(doUsuario: Boolean, texto: String, topico: String) {
        val agora = System.currentTimeMillis()
        val conversa = garantirConversa(agora)
        val v = ContentValues().apply {
            put("quando", agora)
            put("autor", if (doUsuario) "eu" else "sexta")
            put("texto", texto)
            put("topico", topico)
            put("conversa", conversa)
        }
        writableDatabase.insert("mensagens", null, v)
        writableDatabase.execSQL("UPDATE conversas SET ultimo = ? WHERE id = ?", arrayOf(agora, conversa))
        if (doUsuario && !texto.startsWith("Fontes: ")) {
            writableDatabase.execSQL(
                "UPDATE conversas SET titulo = ? WHERE id = ? AND (titulo IS NULL OR titulo = '')",
                arrayOf(BuscaDeConversas.tituloProvisorio(texto), conversa),
            )
        }
    }

    /** Tira a última mensagem do usuário da conversa (ela vai para a conversa retomada). Apaga a conversa se ficar vazia. */
    fun moverUltimaMensagem(de: Long) {
        if (de == 0L) return
        writableDatabase.execSQL(
            "DELETE FROM mensagens WHERE id = (SELECT MAX(id) FROM mensagens WHERE conversa = ? AND autor = 'eu')", arrayOf(de)
        )
        val resta = readableDatabase.rawQuery("SELECT COUNT(*) FROM mensagens WHERE conversa = ?", arrayOf(de.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (resta == 0) writableDatabase.delete("conversas", "id = ?", arrayOf(de.toString()))
    }

    fun mensagensDaConversa(id: Long, limite: Int = 300): List<Mensagem> {
        val lista = mutableListOf<Mensagem>()
        readableDatabase.rawQuery(
            "SELECT quando, autor, texto, topico, conversa FROM mensagens WHERE conversa = ? ORDER BY id DESC LIMIT $limite", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) lista += Mensagem(c.getLong(0), c.getString(1) == "eu", c.getString(2), c.getString(3), c.getLong(4))
        }
        return lista.reversed()
    }

    /** Todas as conversas, das mais recentes para as mais antigas. */
    fun conversas(limite: Int = 300): List<Conversa> {
        val lista = mutableListOf<Conversa>()
        readableDatabase.rawQuery(
            "SELECT c.id, c.inicio, c.ultimo, c.titulo, c.resumo, COUNT(m.id) FROM conversas c JOIN mensagens m ON m.conversa = c.id " +
                "WHERE m.texto NOT LIKE 'Fontes: %' GROUP BY c.id ORDER BY c.ultimo DESC LIMIT $limite", null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val previa = readableDatabase.rawQuery(
                    "SELECT texto FROM mensagens WHERE conversa = ? AND autor = 'eu' ORDER BY id DESC LIMIT 1", arrayOf(id.toString())
                ).use { p -> if (p.moveToFirst()) p.getString(0) else "" }
                val topico = readableDatabase.rawQuery(
                    "SELECT topico FROM mensagens WHERE conversa = ? AND topico != 'Conversa' GROUP BY topico ORDER BY COUNT(*) DESC LIMIT 1",
                    arrayOf(id.toString())
                ).use { p -> if (p.moveToFirst()) p.getString(0) else "Conversa" }
                lista += Conversa(id, c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getInt(5), previa, topico)
            }
        }
        return lista
    }

    fun conversa(id: Long): Conversa? = conversas(1000).firstOrNull { it.id == id }

    /** Procura conversas pelo assunto (aceita plural, erros de digitação e palavras soltas). Melhores primeiro. */
    fun buscarConversas(assunto: String, excluir: Long = -1, minimo: Double = 0.5): List<Conversa> {
        val consulta = BuscaDeConversas.palavras(assunto)
        if (consulta.isEmpty()) return emptyList()
        val achadas = mutableListOf<Conversa>()
        for (c in conversas(1000)) {
            if (c.id == excluir) continue
            val textos = mensagensDaConversa(c.id, 400).map { it.texto }
            val nota = BuscaDeConversas.nota(consulta, textos, c.titulo, c.resumo)
            if (nota >= minimo) achadas += c.copy(trecho = BuscaDeConversas.melhorTrecho(consulta, textos), nota = nota)
        }
        // mais palavras encontradas primeiro; no empate, a mais recente
        return achadas.sortedWith(compareByDescending<Conversa> { it.nota }.thenByDescending { it.ultimo })
    }

    fun definirResumo(id: Long, titulo: String, resumo: String) {
        writableDatabase.execSQL("UPDATE conversas SET titulo = ?, resumo = ? WHERE id = ?", arrayOf(titulo, resumo, id))
    }

    /** Conversas já encerradas, com pelo menos 4 mensagens, que a IA ainda não resumiu. */
    fun conversasSemResumo(limite: Int = 3): List<Conversa> {
        val atual = conversaRecente()
        return conversas(200).filter { it.id != atual && it.quantidade >= 4 && it.resumo.isBlank() }.take(limite)
    }

    fun apagarConversa(id: Long) {
        writableDatabase.delete("mensagens", "conversa = ?", arrayOf(id.toString()))
        writableDatabase.delete("conversas", "id = ?", arrayOf(id.toString()))
        if (conversaAtual() == id) novaConversa()
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
        writableDatabase.delete("conversas", null, null)
        novaConversa()
    }

    companion object {
        /** Mais de 3 horas sem falar: a próxima mensagem começa uma conversa nova. */
        const val PAUSA = 3 * 3600_000L
        private const val TABELA_CONVERSAS =
            "CREATE TABLE IF NOT EXISTS conversas (id INTEGER PRIMARY KEY AUTOINCREMENT, inicio INTEGER, ultimo INTEGER, titulo TEXT, resumo TEXT)"
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
