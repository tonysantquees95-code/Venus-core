package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.Cerebro.Decisao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class CerebroTest {

    private fun novo() = Cerebro(Random(7)) {
        Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 31, 9, 30) }
    }

    private fun executa(c: Cerebro, frase: String, nome: String, vararg args: Pair<String, Any>) {
        val d = c.responder(frase)
        assertTrue("\"$frase\" deveria executar $nome, veio $d", d is Decisao.Executar)
        d as Decisao.Executar
        assertEquals("ferramenta de \"$frase\"", nome, d.nome)
        for ((k, v) in args) assertEquals("argumento $k de \"$frase\"", v, d.args[k])
    }

    private fun pergunta(c: Cerebro, frase: String): String {
        val d = c.responder(frase)
        assertTrue("\"$frase\" deveria gerar uma pergunta, veio $d", d is Decisao.Perguntar)
        return (d as Decisao.Perguntar).texto
    }

    private fun responde(c: Cerebro, frase: String): String {
        val d = c.responder(frase)
        assertTrue("\"$frase\" deveria gerar uma resposta, veio $d", d is Decisao.Responder)
        return (d as Decisao.Responder).texto
    }

    @Test fun conceitos() {
        assertEquals(setOf("ABRIR", "CONFIG"), Cerebro.conceitos("Abre as Configurações!"))
        assertEquals(setOf("LIGAR", "LANTERNA"), Cerebro.conceitos("liga a luz por favor"))
        assertEquals(setOf("BOMDIA"), Cerebro.conceitos("bom dia sexta-feira"))
    }

    @Test fun configuracoesNaoTravamMais() {
        val c = novo()
        executa(c, "abre as configurações", "abrir_configuracao", "tipo" to "geral")
        executa(c, "abrir configurações do celular", "abrir_configuracao", "tipo" to "geral")
        executa(c, "Sexta-feira, configurações", "abrir_configuracao", "tipo" to "geral")
        executa(c, "me mostra os ajustes do telefone", "abrir_configuracao", "tipo" to "geral")
    }

    @Test fun frasesDiferentesMesmoSentido() {
        val c = novo()
        executa(c, "liga a luz", "lanterna", "ligar" to true)
        executa(c, "apaga essa luz aí", "lanterna", "ligar" to false)
        executa(c, "sobe esse som aí", "controlar_midia", "acao" to "aumentar_volume")
        executa(c, "passa pra próxima", "controlar_midia", "acao" to "proxima")
        executa(c, "qual o nível da bateria", "nivel_bateria")
        executa(c, "como está a bateria", "nivel_bateria")
        executa(c, "quero tirar uma foto", "abrir_camera")
        executa(c, "liga o gps", "abrir_configuracao", "tipo" to "localizacao")
    }

    @Test fun conversa() {
        val c = novo()
        assertTrue(responde(c, "oi sexta-feira").isNotBlank())
        executa(c, "bom dia", "rotina_bom_dia")
        assertTrue(responde(c, "boa tarde").startsWith("Boa tarde"))
        assertTrue(responde(c, "tudo bem?").isNotBlank())
        assertTrue(responde(c, "quem te criou").isNotBlank())
        assertTrue(responde(c, "conta uma piada").contains("?"))
        assertTrue(responde(c, "obrigado").isNotBlank())
        assertTrue(responde(c, "que horas são").startsWith("Agora são"))
    }

    @Test fun perguntaOQueFalta() {
        val c = novo()
        assertEquals("Para quem é a mensagem?", pergunta(c, "manda uma mensagem"))
        assertEquals("O que devo escrever?", pergunta(c, "pra Maria"))
        executa(c, "chego em dez minutos", "enviar_whatsapp", "contato" to "Maria", "mensagem" to "chego em dez minutos")

        assertEquals("Para que horas?", pergunta(c, "cria um alarme"))
        executa(c, "sete e meia", "criar_alarme", "hora" to 7, "minuto" to 30)

        assertEquals("De quanto tempo?", pergunta(c, "liga um timer"))
        executa(c, "5", "criar_timer", "segundos" to 300)

        assertEquals("Qual aplicativo você quer abrir?", pergunta(c, "abre um aplicativo"))
        executa(c, "Instagram", "abrir_app", "nome" to "Instagram")

        assertEquals("Para quem você quer ligar?", pergunta(c, "liga pra alguém"))
        executa(c, "pra Mãe", "fazer_ligacao", "contato" to "Mãe")
    }

    @Test fun cancelar() {
        val c = novo()
        pergunta(c, "cria um alarme")
        assertEquals("Tudo bem, cancelado.", responde(c, "cancela"))
    }

    @Test fun confirmaQuandoEstaEmDuvida() {
        val c = novo()
        val d = c.responder("preciso enxergar melhor no escuro com uma luz amarela")
        assertEquals(Decisao.Incerto(true), d)
        val confirmacao = c.usarPalpite()
        assertTrue(confirmacao is Decisao.Perguntar)
        assertEquals("Você quer que eu ligue a lanterna?", (confirmacao as Decisao.Perguntar).texto)
        executa(c, "sim", "lanterna", "ligar" to true)
    }

    @Test fun naoEntendeEPedeParaRepetir() {
        val c = novo()
        assertEquals(Decisao.Incerto(false), c.responder("qual a capital da Austrália"))
        val d = c.usarPalpite()
        assertTrue(d is Decisao.Responder && d.texto in BancoDeFrases.NAO_ENTENDI)
    }

    @Test fun especiais() {
        val c = novo()
        assertTrue(c.responder("ajuda") !is Decisao.Incerto)
        assertTrue(c.responder("pode descansar") is Decisao.Especial)
        assertTrue(c.responder("nova conversa") is Decisao.Especial)
    }

    @Test fun ativacao() {
        assertEquals("", Cerebro.restoDepoisDaAtivacao("Sexta-feira, ativar"))
        assertEquals("", Cerebro.restoDepoisDaAtivacao("sexta feira ativa"))
        assertEquals("abre o WhatsApp", Cerebro.restoDepoisDaAtivacao("Sexta-feira ativar, abre o WhatsApp"))
        assertEquals("", Cerebro.restoDepoisDaAtivacao("ativar sexta-feira"))
        assertNull(Cerebro.restoDepoisDaAtivacao("abre o WhatsApp"))
        assertNull(Cerebro.restoDepoisDaAtivacao("hoje é sexta-feira"))
    }

    @Test
    fun papoSoltoFicaParaAIA() {
        val c = Cerebro()
        for (frase in listOf("como você está", "tudo bem", "estou bem", "obrigado", "sim")) {
            c.responder(frase)
            assertTrue("$frase deveria ir para a IA", c.ultimaFoiPapo)
        }
        c.responder("que horas são")
        assertTrue(!c.ultimaFoiPapo) // comando de verdade não é papo
    }
}
