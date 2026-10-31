package com.tonimarques.sextafeira

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Raciocínio que a Vênus faz sozinha, sem internet:
 *  - contas ("quanto é 25 vezes 4", "15% de 200", "raiz de 144", "dois mil mais quinhentos");
 *  - conversões ("quantos quilômetros são 10 milhas", "30 graus celsius em fahrenheit");
 *  - datas ("quantos dias faltam para o natal", "quando é o carnaval", "que dia cai a páscoa").
 * Recebe a frase já normalizada (minúsculas, sem acentos).
 */
object Calculadora {

    private val BR = Locale("pt", "BR")

    fun responder(c: String, hoje: Calendar = Calendar.getInstance()): String? =
        datas(c, hoje) ?: conversao(c) ?: conta(c)

    // ================================================================ contas
    private val PREFIXO_CONTA = Regex(
        "^(?:quanto (?:e|eh|da|que e|que da|fica)|calcula|calcule|calcular|faz a conta(?: de)?|faca a conta(?: de)?|" +
            "qual (?:e |eh )?(?:o )?resultado de|resolve|resolva|me diz quanto e|quanto vale)\\s+"
    )

    fun conta(c: String): String? {
        var e = c.trim().trimEnd('?', '.', '!', ' ')
        val temPrefixo = PREFIXO_CONTA.containsMatchIn(e)
        e = PREFIXO_CONTA.replace(e, "")
        e = numerosPorExtenso(e)
        e = e.replace(Regex("(\\d),(\\d)"), "$1.$2")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|por cento|porcento)\\s+(?:de|do|da|sobre)\\s+"), "($1/100)*")
            .replace(Regex("raiz quadrada (?:de |do )?"), " r ")
            .replace(Regex("raiz (?:de |do )?"), " r ")
            .replace(Regex("\\bao quadrado\\b"), "^2")
            .replace(Regex("\\bao cubo\\b"), "^3")
            .replace(Regex("\\belevado (?:a|ao|na)(?: potencia(?: de)?)?\\b"), "^")
            .replace(Regex("\\b(?:multiplicado por|vezes)\\b"), "*")
            .replace(Regex("(?<=[\\d)\\s])x(?=[\\s\\d(])"), "*")
            .replace(Regex("\\b(?:dividido por|divido por|dividido)\\b"), "/")
            .replace(Regex("\\bmais\\b"), "+")
            .replace(Regex("\\bmenos\\b"), "-")
            .replace(Regex("\\b(?:abre parenteses|abre parentese)\\b"), "(")
            .replace(Regex("\\b(?:fecha parenteses|fecha parentese)\\b"), ")")
            .replace(Regex("\\s+"), " ").trim()
        if (e.isBlank() || !Regex("^[\\d.+\\-*/^()r ]+$").matches(e)) return null
        if (!Regex("\\d").containsMatchIn(e)) return null
        val operadores = Regex("[+\\-*/^r]").findAll(e).count()
        if (operadores == 0) return null
        // "2 3" ou só um número com sinal não é conta
        if (!temPrefixo && operadores == 1 && e.trim().startsWith("-")) return null
        val valor = try {
            Expressao(e.replace(" ", "")).avaliar()
        } catch (ex: Exception) {
            return null
        }
        if (valor.isNaN()) return "Essa conta não tem resultado (raiz de número negativo)."
        if (valor.isInfinite()) return "Não dá para dividir por zero."
        return "Dá ${numero(valor)}."
    }

    fun numero(v: Double): String {
        val f = NumberFormat.getNumberInstance(BR)
        f.maximumFractionDigits = if (abs(v) >= 100) 2 else 4
        return if (abs(v - v.roundToLong()) < 1e-9 && abs(v) < 1e15) f.format(v.roundToLong()) else f.format(v)
    }

    /** Analisador de expressões: + - * / ^, parênteses, menos unário e raiz (r). */
    private class Expressao(val s: String) {
        var i = 0
        fun avaliar(): Double {
            val v = soma()
            if (i != s.length) throw IllegalArgumentException("sobrou: ${s.substring(i)}")
            return v
        }

        private fun soma(): Double {
            var v = produto()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val d = produto()
                v = if (op == '+') v + d else v - d
            }
            return v
        }

        private fun produto(): Double {
            var v = potencia()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val d = potencia()
                v = if (op == '*') v * d else v / d
            }
            return v
        }

        private fun potencia(): Double {
            val base = unario()
            if (i < s.length && s[i] == '^') {
                i++
                return base.pow(potencia())
            }
            return base
        }

        private fun unario(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -unario()
            }
            if (i < s.length && s[i] == '+') {
                i++
                return unario()
            }
            if (i < s.length && s[i] == 'r') {
                i++
                return sqrt(unario())
            }
            return primario()
        }

        private fun primario(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = soma()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val ini = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (ini == i) throw IllegalArgumentException("número esperado")
            return s.substring(ini, i).toDouble()
        }
    }

    // ------------------------------------------------------- números por extenso
    private val UNIDADES = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13,
        "catorze" to 14, "quatorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18,
        "dezenove" to 19, "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
        "setenta" to 70, "oitenta" to 80, "noventa" to 90, "cem" to 100, "cento" to 100, "duzentos" to 200,
        "duzentas" to 200, "trezentos" to 300, "trezentas" to 300, "quatrocentos" to 400, "quinhentos" to 500,
        "seiscentos" to 600, "setecentos" to 700, "oitocentos" to 800, "novecentos" to 900,
    )
    private val MULTIPLICADORES = mapOf("mil" to 1_000L, "milhao" to 1_000_000L, "milhoes" to 1_000_000L, "bilhao" to 1_000_000_000L, "bilhoes" to 1_000_000_000L)

    /** "dois mil e quinhentos mais trinta" → "2500 mais 30". Números já em dígitos ficam como estão. */
    fun numerosPorExtenso(texto: String): String {
        val palavras = texto.split(" ").filter { it.isNotBlank() }
        val saida = mutableListOf<String>()
        var total = 0L
        var parcial = 0L
        var dentro = false
        fun fechar() {
            if (dentro) saida += (total + parcial).toString()
            total = 0
            parcial = 0
            dentro = false
        }
        var k = 0
        while (k < palavras.size) {
            val p = palavras[k]
            val u = UNIDADES[p]
            val m = MULTIPLICADORES[p]
            when {
                u != null -> {
                    parcial += u
                    dentro = true
                }
                m != null && (dentro || p == "mil") -> {
                    total += (if (parcial == 0L) 1 else parcial) * m
                    parcial = 0
                    dentro = true
                }
                p == "e" && dentro && k + 1 < palavras.size && (palavras[k + 1] in UNIDADES || palavras[k + 1] in MULTIPLICADORES) -> {}
                p.all { it.isDigit() } && k + 1 < palavras.size && palavras[k + 1] in MULTIPLICADORES -> {
                    parcial += p.toLong()
                    dentro = true
                }
                else -> {
                    fechar()
                    saida += p
                }
            }
            k++
        }
        fechar()
        return saida.joinToString(" ")
    }

    // ============================================================ conversões
    private data class Unidade(val grandeza: String, val fator: Double, val nome: String)

    private val UNIDADES_MEDIDA: Map<String, Unidade> = buildMap {
        fun u(nomes: String, grandeza: String, fator: Double, nome: String) {
            for (n in nomes.split("|")) put(n, Unidade(grandeza, fator, nome))
        }
        u("milimetro|milimetros|mm", "comprimento", 0.001, "milímetros")
        u("centimetro|centimetros|cm", "comprimento", 0.01, "centímetros")
        u("metro|metros|m", "comprimento", 1.0, "metros")
        u("quilometro|quilometros|km|kilometro|kilometros", "comprimento", 1000.0, "quilômetros")
        u("polegada|polegadas", "comprimento", 0.0254, "polegadas")
        u("pe|pes", "comprimento", 0.3048, "pés")
        u("jarda|jardas", "comprimento", 0.9144, "jardas")
        u("milha|milhas", "comprimento", 1609.344, "milhas")
        u("grama|gramas|g", "massa", 0.001, "gramas")
        u("quilo|quilos|quilograma|quilogramas|kg|kilo|kilos", "massa", 1.0, "quilos")
        u("tonelada|toneladas", "massa", 1000.0, "toneladas")
        u("libra|libras", "massa", 0.45359237, "libras")
        u("onca|oncas", "massa", 0.028349523, "onças")
        u("mililitro|mililitros|ml", "volume", 0.001, "mililitros")
        u("litro|litros", "volume", 1.0, "litros")
        u("galao|galoes", "volume", 3.785411784, "galões")
        u("xicara|xicaras", "volume", 0.24, "xícaras")
        u("segundo|segundos", "tempo", 1.0, "segundos")
        u("minuto|minutos", "tempo", 60.0, "minutos")
        u("hora|horas", "tempo", 3600.0, "horas")
        u("dia|dias", "tempo", 86400.0, "dias")
        u("semana|semanas", "tempo", 604800.0, "semanas")
        u("mes|meses", "tempo", 2629800.0, "meses")
        u("ano|anos", "tempo", 31557600.0, "anos")
        u("celsius|graus celsius|grau celsius|centigrados", "temperatura", 0.0, "graus Celsius")
        u("fahrenheit|graus fahrenheit|grau fahrenheit", "temperatura", 1.0, "graus Fahrenheit")
        u("kelvin|graus kelvin", "temperatura", 2.0, "kelvin")
        u("metro quadrado|metros quadrados|m2", "area", 1.0, "metros quadrados")
        u("hectare|hectares", "area", 10000.0, "hectares")
        u("alqueire|alqueires", "area", 24200.0, "alqueires")
        u("quilometro quadrado|quilometros quadrados|km2", "area", 1_000_000.0, "quilômetros quadrados")
        u("byte|bytes", "dados", 1.0, "bytes")
        u("kilobyte|kilobytes|kb", "dados", 1024.0, "kilobytes")
        u("megabyte|megabytes|mega|megas|mb", "dados", 1024.0 * 1024, "megabytes")
        u("gigabyte|gigabytes|giga|gigas|gb", "dados", 1024.0 * 1024 * 1024, "gigabytes")
        u("terabyte|terabytes|tera|teras|tb", "dados", 1024.0 * 1024 * 1024 * 1024, "terabytes")
    }

    private val NOMES_UNIDADES = UNIDADES_MEDIDA.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val CONVERSAO_1 = Regex(
        "(?:quant[oa]s?|converte|converter|converta|transforma|transforme)?\\s*(?:e |sao |da |dao )?([\\d.,]+)\\s*(?:graus )?($NOMES_UNIDADES)\\s+" +
            "(?:em|para|pra|sao quant[oa]s|equivale a|equivalem a|e quant[oa]s|em quant[oa]s|da quant[oa]s|dao quant[oa]s|quant[oa]s)\\s+(?:graus )?($NOMES_UNIDADES)\\b"
    )
    private val CONVERSAO_2 = Regex(
        "^quant[oa]s\\s+(?:graus )?($NOMES_UNIDADES)\\s+(?:sao|tem|tem em|cabem em|da|dao|ha em|e|equivalem a|equivale a|em)?\\s*([\\d.,]+)\\s*(?:graus )?($NOMES_UNIDADES)\\b"
    )

    /** "1,5" → 1.5; "1.500" → 1500; "2.5" → 2.5 */
    fun paraNumero(t: String): Double? {
        val s = t.trim().trimEnd('.', ',')
        return when {
            s.contains(',') -> s.replace(".", "").replace(",", ".").toDoubleOrNull()
            Regex("^\\d{1,3}(\\.\\d{3})+$").matches(s) -> s.replace(".", "").toDoubleOrNull()
            else -> s.toDoubleOrNull()
        }
    }

    fun conversao(c: String): String? {
        val e = numerosPorExtenso(c.trim().trimEnd('?', '.', '!'))
        var valor: Double
        var de: Unidade
        var para: Unidade
        val m1 = CONVERSAO_1.find(e)
        val m2 = CONVERSAO_2.find(e)
        when {
            m2 != null -> {
                valor = paraNumero(m2.groupValues[2]) ?: return null
                de = UNIDADES_MEDIDA[m2.groupValues[3]] ?: return null
                para = UNIDADES_MEDIDA[m2.groupValues[1]] ?: return null
            }
            m1 != null -> {
                valor = paraNumero(m1.groupValues[1]) ?: return null
                de = UNIDADES_MEDIDA[m1.groupValues[2]] ?: return null
                para = UNIDADES_MEDIDA[m1.groupValues[3]] ?: return null
            }
            else -> return null
        }
        if (de.grandeza != para.grandeza || de == para) return null
        val resultado = if (de.grandeza == "temperatura") {
            val celsius = when (de.fator) {
                0.0 -> valor
                1.0 -> (valor - 32) * 5 / 9
                else -> valor - 273.15
            }
            when (para.fator) {
                0.0 -> celsius
                1.0 -> celsius * 9 / 5 + 32
                else -> celsius + 273.15
            }
        } else {
            valor * de.fator / para.fator
        }
        val arredondado = if (abs(resultado) >= 10) (resultado * 100).roundToLong() / 100.0 else resultado
        return "${numero(valor)} ${de.nome} dão ${numero(arredondado)} ${para.nome}."
    }

    // ================================================================= datas
    private val QUANTOS_DIAS = Regex("^(?:quantos dias|quanto tempo) (?:faltam?|falta) (?:para|pra|ate) (?:o |a |os |as )?(.+)$")
    private val QUANDO_E = Regex(
        "^(?:quando (?:e|eh|vai ser|sera|cai) (?:o |a )?|que dia (?:e|eh|cai|vai ser|sera) (?:o |a )?|em que dia (?:cai|e) (?:o |a )?|qual (?:e |eh )?a data d[oae] )(.+)$"
    )

    fun datas(c: String, hoje: Calendar = Calendar.getInstance()): String? {
        val e = c.trim().trimEnd('?', '.', '!')
        QUANTOS_DIAS.find(e)?.let { m ->
            val (nome, data) = dataEspecial(m.groupValues[1], hoje) ?: return null
            val dias = diasEntre(hoje, data)
            val quando = SimpleDateFormat("EEEE, d 'de' MMMM", BR).format(data.time)
            return when (dias) {
                0L -> "É hoje! ${nome.replaceFirstChar { it.uppercase() }} é hoje, $quando."
                1L -> "Falta 1 dia para $nome: amanhã, $quando."
                else -> "Faltam $dias dias para $nome ($quando)."
            }
        }
        QUANDO_E.find(e)?.let { m ->
            val (nome, data) = dataEspecial(m.groupValues[1], hoje) ?: return null
            val quando = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", BR).format(data.time)
            val dias = diasEntre(hoje, data)
            val falta = when (dias) {
                0L -> " É hoje!"
                1L -> " É amanhã!"
                else -> " Faltam $dias dias."
            }
            return "${nome.replaceFirstChar { it.uppercase() }} cai em $quando.$falta"
        }
        return null
    }

    private fun diasEntre(a: Calendar, b: Calendar): Long {
        val x = zerar(a).timeInMillis
        val y = zerar(b).timeInMillis
        return Math.floorDiv(y - x + TimeUnit.HOURS.toMillis(12), TimeUnit.DAYS.toMillis(1))
    }

    private fun zerar(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private val MESES = listOf("janeiro", "fevereiro", "marco", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    /** Nome bonito e a próxima data (hoje ou depois) de uma data comemorativa ou de "25 de dezembro". */
    fun dataEspecial(texto: String, hoje: Calendar): Pair<String, Calendar>? {
        val t = texto.trim().removePrefix("o ").removePrefix("a ").trim()
        fun proxima(calc: (Int) -> Calendar): Calendar {
            val ano = hoje.get(Calendar.YEAR)
            val d = calc(ano)
            return if (diasEntre(hoje, d) < 0) calc(ano + 1) else d
        }
        fun fixa(mes: Int, dia: Int) = proxima { ano -> Calendar.getInstance().apply { clear(); set(ano, mes, dia) } }
        fun pascoa(ano: Int): Calendar {
            // Algoritmo de Meeus/Jones/Butcher
            val a = ano % 19; val b = ano / 100; val c = ano % 100; val d = b / 4; val e = b % 4
            val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
            val mes = (h + l - 7 * m + 114) / 31
            val dia = ((h + l - 7 * m + 114) % 31) + 1
            return Calendar.getInstance().apply { clear(); set(ano, mes - 1, dia) }
        }
        fun domingo(mes: Int, ordem: Int) = proxima { ano ->
            Calendar.getInstance().apply {
                clear(); set(ano, mes, 1)
                while (get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) add(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.DAY_OF_MONTH, 7 * (ordem - 1))
            }
        }
        val especiais: List<Pair<Regex, () -> Pair<String, Calendar>>> = listOf(
            Regex("^natal") to { "o Natal" to fixa(Calendar.DECEMBER, 25) },
            Regex("^(ano novo|reveillon|virada)") to { "o Ano Novo" to fixa(Calendar.JANUARY, 1) },
            Regex("^carnaval") to { "o Carnaval" to proxima { pascoa(it).apply { add(Calendar.DAY_OF_MONTH, -47) } } },
            Regex("^(pascoa)") to { "a Páscoa" to proxima { pascoa(it) } },
            Regex("^(sexta feira santa|sexta santa|paixao de cristo)") to { "a Sexta-feira Santa" to proxima { pascoa(it).apply { add(Calendar.DAY_OF_MONTH, -2) } } },
            Regex("^corpus christi") to { "Corpus Christi" to proxima { pascoa(it).apply { add(Calendar.DAY_OF_MONTH, 60) } } },
            Regex("^dia das maes") to { "o Dia das Mães" to domingo(Calendar.MAY, 2) },
            Regex("^dia dos pais") to { "o Dia dos Pais" to domingo(Calendar.AUGUST, 2) },
            Regex("^dia dos namorados") to { "o Dia dos Namorados" to fixa(Calendar.JUNE, 12) },
            Regex("^dia das criancas") to { "o Dia das Crianças" to fixa(Calendar.OCTOBER, 12) },
            Regex("^(dia do consumidor)") to { "o Dia do Consumidor" to fixa(Calendar.MARCH, 15) },
            Regex("^(dia da mulher|dia internacional da mulher)") to { "o Dia da Mulher" to fixa(Calendar.MARCH, 8) },
            Regex("^(halloween|dia das bruxas)") to { "o Halloween" to fixa(Calendar.OCTOBER, 31) },
            Regex("^(finados|dia de finados)") to { "Finados" to fixa(Calendar.NOVEMBER, 2) },
            Regex("^(tiradentes)") to { "Tiradentes" to fixa(Calendar.APRIL, 21) },
            Regex("^(dia do trabalho|dia do trabalhador)") to { "o Dia do Trabalho" to fixa(Calendar.MAY, 1) },
            Regex("^(independencia|7 de setembro|sete de setembro)") to { "a Independência" to fixa(Calendar.SEPTEMBER, 7) },
            Regex("^(nossa senhora aparecida|dia da padroeira)") to { "Nossa Senhora Aparecida" to fixa(Calendar.OCTOBER, 12) },
            Regex("^(proclamacao da republica)") to { "a Proclamação da República" to fixa(Calendar.NOVEMBER, 15) },
            Regex("^(consciencia negra|dia da consciencia negra)") to { "o Dia da Consciência Negra" to fixa(Calendar.NOVEMBER, 20) },
            Regex("^black friday") to {
                "a Black Friday" to proxima { ano ->
                    Calendar.getInstance().apply {
                        clear(); set(ano, Calendar.NOVEMBER, 1)
                        while (get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY) add(Calendar.DAY_OF_MONTH, 1)
                        add(Calendar.DAY_OF_MONTH, 22) // quarta quinta-feira + 1 dia
                    }
                }
            },
            Regex("^(dia do cliente)") to { "o Dia do Cliente" to fixa(Calendar.SEPTEMBER, 15) },
        )
        for ((regra, calc) in especiais) if (regra.containsMatchIn(t)) return calc()
        // "25 de dezembro", "dia 10 de março", "10/03"
        Regex("^(?:dia )?(\\d{1,2})(?: de |/)(${MESES.joinToString("|")}|\\d{1,2})\\b").find(t)?.let { m ->
            val dia = m.groupValues[1].toInt()
            val mes = m.groupValues[2].toIntOrNull()?.minus(1) ?: MESES.indexOf(m.groupValues[2])
            if (dia !in 1..31 || mes !in 0..11) return null
            val nome = "$dia de ${SimpleDateFormat("MMMM", BR).format(Calendar.getInstance().apply { clear(); set(2000, mes, 1) }.time)}"
            return nome to fixa(mes, dia)
        }
        return null
    }
}
