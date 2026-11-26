package com.tonimarques.sextafeira

/**
 * Caixa (balcão): lógica pura da venda rápida. Dinheiro sempre em centavos inteiros.
 * O carrinho guarda só produto -> quantidade; preço e estoque vêm do catálogo no momento de fechar.
 */
internal object Pdv {
    data class Carrinho(val linhas: Map<Int, Int> = emptyMap()) {
        fun somar(id: Int, n: Int = 1): Carrinho {
            val q = (linhas[id] ?: 0) + n
            return Carrinho(if (q <= 0) linhas - id else linhas + (id to q))
        }
        fun vazio() = linhas.isEmpty()
        fun unidades() = linhas.values.sum()
    }

    /** Itens no formato de [CatalogoInterno.registrarVenda]; produto que sumiu do catálogo é ignorado. */
    fun itens(c: Carrinho, produtos: List<Loja.Produto>): List<Triple<Loja.Produto, Int, Int?>> =
        c.linhas.mapNotNull { (id, q) -> produtos.firstOrNull { it.id == id }?.let { Triple(it, q, null) } }

    fun total(c: Carrinho, produtos: List<Loja.Produto>): Int = itens(c, produtos).sumOf { (p, q, _) -> (p.precoCentavos ?: 0) * q }

    /** Produtos que passam do estoque (só os que têm controle de estoque). */
    fun faltando(c: Carrinho, produtos: List<Loja.Produto>): List<String> =
        itens(c, produtos).filter { (p, q, _) -> p.estoque != null && q > p.estoque }.map { it.first.nome }

    /** Troco em centavos; null se o que o cliente deu não cobre o total. */
    fun troco(total: Int, recebido: Int): Int? = if (recebido >= total) recebido - total else null

    /** Calculadora do dinheiro: os dígitos entram pela direita, como numa maquininha (digitar 1 5 0 0 = R$ 15,00). Teclas: "0".."9", "00", "apagar", "limpar". Passa de R$ 999.999,99 não entra. */
    fun teclar(centavos: Long, tecla: String): Long = when (tecla) {
        "apagar" -> centavos / 10
        "limpar" -> 0L
        "00" -> if (centavos * 100 > 99_999_999L) centavos else centavos * 100
        else -> if (tecla.length == 1 && tecla[0] in '0'..'9' && centavos * 10 + (tecla[0] - '0') <= 99_999_999L) centavos * 10 + (tecla[0] - '0') else centavos
    }

    /** Taxa e líquido de um pagamento com taxa em % (ex.: 3,49). Arredonda a taxa para o centavo mais próximo. */
    fun liquido(total: Int, taxaPct: Double): Pair<Int, Int> {
        val pct = taxaPct.coerceIn(0.0, 100.0)
        val taxa = Math.round(total * pct / 100.0).toInt()
        return Pair(taxa, total - taxa)
    }

    /** Lê "3,49" ou "3.49" como porcentagem; null se não for número. */
    fun lerPct(texto: String): Double? = texto.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

    // ------------------------------------------------------------------ etiquetas com QR Code
    const val PREFIXO_CODIGO = "venus:p:"

    /** O texto guardado no QR Code da etiqueta de um produto. */
    fun codigoDoProduto(id: Int): String = "$PREFIXO_CODIGO$id"

    /** O id do produto lido de uma etiqueta; null se o QR Code não for de etiqueta da Vênus. */
    fun lerCodigo(texto: String?): Int? {
        val t = texto?.trim().orEmpty()
        if (!t.startsWith(PREFIXO_CODIGO)) return null
        return t.removePrefix(PREFIXO_CODIGO).toIntOrNull()?.takeIf { it > 0 }
    }

    data class Celula(val x: Float, val y: Float, val largura: Float, val altura: Float)

    /** Divide a página em [colunas] x [linhas] etiquetas, da esquerda para a direita e de cima para baixo. */
    fun grade(largura: Int, altura: Int, colunas: Int, linhas: Int, margem: Int): List<Celula> {
        val cw = (largura - 2 * margem) / colunas.toFloat()
        val ch = (altura - 2 * margem) / linhas.toFloat()
        return (0 until linhas).flatMap { r -> (0 until colunas).map { c -> Celula(margem + c * cw, margem + r * ch, cw, ch) } }
    }

    // ------------------------------------------------------------------ recibo do cliente (CPF e WhatsApp são opcionais)
    /** Só os dígitos do CPF; null se não tiver 11 ou se os dois dígitos verificadores não baterem (ex.: 111.111.111-11 não vale). */
    fun cpfValido(texto: String): String? {
        val d = texto.filter { it.isDigit() }
        if (d.length != 11 || d.all { it == d[0] }) return null
        fun dv(n: Int): Int {
            val soma = (0 until n).sumOf { (d[it] - '0') * (n + 1 - it) }
            val r = (soma * 10) % 11
            return if (r == 10) 0 else r
        }
        return if (dv(9) == d[9] - '0' && dv(10) == d[10] - '0') d else null
    }

    fun cpfBonito(digitos: String): String =
        if (digitos.length == 11) "${digitos.substring(0, 3)}.${digitos.substring(3, 6)}.${digitos.substring(6, 9)}-${digitos.substring(9)}" else digitos

    /** Celular/telefone do cliente no formato do wa.me (55 + DDD + número); null se não parecer um telefone do Brasil. */
    fun telefoneParaWhatsApp(texto: String): String? {
        var d = texto.filter { it.isDigit() }
        if (d.length in 12..13 && d.startsWith("55")) d = d.substring(2)
        if (d.length !in 10..11) return null
        if (d.substring(0, 2).toInt() < 11) return null // DDD
        if (d.length == 11 && d[2] != '9') return null   // celular novo começa com 9
        return "55$d"
    }

    class LinhaDoRecibo(val nome: String, val quantidade: Int, val precoCentavos: Int)

    /** O texto do recibo que vai pelo WhatsApp (negrito do WhatsApp com *). CPF só entra se a pessoa quis. */
    fun textoDoRecibo(loja: String, numero: String, data: String, itens: List<LinhaDoRecibo>, forma: String, mensagem: String, cpfDigitos: String?): String {
        val total = itens.sumOf { it.quantidade * it.precoCentavos }
        val b = StringBuilder()
        b.append("*").append(loja.ifBlank { "Recibo" }).append("*\n")
        b.append("Compra nº ").append(numero)
        if (data.isNotBlank()) b.append(" · ").append(data)
        b.append("\n\n")
        for (i in itens) b.append(i.quantidade).append("x ").append(i.nome).append(" — ").append(Documentos.dinheiro(i.quantidade * i.precoCentavos)).append("\n")
        b.append("\n*Total: ").append(Documentos.dinheiro(total)).append("* · ").append(forma).append("\n")
        if (cpfDigitos != null) b.append("CPF na nota: ").append(cpfBonito(cpfDigitos)).append("\n")
        if (mensagem.isNotBlank()) b.append("\n").append(mensagem.trim()).append("\n")
        b.append("\n").append(Documentos.AVISO_SEM_VALOR_FISCAL)
        return b.toString()
    }

    /** Link que abre o WhatsApp com a mensagem pronta (com ou sem número; sem número a pessoa escolhe o contato). */
    fun linkWhatsApp(telefone55: String?, texto: String): String =
        "https://wa.me/" + (telefone55 ?: "") + "?text=" + java.net.URLEncoder.encode(texto, "UTF-8").replace("+", "%20")
}
