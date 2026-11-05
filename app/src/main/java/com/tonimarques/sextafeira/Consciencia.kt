package com.tonimarques.sextafeira

import android.content.Context

/**
 * O autoconhecimento da Vênus: o que ela é, com que IAs pensa, que ferramentas tem, o que está ligado,
 * o que já aprendeu e as lições que tirou dos próprios erros.
 *  - [retrato]: um resumo curto que vai nas instruções de toda conversa (guardado por 5 minutos);
 *  - [relatorio]: o quadro completo, devolvido pela ferramenta sobre_mim quando ela precisa explicar a si mesma.
 * Tudo é lido do próprio app na hora, então ela nunca descreve uma capacidade que não tem.
 */
object Consciencia {

    const val TIPO_LICAO = "licao"
    private const val LICOES_NAS_INSTRUCOES = 6
    private const val VALIDADE = 5 * 60_000L

    @Volatile private var guardado: Pair<Long, String>? = null

    /** Depois de uma mudança grande (chave nova, lição nova), o próximo retrato é refeito na hora. */
    fun esquecerRetrato() {
        guardado = null
    }

    fun versao(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        "?"
    }

    /** A ordem das IAs que respondem a conversa agora. */
    fun cerebro(ctx: Context): List<String> {
        val reservas = SegundaIA.cadeia(ctx).map { it.first.nome.substringBefore(" (") }
        return if (SegundaIA.principal(ctx)) reservas + "Gemini" else listOf("Gemini") + reservas
    }

    /** Resumo curto de si mesma (vai nas instruções de toda conversa). Lê o banco: chame fora da thread principal. */
    fun retrato(ctx: Context, memoria: Memoria, ferramentas: Ferramentas): String {
        guardado?.let { (quando, texto) -> if (System.currentTimeMillis() - quando < VALIDADE) return texto }
        val texto = try {
            montarRetrato(ctx, memoria, ferramentas)
        } catch (e: Exception) {
            ""
        }
        guardado = System.currentTimeMillis() to texto
        return texto
    }

    private fun montarRetrato(ctx: Context, memoria: Memoria, ferramentas: Ferramentas): String {
        val contagem = memoria.contarVetores()
        val total = contagem.values.sumOf { it.first }
        val nomes = nomesDasFerramentas(ferramentas)
        val licoes = memoria.itensDoTipo(TIPO_LICAO, LICOES_NAS_INSTRUCOES).map { it.texto }
        val estado = estadoCurto(ctx)
        return buildString {
            append("Quem você é: a ${Marca.nome} versão ${versao(ctx)}, um app Android feito sob medida para o Toni (ele mesmo pede as mudanças). ")
            append("Você pensa com estas IAs, nesta ordem: ${cerebro(ctx).joinToString(" → ")}. ")
            append("Tem ${nomes.size} ferramentas de ação (${nomes.joinToString(", ")}), comandos rápidos que rodam sem IA, ")
            append("memória vetorial com $total itens e um estudo diário toda noite. ")
            if (estado.isNotBlank()) append("Agora: $estado. ")
            append("Quando perguntarem o que você é, o que sabe fazer, por que algo falhou ou quais são seus limites, use sobre_mim e responda com o que ela mostrar. ")
            append("Nunca afirme ter uma capacidade que não está aí; se não tiver, diga e sugira o que pode fazer no lugar.")
            if (licoes.isNotEmpty()) {
                append("\nLições que você tirou dos seus erros (siga):\n")
                append(licoes.joinToString("\n") { "- $it" })
            }
        }
    }

    private fun nomesDasFerramentas(ferramentas: Ferramentas): List<String> {
        val d = ferramentas.declaracoes()
        return (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
    }

    /** O que está ligado, em poucas palavras. */
    private fun estadoCurto(ctx: Context): String = listOfNotNull(
        "loja ${if (Loja(ctx).conectada) "conectada" else "desconectada"}",
        Instagram.usuario(ctx)?.let { "Instagram @$it" } ?: "Instagram desconectado",
        if (StoriesDoDia.ligado(ctx)) "stories automáticos ligados" else null,
        if (Estudo.ligado(ctx)) null else "estudo diário desligado",
        try {
            Atualizador(ctx).versaoDoRobo()?.let { "Vênus Robô 1.$it instalado" }
        } catch (e: Exception) {
            null
        },
    ).joinToString(", ")

    /** O quadro completo (ferramenta sobre_mim). Lê o banco e o celular: chame fora da thread principal. */
    fun relatorio(ctx: Context, memoria: Memoria, ferramentas: Ferramentas): String {
        val d = ferramentas.declaracoes()
        val ferramentasTexto = (0 until d.length()).mapNotNull { d.optJSONObject(it) }
            .joinToString("\n") { "- ${it.optString("name")}: ${it.optString("description").take(110)}" }
        val contagem = memoria.contarVetores().entries.sortedByDescending { it.value.first }
            .joinToString(", ") { "${it.key} ${it.value.first}" }
        val estudo = Estudo.ultimo(ctx)?.let { "último estudo ${it.data}: +${it.total} itens" + (it.erro?.let { e -> " (problema: ${e.take(120)})" } ?: "") } ?: "ainda não estudou"
        val erros = Registro.log(ctx).take(5).joinToString("\n") { "- ${it.tipo}: ${(it.detalhe.ifBlank { it.frase }).take(100)} (${it.vezes}x)" }
        val ajustes = try {
            Diario.de(ctx).ultimos(5).joinToString("\n") { "- ${Diario.hora(it.quando)} ${it.area}: ${it.texto.take(100)}${if (it.ok) "" else " (falhou)"}" }
        } catch (e: Exception) {
            ""
        }
        val licoes = memoria.itensDoTipo(TIPO_LICAO, 12).joinToString("\n") { "- ${it.texto}" }
        val ultimaReserva = if (SegundaIA.ultimaVez > 0) Diario.hora(SegundaIA.ultimaVez) else "ainda não usada nesta sessão"
        return listOf(
            "IDENTIDADE: ${Marca.nome} ${versao(ctx)}, assistente pessoal e comercial do Toni, app Android próprio. Também existem a Vênus da Loja " +
                "(atende no site desejo-de-intimidade.floot.app) e o Vênus Robô (responde o WhatsApp Business pelas notificações).",
            "CÉREBRO (ordem): ${cerebro(ctx).joinToString(" → ")}. IA reserva usada pela última vez: $ultimaReserva. Voz: ${VozNeural.resumo(ctx)}",
            "ESTADO: ${estadoCurto(ctx)}.",
            "MEMÓRIA: $contagem. $estudo.",
            "FERRAMENTAS DE AÇÃO:\n$ferramentasTexto",
            "COMANDOS RÁPIDOS (sem IA): lembretes e alarmes, contas e finanças, loja (pedidos, estoque, lucro, cadastrar produto, nota de compra), " +
                "Instagram (posts programados, stories da manhã e da tarde, enquete), ideias de post, estudo (\"estuda agora\", \"mostra o que você aprendeu\"), " +
                "anexos de fotos e arquivos, pesquisa de produto pela foto, atualização do app e do robô.",
            "LIMITES: Groq grátis ~1.000 pedidos e 200 mil tokens por dia por modelo; Cloudflare 10 mil neurons por dia; Gemini grátis ~20 pedidos por dia " +
                "(zera por volta das 5h). PDF, áudio e busca no Google só no Gemini. Voz Microsoft: 500 mil caracteres por mês (para em 470 mil). " +
                "Não lê a tela de outros apps; mensagens e ligações precisam do toque do Toni em Enviar ou Ligar; o Direct do Instagram é respondido pelo site, não pelo app.",
            "LIÇÕES DOS SEUS ERROS:\n${licoes.ifBlank { "- (nenhuma ainda)" }}",
            "ERROS RECENTES:\n${erros.ifBlank { "- nenhum" }}",
            "ÚLTIMOS AJUSTES:\n${ajustes.ifBlank { "- nenhum" }}",
        ).joinToString("\n").take(6000)
    }
}
