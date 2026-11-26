package com.tonimarques.sextafeira

import android.content.Intent

/**
 * Minha loja › Caixa: a Vênus vira um caixa em tela cheia ([CaixaActivity]) para quem vende no balcão, na rua ou em feira.
 * Só vale para a loja sem site (catálogo na Vênus); nas lojas com site as vendas entram sozinhas.
 */
internal fun MainActivity.abrirCaixa() {
    if (!ferramentas.loja.ehInterno) return responder("O Caixa é para a loja sem site (catálogo na ${Marca.nome}). Nas lojas com site, as vendas entram sozinhas.")
    if (CatalogoInterno(this).produtos().none { it.ativo && (it.precoCentavos ?: 0) > 0 }) return responder("Cadastre produtos com preço primeiro (Catálogo da loja → Novo produto).")
    startActivity(Intent(this, CaixaActivity::class.java))
}
