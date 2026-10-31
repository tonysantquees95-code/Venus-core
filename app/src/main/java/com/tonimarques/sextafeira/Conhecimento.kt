package com.tonimarques.sextafeira

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.TimeUnit

/**
 * O que a Vênus sabe por conta própria, guardado no celular:
 *  - novidades dos assuntos que ela acompanha (atualizadas sozinhas a cada 6 horas);
 *  - respostas que ela já deu (inclusive as que vieram do Gemini), para não precisar perguntar de novo.
 */
class Conhecimento(private val memoria: Memoria) {

    private val db get() = memoria.writableDatabase

    // ------------------------------------------------------------- assuntos
    fun temas(): List<String> {
        if (valor("temas_iniciados") == null) {
            for (t in TEMAS_INICIAIS) inserirTema(t)
            definir("temas_iniciados", "1")
        }
        val lista = mutableListOf<String>()
        memoria.readableDatabase.rawQuery("SELECT nome FROM temas ORDER BY nome", null).use { c ->
            while (c.moveToNext()) lista += c.getString(0)
        }
        return lista
    }

    private fun inserirTema(tema: String) {
        db.insertWithOnConflict("temas", null, ContentValues().apply { put("nome", tema.trim()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun seguir(tema: String) {
        temas()
        inserirTema(tema)
    }

    /** Para de acompanhar os assuntos que contêm o trecho. Devolve quantos. */
    fun parar(trecho: String): Int {
        val alvo = ComandosLocais.normalizar(trecho).trim()
        var n = 0
        for (t in temas()) {
            if (ComandosLocais.normalizar(t).contains(alvo) || alvo.contains(ComandosLocais.normalizar(t))) {
                n += db.delete("temas", "nome = ?", arrayOf(t))
            }
        }
        return n
    }

    // ------------------------------------------------------------- novidades
    fun precisaAtualizar(): Boolean {
        val ultima = valor("atualizado")?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - ultima > TimeUnit.HOURS.toMillis(6)
    }

    /** Baixa as novidades de todos os assuntos acompanhados. Rodar fora da thread principal. Devolve quantas novas. */
    fun atualizar(): Int {
        var novas = 0
        for (tema in temas()) {
            val lista = try {
                Pesquisa.noticias("$tema when:7d", 15)
            } catch (e: Exception) {
                continue
            }
            novas += guardar(tema, lista)
        }
        // Mantém só o último mês, para o banco não crescer sem parar.
        db.delete("noticias", "coletado < ?", arrayOf((System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)).toString()))
        definir("atualizado", System.currentTimeMillis().toString())
        return novas
    }

    fun guardar(tema: String, lista: List<Pesquisa.Noticia>): Int {
        var novas = 0
        val agora = System.currentTimeMillis()
        for (n in lista) {
            val id = db.insertWithOnConflict("noticias", null, ContentValues().apply {
                put("tema", tema)
                put("titulo", n.titulo)
                put("fonte", n.fonte)
                put("link", n.link)
                put("data", n.data)
                put("coletado", agora)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) novas++
        }
        return novas
    }

    /** As novidades guardadas que mais combinam com a pergunta (mais recentes primeiro, em caso de empate). */
    fun buscar(pergunta: String, maximo: Int = 3): List<Pesquisa.Noticia> {
        val chaves = Pesquisa.palavrasChave(pergunta)
        if (chaves.isEmpty()) return emptyList()
        val candidatas = mutableListOf<Pair<Int, Pesquisa.Noticia>>()
        memoria.readableDatabase.rawQuery(
            "SELECT tema, titulo, fonte, link, data FROM noticias ORDER BY data DESC LIMIT 600", null
        ).use { c ->
            while (c.moveToNext()) {
                val pontos = Pesquisa.pontuar(chaves, c.getString(1), c.getString(0))
                if (pontos >= 2) candidatas += pontos to Pesquisa.Noticia(c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
            }
        }
        return candidatas.sortedWith(compareByDescending<Pair<Int, Pesquisa.Noticia>> { it.first }.thenByDescending { it.second.data })
            .map { it.second }
            .distinctBy { ComandosLocais.normalizar(it.titulo).take(40) }
            .take(maximo)
    }

    fun totalDeNovidades(): Int = memoria.readableDatabase.rawQuery("SELECT COUNT(*) FROM noticias", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // -------------------------------------------------- respostas guardadas
    /** Guarda uma resposta para reaproveitar depois (só para perguntas que não mudam com o tempo). */
    fun lembrarResposta(pergunta: String, resposta: String) {
        if (!vale(pergunta) || resposta.length < 10) return
        db.insert("respostas", null, ContentValues().apply {
            put("pergunta", pergunta)
            put("resposta", resposta)
            put("quando", System.currentTimeMillis())
        })
        db.execSQL("DELETE FROM respostas WHERE id NOT IN (SELECT id FROM respostas ORDER BY id DESC LIMIT 500)")
    }

    /** Uma resposta já dada para uma pergunta praticamente igual, ou null. */
    fun respostaGuardada(pergunta: String): String? {
        if (!vale(pergunta)) return null
        val alvo = Cerebro.conceitos(pergunta)
        if (alvo.size < 2) return null
        val limite = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)
        memoria.readableDatabase.rawQuery(
            "SELECT pergunta, resposta FROM respostas WHERE quando > ? ORDER BY id DESC", arrayOf(limite.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val outra = Cerebro.conceitos(c.getString(0))
                if (outra == alvo || Cerebro.similaridade(alvo, outra, pergunta = true) >= 0.9) return c.getString(1)
            }
        }
        return null
    }

    // ------------------------------------------------------------- controle
    private fun valor(chave: String): String? = memoria.readableDatabase.rawQuery(
        "SELECT valor FROM controle WHERE chave = ?", arrayOf(chave)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun definir(chave: String, valor: String) {
        db.insertWithOnConflict("controle", null, ContentValues().apply {
            put("chave", chave)
            put("valor", valor)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        val TEMAS_INICIAIS = listOf("lançamentos de celulares", "tecnologia", "e-commerce no Brasil")

        /** Perguntas sobre o momento atual não devem ser respondidas com respostas antigas. */
        private val TEMPORAL = Regex(
            "\\b(hoje|agora|amanha|ontem|atual|atualmente|recente\\w*|ultim\\w*|noticia\\w*|novidade\\w*|preco\\w*|" +
                "valor|cotacao|dolar|euro|clima|tempo|chuva|graus|placar|jogo|resultado|lancad\\w*|lancament\\w*|semana|mes)\\b"
        )

        fun vale(pergunta: String) = !TEMPORAL.containsMatchIn(ComandosLocais.normalizar(pergunta))
    }
}
