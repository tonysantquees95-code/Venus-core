package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.robo.Respostas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uma loja de outro ramo não pode receber nada que é da primeira loja. */
class TextosDeCadaLojaTest {
    private val proibidos = Regex("desejo|porto alegre|nat[aá]lia|lingerie|vibrador|[ií]ntim|intimidade|sexo|sensual|libido|orgasmo|discret|casal|\\bg[eé]is\\b|preservativo", RegexOption.IGNORE_CASE)

    private fun limpo(texto: String) = assertFalse("texto da primeira loja em: $texto", proibidos.containsMatchIn(texto))

    @Test fun reconheceORamo() {
        assertTrue(Perfil.ehRamoIntimo("moda íntima e sex shop"))
        assertTrue(Perfil.ehRamoIntimo("Lingerie"))
        assertTrue(Perfil.ehRamoIntimo("Bem-estar íntimo"))
        assertFalse(Perfil.ehRamoIntimo("papelaria"))
        assertFalse(Perfil.ehRamoIntimo("moda feminina e cosméticos"))
        assertFalse(Perfil.ehRamoIntimo(""))
    }

    @Test fun storiesDeReservaDasOutrasLojas() {
        val reserva = StoriesDoDia.reservaPara(false)
        assertTrue(reserva.size >= 14)
        assertEquals(reserva.size, reserva.map { it.titulo }.toSet().size)
        reserva.forEach { limpo(it.titulo); limpo(it.texto); assertTrue(it.titulo.length <= 45); assertTrue(it.texto.length <= 170) }
    }

    @Test fun datasEEstudosDasOutrasLojas() {
        Gestao.datasDoAno(2026, ramoIntimo = false).forEach { limpo(it.nome); limpo(it.dica) }
        Estudo.disciplinas(ramoIntimo = false, primeiraLoja = false).forEach { limpo(it); assertFalse(it.contains("hotel")) }
        RegrasDaLoja.regrasPara(false).forEach { limpo(it.pergunta); limpo(it.resposta) }
    }

    @Test fun roboDasOutrasLojas() {
        val menu = AtendenteDoRobo.menu("Loja X", "Vênus", temRetirada = false, temDiscreta = false)
        limpo(menu)
        assertTrue(menu.contains("6 - Falar com a loja"))
        val produtos = AtendenteDoRobo.decidir("1", AtendenteDoRobo.Conversa(listOf(AtendenteDoRobo.Msg(true, "oi", 1000L)), 0L, "principal", 1000L),
            emptyMap(), "Loja X", "Vênus", "", 2000L, temRetirada = false, temDiscreta = false, ramoIntimo = false)
        limpo(produtos!!.texto)
        val respostas = Respostas.modelos(entregaDiscreta = false, ramoIntimo = false)
        respostas.forEach { limpo(it.pergunta) }
        assertEquals(Respostas.MODELOS.size - 2, respostas.size)
    }
}
