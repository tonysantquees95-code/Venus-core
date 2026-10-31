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

        /** Pedido sobre a memória (lembrar, esquecer, listar...), executado pelo app. */
        data class Memoria(val pedido: ComandosMemoria.Pedido) : Decisao()
    }

    private sealed class Pendente {
        class Confirmar(val intencao: Intencao) : Pendente()
        class Coletar(val intencao: Intencao, var indice: Int = 0, val dados: MutableMap<String, Any> = mutableMapOf()) : Pendente() {
            var tentativas = 0
        }
    }

    private var pendente: Pendente? = null
    private var palpite: Intencao? = null
    private var fraseDoPalpite: String = ""

    /** Como chamar o usuário nas respostas (troca o "chefe"). */
    var apelido: String = "chefe"

    /** Avisado quando ela aprende uma frase nova (frase normalizada, id da intenção), para salvar. */
    var aoAprender: ((String, String) -> Unit)? = null

    private val aprendidas = mutableListOf<Pair<Intencao, Set<String>>>()

    /** Carrega frases aprendidas em usos anteriores. */
    fun carregarAprendidas(lista: List<Pair<String, String>>) {
        for ((frase, id) in lista) {
            val i = BancoDeFrases.INTENCOES.firstOrNull { it.id == id } ?: continue
            val c = conceitos(frase)
            if (c.isNotEmpty()) aprendidas += i to c
        }
    }

    val aguardandoResposta: Boolean get() = pendente != null

    /** Quando a frase precisou ser corrigida para ser entendida, guarda aqui a versão corrigida. */
    var ultimaCorrecao: String? = null
        private set

    fun responder(entrada: String): Decisao {
        ultimaCorrecao = null
        val frase = ComandosLocais.limpar(entrada)
        val chave = frase.chave
        val bruto = ComandosLocais.normalizar(entrada).replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

        // 1) Resposta a uma pergunta minha
        pendente?.let { p ->
            pendente = null
            tratarPendente(p, frase, bruto)?.let { return it }
        }
        palpite = null

        // Memória: "lembra que...", "meu nome é...", "o que você sabe sobre mim"...
        ComandosMemoria.interpretar(entrada)?.let { return Decisao.Memoria(it) }

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

        // 4) Talvez o reconhecimento de voz tenha errado alguma palavra: tenta a frase corrigida.
        val corrigida = Corretor.corrigir(entrada)
        if (corrigida != entrada) {
            ComandosLocais.interpretar(corrigida)?.let { r ->
                ultimaCorrecao = corrigida
                return when (r) {
                    is ComandosLocais.Resultado.Acao -> Decisao.Executar(r.nome, r.args)
                    is ComandosLocais.Resultado.Resposta -> Decisao.Responder(r.texto)
                }
            }
            val chaveCorrigida = ComandosLocais.limpar(corrigida).chave.ifBlank { alvo }
            val (outra, notaCorrigida) = classificar(chaveCorrigida)
            if (outra != null && notaCorrigida >= CONFIANTE) {
                ultimaCorrecao = corrigida
                return decidir(outra)
            }
        }

        if (melhor != null && nota >= PROVAVEL) {
            palpite = melhor
            fraseDoPalpite = alvo
        }
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

    /**
     * Aprende uma frase que o Gemini resolveu com uma ferramenta sem detalhes variáveis
     * (ex.: "acende o flash aí" → lanterna ligada). Da próxima vez, a Vênus resolve sozinha.
     * Devolve true se aprendeu.
     */
    fun aprenderComIA(frase: String, ferramenta: String, args: Map<String, Any?>): Boolean {
        val intencao = BancoDeFrases.INTENCOES.firstOrNull { i ->
            i.acao == ferramenta && i.pede.isEmpty() && i.especial == null &&
                i.args.all { (k, v) -> args[k]?.toString() == v.toString() } &&
                args.keys.all { it in i.args.keys || args[it] == null || args[it].toString().isBlank() }
        } ?: return false
        val chave = ComandosLocais.limpar(frase).chave
        val novos = conceitos(chave)
        if (novos.isEmpty()) return false
        aprendidas += intencao to novos
        aoAprender?.invoke(chave, intencao.id)
        return true
    }

    fun esquecerAprendidas() {
        aprendidas.clear()
    }

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
                SIM.matches(c) || SIM.matches(bruto) -> {
                    // Aprende: da próxima vez essa frase já vai direto.
                    if (fraseDoPalpite.isNotBlank()) {
                        val novos = conceitos(fraseDoPalpite)
                        if (novos.isNotEmpty()) aprendidas += p.intencao to novos
                        aoAprender?.invoke(fraseDoPalpite, p.intencao.id)
                        fraseDoPalpite = ""
                    }
                    decidir(p.intencao)
                }
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
            .replace("chefe", apelido)
            .replace("{nome}", Marca.nome)
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
        for ((intencao, ex) in aprendidas) {
            val nota = similaridade(entrada, ex, pergunta)
            if (nota > melhorNota) {
                melhorNota = nota
                melhor = intencao
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
            "(sexta[\\s-]*feira|sextafeira|sexta|6a feira|venus|vinus)[\\s,.!]*(ativar|ativa|ative|ativada|ativacao|ativo|online|acordar|acorda)\\b" +
                "|\\b(ativar|ativa)[\\s,]+(a\\s+)?(sexta[\\s-]*feira|sexta|venus)\\b"
        )

        /** Se a frase contém "Sexta-feira, ativar", devolve o que vem depois (pode ser vazio). Senão, null. */
        fun restoDepoisDaAtivacao(texto: String): String? {
            val normal = ComandosLocais.normalizar(texto)
            val m = ATIVACAO.find(normal) ?: return null
            return texto.substring(m.range.last + 1).trim().trimStart(',', '.', '!', ' ').trim()
        }

        /**
         * Versão tolerante da frase de ativação, para o reconhecimento offline (que às vezes escreve
         * "venus ativa", "vênus ativar", "vem us ativar", "sexta feira ativar"...).
         */
        fun pareceAtivacao(texto: String): Boolean {
            if (texto.isBlank()) return false
            if (restoDepoisDaAtivacao(texto) != null) return true
            val ps = Corretor.palavras(texto)
            for (i in ps.indices) {
                val (ehNome, usadas) = when {
                    i + 1 < ps.size && (ps[i] + ps[i + 1]).let { it == "sextafeira" || pareceVenus(it) } -> true to 2
                    pareceVenus(ps[i]) -> true to 1
                    else -> false to 0
                }
                if (!ehNome) continue
                val depois = ps.drop(i + usadas).take(2)
                if (depois.any { it.startsWith("ativ") || Corretor.distancia(it, "ativar") <= 1 || Corretor.distancia(it, "ativa") <= 1 }) {
                    return true
                }
            }
            return false
        }

        private fun pareceVenus(p: String) = p.length in 4..6 && (p == "venus" || Corretor.distancia(p, "venus") <= 1)

        private val INTERJEICAO = Regex(
            "^(?:(?:muito|que|bem|super|ah|oh) )?(?:interessante|legal|bacana|massa|top|entendi|certo|ok|okay|beleza|" +
                "obrigad[oa]|valeu|uau|perfeito|otimo|incrivel|show de bola|que bom|hum+|hm+|ah|entao|tudo bem|ta bom|sei)" +
                "(?:[,.!;]+\\s*|\\s+)(?:(?:venus|sexta[ -]feira)[,.!]?\\s+)?(?:(?:e|agora|entao|mas|tambem)\\s+)?"
        )
        private val CORRECAO = Regex(
            "^(?:nao|na verdade|errado|negativo)[,.!]?\\s+(?:(?:venus|sexta[ -]feira)[,.!]?\\s+)?(?:eu )?(?:quis|queria|quero|to querendo|estou querendo) " +
                "(?:perguntar|dizer|saber|falar)(?: que| se| sobre)?[,: ]+"
        )
        private val VOCATIVO_COM_VIRGULA = Regex(",\\s*(?:venus|sexta[ -]feira)\\s*(?:,|(?=[?!.]|$))")
        private val E_INICIAL = Regex("^e (?=(?:qual|quais|quanto|quantos|quantas|como|onde|quando|quem|o que|por que|me |a |o ))")

        /**
         * Tira da frase o que é só conversa, para entender o pedido:
         * "muito interessante Vênus e qual é a menor cidade da Itália" → "qual é a menor cidade da Itália";
         * "não, eu quis perguntar qual é..." → "qual é..."; "abre o WhatsApp, Vênus" → "abre o WhatsApp".
         * A normalização mantém o tamanho do texto, então os cortes valem para o original (com acentos).
         */
        fun limparFala(texto: String): String {
            var t = texto.trim()
            repeat(3) {
                val n = ComandosLocais.normalizar(t)
                val m = INTERJEICAO.find(n)?.takeIf { it.range.first == 0 }
                    ?: CORRECAO.find(n)?.takeIf { it.range.first == 0 }
                if (m != null) {
                    val sinal = m.value.any { it in ",.!;" } || Regex("venus|sexta|\\b(?:e|agora|entao|mas|tambem)\\s*$").containsMatchIn(m.value) ||
                        CORRECAO.matches(m.value)
                    val resto = t.substring(m.value.length).trim()
                    if (sinal && resto.split(" ").count { it.isNotBlank() } >= 2) {
                        t = resto
                        return@repeat
                    }
                }
                val e = E_INICIAL.find(ComandosLocais.normalizar(t))
                if (e != null) t = t.substring(e.value.length).trim()
            }
            val n = ComandosLocais.normalizar(t)
            VOCATIVO_COM_VIRGULA.find(n)?.let { m -> t = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim() }
            return t.ifBlank { texto.trim() }
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
