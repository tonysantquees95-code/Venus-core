package com.tonimarques.sextafeira

import android.content.Intent

/**
 * Menu › Caixa: a Vênus vira um caixa em tela cheia ([CaixaActivity]) para vender no balcão, na rua ou em feira.
 * Vale para qualquer loja (com ou sem site): usa o catálogo e os preços que a loja já tem.
 */
internal fun MainActivity.abrirCaixa() {
    startActivity(Intent(this, CaixaActivity::class.java))
}
