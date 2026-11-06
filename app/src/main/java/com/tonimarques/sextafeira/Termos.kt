package com.tonimarques.sextafeira

import android.content.Context

/*
 * Termos do teste-piloto: o lojista lê e aceita na primeira etapa do cadastro guiado.
 * Ao mudar o texto de forma importante, suba a VERSAO: quem aceitou a anterior vê os termos de novo.
 */
object Termos {
    const val VERSAO = 1
    private const val PREFS = "termos"

    fun aceitos(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("versao", 0) >= VERSAO

    fun aceitar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("versao", VERSAO).putLong("quando", System.currentTimeMillis()).apply()
        Diario.anotar(ctx, "conta", "Termos do teste-piloto aceitos (versão $VERSAO)", "tela")
    }

    val RESUMO: String get() =
        "• É uma versão de teste, grátis durante o piloto.\n" +
            "• Você usa as suas próprias chaves e contas (IA, Mercado Pago, InfinitePay, Meta).\n" +
            "• Os seus dados ficam no seu celular; nada é vendido nem compartilhado.\n" +
            "• Confira preços, estoque e cobranças antes de confirmar: podem acontecer erros.\n" +
            "• Você pode sair quando quiser, é só desinstalar."

    val TEXTO: String get() {
        val n = Marca.nome
        return """
1. O que é
A $n está em teste-piloto: uma versão em desenvolvimento, oferecida de graça a alguns lojistas convidados para testar e dar sugestões. O período de teste é de 30 dias e pode ser estendido. Nada é cobrado sem que você aceite antes; se a $n passar a ser paga (por assinatura), você será avisado com antecedência e decide se quer continuar.

2. Suas chaves e contas
A $n funciona com as suas próprias chaves e contas: inteligência artificial (Groq, Gemini), cobrança (Mercado Pago, InfinitePay) e Meta (Instagram, WhatsApp). Os limites, as regras e as eventuais tarifas dessas empresas são entre você e elas. O dinheiro das suas vendas cai direto na sua conta; ele não passa pela $n.

3. Seus dados
Produtos, vendas, clientes e conversas ficam guardados no seu celular, e as chaves ficam cifradas no próprio aparelho. Para entender e responder, a $n envia o texto necessário para os serviços de IA que você configurou. Os avisos de pagamento por link passam pelo servidor da $n só com o número da cobrança e a situação do pagamento, e são apagados automaticamente em até 30 dias. Os seus dados e os das suas clientes não são vendidos nem compartilhados com ninguém.

4. Robô e mensagens
O robô lê as notificações do WhatsApp Business no seu celular e responde em nome da sua loja, com as informações que você cadastrou. Você continua responsável pelo que a sua loja envia às clientes e por seguir as regras do WhatsApp, do Instagram e do Código de Defesa do Consumidor. Confira de vez em quando as respostas e mantenha preços e regras atualizados.

5. Versão de teste
Por ser uma versão de teste, podem acontecer falhas, respostas erradas ou recursos fora do ar. Confira preços, estoque, cobranças e publicações antes de confirmar. A $n é oferecida como está, sem garantia de funcionamento contínuo, e não substitui a sua conferência nem a orientação de um contador ou advogado.

6. Atualizações
A $n se atualiza pelo canal oficial dentro do próprio app. Recursos podem mudar, melhorar ou sair durante o piloto.

7. Saída
Você pode parar de usar quando quiser: é só desinstalar o app, o que apaga os dados guardados no celular. Suas contas nas outras empresas continuam suas.

8. Contato
Dúvidas, problemas e sugestões: fale com Toni Marques, responsável pela $n, pelo mesmo contato do convite. As suas sugestões ajudam a decidir o que melhorar primeiro.
""".trim()
    }
}
