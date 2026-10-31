package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import com.tonimarques.sextafeira.ComandosMemoria.Pedido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rotas no Maps: casa, trabalho, lugares salvos, a pé, de ônibus, Waze, sem pedágio. */
class RotasTest {

    private fun rota(frase: String): Acao {
        val r = ComandosLocais.interpretar(frase)
        assertTrue("Esperava rota para \"$frase\", veio $r", r is Acao && r.nome == "abrir_mapa")
        return r as Acao
    }

    @Test fun pedidosDeRota() {
        assertEquals("casa", rota("Vênus, trace uma rota para casa").args["destino"])
        assertEquals("trabalho", rota("me leve para o trabalho").args["destino"])
        assertEquals("casa", rota("quero ir pra casa").args["destino"])
        assertEquals("casa", rota("vamos pra casa").args["destino"])
        assertEquals("rodoviária", rota("me leva pra rodoviária").args["destino"])
        assertEquals("casa da minha mãe", rota("me leva para a casa da minha mãe").args["destino"])
        assertEquals("estação de metrô", rota("me leva para a estação de metrô").args["destino"])
    }

    @Test fun jeitoDeIr() {
        val a = rota("me leva pra academia a pé")
        assertEquals("academia", a.args["destino"])
        assertEquals("a_pe", a.args["modo"])
        assertEquals("transporte", rota("como chego no shopping de ônibus").args["modo"])
        assertEquals("waze", rota("me leva para o trabalho pelo Waze").args["app"])
        val p = rota("trace uma rota para Gramado sem pedágio")
        assertEquals("Gramado", p.args["destino"])
        assertEquals("pedagio", p.args["evitar"])
    }

    @Test fun naoEhRota() {
        val r = ComandosLocais.interpretar("vamos conversar sobre futebol")
        assertTrue("$r", r !is Acao || r.nome != "abrir_mapa")
        val e = ComandosLocais.interpretar("quero ir embora")
        assertTrue("$e", e !is Acao || e.nome != "abrir_mapa")
    }

    @Test fun salvarLugares() {
        val a = ComandosMemoria.interpretar("minha casa fica na Rua Ernesto Vidal Martins, 123")
        assertEquals(Pedido.Definir("lugar:casa", "Rua Ernesto Vidal Martins, 123"), a)
        val b = ComandosMemoria.interpretar("o endereço do meu trabalho é Avenida Ipiranga 6681")
        assertEquals(Pedido.Definir("lugar:trabalho", "Avenida Ipiranga 6681"), b)
        assertEquals(Pedido.Definir("lugar:casa", "Rua Duque de Caxias, 50"), ComandosMemoria.interpretar("eu moro na Rua Duque de Caxias, 50"))
        assertEquals(Pedido.Definir("lugar:academia", "Rua Silva Só, 200"), ComandosMemoria.interpretar("a academia fica na Rua Silva Só, 200"))
        assertEquals(Pedido.Definir("lugar:casa da mae", "Canoas"), ComandosMemoria.interpretar("a casa da minha mãe fica em Canoas"))
        assertNull(ComandosMemoria.interpretar("meu trabalho é chato"))
        assertNull(ComandosMemoria.interpretar("minha casa fica longe"))
        assertEquals(Pedido.Definir("cidade", "Porto Alegre"), ComandosMemoria.interpretar("eu moro em Porto Alegre"))
    }

    @Test fun aquiEMinhaCasa() {
        val a = ComandosLocais.interpretar("aqui é minha casa") as Acao
        assertEquals("salvar_lugar_aqui", a.nome)
        assertEquals("casa", a.args["lugar"])
        assertEquals("academia", (ComandosLocais.interpretar("salva onde eu estou como academia") as Acao).args["lugar"])
        val r = ComandosLocais.interpretar("aqui é perigoso")
        assertTrue("$r", r !is Acao || r.nome != "salvar_lugar_aqui")
        assertEquals("trabalho", ComandosMemoria.nomeDoLugar("escritório".let { ComandosLocais.normalizar(it) }))
    }
}
