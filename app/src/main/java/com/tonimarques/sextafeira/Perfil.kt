package com.tonimarques.sextafeira

import android.content.Context

/**
 * Quem usa a Vênus: o dono, a loja e a cidade. Fica nos ajustes ("Perfil do dono e da loja") para a mesma
 * Vênus servir a outros lojistas; os textos das IAs e os avisos usam estes campos no lugar de nomes fixos.
 * Os valores padrão são os da primeira loja (assim nada muda para quem já usa).
 */
object Perfil {

    /** O perfil é um arquivo por loja: "perfil" na principal e "perfil_<id>" nas extras (ver Lojas). */
    private fun arquivo(ctx: Context) = Lojas.arquivoDoPerfil(ctx)

    @Volatile var dono: String = "Toni"
        private set
    @Volatile var loja: String = "Desejo de Intimidade"
        private set
    @Volatile var cidade: String = "Porto Alegre"
        private set
    @Volatile var uf: String = "RS"
        private set
    /** Endereço do site da loja (o da Desejo de Intimidade é no Floot). */
    @Volatile var site: String = "https://desejo-de-intimidade.tonysantquees95.workers.dev"
        private set
    /** O ramo da loja, como aparece nos textos das IAs. */
    @Volatile var ramo: String = "moda íntima e sex shop"
        private set
    /** Pagamento, frete, retirada, envio, trocas, atendimento e restrições, em campos (ver RegrasDeVenda). */
    @Volatile var regras: RegrasDeVenda = RegrasDeVenda()
        private set
    /** Desconto no Pix, em %, usado nos preços que a Vênus passa para as clientes. */
    val pixPorcento: Int get() = regras.descontoDoPix
    /** false = loja nova que ainda não preencheu as regras: a Vênus não promete nada sobre venda. */
    @Volatile var regrasConfiguradas: Boolean = true
        private set
    /** As regras em texto, para as IAs: o que a Vênus pode prometer às clientes. */
    val regrasDeVenda: String get() = if (regrasConfiguradas) regras.texto() else NAO_CONFIGURADAS

    const val NAO_CONFIGURADAS = "a loja ainda não preencheu as regras de venda. Não prometa formas de pagamento, descontos, parcelas, " +
        "frete, prazos, retirada nem trocas; diga com gentileza que a equipe confirma esses detalhes."

    /**
     * O bloco que entra no prompt de sistema das IAs (Groq, Cloudflare, Gemini): quem é a loja, as regras de venda
     * e o que a loja não informou. Montado na hora de cada pedido, a partir do que o dono preencheu nos ajustes.
     */
    fun injecao(): String = buildString {
        append("Perfil da loja (preenchido pelo dono nos ajustes; sobre a loja, use só isto):\n")
        append("- Loja: $loja" + (if (ramo.isNotBlank()) " ($ramo)" else "") + (if (cidade.isNotBlank()) ", em $cidade" + (if (uf.isNotBlank()) "/$uf" else "") else "") +
            (if (site.isNotBlank()) ". Site: $site" else "") + ".\n")
        append("- Regras de venda: $regrasDeVenda\n")
        val faltam = if (regrasConfiguradas) regras.faltando() else emptyList()
        if (faltam.isNotEmpty()) append("- Não informado pela loja: ${faltam.joinToString(", ")}. Se perguntarem, diga que a equipe confirma.\n")
        append("- Nunca prometa desconto, frete, prazo ou condição que não esteja aqui.")
    }

    /** O site da primeira loja: é ele que mantém o que é só dela (stories já ligados, estudos pessoais). */
    const val SITE_DA_PRIMEIRA_LOJA = "https://desejo-de-intimidade.tonysantquees95.workers.dev"
    val primeiraLoja: Boolean get() = site == SITE_DA_PRIMEIRA_LOJA

    /** Só lojas deste ramo recebem textos de bem-estar íntimo, lingerie e produtos adultos. */
    val ramoIntimo: Boolean get() = ehRamoIntimo(ramo)

    fun ehRamoIntimo(ramo: String): Boolean = RAMO_INTIMO.containsMatchIn(ComandosLocais.normalizar(ramo))

    private val RAMO_INTIMO = Regex("sex[ -]?shop|moda intima|lingerie|erotic|bem[ -]estar intimo|produtos? (intimos?|adultos?)|artigos? adultos?")

    /** A loja marcou "embalagem discreta" nas regras de venda: só então a Vênus fala em discrição na entrega. */
    val entregaDiscreta: Boolean get() = regrasConfiguradas && regras.embalagemDiscreta

    /** "loja online de moda íntima e sex shop", "loja de roupas" (sem site) ou só "loja", conforme o que foi preenchido. */
    fun descricaoDaLoja(): String = (if (site.isNotBlank()) "loja online" else "loja") + (if (ramo.isNotBlank()) " de $ramo" else "")

    /** Uma frase sobre o dono (rotina, trabalho), usada no estudo de saberes. */
    @Volatile var sobre: String = "mora em Porto Alegre e trabalha num hotel"
        private set

    fun carregar(ctx: Context) {
        val p = ctx.getSharedPreferences(arquivo(ctx), Context.MODE_PRIVATE)
        // ao trocar de loja, o que não estiver salvo nesta volta ao padrão (não herda o da loja anterior)
        dono = "Toni"; loja = "Desejo de Intimidade"; cidade = "Porto Alegre"; uf = "RS"
        site = "https://desejo-de-intimidade.tonysantquees95.workers.dev"; ramo = "moda íntima e sex shop"
        sobre = "mora em Porto Alegre e trabalha num hotel"; regras = RegrasDeVenda()
        // quem já usava a Vênus (memória ou chave salva) fica com os dados da primeira loja; instalação nova começa neutra
        val origem = p.getString("origem", null) ?: run {
            val antigo = !Lojas.extraAberta(ctx) && (ctx.getDatabasePath("sexta.db").exists() ||
                ctx.getSharedPreferences("config", Context.MODE_PRIVATE).contains("chave") ||
                ctx.getSharedPreferences("loja", Context.MODE_PRIVATE).contains("email"))
            (if (antigo) "herdado" else "novo").also { p.edit().putString("origem", it).apply() }
        }
        if (origem == "novo") {
            dono = "chefe"; loja = "sua loja"; cidade = ""; uf = ""; site = ""; ramo = ""; sobre = ""
            regras = RegrasDeVenda.VAZIA
        }
        regrasConfiguradas = origem == "herdado" || p.contains("regras_json")
        p.getString("dono", null)?.takeIf { it.isNotBlank() }?.let { dono = it }
        p.getString("loja", null)?.takeIf { it.isNotBlank() }?.let { loja = it }
        p.getString("cidade", null)?.takeIf { it.isNotBlank() }?.let { cidade = it }
        p.getString("uf", null)?.takeIf { it.isNotBlank() }?.let { uf = it }
        p.getString("sobre", null)?.let { sobre = it }
        p.getString("site", null)?.takeIf { it.isNotBlank() }?.let { site = normalizarSite(it) }
        p.getString("ramo", null)?.takeIf { it.isNotBlank() }?.let { ramo = it }
        regras = RegrasDeVenda.ler(p.getString("regras_json", null)).let { r ->
            if (p.contains("regras_json")) r
            else if (origem == "novo") RegrasDeVenda.VAZIA
            // versão 1.133/1.134 guardava texto livre e o Pix à parte: vira observações e o campo do Pix
            else r.copy(
                pixDescontoPct = if (p.contains("pix")) p.getInt("pix", 5).coerceIn(0, 50) else r.pixDescontoPct,
                observacoes = p.getString("regras", null)?.takeIf { it.isNotBlank() && !it.startsWith("entrega discreta para todo o Brasil pelos Correios") }.orEmpty(),
            )
        }
    }

    /** "minhaloja.com.br/" → "https://minhaloja.com.br". */
    fun normalizarSite(t: String): String {
        val s = t.trim().removeSuffix("/")
        if (s.isEmpty()) return "" // loja sem site
        val url = if (s.startsWith("http://") || s.startsWith("https://")) s.replace("http://", "https://") else "https://$s"
        // a loja saiu do Floot para o Cloudflare: o endereço antigo salvo no celular vira o novo
        return url.replace("desejo-de-intimidade.floot.app", "desejo-de-intimidade.tonysantquees95.workers.dev")
    }

    /** Dados da loja (site, ramo e regras de venda em campos). Leva as regras para a memória e para o site. */
    fun salvarLoja(ctx: Context, site: String, ramo: String, novas: RegrasDeVenda) {
        ctx.getSharedPreferences(arquivo(ctx), Context.MODE_PRIVATE).edit()
            .putString("site", normalizarSite(site)).putString("ramo", ramo.trim())
            .putString("regras_json", novas.json(ramo.trim()).toString())
            .remove("regras").remove("pix")
            .apply()
        carregar(ctx)
        Consciencia.esquecerRetrato()
        Diario.anotar(ctx, "perfil", "Regras de venda atualizadas: $site, $ramo, " + (if (regras.aceitaPix) "Pix $pixPorcento%" else "sem Pix") + ", " + (if (regras.aceitaCartao) "${regras.cartaoParcelasMax}x no cartão" else "sem cartão"), "tela")
    }

    /** "Porto Alegre, RS" ou "Porto Alegre RS" → cidade e UF. */
    fun separarCidade(t: String): Pair<String, String> {
        val m = Regex("^(.*?)[,\\s/-]+([A-Za-z]{2})$").find(t.trim())
        return if (m != null) m.groupValues[1].trim() to m.groupValues[2].uppercase() else t.trim() to ""
    }

    fun salvar(ctx: Context, dono: String, loja: String, cidadeComUf: String, sobre: String) {
        val (c, u) = separarCidade(cidadeComUf)
        ctx.getSharedPreferences(arquivo(ctx), Context.MODE_PRIVATE).edit()
            .putString("dono", dono.trim()).putString("loja", loja.trim())
            .putString("cidade", c).putString("uf", u).putString("sobre", sobre.trim())
            .apply()
        carregar(ctx)
        Consciencia.esquecerRetrato()
        Diario.anotar(ctx, "perfil", "Perfil atualizado: ${this.dono}, ${this.loja}, ${this.cidade}${if (this.uf.isNotBlank()) "/${this.uf}" else ""}", "tela")
    }

    /** O cadastro da empresa que viaja com o Vetorial ID (celular, computador, outro celular). Sem chaves de IA nem senhas. */
    fun paraSincronia(ctx: Context): org.json.JSONObject? {
        val p = ctx.getSharedPreferences(arquivo(ctx), Context.MODE_PRIVATE)
        // instalação nova que ainda não preencheu nada não manda um cadastro vazio por cima do que já existe na conta
        if (p.getString("origem", "novo") == "novo" && !p.contains("loja") && !p.contains("regras_json")) return null
        return org.json.JSONObject().put("dono", dono).put("loja", loja).put("cidade", cidade).put("uf", uf).put("sobre", sobre)
            .put("site", site).put("ramo", ramo).put("regras", regras.json(ramo)).put("regrasConfiguradas", regrasConfiguradas)
    }

    fun aplicarSincronia(ctx: Context, v: org.json.JSONObject) {
        val e = ctx.getSharedPreferences(arquivo(ctx), Context.MODE_PRIVATE).edit()
        for (campo in listOf("dono", "loja", "cidade", "uf", "sobre", "ramo")) if (v.has(campo)) e.putString(campo, v.optString(campo))
        if (v.has("site")) e.putString("site", normalizarSite(v.optString("site")))
        v.optJSONObject("regras")?.let { e.putString("regras_json", it.toString()).remove("regras").remove("pix") }
        e.apply()
        carregar(ctx)
        Consciencia.esquecerRetrato()
    }

    fun resumo(): String = "$dono · $loja · $cidade${if (uf.isNotBlank()) "/$uf" else ""}"
}
