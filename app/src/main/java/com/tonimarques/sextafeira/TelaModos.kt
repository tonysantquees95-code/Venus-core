package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.MainActivity.Companion.MENU_FUNDO
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_VIGILIA
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_FUNDO
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_VIGILIA

/** Como usar a Vênus: conversa direta, orbe por voz, bolha flutuante ou modo silencioso (só automações e alertas). */
internal object Modos {
    /** O que cada modo liga ou desliga (pura, testada): (escuta contínua com app aberto, escuta com app fechado/bolha). */
    fun efeito(modo: String): Pair<Boolean, Boolean> = when (modo) {
        "orbe" -> true to false
        "bolha" -> false to true
        else -> false to false // conversacional e silencioso: nada ouvindo
    }

    fun descricao(modo: String) = when (modo) {
        "conversacional" -> "Chat tradicional: você escreve e a Vênus responde por texto."
        "orbe" -> "Chame pelo nome ou toque no orbe. Pode ficar ouvindo o tempo todo ou só quando chamada."
        "bolha" -> "Uma bolinha flutuante sobre os outros apps, sem tomar a tela (precisa de \"Exibir sobre outros apps\")."
        else -> "Só automações da loja, webhooks e alertas. O app não abre o chat: ao tocar no ícone você vai direto para a Central da loja."
    }
}

internal fun MainActivity.abrirModos() {
    val itens = VetorialId.MODOS.map { m ->
        TelaDeAjustes.Ajuste(VetorialId.ROTULOS_MODO.getValue(m), { Modos.descricao(m) }, null, { VetorialId.modo(this) == m }) { definirModo(m) }
    }
    TelaDeAjustes(this, versaoDoApp(), listOf(TelaDeAjustes.Secao("Escolha um modo", itens)), "COMO USAR A ${Marca.nomeMaiusculo}", compacto = true).show()
}

internal fun MainActivity.definirModo(modo: String) {
    prefs.edit().putString(VetorialId.PREF_MODO, modo).apply()
    val (escutaAberta, escutaFundo) = Modos.efeito(modo)
    // desliga o que o modo novo não usa
    if (!escutaAberta && vigiliaLigada()) { prefs.edit().putBoolean(PREF_VIGILIA, false).apply(); pararVigilia() }
    if (!escutaFundo && prefs.getBoolean(PREF_FUNDO, false)) tratarMenu(MENU_FUNDO)
    // liga o que ele usa (o aviso do microfone e os pedidos de permissão ficam nos caminhos que já existiam)
    if (escutaAberta && !vigiliaLigada()) tratarMenu(MENU_VIGILIA)
    if (escutaFundo && !prefs.getBoolean(PREF_FUNDO, false)) tratarMenu(MENU_FUNDO)
    Diario.anotar(this, "Modo de uso", "Modo \"${VetorialId.ROTULOS_MODO[modo]}\" escolhido.", "tela", true)
    mudarEstadoOcioso()
    ajustes?.atualizar()
    aviso("Modo: ${VetorialId.ROTULOS_MODO[modo]}.")
    BatimentoId.agora(this)
}
