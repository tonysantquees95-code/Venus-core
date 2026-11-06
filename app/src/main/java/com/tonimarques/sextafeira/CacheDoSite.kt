package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Cópia local do que a Vênus lê do site (produtos e pedidos), para usar menos o site:
 * - dentro da validade, responde com a cópia, sem ir ao site;
 * - se o site estiver fora do ar ou pausado, responde com a última cópia (até 2 dias), em vez de falhar;
 * - qualquer mudança feita pela Vênus no site (preço, estoque, pedido) invalida a cópia.
 * Fica no armazenamento do app (sobrevive a fechar o app).
 */
object CacheDoSite {

    private const val VELHA_MAXIMO = 2 * 86_400_000L
    private val memoria = mutableMapOf<String, Pair<Long, String>>()
    @Volatile private var invalidadoEm = 0L

    /** Quando a última resposta veio de uma cópia velha (site fora do ar). 0 = nunca. */
    @Volatile var copiaVelhaEm = 0L
        private set

    private fun arquivo(ctx: Context, nome: String) = File(ctx.filesDir, "site_$nome.json")

    private fun carregar(ctx: Context, nome: String): Pair<Long, String>? = synchronized(memoria) {
        memoria[nome] ?: try {
            val o = JSONObject(arquivo(ctx, nome).takeIf { it.exists() }?.readText() ?: return null)
            (o.optLong("q") to o.optString("t")).also { memoria[nome] = it }
        } catch (e: Exception) {
            null
        }
    }

    /** A cópia, se foi guardada há menos de [validade] e depois da última mudança. */
    fun fresca(ctx: Context, nome: String, validade: Long): String? {
        val (quando, texto) = carregar(ctx, nome) ?: return null
        val agora = System.currentTimeMillis()
        return texto.takeIf { agora - quando < validade && quando > invalidadoEm && texto.isNotBlank() }
    }

    /** A última cópia, mesmo vencida (até 2 dias), para quando o site não responde. */
    fun velha(ctx: Context, nome: String): String? {
        val (quando, texto) = carregar(ctx, nome) ?: return null
        if (System.currentTimeMillis() - quando > VELHA_MAXIMO || texto.isBlank()) return null
        copiaVelhaEm = System.currentTimeMillis()
        return texto
    }

    fun guardar(ctx: Context, nome: String, texto: String) {
        val agora = System.currentTimeMillis()
        synchronized(memoria) { memoria[nome] = agora to texto }
        try {
            arquivo(ctx, nome).writeText(JSONObject().put("q", agora).put("t", texto).toString())
        } catch (e: Exception) {
        }
    }

    /** Depois de mudar algo no site: a próxima leitura busca de novo (a cópia velha continua valendo se o site cair). */
    fun invalidar() {
        invalidadoEm = System.currentTimeMillis()
    }
}
