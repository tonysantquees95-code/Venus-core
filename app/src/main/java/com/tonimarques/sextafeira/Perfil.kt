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
