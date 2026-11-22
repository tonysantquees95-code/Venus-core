package com.tonimarques.sextafeira

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/**
 * Recibo, notinha e etiqueta de envio (sem valor fiscal). Aqui fica só a parte "de conta": monta o que vai
 * escrito em cada documento a partir do pedido, formata dinheiro e data e controla o limite do plano grátis
 * (15 por mês). O desenho do PDF está em [GeradorDeDocumentos] e a tela em TelaDocumentos.kt.
 * Dinheiro sempre em centavos inteiros.
 */
object Documentos {
    enum class Tipo(val rotulo: String, val arquivo: String) {
        RECIBO("Recibo", "recibo"),
        NOTINHA("Notinha", "notinha"),
        ETIQUETA("Etiqueta de envio", "etiqueta"),
    }

    const val LIMITE_GRATIS = 15
    const val AVISO_SEM_VALOR_FISCAL = "Documento sem valor fiscal."

    data class Linha(val nome: String, val quantidade: Int, val precoCentavos: Int)

    /** Os dados da loja que saem no cabeçalho e no remetente (guardados no aparelho). */
    data class Emissor(
        val nome: String,
        val documento: String = "",
        val contato: String = "",
        val enderecoLinhas: List<String> = emptyList(),
        val mensagem: String = "",
    )

    data class Dados(
        val loja: Emissor,
        val numero: String,
        val data: String,
        val cliente: String,
        val telefone: String,
        val enderecoLinhas: List<String>,
        val itens: List<Linha>,
        val freteCentavos: Int,
        val descontoCentavos: Int,
        val totalCentavos: Int,
        val retirada: Boolean,
        val rastreio: String?,
        val observacao: String = "",
    ) {
        val subtotalCentavos: Int get() = itens.sumOf { it.quantidade * it.precoCentavos }
    }

    // ------------------------------------------------------------------ montagem
    /** Do pedido da loja para os dados do documento. A data é a do pagamento (ou a da criação) no fuso de Brasília. */
    fun doPedido(p: Loja.Pedido, loja: Emissor): Dados = Dados(
        loja = loja,
        numero = p.id.toString(),
        data = dataBR(p.pago ?: p.criado),
        cliente = p.cliente.trim(),
        telefone = telefoneBonito(p.telefone),
        enderecoLinhas = p.endereco.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
        itens = p.itens.map { Linha(it.nome.trim(), it.quantidade, it.precoCentavos) },
        freteCentavos = p.freteCentavos,
        descontoCentavos = p.descontoCentavos,
        totalCentavos = p.totalCentavos,
        retirada = p.entrega == "pickup",
        rastreio = p.rastreio?.trim()?.ifBlank { null },
    )

    /** Recibo avulso (sem pedido): um valor, uma descrição, o nome de quem pagou. */
    fun avulso(loja: Emissor, numero: String, agora: Long, cliente: String, descricao: String, totalCentavos: Int): Dados = Dados(
        loja = loja, numero = numero, data = dataBR(agora), cliente = cliente.trim(), telefone = "",
        enderecoLinhas = emptyList(), itens = listOf(Linha(descricao.trim().ifBlank { "Pagamento" }, 1, totalCentavos)),
        freteCentavos = 0, descontoCentavos = 0, totalCentavos = totalCentavos, retirada = true, rastreio = null,
    )

    /** Linhas do endereço como a pessoa escreveria no envelope. Campos vazios não deixam buraco. */
    fun enderecoEmLinhas(rua: String, numero: String, complemento: String, bairro: String, cidade: String, uf: String, cep: String): List<String> {
        val digitos = cep.filter { it.isDigit() }
        val ruaENumero = listOf(rua.trim(), numero.trim()).filter { it.isNotEmpty() }.joinToString(", ")
        val comComplemento = listOf(ruaENumero, complemento.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
        val cidadeUf = listOf(cidade.trim(), uf.trim().uppercase()).filter { it.isNotEmpty() }.joinToString("/")
        return listOf(
            comComplemento,
            bairro.trim(),
            cidadeUf,
            if (digitos.length == 8) "CEP " + digitos.substring(0, 5) + "-" + digitos.substring(5) else "",
        ).filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ formatos
    fun dinheiro(centavos: Int): String {
        val negativo = centavos < 0
        val c = Math.abs(centavos.toLong())
        val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        return (if (negativo) "-" else "") + "R$ $reais,%02d".format(c % 100)
    }

    private fun brasilia(millis: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT-03:00")).apply { timeInMillis = millis }

    fun dataBR(millis: Long): String {
        if (millis <= 0L) return ""
        val c = brasilia(millis)
        return "%02d/%02d/%04d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
    }

    /** "2026-11" no fuso de Brasília: a conta do limite mensal vira no começo do mês de lá. */
    fun mesDe(millis: Long): String {
        val c = brasilia(millis)
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    fun telefoneBonito(bruto: String): String {
        var d = bruto.filter { it.isDigit() }
        if (d.length >= 12 && d.startsWith("55")) d = d.substring(2)
        return when (d.length) {
            11 -> "(${d.substring(0, 2)}) ${d.substring(2, 7)}-${d.substring(7)}"
            10 -> "(${d.substring(0, 2)}) ${d.substring(2, 6)}-${d.substring(6)}"
            else -> bruto.trim()
        }
    }

    /** Nome do arquivo no formato que o FotoProvider aceita (minúsculas, sem acento, só letras, números, - e _). */
    fun nomeDoArquivo(tipo: Tipo, numero: String): String {
        val limpo = Normalizer.normalize(numero, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "avulso" }
        return "${tipo.arquivo}-$limpo.pdf"
    }

    // ------------------------------------------------------------------ limite do plano grátis
    /** O que está guardado: o mês e as chaves "tipo-número" já feitas nele. */
    data class Cota(val mes: String, val feitos: Set<String>)

    private fun chave(tipo: Tipo, numero: String) = "${tipo.arquivo}-$numero"

    /** Vira o mês: quem ficou para trás zera. */
    fun doMes(guardada: Cota?, mesAtual: String): Cota = if (guardada == null || guardada.mes != mesAtual) Cota(mesAtual, emptySet()) else guardada

    fun usados(guardada: Cota?, mesAtual: String): Int = doMes(guardada, mesAtual).feitos.size

    fun restantes(guardada: Cota?, mesAtual: String): Int = (LIMITE_GRATIS - usados(guardada, mesAtual)).coerceAtLeast(0)

    /** Refazer o mesmo documento (mesmo pedido e tipo) no mesmo mês não gasta outro: só conta o que é novo. */
    fun podeGerar(guardada: Cota?, mesAtual: String, tipo: Tipo, numero: String, semLimite: Boolean): Boolean {
        if (semLimite) return true
        val c = doMes(guardada, mesAtual)
        return chave(tipo, numero) in c.feitos || c.feitos.size < LIMITE_GRATIS
    }

    fun registrar(guardada: Cota?, mesAtual: String, tipo: Tipo, numero: String): Cota {
        val c = doMes(guardada, mesAtual)
        return c.copy(feitos = c.feitos + chave(tipo, numero))
    }

    /** No plano pago o documento sai sem a marca da Vênus no rodapé. */
    fun comMarca(semLimite: Boolean): Boolean = !semLimite

    /** Texto de uso para a tela. */
    fun resumoDoUso(guardada: Cota?, mesAtual: String, semLimite: Boolean): String =
        if (semLimite) "Sem limite no seu plano."
        else "Você usou ${usados(guardada, mesAtual)} de $LIMITE_GRATIS documentos grátis neste mês."
}
