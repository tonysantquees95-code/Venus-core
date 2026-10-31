package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import com.tonimarques.sextafeira.ComandosLocais.Resultado.Resposta
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Novas capacidades: contas, conversões, datas, fatos (Wikidata), CEP/CNPJ/DDD, feriados e tradução. */
class CapacidadesTest {

    private val hoje: Calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 31, 10, 0) }

    private fun c(s: String) = ComandosLocais.normalizar(s)

    // ------------------------------------------------------------- contas
    @Test fun contas() {
        assertEquals("Dá 100.", Calculadora.conta(c("quanto é 25 vezes 4")))
        assertEquals("Dá 30.", Calculadora.conta(c("15% de 200")))
        assertEquals("Dá 12.", Calculadora.conta(c("raiz de 144")))
        assertEquals("Dá 2.530.", Calculadora.conta(c("dois mil e quinhentos mais trinta")))
        assertEquals("Dá 2,5.", Calculadora.conta(c("10 dividido por 4")))
        assertEquals("Dá 1.024.", Calculadora.conta(c("quanto é 2 elevado a 10")))
        assertEquals("Dá 14.", Calculadora.conta(c("2 mais 3 vezes 4")))
        assertEquals("Não dá para dividir por zero.", Calculadora.conta(c("10 dividido por 0")))
    }

    @Test fun naoEContas() {
        assertNull(Calculadora.conta(c("abre o WhatsApp")))
        assertNull(Calculadora.conta(c("2026")))
        assertNull(Calculadora.conta(c("quanto custa o iPhone")))
        assertNull(Calculadora.conta(c("quanto tem de bateria")))
    }

    @Test fun numerosPorExtenso() {
        assertEquals("2500 mais 30", Calculadora.numerosPorExtenso("dois mil e quinhentos mais trinta"))
        assertEquals("125", Calculadora.numerosPorExtenso("cento e vinte e cinco"))
        assertEquals("1200", Calculadora.numerosPorExtenso("mil e duzentos"))
    }

    @Test fun conversoes() {
        assertEquals("10 milhas dão 16,09 quilômetros.", Calculadora.conversao(c("quantos quilômetros são 10 milhas")))
        assertEquals("30 graus Celsius dão 86 graus Fahrenheit.", Calculadora.conversao(c("30 graus celsius em fahrenheit")))
        assertEquals("2 litros dão 2.000 mililitros.", Calculadora.conversao(c("converte 2 litros em mililitros")))
        assertNull(Calculadora.conversao(c("timer de 5 minutos")))
        assertNull(Calculadora.conversao(c("quantos graus faz hoje")))
    }

    @Test fun datas() {
        val natal = Calculadora.datas(c("quantos dias faltam para o Natal?"), hoje)
        assertTrue(natal, natal!!.contains("55 dias"))
        val carnaval = Calculadora.datas(c("quando é o carnaval"), hoje)
        assertTrue(carnaval, carnaval!!.contains("9 de fevereiro de 2027"))
        val halloween = Calculadora.datas(c("quantos dias faltam para o halloween"), hoje)
        assertTrue(halloween, halloween!!.startsWith("É hoje"))
        val black = Calculadora.datas(c("quando é a black friday"), hoje)
        assertTrue(black, black!!.contains("27 de novembro de 2026"))
        val data = Calculadora.datas(c("quantos dias faltam para 10 de janeiro"), hoje)
        assertTrue(data, data!!.contains("71 dias"))
        assertNull(Calculadora.datas(c("que dia é hoje"), hoje))
    }

    // ------------------------------------------------------------- fatos
    @Test fun entendePerguntasDeFato() {
        val capital = Fatos.entender(c("Qual a capital da Austrália?"))!!
        assertEquals("capital", capital.tipo)
        assertEquals("australia", capital.sujeito)
        assertEquals("da", capital.prep)
        assertEquals("idade", Fatos.entender(c("quantos anos tem o Neymar"))!!.tipo)
        assertEquals("neymar", Fatos.entender(c("quantos anos tem o Neymar"))!!.sujeito)
        assertEquals("fundador", Fatos.entender(c("quem fundou a Apple"))!!.tipo)
        assertEquals("populacao", Fatos.entender(c("quantos habitantes tem o Japão"))!!.tipo)
        assertEquals("presidente", Fatos.entender(c("quem é o presidente da França"))!!.tipo)
        assertEquals("autor", Fatos.entender(c("quem escreveu Dom Casmurro"))!!.tipo)
        assertNull(Fatos.entender(c("abre o whatsapp")))
        assertNull(Fatos.entender(c("me conta uma piada")))
        assertTrue(Pesquisa.ehPesquisavel("qual a moeda do Japão"))
    }

    @Test fun respostasDeFato() {
        val p = Fatos.Pergunta("capital", listOf("P36"), "australia", "da")
        assertEquals("A capital da Austrália é Camberra.", Fatos.frase(p, "P36", "Austrália", listOf("Camberra")))
        val pop = Fatos.Pergunta("populacao", listOf("P1082"), "brasil", "o")
        assertEquals(
            "Brasil tem cerca de 213.421.037 habitantes, segundo dados de 2022.",
            Fatos.frase(pop, "P1082", "Brasil", listOf("213.421.037|2022"))
        )
        val autores = Fatos.Pergunta("fundador", listOf("P112"), "apple", "a")
        assertEquals("Apple foi fundado por Steve Jobs, Steve Wozniak e Ronald Wayne.",
            Fatos.frase(autores, "P112", "Apple", listOf("Steve Jobs", "Steve Wozniak", "Ronald Wayne")))
        assertEquals("5 de fevereiro de 1992", Fatos.formatarData("+1992-02-05T00:00:00Z", 11))
        assertEquals("1992", Fatos.formatarData("+1992-00-00T00:00:00Z", 9))
    }

    // ------------------------------------------------------------- serviços
    @Test fun cepCnpjDdd() {
        val cep = JSONObject("""{"street":"Praça da Sé","neighborhood":"Sé","city":"São Paulo","state":"SP"}""")
        assertEquals("O CEP 01001-000 é de Praça da Sé, Sé, São Paulo - SP.", Servicos.montarCep(cep, "01001000"))
        val cnpj = JSONObject(
            """{"razao_social":"MAGAZINE LUIZA S/A","nome_fantasia":"","descricao_situacao_cadastral":"ATIVA",
               "municipio":"FRANCA","uf":"SP","cnae_fiscal_descricao":"Comércio varejista","data_inicio_atividade":"1977-01-03"}"""
        )
        assertEquals(
            "É a empresa MAGAZINE LUIZA S/A, de Franca - SP. Situação: ativa. Atividade: comércio varejista. Aberta em 03/01/1977.",
            Servicos.montarCnpj(cnpj)
        )
        val ddd = JSONObject("""{"state":"RS","cities":["PORTO ALEGRE","CANOAS"]}""")
        assertEquals("O DDD 51 é de RS: Canoas, Porto Alegre.", Servicos.montarDdd(ddd, "51"))
    }

    @Test fun feriados() {
        val lista = Servicos.lerFeriados(
            JSONArray(
                """[{"date":"2026-10-12","name":"Nossa Senhora Aparecida"},{"date":"2026-11-02","name":"Finados"},
                   {"date":"2026-11-15","name":"Proclamação da República"},{"date":"2026-12-25","name":"Natal"}]"""
            )
        )
        val texto = Servicos.montarFeriados(lista, hoje)
        assertTrue(texto, texto.startsWith("O próximo feriado nacional é Finados, segunda-feira, 2 de novembro (faltam 2 dias)."))
        assertTrue(texto, texto.contains("Proclamação da República (15/11) e Natal (25/12)"))
    }

    // ------------------------------------------------------------- comandos
    private fun acao(frase: String, nome: String): Acao {
        val r = ComandosLocais.interpretar(frase)
        assertTrue("Esperava $nome para \"$frase\", veio $r", r is Acao && r.nome == nome)
        return r as Acao
    }

    @Test fun comandosNovos() {
        assertEquals("01001000", acao("qual o endereço do CEP 01001-000", "consultar_cep").args["cep"])
        assertEquals("47960950000121", acao("consulta o CNPJ 47.960.950/0001-21", "consultar_cnpj").args["cnpj"])
        assertEquals("51", acao("de onde é o DDD 51", "consultar_ddd").args["ddd"])
        acao("quando é o próximo feriado", "feriados")

        val t = acao("como se diz obrigado em inglês", "traduzir")
        assertEquals("obrigado", t.args["texto"])
        assertEquals("en", t.args["para"])
        val t2 = acao("traduz bom dia para o espanhol", "traduzir")
        assertEquals("bom dia", t2.args["texto"])
        assertEquals("es", t2.args["para"])
        val t3 = acao("o que significa good morning em português", "traduzir")
        assertEquals("en", t3.args["de"])
        assertEquals("pt-BR", t3.args["para"])
    }

    @Test fun respostasLocais() {
        val r = ComandosLocais.interpretar("quanto é 25 vezes 4?")
        assertTrue("$r", r is Resposta && r.texto == "Dá 100.")
        val conv = ComandosLocais.interpretar("30 graus celsius em fahrenheit")
        assertTrue("$conv", conv is Resposta && conv.texto.contains("86"))
        assertNotNull(ComandosLocais.interpretar("quantos dias faltam para o natal"))
    }
}
