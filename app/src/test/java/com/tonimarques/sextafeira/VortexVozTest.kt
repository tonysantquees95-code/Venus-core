package com.tonimarques.sextafeira

import org.junit.Assert.assertEquals
import org.junit.Test

class VortexVozTest {
    private fun acao(texto: String) = ComandosLocais.interpretar(texto) as? ComandosLocais.Resultado.Acao

    @Test
    fun abreJogoPeloNome() {
        val a = acao("abra o jogo Super Mario World")!!
        assertEquals("vortex", a.nome)
        assertEquals("abrir_jogo", a.args["acao"])
        assertEquals("super mario world", a.args["jogo"])
        assertEquals("zelda", acao("abre o jogo zelda no vortex")!!.args["jogo"])
        assertEquals("sonic", acao("jogue sonic no vortex")!!.args["jogo"])
    }

    @Test
    fun otimizaOVortex() {
        val a = acao("otimize o Vortex")!!
        assertEquals("vortex", a.nome)
        assertEquals("otimizar", a.args["acao"])
    }

    @Test
    fun abrirAppComumNaoViraVortex() {
        val a = acao("abre o youtube")
        assertEquals(true, a == null || a.nome != "vortex")
        assertEquals(true, acao("abra o vortex")?.nome != "vortex")
    }
}
