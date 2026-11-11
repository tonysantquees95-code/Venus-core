package com.tonimarques.sextafeira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VetorialIdTest {
    @Test
    fun temaVaiEVoltaEntreAndroidEWeb() {
        // Líquido=0, Vênus=0, cor rosa
        val j = VetorialId.temaParaJson(0, 0, 0xE36BD0)
        assertEquals("aurora", j.getString("orbe"))
        assertEquals("duotone", j.getString("icones"))
        assertEquals(0xE3, j.getJSONObject("cores").getJSONObject("destaque").getInt("r"))
        assertEquals(Triple(0, 0, 0xE36BD0), VetorialId.temaDoJson(j))
        // Cristal=1 → prisma; Sólido=2 → tecnico; Pulso=2 → aneis
        assertEquals("prisma", VetorialId.temaParaJson(1, 1, 0x112233).getString("orbe"))
        assertEquals("tecnico", VetorialId.temaParaJson(2, 2, 0x112233).getString("icones"))
        assertEquals("aneis", VetorialId.temaParaJson(2, 0, 0x112233).getString("orbe"))
        assertEquals(Triple(2, 1, 0x00FF7F), VetorialId.temaDoJson(JSONObject("""{"orbe":"aneis","icones":"linha","cores":{"destaque":{"r":0,"g":255,"b":127},"secundaria":{"r":1,"g":2,"b":3}}}""")))
    }

    @Test
    fun temaInvalidoDoServidorEIgnorado() {
        assertNull(VetorialId.temaDoJson(null))
        assertNull(VetorialId.temaDoJson(JSONObject("""{"orbe":"hacker","icones":"linha","cores":{"destaque":{"r":1,"g":1,"b":1}}}""")))
        assertNull(VetorialId.temaDoJson(JSONObject("""{"orbe":"aurora","icones":"linha","cores":{"destaque":{"r":999,"g":1,"b":1}}}""")))
        assertNull(VetorialId.temaDoJson(JSONObject("""{"orbe":"aurora","icones":"linha"}""")))
    }

    @Test
    fun preferenciasPreservamAsChavesDoWeb() {
        val base = JSONObject("""{"modo":"conversacional","notificacoes":false,"alertasDaLoja":true}""")
        val j = VetorialId.mesclarPreferencias(base, "orbe", false, true)
        assertEquals("orbe", j.getString("modo"))
        assertFalse(j.getBoolean("acionarPeloNome"))
        assertFalse(j.getBoolean("notificacoes")) // do web: continua igual
        assertTrue(j.getBoolean("alertasDaLoja"))
        assertEquals(Triple("bolha", true, false), VetorialId.lerPreferencias(JSONObject("""{"modo":"bolha","falarRespostas":false}""")))
        assertNull(VetorialId.lerPreferencias(JSONObject("""{"modo":"qualquer"}""")))
    }

    @Test
    fun versaoESessao() {
        assertTrue(VetorialId.deveAplicar(3, 2))
        assertFalse(VetorialId.deveAplicar(2, 2))
        assertTrue(VetorialId.sessaoPerdida(401, "sessao_encerrada"))
        assertFalse(VetorialId.sessaoPerdida(409, "limite_simultaneo"))
        assertFalse(VetorialId.sessaoPerdida(401, "credenciais")) // senha errada não desloga ninguém
    }

    @Test
    fun haQuantoTempoEmPortugues() {
        assertEquals("agora", haQuantoTempo(1000, 1000 + 30_000))
        assertEquals("há 5 min", haQuantoTempo(0, 5 * 60_000))
        assertEquals("há 3 h", haQuantoTempo(0, 3 * 3_600_000L))
        assertEquals("há 2 d", haQuantoTempo(0, 2 * 86_400_000L))
    }

    @Test
    fun modosLigamOQueUsam() {
        assertEquals(true to false, Modos.efeito("orbe"))
        assertEquals(false to true, Modos.efeito("bolha"))
        assertEquals(false to false, Modos.efeito("silencioso"))
        assertEquals(false to false, Modos.efeito("conversacional"))
        assertEquals(4, VetorialId.MODOS.size)
    }

    @Test
    fun hashtagsDoEditor() {
        assertEquals(listOf("vela", "casa", "oferta_do_dia"), hashtagsDoTexto("#Vela, casa  #vela #oferta_do_dia !!"))
        assertEquals("#vela #casa", textoDeHashtags(listOf("vela", "casa")))
        assertEquals(12, hashtagsDoTexto((1..30).joinToString(" ") { "tag$it" }).size)
        assertTrue(estadoDaPostagem("sugerido", 0, "", 0).startsWith("SUGEST"))
        assertTrue(estadoDaPostagem("erro", 0, "token vencido", 0).contains("token vencido"))
    }
}

class ContaPelaConversaTest {
    private fun acao(frase: String): String? =
        (ComandosLocais.interpretar(frase) as? ComandosLocais.Resultado.Acao)?.takeIf { it.nome == "conta_vetorial" }?.args?.get("acao") as? String

    @Test
    fun comandosDeConta() {
        assertEquals("status", acao("Vênus, qual é o status da minha conta?"))
        assertEquals("status", acao("quantos dias de teste ainda tenho"))
        assertEquals("status", acao("quando vence minha assinatura"))
        assertEquals("conectar", acao("Vênus, conectar minha conta no novo dispositivo"))
        assertEquals("sair", acao("Vênus, sair da minha conta aqui"))
        assertEquals("dispositivos", acao("quais dispositivos estão na minha conta"))
        assertEquals("dispositivos", acao("meus dispositivos"))
    }

    @Test
    fun outrasFrasesNaoViramComandoDeConta() {
        assertNull(acao("qual a previsão do tempo"))
        assertNull(acao("tocar uma música"))
        assertNull(acao("status do pedido 123"))
        assertNull(acao("desliga a lanterna"))
    }

    @Test
    fun avatarEAssinatura() {
        assertEquals("TM", iniciaisDoNome("Toni Marques"))
        assertEquals("TO", iniciaisDoNome("toni"))
        assertEquals("A", iniciaisDoNome("", "ana@x.com"))
        assertEquals("Plano Fundadores · teste grátis, 5 dias restantes", textoDaAssinatura(JSONObject("""{"acesso":true,"emTeste":true,"diasDeTesteRestantes":5}""")))
        assertEquals("Plano Fundadores · 1 dia restante", textoDaAssinatura(JSONObject("""{"acesso":true,"emTeste":false,"diasPagosRestantes":1}""")))
        assertTrue(textoDaAssinatura(JSONObject("""{"acesso":false}""")).contains("vencido"))
    }

    @Test
    fun soAppsDaSuitePedemAConta() {
        assertEquals("vindex", appDaSuite("tech.vetorial.vindex"))
        assertNull(appDaSuite("com.malware.app"))
        assertNull(appDaSuite(null))
    }
}
