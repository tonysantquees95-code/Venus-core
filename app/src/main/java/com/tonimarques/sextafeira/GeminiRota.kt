package com.tonimarques.sextafeira

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * O caminho até o Gemini. Quem tem chave própria (o dono) fala direto com o Google; todo o resto fala com o
 * servidor da Vênus, com o token do Vetorial ID, e a chave do Google fica só lá. Assim PDF, áudio, foto,
 * memória e o resto funcionam sem o usuário criar chave nenhuma.
 */
object GeminiRota {
    /** Valor que fica no lugar da chave quando o Gemini vem do servidor (nunca é enviado a lugar nenhum). */
    const val SERVIDOR = "venus-servidor"
    private const val GOOGLE = "https://generativelanguage.googleapis.com/v1beta"

    /** A chave a usar: a própria, se houver; senão o servidor (se logado no Vetorial ID); senão vazio. */
    fun efetiva(ctx: Context?, propria: String): String = when {
        propria.isNotBlank() -> propria
        ctx != null && Servidor.configurado() && VetorialId.token(ctx).isNotBlank() -> SERVIDOR
        else -> ""
    }

    fun doServidor(chave: String) = chave == SERVIDOR

    /** Abre a conexão para [caminho] (ex.: "models/gemini-3.8-flash:generateContent") já com a autorização certa. */
    fun abrir(caminho: String, chave: String, ctx: Context? = SegundaIA.ctx): HttpURLConnection {
        val servidor = doServidor(chave)
        val url = if (servidor) "${Servidor.ENDERECO}/ia/gemini/$caminho" else "$GOOGLE/$caminho"
        val c = URL(url).openConnection() as HttpURLConnection
        if (servidor) c.setRequestProperty("Authorization", "Bearer " + (ctx?.let { VetorialId.token(it) }.orEmpty()))
        else c.setRequestProperty("x-goog-api-key", chave)
        return c
    }
}
