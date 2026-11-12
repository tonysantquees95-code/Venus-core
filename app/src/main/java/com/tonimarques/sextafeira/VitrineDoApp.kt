package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject

/** O que a Vênus manda do catálogo da loja (site conectado ou catálogo interno) para a vitrine pública do servidor. */
object VitrineDoApp {
    const val MAXIMO = 500

    /** Só o que dá para vender: ativo e com preço. Preço antigo maior que o atual vira promoção. */
    fun itens(produtos: List<Loja.Produto>): JSONArray {
        val saida = JSONArray()
        for (p in produtos) {
            if (saida.length() >= MAXIMO) break
            val preco = p.precoCentavos ?: continue
            if (!p.ativo || preco <= 0 || p.nome.isBlank()) continue
            val antigo = p.precoAntigo
            val promocao = antigo != null && antigo > preco
            val item = JSONObject()
                .put("sku", p.id.toString())
                .put("nome", p.nome.trim())
                .put("preco_centavos", if (promocao) antigo else preco)
                .put("estoque", p.estoque ?: 1)
                .put("categoria", categoria(p))
            if (promocao) item.put("promocao_centavos", preco)
            texto(p, "imageUrl")?.takeIf { it.startsWith("https://") }?.let { item.put("imagem", it) }
            texto(p, "description")?.let { item.put("descricao", it.replace(Regex("\\s+"), " ").take(400)) }
            saida.put(item)
        }
        return saida
    }

    private fun texto(p: Loja.Produto, campo: String): String? =
        p.bruto?.takeIf { it.has(campo) && !it.isNull(campo) }?.optString(campo)?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    private fun categoria(p: Loja.Produto): String = texto(p, "category")?.let { GeradorDeCatalogo.nomeDaCategoria(it) } ?: ""

    /** Frase curta para a vitrine quando o dono ainda não escreveu uma. */
    fun descricao(ramo: String, cidade: String, uf: String): String {
        val lugar = listOf(cidade.trim(), uf.trim()).filter { it.isNotBlank() }.joinToString("/")
        val r = ramo.trim().replaceFirstChar { it.uppercase() }
        return listOf(r, lugar).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
