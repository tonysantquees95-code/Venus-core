package com.tonimarques.sextafeira

import android.content.SharedPreferences

/**
 * O que a Central da loja já sabe da loja (site conectado, Instagram, WhatsApp, Pix), para o QR Code, o cartão de visita
 * e o panfleto saírem completos sem digitar de novo. O que a pessoa digita uma vez fica guardado para a próxima.
 * A parte de texto é pura (testada); só [lembrar] e [guardado] tocam nas preferências.
 */
object DadosDaLoja {
    const val PREF_LINK = "materiais_link" // "site" ou "vitrine"
    const val PREF_WHATSAPP = "materiais_whatsapp"
    const val PREF_PIX = "materiais_pix"
    const val PREF_CARGO = "materiais_cargo"

    /** Qual endereço vai nos materiais: o escolhido, se existir; senão o outro; o site da loja é o padrão. */
    fun escolherLink(preferencia: String, site: String, vitrine: String): String {
        val s = site.trim()
        val v = vitrine.trim()
        return when {
            preferencia == "vitrine" && v.isNotBlank() -> v
            s.isNotBlank() && preferencia != "vitrine" -> s
            v.isNotBlank() -> v
            else -> s
        }
    }

    fun nomeDoLink(preferencia: String, site: String): String =
        if (preferencia == "vitrine" || site.isBlank()) "Site criado pela Vênus (vitrine)" else "Site da loja conectado"

    /** "@sualoja" (aceita "sualoja", "@sualoja" e o endereço do perfil); vazio se não tiver. */
    fun arroba(usuario: String?): String {
        val u = usuario.orEmpty().trim().removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .removePrefix("instagram.com/").trimEnd('/').removePrefix("@").substringBefore("?").trim()
        return if (u.isBlank()) "" else "@$u"
    }

    fun enderecoDoInstagram(usuario: String?): String = arroba(usuario).let { if (it.isBlank()) "" else "https://instagram.com/${it.removePrefix("@")}" }

    fun digitos(t: String) = t.filter { it.isDigit() }

    /** (51) 99999-9999 quando for celular com DDD; senão devolve como foi digitado. */
    fun telefoneBonito(t: String): String {
        val d = digitos(t).removePrefix("55").let { if (digitos(t).length in 12..13) it else digitos(t) }
        return when (d.length) {
            11 -> "(${d.substring(0, 2)}) ${d.substring(2, 7)}-${d.substring(7)}"
            10 -> "(${d.substring(0, 2)}) ${d.substring(2, 6)}-${d.substring(6)}"
            else -> t.trim()
        }
    }

    /** Rodapé do panfleto: WhatsApp, Instagram e o site, só o que existir. */
    fun rodape(whatsapp: String, instagram: String?, link: String): String =
        listOf(telefoneBonito(whatsapp).takeIf { digitos(whatsapp).length >= 10 }, arroba(instagram).takeIf { it.isNotBlank() }, link.removePrefix("https://").removePrefix("http://").trimEnd('/').takeIf { it.isNotBlank() })
            .filterNotNull().joinToString("  ·  ")

    fun guardado(p: SharedPreferences, chave: String): String = p.getString(chave, "").orEmpty()
    fun lembrar(p: SharedPreferences, chave: String, valor: String) {
        if (valor.isNotBlank()) p.edit().putString(chave, valor.trim()).apply()
    }
}
