package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONObject

/**
 * A conexão da loja com o site (plataforma, endereço, chave de acesso, login de administrador e o canal de avisos de pedido)
 * acompanha a conta Vetorial: quem reinstala o app ou entra em outro aparelho não precisa configurar tudo de novo.
 * Um aparelho que já tem a conexão não é sobrescrito; e um aparelho sem nada configurado não manda um cadastro vazio por cima da conta.
 */
internal object SincroniaConexoes {
    const val CHAVE = "conexoes"

    /** O que está configurado neste aparelho, ou null se não há nada. */
    fun paraSincronia(ctx: Context): JSONObject? {
        val c = Plataformas.config(ctx)
        val doc = JSONObject()
        if (c.tipo != "floot" && (c.chave.isNotBlank() || c.tipo == "interno")) {
            doc.put("plataforma", JSONObject().put("tipo", c.tipo).put("endereco", c.endereco).put("chave", c.chave).put("segredo", c.segredo).put("loja_id", c.lojaId))
        }
        Loja(ctx).loginParaSincronia()?.let { (email, senha) -> doc.put("login", JSONObject().put("email", email).put("senha", senha)) }
        PedidosDoSite.canal(ctx)?.let { doc.put("canal", it) }
        return if (doc.length() == 0) null else doc
    }

    /** Recebe da conta: só preenche o que este aparelho ainda não tem. */
    fun aplicar(ctx: Context, v: JSONObject) {
        v.optJSONObject("plataforma")?.let { p ->
            val atual = Plataformas.config(ctx)
            val semConexao = atual.chave.isBlank() && Plataformas.tipo(ctx).let { it == "floot" }
            if (semConexao) Plataformas.salvar(ctx, Plataformas.Config(p.optString("tipo", "floot"), p.optString("endereco"), p.optString("chave"), p.optString("segredo"), p.optString("loja_id")))
        }
        v.optJSONObject("login")?.let { Loja(ctx).guardarLoginDaConta(it.optString("email"), it.optString("senha")) }
        v.optString("canal").takeIf { it.isNotBlank() && PedidosDoSite.canal(ctx) == null }?.let { PedidosDoSite.guardarCanal(ctx, it) }
    }
}
