package com.tonimarques.sextafeira

import java.util.Locale

/**
 * A identidade da assistente: Vênus, pessoal e comercial (ela nasceu da Sexta-feira).
 * Tudo que diz o nome, a personalidade e as cores fica aqui.
 */
object Marca {

    val nome: String = "Vênus"
    val nomeMaiusculo: String = nome.uppercase(Locale("pt", "BR"))
    val subtitulo: String = "ASSISTENTE PESSOAL E COMERCIAL"

    /** Primeiras linhas das instruções da IA. */
    val personalidade: String get() =
        "Você é a Vênus, assistente pessoal e comercial do usuário, no celular Android dele. " +
            "Ele é dono da loja online ${Perfil.loja}, que vende produtos para o bem-estar íntimo " +
            "com entrega discreta. Você cuida tanto da vida pessoal (apps, música, mensagens, clima, " +
            "lembretes, conversa) quanto da loja. Você é elegante, discreta, eficiente e simpática, " +
            "com um toque de humor leve."

    // ------------------------------------------------------------- cores
    /** false = cores oscilam em torno dos tons da Vênus (roxo, magenta, rosa e dourado). */
    val cicloCompleto: Boolean = false

    /** Matiz central e quanto ele oscila (graus no círculo de cores). */
    const val MATIZ_VENUS = 325f
    const val OSCILACAO_VENUS = 60f

    /** Distância entre as três camadas de cor do orbe. */
    val deslocamentoCor2: Float = 30f
    val deslocamentoCor3: Float = 60f
}
