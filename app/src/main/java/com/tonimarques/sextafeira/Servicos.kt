package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Serviços públicos e gratuitos (sem chave), úteis no dia a dia e na loja:
 *  - CEP (BrasilAPI, com ViaCEP de reserva);
 *  - CNPJ (BrasilAPI: razão social, situação, cidade, atividade);
 *  - DDD (de que estado e cidades é);
 *  - feriados nacionais (BrasilAPI);
 *  - tradução (MyMemory).
 * As funções com rede devem rodar fora da thread principal; as de "montar" são testáveis.
 */
object Servicos {

    private val BR = Locale("pt", "BR")
    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ CEP
    fun cep(numero: String): String {
        val n = numero.filter { it.isDigit() }
        if (n.length != 8) return "O CEP precisa ter 8 números."
        return try {
            montarCep(JSONObject(Web.texto("https://brasilapi.com.br/api/cep/v1/$n")), n)
        } catch (e: Exception) {
            val j = JSONObject(Web.texto("https://viacep.com.br/ws/$n/json/"))
            if (j.optBoolean("erro")) return "Não encontrei o CEP ${formatarCep(n)}."
            montarCep(
                JSONObject()
                    .put("street", j.optString("logradouro")).put("neighborhood", j.optString("bairro"))
                    .put("city", j.optString("localidade")).put("state", j.optString("uf")), n
            )
        }
    }

    private val ESTADOS = mapOf(
        "acre" to "AC", "alagoas" to "AL", "amapa" to "AP", "amazonas" to "AM", "bahia" to "BA", "ceara" to "CE",
        "distrito federal" to "DF", "espirito santo" to "ES", "goias" to "GO", "maranhao" to "MA", "mato grosso" to "MT",
        "mato grosso do sul" to "MS", "minas gerais" to "MG", "para" to "PA", "paraiba" to "PB", "parana" to "PR",
        "pernambuco" to "PE", "piaui" to "PI", "rio de janeiro" to "RJ", "rio grande do norte" to "RN",
        "rio grande do sul" to "RS", "rondonia" to "RO", "roraima" to "RR", "santa catarina" to "SC",
        "sao paulo" to "SP", "sergipe" to "SE", "tocantins" to "TO",
    )
    private val TIPOS_DE_RUA = Regex("^(rua|r|avenida|av|travessa|tv|alameda|estrada|praca|rodovia|largo|beco|viela|servidao|acesso)\\.?\\s+")

    /** Sigla do estado a partir do nome ("Rio Grande do Sul" → "RS") ou da própria sigla. */
    fun uf(nome: String): String? {
        val n = ComandosLocais.normalizar(nome).trim()
        if (n.length == 2 && n.uppercase(BR) in ESTADOS.values) return n.uppercase(BR)
        return ESTADOS[n]
    }

    private fun caminho(s: String) = codificar(s).replace("+", "%20")

    /**
     * CEP pelo endereço: "Rua Ernesto Vidal Martins" em "Porto Alegre" (ViaCEP).
     * Descobre o estado da cidade pela busca de cidades da Open-Meteo.
     */
    fun buscarCep(logradouro: String, cidade: String, estado: String = ""): String {
        var uf = uf(estado)
        var nomeCidade = cidade.trim()
        if (uf == null) {
            val lugar = Web.procurarCidade(nomeCidade) ?: return "Não encontrei a cidade $cidade."
            nomeCidade = lugar.nome.substringBefore(",").trim()
            uf = uf(lugar.nome.substringAfter(",", "").trim()) ?: return "Não consegui saber o estado de $cidade."
        }
        val semTipo = TIPOS_DE_RUA.replace(ComandosLocais.normalizar(logradouro).trim(), "")
        val palavras = semTipo.split(" ").filter { it.length >= 3 }
        val tentativas = listOf(semTipo, palavras.takeLast(2).joinToString(" "), palavras.maxByOrNull { it.length }.orEmpty())
            .filter { it.length >= 3 }.distinct()
        for (termo in tentativas) {
            val j = try {
                JSONArray(Web.texto("https://viacep.com.br/ws/$uf/${caminho(nomeCidade)}/${caminho(termo)}/json/"))
            } catch (e: Exception) {
                continue
            }
            val texto = montarBuscaCep(j, palavras)
            if (texto != null) return texto
        }
        return "Não encontrei o CEP de $logradouro em $nomeCidade - $uf. Confira o nome da rua."
    }

    /** Separado da rede para ser testado. Prefere as ruas que têm mais palavras do pedido. */
    fun montarBuscaCep(j: JSONArray, palavras: List<String>): String? {
        val itens = (0 until j.length()).mapNotNull { j.optJSONObject(it) }.filter { it.optString("cep").isNotBlank() }
        if (itens.isEmpty()) return null
        val ordenados = itens.sortedByDescending { o ->
            val l = ComandosLocais.normalizar(o.optString("logradouro"))
            palavras.count { l.contains(it) }
        }
        val melhores = ordenados.distinctBy { it.optString("cep") }.take(3)
        val partes = melhores.map { o ->
            val bairro = o.optString("bairro").takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
            val comp = o.optString("complemento").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            "${o.optString("logradouro")}$comp$bairro: ${o.optString("cep")}"
        }
        val cidade = melhores.first().let { "${it.optString("localidade")} - ${it.optString("uf")}" }
        val mais = if (itens.size > 3) " Há mais ${itens.size - 3} resultados parecidos." else ""
        return if (partes.size == 1) "O CEP de ${partes[0].replace(": ", " é ")}, em $cidade.$mais"
        else "Encontrei em $cidade: ${partes.joinToString("; ")}.$mais"
    }

    fun formatarCep(n: String) = if (n.length == 8) n.substring(0, 5) + "-" + n.substring(5) else n

    fun montarCep(j: JSONObject, n: String): String {
        val partes = listOf(j.optString("street"), j.optString("neighborhood"))
            .filter { it.isNotBlank() && it != "null" }
        val cidade = listOf(j.optString("city"), j.optString("state")).filter { it.isNotBlank() && it != "null" }.joinToString(" - ")
        val onde = (partes + cidade).filter { it.isNotBlank() }.joinToString(", ")
        return if (onde.isBlank()) "Não encontrei o CEP ${formatarCep(n)}." else "O CEP ${formatarCep(n)} é de $onde."
    }

    // ----------------------------------------------------------------- CNPJ
    fun cnpj(numero: String): String {
        val n = numero.filter { it.isDigit() }
        if (n.length != 14) return "O CNPJ precisa ter 14 números."
        return montarCnpj(JSONObject(Web.texto("https://brasilapi.com.br/api/cnpj/v1/$n")))
    }

    fun montarCnpj(j: JSONObject): String {
        val razao = j.optString("razao_social").ifBlank { return "Não encontrei esse CNPJ." }
        val fantasia = j.optString("nome_fantasia").takeIf { it.isNotBlank() && it != "null" && !it.equals(razao, true) }
        val situacao = j.optString("descricao_situacao_cadastral").lowercase(BR).takeIf { it.isNotBlank() && it != "null" }
        val cidade = listOf(j.optString("municipio"), j.optString("uf")).filter { it.isNotBlank() && it != "null" }
            .joinToString(" - ") { it.lowercase(BR).split(" ").joinToString(" ") { p -> if (p.length > 2) p.replaceFirstChar(Char::uppercase) else p } }
            .replace(Regex("- (\\w\\w)$")) { "- " + it.groupValues[1].uppercase(BR) }
        val atividade = j.optString("cnae_fiscal_descricao").takeIf { it.isNotBlank() && it != "null" }
        val abertura = j.optString("data_inicio_atividade").takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }
            ?.let { it.substring(8, 10) + "/" + it.substring(5, 7) + "/" + it.substring(0, 4) }
        val partes = mutableListOf("É a empresa $razao" + (if (fantasia != null) " ($fantasia)" else ""))
        if (cidade.isNotBlank()) partes += "de $cidade"
        var texto = partes.joinToString(", ") + "."
        if (situacao != null) texto += " Situação: $situacao."
        if (atividade != null) texto += " Atividade: ${atividade.lowercase(BR)}."
        if (abertura != null) texto += " Aberta em $abertura."
        return texto
    }

    // ------------------------------------------------------------------ DDD
    fun ddd(numero: String): String {
        val n = numero.filter { it.isDigit() }
        if (n.length != 2) return "O DDD tem 2 números."
        return montarDdd(JSONObject(Web.texto("https://brasilapi.com.br/api/ddd/v1/$n")), n)
    }

    fun montarDdd(j: JSONObject, n: String): String {
        val estado = j.optString("state")
        val cidades = j.optJSONArray("cities") ?: JSONArray()
        if (estado.isBlank()) return "Não encontrei o DDD $n."
        val nomes = (0 until cidades.length()).map { cidades.optString(it) }
            .map { it.lowercase(BR).split(" ").joinToString(" ") { p -> if (p.length > 2) p.replaceFirstChar(Char::uppercase) else p } }
            .sorted()
        val exemplos = nomes.take(4).joinToString(", ")
        val mais = if (nomes.size > 4) " e mais ${nomes.size - 4} cidades" else ""
        return "O DDD $n é de $estado: $exemplos$mais."
    }

    // ------------------------------------------------------------- feriados
    data class Feriado(val data: Calendar, val nome: String)

    fun proximosFeriados(hoje: Calendar = Calendar.getInstance()): String {
        val ano = hoje.get(Calendar.YEAR)
        val lista = lerFeriados(JSONArray(Web.texto("https://brasilapi.com.br/api/feriados/v1/$ano"))) +
            try {
                lerFeriados(JSONArray(Web.texto("https://brasilapi.com.br/api/feriados/v1/${ano + 1}")))
            } catch (e: Exception) {
                emptyList()
            }
        return montarFeriados(lista, hoje)
    }

    fun lerFeriados(j: JSONArray): List<Feriado> {
        val f = SimpleDateFormat("yyyy-MM-dd", BR)
        return (0 until j.length()).mapNotNull { i ->
            val o = j.optJSONObject(i) ?: return@mapNotNull null
            val data = try {
                f.parse(o.optString("date"))
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            Feriado(Calendar.getInstance().apply { time = data }, o.optString("name"))
        }
    }

    fun montarFeriados(lista: List<Feriado>, hoje: Calendar): String {
        val inicio = (hoje.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val proximos = lista.filter { !it.data.before(inicio) }.sortedBy { it.data.timeInMillis }.take(3)
        if (proximos.isEmpty()) return "Não encontrei os próximos feriados."
        val f = SimpleDateFormat("EEEE, d 'de' MMMM", BR)
        val primeiro = proximos.first()
        val dias = TimeUnit.MILLISECONDS.toDays(primeiro.data.timeInMillis - inicio.timeInMillis + TimeUnit.HOURS.toMillis(12))
        val quando = when (dias) {
            0L -> "é hoje"
            1L -> "é amanhã"
            else -> "falta${if (dias > 1) "m" else ""} $dias dias"
        }
        var texto = "O próximo feriado nacional é ${primeiro.nome}, ${f.format(primeiro.data.time)} ($quando)."
        if (proximos.size > 1) {
            texto += " Depois vêm " + proximos.drop(1).joinToString(" e ") { "${it.nome} (${SimpleDateFormat("d/MM", BR).format(it.data.time)})" } + "."
        }
        return texto
    }

    // ------------------------------------------------------------ tradução
    val IDIOMAS = mapOf(
        "ingles" to "en", "espanhol" to "es", "castelhano" to "es", "frances" to "fr", "italiano" to "it",
        "alemao" to "de", "japones" to "ja", "chines" to "zh-CN", "mandarim" to "zh-CN", "coreano" to "ko",
        "russo" to "ru", "arabe" to "ar", "holandes" to "nl", "grego" to "el", "hebraico" to "he", "turco" to "tr",
        "polones" to "pl", "sueco" to "sv", "hindi" to "hi", "portugues" to "pt-BR", "latim" to "la",
    )

    private val NOMES_IDIOMAS = mapOf(
        "en" to "inglês", "es" to "espanhol", "fr" to "francês", "it" to "italiano", "de" to "alemão", "ja" to "japonês",
        "zh-CN" to "chinês", "ko" to "coreano", "ru" to "russo", "ar" to "árabe", "nl" to "holandês", "el" to "grego",
        "he" to "hebraico", "tr" to "turco", "pl" to "polonês", "sv" to "sueco", "hi" to "hindi", "pt-BR" to "português",
        "la" to "latim",
    )

    fun traduzir(texto: String, para: String, de: String = "pt-BR"): String {
        val origem = if (para == "pt-BR" && de == "pt-BR") "en" else de
        val j = JSONObject(
            Web.texto("https://api.mymemory.translated.net/get?langpair=" + codificar("$origem|$para") + "&q=" + codificar(texto))
        )
        val traducao = j.optJSONObject("responseData")?.optString("translatedText").orEmpty()
        if (traducao.isBlank() || traducao.contains("MYMEMORY WARNING", true) || traducao.contains("INVALID", true)) {
            return "Não consegui traduzir agora."
        }
        val idioma = NOMES_IDIOMAS[para] ?: para
        return "Em $idioma: $traducao"
    }
}
