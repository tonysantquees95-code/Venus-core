package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import com.tonimarques.sextafeira.ComandosLocais.Resultado.Resposta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contexto da conversa nas contas, conhecimentos gerais, dicionário e continuação de perguntas. */
class ConversaESaberTest {

    private fun c(s: String) = ComandosLocais.normalizar(s)

    /** Simula a conversa: pergunta, resposta, e as duas ficam na memória da conversa. */
    private fun diz(frase: String): String? {
        val r = Calculadora.conta(c(frase))
        Calculadora.lembrar(frase)
        if (r != null) Calculadora.lembrar(r)
        return r
    }

    @Test fun conversaDoPrint() {
        Calculadora.esquecerConversa()
        assertEquals("Dá 104.880.", diz("quanto é 230 x 456"))
        assertTrue(diz("Quanto é 25% desse valor")!!.startsWith("Dá 26.220."))
        assertTrue(diz("divide esse valor por três")!!.startsWith("Dá 8.740."))
        assertTrue(diz("some mais 10600")!!.startsWith("Dá 19.340."))
        assertTrue(diz("some mais 10 mil e seiscentos")!!.startsWith("Dá 29.940."))
        assertTrue(diz("some mais 10.600 aos 8740")!!.startsWith("Dá 19.340."))
        assertTrue(diz("tira 340 de 19340")!!.startsWith("Dá 19.000."))
        Calculadora.esquecerConversa()
    }

    @Test fun valoresDeAlgumasMensagensAtras() {
        Calculadora.esquecerConversa()
        Calculadora.lembrar("o frete ficou em 30 reais")
        Calculadora.lembrar("Anotado!")
        Calculadora.lembrar("o produto custa 120")
        Calculadora.lembrar("Certo.")
        Calculadora.lembrar("toca uma música")
        Calculadora.lembrar("Tocando agora.")
        val r = diz("quanto é 10% do frete")!!
        assertTrue(r, r.startsWith("Dá 3."))
        assertTrue(r, r.contains("frete = 30"))
        assertTrue(diz("quanto é o frete mais o produto")!!.startsWith("Dá 150."))
        Calculadora.esquecerConversa()
        Calculadora.lembrar("Dá 100.")
        Calculadora.lembrar("Dá 50.")
        assertTrue(diz("some os dois valores")!!.startsWith("Dá 150."))
        assertTrue(diz("quanto é o valor anterior vezes 2")!!.startsWith("Dá 100."))
        Calculadora.esquecerConversa()
    }

    @Test fun naoConfundeComMatematica() {
        Calculadora.esquecerConversa()
        Calculadora.lembrar("toca 5 músicas do Queen")
        assertNull(Calculadora.conta(c("toca mais uma")))
        assertNull(Calculadora.conta(c("diminui o volume")))
        assertNull(Calculadora.conta(c("abre o WhatsApp")))
        Calculadora.esquecerConversa()
    }

    @Test fun outrasContas() {
        assertEquals("50 é 25% de 200.", Calculadora.outrasContas(c("50 é quantos por cento de 200")))
        assertEquals("O fatorial de 5 é 120.", Calculadora.outrasContas(c("fatorial de 5")))
        assertEquals("Sim, 17 é um número primo.", Calculadora.outrasContas(c("17 é primo")))
        assertTrue(Calculadora.outrasContas(c("21 é primo"))!!.startsWith("Não"))
        assertEquals("O MMC de 4 e 6 é 12.", Calculadora.outrasContas(c("mmc de 4 e 6")))
        assertEquals("O MDC de 12 e 18 é 6.", Calculadora.outrasContas(c("mdc de 12 e 18")))
        assertTrue(Calculadora.conta(c("quanto é 200 com 10% de desconto"))!!.startsWith("Dá 180."))
        assertTrue(Calculadora.conta(c("quanto é a metade de 90"))!!.startsWith("Dá 45."))
        assertTrue(Calculadora.conta(c("raiz cúbica de 27"))!!.startsWith("Dá 3."))
    }

    // ------------------------------------------------------------- saber
    @Test fun portugues() {
        assertTrue(Saber.responder(c("qual a diferença entre porque e por que"))!!.contains("porquês"))
        assertNull(Saber.responder(c("porque eu quero usar o carro")))
        assertTrue(Saber.responder(c("mas ou mais"))!!.contains("porém"))
        assertTrue(Saber.responder(c("quando usa crase?"))!!.contains("à"))
        assertTrue(Saber.responder(c("é para mim fazer ou para eu fazer"))!!.contains("eu"))
    }

    @Test fun geografiaECiencias() {
        assertEquals("A capital do Rio Grande do Sul (RS) é Porto Alegre.", Saber.responder(c("qual a capital do Rio Grande do Sul")))
        assertEquals("A capital da Bahia (BA) é Salvador.", Saber.responder(c("capital da Bahia")))
        assertNull(Saber.responder(c("qual a capital da França")))
        assertEquals("O símbolo do ouro é Au, número atômico 79.", Saber.responder(c("qual o símbolo do ouro")))
        assertEquals("Fe é o símbolo do ferro, número atômico 26.", Saber.responder(c("qual elemento é Fe")))
        assertTrue(Saber.responder(c("quantos ossos tem o corpo humano"))!!.contains("206"))
        assertNull(Saber.responder(c("quantos ossos tem um gato")))
        assertTrue(Saber.responder(c("quantos planetas tem o sistema solar"))!!.contains("8 planetas"))
        assertTrue(Saber.responder(c("qual a fórmula de bhaskara"))!!.contains("Δ"))
        val r = ComandosLocais.interpretar("qual a temperatura do sol")
        assertTrue("$r", r is Resposta && r.texto.contains("5.500"))
    }

    // ------------------------------------------------------------- dicionário
    private fun acao(frase: String, nome: String): Acao {
        val r = ComandosLocais.interpretar(frase)
        assertTrue("Esperava $nome para \"$frase\", veio $r", r is Acao && r.nome == nome)
        return r as Acao
    }

    @Test fun comandosDeDicionario() {
        val a = acao("o que significa efêmero", "dicionario")
        assertEquals("significado", a.args["tipo"])
        assertEquals("efêmero", a.args["palavra"])
        assertEquals("sinonimos", acao("sinônimo de feliz", "dicionario").args["tipo"])
        assertEquals("plural", acao("qual o plural de cidadão", "dicionario").args["tipo"])
        val o = acao("exceção ou excessão, qual é o certo", "ortografia")
        assertEquals("exceção", o.args["a"])
        assertEquals("excessão", o.args["b"])
        assertEquals("traduzir", (ComandosLocais.interpretar("o que significa good morning em português") as Acao).nome)
    }

    @Test fun leWikcionario() {
        val w = """
            ={{-pt-}}=
            ==Substantivo==
            {{flex.pt|ms=casa|mp=casas}}
            '''ca.sa''', {{fem}}

            # [[edifício]] destinado à [[habitação]]
            #* ''Comprei uma '''casa''' nova.''
            # [[lar]], [[família]]

            ===Sinônimos===
            * [[lar]]
            * [[moradia]]
            * [[residência|residência]]

            ={{-en-}}=
            ==Noun==
            # something in English
        """.trimIndent()
        val pt = Dicionario.secaoPortugues(w)
        assertEquals(listOf("Edifício destinado à habitação.", "Lar, família."), Dicionario.definicoes(pt))
        assertEquals("substantivo", Dicionario.classe(pt))
        assertEquals("casas", Dicionario.plural(pt))
        assertEquals(listOf("lar", "moradia", "residência"), Dicionario.lista(pt, "Sinônimos"))
    }

    // ------------------------------------------------------------- continuação de assunto
    @Test fun continuaOAssunto() {
        Fatos.ultimoSujeito = "franca"
        val p = Fatos.entender(c("e a população?"))!!
        assertEquals("populacao", p.tipo)
        assertEquals("franca", p.sujeito)
        assertEquals("franca", Fatos.entender(c("qual a moeda de lá"))!!.sujeito)
        Fatos.ultimoSujeito = "neymar"
        assertEquals("neymar", Fatos.entender(c("quantos anos ele tem"))!!.sujeito)
        Fatos.ultimoSujeito = null
        assertNull(Fatos.entender(c("e a população?")))
    }

    @Test fun soPesquisaPerguntasDeVerdade() {
        assertFalse(Pesquisa.pareceConsulta("não posso"))
        assertTrue(Pesquisa.pareceConsulta("o que é um buraco negro"))
        assertTrue(Pesquisa.pareceConsulta("Bahia?"))
        assertFalse(Pesquisa.tituloCombina(Pesquisa.palavrasChave("quanto custa uma geladeira nova"), "Rancho Não Posso Me Amofiná"))
    }
}
