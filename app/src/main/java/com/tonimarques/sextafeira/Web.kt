package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Informações da internet que não precisam de chave nem de pagamento:
 * previsão do tempo (Open-Meteo) e resumos da Wikipédia.
 * Tudo aqui faz acesso à rede: chamar sempre fora da thread principal.
 */
object Web {

    data class Fonte(val titulo: String, val url: String)
    data class Resposta(val texto: String, val fontes: List<Fonte> = emptyList())

    /** "Fontes: Título (https://...); Outro (https://...)" (como fica guardado na conversa) → lista. */
    fun lerFontesSalvas(texto: String): List<Fonte> =
        Regex("([^;]+?)\\s*\\((https?://[^)\\s]+)\\)").findAll(texto.removePrefix("Fontes: "))
            .map { Fonte(it.groupValues[1].trim().removePrefix(";").trim(), it.groupValues[2]) }.toList()

    private const val AGENTE = "SextaFeira/1.0 (assistente pessoal Android)"

    fun texto(
        endereco: String,
        cabecalhos: Map<String, String> = emptyMap(),
        aceitar: String = "application/json",
    ): String {
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = 8_000
            conexao.readTimeout = 12_000
            conexao.setRequestProperty("User-Agent", AGENTE)
            conexao.setRequestProperty("Accept", aceitar)
            for ((k, v) in cabecalhos) conexao.setRequestProperty(k, v)
            val codigo = conexao.responseCode
            if (codigo !in 200..299) throw java.io.IOException("HTTP $codigo")
            return conexao.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conexao.disconnect()
        }
    }

    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8")

    // --------------------------------------------------------------- clima
    data class Lugar(val nome: String, val latitude: Double, val longitude: Double)

    fun procurarCidade(nome: String): Lugar? {
        val json = JSONObject(
            texto("https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name=" + codificar(nome))
        )
        val r = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val estado = r.optString("admin1")
        val nomeCompleto = r.optString("name") + if (estado.isNotBlank() && estado != r.optString("name")) ", $estado" else ""
        return Lugar(nomeCompleto, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    /**
     * [dia] = "hoje", "amanha", "semana" (próximos 7 dias), "fim_de_semana", "horas" (a que horas chove hoje)
     * ou "horas_amanha". [nomeLugar] vazio quando veio do GPS.
     */
    fun previsao(latitude: Double, longitude: Double, nomeLugar: String, dia: String): Resposta {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code,relative_humidity_2m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            (if (dia.startsWith("horas")) "&hourly=precipitation_probability,weather_code" else "") +
            "&timezone=auto&forecast_days=" + (if (dia == "semana" || dia == "fim_de_semana") 8 else 2)
        val json = JSONObject(texto(url))
        return Resposta(montarPrevisao(json, nomeLugar, dia), listOf(Fonte("Open-Meteo", "https://open-meteo.com")))
    }

    /** Separado da rede para poder ser testado. */
    fun montarPrevisao(json: JSONObject, nomeLugar: String, dia: String): String {
        val onde = if (nomeLugar.isBlank()) "Aí onde você está" else "Em $nomeLugar"
        val diario = json.getJSONObject("daily")
        when (dia) {
            "semana", "fim_de_semana" -> return montarSemana(diario, onde, dia == "fim_de_semana")
            "horas", "horas_amanha" -> json.optJSONObject("hourly")?.let { return montarHoras(it, onde, dia == "horas_amanha", json.optJSONObject("current")?.optString("time")) }
        }
        val i = if (dia == "amanha") 1 else 0
        val max = diario.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()
        val min = diario.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
        val chuva = diario.optJSONArray("precipitation_probability_max")?.optInt(i, -1) ?: -1
        val codigoDia = diario.getJSONArray("weather_code").getInt(i)
        val textoChuva = if (chuva >= 0) ", com $chuva% de chance de chuva" else ""

        if (dia == "amanha") {
            return "$onde, amanhã: ${descricaoTempo(codigoDia)}, máxima de $max e mínima de $min graus$textoChuva."
        }
        val atual = json.getJSONObject("current")
        val agora = atual.getDouble("temperature_2m").roundToInt()
        val sensacao = atual.getDouble("apparent_temperature").roundToInt()
        val ceu = descricaoTempo(atual.getInt("weather_code"))
        val sens = if (sensacao != agora) ", sensação de $sensacao" else ""
        return "$onde agora faz $agora graus$sens, $ceu. Hoje a máxima é de $max e a mínima de $min$textoChuva."
    }

    private val DIAS_SEMANA = listOf("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado")

    private fun diaDaSemana(data: String): String {
        val c = java.util.Calendar.getInstance().apply {
            clear()
            val (a, m, d) = data.take(10).split("-").map { it.toInt() }
            set(a, m - 1, d)
        }
        return DIAS_SEMANA[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }

    /** Próximos 7 dias (a partir de amanhã) ou só sábado e domingo. */
    fun montarSemana(diario: JSONObject, onde: String, soFimDeSemana: Boolean): String {
        val datas = diario.getJSONArray("time")
        val dias = (1 until datas.length()).map { i ->
            val nome = diaDaSemana(datas.getString(i))
            val max = diario.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()
            val min = diario.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
            val chuva = diario.optJSONArray("precipitation_probability_max")?.optInt(i, -1) ?: -1
            val ceu = descricaoTempo(diario.getJSONArray("weather_code").getInt(i)).removePrefix("com ")
            Triple(nome, "$nome: $min a $max graus, $ceu" + (if (chuva >= 40) ", $chuva% de chuva" else ""), chuva)
        }.let { lista -> if (soFimDeSemana) lista.filter { it.first == "sábado" || it.first == "domingo" }.take(2) else lista.take(7) }
        if (dias.isEmpty()) return "$onde: não consegui a previsão desses dias."
        val chuvosos = dias.filter { it.third >= 60 }.map { it.first }
        val resumo = when {
            chuvosos.isEmpty() -> "Sem dias com chuva forte prevista."
            chuvosos.size == 1 -> "Leve guarda-chuva na ${chuvosos[0]}."
            else -> "Dias com mais chance de chuva: ${chuvosos.dropLast(1).joinToString(", ")} e ${chuvosos.last()}."
        }
        return (if (soFimDeSemana) "$onde no fim de semana: " else "$onde nos próximos dias: ") + dias.joinToString("; ") { it.second } + ". " + resumo
    }

    /** A que horas tem mais chance de chover (hoje, a partir de agora, ou amanhã). */
    fun montarHoras(horario: JSONObject, onde: String, amanha: Boolean, agoraIso: String?): String {
        val horas = horario.getJSONArray("time")
        val prob = horario.getJSONArray("precipitation_probability")
        val hoje = (agoraIso ?: horas.getString(0)).take(10)
        val alvo = if (amanha) horas.getString(minOf(24, horas.length() - 1)).take(10) else hoje
        val desde = if (amanha || agoraIso == null) "" else agoraIso.take(13)
        val pontos = (0 until horas.length()).mapNotNull { i ->
            val t = horas.getString(i)
            if (t.take(10) != alvo || t.take(13) < desde) null else t.substring(11, 13).toInt() to prob.optInt(i, 0)
        }
        val quando = if (amanha) "Amanhã" else "Hoje"
        if (pontos.isEmpty()) return "$onde: não consegui a previsão por hora."
        val pico = pontos.maxByOrNull { it.second }!!
        if (pico.second < 20) return "$onde, ${quando.lowercase()} a chance de chuva é baixa o tempo todo: no máximo ${pico.second}%, por volta das ${pico.first}h."
        val acima = pontos.filter { it.second >= 40 }
        var t = "$onde, ${quando.lowercase()} a maior chance de chuva é por volta das ${pico.first}h (${pico.second}%)."
        if (acima.size >= 2) t += " Entre ${acima.first().first}h e ${acima.last().first + 1}h a chance fica acima de 40%."
        else if (acima.isEmpty()) t += " No resto do dia fica abaixo de 40%."
        return t
    }

    /** Códigos de tempo da OMM usados pela Open-Meteo. */
    fun descricaoTempo(codigo: Int): String = when (codigo) {
        0 -> "céu limpo"
        1 -> "céu quase limpo"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "com neblina"
        51, 53, 55 -> "com garoa"
        56, 57 -> "com garoa congelante"
        61 -> "com chuva fraca"
        63 -> "com chuva moderada"
        65 -> "com chuva forte"
        66, 67 -> "com chuva congelante"
        71, 73, 75, 77 -> "com neve"
        80, 81 -> "com pancadas de chuva"
        82 -> "com pancadas de chuva fortes"
        85, 86 -> "com pancadas de neve"
        95 -> "com trovoadas"
        96, 99 -> "com trovoadas e granizo"
        else -> "com tempo variável"
    }

    // ------------------------------------------------------------- wikipédia
    fun wikipedia(assunto: String): Resposta? {
        val busca = JSONArray(
            texto("https://pt.wikipedia.org/w/api.php?action=opensearch&limit=1&namespace=0&format=json&search=" + codificar(assunto))
        )
        val titulo = busca.optJSONArray(1)?.optString(0).orEmpty()
        if (titulo.isBlank()) return null
        return resumoWikipedia(titulo)
    }

    /** Resumo de um artigo da Wikipédia pelo título exato. */
    fun resumoWikipedia(titulo: String): Resposta? {
        val resumo = JSONObject(
            texto("https://pt.wikipedia.org/api/rest_v1/page/summary/" + codificar(titulo.replace(' ', '_')).replace("+", "%20"))
        )
        val extrato = resumo.optString("extract")
        if (extrato.isBlank() || resumo.optString("type") == "disambiguation") return null
        val link = resumo.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
            ?: "https://pt.wikipedia.org/wiki/" + titulo.replace(' ', '_')
        return Resposta(primeirasFrases(extrato, 3), listOf(Fonte("Wikipédia: $titulo", link)))
    }

    fun primeirasFrases(texto: String, quantas: Int): String {
        val frases = Regex("(?<=[.!?])\\s+").split(texto.trim())
        return frases.take(quantas).joinToString(" ").trim()
    }

    /** "o que é X", "quem foi X", "me fala sobre X" → X. Null se não for uma pergunta desse tipo. */
    fun assuntoEnciclopedico(frase: String): String? {
        val f = ComandosLocais.limpar(frase)
        val m = Regex(
            "^(?:o que (?:e|eh|sao|significa)|que (?:e|eh) (?:um|uma)|quem (?:e|eh|foi|era|sao|foram)|" +
                "me (?:fala|fale|conta|conte) (?:sobre|de|do|da)|(?:fala|fale|explica|explique) (?:sobre|o que e)|" +
                "significado de|definicao de|historia d[oae])\\s+(.+)$"
        ).matchEntire(f.chave) ?: return null
        val assunto = f.trecho(m.groups[1])?.trim()?.trimEnd('?', '.', '!')
            ?.replace(Regex("^(?i)(o|a|os|as|um|uma)\\s+"), "")
        return assunto?.takeIf { it.isNotBlank() && it.length <= 80 }
    }
}
