package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriaVetorialTest {

    @Test fun cossenoEProdutoInterno() {
        val a = EmbeddingsGemini.normalizar(floatArrayOf(3f, 4f))!!
        val b = EmbeddingsGemini.normalizar(floatArrayOf(6f, 8f))!!
        val c = EmbeddingsGemini.normalizar(floatArrayOf(-4f, 3f))!!
        assertEquals(1.0, Memoria.produtoInterno(a, b), 1e-6)
        assertEquals(0.0, Memoria.produtoInterno(a, c), 1e-6)
        assertEquals(Memoria.similaridadeCosseno(floatArrayOf(3f, 4f), floatArrayOf(6f, 8f)), Memoria.produtoInterno(a, b), 1e-6)
        assertNull(EmbeddingsGemini.normalizar(floatArrayOf(0f, 0f)))
        val bytes = Memoria.vetorParaBytes(a)
        assertEquals(a.toList(), Memoria.bytesParaVetor(bytes).toList())
    }

    @Test fun pedidoERespostaDoLote() {
        val corpo = EmbeddingsGemini.corpoDoLote("gemini-embedding-001", listOf("oi", "tchau"), comTarefa = true)
        val pedidos = corpo.getJSONArray("requests")
        assertEquals(2, pedidos.length())
        assertEquals("models/gemini-embedding-001", pedidos.getJSONObject(0).getString("model"))
        assertEquals(EmbeddingsGemini.DIMENSOES, pedidos.getJSONObject(0).getInt("outputDimensionality"))
        assertEquals("SEMANTIC_SIMILARITY", pedidos.getJSONObject(1).getString("taskType"))
        assertFalse(EmbeddingsGemini.corpoDoLote("x", listOf("a"), comTarefa = false).getJSONArray("requests").getJSONObject(0).has("taskType"))
        val lidos = EmbeddingsGemini.lerLote("""{"embeddings":[{"values":[3,4]},{"values":[0,2]}]}""", 2)!!
        assertEquals(0.6f, lidos[0][0], 1e-6f)
        assertEquals(1f, lidos[1][1], 1e-6f)
        assertNull(EmbeddingsGemini.lerLote("""{"embeddings":[{"values":[1]}]}""", 2))
    }

    private fun achado(tipo: String, nota: Double, acao: String? = null, args: String? = null, resposta: String? = null) =
        Memoria.Achado(Memoria.Item(1, tipo, "frase guardada", resposta, acao, args, "teste"), nota)

    @Test fun decisao() {
        val sem = Semantica.decidir(achado("comando", 0.90, "instagram_resumo", "{}"), "como ta nosso insta")
        assertTrue(sem is Semantica.Decisao.Executar && sem.acao == "instagram_resumo")
        assertNull(Semantica.decidir(achado("comando", 0.85, "instagram_resumo", "{}"), "x y"))
        // com argumentos, só frase quase igual (senão trocaria "toca rock" por "toca samba")
        assertNull(Semantica.decidir(achado("comando", 0.92, "loja_estoque", """{"produto":"gel"}"""), "quanto tem do vibrador"))
        assertNotNull(Semantica.decidir(achado("comando", 0.97, "loja_estoque", """{"produto":"gel"}"""), "quanto tem do gel"))
        assertNull(Semantica.decidir(achado("comando", 0.99, "acao_que_nao_existe", "{}"), "x y"))
        assertTrue(Semantica.decidir(achado("enigma", 0.92, resposta = "A sombra."), "o que e o que e") is Semantica.Decisao.Responder)
        assertNull(Semantica.decidir(achado("resposta", 0.95, resposta = "Faz 25 graus."), "como esta o clima hoje"))
        assertTrue(Semantica.decidir(achado("resposta", 0.95, resposta = "Brasília é a capital."), "qual a capital do brasil") is Semantica.Decisao.Responder)
        assertTrue(Semantica.decidir(achado("intencao", 0.9), "abre o zap") is Semantica.Decisao.Frase)
    }

    @Test fun oQueVaiParaAMemoria() {
        assertTrue(Semantica.vale("qual a capital da Austrália", "A capital da Austrália é Camberra."))
        assertFalse(Semantica.vale("qual a minha cidade", "Porto Alegre, você me contou."))
        assertFalse(Semantica.vale("quanto está o dólar hoje", "R$ 5,10"))
        assertFalse(Semantica.vale("quem descobriu o Brasil", "Não consegui responder agora."))
    }

    @Test fun estudo() {
        val frases = Estudo.lerFrases(
            """{"frases":[{"frase":"como tá nosso insta","acao":"instagram_resumo","args":{}},
            {"frase":"quanto sobrou do gel","acao":"loja_estoque","args":{"produto":"gel"}},
            {"frase":"faz um café","acao":"fazer_cafe","args":{}},
            {"frase":"oi","acao":"loja_resumo","args":{}},
            {"frase":"Como tá nosso insta","acao":"instagram_resumo","args":{}}]}"""
        )
        assertEquals(listOf("como tá nosso insta", "quanto sobrou do gel"), frases.map { it.first })
        assertEquals("gel", frases[1].second.args["produto"])

        val enigmas = Estudo.lerEnigmas("""texto antes {"enigmas":[{"pergunta":"O que é o que é: quanto mais se tira, maior fica?","resposta":"O buraco","explicacao":"Tirar terra aumenta o buraco."},{"pergunta":"curta","resposta":"x"}]}""")
        assertEquals(1, enigmas.size)
        assertEquals(listOf("o buraco", "12"), Estudo.lerSolucoes("""{"respostas":["o buraco","12"]}"""))

        assertTrue(Estudo.mesmaResposta("O buraco", "buraco"))
        assertTrue(Estudo.mesmaResposta("12 anos", "Ela tem 12"))
        assertFalse(Estudo.mesmaResposta("12", "13"))
        assertTrue(Estudo.mesmaResposta("A letra M", "letra m"))
        assertFalse(Estudo.mesmaResposta("a sombra", "o vento"))
        assertTrue(Estudo.pedidoDeFrases(emptyList()).contains("instagram_resumo"))
        assertTrue(Estudo.pedidoDeSolucao(listOf("a", "b")).contains("2. b"))
    }

    @Test fun comandosDoEstudo() {
        assertEquals("estudo_resumo", (ComandosLocais.interpretar("o que você estudou hoje?") as? Acao)?.nome)
        assertEquals("estudo_resumo", (ComandosLocais.interpretar("como está seu estudo") as? Acao)?.nome)
        assertEquals("estudo_agora", (ComandosLocais.interpretar("estuda agora") as? Acao)?.nome)
        assertEquals("estudo_agora", (ComandosLocais.interpretar("vai estudar agora") as? Acao)?.nome)
        assertEquals("aprendizado", (ComandosLocais.interpretar("mostra o que você aprendeu") as? Acao)?.nome)
        assertEquals("aprendizado", (ComandosLocais.interpretar("o que eu aprendi") as? Acao)?.nome)
        assertEquals("aprendizado", (ComandosLocais.interpretar("abrir a memória vetorial") as? Acao)?.nome)
    }

    @Test fun licoesESaberes() {
        assertEquals(Estudo.DISCIPLINAS[0], Estudo.disciplinaDoDia(Estudo.DISCIPLINAS.size))
        assertEquals(Estudo.DISCIPLINAS.size, (1..Estudo.DISCIPLINAS.size).map { Estudo.disciplinaDoDia(it) }.toSet().size)
        assertTrue("licoes" in Estudo.ETAPAS && "saberes" in Estudo.ETAPAS)
        val p = Estudo.pedidoDeLicoes(emptyList(), listOf("erro: buscar_na_internet → 429 (3x)"), listOf("Confirme antes de enviar mensagens"))
        assertTrue(p.contains("buscar_na_internet") && p.contains("Confirme antes de enviar mensagens") && p.contains("\"licoes\""))
        val pares = Estudo.lerPares("""{"licoes":[{"licao":"Pesquise antes de dar preço de concorrente","resposta":"Ela chutou um preço errado ontem."}]}""", "licoes", "licao")
        assertEquals("Pesquise antes de dar preço de concorrente", pares.single().first)
        assertTrue(Estudo.pedidoDeSaberes("finanças", emptyList()).contains("${Estudo.SABERES_POR_DIA} perguntas"))
    }

    @Test fun regrasDaLoja() {
        assertTrue(RegrasDaLoja.REGRAS.all { it.pergunta.isNotBlank() && it.resposta.length in 40..600 })
        assertEquals(RegrasDaLoja.REGRAS.size, RegrasDaLoja.REGRAS.map { it.pergunta }.toSet().size)
        assertEquals(
            listOf("Gel Secret Love Morango", "TURBO GEL 15g"),
            RegrasDaLoja.suspeitosDaAnvisa(listOf("Gel Secret Love Morango", "Vibrador Bullet", "TURBO GEL 15g", "Óleo de massagem")),
        )
    }
}
