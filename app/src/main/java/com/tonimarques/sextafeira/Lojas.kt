package com.tonimarques.sextafeira

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Até 3 lojas na mesma conta (uma assinatura vale as 3). A loja aberta vai no cabeçalho `x-loja` de todo pedido ao
 * servidor, então catálogo, vitrine, painel e links são separados por loja. O perfil (nome, site, ramo, regras) de cada
 * loja extra fica num arquivo próprio ("perfil_<id>") e viaja pela conta na chave "empresa-<id>".
 */
object Lojas {
    const val MAX = 3
    private const val PREFS = "config"
    private const val PREF_ABERTA = "loja_aberta"

    class Item(val id: String, val nome: String, val principal: Boolean, val ativa: Boolean)

    /** Id da loja extra aberta; vazio = a principal. */
    fun aberta(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ABERTA, "").orEmpty()
    fun extraAberta(ctx: Context) = aberta(ctx).isNotBlank()

    /** Nome do arquivo de preferências do perfil da loja aberta. */
    fun arquivoDoPerfil(ctx: Context): String = aberta(ctx).let { if (it.isBlank()) "perfil" else "perfil_$it" }

    /** Chave de sincronização do cadastro da loja aberta ("empresa" ou "empresa-<id>"). */
    fun chaveDeSync(ctx: Context): String = aberta(ctx).let { if (it.isBlank()) "empresa" else "empresa-$it" }

    fun listar(ctx: Context): List<Item> {
        val r = Nuvem.pedir(ctx, "GET", "/loja/lojas")
        if (!r.ok) throw Nuvem.ErroNuvem(r.erro())
        val lista = r.json.optJSONArray("lojas") ?: JSONArray()
        return (0 until lista.length()).map { i ->
            val o = lista.getJSONObject(i)
            Item(o.optString("id"), o.optString("nome"), o.optBoolean("principal"), o.optBoolean("ativa"))
        }
    }

    /** Cria a loja extra no servidor, já com o perfil começando neutro (só o nome e o site). Devolve o id. */
    fun criar(ctx: Context, nome: String, site: String): String {
        val r = Nuvem.pedir(ctx, "POST", "/loja/lojas", JSONObject().put("nome", nome))
        if (!r.ok) throw Nuvem.ErroNuvem(r.erro())
        val id = r.json.optString("id")
        ctx.getSharedPreferences("perfil_$id", Context.MODE_PRIVATE).edit()
            .putString("origem", "novo").putString("dono", Perfil.dono).putString("loja", nome.trim()).putString("site", Perfil.normalizarSite(site)).apply()
        return id
    }

    /** Abre uma loja ("" = a principal): troca o perfil e puxa o cadastro dela da conta. */
    fun abrir(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versoes = try { JSONObject(p.getString(VetorialId.PREF_VERSOES, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val enviados = try { JSONObject(p.getString(VetorialId.PREF_ENVIADO, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        p.edit().putString(PREF_ABERTA, id).apply()
        val chave = chaveDeSync(ctx)
        versoes.remove(chave); enviados.remove(chave) // na próxima sincronização aplica o cadastro desta loja
        p.edit().putString(VetorialId.PREF_VERSOES, versoes.toString()).putString(VetorialId.PREF_ENVIADO, enviados.toString()).putLong("vid_desde", 0L).apply()
        Perfil.carregar(ctx)
        Consciencia.esquecerRetrato()
        Diario.anotar(ctx, "loja", "Loja aberta: ${Perfil.loja}", "tela")
    }
}

/** Tela "Minhas lojas": lista as lojas da conta, abre uma delas ou cria outra (até 3). */
internal fun MainActivity.minhasLojas() {
    if (!VetorialId.logado(this)) return responder("Entre com o seu Vetorial ID (em Ajustes) para ter até ${Lojas.MAX} lojas na mesma conta.")
    Thread {
        val lojas = try { Lojas.listar(this) } catch (e: Exception) { null }
        runOnUiThread {
            if (lojas == null) return@runOnUiThread responder("Não consegui ver as suas lojas agora. Tente de novo em instantes.")
            val aberta = Lojas.aberta(this)
            val itens = lojas.map { l ->
                val idLoja = if (l.principal) "" else l.id
                TelaDeAjustes.Ajuste(l.nome, { if (idLoja == aberta) "✓ Aberta agora" else "Toque para abrir" }) {
                    if (idLoja != aberta) {
                        Lojas.abrir(this, idLoja)
                        ajustes?.atualizar()
                        responder("Abri a loja ${Perfil.loja}. Vitrine, painel, catálogo e perfil agora são dela.")
                        Thread { try { SincroniaId.rodar(this) } catch (e: Exception) { /* tenta no próximo ciclo */ } }.start()
                    }
                }
            }.toMutableList()
            if (lojas.size < Lojas.MAX) itens.add(TelaDeAjustes.Ajuste("Criar outra loja", { "Outro site ou nicho, na mesma assinatura (${lojas.size} de ${Lojas.MAX})." }) { criarLoja() })
            TelaDeAjustes(this, versaoDoApp(), listOf(TelaDeAjustes.Secao("Minhas lojas (${lojas.size} de ${Lojas.MAX})", itens)), "MINHAS LOJAS").show()
        }
    }.start()
}

internal fun MainActivity.criarLoja() {
    val caixa = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
    val nome = campoDeDialogo("", "Nome da loja").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    val site = campoDeDialogo("", "Site da loja (opcional)")
    listOf(nome, site).forEach { caixa.addView(it) }
    CaixaBuilder(this)
        .setTitle("Criar outra loja")
        .setMessage("Ela tem produtos, vitrine, painel e perfil próprios, e usa a mesma assinatura.")
        .setView(caixa)
        .setPositiveButton("Criar") { _, _ ->
            val n = nome.text.toString().trim()
            if (n.isBlank()) return@setPositiveButton Toast.makeText(this, "Dê um nome para a loja.", Toast.LENGTH_SHORT).show()
            val s = site.text.toString()
            Thread {
                val erro = try { Lojas.abrir(this, Lojas.criar(this, n, s)); null } catch (e: Exception) { e.message ?: "Não consegui criar a loja agora." }
                runOnUiThread {
                    if (erro != null) return@runOnUiThread responder(erro)
                    ajustes?.atualizar()
                    responder("Criei a loja ${Perfil.loja} e já abri. Em Perfil do dono e da loja você completa o resto.")
                }
            }.start()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
