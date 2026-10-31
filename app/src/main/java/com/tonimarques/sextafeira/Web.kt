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

    private const val AGENTE = "SextaFeira/1.0 (assistente pessoal Android)"

    fun texto(endereco: String, cabecalhos: Map<String, String> = emptyMap()): String {
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = 8_000
            conexao.readTimeout = 12_000
            conexao.setRequestProperty("User-Agent", AGENTE)
            conexao.setRequestProperty("Accept", "application/json")
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

    /** [dia] = "hoje" ou "amanha". [nomeLugar] vazio quando veio do GPS. */
    fun previsao(latitude: Double, longitude: Double, nomeLugar: String, dia: String): Resposta {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code,relative_humidity_2m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=2"
        val json = JSONObject(texto(url))
        return Resposta(montarPrevisao(json, nomeLugar, dia), listOf(Fonte("Open-Meteo", "https://open-meteo.com")))
    }

    /** Separado da rede para poder ser testado. */
    fun montarPrevisao(json: JSONObject, nomeLugar: String, dia: String): String {
        val onde = if (nomeLugar.isBlank()) "Aí onde você está" else "Em $nomeLugar"
        val diario = json.getJSONObject("daily")
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
