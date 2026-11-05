package com.tonimarques.sextafeira

import android.content.Context

/**
 * Regras que protegem a loja e a cliente (lei e Anvisa), guardadas na memória vetorial como tipo "regra".
 * A Vênus usa na conversa (quando a pergunta for parecida) e ao escrever respostas para clientes.
 * Entram uma vez por versão desta lista: quando mudar algum texto, aumente [VERSAO].
 */
object RegrasDaLoja {

    const val VERSAO = 1
    const val TIPO = "regra"

    data class Regra(val pergunta: String, val resposta: String, val fonte: String)

    val REGRAS = listOf(
        Regra(
            "A cliente pode desistir ou devolver uma compra feita pelo site?",
            "Sim. Pelo Código de Defesa do Consumidor (art. 49), em compras pela internet a cliente pode desistir em até 7 dias " +
                "depois de receber o produto, sem precisar explicar o motivo. A loja devolve todo o valor pago, frete incluído. " +
                "A devolução é combinada com a equipe pelo WhatsApp.",
            "CDC art. 49",
        ),
        Regra(
            "O produto veio com defeito, o que a cliente pode fazer?",
            "Defeito tem garantia por lei: 30 dias para produtos não duráveis (como géis e cosméticos) e 90 dias para duráveis " +
                "(como vibradores), contados do recebimento (CDC, art. 26). A loja tem até 30 dias para resolver; se não resolver, " +
                "a cliente escolhe entre troca, dinheiro de volta ou desconto (CDC, art. 18). Peça foto ou vídeo do problema pelo WhatsApp.",
            "CDC arts. 18 e 26",
        ),
        Regra(
            "A loja pode usar ou compartilhar os dados das clientes?",
            "Pela LGPD, os dados pessoais só podem ser usados para a finalidade informada (o pedido e a entrega). " +
                "A cliente tem direito de ver, corrigir e pedir para apagar os dados dela a qualquer momento.",
            "LGPD (Lei 13.709/2018)",
        ),
        Regra(
            "Quais géis íntimos a Anvisa proibiu em 2026?",
            "Em 25/02/2026 a Anvisa proibiu a venda de mais de 150 géis e lubrificantes íntimos do fabricante Marcos Marciano Wagner EPP, " +
                "das linhas Secret Love, Black Ice, Kama Sutra, Turbo Gel e Uzempica. Produtos dessas linhas desse fabricante não podem ser vendidos.",
            "Anvisa, 25/02/2026",
        ),
        Regra(
            "Que cuidado ter ao responder dúvida de saúde de cliente?",
            "A loja não dá orientação médica. Para dor, alergia, irritação, gravidez ou infecção, responda com carinho e indique procurar " +
                "um profissional de saúde. Pode falar do uso e da limpeza que estão no manual do produto.",
            "Regra da loja",
        ),
    )

    const val FONTE_VENDA = "regras de venda"

    /**
     * As regras de venda do Perfil viram perguntas e respostas na memória (tipo "regra") e vão para o site.
     * Só refaz quando as regras mudam. Bloqueante (banco e internet): chame em segundo plano.
     */
    fun sincronizarVenda(ctx: Context, memoria: Memoria) {
        val prefs = ctx.getSharedPreferences("regras_da_loja", Context.MODE_PRIVATE)
        val json = Perfil.regras.json().toString()
        val marca = json.hashCode().toString() + ":" + Perfil.loja
        if (prefs.getString("venda", null) != marca) {
            memoria.apagarDaFonte(TIPO, FONTE_VENDA)
            for ((p, r) in Perfil.regras.perguntas(Perfil.loja)) memoria.guardarVetor(TIPO, p, resposta = r, fonte = FONTE_VENDA)
            prefs.edit().putString("venda", marca).apply()
            Consciencia.esquecerRetrato()
        }
        // o site (Vênus da Loja, robô e Direct do Instagram) usa as mesmas regras
        if (prefs.getString("venda_site", null) != marca) {
            try {
                val loja = Loja(ctx)
                if (loja.conectada && Plataformas.tipo(ctx) == "floot") {
                    loja.enviarRegrasDeVenda(Perfil.regras.json())
                    prefs.edit().putString("venda_site", marca).apply()
                    Diario.anotar(ctx, "loja", "Regras de venda enviadas ao site", "automatico")
                }
            } catch (e: Exception) {
                // o site ainda não recebe as regras (ou está sem internet): tenta de novo na próxima vez
            }
        }
    }

    /** Linhas dos produtos proibidos pela Anvisa (para conferir o catálogo). */
    private val PROIBIDOS = Regex("\\b(?:secret love|black ice|kama sutra|turbo gel|uzempica)\\b")

    /** Produtos do catálogo com nome de uma linha proibida (confira o fabricante antes de tirar do ar). */
    fun suspeitosDaAnvisa(nomes: List<String>): List<String> = nomes.filter { PROIBIDOS.containsMatchIn(ComandosLocais.normalizar(it)) }

    /** Guarda as regras na memória (uma vez por versão). Devolve quantas entraram. */
    fun semear(ctx: Context, memoria: Memoria): Int {
        val prefs = ctx.getSharedPreferences("regras_da_loja", Context.MODE_PRIVATE)
        if (prefs.getInt("versao", 0) >= VERSAO) return 0
        var novas = 0
        for (r in REGRAS) if (memoria.guardarVetor(TIPO, r.pergunta, resposta = r.resposta, fonte = r.fonte)) novas++
        prefs.edit().putInt("versao", VERSAO).apply()
        Diario.anotar(ctx, "memoria", "Regras da loja na memória: ${REGRAS.size} (CDC, LGPD, Anvisa, saúde)", "automatico")
        return novas
    }
}
