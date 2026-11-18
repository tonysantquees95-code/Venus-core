package com.tonimarques.sextafeira

import java.util.Calendar

/**
 * O painel do dia: na primeira vez que a pessoa abre a Vênus em cada dia, aparece uma mensagem curta no chat com o que importa agora
 * (lembretes de hoje, como está a loja e uma dica de algo que a Vênus sabe fazer). Só escreve, não fala. Dá para desligar no Menu.
 * A lógica é pura (sem Android) para ser testada.
 */
object PainelDoDia {
    const val PREF_LIGADO = "painel_dia_ligado"
    const val PREF_ULTIMO = "painel_dia_ultimo"

    /** Dicas que giram pelos dias; todas são coisas que a Vênus já faz. */
    val DICAS = listOf(
        "Pergunte: o que está acabando na loja?",
        "Peça: uma ideia de post para hoje.",
        "Diga: me lembra amanhã às 9 de separar os pedidos.",
        "Pergunte: como foram as vendas da semana?",
        "Peça: um resumo da loja.",
        "Diga: me lembra daqui a 2 horas de responder os clientes.",
        "Pergunte: quais produtos estão parados?",
    )

    fun chaveDoDia(agora: Calendar): String =
        "%04d-%02d-%02d".format(agora.get(Calendar.YEAR), agora.get(Calendar.MONTH) + 1, agora.get(Calendar.DAY_OF_MONTH))

    /** Mostra se está ligado, se ainda não mostrou hoje e se é um horário razoável (das 5h às 22h). */
    fun deveMostrar(ligado: Boolean, ultimoDia: String?, agora: Calendar): Boolean =
        ligado && ultimoDia != chaveDoDia(agora) && agora.get(Calendar.HOUR_OF_DAY) in 5..21

    fun saudacao(agora: Calendar): String = when (agora.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }

    fun dica(agora: Calendar): String = DICAS[agora.get(Calendar.DAY_OF_YEAR) % DICAS.size]

    /** [lembretes] já vêm falados ("às 9h, separar os pedidos"); [loja] é o resumo da loja (ou nulo se não está conectada). */
    fun montar(apelido: String?, lembretes: List<String>, loja: String?, aviso: String?, agora: Calendar): String {
        val partes = mutableListOf("${saudacao(agora)}${if (!apelido.isNullOrBlank()) ", $apelido" else ""}! Seu painel de hoje:")
        if (lembretes.isNotEmpty()) {
            partes += "Lembretes: " + lembretes.take(5).joinToString("; ") + (if (lembretes.size > 5) " e mais ${lembretes.size - 5}." else ".")
        }
        if (!loja.isNullOrBlank()) partes += loja.trim()
        if (!aviso.isNullOrBlank()) partes += aviso.trim()
        if (lembretes.isEmpty() && loja.isNullOrBlank() && aviso.isNullOrBlank()) partes += "Nada marcado para hoje."
        partes += "Dica: ${dica(agora)}"
        return partes.joinToString("\n")
    }
}
