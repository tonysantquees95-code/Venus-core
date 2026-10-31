package com.tonimarques.sextafeira

import com.tonimarques.sextafeira.BancoDeFrases.Intencao
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * O "cérebro local" da Sexta-feira: entende o pedido sem internet, conversa,
 * pergunta o que falta e confirma quando fica em dúvida.
 *
 * Ordem de decisão:
 *  1. resposta a uma pergunta que ela mesma fez (confirmação ou informação que faltava);
 *  2. comandos diretos reconhecidos com precisão (ComandosLocais);
 *  3. banco de frases, comparando conceitos com centenas de exemplos;
 *  4. se não tiver certeza, devolve Incerto: o app tenta a IA e, se ela falhar, chama usarPalpite().
 */
class Cerebro(
    private val sorteio: Random = Random.Default,
    private val relogio: () -> Calendar = { Calendar.getInstance() },
) {

    sealed class Decisao {
        data class Executar(val nome: String, val args: Map<String, Any>) : Decisao()
        data class Responder(val texto: String) : Decisao()
        data class Perguntar(val texto: String) : Decisao()
        data class Especial(val tipo: String, val texto: String) : Decisao()

        /** Não teve certeza. [temPalpite] diz se há uma intenção provável para confirmar depois. */
        data class Incerto(val temPalpite: Boolean) : Decisao()
    }

    private sealed class Pendente {
        class Confirmar(val intencao: Intencao) : Pendente()
        class Coletar(val intencao: Intencao, var indice: Int = 0, val dados: MutableMap<String, Any> = mutableMapOf()) : Pendente() {
            var tentativas = 0
        }
    }

    private var pendente: Pendente? = null
    private var palpite: Intencao? = null

    val aguardandoResposta: Boolean get() = pendente != null

    fun responder(entrada: String): Decisao {
        val frase = ComandosLocais.limpar(entrada)
        val chave = frase.chave
        val bruto = ComandosLocais.normalizar(entrada).replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

        // 1) Resposta a uma pergunta minha
        pendente?.let { p ->
            pendente = null
            tratarPendente(p, frase, bruto)?.let { return it }
        }
        palpite = null

        if (SIM.matches(chave) || SIM.matches(bruto)) return Decisao.Responder("Certo! Precisa de mais alguma coisa?")
        if (NAO.matches(chave) || NAO.matches(bruto)) return Decisao.Responder("Tudo bem. É só chamar quando precisar.")
        // "Oi, Sexta-feira" fica vazio depois da limpeza: usa a frase inteira para entender o cumprimento.
        val alvo = if (chave.isBlank()) bruto else chave
        if (conceitos(alvo).isEmpty()) return Decisao.Responder(sortear(listOf("Sim? Estou ouvindo.", "Pode falar.", "Estou aqui.")))

        // 2) Comandos diretos
        ComandosLocais.interpretar(entrada)?.let { r ->
            return when (r) {
                is ComandosLocais.Resultado.Acao -> Decisao.Executar(r.nome, r.args)
                is ComandosLocais.Resultado.Resposta -> Decisao.Responder(r.texto)
            }
        }

        // 3) Banco de frases
        val (melhor, nota) = classificar(alvo)
        if (melhor != null && nota >= CONFIANTE) return decidir(melhor)
        if (melhor != null && nota >= PROVAVEL) palpite = melhor
        return Decisao.Incerto(palpite != null)
    }

    /** Chamado quando a IA não está disponível ou falhou: confirma o palpite ou pede para repetir. */
    fun usarPalpite(): Decisao {
        val p = palpite
        palpite = null
        if (p != null) {
            pendente = Pendente.Confirmar(p)
            val pergunta = if (p.descricao != null) "Você quer que eu ${p.descricao}?"
            else "Você quis dizer: ${p.exemplos.first()}?"
            return Decisao.Perguntar(pergunta)
        }
        return Decisao.Responder(sortear(BancoDeFrases.NAO_ENTENDI))
    }

    fun saudacaoDeAtivacao(): String = preencher(sortear(BancoDeFrases.RESPOSTAS_ATIVACAO))

    fun esquecerPendencias() {
        pendente = null
        palpite = null
    }

    // -------------------------------------------------------------- diálogo
    private fun tratarPendente(p: Pendente, frase: ComandosLocais.Frase, bruto: String): Decisao? {
        val c = frase.chave
        if (CANCELAR.matches(c) || CANCELAR.matches(bruto)) return Decisao.Responder("Tudo bem, cancelado.")

        return when (p) {
            is Pendente.Confirmar -> when {
                SIM.matches(c) || SIM.matches(bruto) -> decidir(p.intencao)
                NAO.matches(c) || NAO.matches(bruto) -> Decisao.Responder("Tudo bem. Então me diz de outro jeito o que você quer.")
                else -> null // era um pedido novo
            }
            is Pendente.Coletar -> coletar(p, frase.original.trim())
        }
    }

    private fun coletar(p: Pendente.Coletar, valor: String): Decisao {
        val (campo, pergunta) = p.intencao.pede[p.indice]
        if (valor.isBlank()) {
            pendente = p
            return Decisao.Perguntar(pergunta)
        }
        when (campo) {
            "hora" -> {
                val r = ComandosLocais.interpretar("alarme às $valor") as? ComandosLocais.Resultado.Acao
                if (r?.nome != "criar_alarme") return repetir(p, "Não entendi o horário. Pode repetir? Por exemplo: sete e meia.")
                p.dados.putAll(r.args)
            }
            "segundos" -> {
                val so = valor.trim()
                val texto = if (Regex("^\\d+$").matches(so)) "$so minutos" else so
                val r = ComandosLocais.interpretar("timer de $texto") as? ComandosLocais.Resultado.Acao
                if (r?.nome != "criar_timer") return repetir(p, "Não entendi o tempo. Diga, por exemplo: cinco minutos.")
                p.dados.putAll(r.args)
            }
            "contato" -> p.dados[campo] = valor.replace(Regex("^(?i)(pra|para|pro|ao|a|o)\\s+"), "").trim()
            else -> p.dados[campo] = valor
        }
        p.indice++
        if (p.indice < p.intencao.pede.size) {
            pendente = p
            return Decisao.Perguntar(p.intencao.pede[p.indice].second)
        }
        return Decisao.Executar(p.intencao.acao!!, p.intencao.args + p.dados)
    }

    private fun repetir(p: Pendente.Coletar, mensagem: String): Decisao {
        if (p.tentativas >= 1) return Decisao.Responder("Não consegui entender. Vamos tentar de novo depois.")
        p.tentativas++
        pendente = p
        return Decisao.Perguntar(mensagem)
    }

    private fun decidir(i: Intencao): Decisao = when {
        i.especial == "ajuda" -> Decisao.Especial("ajuda", BancoDeFrases.AJUDA_TEXTO)
        i.especial != null -> Decisao.Especial(i.especial, preencher(sortear(i.respostas)))
        i.pede.isNotEmpty() -> {
            pendente = Pendente.Coletar(i)
            Decisao.Perguntar(i.pede[0].second)
        }
        i.acao != null -> Decisao.Executar(i.acao, i.args)
        else -> Decisao.Responder(preencher(sortear(i.respostas)))
    }

    // ------------------------------------------------------------- respostas
    private fun sortear(opcoes: List<String>): String = if (opcoes.isEmpty()) "Certo." else opcoes[sorteio.nextInt(opcoes.size)]

    private fun preencher(texto: String): String {
        val agora = relogio()
        val br = Locale("pt", "BR")
        val h = agora.get(Calendar.HOUR_OF_DAY)
        return texto
            .replace("{hora}", SimpleDateFormat("HH:mm", br).format(agora.time))
            .replace("{data}", SimpleDateFormat("EEEE, d 'de' MMMM", br).format(agora.time))
            .replace("{periodo}", if (h < 12) "Bom dia" else if (h < 18) "Boa tarde" else "Boa noite")
            .replace("{dado}", (sorteio.nextInt(6) + 1).toString())
            .replace("{moeda}", if (sorteio.nextBoolean()) "cara" else "coroa")
    }

    // ---------------------------------------------------------- classificação
    fun classificar(chave: String): Pair<Intencao?, Double> {
        val entrada = conceitos(chave)
        if (entrada.isEmpty()) return null to 0.0
        val pergunta = ehPergunta(chave)
        var melhor: Intencao? = null
        var melhorNota = 0.0
        for ((intencao, exemplos) in INDICE) {
            for (ex in exemplos) {
                val nota = similaridade(entrada, ex, pergunta)
                if (nota > melhorNota) {
                    melhorNota = nota
                    melhor = intencao
                }
            }
        }
        return melhor to melhorNota
    }

    companion object {
        const val CONFIANTE = 0.8
        const val PROVAVEL = 0.55

        private val SIM = Regex(
            "^(sim|isso|isso mesmo|claro|pode|pode sim|pode ser|ok|okay|beleza|manda|manda ver|com certeza|exato|" +
                "confirmo|positivo|aham|uhum|correto|isso ai|quero|faz isso|bora|vai|sim por favor|aceito)$"
        )
        private val NAO = Regex(
            "^(nao|nao precisa|nao quero|negativo|nada|deixa|deixa pra la|so isso|errado|nao e isso|nao obrigado|nao valeu)$"
        )
        private val CANCELAR = Regex("^(cancela|cancelar|cancele|esquece|esqueca|deixa pra la|nenhum|para|pare)$")
        private val PERGUNTA = Regex("^(qual|quais|quanto|quantos|quantas|como|por que|porque|o que|quem|onde|quando)\\b")

        private val INDICE: List<Pair<Intencao, List<Set<String>>>> by lazy {
            BancoDeFrases.INTENCOES.map { i -> i to i.exemplos.map { conceitos(it) }.filter { it.isNotEmpty() } }
        }

        fun ehPergunta(chave: String) = PERGUNTA.containsMatchIn(chave)

        private val ATIVACAO = Regex(
            "(sexta[\\s-]*feira|sextafeira|sexta|6a feira)[\\s,.!]*(ativar|ativa|ative|ativada|ativacao|ativo|online|acordar|acorda)\\b" +
                "|\\b(ativar|ativa)[\\s,]+(a\\s+)?(sexta[\\s-]*feira|sexta)\\b"
        )

        /** Se a frase contém "Sexta-feira, ativar", devolve o que vem depois (pode ser vazio). Senão, null. */
        fun restoDepoisDaAtivacao(texto: String): String? {
            val normal = ComandosLocais.normalizar(texto)
            val m = ATIVACAO.find(normal) ?: return null
            return texto.substring(m.range.last + 1).trim().trimStart(',', '.', '!', ' ').trim()
        }

        /** Transforma uma frase em conceitos: sinônimos unificados, palavras sem importância removidas. */
        fun conceitos(texto: String): Set<String> {
            var s = " " + ComandosLocais.normalizar(texto)
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim() + " "
            for ((regra, troca) in BancoDeFrases.EXPRESSOES) s = regra.replace(s, troca)
            val resultado = LinkedHashSet<String>()
            for (palavra in s.split(" ")) {
                if (palavra.isBlank() || palavra in BancoDeFrases.IRRELEVANTES) continue
                resultado += BancoDeFrases.SINONIMOS[palavra] ?: palavra
            }
            return resultado
        }

        /**
         * Quanto a frase dita parece com um exemplo (0 a 1).
         * Mistura "o exemplo inteiro aparece na frase" com "as duas frases têm o mesmo tamanho".
         * Para perguntas, exige frases quase iguais, para não confundir perguntas livres com comandos.
         */
        fun similaridade(entrada: Set<String>, exemplo: Set<String>, pergunta: Boolean = false): Double {
            if (exemplo.isEmpty() || entrada.isEmpty()) return 0.0
            var comum = 0
            for (e in exemplo) if (e in entrada || entrada.any { parecidas(it, e) }) comum++
            val contem = comum.toDouble() / exemplo.size
            val equilibrio = 2.0 * comum / (entrada.size + exemplo.size)
            return if (pergunta) equilibrio else 0.6 * contem + 0.4 * equilibrio
        }

        /** Palavras quase iguais (erros de reconhecimento de voz), ex.: "lanterna" e "lanterma". */
        private fun parecidas(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.any { it.isUpperCase() } || b.any { it.isUpperCase() }) return false
            if (a.length < 4 || b.length < 4) return false
            val limite = if (max(a.length, b.length) >= 7) 2 else 1
            return distancia(a, b) <= limite
        }

        private fun distancia(a: String, b: String): Int {
            var anterior = IntArray(b.length + 1) { it }
            var atual = IntArray(b.length + 1)
            for (i in 1..a.length) {
                atual[0] = i
                for (j in 1..b.length) {
                    val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                    atual[j] = min(min(atual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + custo)
                }
                val t = anterior
                anterior = atual
                atual = t
            }
            return anterior[b.length]
        }
    }
}
