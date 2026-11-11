package com.tonimarques.sextafeira

/**
 * Isolamento de sessão: a Vênus sempre abre numa conversa nova e limpa. Ao reabrir (voltar de outro app),
 * começa outra conversa só se ficou fora por um tempo e nada está pendente; a antiga fica no histórico.
 */
object Sessao {
    /** Tempo fora do app a partir do qual reabrir começa uma conversa nova. */
    const val AUSENCIA_MINIMA_MS = 60_000L

    fun deveComecarNova(
        ausenteMs: Long,
        conversaTemMensagens: Boolean,
        ocupado: Boolean,
        esperandoVoltar: Boolean,
    ): Boolean = ausenteMs >= AUSENCIA_MINIMA_MS && conversaTemMensagens && !ocupado && !esperandoVoltar
}
