package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * As regras de venda da loja em campos (pagamento, frete, retirada, envio, trocas, atendimento, restrições).
 * É a fonte única: vira texto para as IAs ([texto]), perguntas e respostas na memória ([perguntas]),
 * contas exatas ([precoNoPix], [parcelas], [freteGratis]) e o JSON que vai para o site ([json]).
 * Campo null = a loja não informou; a Vênus não inventa e diz que a equipe confirma.
 * Os valores padrão são os da Desejo de Intimidade (os que estavam escritos no código).
 */
data class RegrasDeVenda(
    // pagamento
    val pixDescontoPct: Int = 5,
    val pixValeEmPromocao: Boolean = false,
    val cartaoParcelasMax: Int = 3,
    val parcelasSemJuros: Int = 3,
    // frete
    val freteMetodos: List<String> = listOf("PAC", "SEDEX"),
    val freteCalculo: String = "pelo CEP no site",
    val freteGratisAcimaDe: Int? = null,
    val prazoPostagemDias: Int? = null,
    // retirada
    val retiradaDisponivel: Boolean = true,
    val retiradaCidade: String = "Porto Alegre",
    val retiradaComo: String = "dia e horário combinados pelo WhatsApp depois da compra",
    val retiradaCustoCentavos: Int = 0,
    // envio
    val embalagemDiscreta: Boolean = true,
    val abrangencia: String = "todo o Brasil",
    // trocas
    val arrependimentoDias: Int = 7,
    val garantia: String = "pelo Código de Defesa do Consumidor",
    // atendimento
    val canais: List<String> = listOf("site", "WhatsApp", "Instagram"),
    val horarioAtendimento: String? = null,
    // restrições
    val idadeMinima: Int? = 18,
    /** O que não cabe nos campos (texto livre). */
    val observacoes: String = "",
) {
    // ------------------------------------------------------------------ contas
    /** Preço no Pix (null se a loja não dá desconto ou se o produto está em promoção e o Pix não vale). */
    fun precoNoPix(centavos: Int, emPromocao: Boolean = false): Int? {
        if (pixDescontoPct <= 0 || (emPromocao && !pixValeEmPromocao)) return null
        return centavos - (centavos * pixDescontoPct + 50) / 100
    }

    /** "3x de R$ 16,63 sem juros" (null se não parcela). */
    fun parcelas(centavos: Int): String? {
        if (cartaoParcelasMax <= 1) return null
        val vezes = cartaoParcelasMax
        val parcela = (centavos + vezes - 1) / vezes
        val juros = if (parcelasSemJuros >= vezes) " sem juros" else if (parcelasSemJuros > 1) " (sem juros até ${parcelasSemJuros}x)" else ""
        return "${vezes}x de ${dinheiro(parcela)}$juros"
    }

    /** O pedido ganha frete grátis? (null = a loja não tem regra de frete grátis.) */
    fun freteGratis(totalCentavos: Int): Boolean? = freteGratisAcimaDe?.let { totalCentavos >= it }

    // ------------------------------------------------------------------ texto para as IAs
    fun texto(): String = listOfNotNull(
        "pagamento: " + listOfNotNull(
            if (pixDescontoPct > 0) "Pix com $pixDescontoPct% de desconto" + (if (pixValeEmPromocao) "" else " (exceto produtos em promoção)") else "Pix",
            if (cartaoParcelasMax > 1) "cartão em até ${cartaoParcelasMax}x" + (if (parcelasSemJuros >= cartaoParcelasMax) " sem juros" else if (parcelasSemJuros > 1) " (sem juros até ${parcelasSemJuros}x)" else "") else "cartão à vista",
        ).joinToString(" ou "),
        "entrega ${if (embalagemDiscreta) "discreta " else ""}para $abrangencia" +
            (if (freteMetodos.isNotEmpty()) " por ${freteMetodos.joinToString(" ou ")}" else "") +
            (if (freteCalculo.isNotBlank()) ", frete calculado $freteCalculo" else "") +
            (freteGratisAcimaDe?.let { ", frete grátis acima de ${dinheiro(it)}" } ?: "") +
            (prazoPostagemDias?.let { ", postagem em até $it ${if (it == 1) "dia útil" else "dias úteis"} depois do pagamento" } ?: ""),
        if (retiradaDisponivel) "retirada ${if (retiradaCustoCentavos == 0) "grátis" else "por ${dinheiro(retiradaCustoCentavos)}"} em $retiradaCidade" +
            (if (retiradaComo.isNotBlank()) ", $retiradaComo" else "") else "sem retirada no local",
        if (embalagemDiscreta) "embalagem neutra e sem identificação" else null,
        "arrependimento em até $arrependimentoDias dias do recebimento; defeito com garantia $garantia",
        if (canais.isNotEmpty()) "atendimento por ${canais.joinToString(", ")}" + (horarioAtendimento?.let { ", $it" } ?: "") else null,
        idadeMinima?.let { "venda só para maiores de $it anos" },
        observacoes.trim().ifBlank { null },
    ).joinToString("; ") + "."

    /** Os campos que a loja deixou sem informar (a Vênus diz que a equipe confirma, sem inventar). */
    fun faltando(): List<String> = listOfNotNull(
        if (freteMetodos.isEmpty()) "formas de envio" else null,
        if (freteCalculo.isBlank()) "como o frete é calculado" else null,
        if (freteGratisAcimaDe == null) "frete grátis" else null,
        if (prazoPostagemDias == null) "prazo de postagem" else null,
        if (horarioAtendimento == null) "horário de atendimento" else null,
        if (canais.isEmpty()) "canais de atendimento" else null,
    )

    // ------------------------------------------------------------------ memória (tipo "regra")
    /** Perguntas que as clientes fazem, com a resposta tirada só dos campos preenchidos. */
    fun perguntas(loja: String): List<Pair<String, String>> = perguntasComId(loja).map { it.second to it.third }

    /** As mesmas perguntas com um nome fixo (pagamento, frete…), para o robô achar a resposta certa. */
    fun perguntasComId(loja: String): List<Triple<String, String, String>> = listOfNotNull(
        Triple("pagamento", "Quais são as formas de pagamento da $loja?", "Na $loja dá para pagar " + texto().substringAfter("pagamento: ").substringBefore(";") + "."),
        Triple("frete", "Como funciona o frete e a entrega da $loja?", "A $loja faz " + texto().split("; ").first { it.startsWith("entrega") } + "."),
        freteGratisAcimaDe?.let { Triple("frete_gratis", "A $loja tem frete grátis?", "Sim, nas compras acima de ${dinheiro(it)}.") },
        prazoPostagemDias?.let { Triple("postagem", "Em quanto tempo a $loja posta o pedido?", "Em até $it ${if (it == 1) "dia útil" else "dias úteis"} depois da confirmação do pagamento.") },
        Triple("retirada", "Dá para retirar o pedido pessoalmente na $loja?",
            if (retiradaDisponivel) "Sim, a retirada é ${if (retiradaCustoCentavos == 0) "grátis" else "por ${dinheiro(retiradaCustoCentavos)}"} em $retiradaCidade" +
                (if (retiradaComo.isNotBlank()) ", com $retiradaComo" else "") + "."
            else "Não, a $loja trabalha só com envio."
            ),
        if (embalagemDiscreta) Triple("discreto", "A embalagem da $loja é discreta?", "Sim, a embalagem é neutra e sem identificação do conteúdo ou da loja.") else null,
        Triple("troca", "Posso trocar ou devolver um produto da $loja?", "Sim: em até $arrependimentoDias dias do recebimento você pode desistir da compra, e produto com defeito tem garantia $garantia. Fale com a equipe para combinar."),
        horarioAtendimento?.let { Triple("horario", "Qual o horário de atendimento da $loja?", "O atendimento é ${canais.joinToString(", ")}, $it.") },
        idadeMinima?.let { Triple("idade", "Menor de idade pode comprar na $loja?", "Não, a venda é só para maiores de $it anos.") },
    )

    // ------------------------------------------------------------------ JSON (preferências e site)
    fun json(ramo: String = Perfil.ramo): JSONObject = JSONObject()
        .put("versao", 1)
        .put("pagamento", JSONObject().put("pix_desconto_pct", pixDescontoPct).put("pix_em_promocao", pixValeEmPromocao)
            .put("cartao_parcelas_max", cartaoParcelasMax).put("parcelas_sem_juros", parcelasSemJuros))
        .put("frete", JSONObject().put("metodos", JSONArray(freteMetodos)).put("calculo", freteCalculo)
            .put("gratis_acima_de_centavos", freteGratisAcimaDe ?: JSONObject.NULL).put("prazo_postagem_dias", prazoPostagemDias ?: JSONObject.NULL))
        .put("retirada", JSONObject().put("disponivel", retiradaDisponivel).put("cidade", retiradaCidade).put("como", retiradaComo)
            .put("custo_centavos", retiradaCustoCentavos))
        .put("envio", JSONObject().put("embalagem_discreta", embalagemDiscreta).put("abrangencia", abrangencia))
        .put("trocas", JSONObject().put("arrependimento_dias", arrependimentoDias).put("garantia", garantia))
        .put("atendimento", JSONObject().put("canais", JSONArray(canais)).put("horario", horarioAtendimento ?: JSONObject.NULL))
        .put("restricoes", JSONObject().put("idade_minima", idadeMinima ?: JSONObject.NULL))
        .put("ramo", ramo)
        .put("observacoes", observacoes)
        .put("texto", texto())

    companion object {
        private val BR = Locale("pt", "BR")

        /** Para quem instala do zero: nada prometido até o dono preencher (sem Pix, parcelas, frete ou retirada). */
        val VAZIA = RegrasDeVenda(
            pixDescontoPct = 0, cartaoParcelasMax = 1, parcelasSemJuros = 1, freteMetodos = emptyList(), freteCalculo = "",
            retiradaDisponivel = false, retiradaCidade = "", retiradaComo = "", embalagemDiscreta = false, abrangencia = "",
            canais = emptyList(), idadeMinima = null,
        )

        fun dinheiro(centavos: Int): String = "R$ " + String.format(BR, "%,.2f", centavos / 100.0)

        private fun inteiroOuNulo(o: JSONObject?, campo: String): Int? = if (o == null || !o.has(campo) || o.isNull(campo)) null else o.optInt(campo)

        private fun lista(a: JSONArray?, padrao: List<String>): List<String> =
            a?.let { (0 until it.length()).map { i -> it.optString(i).trim() }.filter { s -> s.isNotEmpty() } } ?: padrao

        /** Lê o JSON salvo (campos que faltarem ficam com o padrão). */
        fun ler(texto: String?): RegrasDeVenda {
            val o = try {
                JSONObject(texto ?: return RegrasDeVenda())
            } catch (e: Exception) {
                return RegrasDeVenda()
            }
            val p = RegrasDeVenda()
            val pag = o.optJSONObject("pagamento")
            val fr = o.optJSONObject("frete")
            val re = o.optJSONObject("retirada")
            val en = o.optJSONObject("envio")
            val tr = o.optJSONObject("trocas")
            val at = o.optJSONObject("atendimento")
            val rs = o.optJSONObject("restricoes")
            return RegrasDeVenda(
                pixDescontoPct = pag?.optInt("pix_desconto_pct", p.pixDescontoPct)?.coerceIn(0, 50) ?: p.pixDescontoPct,
                pixValeEmPromocao = pag?.optBoolean("pix_em_promocao", p.pixValeEmPromocao) ?: p.pixValeEmPromocao,
                cartaoParcelasMax = pag?.optInt("cartao_parcelas_max", p.cartaoParcelasMax)?.coerceIn(1, 24) ?: p.cartaoParcelasMax,
                parcelasSemJuros = pag?.optInt("parcelas_sem_juros", p.parcelasSemJuros)?.coerceIn(1, 24) ?: p.parcelasSemJuros,
                freteMetodos = lista(fr?.optJSONArray("metodos"), p.freteMetodos),
                freteCalculo = fr?.optString("calculo", p.freteCalculo) ?: p.freteCalculo,
                freteGratisAcimaDe = if (fr == null) p.freteGratisAcimaDe else inteiroOuNulo(fr, "gratis_acima_de_centavos"),
                prazoPostagemDias = if (fr == null) p.prazoPostagemDias else inteiroOuNulo(fr, "prazo_postagem_dias"),
                retiradaDisponivel = re?.optBoolean("disponivel", p.retiradaDisponivel) ?: p.retiradaDisponivel,
                retiradaCidade = re?.optString("cidade", p.retiradaCidade) ?: p.retiradaCidade,
                retiradaComo = re?.optString("como", p.retiradaComo) ?: p.retiradaComo,
                retiradaCustoCentavos = re?.optInt("custo_centavos", p.retiradaCustoCentavos) ?: p.retiradaCustoCentavos,
                embalagemDiscreta = en?.optBoolean("embalagem_discreta", p.embalagemDiscreta) ?: p.embalagemDiscreta,
                abrangencia = en?.optString("abrangencia", p.abrangencia) ?: p.abrangencia,
                arrependimentoDias = tr?.optInt("arrependimento_dias", p.arrependimentoDias)?.coerceAtLeast(7) ?: p.arrependimentoDias,
                garantia = tr?.optString("garantia", p.garantia) ?: p.garantia,
                canais = lista(at?.optJSONArray("canais"), p.canais),
                horarioAtendimento = if (at == null) p.horarioAtendimento else at.optString("horario").takeIf { !at.isNull("horario") && it.isNotBlank() },
                idadeMinima = if (rs == null) p.idadeMinima else inteiroOuNulo(rs, "idade_minima"),
                observacoes = o.optString("observacoes"),
            )
        }

        /** "PAC, SEDEX e motoboy" → [PAC, SEDEX, motoboy]. */
        fun separar(t: String): List<String> = t.split(Regex("\\s*(?:,|;|/|\\be\\b|\\bou\\b)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

        /** "199", "199,90", "R$ 199" → centavos; vazio → null. */
        fun centavosDe(t: String): Int? {
            val limpo = t.replace("R$", "").replace(" ", "").trim()
            val numero = if (limpo.contains(",")) limpo.replace(".", "").replace(",", ".") else limpo
            return numero.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
        }
    }
}
