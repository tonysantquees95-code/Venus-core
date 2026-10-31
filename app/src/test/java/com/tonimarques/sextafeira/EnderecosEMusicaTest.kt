package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import com.tonimarques.sextafeira.ComandosMemoria.Pedido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frases dos prints: salvar endereços de vários jeitos, continuar a música e lugares perto. */
class EnderecosEMusicaTest {

    @Test fun salvarEnderecosComoNoPrint() {
        assertEquals(
            Pedido.Definir("lugar:casa", "Rua Maranhão Vidal Martins 45 em Porto Alegre"),
            ComandosMemoria.interpretar("Vênus o meu endereço é na Rua Maranhão Vidal Martins 45 em Porto Alegre grave este endereço como minha casa")
        )
        assertEquals(
            Pedido.Definir("lugar:trabalho", "Hotel Intercity aeroporto Porto Alegre"),
            ComandosMemoria.interpretar("grave também o meu endereço do trabalho eu trabalho no Hotel Intercity aeroporto Porto Alegre")
        )
        assertEquals(Pedido.PerguntarLugar("trabalho"), ComandosMemoria.interpretar("Vênus o endereço do meu trabalho"))
        assertEquals(Pedido.PerguntarLugar("trabalho"), ComandosMemoria.interpretar("Vênus salve o endereço do meu trabalho"))
        assertEquals(Pedido.PerguntarLugar("trabalho"), ComandosMemoria.interpretar("salvar endereço do trabalho"))
        assertEquals(Pedido.Definir("lugar:casa", "Rua X, 10"), ComandosMemoria.interpretar("meu endereço é Rua X, 10"))
        assertEquals(
            Pedido.Definir("lugar:trabalho", "Avenida Ipiranga 6681"),
            ComandosMemoria.interpretar("o endereço do meu trabalho é Avenida Ipiranga 6681")
        )
    }

    @Test fun respostaComOEndereco() {
        assertEquals("Rua Professor Sarmento Barata 360", Cerebro.enderecoDaResposta("é na Rua Professor Sarmento Barata 360"))
        assertEquals("Rua Professor Sarmento Barata 360", Cerebro.enderecoDaResposta("Rua Professor Sarmento Barata 360"))
        assertEquals("Hotel Intercity Aeroporto", Cerebro.enderecoDaResposta("eu trabalho no Hotel Intercity Aeroporto"))
        assertNull(Cerebro.enderecoDaResposta("cancela"))
        assertNull(Cerebro.enderecoDaResposta("abre o whatsapp"))
    }

    private fun acao(frase: String): Acao? = ComandosLocais.interpretar(frase) as? Acao

    @Test fun continuarAMusica() {
        for (f in listOf(
            "toque a última música tocada no Deezer", "dê play no Deezer", "dá play", "continua a música no Spotify",
            "toca de onde parou", "toca a música que eu estava ouvindo",
        )) {
            val a = acao(f)
            assertTrue("\"$f\" deveria continuar a música, veio $a", a != null && a.nome == "controlar_midia" && a.args["acao"] == "tocar")
        }
        assertEquals("deezer", acao("toque a última música tocada no Deezer")!!.args["app"])
        assertEquals("tocar_musica", acao("toca Sagrado Profano no Deezer")!!.nome)
    }

    @Test fun lugaresPerto() {
        val a = acao("Vênus Qual é ferragem mais próxima da onde eu estou")!!
        assertEquals("buscar_perto", a.nome)
        assertEquals("ferragem", a.args["oque"])
        assertEquals("farmácia", acao("onde tem farmácia perto de mim")!!.args["oque"])
        assertEquals("casa", acao("qual o mercado mais próximo da minha casa")!!.args["referencia"])
        val p = ComandosLocais.interpretar("qual o planeta mais próximo")
        assertTrue("$p", p !is Acao || p.nome != "buscar_perto")
    }
}
