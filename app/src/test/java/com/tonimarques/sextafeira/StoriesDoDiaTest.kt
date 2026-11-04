package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoriesDoDiaTest {

    @Test fun leTemasDoGemini() {
        val t = StoriesDoDia.lerTemas(
            """```json
            {"temas":[{"tipo":"curiosidade","titulo":"O abraço que acalma","texto":"Abraços longos liberam ocitocina, ligada ao vínculo."},
            {"tipo":"dica","titulo":"x","texto":"curto"},{"tipo":"Dica","titulo":"Durma bem","texto":"Uma boa noite de sono ajuda o humor e a disposição."}]}```"""
        )
        assertEquals(2, t.size)
        assertEquals("curiosidade", t[0].tipo)
        assertEquals("dica", t[1].tipo)
        assertTrue(StoriesDoDia.lerTemas("nada").isEmpty())
    }

    @Test fun pedidoSeguroParaOInstagram() {
        val p = StoriesDoDia.pedidoDeTemas(listOf("Comece pelo espelho"))
        assertTrue(p.contains("nada explícito"))
        assertTrue(p.contains("Não repita estes títulos: Comece pelo espelho"))
    }

    @Test fun reservaCabeNaArte() {
        assertTrue(StoriesDoDia.RESERVA.size >= 14)
        for (r in StoriesDoDia.RESERVA) {
            assertTrue(r.titulo, r.titulo.length <= 45)
            assertTrue(r.titulo, r.texto.length <= 170)
        }
        assertEquals("9:30", StoriesDoDia.horaTexto(9 * 60 + 30))
    }

    @Test fun comandos() {
        val liga = ComandosLocais.interpretar("liga os stories automáticos") as Acao
        assertEquals("story_auto", liga.nome)
        assertEquals(true, liga.args["ligado"])
        assertEquals(false, (ComandosLocais.interpretar("desliga os stories do dia") as Acao).args["ligado"])
        assertEquals("story_auto_ver", (ComandosLocais.interpretar("mostra o story do dia") as? Acao)?.nome)
        assertEquals("story_auto_agora", (ComandosLocais.interpretar("posta a dica agora") as? Acao)?.nome)
        assertEquals("story_auto", (ComandosLocais.interpretar("como estão os stories automáticos?") as? Acao)?.nome)
    }
}
