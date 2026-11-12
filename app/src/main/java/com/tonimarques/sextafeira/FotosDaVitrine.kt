package com.tonimarques.sextafeira

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * As fotos do catálogo interno ficam em arquivos do celular; a vitrine pública mora no servidor e só mostra imagem que ele tenha.
 * Aqui cada foto vai reduzida (JPEG de 700 px) uma vez e só é enviada de novo se o arquivo mudar.
 */
object FotosDaVitrine {
    private const val PREF = "vitrine_fotos_enviadas"
    private const val POR_VEZ = 20 // a abertura da loja não pode travar esperando 500 fotos: o resto vai nas próximas

    fun enviar(ctx: Context, produtos: List<Loja.Produto>): Int {
        val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val feitas = try { JSONObject(prefs.getString(PREF, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        var enviadas = 0
        for ((sku, caminho) in VitrineDoApp.fotosLocais(produtos)) {
            if (enviadas >= POR_VEZ) break
            val arquivo = File(Uri.decode(caminho))
            if (!arquivo.isFile) continue
            val assinatura = "${arquivo.lastModified()}-${arquivo.length()}"
            if (feitas.optString(sku) == assinatura) continue
            val bytes = FotoProvider.prepararParaEnviar(ctx, Uri.fromFile(arquivo), 700) ?: continue
            val r = try {
                Nuvem.pedir(ctx, "PUT", "/loja/vitrine/foto", JSONObject().put("sku", sku).put("dados", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            } catch (e: Exception) { break } // sem internet: tenta na próxima
            if (r.ok) { feitas.put(sku, assinatura); enviadas++ }
        }
        if (enviadas > 0) prefs.edit().putString(PREF, feitas.toString()).apply()
        return enviadas
    }
}
