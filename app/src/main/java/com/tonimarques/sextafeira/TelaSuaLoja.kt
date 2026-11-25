package com.tonimarques.sextafeira

import java.util.Calendar

/**
 * Minha loja › Sua loja hoje: o que a Vênus atendeu e o que a loja vendeu, hoje e nos últimos 7 dias, mais o que fica para fazer.
 * Funciona sem loja conectada (mostra só os atendimentos). Os números vêm do que a Vênus registrou neste celular e da loja conectada.
 */
internal fun MainActivity.abrirSuaLojaHoje() {
    aviso("Abrindo…")
    emFundo({
        val l = Loja(this)
        if (l.conectada) Pair(l.pedidos(), l.produtos()) else null
    }) { r ->
        val dados = r.getOrNull()
        val agora = Calendar.getInstance()
        val dias = Resultado.dias(this)
        val hoje = Resultado.somar(dias, Resultado.ultimosDias(agora, 1))
        val semana = Resultado.somar(dias, Resultado.ultimosDias(agora, 7))
        val semLoja = if (ferramentas.loja.conectada) "Não consegui ler a loja agora. Tente de novo daqui a pouco." else "Conecte a loja (Plataforma da loja) para ver as vendas aqui."

        fun bloco(titulo: String, atend: Resultado.Dia, nDias: Int) = TelaDeAjustes.Secao(
            titulo, listOf(
                TelaDeAjustes.Ajuste("Atendimentos", { Resultado.fraseDeAtendimento(atend) }) {},
                TelaDeAjustes.Ajuste("Vendas", { dados?.let { Resultado.fraseDeVendas(Resultado.vendas(it.first, agora, nDias)) } ?: semLoja }) {},
            )
        )

        val secoes = mutableListOf(bloco("Hoje", hoje, 1), bloco("Últimos 7 dias", semana, 7))
        if (dados != null) {
            val separar = dados.first.count { it.status == "paid" || it.status == "preparing" }
            val retirar = dados.first.count { it.entrega == "pickup" && it.status in setOf("paid", "preparing", "shipped") }
            val acabando = dados.second.count { it.ativo && it.estoque != null && it.estoque <= Loja.ESTOQUE_BAIXO }
            val tarefas = listOfNotNull(
                separar.takeIf { it > 0 }?.let { "$it para separar" },
                retirar.takeIf { it > 0 }?.let { "$it retirada(s) para agendar" },
                acabando.takeIf { it > 0 }?.let { "$it produto(s) acabando ou esgotado(s)" },
            )
            secoes += TelaDeAjustes.Secao(
                "Para fazer", listOf(TelaDeAjustes.Ajuste("Agora", { if (tarefas.isEmpty()) "Nada pendente por enquanto." else tarefas.joinToString(" · ") + "." }) {})
            )
        }
        secoes += TelaDeAjustes.Secao(
            "Como é contado", listOf(
                TelaDeAjustes.Ajuste("Só o que a Vênus registrou", {
                    "Os atendimentos são as respostas do robô neste celular. As vendas são os pedidos pagos da loja. A Vênus não afirma quais vendas vieram das conversas."
                }) {}
            )
        )
        telaDeLista("Sua loja hoje", secoes)
    }
}
