package com.tonimarques.sextafeira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A memória da Sexta-feira, guardada só no celular (banco SQLite do app):
 *  - mensagens: todas as conversas, cada uma com um tópico;
 *  - memorias: fatos e preferências sobre você ("gosto de rock", cidade, apelido, favoritos);
 *  - aprendidas: frases novas que ela aprendeu quando você confirmou o que queria dizer.
 *    Cada frase guarda também o "vetor semântico" (embedding do Gemini), para achar frases de
 *    mesmo sentido ("abre o zap" ~ "abrir o whatsapp") pela similaridade de cosseno;
 *  - uso: o que você mais usa (apps, músicas, contatos), para entender "o de sempre".
 */
class Memoria(contexto: Context) : SQLiteOpenHelper(contexto, "sexta.db", null, 5) {

    data class Mensagem(val quando: Long, val doUsuario: Boolean, val texto: String, val topico: String, val conversa: Long = 0)

    /** Uma conversa: começa quando você pede "nova conversa" ou volta depois de umas horas sem falar. */
    data class Conversa(
        val id: Long, val inicio: Long, val ultimo: Long, val titulo: String, val resumo: String,
        val quantidade: Int, val previa: String, val topico: String, val trecho: String? = null, val nota: Double = 0.0,
    )
    data class ResumoTopico(val topico: String, val quantidade: Int, val ultimo: Long, val previa: String)
    data class Fato(val id: Long, val quando: Long, val texto: String, val categoria: String)

    /** Uma frase já aprendida que tem o mesmo sentido do que foi dito. nota: 0 a 1 (1 = igual). */
    data class Parecida(val frase: String, val intencao: String, val nota: Double)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mensagens (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, autor TEXT, texto TEXT, topico TEXT, conversa INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_topico ON mensagens(topico)")
        db.execSQL("CREATE INDEX idx_conversa ON mensagens(conversa)")
        db.execSQL(TABELA_CONVERSAS)
        db.execSQL("CREATE TABLE memorias (id INTEGER PRIMARY KEY AUTOINCREMENT, quando INTEGER, texto TEXT, categoria TEXT)")
        db.execSQL("CREATE TABLE aprendidas (id INTEGER PRIMARY KEY AUTOINCREMENT, frase TEXT UNIQUE, intencao TEXT, vetor BLOB, modelo TEXT)")
        db.execSQL("CREATE TABLE uso (chave TEXT PRIMARY KEY, contagem INTEGER, ultimo INTEGER)")
        criarTabelasDeConhecimento(db)
        db.execSQL(TABELA_VETORES)
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
        if (antiga < 2) criarTabelasDeConhecimento(db)
        if (antiga < 3) separarEmConversas(db)
        if (antiga < 4) acrescentarVetores(db)
        if (antiga < 5) db.execSQL(TABELA_VETORES)
    }

    /**
     * Versão 4: cada frase aprendida ganha o vetor semântico (BLOB) e o nome do modelo que o gerou.
     * As frases antigas ficam sem vetor até [completarVetores] rodar; nada é apagado.
     */
    private fun acrescentarVetores(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE aprendidas ADD COLUMN vetor BLOB")
        db.execSQL("ALTER TABLE aprendidas ADD COLUMN modelo TEXT")
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
        /**
         * Versão 5: a memória vetorial geral. Cada linha é algo que ela sabe, com o vetor do texto:
         *  - comando: frase → ação (e argumentos) que o app executa;
         *  - intencao: frase → intenção do vocabulário (as "aprendidas");
         *  - resposta: pergunta atemporal → resposta já dada;
         *  - enigma: charada ou problema de lógica → resposta conferida;
         *  - fato: algo sobre você (vira contexto para a IA);
         *  - conversa: resumo de uma conversa antiga.
         */
        private const val TABELA_VETORES =
            "CREATE TABLE IF NOT EXISTS vetores (id INTEGER PRIMARY KEY AUTOINCREMENT, tipo TEXT NOT NULL, texto TEXT NOT NULL, " +
                "resposta TEXT, acao TEXT, args TEXT, fonte TEXT, vetor BLOB, modelo TEXT, criado INTEGER, usos INTEGER DEFAULT 0, " +
                "UNIQUE(tipo, texto))"

        private const val TABELA_CONVERSAS =
            "CREATE TABLE IF NOT EXISTS conversas (id INTEGER PRIMARY KEY AUTOINCREMENT, inicio INTEGER, ultimo INTEGER, titulo TEXT, resumo TEXT)"

        /**
         * Nota mínima de similaridade (0 a 1) para dizer que duas frases têm o mesmo sentido.
         * 0,80 é um ponto de partida: se ela confundir frases diferentes, suba (0,85); se não
         * reconhecer sinônimos óbvios, desça (0,75).
         */
        const val MINIMO_SENTIDO = 0.80

        /** Para vetores já normalizados (comprimento 1) o cosseno é só o produto interno: bem mais rápido. */
        fun produtoInterno(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return 0.0
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s.toDouble()
        }

        /** Similaridade de cosseno entre dois vetores: 1 = mesma direção, 0 = sem relação. Sem bibliotecas. */
        fun similaridadeCosseno(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || a.size != b.size) return 0.0
            var produto = 0.0
            var normaA = 0.0
            var normaB = 0.0
            for (i in a.indices) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                produto += x * y
                normaA += x * x
                normaB += y * y
            }
            if (normaA == 0.0 || normaB == 0.0) return 0.0
            return produto / (Math.sqrt(normaA) * Math.sqrt(normaB))
        }

        /** Vetor -> bytes (4 por número) para guardar na coluna BLOB. */
        fun vetorParaBytes(vetor: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vetor.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(vetor)
            return buffer.array()
        }

        /** Teto da memória vetorial: com o formato compacto, cerca de 50 MB no celular e a busca continua rápida. */
        const val MAXIMO_ITENS = 60_000

        /** Vetores em blocos de 4.096 dentro do índice (nenhum pedaço grande demais de memória). */
        private const val BLOCO = 4096

        /** Formato compacto: 'Q' + escala (float) + 1 byte por número. */
        private const val CABECALHO = 5
        private const val MARCA: Byte = 'Q'.code.toByte()

        @Volatile
        private var geracaoGlobal = 0L

        fun ehCompacto(b: ByteArray) = b.size > CABECALHO && b.size % 4 == 1 && b[0] == MARCA

        /** Vetor -> 'Q' + escala + cada número em 1 byte (-127..127): 4 vezes menor, quase a mesma nota. */
        fun compactar(v: FloatArray): ByteArray {
            var maior = 0f
            for (x in v) maior = maxOf(maior, Math.abs(x))
            val escala = if (maior == 0f) 1f else maior / 127f
            val saida = ByteArray(CABECALHO + v.size)
            saida[0] = MARCA
            ByteBuffer.wrap(saida, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(escala)
            for (i in v.indices) saida[CABECALHO + i] = Math.round(v[i] / escala).coerceIn(-127, 127).toByte()
            return saida
        }

        /** Volta do formato compacto (ou do antigo) para números. */
        fun descompactar(b: ByteArray): FloatArray {
            if (!ehCompacto(b)) return bytesParaVetor(b)
            val escala = ByteBuffer.wrap(b, 1, 4).order(ByteOrder.LITTLE_ENDIAN).float
            return FloatArray(b.size - CABECALHO) { b[CABECALHO + it] * escala }
        }

        /** Bytes da coluna BLOB -> vetor. */
        fun bytesParaVetor(bytes: ByteArray): FloatArray {
            val vetor = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vetor)
            return vetor
        }
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
    /**
     * Guarda uma frase nova e o que ela significa. O vetor é opcional: sem ele a frase fica
     * guardada e ganha o vetor depois, em [completarVetores]. Mantém a chamada antiga: aprender(frase, intencao).
     */
    fun aprender(frase: String, intencao: String, vetor: FloatArray? = null, modelo: String? = null) {
        writableDatabase.insertWithOnConflict("aprendidas", null, ContentValues().apply {
            put("frase", frase)
            put("intencao", intencao)
            if (vetor != null && modelo != null) {
                put("vetor", vetorParaBytes(vetor))
                put("modelo", modelo)
            }
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun aprendidas(): List<Pair<String, String>> {
        val lista = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT frase, intencao FROM aprendidas", null).use { c ->
            while (c.moveToNext()) lista += c.getString(0) to c.getString(1)
        }
        return lista
    }

    /**
     * Guarda a frase já com o vetor semântico. Faz chamada de internet (Gemini):
     * use numa thread de segundo plano (ex.: Dispatchers.IO), nunca na principal.
     */
    fun aprenderComSentido(frase: String, intencao: String, chaveApi: String) {
        val vetor = EmbeddingsGemini.gerar(frase, chaveApi)
        aprender(frase, intencao, vetor, if (vetor != null) EmbeddingsGemini.MODELO else null)
    }

    /**
     * Acha o que você já ensinou que tem o mesmo sentido da frase dita (sinônimos, outras palavras).
     * 1) frase igual (sem acento e maiúscula) já resolve sem internet;
     * 2) senão, gera o vetor da frase e compara com os guardados pela similaridade de cosseno;
     * 3) se não houver internet ou chave, devolve null (o app segue o caminho normal).
     * Faz chamada de internet: use em segundo plano.
     */
    fun entenderPorSentido(frase: String, chaveApi: String, minimo: Double = MINIMO_SENTIDO): Parecida? {
        acharIgual(frase)?.let { return it }
        val vetor = EmbeddingsGemini.gerar(frase, chaveApi) ?: return null
        return buscarParecidas(vetor, EmbeddingsGemini.MODELO, minimo, 1).firstOrNull()
    }

    /** As frases aprendidas mais parecidas com o vetor, da maior nota para a menor. Só compara vetores do mesmo modelo. */
    fun buscarParecidas(consulta: FloatArray, modelo: String, minimo: Double = MINIMO_SENTIDO, quantos: Int = 3): List<Parecida> {
        val achadas = mutableListOf<Parecida>()
        readableDatabase.rawQuery(
            "SELECT frase, intencao, vetor FROM aprendidas WHERE vetor IS NOT NULL AND modelo = ?", arrayOf(modelo)
        ).use { c ->
            while (c.moveToNext()) {
                val nota = similaridadeCosseno(consulta, bytesParaVetor(c.getBlob(2)))
                if (nota >= minimo) achadas += Parecida(c.getString(0), c.getString(1), nota)
            }
        }
        return achadas.sortedByDescending { it.nota }.take(quantos)
    }

    /** Busca de reserva, sem internet: a mesma frase, ignorando acentos, maiúsculas e espaços nas pontas. */
    private fun acharIgual(frase: String): Parecida? {
        val alvo = ComandosLocais.normalizar(frase).trim()
        if (alvo.isEmpty()) return null
        val achada = aprendidas().firstOrNull { ComandosLocais.normalizar(it.first).trim() == alvo } ?: return null
        return Parecida(achada.first, achada.second, 1.0)
    }

    /**
     * Gera os vetores das frases que ainda não têm (as antigas, ou as de outro modelo).
     * Chame uma vez em segundo plano depois de atualizar o app. Devolve quantas foram completadas;
     * para na primeira falha de internet e continua na próxima vez.
     */
    fun completarVetores(chaveApi: String, limite: Int = 50): Int {
        val pendentes = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT id, frase FROM aprendidas WHERE vetor IS NULL OR modelo IS NULL OR modelo != ? LIMIT $limite",
            arrayOf(EmbeddingsGemini.MODELO)
        ).use { c -> while (c.moveToNext()) pendentes += c.getLong(0) to c.getString(1) }
        var feitas = 0
        for ((id, frase) in pendentes) {
            val vetor = EmbeddingsGemini.gerar(frase, chaveApi) ?: break
            writableDatabase.update("aprendidas", ContentValues().apply {
                put("vetor", vetorParaBytes(vetor))
                put("modelo", EmbeddingsGemini.MODELO)
            }, "id = ?", arrayOf(id.toString()))
            feitas++
        }
        return feitas
    }

    // ------------------------------------------------------ memória vetorial geral
    data class Item(
        val id: Long, val tipo: String, val texto: String, val resposta: String?, val acao: String?, val args: String?,
        val fonte: String?,
    )

    data class Achado(val item: Item, val nota: Double)

    /**
     * Vetores em memória, compactos (1 byte por número + 1 escala por vetor), em blocos de [BLOCO] vetores,
     * para caberem dezenas de milhares sem pesar. O texto de cada item fica no banco e só é lido para os achados.
     */
    private class Indice(val modelo: String, var geracao: Long) {
        var dim = 0
        var n = 0
        var ids = LongArray(1024)
        var tipos = arrayOfNulls<String>(1024)
        var escalas = FloatArray(1024)
        val blocos = ArrayList<ByteArray>()

        fun somar(id: Long, tipo: String, compacto: ByteArray) {
            val d = compacto.size - CABECALHO
            if (d <= 0) return
            if (dim == 0) dim = d
            if (d != dim) return
            if (n == ids.size) {
                ids = ids.copyOf(n * 2)
                tipos = tipos.copyOf(n * 2)
                escalas = escalas.copyOf(n * 2)
            }
            if (n / BLOCO >= blocos.size) blocos += ByteArray(BLOCO * dim)
            ids[n] = id
            tipos[n] = tipo.intern()
            escalas[n] = ByteBuffer.wrap(compacto, 1, 4).order(ByteOrder.LITTLE_ENDIAN).float
            System.arraycopy(compacto, CABECALHO, blocos[n / BLOCO], (n % BLOCO) * dim, dim)
            n++
        }

        fun nota(i: Int, consulta: FloatArray): Double {
            val b = blocos[i / BLOCO]
            val ini = (i % BLOCO) * dim
            var s = 0f
            for (j in 0 until dim) s += consulta[j] * b[ini + j]
            return (s * escalas[i]).toDouble()
        }
    }

    @Volatile
    private var indice: Indice? = null

    /**
     * Guarda (ou atualiza) um item. [vetor] é opcional: sem ele, o item ganha o vetor depois em [vetoresPendentes].
     * Devolve true se o item é novo.
     */
    fun guardarVetor(
        tipo: String, texto: String, resposta: String? = null, acao: String? = null, args: String? = null,
        fonte: String? = null, vetor: FloatArray? = null, modelo: String? = null,
    ): Boolean {
        val t = texto.trim().take(1000)
        if (t.isEmpty()) return false
        val compacto = if (vetor != null && modelo != null) compactar(vetor) else null
        val valores = ContentValues().apply {
            put("tipo", tipo)
            put("texto", t)
            put("resposta", resposta?.take(4000))
            put("acao", acao)
            put("args", args)
            put("fonte", fonte)
            put("criado", System.currentTimeMillis())
            if (compacto != null) {
                put("vetor", compacto)
                put("modelo", modelo)
            }
        }
        val id = writableDatabase.insertWithOnConflict("vetores", null, valores, SQLiteDatabase.CONFLICT_IGNORE)
        val novo = id != -1L
        if (!novo) {
            valores.remove("criado")
            writableDatabase.update("vetores", valores, "tipo = ? AND texto = ?", arrayOf(tipo, t))
            mudou(null)
        } else if (compacto != null) {
            if (indice?.modelo == modelo) mudou { ind -> ind.somar(id, tipo, compacto) } else mudou(null)
        }
        return novo
    }

    /** Avisa as outras instâncias (o estudo roda em outra) e atualiza o índice desta sem reler tudo. */
    private fun mudou(acrescentar: ((Indice) -> Unit)?) {
        synchronized(Memoria::class.java) {
            val atual = indice
            geracaoGlobal++
            if (acrescentar != null && atual != null && atual.geracao == geracaoGlobal - 1) {
                acrescentar(atual)
                atual.geracao = geracaoGlobal
            } else {
                indice = null
            }
        }
    }

    /**
     * Os itens mais parecidos com o vetor da consulta (similaridade de cosseno), da maior nota para a menor.
     * [tipos] vazio = todos. Só compara vetores do mesmo [modelo].
     */
    fun buscarVetores(consulta: FloatArray, modelo: String, tipos: Set<String> = emptySet(), minimo: Double = MINIMO_SENTIDO, quantos: Int = 5): List<Achado> {
        val ind = indice?.takeIf { it.modelo == modelo && it.geracao == geracaoGlobal } ?: carregarIndice(modelo)
        if (ind.n == 0 || consulta.size != ind.dim) return emptyList()
        val melhores = ArrayList<Pair<Int, Double>>()
        for (i in 0 until ind.n) {
            if (tipos.isNotEmpty() && ind.tipos[i]!! !in tipos) continue
            val nota = ind.nota(i, consulta)
            if (nota < minimo) continue
            melhores += i to nota
            if (melhores.size > quantos * 8) {
                melhores.sortByDescending { it.second }
                while (melhores.size > quantos) melhores.removeAt(melhores.size - 1)
            }
        }
        val topo = melhores.sortedByDescending { it.second }.take(quantos)
        if (topo.isEmpty()) return emptyList()
        val itens = itensPorId(topo.map { ind.ids[it.first] })
        return topo.mapNotNull { (i, nota) -> itens[ind.ids[i]]?.let { Achado(it, nota) } }
    }

    private fun itensPorId(ids: List<Long>): Map<Long, Item> {
        val mapa = HashMap<Long, Item>()
        readableDatabase.rawQuery(
            "SELECT id, tipo, texto, resposta, acao, args, fonte FROM vetores WHERE id IN (${ids.joinToString(",")})", null
        ).use { c ->
            while (c.moveToNext()) mapa[c.getLong(0)] = Item(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
        }
        return mapa
    }

    private fun carregarIndice(modelo: String): Indice {
        val geracao = synchronized(Memoria::class.java) { geracaoGlobal }
        val ind = Indice(modelo, geracao)
        readableDatabase.rawQuery("SELECT id, tipo, vetor FROM vetores WHERE vetor IS NOT NULL AND modelo = ?", arrayOf(modelo)).use { c ->
            while (c.moveToNext()) {
                val b = c.getBlob(2) ?: continue
                ind.somar(c.getLong(0), c.getString(1), if (ehCompacto(b)) b else compactar(bytesParaVetor(b)))
            }
        }
        return ind.also { indice = it }
    }

    fun contarUso(id: Long) {
        writableDatabase.execSQL("UPDATE vetores SET usos = usos + 1 WHERE id = ?", arrayOf(id))
    }

    /** Itens sem vetor (ou com vetor de outro modelo), para completar em lote. */
    fun vetoresPendentes(modelo: String, limite: Int): List<Pair<Long, String>> {
        val lista = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT id, texto FROM vetores WHERE vetor IS NULL OR modelo IS NULL OR modelo != ? LIMIT $limite", arrayOf(modelo)
        ).use { c -> while (c.moveToNext()) lista += c.getLong(0) to c.getString(1) }
        return lista
    }

    fun salvarVetores(vetores: List<Pair<Long, FloatArray>>, modelo: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, v) in vetores) {
                db.update("vetores", ContentValues().apply {
                    put("vetor", compactar(v))
                    put("modelo", modelo)
                }, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        mudou(null)
    }

    // ------------------------------------------------------ manutenção (estudo diário)
    /** Passa os vetores antigos (4 bytes por número) para o formato compacto. Devolve quantos converteu. */
    fun compactarAntigos(limite: Int = 5000): Int {
        val antigos = mutableListOf<Pair<Long, ByteArray>>()
        readableDatabase.rawQuery("SELECT id, vetor FROM vetores WHERE vetor IS NOT NULL AND length(vetor) % 4 = 0 LIMIT $limite", null).use { c ->
            while (c.moveToNext()) antigos += c.getLong(0) to c.getBlob(1)
        }
        if (antigos.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, b) in antigos) db.update("vetores", ContentValues().apply { put("vetor", compactar(bytesParaVetor(b))) }, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        mudou(null)
        return antigos.size
    }

    /**
     * Tira o que o estudo criou e nunca foi usado depois de [dias] dias (frases de comando e charadas),
     * as ideias de post velhas, e mantém o total abaixo de [MAXIMO_ITENS] (sai o mais antigo e nunca usado).
     * O que você ensinou, as respostas para clientes e o que já foi usado ficam. Devolve quantos saíram.
     */
    fun limparEstudoVelho(dias: Int = 60, agora: Long = System.currentTimeMillis()): Int {
        val limite = (agora - dias * 86_400_000L).toString()
        val db = writableDatabase
        var removidos = db.delete("vetores", "fonte = 'estudo' AND usos = 0 AND criado < ? AND tipo IN ('comando', 'enigma')", arrayOf(limite))
        removidos += db.delete("vetores", "tipo = 'ideia_post' AND criado < ?", arrayOf(limite))
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM vetores", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val excesso = total - MAXIMO_ITENS
        if (excesso > 0) {
            removidos += db.delete(
                "vetores",
                "id IN (SELECT id FROM vetores WHERE fonte = 'estudo' AND usos = 0 AND tipo IN ('comando', 'enigma', 'objecao') ORDER BY criado LIMIT $excesso)",
                null,
            )
        }
        if (removidos > 0) mudou(null)
        return removidos
    }

    /** As mensagens (suas e dela) desde [desde], na ordem, para a revisão do dia. */
    fun mensagensDesde(desde: Long, limite: Int = 300): List<Mensagem> {
        val lista = mutableListOf<Mensagem>()
        readableDatabase.rawQuery(
            "SELECT quando, autor, texto, topico FROM mensagens WHERE quando >= ? ORDER BY id DESC LIMIT $limite", arrayOf(desde.toString())
        ).use { c -> while (c.moveToNext()) lista += Mensagem(c.getLong(0), c.getString(1) == "eu", c.getString(2), c.getString(3)) }
        return lista.reversed()
    }

    /** Apaga as respostas guardadas (tipo "resposta") que [ruim] reprovar. */
    fun apagarRespostas(ruim: (String, String) -> Boolean): Int {
        val apagar = mutableListOf<Long>()
        readableDatabase.rawQuery("SELECT id, texto, resposta FROM vetores WHERE tipo = 'resposta'", null).use { c ->
            while (c.moveToNext()) if (ruim(c.getString(1).orEmpty(), c.getString(2).orEmpty())) apagar += c.getLong(0)
        }
        if (apagar.isEmpty()) return 0
        apagar.chunked(500).forEach { writableDatabase.delete("vetores", "id IN (${it.joinToString(",")})", null) }
        mudou(null)
        return apagar.size
    }

    /** Deixa só os [quantos] mais novos de um tipo (as lições, por exemplo). Devolve quantos saíram. */
    fun manterUltimos(tipo: String, quantos: Int): Int {
        val n = writableDatabase.delete(
            "vetores", "tipo = ? AND id NOT IN (SELECT id FROM vetores WHERE tipo = ? ORDER BY id DESC LIMIT $quantos)", arrayOf(tipo, tipo)
        )
        if (n > 0) mudou(null)
        return n
    }

    /** Apaga um aprendizado (pela tela "O que eu aprendi"). */
    fun apagarItem(id: Long): Boolean {
        val n = writableDatabase.delete("vetores", "id = ?", arrayOf(id.toString()))
        if (n > 0) mudou(null)
        return n > 0
    }

    /** Itens de um tipo, mais novos primeiro (para mostrar as ideias de post, por exemplo). */
    fun itensDoTipo(tipo: String, quantos: Int): List<Item> {
        val lista = mutableListOf<Item>()
        readableDatabase.rawQuery(
            "SELECT id, tipo, texto, resposta, acao, args, fonte FROM vetores WHERE tipo = ? ORDER BY id DESC LIMIT $quantos", arrayOf(tipo)
        ).use { c -> while (c.moveToNext()) lista += Item(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)) }
        return lista
    }

    /** Quantos itens de cada tipo (e quantos já têm vetor). */
    fun contarVetores(): Map<String, Pair<Int, Int>> {
        val mapa = mutableMapOf<String, Pair<Int, Int>>()
        readableDatabase.rawQuery("SELECT tipo, COUNT(*), SUM(CASE WHEN vetor IS NULL THEN 0 ELSE 1 END) FROM vetores GROUP BY tipo", null).use { c ->
            while (c.moveToNext()) mapa[c.getString(0)] = c.getInt(1) to c.getInt(2)
        }
        return mapa
    }

    /** Os últimos textos de um tipo (para o estudo diário não repetir). */
    fun ultimosTextos(tipo: String, quantos: Int): List<String> {
        val lista = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT texto FROM vetores WHERE tipo = ? ORDER BY id DESC LIMIT $quantos", arrayOf(tipo)).use { c ->
            while (c.moveToNext()) lista += c.getString(0)
        }
        return lista
    }

    /**
     * Passa para a memória vetorial tudo o que ela sabia só em texto: frases aprendidas, fatos sobre você,
     * respostas guardadas e resumos de conversas. Não apaga nada das tabelas antigas; os vetores
     * são gerados depois, em lote. Pode rodar várias vezes (o que já foi passado é ignorado).
     */
    fun migrarTextoParaVetores(rotas: Map<String, String> = emptyMap()): Int {
        var novos = 0
        for ((frase, intencao) in aprendidas()) if (guardarVetor("intencao", frase, acao = intencao, fonte = "aprendida")) novos++
        for (f in fatos()) if (guardarVetor("fato", f.texto, acao = f.categoria, fonte = "memoria")) novos++
        readableDatabase.rawQuery("SELECT pergunta, resposta FROM respostas ORDER BY id DESC LIMIT 500", null).use { c ->
            while (c.moveToNext()) if (guardarVetor("resposta", c.getString(0), resposta = c.getString(1), fonte = "conhecimento")) novos++
        }
        readableDatabase.rawQuery("SELECT titulo, resumo FROM conversas WHERE resumo IS NOT NULL AND resumo != ''", null).use { c ->
            while (c.moveToNext()) {
                val titulo = c.getString(0).orEmpty()
                val resumo = c.getString(1).orEmpty()
                if (guardarVetor("conversa", "$titulo: $resumo".take(1000), resposta = resumo, fonte = "conversas")) novos++
            }
        }
        // rotas do roteador: frase → {"acao":..,"args":{..}}
        for ((frase, json) in rotas) {
            val o = try {
                org.json.JSONObject(json)
            } catch (e: Exception) {
                continue
            }
            val acao = o.optString("acao")
            if (acao.isBlank()) continue
            if (guardarVetor("comando", frase, acao = acao, args = o.optJSONObject("args")?.toString() ?: "{}", fonte = "roteador")) novos++
        }
        return novos
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
