package com.tonimarques.sextafeira

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O estudo diário da Vênus, em segundo plano (mesmo com o app fechado, uma vez por dia, com internet):
 *  1. passa o banco antigo de texto para a memória vetorial e completa os vetores que faltam;
 *  2. gera 50 jeitos diferentes de pedir as coisas que ela sabe fazer (sinônimos, gírias, frases indiretas)
 *     e guarda cada um ligado à ação certa: é isso que faz ela entender quando você fala diferente;
 *  3. gera 50 charadas e problemas de lógica, resolve cada um de novo, sem ver a resposta, e só guarda os que
 *     as duas soluções concordam (o resto é descartado, para não aprender errado).
 * Tudo vai para o SQLite com o vetor de cada texto.
 */
object Estudo {

    const val FRASES_POR_DIA = 50
    const val ENIGMAS_POR_DIA = 50
    private const val PREFS = "estudo"

    data class Resumo(val data: String, val frases: Int, val enigmas: Int, val descartados: Int, val vetores: Int, val erro: String? = null)

    private fun hoje() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun ligado(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ligado", true)

    fun definirLigado(ctx: Context, ligado: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ligado", ligado).apply()
        if (ligado) EstudoDiario.agendar(ctx) else EstudoDiario.cancelar(ctx)
    }

    fun ultimo(ctx: Context): Resumo? {
        val o = try {
            JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ultimo", null) ?: return null)
        } catch (e: Exception) {
            return null
        }
        return Resumo(o.optString("data"), o.optInt("frases"), o.optInt("enigmas"), o.optInt("descartados"), o.optInt("vetores"),
            o.optString("erro").ifBlank { null })
    }

    /** Estuda (se ainda não estudou hoje, ou se [forcar]). Bloqueante: chame em segundo plano. */
    fun estudar(ctx: Context, forcar: Boolean = false): Resumo? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!forcar && (prefs.getString("dia", "") == hoje() || !ligado(ctx))) return null
        val config = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val chave = config.getString("chave", "").orEmpty()
        val modelo = config.getString("modelo", "").orEmpty().ifBlank { "gemini-flash-latest" }
        if (chave.isBlank()) return null
        val memoria = Memoria(ctx)
        var frases = 0
        var enigmas = 0
        var descartados = 0
        var erro: String? = null
        try {
            Semantica.migrarSePreciso(ctx, memoria)
            Semantica.completar(memoria, chave, 300)

            // 2) jeitos novos de pedir as mesmas coisas
            Gemini.gerarJson(chave, modelo, pedidoDeFrases(memoria.ultimosTextos("comando", 40)))?.let { json ->
                for ((frase, rota) in lerFrases(json)) {
                    if (memoria.guardarVetor("comando", frase, acao = rota.acao, args = JSONObject(rota.args).toString(), fonte = "estudo")) frases++
                }
            }

            // 3) charadas: cria, resolve de novo sem ver a resposta e guarda só as que batem
            val propostos = Gemini.gerarJson(chave, modelo, pedidoDeEnigmas(memoria.ultimosTextos("enigma", 60)))?.let { lerEnigmas(it) }.orEmpty()
            if (propostos.isNotEmpty()) {
                val resolvidos = Gemini.gerarJson(chave, modelo, pedidoDeSolucao(propostos.map { it.pergunta }), pensar = 4096, temperatura = 0.2)
                    ?.let { lerSolucoes(it) }.orEmpty()
                propostos.forEachIndexed { i, e ->
                    val minha = resolvidos.getOrNull(i)
                    if (minha != null && mesmaResposta(e.resposta, minha)) {
                        val texto = e.resposta.trim().trimEnd('.') + ". " + e.explicacao.trim()
                        if (memoria.guardarVetor("enigma", e.pergunta, resposta = texto.trim(), fonte = "estudo")) enigmas++
                    } else descartados++
                }
            }
        } catch (e: Exception) {
            erro = e.message ?: e.javaClass.simpleName
        }
        val vetores = try {
            Semantica.completar(memoria, chave, 400)
        } catch (e: Exception) {
            0
        }
        val r = Resumo(hoje(), frases, enigmas, descartados, vetores, erro)
        prefs.edit()
            .putString("dia", if (frases + enigmas > 0) hoje() else prefs.getString("dia", ""))
            .putString("ultimo", JSONObject().put("data", r.data).put("frases", frases).put("enigmas", enigmas)
                .put("descartados", descartados).put("vetores", vetores).put("erro", erro ?: "").toString())
            .apply()
        return r
    }

    /** "Hoje estudei: 50 frases novas, 46 charadas (4 descartadas). Memória: 1.240 itens." */
    fun descrever(r: Resumo?, contagem: Map<String, Pair<Int, Int>>): String {
        val total = contagem.values.sumOf { it.first }
        val comVetor = contagem.values.sumOf { it.second }
        val memoria = "Memória vetorial: $total itens ($comVetor com vetor), sendo " +
            listOf("comando" to "jeitos de pedir", "enigma" to "charadas", "resposta" to "respostas", "fato" to "fatos sobre você",
                "intencao" to "frases do vocabulário", "conversa" to "conversas")
                .mapNotNull { (k, n) -> contagem[k]?.first?.takeIf { it > 0 }?.let { "$it $n" } }.joinToString(", ") + "."
        if (r == null) return "Ainda não fiz o estudo diário. $memoria"
        val quando = if (r.data == hoje()) "Hoje" else "No último estudo (${r.data.split("-").reversed().take(2).joinToString("/")})"
        val falha = if (r.erro != null && r.frases + r.enigmas == 0) " Não consegui estudar: ${r.erro}." else ""
        return "$quando aprendi ${r.frases} jeitos novos de pedir as coisas e ${r.enigmas} charadas" +
            (if (r.descartados > 0) " (descartei ${r.descartados} em que minha segunda solução não bateu)" else "") + ".$falha $memoria"
    }

    // ------------------------------------------------------------------ pedidos ao Gemini
    fun pedidoDeFrases(jaConhecidas: List<String>): String =
        "Você treina a Vênus, assistente de voz em português do Brasil do Toni, dono da loja online Desejo de Intimidade. " +
            "Ações que ela executa (nome: para que serve | argumentos):\n" +
            Roteador.ACOES.joinToString("\n") { "- ${it.first}: ${it.second}" + if (it.third.isNotBlank()) " | ${it.third}" else "" } +
            "\n\nEscreva exatamente $FRASES_POR_DIA frases DIFERENTES que um brasileiro falaria de verdade para pedir essas ações: " +
            "sinônimos, gírias, frases indiretas, perguntas, jeitos curtos e longos, como se fala no WhatsApp ou por voz. " +
            "Use pelo menos 25 ações diferentes. Em cada frase, dê a ação e só os argumentos que a frase disser ({} se nenhum). " +
            (if (jaConhecidas.isNotEmpty()) "Não repita estas: ${jaConhecidas.take(40).joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"frases\":[{\"frase\":\"...\",\"acao\":\"nome\",\"args\":{}}]}"

    fun pedidoDeEnigmas(jaConhecidos: List<String>): String =
        "Crie exatamente $ENIGMAS_POR_DIA charadas, enigmas e problemas curtos de lógica em português do Brasil, bem variados: " +
            "o que é o que é, adivinhas, sequências de números ou letras, quem mente e quem fala a verdade, idades, contas do dia a dia " +
            "com pegadinha, relações de parentesco e lógica de comércio (preço, troco, desconto). Cada um com resposta curta e certa " +
            "e uma explicação de uma frase. Evite os famosos demais e não repita estes: " +
            jaConhecidos.take(60).joinToString(" | ").ifBlank { "(nenhum ainda)" } +
            ". Responda só com JSON: {\"enigmas\":[{\"pergunta\":\"...\",\"resposta\":\"...\",\"explicacao\":\"...\"}]}"

    fun pedidoDeSolucao(perguntas: List<String>): String =
        "Resolva cada enigma abaixo sozinho, pensando com cuidado, conferindo as contas e desconfiando de pegadinhas. " +
            "Responda só com JSON, na mesma ordem e com a mesma quantidade: {\"respostas\":[\"resposta curta 1\", ...]}\n" +
            perguntas.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n")

    // ------------------------------------------------------------------ leitura das respostas
    data class Enigma(val pergunta: String, val resposta: String, val explicacao: String)

    private fun objeto(json: String): JSONObject? = try {
        JSONObject(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1))
    } catch (e: Exception) {
        null
    }

    /** Só ficam as frases cuja ação existe e cujos argumentos fazem sentido. */
    fun lerFrases(json: String): List<Pair<String, Roteador.Rota>> {
        val a = objeto(json)?.optJSONArray("frases") ?: return emptyList()
        val vistas = mutableSetOf<String>()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val frase = o.optString("frase").trim().take(200)
            if (frase.split(" ").size < 2 || !vistas.add(ComandosLocais.normalizar(frase))) return@mapNotNull null
            val rota = Roteador.lerRota(JSONObject().put("acao", o.optString("acao")).put("args", o.optJSONObject("args") ?: JSONObject()))
                ?: return@mapNotNull null
            frase to rota
        }
    }

    fun lerEnigmas(json: String): List<Enigma> {
        val a = objeto(json)?.optJSONArray("enigmas") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optString("pergunta").trim()
            val r = o.optString("resposta").trim()
            if (p.length < 10 || r.isBlank()) null else Enigma(p.take(600), r.take(200), o.optString("explicacao").trim().take(400))
        }
    }

    fun lerSolucoes(json: String): List<String> {
        val a: JSONArray = objeto(json)?.optJSONArray("respostas") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }
    }

    private val ARTIGOS = setOf("o", "a", "os", "as", "um", "uma", "de", "do", "da", "e", "é", "sao", "são", "que", "resposta")

    private fun palavras(s: String) = ComandosLocais.normalizar(s).replace(Regex("[^a-z0-9 ]"), " ").split(" ")
        .filter { it.isNotBlank() && it !in ARTIGOS }

    /**
     * As duas respostas dizem a mesma coisa? Iguais, uma contida na outra, os mesmos números,
     * ou a maior parte das palavras em comum.
     */
    fun mesmaResposta(a: String, b: String): Boolean {
        val pa = palavras(a)
        val pb = palavras(b)
        if (pa.isEmpty() || pb.isEmpty()) return false
        val ja = pa.joinToString(" ")
        val jb = pb.joinToString(" ")
        if (ja == jb) return true
        val na = Regex("\\d+(?:[.,]\\d+)?").findAll(ja).map { it.value.replace(',', '.') }.toList()
        val nb = Regex("\\d+(?:[.,]\\d+)?").findAll(jb).map { it.value.replace(',', '.') }.toList()
        if (na.isNotEmpty() || nb.isNotEmpty()) return na.isNotEmpty() && na.toSet() == nb.toSet()
        if ((ja.length >= 3 && " $jb ".contains(" $ja ")) || (jb.length >= 3 && " $ja ".contains(" $jb "))) return true
        val comuns = pa.toSet().intersect(pb.toSet()).size
        return comuns * 2.0 / (pa.toSet().size + pb.toSet().size) >= 0.6
    }
}

/** O trabalho em segundo plano que chama o estudo (o Android escolhe a hora, com internet e bateria boa). */
class EstudoDiario : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Estudo.estudar(applicationContext)
            } catch (e: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 73_100

        /** Confere a cada 6 horas; estuda uma vez por dia. */
        fun agendar(ctx: Context) {
            if (!Estudo.ligado(ctx)) return
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, EstudoDiario::class.java))
                    .setPeriodic(6 * 3600_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancelar(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
