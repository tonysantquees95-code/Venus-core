package com.tonimarques.sextafeira

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda chaves e tokens (Gemini, Groq, Cloudflare, Microsoft, Instagram) cifrados com uma chave AES que fica
 * no chip de segurança do Android (AndroidKeyStore): ela nunca sai do celular nem vai para backups.
 * O valor salvo fica "cofre1:<iv>:<dados>". Valores antigos, em texto, são cifrados na primeira leitura.
 * Se a chave do Android sumir (restauração em outro celular), a leitura volta vazia e o app pede a chave de novo.
 */
object Cofre {

    private const val ALIAS = "venus_cofre"
    const val MARCA = "cofre1:"

    /** Decifrar a cada leitura é caro: guarda o último par (cifrado → texto) de cada valor. */
    private val lembrados = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var guardada: SecretKey? = null

    /**
     * Uma só chave, criada uma vez: sincronizado porque duas partes do app abrindo ao mesmo tempo
     * criavam duas chaves com o mesmo nome, e o que foi cifrado com a primeira não abria mais.
     */
    @Synchronized
    private fun chave(): SecretKey {
        guardada?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { guardada = it; return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gerador.generateKey().also { guardada = it }
    }

    /** Valor pronto para salvar: cifrado quando o Android deixa; em texto só se o cofre não funcionar (testes). */
    fun selar(valor: String): String {
        if (valor.isEmpty() || valor.startsWith(MARCA)) return valor
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, chave())
            val dados = c.doFinal(valor.toByteArray(Charsets.UTF_8))
            val iv = Base64.encodeToString(c.iv, Base64.NO_WRAP) ?: return valor
            val corpo = Base64.encodeToString(dados, Base64.NO_WRAP) ?: return valor
            (MARCA + iv + ":" + corpo).also { lembrados[it] = valor }
        } catch (e: Exception) {
            valor
        }
    }

    /** Texto de um valor salvo (cifrado ou antigo, em texto). Vazio se não der para abrir. */
    fun abrir(salvo: String?): String {
        if (salvo.isNullOrEmpty()) return ""
        if (!salvo.startsWith(MARCA)) return salvo
        lembrados[salvo]?.let { return it }
        return try {
            val (iv, dados) = salvo.removePrefix(MARCA).split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, iv))
            String(c.doFinal(dados), Charsets.UTF_8).also { lembrados[salvo] = it }
        } catch (e: Exception) {
            ""
        }
    }

    /** Lê um segredo salvo; se ainda estiver em texto (versões antigas), já guarda cifrado. */
    fun ler(p: SharedPreferences, nome: String): String {
        val salvo = p.getString(nome, null) ?: return ""
        if (salvo.isNotEmpty() && !salvo.startsWith(MARCA)) {
            val selado = selar(salvo)
            if (selado != salvo) p.edit().putString(nome, selado).apply()
            return salvo
        }
        return abrir(salvo)
    }

    fun guardar(p: SharedPreferences, nome: String, valor: String) {
        p.edit().putString(nome, selar(valor.trim())).apply()
    }
}
