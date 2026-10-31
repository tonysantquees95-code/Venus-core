package com.tonimarques.sextafeira

import java.util.Locale

/**
 * A identidade de cada versão do app, escolhida na compilação:
 *  - "sexta": Sexta-feira, assistente pessoal;
 *  - "venus": Vênus, assistente da loja Desejo de Intimidade.
 * O resto do código é o mesmo para as duas.
 */
object Marca {

    val ehVenus: Boolean = BuildConfig.FLAVOR == "venus"

    val nome: String = if (ehVenus) "Vênus" else "Sexta-feira"
    val nomeMaiusculo: String = nome.uppercase(Locale("pt", "BR"))
    val subtitulo: String = if (ehVenus) "ASSISTENTE DA LOJA" else "ASSISTENTE PESSOAL"

    /** Primeiras linhas das instruções da IA. */
    val personalidade: String =
        if (ehVenus) {
            "Você é a Vênus, assistente do dono da loja online Desejo de Intimidade, que vende produtos " +
                "para o bem-estar íntimo com entrega discreta. Você é elegante, discreta, profissional e " +
                "simpática. Ajuda a cuidar da loja e também das tarefas do dia a dia no celular."
        } else {
            "Você é a Sexta-feira, assistente pessoal no celular Android do usuário: eficiente, " +
                "simpática e com um toque de humor leve."
        }

    // ------------------------------------------------------------- cores
    /** Sexta-feira passeia por todas as cores; Vênus fica nos tons de rosa, magenta e dourado. */
    val cicloCompleto: Boolean = !ehVenus

    /** Matiz central e quanto ele oscila (graus no círculo de cores), quando não é ciclo completo. */
    const val MATIZ_VENUS = 330f
    const val OSCILACAO_VENUS = 40f

    /** Distância entre as três camadas de cor do orbe. */
    val deslocamentoCor2: Float = if (ehVenus) 28f else 75f
    val deslocamentoCor3: Float = if (ehVenus) 55f else 160f
}
