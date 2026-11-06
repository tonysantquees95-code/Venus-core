package com.tonimarques.sextafeira

/**
 * O servidor da Vênus no Cloudflare (pasta servidor/ do repositório): canal de atualização, canal de avisos e
 * avisos de pagamento da cobrança por link. Enquanto o endereço estiver vazio, a Vênus usa o site da loja e o ntfy.
 */
object Servidor {
    /** Preenchido depois da primeira publicação (o endereço *.workers.dev aparece no resumo do workflow). */
    const val ENDERECO = "https://venus-servidor.tonysantquees95.workers.dev"

    fun configurado(): Boolean = ENDERECO.startsWith("https://")
}
