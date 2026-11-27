package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * O caderninho do Caixa (fiado): a venda sai do estoque na hora, mas só vira dinheiro quando a pessoa marca "Pago".
 * Nomes e telefones dos clientes ficam SÓ neste celular (nunca vão para o servidor). Só depois do "Pago" a venda entra
 * no caixa do Vyntem (o pedido nasce "aguardando pagamento", que o Vyntem nunca lê).
 * A parte de cima é lógica pura (testada em CaderninhoTest); a de baixo fala com o Android (guardar, avisar no dia do vencimento).
 */
internal object Caderninho {
    const val FORMA_PIX = "Caderninho Pix"
    const val FORMA_DINHEIRO = "Caderninho dinheiro"
    const val HORA_DO_AVISO = 9
    private const val MAX_GUARDADOS = 300
    private const val DIAS_DOS_PAGOS = 60

    fun ehCaderninho(forma: String) = forma == FORMA_PIX || forma == FORMA_DINHEIRO

    data class Linha(val nome: String, val quantidade: Int, val precoCentavos: Int, val produtoId: Int = 0)

    data class Fiado(
        val id: Int, val numero: String, val cliente: String, val telefone: String, val linhas: List<Linha>, val forma: String,
        val criado: Long, val venceEm: Long, val pagoEm: Long = 0L, val pedidoId: Int = 0, val interno: Boolean = true, val cobrancas: Int = 0,
    ) {
        val total: Int get() = linhas.sumOf { it.quantidade * it.precoCentavos }
        val pago: Boolean get() = pagoEm > 0L
        val viaPix: Boolean get() = forma == FORMA_PIX
        /** Como a venda fica registrada quando o dinheiro entra. */
        val formaReal: String get() = if (viaPix) "Pix" else "Dinheiro"
    }

    enum class Situacao { ABERTO, VENCE_HOJE, ATRASADO, PAGO }

    // ------------------------------------------------------------------ datas (sempre no fuso de Brasília, como o resto do Caixa)
    private fun brasilia(millis: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT-03:00")).apply { timeInMillis = millis }

    /** Um número que ordena por dia (aaaammdd), para comparar dias sem se preocupar com a hora. */
    fun diaDe(millis: Long): Int { val c = brasilia(millis); return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH) }

    /** O vencimento "daqui a [dias] dias": o dia inteiro conta, e o aviso toca às 9h desse dia. */
    fun vencimentoEmDias(agora: Long, dias: Int): Long {
        val c = brasilia(agora)
        c.add(Calendar.DAY_OF_YEAR, dias.coerceAtLeast(0))
        c.set(Calendar.HOUR_OF_DAY, HORA_DO_AVISO); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** "15/12/2026" (ou 15122026) → o vencimento daquele dia; null se a data não existe ou já passou. */
    fun dataDeTexto(texto: String, agora: Long): Long? {
        val d = texto.filter { it.isDigit() }
        if (d.length != 8) return null
        val dia = d.substring(0, 2).toInt(); val mes = d.substring(2, 4).toInt(); val ano = d.substring(4).toInt()
        val c = brasilia(agora).apply { clear(); isLenient = false; set(ano, mes - 1, dia, HORA_DO_AVISO, 0, 0) }
        val ms = try { c.timeInMillis } catch (e: Exception) { return null }
        return if (diaDe(ms) < diaDe(agora)) null else ms
    }

    fun dataBR(millis: Long): String { val c = brasilia(millis); return "%02d/%02d/%04d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR)) }

    fun situacao(f: Fiado, agora: Long): Situacao = when {
        f.pago -> Situacao.PAGO
        diaDe(f.venceEm) < diaDe(agora) -> Situacao.ATRASADO
        diaDe(f.venceEm) == diaDe(agora) -> Situacao.VENCE_HOJE
        else -> Situacao.ABERTO
    }

    /** Linha curta para a lista: "vence hoje", "vence em 3 dias (15/12)", "atrasado há 2 dias". */
    fun prazoEmTexto(f: Fiado, agora: Long): String {
        val dias = diasEntre(diaDe(agora), diaDe(f.venceEm))
        return when (situacao(f, agora)) {
            Situacao.PAGO -> "pago em ${dataBR(f.pagoEm)}"
            Situacao.VENCE_HOJE -> "vence hoje"
            Situacao.ATRASADO -> "atrasado há ${-dias} dia" + if (-dias == 1) "" else "s"
            Situacao.ABERTO -> "vence em $dias dia" + (if (dias == 1) "" else "s") + " (${dataBR(f.venceEm).substring(0, 5)})"
        }
    }

    private fun diasEntre(de: Int, ate: Int): Int {
        fun cal(d: Int) = Calendar.getInstance(TimeZone.getTimeZone("GMT")).apply { clear(); set(d / 10000, (d / 100) % 100 - 1, d % 100) }
        return Math.round((cal(ate).timeInMillis - cal(de).timeInMillis) / 86_400_000.0).toInt()
    }

    // ------------------------------------------------------------------ a lista (funções puras: devolvem a lista nova)
    fun adicionar(lista: List<Fiado>, f: Fiado): List<Fiado> {
        val id = (lista.maxOfOrNull { it.id } ?: 0) + 1
        return limpar(lista + f.copy(id = id), f.criado)
    }

    /** Marca como pago. Devolve a lista nova e o fiado que acabou de ser pago; null se não existe ou JÁ estava pago (não paga duas vezes). */
    fun pagar(lista: List<Fiado>, id: Int, agora: Long): Pair<List<Fiado>, Fiado>? {
        val alvo = lista.firstOrNull { it.id == id } ?: return null
        if (alvo.pago) return null
        val pago = alvo.copy(pagoEm = agora)
        return Pair(lista.map { if (it.id == id) pago else it }, pago)
    }

    /** Corrige um fiado ainda aberto (nome, telefone, prazo e itens). Null se não existe, já foi pago ou ficou sem itens (para isso é excluir). */
    fun editar(lista: List<Fiado>, id: Int, cliente: String, telefone: String, venceEm: Long, linhas: List<Linha>): List<Fiado>? {
        val alvo = lista.firstOrNull { it.id == id } ?: return null
        val boas = linhas.filter { it.quantidade > 0 }
        if (alvo.pago || boas.isEmpty() || cliente.isBlank()) return null
        val novo = alvo.copy(cliente = cliente.trim(), telefone = telefone, venceEm = venceEm, linhas = boas)
        return lista.map { if (it.id == id) novo else it }
    }

    /** Tira da lista um fiado ainda aberto. Pago não se apaga (já virou dinheiro no caixa). */
    fun excluir(lista: List<Fiado>, id: Int): List<Fiado>? {
        val alvo = lista.firstOrNull { it.id == id } ?: return null
        return if (alvo.pago) null else lista.filter { it.id != id }
    }

    /** Quanto cada produto muda no estoque ao trocar [antes] por [depois]: positivo = sai mais do estoque, negativo = volta. Só produtos conhecidos (id > 0) e que mudaram. */
    fun diferencaDeEstoque(antes: List<Linha>, depois: List<Linha>): Map<Int, Int> {
        val a = antes.filter { it.produtoId > 0 }.groupBy { it.produtoId }.mapValues { e -> e.value.sumOf { it.quantidade } }
        val d = depois.filter { it.produtoId > 0 }.groupBy { it.produtoId }.mapValues { e -> e.value.sumOf { it.quantidade } }
        return (a.keys + d.keys).associateWith { (d[it] ?: 0) - (a[it] ?: 0) }.filterValues { it != 0 }
    }

    fun cobrou(lista: List<Fiado>, id: Int): List<Fiado> = lista.map { if (it.id == id && !it.pago) it.copy(cobrancas = it.cobrancas + 1) else it }

    fun aReceber(lista: List<Fiado>): List<Fiado> = lista.filter { !it.pago }.sortedBy { it.venceEm }
    fun totalAReceber(lista: List<Fiado>): Int = aReceber(lista).sumOf { it.total }
    fun atrasados(lista: List<Fiado>, agora: Long): List<Fiado> = aReceber(lista).filter { situacao(it, agora) == Situacao.ATRASADO }

    /** Os pagos há mais de 60 dias saem; o resto fica, no máximo 300. */
    fun limpar(lista: List<Fiado>, agora: Long): List<Fiado> {
        val corte = agora - DIAS_DOS_PAGOS * 86_400_000L
        return lista.filter { !it.pago || it.pagoEm >= corte }.takeLast(MAX_GUARDADOS)
    }

    // ------------------------------------------------------------------ os textos do WhatsApp (tom educado, sem cobrar com dureza)
    private fun primeiroNome(cliente: String) = cliente.trim().substringBefore(" ").substringBefore("(").trim()

    private fun saudacao(cliente: String): String { val n = primeiroNome(cliente); return if (n.isNotBlank()) "Oi, $n! " else "Oi! " }

    /** A cobrança. [lembrete] = true na segunda vez em diante ("Cobrar de novo"). [pix] = o Pix copia e cola (só vai se a forma é Pix). */
    fun textoDaCobranca(loja: String, f: Fiado, pix: String?, lembrete: Boolean, agora: Long): String {
        val b = StringBuilder()
        b.append(saudacao(f.cliente))
        val nomeLoja = loja.ifBlank { "nossa loja" }
        if (lembrete) {
            val quando = when (situacao(f, agora)) {
                Situacao.ATRASADO -> "venceu em ${dataBR(f.venceEm)}"
                Situacao.VENCE_HOJE -> "vence hoje"
                else -> "vence em ${dataBR(f.venceEm)}"
            }
            b.append("Passando só para lembrar do combinado com a *").append(nomeLoja).append("*: ").append(Documentos.dinheiro(f.total)).append(" ($quando).\n")
        } else {
            b.append("Aqui é da *").append(nomeLoja).append("*. Anotei a sua compra no caderninho:\n\n")
            for (l in f.linhas) b.append(l.quantidade).append("x ").append(l.nome).append(" — ").append(Documentos.dinheiro(l.quantidade * l.precoCentavos)).append("\n")
            b.append("\n*Total: ").append(Documentos.dinheiro(f.total)).append("*\n")
            b.append("Combinado para pagar até *").append(dataBR(f.venceEm)).append("*").append(if (f.viaPix) " no Pix." else " em dinheiro.").append("\n")
        }
        if (f.viaPix && !pix.isNullOrBlank()) b.append("\nPix copia e cola:\n").append(pix).append("\n")
        b.append("\nQualquer dúvida é só chamar. Obrigado! 💖")
        return b.toString()
    }

    fun textoDeAgradecimento(loja: String, f: Fiado): String =
        saudacao(f.cliente) + "Recebi o pagamento de " + Documentos.dinheiro(f.total) + " da sua compra de " + dataBR(f.criado) +
            ". Muito obrigado! 💖\n— " + loja.ifBlank { "Equipe" }

    // ------------------------------------------------------------------ guardar no celular (JSON)
    internal fun paraJson(lista: List<Fiado>): String {
        val a = JSONArray()
        for (f in lista) a.put(
            JSONObject().put("id", f.id).put("num", f.numero).put("cli", f.cliente).put("tel", f.telefone).put("forma", f.forma)
                .put("criado", f.criado).put("vence", f.venceEm).put("pago", f.pagoEm).put("ped", f.pedidoId).put("int", f.interno).put("cob", f.cobrancas)
                .put("itens", JSONArray().also { l -> f.linhas.forEach { l.put(JSONObject().put("n", it.nome).put("q", it.quantidade).put("c", it.precoCentavos).put("p", it.produtoId)) } }),
        )
        return a.toString()
    }

    internal fun deJson(texto: String?): List<Fiado> = try {
        val a = JSONArray(texto ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val l = o.optJSONArray("itens") ?: JSONArray()
            Fiado(
                o.optInt("id"), o.optString("num"), o.optString("cli"), o.optString("tel"),
                (0 until l.length()).mapNotNull { l.optJSONObject(it) }.map { Linha(it.optString("n"), it.optInt("q"), it.optInt("c"), it.optInt("p")) },
                o.optString("forma"), o.optLong("criado"), o.optLong("vence"), o.optLong("pago"), o.optInt("ped"), o.optBoolean("int", true), o.optInt("cob"),
            )
        }
    } catch (e: Exception) { emptyList() }
}
