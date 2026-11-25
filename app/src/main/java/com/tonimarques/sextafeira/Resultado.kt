package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * "Sua loja hoje": quantos clientes a Vênus atendeu (robô do WhatsApp e do Instagram) e quanto a loja vendeu.
 * Só conta o que a Vênus de fato registrou: não diz quais vendas vieram das conversas, porque isso ela não sabe.
 * Guarda só um código de cada cliente (o mesmo hash das conversas), nunca nome, telefone ou texto. A lógica é pura para ser testada.
 */
object Resultado {
    private const val PREFS = "resultado_loja"
    private const val MANTER_DIAS = 35

    /** O que a Vênus fez num dia: clientes diferentes, respostas dadas e conversas passadas para a loja. */
    data class Dia(val clientes: Set<String> = emptySet(), val respostas: Int = 0, val passadas: Int = 0)

    fun chaveDoDia(agora: Calendar): String =
        "%04d-%02d-%02d".format(agora.get(Calendar.YEAR), agora.get(Calendar.MONTH) + 1, agora.get(Calendar.DAY_OF_MONTH))

    /** As chaves dos últimos [n] dias, do mais recente (hoje) para trás. */
    fun ultimosDias(agora: Calendar, n: Int): List<String> = (0 until n).map {
        chaveDoDia((agora.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -it) })
    }

    fun ontem(agora: Calendar): String = ultimosDias(agora, 2)[1]

    /** Soma mais uma resposta do robô no dia [dia]; [humano] = a conversa foi passada para a loja. */
    fun registrar(dias: Map<String, Dia>, dia: String, cliente: String, humano: Boolean): Map<String, Dia> {
        val atual = dias[dia] ?: Dia()
        return dias + (dia to Dia(atual.clientes + cliente, atual.respostas + 1, atual.passadas + if (humano) 1 else 0))
    }

    /** Guarda só os últimos [MANTER_DIAS] dias. */
    fun podar(dias: Map<String, Dia>, agora: Calendar): Map<String, Dia> {
        val validos = ultimosDias(agora, MANTER_DIAS).toSet()
        return dias.filterKeys { it in validos }
    }

    /** Junta vários dias: clientes diferentes no período (quem voltou conta uma vez só). */
    fun somar(dias: Map<String, Dia>, chaves: List<String>): Dia {
        val lista = chaves.mapNotNull { dias[it] }
        return Dia(lista.flatMap { it.clientes }.toSet(), lista.sumOf { it.respostas }, lista.sumOf { it.passadas })
    }

    // ------------------------------------------------------------------ vendas (da loja conectada)
    private val PAGOS = setOf("paid", "preparing", "shipped", "delivered")

    data class Vendas(val quantidade: Int, val centavos: Long)

    private fun inicioDoDia(agora: Calendar, voltar: Int): Long = (agora.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -voltar); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Vendas pagas dos últimos [dias] dias, contando hoje. Cancelado, devolvido e aguardando pagamento ficam de fora. */
    fun vendas(pedidos: List<Loja.Pedido>, agora: Calendar, dias: Int): Vendas {
        val ini = inicioDoDia(agora, dias - 1)
        val lista = pedidos.filter { it.status in PAGOS && (it.pago ?: it.criado) >= ini }
        return Vendas(lista.size, lista.sumOf { it.totalCentavos.toLong() })
    }

    // ------------------------------------------------------------------ textos
    private fun plural(n: Int, um: String, varios: String) = "$n ${if (n == 1) um else varios}"

    fun fraseDeAtendimento(d: Dia): String =
        if (d.respostas == 0) "A Vênus ainda não respondeu nenhum cliente."
        else "A Vênus atendeu ${plural(d.clientes.size, "cliente", "clientes")} (${plural(d.respostas, "resposta", "respostas")})" +
            if (d.passadas > 0) " e passou ${plural(d.passadas, "conversa", "conversas")} para você." else "."

    fun fraseDeVendas(v: Vendas): String =
        if (v.quantidade == 0) "Nenhuma venda paga."
        else "${plural(v.quantidade, "venda paga", "vendas pagas")}, ${Loja.dinheiro(v.centavos.toInt())}."

    /** A parte do aviso da manhã sobre o dia anterior; vazia se o robô não respondeu ninguém. */
    fun ontemParaOAviso(d: Dia): String =
        if (d.respostas == 0) "" else "Ontem " + fraseDeAtendimento(d).replaceFirstChar { it.lowercase() }

    /** Horários que a pessoa pode escolher para o aviso da manhã. */
    val HORAS_DA_MANHA = listOf(6, 7, 8, 9, 10)
    const val HORA_PADRAO = 8

    /** Hora válida (das 5h às 11h); qualquer outra volta ao padrão. */
    fun horaValida(h: Int): Int = if (h in 5..11) h else HORA_PADRAO

    /** Manda o aviso se está ligado, já passou da hora escolhida, ainda é manhã/dia (até 14h) e hoje ainda não mandou. */
    fun deveAvisarDeManha(ligado: Boolean, hora: Int, ultimoDia: String?, agora: Calendar): Boolean =
        ligado && ultimoDia != chaveDoDia(agora) && agora.get(Calendar.HOUR_OF_DAY) in horaValida(hora)..13

    // ------------------------------------------------------------------ guardar no celular
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ler(json: String?): Map<String, Dia> = try {
        val o = JSONObject(json ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val d = o.getJSONObject(k)
            val c = d.optJSONArray("c") ?: JSONArray()
            Dia((0 until c.length()).map { c.getString(it) }.toSet(), d.optInt("r"), d.optInt("h"))
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun gravar(dias: Map<String, Dia>): String = JSONObject().also { o ->
        dias.forEach { (k, d) -> o.put(k, JSONObject().put("c", JSONArray(d.clientes.toList())).put("r", d.respostas).put("h", d.passadas)) }
    }.toString()

    @Synchronized
    fun anotarResposta(c: Context, cliente: String, humano: Boolean) {
        try {
            val agora = Calendar.getInstance()
            val novo = podar(registrar(ler(prefs(c).getString("dias", null)), chaveDoDia(agora), cliente, humano), agora)
            prefs(c).edit().putString("dias", gravar(novo)).apply()
        } catch (e: Exception) {
            // contar é só um extra: nunca atrapalha a resposta ao cliente
        }
    }

    fun dias(c: Context): Map<String, Dia> = ler(prefs(c).getString("dias", null))

    // ------------------------------------------------------------------ aviso da manhã (ajustes)
    const val PREF_MANHA = "resumo_manha"
    const val PREF_MANHA_HORA = "resumo_manha_hora"
    const val PREF_MANHA_DIA = "resumo_manha_dia"

    fun manhaLigada(c: Context) = prefs(c).getBoolean(PREF_MANHA, true)
    fun definirManha(c: Context, ligar: Boolean) { prefs(c).edit().putBoolean(PREF_MANHA, ligar).apply() }
    fun horaDaManha(c: Context) = horaValida(prefs(c).getInt(PREF_MANHA_HORA, HORA_PADRAO))
    fun definirHoraDaManha(c: Context, h: Int) { prefs(c).edit().putInt(PREF_MANHA_HORA, horaValida(h)).apply() }
    fun ultimoAvisoDaManha(c: Context): String? = prefs(c).getString(PREF_MANHA_DIA, null)
    fun marcarAvisoDaManha(c: Context, agora: Calendar) { prefs(c).edit().putString(PREF_MANHA_DIA, chaveDoDia(agora)).apply() }
}
