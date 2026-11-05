package com.tonimarques.venusrobo

import android.content.Context
import org.json.JSONObject

/**
 * Respostas prontas para o Instagram, escritas pela Vênus da loja (que conhece produtos e regras do site).
 * O robô faz à Vênus da loja as perguntas que as clientes mais mandam e guarda cada resposta, para você colar
 * nas ferramentas oficiais do Instagram: "Perguntas frequentes" (até 4) e "Respostas salvas" (com atalho).
 * Assim o Instagram responde pelo jeito oficial e o robô não precisa ler as notificações do Direct.
 */
object Respostas {

    /** [faq] = entra nas 4 perguntas frequentes do Direct (a pergunta aparece para a cliente tocar). */
    data class Modelo(val id: String, val atalho: String, val pergunta: String, val faq: Boolean)

    /** [conferir] = a Vênus da loja passaria essa conversa para vocês; leia antes de usar. */
    data class Pronta(val modelo: Modelo, val texto: String, val editada: Boolean, val conferir: Boolean)

    data class Gerada(val texto: String, val conferir: Boolean)

    val MODELOS = listOf(
        Modelo("discreto", "discreto", "A entrega é discreta?", true),
        Modelo("frete", "frete", "Qual o prazo e o valor do frete?", true),
        Modelo("pagamento", "pagamento", "Quais as formas de pagamento?", true),
        Modelo("iniciante", "iniciante", "O que vocês indicam para quem está começando?", true),
        Modelo("oi", "oi", "Oi, boa tarde! Queria tirar uma dúvida", false),
        Modelo("troca", "troca", "Posso trocar ou devolver?", false),
        Modelo("retirada", "retirada", "Dá para retirar pessoalmente? Onde vocês ficam?", false),
        Modelo("casal", "casal", "O que é bom para usar a dois?", false),
        Modelo("original", "original", "Os produtos são originais e seguros?", false),
        Modelo("pedido", "pedido", "Como faço o pedido pelo site?", false),
        Modelo("rastreio", "rastreio", "Como acompanho meu pedido?", false),
        Modelo("horario", "horario", "Qual o horário de atendimento?", false),
    )

    /** O Instagram corta respostas muito longas; deixa uma margem. */
    const val MAXIMO = 900

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("respostas", Context.MODE_PRIVATE)

    fun lista(ctx: Context): List<Pronta> {
        val salvas = lerSalvas(prefs(ctx).getString("lista", "{}").orEmpty())
        return MODELOS.map { m -> salvas[m.id]?.let { Pronta(m, it.texto, it.editada, it.conferir) } ?: Pronta(m, "", false, false) }
    }

    fun quando(ctx: Context): Long = prefs(ctx).getLong("quando", 0)

    /** Guarda o texto de uma resposta ([editada] = você mexeu; "Gerar todas" não sobrescreve). */
    fun guardar(ctx: Context, id: String, texto: String, editada: Boolean, conferir: Boolean = false) {
        val p = prefs(ctx)
        val o = try {
            JSONObject(p.getString("lista", "{}").orEmpty())
        } catch (e: Exception) {
            JSONObject()
        }
        if (texto.isBlank()) o.remove(id) else o.put(id, JSONObject().put("texto", texto).put("editada", editada).put("conferir", conferir && !editada))
        p.edit().putString("lista", o.toString()).putLong("quando", System.currentTimeMillis()).apply()
    }

    /**
     * Bloqueante: pergunta à Vênus da loja e devolve o texto pronto (null = ela não mandou texto).
     * Cada pergunta vai com um remetente próprio para não misturar as conversas.
     */
    fun gerar(ctx: Context, m: Modelo): Gerada? {
        val r = Robo.perguntar(ctx, Robo.INSTAGRAM, "Respostas prontas (${m.id})", m.pergunta, teste = true)
        return juntar(r.mensagens)?.let { Gerada(it, r.humano) }
    }

    /** As mensagens da Vênus viram um texto só, sem sobras de formatação; null se vier vazio. */
    fun juntar(mensagens: List<String>): String? {
        val t = mensagens.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .trim()
        if (t.isEmpty()) return null
        return if (t.length <= MAXIMO) t else t.take(MAXIMO).substringBeforeLast(' ').trimEnd(',', ';', ' ') + "…"
    }

    data class Salva(val texto: String, val editada: Boolean, val conferir: Boolean)

    fun lerSalvas(json: String): Map<String, Salva> = try {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { k ->
            o.optJSONObject(k)?.let { v ->
                v.optString("texto").takeIf { it.isNotBlank() }?.let { k to Salva(it, v.optBoolean("editada"), v.optBoolean("conferir")) }
            }
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /** Tudo num texto só (para mandar para você mesmo ou guardar). */
    fun tudoEmTexto(prontas: List<Pronta>): String {
        val faq = prontas.filter { it.modelo.faq && it.texto.isNotBlank() }
        val salvas = prontas.filter { it.texto.isNotBlank() }
        val partes = mutableListOf<String>()
        if (faq.isNotEmpty()) partes += "PERGUNTAS FREQUENTES (Direct)\n\n" + faq.joinToString("\n\n") { "Pergunta: ${it.modelo.pergunta}\nResposta: ${it.texto}" }
        if (salvas.isNotEmpty()) partes += "RESPOSTAS SALVAS (atalho → texto)\n\n" + salvas.joinToString("\n\n") { "${it.modelo.atalho} →\n${it.texto}" }
        return partes.joinToString("\n\n— — —\n\n")
    }
}
