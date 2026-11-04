package com.tonimarques.sextafeira

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A "caixa-preta" da Vênus: anota sozinha o que deu errado (travamentos, erros de ações, falhas da IA,
 * frases que ela não entendeu, respostas lentas e quando você diz "não foi isso") e, de tempos em tempos,
 * manda um relatório para o repositório do app no GitHub (aba Issues). Assim, quando você pede uma
 * atualização, o Claude lê esses relatórios e corrige o que apareceu.
 *
 * Números longos (telefone, CPF, cartão) e e-mails são apagados antes de guardar.
 */
object Registro {

    data class Ocorrencia(
        val tipo: String, val detalhe: String, val frase: String, val vezes: Int, val primeira: Long, val ultima: Long,
        val versao: String,
        /** aberto (só no celular) → enviado (já está no GitHub); some do log quando é consertado. */
        val status: String = "aberto",
    ) {
        val chave get() = assinatura(tipo, if (tipo == "nao_entendi" || tipo == "corrigido") frase else detalhe)
    }

    /** Uma linha de assets/erros_consertados.txt: o começo da assinatura do erro e a versão que consertou. */
    data class Conserto(val prefixo: String, val versao: Int, val descricao: String)

    private const val PREFS = "registro_erros"
    private const val LIMITE = 200
    const val TITULO = "[Vênus] Relatório automático de erros"

    val NOMES = mapOf(
        "falha" to "Travamentos (o app fechou)",
        "erro" to "Erros ao executar uma ação",
        "ia" to "Falhas da IA (Gemini)",
        "nao_entendi" to "Frases que ela não entendeu",
        "corrigido" to "Quando você disse que ela errou",
        "lento" to "Respostas lentas",
    )

    // ================================================================== anotar
    /** Tira dados pessoais: números longos, e-mails e links com parâmetros. */
    fun limpar(texto: String): String = texto
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "[e-mail]")
        .replace(Regex("(?:\\d[\\s.()-]?){8,}"), "[número] ")
        .replace(Regex("(https?://[^\\s?]+)\\?\\S*"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(400)

    /** Mesma ocorrência = mesmo tipo e mesmo detalhe (sem números), para contar repetições em vez de duplicar. */
    fun assinatura(tipo: String, detalhe: String): String =
        tipo + ":" + ComandosLocais.normalizar(detalhe).replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").take(160)

    fun anotar(ctx: Context, tipo: String, detalhe: String, frase: String = "") {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lista = ler(prefs.getString("pendentes", "[]").orEmpty()).toMutableList()
            val d = limpar(detalhe)
            val f = limpar(frase)
            val chave = assinatura(tipo, if (tipo == "nao_entendi" || tipo == "corrigido") f else d)
            val agora = System.currentTimeMillis()
            val i = lista.indexOfFirst { assinatura(it.tipo, if (it.tipo == "nao_entendi" || it.tipo == "corrigido") it.frase else it.detalhe) == chave }
            if (i >= 0) {
                val o = lista[i]
                lista[i] = o.copy(vezes = o.vezes + 1, ultima = agora, frase = f.ifBlank { o.frase })
            } else {
                lista += Ocorrencia(tipo, d, f, 1, agora, agora, versao(ctx))
            }
            while (lista.size > LIMITE) lista.removeAt(0)
            // o log (o que está em aberto, mesmo depois de enviado) acompanha
            val log = ler(prefs.getString("historico", "[]").orEmpty()).toMutableList()
            val j = log.indexOfFirst { it.chave == chave }
            if (j >= 0) log[j] = log[j].copy(vezes = log[j].vezes + 1, ultima = agora, versao = versao(ctx))
            else log += Ocorrencia(tipo, d, f, 1, agora, agora, versao(ctx))
            while (log.size > LIMITE) log.removeAt(0)
            prefs.edit().putString("pendentes", gravar(lista)).putString("historico", gravar(log)).apply()
        } catch (e: Exception) {
        }
    }

    /** O log de erros: tudo que ainda não foi consertado (aberto ou já enviado), do mais recente ao mais antigo. */
    fun log(ctx: Context): List<Ocorrencia> =
        ler(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("historico", "[]").orEmpty()).sortedByDescending { it.ultima }

    /** Limpa o log inteiro (e o que esperava envio). */
    fun limparTudo(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pendentes", "[]").putString("historico", "[]").apply()
    }

    // ================================================================== erros consertados
    /**
     * Lê assets/erros_consertados.txt (uma linha por conserto: "começo da assinatura | versão | descrição").
     * Quem corrige um erro (o Claude, ao ler o relatório) acrescenta a linha na mesma atualização.
     */
    fun lerConsertos(texto: String): List<Conserto> = texto.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { linha ->
            val partes = linha.split("|").map { it.trim() }
            val versao = partes.getOrNull(1)?.removePrefix("v")?.removePrefix("1.")?.toIntOrNull() ?: return@mapNotNull null
            Conserto(partes[0].lowercase(), versao, partes.getOrNull(2).orEmpty())
        }.toList()

    /** "1.107" → 107 (0 se não der para ler). */
    fun numeroDaVersao(v: String): Int = v.removePrefix("v").substringAfter("1.").substringBefore(" ").toIntOrNull() ?: 0

    /** As ocorrências que um conserto já resolveu: mesma assinatura e anotadas numa versão anterior ao conserto. */
    fun resolvidas(lista: List<Ocorrencia>, consertos: List<Conserto>): Map<Conserto, List<Ocorrencia>> {
        val mapa = linkedMapOf<Conserto, MutableList<Ocorrencia>>()
        for (o in lista) {
            val c = consertos.firstOrNull { o.chave.startsWith(it.prefixo) && numeroDaVersao(o.versao) < it.versao } ?: continue
            mapa.getOrPut(c) { mutableListOf() } += o
        }
        return mapa
    }

    /**
     * Depois de atualizar o app: tira do log (e do que esperava envio) os erros já consertados nesta versão
     * e anota no diário o que foi consertado. Se o erro voltar na versão nova, ele reaparece normalmente.
     */
    fun limparConsertados(ctx: Context): Int {
        return try {
            val consertos = lerConsertos(ctx.assets.open("erros_consertados.txt").bufferedReader().use { it.readText() })
            if (consertos.isEmpty()) return 0
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val log = ler(prefs.getString("historico", "[]").orEmpty())
            val pendentes = ler(prefs.getString("pendentes", "[]").orEmpty())
            val achados = resolvidas(log + pendentes, consertos)
            if (achados.isEmpty()) return 0
            val sair = achados.values.flatten().map { it.chave }.toSet()
            prefs.edit()
                .putString("historico", gravar(log.filter { it.chave !in sair }))
                .putString("pendentes", gravar(pendentes.filter { it.chave !in sair }))
                .apply()
            for ((c, os) in achados) {
                val vezes = os.distinctBy { it.chave }.sumOf { it.vezes }
                Diario.anotar(ctx, "erro", "Consertado na versão 1.${c.versao}: ${c.descricao.ifBlank { os.first().detalhe.take(80) }} ($vezes ${if (vezes == 1) "ocorrência" else "ocorrências"} saíram do log)", "atualizacao")
            }
            sair.size
        } catch (e: Exception) {
            0
        }
    }

    fun pendentes(ctx: Context): List<Ocorrencia> =
        ler(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pendentes", "[]").orEmpty())

    fun ler(json: String): List<Ocorrencia> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Ocorrencia(it.optString("tipo"), it.optString("detalhe"), it.optString("frase"), it.optInt("vezes", 1),
                it.optLong("primeira"), it.optLong("ultima"), it.optString("versao"), it.optString("status", "aberto"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun gravar(lista: List<Ocorrencia>): String {
        val a = JSONArray()
        for (o in lista) a.put(
            JSONObject().put("tipo", o.tipo).put("detalhe", o.detalhe).put("frase", o.frase).put("vezes", o.vezes)
                .put("primeira", o.primeira).put("ultima", o.ultima).put("versao", o.versao).put("status", o.status)
        )
        return a.toString()
    }

    private fun versao(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        "?"
    }

    // ================================================================== travamentos
    /** Grava o motivo se o app fechar sozinho (fica num arquivo e entra no próximo relatório). */
    fun instalar(ctx: Context) {
        val app = ctx.applicationContext
        // atualizou o app: o que foi consertado sai do log
        Thread { limparConsertados(app) }.start()
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        if (anterior is Guardiao) return
        Thread.setDefaultUncaughtExceptionHandler(Guardiao(app, anterior))
        // travamento da última vez: passa do arquivo para a lista
        try {
            val f = arquivoDeFalha(app)
            if (f.exists()) {
                val texto = f.readText()
                f.delete()
                anotar(app, "falha", texto.lineSequence().first(), texto.lineSequence().drop(1).joinToString(" | ").take(400))
            }
        } catch (e: Exception) {
        }
    }

    private fun arquivoDeFalha(ctx: Context) = File(ctx.filesDir, "ultima_falha.txt")

    private class Guardiao(private val ctx: Context, private val anterior: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                arquivoDeFalha(ctx).writeText(resumoDoErro(e))
            } catch (x: Throwable) {
            }
            anterior?.uncaughtException(t, e)
        }
    }

    /** "IllegalStateException: mensagem" + os pontos do código da Vênus onde aconteceu. */
    fun resumoDoErro(e: Throwable): String {
        val causa = generateSequence(e) { it.cause }.last()
        val linhas = (e.stackTrace + causa.stackTrace).filter { it.className.startsWith("com.tonimarques") }
            .distinct().take(6).map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return "${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}" +
            (if (causa !== e) " (causa: ${causa.javaClass.simpleName}: ${causa.message.orEmpty().take(120)})" else "") +
            "\n" + linhas.joinToString("\n")
    }

    // ================================================================== relatório
    fun relatorio(lista: List<Ocorrencia>, aparelho: String): String {
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
        val sb = StringBuilder()
        sb.append("Relatório gerado pela própria Vênus. Aparelho: $aparelho.\n")
        sb.append("Versões: ${lista.map { it.versao }.distinct().joinToString(", ")}.\n")
        for ((tipo, nome) in NOMES) {
            val doTipo = lista.filter { it.tipo == tipo }.sortedByDescending { it.vezes }
            if (doTipo.isEmpty()) continue
            sb.append("\n### $nome (${doTipo.sumOf { it.vezes }})\n")
            for (o in doTipo.take(40)) {
                sb.append("- ")
                if (o.vezes > 1) sb.append("**${o.vezes}x** ")
                if (o.frase.isNotBlank()) sb.append("“${o.frase}”")
                if (o.detalhe.isNotBlank()) sb.append(if (o.frase.isNotBlank()) " → `${o.detalhe}`" else "`${o.detalhe}`")
                sb.append(" (${fmt.format(Date(o.ultima))}, v${o.versao})\n")
            }
        }
        return sb.toString().take(60_000)
    }

    /** Hora de mandar? Teve travamento, ou há ocorrências e o último envio foi há mais de 12 horas. */
    fun deveEnviar(ctx: Context): Boolean {
        val lista = pendentes(ctx)
        if (lista.isEmpty()) return false
        val ultimo = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("ultimo_envio", 0)
        return lista.any { it.tipo == "falha" } || System.currentTimeMillis() - ultimo > 12 * 3600_000L
    }

    /**
     * Abre uma "issue" no GitHub com o relatório. Roda fora da thread principal.
     * Devolve null se deu certo, ou a explicação do problema.
     */
    fun enviar(ctx: Context, token: String?): String? {
        val lista = pendentes(ctx)
        if (lista.isEmpty()) return null
        if (token.isNullOrBlank()) return "Para mandar os relatórios, cadastre o token do GitHub nos ajustes (Atualizações)."
        val aparelho = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        val total = lista.sumOf { it.vezes }
        val corpo = JSONObject()
            .put("title", "$TITULO — ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())} ($total)")
            .put("body", relatorio(lista, aparelho))
        val c = URL("https://api.github.com/repos/${Atualizador.REPO}/issues").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("User-Agent", "Venus-Android")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            when (c.responseCode) {
                in 200..299 -> {
                    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val enviados = lista.map { it.chave }.toSet()
                    val log = ler(prefs.getString("historico", "[]").orEmpty()).map { if (it.chave in enviados) it.copy(status = "enviado") else it }
                    prefs.edit().putString("pendentes", "[]").putString("historico", gravar(log))
                        .putLong("ultimo_envio", System.currentTimeMillis()).apply()
                    Diario.anotar(ctx, "erro", "Relatório com ${lista.sumOf { it.vezes }} ocorrências enviado ao GitHub.", "automatico")
                    null
                }
                401 -> "O token do GitHub não vale mais. Cadastre um novo nos ajustes."
                403, 404 -> "O token do GitHub não tem permissão para relatórios. No GitHub, edite o token e libere \"Issues: Read and write\" para o repositório da Vênus."
                else -> "O GitHub não aceitou o relatório (erro ${c.responseCode})."
            }
        } catch (e: Exception) {
            "Sem internet para mandar o relatório agora."
        } finally {
            c.disconnect()
        }
    }

    /** "Não foi isso", "você entendeu errado": a resposta anterior estava errada. */
    private val CORRECAO = Regex(
        "^(?:nao|não)?[, ]*(?:nao (?:foi|era|e|eh) (?:isso|isto|o que eu (?:pedi|falei|perguntei|quis dizer))|voce (?:nao entendeu|entendeu errado|errou|se enganou)|" +
            "(?:ta|esta|isso esta|isso ta) errad[oa]|resposta errada|errado|errou|nao foi o que eu pedi|nao e o que eu pedi|nao entendeu nada)(?: [a-z ]{0,30})?$"
    )

    fun ehCorrecao(frase: String): Boolean = CORRECAO.matches(ComandosLocais.normalizar(frase).trim().trimEnd('.', '!', '?'))
}
