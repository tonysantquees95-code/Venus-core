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
 * O estudo diário da Vênus, em segundo plano (mesmo com o app fechado, com internet e bateria boa).
 * Roda em etapas: cada uma fica marcada quando termina e, se o Android interromper, a próxima rodada continua dali.
 *  - preparar: passa o banco antigo para vetores, completa os que faltam e compacta os vetores antigos;
 *  - frases: 50 jeitos novos de pedir as coisas que ela sabe fazer;
 *  - enigmas: 50 charadas, resolvidas de novo sem ver a resposta (só ficam as que batem);
 *  - clientes: 100 perguntas de clientes sobre o catálogo e a compra, com resposta conferida contra os dados da loja;
 *  - revisao: até 30 coisas que ela não entendeu ou errou nas conversas do dia, já com o jeito certo;
 *  - voz: 30 frases como o reconhecimento de voz e o teclado erram, gírias gaúchas e abreviações;
 *  - licoes: até 5 lições tiradas dos próprios erros (conversas e log de erros), que vão nas instruções dela;
 *  - objecoes: 20 respostas acolhedoras para "tá caro", "tenho vergonha", "e se alguém vir?";
 *  - saberes: 12 perguntas e respostas conferidas de uma área por dia, em rodízio (marketing, finanças, direito, tecnologia…);
 *  - ideias (1 vez por semana): 10 ideias de post para as próximas datas;
 *  - limpeza (1 vez por semana): tira o que o estudo criou e nunca foi usado em 60 dias.
 * Antes de guardar, cada item é comparado pelo sentido com o que ela já sabe: quase igual (≥ 0,95) é descartado.
 */
object Estudo {

    const val FRASES_POR_DIA = 50
    const val ENIGMAS_POR_DIA = 50
    const val CLIENTES_POR_DIA = 100
    const val REVISAO_MAXIMO = 30
    const val VOZ_POR_DIA = 30
    const val OBJECOES_POR_DIA = 20
    const val IDEIAS_POR_SEMANA = 10
    const val LICOES_MAXIMO = 5
    const val LICOES_GUARDADAS = 30
    const val SABERES_POR_DIA = 12
    const val TIPO_SABER = "saber"

    /** Uma área por dia, em rodízio (os saberes que ela estuda além da loja). */
    val DISCIPLINAS = listOf(
        "marketing digital e Instagram para pequenas lojas",
        "atendimento e vendas por WhatsApp e Direct",
        "finanças de pequeno negócio: preço, margem, fluxo de caixa e estoque",
        "direito do consumidor e comércio eletrônico no Brasil",
        "educação sexual e bem-estar íntimo (informativo, sem orientação médica individual)",
        "psicologia do consumidor e comportamento de compra",
        "logística, embalagem discreta e envio no Brasil",
        "hotelaria e atendimento ao hóspede",
        "tecnologia: Android, aplicativos e segurança digital",
        "comunicação, escrita persuasiva e negociação",
        "produtividade, organização e rotina de quem trabalha e empreende",
        "cultura, história e expressões do Rio Grande do Sul",
    )

    fun disciplinaDoDia(diaDoAno: Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)): String =
        DISCIPLINAS[Math.floorMod(diaDoAno, DISCIPLINAS.size)]
    /** Nota de sentido a partir da qual um item novo é "repetido" e não entra. */
    const val LIMIAR_REPETIDO = 0.95
    private const val PREFS = "estudo"

    val ETAPAS = listOf("preparar", "frases", "enigmas", "clientes", "revisao", "licoes", "voz", "objecoes", "saberes", "ideias", "limpeza")
    private val SEMANAIS = setOf("ideias", "limpeza")
    val NOMES = linkedMapOf(
        "frases" to "jeitos de pedir", "enigmas" to "charadas", "clientes" to "respostas para clientes",
        "revisao" to "correções das conversas", "voz" to "frases com erro de voz e digitação",
        "objecoes" to "respostas a objeções de venda", "ideias" to "ideias de post",
        "licoes" to "lições dos próprios erros", "saberes" to "saberes de outras áreas",
    )

    data class Resumo(
        val data: String, val itens: Map<String, Int>, val descartados: Int, val repetidos: Int, val vetores: Int,
        val removidos: Int = 0, val erro: String? = null, val faltam: List<String> = emptyList(),
    ) {
        val frases get() = itens["frases"] ?: 0
        val enigmas get() = itens["enigmas"] ?: 0
        val total get() = itens.values.sum()
    }

    private class Progresso(val o: JSONObject) {
        val data: String get() = o.optString("data")
        val feitas: MutableSet<String> = (o.optJSONArray("feitas") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toMutableSet() }
        fun somar(campo: String, n: Int) = o.put(campo, o.optInt(campo) + n)
        fun item(etapa: String, n: Int) {
            val itens = o.optJSONObject("itens") ?: JSONObject().also { o.put("itens", it) }
            itens.put(etapa, itens.optInt(etapa) + n)
        }
        fun salvar(prefs: android.content.SharedPreferences) {
            o.put("feitas", JSONArray(feitas.toList()))
            prefs.edit().putString("progresso", o.toString()).apply()
        }
        fun resumo(): Resumo {
            val itens = o.optJSONObject("itens") ?: JSONObject()
            return Resumo(
                data, itens.keys().asSequence().associateWith { itens.optInt(it) }, o.optInt("descartados"), o.optInt("repetidos"),
                o.optInt("vetores"), o.optInt("removidos"), o.optString("erro").ifBlank { null }, ETAPAS.filter { it !in feitas },
            )
        }
    }

    private val trava = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Está estudando agora (em segundo plano ou pelo pedido "estuda agora")? */
    fun ocupado() = trava.get()

    private fun hoje() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun ligado(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ligado", true)

    fun definirLigado(ctx: Context, ligado: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ligado", ligado).apply()
        if (ligado) EstudoDiario.agendar(ctx) else EstudoDiario.cancelar(ctx)
    }

    fun ultimo(ctx: Context): Resumo? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("progresso", null)?.let { t ->
            try {
                return Progresso(JSONObject(t)).resumo()
            } catch (e: Exception) {
            }
        }
        val o = try {
            JSONObject(prefs.getString("ultimo", null) ?: return null)
        } catch (e: Exception) {
            return null
        }
        return Resumo(o.optString("data"), mapOf("frases" to o.optInt("frases"), "enigmas" to o.optInt("enigmas")), o.optInt("descartados"), 0,
            o.optInt("vetores"), 0, o.optString("erro").ifBlank { null })
    }

    /**
     * Estuda as etapas que faltam hoje ([forcar]: começa o dia de novo). Bloqueante: chame em segundo plano.
     * [prazo]: não começa etapa nova depois desse horário; [parar]: o Android pediu para parar.
     */
    fun estudar(ctx: Context, forcar: Boolean = false, prazo: Long = Long.MAX_VALUE, parar: () -> Boolean = { false }): Resumo? {
        if (!forcar && !ligado(ctx)) return null
        val config = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val chave = Cofre.ler(config, "chave")
        val modelo = config.getString("modelo", "").orEmpty().ifBlank { "gemini-flash-latest" }
        if (chave.isBlank()) return null
        if (!trava.compareAndSet(false, true)) return null
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val anterior = try {
                Progresso(JSONObject(prefs.getString("progresso", "{}") ?: "{}"))
            } catch (e: Exception) {
                Progresso(JSONObject())
            }
            val prog = if (forcar || anterior.data != hoje()) Progresso(JSONObject().put("data", hoje())) else anterior
            if (!forcar && prog.feitas.containsAll(ETAPAS)) return null
            prog.o.put("erro", "")
            val memoria = Memoria(ctx)
            val estudo = Sessao(ctx, memoria, chave, modelo, prog)
            var rodou = false
            for (etapa in ETAPAS) {
                if (etapa in prog.feitas) continue
                if (System.currentTimeMillis() > prazo || parar()) break
                if (etapa in SEMANAIS && !forcar && System.currentTimeMillis() - prefs.getLong("semana_$etapa", 0) < 6 * 86_400_000L) {
                    prog.feitas += etapa
                    continue
                }
                try {
                    estudo.rodar(etapa)
                    prog.feitas += etapa
                    if (etapa in SEMANAIS) prefs.edit().putLong("semana_$etapa", System.currentTimeMillis()).apply()
                } catch (e: Exception) {
                    val antes = prog.o.optString("erro")
                    prog.o.put("erro", (if (antes.isBlank()) "" else "$antes; ") + "${NOMES[etapa] ?: etapa}: ${e.message ?: e.javaClass.simpleName}")
                }
                rodou = true
                prog.salvar(prefs)
            }
            if (rodou) {
                prog.somar("vetores", try {
                    Semantica.completar(memoria, chave, 600)
                } catch (e: Exception) {
                    0
                })
                prog.salvar(prefs)
            }
            val r = prog.resumo()
            if (rodou && (r.faltam.isEmpty() || forcar)) {
                Diario.anotar(
                    ctx, "estudo",
                    if (r.total == 0 && r.erro != null) "Estudo diário não aconteceu: ${r.erro}"
                    else "Estudo diário: " + NOMES.entries.mapNotNull { (k, n) -> r.itens[k]?.takeIf { it > 0 }?.let { "+$it $n" } }.joinToString(", ") +
                        " (${r.descartados} reprovados na conferência, ${r.repetidos} repetidos" +
                        (if (r.removidos > 0) ", ${r.removidos} antigos sem uso removidos" else "") + ")",
                    "automatico", ok = r.total > 0,
                )
                if (r.faltam.isEmpty()) prefs.edit().putString("dia", hoje()).apply()
            }
            return r
        } finally {
            trava.set(false)
        }
    }

    /** Um item para guardar (antes do filtro de repetição). */
    data class Novo(val tipo: String, val texto: String, val resposta: String? = null, val acao: String? = null, val args: String? = null, val fonte: String = "estudo")

    /** O trabalho de um dia de estudo. */
    private class Sessao(val ctx: Context, val memoria: Memoria, val chave: String, val modelo: String, val prog: Progresso) {

        fun rodar(etapa: String) {
            when (etapa) {
                "preparar" -> {
                    Semantica.migrarSePreciso(ctx, memoria)
                    RegrasDaLoja.semear(ctx, memoria)
                    produtosDaLoja()?.let { lista ->
                        val suspeitos = RegrasDaLoja.suspeitosDaAnvisa(lista.filter { it.ativo }.map { it.nome })
                        if (suspeitos.isNotEmpty()) Diario.anotar(
                            ctx, "loja",
                            "Atenção Anvisa: ${suspeitos.joinToString(", ")} tem nome de linha de gel proibida em 25/02/2026. Confira o fabricante.",
                            "automatico", ok = false,
                        )
                    }
                    prog.somar("removidos", Semantica.limparRespostasRuins(memoria))
                    while (memoria.compactarAntigos() > 0) Unit
                    prog.somar("vetores", Semantica.completar(memoria, chave, 300))
                }
                "frases" -> {
                    val json = Gemini.gerarJson(chave, modelo, pedidoDeFrases(memoria.ultimosTextos("comando", 40))) ?: throw falhou()
                    guardar(etapa, lerFrases(json).map { (f, r) -> Novo("comando", f, acao = r.acao, args = JSONObject(r.args).toString()) })
                }
                "voz" -> {
                    val json = Gemini.gerarJson(chave, modelo, pedidoDeVoz(memoria.ultimosTextos("comando", 40))) ?: throw falhou()
                    guardar(etapa, lerFrases(json).map { (f, r) -> Novo("comando", f, acao = r.acao, args = JSONObject(r.args).toString()) })
                }
                "enigmas" -> enigmas()
                "clientes" -> clientes()
                "revisao" -> revisao()
                "objecoes" -> {
                    val json = Gemini.gerarJson(chave, modelo, pedidoDeObjecoes(memoria.ultimosTextos("objecao", 60)), temperatura = 0.8) ?: throw falhou()
                    guardar(etapa, lerPares(json, "objecoes", "objecao").map { (o, r) -> Novo("objecao", o, resposta = r) })
                }
                "licoes" -> licoes()
                "saberes" -> saberes()
                "ideias" -> {
                    val agora = java.util.Calendar.getInstance()
                    val datas = Gestao.textoDatas(Gestao.proximasDatas(agora, 45), 45)
                    val nomes = produtosDaLoja()?.filter { it.ativo }?.take(60)?.joinToString(", ") { it.nome }.orEmpty()
                    val json = Gemini.gerarJson(chave, modelo, pedidoDeIdeias(datas, nomes, memoria.ultimosTextos("ideia_post", 20), hoje())) ?: throw falhou()
                    guardar(etapa, lerIdeias(json).map { Novo("ideia_post", it.first, resposta = it.second) }, filtrar = false)
                }
                "limpeza" -> prog.somar("removidos", memoria.limparEstudoVelho())
            }
        }

        private fun falhou() = IllegalStateException("o Gemini não respondeu agora")

        private fun produtosDaLoja(): List<Loja.Produto>? = try {
            Loja(ctx).takeIf { it.conectada }?.produtos()
        } catch (e: Exception) {
            null
        }

        private fun enigmas() {
            val propostos = Gemini.gerarJson(chave, modelo, pedidoDeEnigmas(memoria.ultimosTextos("enigma", 60)))?.let { lerEnigmas(it) } ?: throw falhou()
            if (propostos.isEmpty()) return
            val resolvidos = Gemini.gerarJson(chave, modelo, pedidoDeSolucao(propostos.map { it.pergunta }), pensar = 4096, temperatura = 0.2)
                ?.let { lerSolucoes(it) }.orEmpty()
            val bons = mutableListOf<Novo>()
            propostos.forEachIndexed { i, e ->
                val minha = resolvidos.getOrNull(i)
                if (minha != null && mesmaResposta(e.resposta, minha)) {
                    bons += Novo("enigma", e.pergunta, resposta = (e.resposta.trim().trimEnd('.') + ". " + e.explicacao.trim()).trim())
                } else prog.somar("descartados", 1)
            }
            guardar("enigmas", bons)
        }

        /** Perguntas de clientes em 2 lotes (produtos; compra e entrega), cada resposta conferida contra os dados da loja. */
        private fun clientes() {
            val produtos = produtosDaLoja() ?: return // loja não conectada: pula hoje
            val catalogo = Loja.catalogoParaIA(produtos)
            if (catalogo.isBlank()) return
            val ja = memoria.ultimosTextos("cliente", 80)
            for (foco in FOCOS_CLIENTE) {
                val json = Gemini.gerarJson(chave, modelo, pedidoDeClientes(catalogo, foco, ja), temperatura = 0.7) ?: throw falhou()
                val pares = lerPares(json, "perguntas", "pergunta")
                if (pares.isEmpty()) continue
                val notas = Gemini.gerarJson(chave, modelo, pedidoDeConferencia(catalogo, pares), pensar = 2048, temperatura = 0.1)
                    ?.let { lerAprovados(it, pares.size) } ?: throw falhou()
                val bons = pares.filterIndexed { i, _ -> notas[i] }
                prog.somar("descartados", pares.size - bons.size)
                guardar("clientes", bons.map { (p, r) -> Novo("cliente", p, resposta = r) })
            }
        }

        /** Reflexão: o que deu errado nas conversas e no log de erros vira lição curta, que ela passa a seguir. */
        private fun licoes() {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val desde = maxOf(prefs.getLong("licoes_desde", 0), System.currentTimeMillis() - 2 * 86_400_000L)
            val msgs = memoria.mensagensDesde(desde, 200)
            val erros = Registro.log(ctx).filter { it.ultima >= desde }.take(15)
            if (msgs.count { it.doUsuario } >= 2 || erros.isNotEmpty()) {
                val ja = memoria.ultimosTextos(Consciencia.TIPO_LICAO, LICOES_GUARDADAS)
                val json = Gemini.gerarJson(chave, modelo, pedidoDeLicoes(msgs, erros.map { "${it.tipo}: ${it.detalhe.ifBlank { it.frase }.take(160)} (${it.vezes}x)" }, ja),
                    pensar = 2048, temperatura = 0.3) ?: throw falhou()
                val novas = lerPares(json, "licoes", "licao").take(LICOES_MAXIMO)
                guardar("licoes", novas.map { (l, porque) -> Novo(Consciencia.TIPO_LICAO, l.take(220), resposta = porque.take(400), fonte = "reflexao") })
                memoria.manterUltimos(Consciencia.TIPO_LICAO, LICOES_GUARDADAS)
                Consciencia.esquecerRetrato()
            }
            prefs.edit().putLong("licoes_desde", System.currentTimeMillis()).apply()
        }

        /** Uma área por dia: perguntas e respostas atemporais, conferidas por uma segunda leitura antes de guardar. */
        private fun saberes() {
            val area = disciplinaDoDia()
            val ja = memoria.ultimosTextos(TIPO_SABER, 80)
            val json = Gemini.gerarJson(chave, modelo, pedidoDeSaberes(area, ja), temperatura = 0.7) ?: throw falhou()
            val pares = lerPares(json, "perguntas", "pergunta").take(SABERES_POR_DIA)
            if (pares.isEmpty()) return
            val notas = Gemini.gerarJson(chave, modelo, pedidoDeConferenciaDeSaberes(area, pares), pensar = 2048, temperatura = 0.1)
                ?.let { lerAprovados(it, pares.size) } ?: throw falhou()
            val bons = pares.filterIndexed { i, _ -> notas[i] }
            prog.somar("descartados", pares.size - bons.size)
            guardar("saberes", bons.map { (p, r) -> Novo(TIPO_SABER, p, resposta = r, fonte = "saber: $area".take(80)) })
        }

        /** Lê as conversas desde a última revisão e aprende o jeito certo do que deu errado. */
        private fun revisao() {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val desde = maxOf(prefs.getLong("revisao_desde", 0), System.currentTimeMillis() - 2 * 86_400_000L)
            val msgs = memoria.mensagensDesde(desde, 240)
            if (msgs.count { it.doUsuario } >= 2) {
                val json = Gemini.gerarJson(chave, modelo, pedidoDeRevisao(msgs), pensar = 2048, temperatura = 0.2) ?: throw falhou()
                val novos = lerRevisao(json).take(REVISAO_MAXIMO).mapNotNull { c ->
                    when {
                        c.rota != null -> Novo("comando", c.frase, acao = c.rota.acao, args = JSONObject(c.rota.args).toString(), fonte = "revisao")
                        c.resposta.isNotBlank() && Semantica.vale(c.frase, c.resposta) -> Novo("resposta", c.frase, resposta = c.resposta, fonte = "revisao")
                        else -> null
                    }
                }
                guardar("revisao", novos)
            }
            prefs.edit().putLong("revisao_desde", System.currentTimeMillis()).apply()
        }

        /** Gera os vetores de uma vez, descarta o que ela já sabe (ou que veio repetido no lote) e guarda o resto. */
        fun guardar(etapa: String, novos: List<Novo>, filtrar: Boolean = true) {
            if (novos.isEmpty()) return
            val vetores = EmbeddingsGemini.gerarVarios(novos.map { it.texto }, chave)
            val modeloVetor = EmbeddingsGemini.MODELO
            val aceitos = mutableListOf<FloatArray>()
            var guardados = 0
            var repetidos = 0
            novos.forEachIndexed { i, n ->
                val v = vetores?.getOrNull(i)
                if (filtrar && v != null && ehRepetido(v, n.tipo, aceitos, modeloVetor)) {
                    repetidos++
                    return@forEachIndexed
                }
                if (memoria.guardarVetor(n.tipo, n.texto, n.resposta, n.acao, n.args, n.fonte, v, if (v != null) modeloVetor else null)) {
                    guardados++
                    if (v != null) aceitos += v
                } else repetidos++
            }
            prog.item(etapa, guardados)
            prog.somar("repetidos", repetidos)
        }

        private fun ehRepetido(v: FloatArray, tipo: String, aceitos: List<FloatArray>, modeloVetor: String): Boolean {
            if (aceitos.any { Memoria.produtoInterno(it, v) >= LIMIAR_REPETIDO }) return true
            val tipos = if (tipo == "comando") setOf("comando", "intencao") else setOf(tipo)
            return memoria.buscarVetores(v, modeloVetor, tipos, minimo = LIMIAR_REPETIDO, quantos = 1).isNotEmpty()
        }
    }

    /** "Hoje aprendi: 48 jeitos de pedir, 92 respostas para clientes… Memória: 3.420 itens de até 60.000." */
    fun descrever(r: Resumo?, contagem: Map<String, Pair<Int, Int>>): String {
        val total = contagem.values.sumOf { it.first }
        val comVetor = contagem.values.sumOf { it.second }
        val memoria = "Memória vetorial: ${"%,d".format(total).replace(',', '.')} itens de até ${"%,d".format(Memoria.MAXIMO_ITENS).replace(',', '.')} " +
            "($comVetor com vetor), sendo " +
            listOf("comando" to "jeitos de pedir", "enigma" to "charadas", "cliente" to "respostas para clientes", "objecao" to "respostas a objeções",
                "resposta" to "respostas", "fato" to "fatos sobre você", "intencao" to "frases do vocabulário", "conversa" to "conversas",
                "ideia_post" to "ideias de post")
                .mapNotNull { (k, n) -> contagem[k]?.first?.takeIf { it > 0 }?.let { "$it $n" } }.joinToString(", ") + "."
        if (r == null) return "Ainda não fiz o estudo diário. $memoria"
        val quando = if (r.data == hoje()) "Hoje" else "No último estudo (${r.data.split("-").reversed().take(2).joinToString("/")})"
        val aprendi = NOMES.entries.mapNotNull { (k, n) -> r.itens[k]?.takeIf { it > 0 }?.let { "$it $n" } }
        val partes = mutableListOf<String>()
        partes += if (aprendi.isEmpty()) "$quando ainda não aprendi nada novo." else "$quando aprendi ${juntar(aprendi)}."
        if (r.descartados + r.repetidos > 0) {
            partes += "Descartei ${r.descartados} que não passaram na conferência e ${r.repetidos} que eu já sabia."
        }
        if (r.removidos > 0) partes += "Tirei ${r.removidos} itens antigos que nunca usei."
        val faltam = r.faltam.mapNotNull { NOMES[it] }
        if (r.data == hoje() && faltam.isNotEmpty()) partes += "Ainda falta estudar: ${juntar(faltam)} (continuo sozinha mais tarde)."
        if (r.erro != null) partes += "Problema: ${r.erro}."
        return partes.joinToString(" ") + " " + memoria
    }

    private fun juntar(l: List<String>) = if (l.size <= 1) l.joinToString("") else l.dropLast(1).joinToString(", ") + " e " + l.last()

    val FOCOS_CLIENTE = listOf(
        "sobre os produtos: para que serve, como usar, diferença entre dois produtos, qual indicar para iniciantes, para casal, " +
            "para presente, material, tamanho, limpeza, cuidados, se faz barulho, se é à prova d'água, se combina com lubrificante",
        "sobre comprar: como comprar no site, formas de pagamento, Pix, parcelamento, frete e prazo, se a embalagem é discreta, " +
            "o que aparece na fatura, sigilo, troca e devolução, produto esgotado, retirada, atendimento pelo WhatsApp",
    )

    // ------------------------------------------------------------------ pedidos ao Gemini
    fun pedidoDeFrases(jaConhecidas: List<String>): String =
        "Você treina a Vênus, assistente de voz em português do Brasil do ${Perfil.dono}, dono da loja online ${Perfil.loja}. " +
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

    fun pedidoDeVoz(jaConhecidas: List<String>): String =
        "Você treina a Vênus, assistente de voz do ${Perfil.dono} (${Perfil.cidade}, ${Perfil.uf}). Ações que ela executa:\n" +
            Roteador.ACOES.joinToString("\n") { "- ${it.first}: ${it.second}" + if (it.third.isNotBlank()) " | ${it.third}" else "" } +
            "\n\nEscreva exatamente $VOZ_POR_DIA pedidos para essas ações do jeito que chegam ERRADOS ou informais: " +
            "erros comuns do reconhecimento de voz em português (palavras trocadas por outras de som parecido, falta de acento, " +
            "palavras coladas ou cortadas), erros de digitação no celular, abreviações de WhatsApp (vc, q, pq, tb, msg, zap, insta) " +
            "e gírias gaúchas (bah, tchê, capaz, tri, guri, guria). Precisa continuar dando para entender o pedido. " +
            "Em cada um, a ação certa e só os argumentos ditos ({} se nenhum). " +
            (if (jaConhecidas.isNotEmpty()) "Não repita estas: ${jaConhecidas.take(40).joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"frases\":[{\"frase\":\"...\",\"acao\":\"nome\",\"args\":{}}]}"

    fun pedidoDeClientes(catalogo: String, foco: String, jaConhecidas: List<String>): String =
        "Você treina a atendente virtual da ${Perfil.loja}, sex shop online de ${Perfil.cidade} (site ${Loja.SITE}). " +
            "Dados da loja (produto | preço | preço no Pix | estoque | link):\n$catalogo\n" +
            "No Pix há 5% de desconto. Compras pelo site; atendimento também pelo WhatsApp.\n\n" +
            "Escreva exatamente ${CLIENTES_POR_DIA / 2} perguntas DIFERENTES que clientes mandam de verdade no WhatsApp ou no Instagram, $foco. " +
            "Varie o jeito (curtas, com erro de digitação, tímidas, diretas) e cite produtos reais da lista. " +
            "Para cada uma, a resposta ideal da loja: acolhedora, discreta, sem julgamento, em até 3 frases, no máximo 1 emoji. " +
            "Regras da resposta: use só o que está nos dados; NUNCA diga preço nem quantidade em estoque (mudam), mande o link do produto; " +
            "se a informação não estiver nos dados (prazo, frete, troca, medidas), diga com gentileza que a equipe confirma pelo WhatsApp, sem inventar. " +
            "Sem linguagem explícita. " +
            (if (jaConhecidas.isNotEmpty()) "Não repita estas perguntas: ${jaConhecidas.take(60).joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"perguntas\":[{\"pergunta\":\"...\",\"resposta\":\"...\"}]}"

    fun pedidoDeConferencia(catalogo: String, pares: List<Pair<String, String>>): String =
        "Você confere respostas de atendimento de uma loja. Dados verdadeiros da loja:\n$catalogo\nNo Pix há 5% de desconto.\n\n" +
            "Para cada par abaixo, aprove (true) só se a resposta: responde à pergunta; não inventa nada fora dos dados " +
            "(prazos, medidas, políticas, promoções); não diz preço nem estoque; cita o produto e o link certos quando fala de produto; " +
            "é gentil e sem linguagem explícita. Na dúvida, reprove (false). " +
            "Responda só com JSON, na mesma ordem e quantidade: {\"aprovadas\":[true,false,...]}\n" +
            pares.mapIndexed { i, (p, r) -> "${i + 1}. Pergunta: $p\n   Resposta: $r" }.joinToString("\n")

    fun pedidoDeObjecoes(jaConhecidas: List<String>): String =
        "Você treina a atendente virtual de uma sex shop online (${Perfil.loja}). Escreva exatamente $OBJECOES_POR_DIA objeções " +
            "DIFERENTES que clientes falam antes de comprar (preço, vergonha, medo de alguém ver a encomenda, dúvida se funciona, " +
            "primeira vez, parceiro(a) não sabe, vou pensar, achei mais barato em outro lugar, e se não gostar) e para cada uma a melhor resposta: " +
            "acolhedora, respeitosa, sem pressão e sem julgamento, em até 3 frases, no máximo 1 emoji. Não invente preços, descontos, " +
            "prazos ou políticas; quando precisar, ofereça ajuda pelo WhatsApp. Sem linguagem explícita. " +
            (if (jaConhecidas.isNotEmpty()) "Não repita estas: ${jaConhecidas.take(60).joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"objecoes\":[{\"objecao\":\"...\",\"resposta\":\"...\"}]}"

    fun pedidoDeIdeias(datas: String, produtos: String, jaFeitas: List<String>, hoje: String): String =
        "Hoje é $hoje. Você é social media da ${Perfil.loja}, sex shop online de ${Perfil.cidade} (Instagram da loja). " +
            "$datas\n" + (if (produtos.isNotBlank()) "Produtos à venda: $produtos\n" else "") +
            "Crie exatamente $IDEIAS_POR_SEMANA ideias de post para as próximas semanas, aproveitando as datas e os produtos: " +
            "variadas (feed, story, reels), elegantes, sem nudez nem linguagem explícita (regras do Instagram). " +
            "Para cada uma: título curto, quando postar (data ou semana), formato e uma legenda pronta com chamada para o link da bio. " +
            (if (jaFeitas.isNotEmpty()) "Não repita estas: ${jaFeitas.joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"ideias\":[{\"titulo\":\"...\",\"quando\":\"...\",\"formato\":\"feed|story|reels\",\"legenda\":\"...\"}]}"

    fun pedidoDeLicoes(msgs: List<Memoria.Mensagem>, erros: List<String>, jaSabidas: List<String>): String =
        "Você ajuda a Vênus, a assistente pessoal e comercial do ${Perfil.dono} (app Android), a aprender com os próprios erros. " +
            "Leia as conversas e os erros abaixo e escreva até $LICOES_MAXIMO lições NOVAS para ela seguir daqui para frente.\n" +
            "Cada lição: uma regra curta e geral, no imperativo, em até 25 palavras (ex.: \"Quando o ${Perfil.dono} pedir preço de concorrente, pesquise antes de responder\"), " +
            "e o porquê em uma frase, citando o que aconteceu. Só lições que mudam o comportamento dela; nada óbvio, nada sobre um caso único. " +
            "Se nada deu errado, devolva a lista vazia.\n" +
            (if (jaSabidas.isNotEmpty()) "Ela já segue estas (não repita nem contradiga sem motivo):\n" + jaSabidas.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (erros.isNotEmpty()) "\nErros registrados no app:\n" + erros.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (msgs.isNotEmpty()) "\nConversas:\n" + msgs.joinToString("\n") { (if (it.doUsuario) "${Perfil.dono}: " else "Vênus: ") + it.texto.replace('\n', ' ').take(300) } + "\n" else "") +
            "\nResponda só com JSON: {\"licoes\":[{\"licao\":\"...\",\"resposta\":\"o porquê\"}]}"

    fun pedidoDeSaberes(area: String, jaConhecidas: List<String>): String =
        "Você monta o estudo diário da Vênus, assistente pessoal e comercial do ${Perfil.dono}: ele mora em ${Perfil.cidade}, trabalha num hotel " +
            "e tem a ${Perfil.loja}, loja online de produtos para o bem-estar íntimo, e faz tudo pelo celular Android.\n" +
            "Área de hoje: $area.\n" +
            "Escreva exatamente $SABERES_POR_DIA perguntas DIFERENTES que o ${Perfil.dono} poderia fazer sobre essa área, úteis na vida ou no negócio dele, " +
            "cada uma com uma resposta correta, prática e curta (até 3 frases), em português do Brasil. " +
            "Só conhecimento que não muda com o tempo: nada de preços, valores de impostos, datas futuras, notícias ou leis recentes. " +
            "Sem orientação médica, jurídica ou financeira individual; em saúde, termine indicando procurar um profissional quando fizer sentido. " +
            "Sem linguagem explícita. " +
            (if (jaConhecidas.isNotEmpty()) "Não repita estas: ${jaConhecidas.take(80).joinToString(" | ")}. " else "") +
            "Responda só com JSON: {\"perguntas\":[{\"pergunta\":\"...\",\"resposta\":\"...\"}]}"

    fun pedidoDeConferenciaDeSaberes(area: String, pares: List<Pair<String, String>>): String =
        "Você confere respostas sobre $area antes de uma assistente guardar na memória. Aprove (true) só se a resposta estiver correta, " +
            "responder à pergunta, não depender de data (preços, valores, leis recentes), não der orientação médica, jurídica ou financeira individual " +
            "e não tiver linguagem explícita. Na dúvida, reprove (false). " +
            "Responda só com JSON, na mesma ordem e quantidade: {\"aprovadas\":[true,false,...]}\n" +
            pares.mapIndexed { i, (p, r) -> "${i + 1}. Pergunta: $p\n   Resposta: $r" }.joinToString("\n")

    fun pedidoDeRevisao(msgs: List<Memoria.Mensagem>): String =
        "Você revisa as conversas do ${Perfil.dono} com a Vênus, a assistente de voz dele, para ela aprender com os erros. Ações que ela executa:\n" +
            Roteador.ACOES.joinToString("\n") { "- ${it.first}: ${it.second}" + if (it.third.isNotBlank()) " | ${it.third}" else "" } +
            "\n\nConversas:\n" + msgs.joinToString("\n") { (if (it.doUsuario) "${Perfil.dono}: " else "Vênus: ") + it.texto.replace('\n', ' ').take(300) } +
            "\n\nAche até $REVISAO_MAXIMO momentos em que a Vênus entendeu errado, não soube fazer, disse que não conseguiu, " +
            "ou o ${Perfil.dono} teve que repetir ou corrigir. Para cada um: a frase do ${Perfil.dono} exatamente como ele disse; se era um pedido de ação, " +
            "a ação certa da lista e os argumentos ditos; se era uma pergunta de conhecimento geral que não muda com o tempo, " +
            "uma resposta certa e curta (senão deixe vazia). Ignore o que ela fez certo e o que nenhuma ação resolve. " +
            "Responda só com JSON: {\"correcoes\":[{\"frase\":\"...\",\"acao\":\"nome ou vazio\",\"args\":{},\"resposta\":\"\"}]}"

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

    /** Pares {lista:[{campo, "resposta"}]} → (texto, resposta), sem vazios nem repetidos. */
    fun lerPares(json: String, lista: String, campo: String): List<Pair<String, String>> {
        val a = objeto(json)?.optJSONArray(lista) ?: return emptyList()
        val vistas = mutableSetOf<String>()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString(campo).trim().take(400)
            val r = o.optString("resposta").trim().take(800)
            if (t.length < 4 || r.length < 10 || !vistas.add(ComandosLocais.normalizar(t))) null else t to r
        }
    }

    /** {"aprovadas":[true,false]} → lista do tamanho pedido (o que faltar conta como reprovado). */
    fun lerAprovados(json: String, quantos: Int): List<Boolean>? {
        val a = objeto(json)?.optJSONArray("aprovadas") ?: return null
        return List(quantos) { i -> a.optBoolean(i, false) }
    }

    /** Ideia de post → ("Título (quando, formato)", legenda). */
    fun lerIdeias(json: String): List<Pair<String, String>> {
        val a = objeto(json)?.optJSONArray("ideias") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val titulo = o.optString("titulo").trim()
            val legenda = o.optString("legenda").trim()
            if (titulo.isBlank() || legenda.isBlank()) return@mapNotNull null
            val detalhe = listOf(o.optString("quando").trim(), o.optString("formato").trim()).filter { it.isNotBlank() }.joinToString(", ")
            (if (detalhe.isNotBlank()) "$titulo ($detalhe)" else titulo).take(300) to legenda.take(1500)
        }
    }

    data class Correcao(val frase: String, val rota: Roteador.Rota?, val resposta: String)

    fun lerRevisao(json: String): List<Correcao> {
        val a = objeto(json)?.optJSONArray("correcoes") ?: return emptyList()
        val vistas = mutableSetOf<String>()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val frase = o.optString("frase").trim().take(200)
            if (frase.split(" ").size < 2 || !vistas.add(ComandosLocais.normalizar(frase))) return@mapNotNull null
            val acao = o.optString("acao").trim()
            val rota = if (acao.isBlank()) null else Roteador.lerRota(JSONObject().put("acao", acao).put("args", o.optJSONObject("args") ?: JSONObject()))
            val resposta = o.optString("resposta").trim().take(1500)
            if (rota == null && resposta.isBlank()) null else Correcao(frase, rota, resposta)
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
    @Volatile
    private var parar = false

    override fun onStartJob(params: JobParameters): Boolean {
        parar = false
        Thread {
            try {
                // o Android dá uns 10 minutos por vez; o que faltar fica para a próxima rodada
                Estudo.estudar(applicationContext, prazo = System.currentTimeMillis() + 7 * 60_000L, parar = { parar })
            } catch (e: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        parar = true
        return true
    }

    companion object {
        private const val JOB_ID = 73_100
        private const val INTERVALO = 3 * 3600_000L

        /** Confere a cada 3 horas; estuda uma vez por dia (em etapas, se o Android interromper). */
        fun agendar(ctx: Context) {
            if (!Estudo.ligado(ctx)) return
            val js = ctx.getSystemService(JobScheduler::class.java)
            if ((js.getPendingJob(JOB_ID)?.intervalMillis ?: 0L) == INTERVALO) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, EstudoDiario::class.java))
                    .setPeriodic(INTERVALO)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancelar(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
