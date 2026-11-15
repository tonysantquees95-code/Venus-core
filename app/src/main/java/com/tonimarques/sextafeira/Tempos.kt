package com.tonimarques.sextafeira

/**
 * Cronômetro das respostas: anota quanto cada etapa levou (memória, IA, primeira palavra, total) para a gente
 * ver onde está a lentidão. Fica só na memória do app; aparece no relatório do Diagnóstico.
 */
object Tempos {
    private class Pedido(val inicio: Long) {
        val marcas = LinkedHashMap<String, Long>()
        var quem: String? = null
    }

    private val recentes = ArrayDeque<String>()
    private var atual: Pedido? = null

    @Synchronized
    fun comecar() {
        atual = Pedido(System.currentTimeMillis())
    }

    /** Marca que [etapa] terminou agora (só a primeira vez em cada pedido). */
    @Synchronized
    fun marcar(etapa: String) {
        val p = atual ?: return
        if (etapa !in p.marcas) p.marcas[etapa] = System.currentTimeMillis() - p.inicio
    }

    /** Quem respondeu (provedor/modelo). */
    @Synchronized
    fun quem(nome: String) {
        atual?.quem = nome
    }

    @Synchronized
    fun terminar(pedido: String) {
        val p = atual ?: return
        atual = null
        val total = System.currentTimeMillis() - p.inicio
        recentes.addLast(linha(pedido, p, total))
        while (recentes.size > MAXIMO) recentes.removeFirst()
    }

    @Synchronized
    fun relatorio(): String =
        if (recentes.isEmpty()) "Tempos das respostas: ainda sem medidas (faça algumas perguntas e abra de novo)."
        else "Tempos das últimas respostas (ms desde o pedido):\n" + recentes.joinToString("\n")

    private fun linha(pedido: String, p: Pedido, total: Long): String {
        val pedidoCurto = pedido.replace(Regex("\\s+"), " ").take(28)
        val etapas = p.marcas.entries.joinToString(" · ") { "${it.key} ${it.value}" }
        return "“$pedidoCurto” → " + (if (etapas.isNotBlank()) "$etapas · " else "") + "total $total" + (p.quem?.let { " ($it)" } ?: "")
    }

    private const val MAXIMO = 12
}
