package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import com.tonimarques.sextafeira.ComandosLocais.Resultado.Resposta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComandosLocaisTest {

    private fun acao(frase: String): Acao {
        val r = ComandosLocais.interpretar(frase)
        assertTrue("Esperava uma ação para \"$frase\", veio $r", r is Acao)
        return r as Acao
    }

    private fun confere(frase: String, nome: String, vararg args: Pair<String, Any>) {
        val a = acao(frase)
        assertEquals("ferramenta de \"$frase\"", nome, a.nome)
        for ((k, v) in args) assertEquals("argumento $k de \"$frase\"", v, a.args[k])
    }

    @Test fun lanterna() {
        confere("sexta-feira Ligar lanterna", "lanterna", "ligar" to true)
        confere("sexta-feira abrir lanterna", "lanterna", "ligar" to true)
        confere("Sexta-feira, desliga a lanterna por favor", "lanterna", "ligar" to false)
        confere("apaga a lanterna", "lanterna", "ligar" to false)
    }

    @Test fun midia() {
        confere("próxima música", "controlar_midia", "acao" to "proxima")
        confere("pula essa", "controlar_midia", "acao" to "proxima")
        confere("troca de música", "controlar_midia", "acao" to "proxima")
        confere("música anterior", "controlar_midia", "acao" to "anterior")
        confere("pausa", "controlar_midia", "acao" to "pausar")
        confere("para a música", "controlar_midia", "acao" to "pausar")
        confere("continua", "controlar_midia", "acao" to "tocar")
        confere("aumenta o volume", "controlar_midia", "acao" to "aumentar_volume")
        confere("abaixa o volume", "controlar_midia", "acao" to "diminuir_volume")
        confere("volume mais alto", "controlar_midia", "acao" to "aumentar_volume")
        confere("coloca no mudo", "controlar_midia", "acao" to "mudo")
    }

    @Test fun musica() {
        confere("toca Henrique e Juliano", "tocar_musica", "busca" to "Henrique e Juliano", "app" to "")
        confere("Sexta-feira, toca uma música do Jorge e Mateus", "tocar_musica", "busca" to "Jorge e Mateus")
        confere("coloca Legião Urbana no Spotify", "tocar_musica", "busca" to "Legião Urbana", "app" to "spotify")
        confere("toca playlist de rock no youtube music", "tocar_musica", "busca" to "playlist de rock", "app" to "youtube music")
    }

    @Test fun abrirApps() {
        confere("abre o WhatsApp", "abrir_app", "nome" to "WhatsApp")
        confere("sexta-feira Abrir o WhatsApp", "abrir_app", "nome" to "WhatsApp")
        confere("abrir instagram", "abrir_app", "nome" to "instagram")
        confere("abre o app do Nubank", "abrir_app", "nome" to "Nubank")
        confere("abre a câmera", "abrir_camera")
        confere("tira uma foto", "abrir_camera")
    }

    @Test fun alarmesETimers() {
        confere("alarme às 7", "criar_alarme", "hora" to 7, "minuto" to 0)
        confere("cria um alarme para 6:30", "criar_alarme", "hora" to 6, "minuto" to 30)
        confere("me acorda às 7 e meia", "criar_alarme", "hora" to 7, "minuto" to 30)
        confere("alarme pras 8 da noite", "criar_alarme", "hora" to 20, "minuto" to 0)
        confere("despertador ao meio-dia", "criar_alarme", "hora" to 12, "minuto" to 0)
        confere("timer de 5 minutos", "criar_timer", "segundos" to 300)
        confere("me avisa daqui a meia hora", "criar_timer", "segundos" to 1800)
        confere("temporizador de 1 hora e 10 minutos", "criar_timer", "segundos" to 4200)
    }

    @Test fun mensagensELigacoes() {
        confere(
            "manda pra Maria dizendo chego em 10 minutos", "enviar_whatsapp",
            "contato" to "Maria", "mensagem" to "chego em 10 minutos"
        )
        confere(
            "manda no zap para a Maria que eu já estou indo", "enviar_whatsapp",
            "contato" to "Maria", "mensagem" to "eu já estou indo"
        )
        confere(
            "envia um SMS para o João dizendo Oi, tudo bem", "enviar_sms",
            "contato" to "João", "mensagem" to "Oi, tudo bem"
        )
        confere("liga pra Mãe", "fazer_ligacao", "contato" to "Mãe")
        confere("ligar para 51999998888", "fazer_ligacao", "contato" to "51999998888")
    }

    @Test fun mapaPesquisaSiteConfig() {
        confere("me leva para a rodoviária", "abrir_mapa", "destino" to "rodoviária", "navegar" to true)
        confere("onde fica o Parque Farroupilha", "abrir_mapa", "destino" to "Parque Farroupilha", "navegar" to false)
        confere("pesquisa receita de bolo de cenoura", "pesquisar_na_web", "termo" to "receita de bolo de cenoura")
        confere("abre o site youtube.com", "abrir_site", "endereco" to "youtube.com")
        confere("abre g1 ponto com", "abrir_site", "endereco" to "g1.com")
        confere("liga o wifi", "abrir_configuracao", "tipo" to "wifi")
        confere("desliga o bluetooth", "abrir_configuracao", "tipo" to "bluetooth")
    }

    @Test fun respostasDiretas() {
        assertTrue(ComandosLocais.interpretar("que horas são?") is Resposta)
        assertTrue(ComandosLocais.interpretar("Sexta-feira, que dia é hoje") is Resposta)
        assertTrue(ComandosLocais.interpretar("ajuda") is Resposta)
    }

    @Test fun ficaParaAIA() {
        assertNull(ComandosLocais.interpretar("abre o YouTube e toca um rock"))
        assertNull(ComandosLocais.interpretar("qual a capital da Austrália?"))
        assertNull(ComandosLocais.interpretar("me conta uma piada"))
        assertNull(ComandosLocais.interpretar("como vai a vida"))
        assertNull(ComandosLocais.interpretar("manda mensagem pra Maria"))
        assertNull(ComandosLocais.interpretar("sexta-feira"))
    }

    @Test fun volumeEFaixas() {
        confere("volume máximo", "controlar_midia", "acao" to "volume_maximo")
        confere("volume mínimo", "controlar_midia", "acao" to "volume_minimo")
        confere("volume na metade", "controlar_midia", "acao" to "volume_metade")
        confere("volume 40", "controlar_midia", "acao" to "volume_nivel", "nivel" to "40")
        confere("avança um pouco", "controlar_midia", "acao" to "avancar")
    }
}
