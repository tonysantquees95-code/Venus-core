package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistroTest {
    @Test fun tiraDadosPessoais() {
        val t = Registro.limpar("manda pra (51) 99999-0000 o email ana@gmail.com e o CPF 123.456.789-00, veja https://site.com/a?token=abc")
        assertFalse(t, t.contains("99999"))
        assertFalse(t, t.contains("ana@gmail.com"))
        assertFalse(t, t.contains("789"))
        assertFalse(t, t.contains("token=abc"))
        assertTrue(t, t.contains("[e-mail]"))
        assertTrue(t, t.contains("https://site.com/a"))
        // valores pequenos continuam (são úteis para entender o erro)
        assertTrue(Registro.limpar("paguei 120 de internet").contains("120"))
    }

    @Test fun juntaRepeticoes() {
        assertEquals(Registro.assinatura("erro", "loja_resumo → timeout 15 s"), Registro.assinatura("erro", "loja_resumo → timeout 30 s"))
        assertTrue(Registro.assinatura("erro", "a") != Registro.assinatura("ia", "a"))
    }

    @Test fun reconheceQuandoErrou() {
        for (f in listOf("não foi isso", "Não, você entendeu errado", "errado", "não era isso que eu pedi", "tá errado", "você errou")) {
            assertTrue(f, Registro.ehCorrecao(f))
        }
        for (f in listOf("não", "sim", "está tudo certo", "o que está errado no site da loja amanhã de manhã cedo")) {
            assertFalse(f, Registro.ehCorrecao(f))
        }
    }

    @Test fun relatorio() {
        val lista = listOf(
            Registro.Ocorrencia("nao_entendi", "sem ação para a frase", "quanto rendeu o pix ontem", 3, 0, 1000, "1.59"),
            Registro.Ocorrencia("erro", "loja_resumo → JSONException: x", "", 1, 0, 2000, "1.59"),
        )
        val r = Registro.relatorio(lista, "Samsung A54, Android 14")
        assertTrue(r, r.contains("### Frases que ela não entendeu (3)"))
        assertTrue(r, r.contains("**3x** “quanto rendeu o pix ontem”"))
        assertTrue(r, r.contains("### Erros ao executar uma ação (1)"))
        val e = Registro.resumoDoErro(IllegalStateException("quebrou", RuntimeException("raiz")))
        assertTrue(e, e.startsWith("IllegalStateException: quebrou (causa: RuntimeException: raiz)"))
        assertEquals("enviar_relatorio_erros", (ComandosLocais.interpretar("manda o relatório de erros") as Acao).nome)
    }
}
