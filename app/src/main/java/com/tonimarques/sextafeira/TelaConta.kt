package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** Vetorial ID: entrar, criar conta, licença e "Meus dispositivos" (3 vinculados, 2 ativos). */

private fun JSONArray?.itens(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Qualquer falha ao montar a janela da conta vira aviso (e fica no registro) em vez de fechar a Vênus. */
private inline fun MainActivity.protegido(onde: String, bloco: () -> Unit) {
    try { bloco() } catch (e: Throwable) {
        Registro.anotar(this, "erro", "$onde → " + Registro.resumoDoErro(e).replace("\n", " | "))
        try { aviso("Não consegui abrir o Vetorial ID ($onde): ${e.javaClass.simpleName} ${e.message.orEmpty().take(90)} ${e.stackTrace.firstOrNull()?.let { "@" + it.fileName + ":" + it.lineNumber }.orEmpty()}") } catch (_: Throwable) { }
    }
}

internal fun MainActivity.abrirConta() = protegido("abrirConta") {
    if (!VetorialId.logado(this)) return@protegido formularioDeConta(criar = false)
    escolher("Vetorial ID · ${VetorialId.email(this)}", listOf(
        "Meus dispositivos" to { abrirDispositivos() },
        "Licença e renovação" to { abrirLicenca() },
        "Sair deste aparelho" to { emFundo({ VetorialId.sair(this) }) { aviso("Você saiu do Vetorial ID neste aparelho."); ajustes?.atualizar(); gaveta?.atualizarPerfil() } },
    ))
}

private fun MainActivity.formularioDeConta(criar: Boolean, email0: String = "", nome0: String = "") {
    val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
    fun campo(dica: String, tipo: Int, valor: String = "") = EditText(this).apply { hint = dica; setText(valor); inputType = tipo; setSingleLine(); coluna.addView(this) }
    val nome = if (criar) campo("Nome da loja ou o seu nome", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, nome0) else null
    val email = campo("E-mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, email0)
    val senha = campo("Senha (8 ou mais caracteres)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    val pendente = VetorialId.pegarAviso(this)
    CaixaBuilder(this)
        .setTitle(if (criar) "Criar conta Vetorial ID" else "Entrar com o Vetorial ID")
        .setMessage(pendente.ifBlank { "Um login só para a Vênus (celular, Windows e iPhone), o Vindex e o Vortex." })
        .setView(coluna)
        .setPositiveButton(if (criar) "Criar conta" else "Entrar") { _, _ ->
            val e = email.text.toString().trim(); val s = senha.text.toString(); val n = nome?.text?.toString().orEmpty().trim()
            tentarLogin(criar, e, s, n, null, null)
        }
        .setNeutralButton(if (criar) "Já tenho conta" else "Criar conta") { _, _ ->
            formularioDeConta(!criar, email.text.toString().trim(), nome?.text?.toString().orEmpty())
        }
        .setNegativeButton("Cancelar", null).show()
}

private fun MainActivity.tentarLogin(criar: Boolean, email: String, senha: String, nome: String, encerrar: String?, remover: String?) {
    aviso(if (criar) "Criando a conta…" else "Entrando…")
    emFundo({ if (criar && encerrar == null && remover == null) VetorialId.cadastrar(this, email, senha, nome) else VetorialId.entrar(this, email, senha, encerrar, remover) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { resp ->
            val codigo = resp.json.optString("codigo")
            when {
                resp.ok -> { aviso("Conectado como ${VetorialId.email(this)}."); BatimentoId.agora(this); ajustes?.atualizar() }
                codigo == "limite_simultaneo" -> escolher("Já há 2 aparelhos usando a conta agora. Encerre um para entrar aqui:",
                    resp.json.optJSONArray("ativas").itens().map { a ->
                        "Encerrar ${a.optString("nome")}" to { tentarLogin(false, email, senha, nome, a.optString("sessao"), null) }
                    } + ("Cancelar login neste aparelho" to { aviso("Login cancelado.") }))
                codigo == "limite_dispositivos" -> escolher("Você já tem 3 aparelhos vinculados. Remova um para vincular este:",
                    resp.json.optJSONArray("dispositivos").itens().map { d ->
                        "Remover ${d.optString("nome")}" to { tentarLogin(false, email, senha, nome, null, d.optString("id")) }
                    } + ("Cancelar login neste aparelho" to { aviso("Login cancelado.") }))
                else -> aviso(resp.erro())
            }
        }
    }
}

internal fun MainActivity.abrirDispositivos() {
    if (!VetorialId.logado(this)) return aviso("Entre com o Vetorial ID para ver os seus dispositivos.").also { formularioDeConta(false) }
    emFundo({ VetorialId.dispositivos(this) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { resp ->
            if (!resp.ok) return@onSuccess aviso(resp.erro())
            val lista = resp.json.optJSONArray("dispositivos").itens()
            val itens = lista.map { d ->
                val atual = d.optBoolean("atual"); val ativo = d.optBoolean("ativo")
                TelaDeAjustes.Ajuste(d.optString("nome") + if (atual) " (este aparelho)" else "", {
                    "${plataformaLegivel(d.optString("plataforma"))} · " + if (ativo) "ativo agora" else "visto ${haQuantoTempo(d.optLong("ultimo"))}"
                }) {
                    if (atual) return@Ajuste aviso("Para sair deste aparelho use Vetorial ID › Sair.")
                    escolher(d.optString("nome"), buildList {
                        if (ativo) add("Encerrar sessão agora" to { acaoDeDispositivo(d, remover = false) })
                        add("Remover aparelho da conta" to { acaoDeDispositivo(d, remover = true) })
                    })
                }
            }
            val max = resp.json.optInt("maxDispositivos", 3); val ativas = resp.json.optInt("maxAtivas", 2)
            telaDeLista("MEUS DISPOSITIVOS", listOf(TelaDeAjustes.Secao("Até $max vinculados · $ativas usando ao mesmo tempo", if (itens.isEmpty()) listOf(TelaDeAjustes.Ajuste("Nenhum aparelho", { "" }) { }) else itens)))
        }
    }
}

private fun plataformaLegivel(p: String) = when (p) { "android" -> "Android"; "ios" -> "iPhone"; "windows" -> "Windows"; else -> "Navegador" }

private fun MainActivity.acaoDeDispositivo(d: JSONObject, remover: Boolean) {
    emFundo({ if (remover) VetorialId.removerDispositivo(this, d.optString("id")) else VetorialId.encerrarSessao(this, d.optString("sessao")) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { aviso(if (it.ok) (if (remover) "Aparelho removido." else "Sessão encerrada.") else it.erro()); if (it.ok) abrirDispositivos() }
    }
}

internal fun MainActivity.abrirLicenca() {
    emFundo({ VetorialId.licenca(this, false) }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { resp ->
            if (!resp.ok) return@onSuccess aviso(resp.erro())
            val j = resp.json
            val estado = when {
                !j.optBoolean("acesso") -> "Acesso vencido. Renove o Plano Fundadores para continuar."
                j.optBoolean("emTeste") -> "Teste grátis: ${j.optInt("diasDeTesteRestantes")} dia(s) restantes."
                else -> "Plano Fundadores ativo: ${j.optInt("diasPagosRestantes")} dia(s) restantes."
            }
            CaixaBuilder(this).setTitle("Licença").setMessage("$estado\n\nPlano Fundadores: R$ 49,90 por mês. Ao pagar, a licença é renovada sozinha por mais 30 dias.")
                .setPositiveButton("Gerar link de pagamento") { _, _ ->
                    emFundo({ VetorialId.licenca(this, true) }) { r2 ->
                        r2.onFailure { aviso(it.mensagemAmigavel()) }
                        r2.onSuccess { x ->
                            val url = x.json.optJSONObject("cobranca")?.optString("url").orEmpty()
                            if (x.ok && url.startsWith("https://")) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { copiar("Pagamento", url) } else aviso(x.erro())
                        }
                    }
                }.setNegativeButton("Fechar", null).show()
        }
    }
}

/** Menu rápido da conta (avatar): e-mail, assinatura e atalhos. */
internal fun MainActivity.abrirMenuDaConta() = protegido("abrirMenuDaConta") {
    if (!VetorialId.logado(this)) return@protegido abrirConta()
    val nome = VetorialId.nomeDaConta(this).ifBlank { "Minha conta" }
    escolher("$nome\n${VetorialId.email(this)}\n${VetorialId.assinatura(this).ifBlank { "Carregando assinatura…" }}", listOf(
        "Gerenciar conta" to { abrirConta() },
        "Meus dispositivos conectados" to { abrirDispositivos() },
        "Configurações" to { abrirMenu(window.decorView) },
        "Sair" to { emFundo({ VetorialId.sair(this) }) { aviso("Você saiu do Vetorial ID neste aparelho."); gaveta?.atualizarPerfil() } },
    ))
}

/** Aviso inteligente: já há 2 aparelhos ativos; encerrar um deles ou sair deste. */
internal fun MainActivity.mostrarConflitoDeSessao(ativas: List<JSONObject>) {
    if (isFinishing || BatimentoId.conflitoAberto) return
    BatimentoId.conflitoAberto = true
    val nomes = ativas.map { "Encerrar ${it.optString("nome")}" }.toTypedArray()
    CaixaBuilder(this).setTitle("Aparelhos demais usando a conta")
        .setMessage("Já há 2 aparelhos conectados agora. Encerre um deles para continuar aqui, ou cancele.")
        .setItems(nomes) { _, i ->
            BatimentoId.conflitoAberto = false
            emFundo({ VetorialId.reativar(this, ativas[i].optString("sessao")) }) { r -> r.onSuccess { aviso(if (it.ok) "Pronto, você está conectado." else it.erro()) } }
        }
        .setNegativeButton("Cancelar (sair deste aparelho)") { _, _ ->
            BatimentoId.conflitoAberto = false
            emFundo({ VetorialId.sair(this) }) { aviso("Você saiu do Vetorial ID neste aparelho.") }
        }
        .setCancelable(false).show()
}

/** Batimento (ping) a cada minuto com o app aberto + sincronização de tema e preferências entre os aparelhos. */
object BatimentoId {
    private val h = Handler(Looper.getMainLooper())
    private var ativo: MainActivity? = null
    internal var conflitoAberto = false
    private const val PERIODO_MS = 60_000L
    private val tique = object : Runnable {
        override fun run() {
            val a = ativo ?: return
            agora(a)
            h.postDelayed(this, PERIODO_MS)
        }
    }

    fun iniciar(a: MainActivity) { ativo = a; h.removeCallbacks(tique); h.post(tique) }
    fun parar() { ativo = null; h.removeCallbacks(tique) }

    fun agora(a: MainActivity) {
        if (!VetorialId.logado(a)) return
        Thread {
            try {
                val r = VetorialId.ping(a)
                val codigo = r.json.optString("codigo")
                when {
                    r.status == 409 && codigo == "limite_simultaneo" -> h.post { a.mostrarConflitoDeSessao(r.json.optJSONArray("ativas").itens()) }
                    VetorialId.sessaoPerdida(r.status, codigo) -> {
                        VetorialId.limpar(a, "Esta sessão foi encerrada em outro aparelho. Entre de novo.")
                        h.post { if (!a.isFinishing) { a.aviso("Sua sessão foi encerrada em outro aparelho."); a.ajustes?.atualizar() } }
                    }
                    r.ok -> {
                        VetorialId.guardarSelo(a, r.json.optString("selo"))
                        if (r.json.has("admin")) VetorialId.guardarAdmin(a, r.json.optBoolean("admin", false))
                        if (r.json.optLong("perfil") != VetorialId.perfilVersao(a) && VetorialId.atualizarPerfil(a)) h.post { a.gaveta?.atualizarPerfil() }
                        SincroniaId.rodar(a)
                    }
                }
            } catch (e: Exception) {
                // sem conexão: tenta de novo no próximo minuto
            }
        }.start()
    }
}

/** Tema (orbe, ícones, cor), preferências e o cadastro da empresa iguais em todos os aparelhos da conta. */
internal object SincroniaId {
    private fun versoes(a: MainActivity) = try { JSONObject(a.prefs.getString(VetorialId.PREF_VERSOES, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun enviados(a: MainActivity) = try { JSONObject(a.prefs.getString(VetorialId.PREF_ENVIADO, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun rodar(a: MainActivity) {
        val ver = versoes(a); val env = enviados(a)
        var desde = a.prefs.getLong("vid_desde", 0L)
        val perfilLocal = a.getSharedPreferences("perfil", android.content.Context.MODE_PRIVATE)
        if (VetorialId.cadastroPerdido(perfilLocal.contains("dono") || perfilLocal.contains("regras_json"), ver.optInt("empresa", 0))) {
            ver.remove("empresa"); env.remove("empresa"); desde = 0L // reinstalou e o Android só devolveu as versões: busca o cadastro da conta de novo
        }
        val r = Nuvem.bruto(a, "GET", "/id/sync?desde=$desde", null, VetorialId.token(a))
        if (!r.ok) return
        for (doc in r.json.optJSONArray("docs").itens()) {
            val chave = doc.optString("chave"); val v = doc.optInt("versao")
            if (!VetorialId.deveAplicar(v, ver.optInt(chave, 0))) continue
            ver.put(chave, v)
            val valor = doc.optJSONObject("valor")
            if (chave == "tema") VetorialId.temaDoJson(valor)?.let { (m, i, cor) ->
                a.prefs.edit().putInt(Tema.PREF_MODELO, m).putInt(Tema.PREF_ICONES, i).putInt(Tema.PREF_COR, cor).apply()
                env.put("tema", VetorialId.temaParaJson(m, i, cor).toString())
                Handler(Looper.getMainLooper()).post { a.aplicarTema() }
            }
            if (chave == "empresa" && valor != null) {
                Perfil.aplicarSincronia(a, valor)
                Perfil.paraSincronia(a)?.let { env.put("empresa", it.toString()) }
            }
            if (chave == SincroniaMarketing.IDEIAS) SincroniaMarketing.aplicarIdeias(a, valor)
            if (chave == "preferencias") VetorialId.lerPreferencias(valor)?.let { (modo, nome, falar) ->
                a.prefs.edit().putString(VetorialId.PREF_MODO, modo).putBoolean(VigiliaService.PREF_NOME, nome).putBoolean(MainActivity.PREF_FALAR, falar)
                    .putString(VetorialId.PREF_DOC_PREFS, valor.toString()).apply()
                env.put("preferencias", prefsAtuais(a).toString())
            }
        }
        a.prefs.edit().putLong("vid_desde", (r.json.optLong("agora") - 60_000).coerceAtLeast(0)).apply()
        // enviar o que mudou aqui
        val tema = VetorialId.temaParaJson(a.prefs.getInt(Tema.PREF_MODELO, 0), a.prefs.getInt(Tema.PREF_ICONES, 0), Tema.cor(a.prefs))
        enviar(a, "tema", tema, ver, env)
        enviar(a, "preferencias", prefsAtuais(a), ver, env)
        Perfil.paraSincronia(a)?.let { enviar(a, "empresa", it, ver, env) }
        SincroniaMarketing.enviarTudo(a, ver, env)
        a.prefs.edit().putString(VetorialId.PREF_VERSOES, ver.toString()).putString(VetorialId.PREF_ENVIADO, env.toString()).apply()
    }

    private fun prefsAtuais(a: MainActivity): JSONObject {
        val base = try { JSONObject(a.prefs.getString(VetorialId.PREF_DOC_PREFS, null) ?: "{}") } catch (e: Exception) { JSONObject() }
        return VetorialId.mesclarPreferencias(base, VetorialId.modo(a), a.prefs.getBoolean(VigiliaService.PREF_NOME, true), a.prefs.getBoolean(MainActivity.PREF_FALAR, true))
    }

    internal fun enviar(a: MainActivity, chave: String, valor: JSONObject, ver: JSONObject, env: JSONObject) {
        if (env.optString(chave) == valor.toString()) return
        val r = Nuvem.bruto(a, "PUT", "/id/sync/$chave", JSONObject().put("valor", valor).put("versao", ver.optInt(chave, 0)), VetorialId.token(a))
        if (r.ok) { ver.put(chave, r.json.optInt("versao")); env.put(chave, valor.toString()) }
        else if (r.status == 409) ver.put(chave, (r.json.optInt("versao") - 1).coerceAtLeast(0)) // o próximo ciclo aplica o que veio do outro aparelho
    }
}
