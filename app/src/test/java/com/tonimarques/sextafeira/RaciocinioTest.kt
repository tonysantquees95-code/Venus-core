package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.ComandosLocais.Resultado.Acao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaciocinioTest {
    private fun acao(f: String) = ComandosLocais.interpretar(f) as? Acao

    /** Perguntas que pedem raciocínio: vão para o modo "pensar com calma". */
    @Test fun reconhecePerguntasDeRaciocinio() {
        val dificeis = listOf(
            "Se 5 máquinas fazem 5 peças em 5 minutos, quanto tempo 100 máquinas levam para fazer 100 peças?",
            "Se todos os gatos são mamíferos e alguns mamíferos voam, então algum gato voa?",
            "Qual é o próximo número da sequência 2, 6, 12, 20, 30?",
            "Um taco e uma bola custam 11 reais, o taco custa 10 a mais que a bola, quanto custa a bola?",
            "Vale a pena investir 3 mil reais em conjuntos de lingerie agora antes do natal?",
            "Qual a melhor estratégia para vender mais na Black Friday?",
            "Compare vender no Mercado Livre e vender só no meu site",
            "Por que o céu é azul?",
            "Qual a probabilidade de tirar dois seis jogando dois dados?",
            "Quem está mentindo: Ana diz que Bia mente, Bia diz que Carla mente e Carla diz que Ana e Bia mentem",
        )
        for (f in dificeis) assertTrue(f, Raciocinio.precisaPensar(f))
        val simples = listOf(
            "que horas são", "abre o WhatsApp", "como foram as vendas hoje", "toca rock", "qual a capital da França",
            "bom dia", "quanto é 2 mais 2", "previsão do tempo para amanhã",
        )
        for (f in simples) assertFalse(f, Raciocinio.precisaPensar(f))
    }

    /** A frase inteira é lida: dois pedidos juntos viram duas etapas. */
    @Test fun divideFrasesComVariosPedidos() {
        val e = Raciocinio.dividir("como foram as vendas hoje e quais produtos estão acabando")!!
        assertEquals(listOf("loja_resumo", "loja_acabando"), e.map { it.second })
        val tres = Raciocinio.dividir("como foram as vendas hoje, quais produtos estão acabando e tem pedido novo?")!!
        assertEquals(listOf("loja_resumo", "loja_acabando", "loja_pedidos"), tres.map { it.second })
        // a segunda parte herda o verbo: "paguei 120 de internet e 80 de luz"
        val d = Raciocinio.dividir("paguei 120 de internet e 80 de luz")!!
        assertEquals(listOf("paguei 120 de internet", "paguei 80 de luz"), d.map { it.first })
        // não divide o que é um pedido só
        assertNull(Raciocinio.dividir("manda pra Natália que eu comprei pão e leite"))
        assertNull(Raciocinio.dividir("coloca o gel e o óleo em promoção"))
        assertNull(Raciocinio.dividir("como foram as vendas hoje"))
        // o que precisa de confirmação só pode ser a última etapa
        assertNull(Raciocinio.dividir("muda o preço do gel para 30 e como foram as vendas hoje", entende = { p ->
            when {
                p.startsWith("muda") -> "loja_alterar"
                p.startsWith("como") -> "loja_resumo"
                else -> null
            }
        }))
    }

    @Test fun pesquisaDePrecos() {
        val a = acao("quanto custa o gel KY no Mercado Livre")!!
        assertEquals("pesquisar_preco", a.nome)
        assertEquals("gel KY", a.args["produto"])
        assertEquals("vibrador bullet", acao("pesquisa o preço do vibrador bullet")!!.args["produto"])
        assertEquals("pesquisar_preco", acao("qual o menor preço do conjunto de renda preto")!!.nome)
        assertEquals("pesquisar_preco", acao("onde compro óleo de massagem mais barato")!!.nome)
        assertEquals("gel lubrificante", acao("quanto os concorrentes cobram pelo gel lubrificante")!!.args["produto"])
        // o preço da própria loja continua sendo da loja
        assertTrue(acao("quanto custa o gel kgel")?.nome != "pesquisar_preco")

        val texto = """
            Mercado Livre — R$ 29,90 — frete grátis
            - Amazon — R$ 34,50 — kit com 2
            Shopee: R$ 24,99
            Magazine Luiza — R$ 1.299,90 — tamanho grande
            Texto qualquer sem preço
        """.trimIndent()
        val ofertas = Precos.lerOfertas(texto)
        assertEquals(listOf("Mercado Livre", "Amazon", "Shopee", "Magazine Luiza"), ofertas.map { it.loja })
        assertEquals(listOf(2990, 3450, 2499, 129990), ofertas.map { it.precoCentavos })
        assertEquals("frete grátis", ofertas[0].obs)
        val tres = ofertas.take(3)
        assertEquals(2499 to 2979, Precos.faixa(tres))
        val falado = Precos.textoOfertas("gel", tres)
        assertTrue(falado, falado.startsWith("Preços de gel na internet: Shopee, R$ 24,99; Mercado Livre"))
        val comp = Precos.comparar("Gel KGEL", 3990, 1200, tres)!!
        assertTrue(comp, comp.contains("34% acima da média"))
        assertTrue(comp, comp.contains("a margem seria de 59%"))
        assertTrue(Precos.comparar("Gel", 3000, null, tres)!!.contains("na média do mercado"))
    }
}
