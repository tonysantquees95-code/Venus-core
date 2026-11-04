package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EstudoAmpliadoTest {

    private fun normalizado(n: Int, semente: Int): FloatArray {
        val r = Random(semente)
        return EmbeddingsGemini.normalizar(FloatArray(n) { (r.nextFloat() - 0.5f) * (if (it % 7 == 0) 3f else 1f) })!!
    }

    @Test fun vetorCompactoQuaseIgual() {
        val a = normalizado(768, 1)
        val b = normalizado(768, 2)
        val ca = Memoria.compactar(a)
        assertEquals(5 + 768, ca.size)
        assertTrue(Memoria.ehCompacto(ca))
        assertFalse(Memoria.ehCompacto(Memoria.vetorParaBytes(a)))
        val volta = Memoria.descompactar(ca)
        // a nota muda bem pouco depois de compactar
        assertEquals(Memoria.produtoInterno(a, a), Memoria.produtoInterno(a, volta), 0.01)
        assertEquals(Memoria.produtoInterno(a, b), Memoria.produtoInterno(a, Memoria.descompactar(Memoria.compactar(b))), 0.01)
        // o formato antigo continua sendo lido
        assertEquals(a.toList(), Memoria.descompactar(Memoria.vetorParaBytes(a)).toList())
    }

    @Test fun lePerguntasDeClientesEConferencia() {
        val pares = Estudo.lerPares(
            """{"perguntas":[{"pergunta":"A embalagem é discreta?","resposta":"Sim! Vai em caixa lisa, sem o nome da loja."},
            {"pergunta":"a embalagem é discreta?","resposta":"Repetida, não entra de novo."},{"pergunta":"oi","resposta":"curta"}]}""",
            "perguntas", "pergunta",
        )
        assertEquals(1, pares.size)
        assertEquals("A embalagem é discreta?", pares[0].first)
        assertEquals(listOf(true, false, false), Estudo.lerAprovados("""{"aprovadas":[true,false]}""", 3))
        assertNull(Estudo.lerAprovados("nada", 2))
        val pedido = Estudo.pedidoDeClientes("Vibrador X | R$ 99,90 | R$ 94,90 | 3 un. | link", Estudo.FOCOS_CLIENTE[0], emptyList())
        assertTrue(pedido.contains("NUNCA diga preço"))
        assertTrue(Estudo.pedidoDeConferencia("cat", pares).contains("1. Pergunta: A embalagem é discreta?"))
    }

    @Test fun leObjecoesIdeiasERevisao() {
        val obj = Estudo.lerPares("""{"objecoes":[{"objecao":"Tá caro","resposta":"Entendo! No Pix tem 5% de desconto, quer que eu te mostre?"}]}""", "objecoes", "objecao")
        assertEquals("Tá caro", obj.single().first)
        val ideias = Estudo.lerIdeias("""{"ideias":[{"titulo":"Kit Black Friday","quando":"20/11","formato":"reels","legenda":"Chegou a semana mais esperada…"},{"titulo":"","legenda":"x"}]}""")
        assertEquals("Kit Black Friday (20/11, reels)", ideias.single().first)
        val rev = Estudo.lerRevisao(
            """{"correcoes":[{"frase":"bah como tá o insta","acao":"instagram_resumo","args":{}},
            {"frase":"qual a capital da Austrália","acao":"","resposta":"Camberra."},
            {"frase":"faz um negócio aí","acao":"acao_que_nao_existe"}]}"""
        )
        assertEquals(2, rev.size)
        assertEquals("instagram_resumo", rev[0].rota?.acao)
        assertNull(rev[1].rota)
        assertEquals("Camberra.", rev[1].resposta)
        assertTrue(Estudo.pedidoDeRevisao(listOf(Memoria.Mensagem(0, true, "abre o zap", "Conversa"), Memoria.Mensagem(0, false, "Não entendi", "Conversa")))
            .contains("Toni: abre o zap\nVênus: Não entendi"))
        assertTrue(Estudo.pedidoDeVoz(emptyList()).contains("bah"))
    }

    @Test fun resumoDoEstudo() {
        val r = Estudo.Resumo("2000-01-01", mapOf("frases" to 48, "clientes" to 90), descartados = 7, repetidos = 5, vetores = 100, faltam = listOf("ideias"))
        assertEquals(48, r.frases)
        assertEquals(138, r.total)
        val texto = Estudo.descrever(r, mapOf("comando" to (500 to 500), "cliente" to (90 to 90)))
        assertTrue(texto, texto.contains("48 jeitos de pedir e 90 respostas para clientes"))
        assertTrue(texto, texto.contains("Descartei 7"))
        assertTrue(texto, texto.contains("590 itens de até 60.000"))
    }

    @Test fun pedeIdeiasDePost() {
        assertEquals("estudo_ideias", (ComandosLocais.interpretar("me dá ideias de post") as? Acao)?.nome)
        assertEquals("estudo_ideias", (ComandosLocais.interpretar("o que eu posto no insta essa semana?") as? Acao)?.nome)
    }
}
