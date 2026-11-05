package com.tonimarques.sextafeira

import android.content.Context

/**
 * Quem usa a Vênus: o dono, a loja e a cidade. Fica nos ajustes ("Perfil do dono e da loja") para a mesma
 * Vênus servir a outros lojistas; os textos das IAs e os avisos usam estes campos no lugar de nomes fixos.
 * Os valores padrão são os da primeira loja (assim nada muda para quem já usa).
 */
object Perfil {

    private const val PREFS = "perfil"

    @Volatile var dono: String = "Toni"
        private set
    @Volatile var loja: String = "Desejo de Intimidade"
        private set
    @Volatile var cidade: String = "Porto Alegre"
        private set
    @Volatile var uf: String = "RS"
        private set
    /** Endereço do site da loja (o da Desejo de Intimidade é no Floot). */
    @Volatile var site: String = "https://desejo-de-intimidade.floot.app"
        private set
    /** O ramo da loja, como aparece nos textos das IAs. */
    @Volatile var ramo: String = "moda íntima e sex shop"
        private set
    /** Pagamento, frete, retirada, envio, trocas, atendimento e restrições, em campos (ver RegrasDeVenda). */
    @Volatile var regras: RegrasDeVenda = RegrasDeVenda()
        private set
    /** Desconto no Pix, em %, usado nos preços que a Vênus passa para as clientes. */
    val pixPorcento: Int get() = regras.pixDescontoPct
    /** As regras em texto, para as IAs: o que a Vênus pode prometer às clientes. */
    val regrasDeVenda: String get() = regras.texto()

    /** Uma frase sobre o dono (rotina, trabalho), usada no estudo de saberes. */
    @Volatile var sobre: String = "mora em Porto Alegre e trabalha num hotel"
        private set

    fun carregar(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("dono", null)?.takeIf { it.isNotBlank() }?.let { dono = it }
        p.getString("loja", null)?.takeIf { it.isNotBlank() }?.let { loja = it }
        p.getString("cidade", null)?.takeIf { it.isNotBlank() }?.let { cidade = it }
        p.getString("uf", null)?.takeIf { it.isNotBlank() }?.let { uf = it }
        p.getString("sobre", null)?.let { sobre = it }
        p.getString("site", null)?.takeIf { it.isNotBlank() }?.let { site = normalizarSite(it) }
        p.getString("ramo", null)?.takeIf { it.isNotBlank() }?.let { ramo = it }
        regras = RegrasDeVenda.ler(p.getString("regras_json", null)).let { r ->
            if (p.contains("regras_json")) r
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
        return if (s.startsWith("http://") || s.startsWith("https://")) s.replace("http://", "https://") else "https://$s"
    }

    /** Dados da loja (site, ramo e regras de venda em campos). Leva as regras para a memória e para o site. */
    fun salvarLoja(ctx: Context, site: String, ramo: String, novas: RegrasDeVenda) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("site", normalizarSite(site)).putString("ramo", ramo.trim())
            .putString("regras_json", novas.json(ramo.trim()).toString())
            .remove("regras").remove("pix")
            .apply()
        carregar(ctx)
        Consciencia.esquecerRetrato()
        Diario.anotar(ctx, "perfil", "Regras de venda atualizadas: $site, $ramo, Pix $pixPorcento%, ${regras.cartaoParcelasMax}x no cartão", "tela")
    }

    /** "Porto Alegre, RS" ou "Porto Alegre RS" → cidade e UF. */
    fun separarCidade(t: String): Pair<String, String> {
        val m = Regex("^(.*?)[,\\s/-]+([A-Za-z]{2})$").find(t.trim())
        return if (m != null) m.groupValues[1].trim() to m.groupValues[2].uppercase() else t.trim() to ""
    }

    fun salvar(ctx: Context, dono: String, loja: String, cidadeComUf: String, sobre: String) {
        val (c, u) = separarCidade(cidadeComUf)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dono", dono.trim()).putString("loja", loja.trim())
            .putString("cidade", c).putString("uf", u).putString("sobre", sobre.trim())
            .apply()
        carregar(ctx)
        Consciencia.esquecerRetrato()
        Diario.anotar(ctx, "perfil", "Perfil atualizado: ${this.dono}, ${this.loja}, ${this.cidade}${if (this.uf.isNotBlank()) "/${this.uf}" else ""}", "tela")
    }

    fun resumo(): String = "$dono · $loja · $cidade${if (uf.isNotBlank()) "/$uf" else ""}"
}
