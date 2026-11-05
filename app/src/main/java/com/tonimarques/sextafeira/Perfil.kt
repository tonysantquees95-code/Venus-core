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
    /** Desconto no Pix, em %, usado nos preços que a Vênus passa para as clientes. */
    @Volatile var pixPorcento: Int = 5
        private set
    /** Entrega, pagamento, retirada e embalagem: o que a Vênus pode prometer às clientes. */
    @Volatile var regrasDeVenda: String = "entrega discreta para todo o Brasil pelos Correios (SEDEX ou PAC), frete calculado pelo CEP no site; " +
        "pagamento por Pix com 5% de desconto (exceto produtos em promoção) ou cartão em até 3x; retirada grátis em ponto de retirada em Porto Alegre, " +
        "com dia e horário combinados pelo WhatsApp depois da compra; embalagem neutra e sem identificação; venda só para maiores de 18 anos."
        private set
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
        p.getString("regras", null)?.takeIf { it.isNotBlank() }?.let { regrasDeVenda = it }
        if (p.contains("pix")) pixPorcento = p.getInt("pix", 5).coerceIn(0, 50)
    }

    /** "minhaloja.com.br/" → "https://minhaloja.com.br". */
    fun normalizarSite(t: String): String {
        val s = t.trim().removeSuffix("/")
        return if (s.startsWith("http://") || s.startsWith("https://")) s.replace("http://", "https://") else "https://$s"
    }

    /** Dados da loja (site, ramo, Pix e regras de venda). */
    fun salvarLoja(ctx: Context, site: String, ramo: String, pix: Int, regras: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("site", normalizarSite(site)).putString("ramo", ramo.trim()).putInt("pix", pix.coerceIn(0, 50))
            .putString("regras", regras.trim())
            .apply()
        carregar(ctx)
        Consciencia.esquecerRetrato()
        Diario.anotar(ctx, "perfil", "Dados da loja atualizados: $site, $ramo, Pix $pixPorcento%", "tela")
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
