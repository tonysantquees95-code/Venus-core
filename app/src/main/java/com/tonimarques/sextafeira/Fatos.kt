package com.tonimarques.sextafeira

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Perguntas de fato respondidas pela Wikidata (a base de dados da Wikipédia, sem chave):
 * capital, população, presidente, CEO, fundador, data de fundação, nascimento, idade, altura,
 * área, moeda, idioma, autor, diretor, sede, onde nasceu...
 * Ex.: "qual a capital da Austrália", "quantos anos tem o Neymar", "quem fundou a Apple".
 */
object Fatos {

    data class Pergunta(val tipo: String, val propriedades: List<String>, val sujeito: String, val prep: String)

    private val BR = Locale("pt", "BR")

    private class Regra(val tipo: String, val props: List<String>, val regex: Regex)

    private val P = "(?:o |a |os |as )?"
    private val DE = "(d[oa]s?|de)"
    private val REGRAS = listOf(
        Regra("capital", listOf("P36"), Regex("^qual (?:e |eh )?(?:a )?capital $DE (.+)$")),
        Regra("populacao", listOf("P1082"), Regex("^(?:qual (?:e |eh )?(?:a )?populacao $DE |quantos habitantes (?:tem|possui|ha em|existem em) ($P)|quantas pessoas (?:moram|vivem) (no|na|em|nos|nas) )(.+)$")),
        Regra("presidente", listOf("P35", "P6", "P488", "P169"), Regex("^quem (?:e|eh) (?:o |a )?(?:atual )?(?:presidente|presidenta|chefe de estado) $DE (.+)$")),
        Regra("governante", listOf("P6", "P35"), Regex("^quem (?:e|eh) (?:o |a )?(?:atual )?(?:primeiro ministro|primeira ministra|governador|governadora|prefeito|prefeita) $DE (.+)$")),
        Regra("ceo", listOf("P169", "P488", "P127"), Regex("^quem (?:e|eh) (?:o |a )?(?:atual )?(?:ceo|diretor executivo|diretora executiva|dono|dona) $DE (.+)$")),
        Regra("fundador", listOf("P112", "P170", "P61"), Regex("^(?:quem (?:fundou|criou|inventou) ($P)|quem (?:e|eh|foi) (?:o |a )?(?:fundador|fundadora|criador|criadora|inventor|inventora) $DE )(.+)$")),
        Regra("fundacao", listOf("P571"), Regex("^(?:quando (?:foi )?(?:fundad[oa]|criad[oa]|inaugurad[oa]) ($P)|em que ano (?:foi fundad[oa]|foi criad[oa]|surgiu) ($P))(.+)$")),
        Regra("nascimento", listOf("P569"), Regex("^(?:quando (?:nasceu|e o aniversario d[eoa]) ($P)|qual (?:e |eh )?a data de nascimento $DE )(.+)$")),
        Regra("idade", listOf("P569"), Regex("^(?:quantos anos tem ($P)|qual (?:e |eh )?a idade $DE )(.+)$")),
        Regra("morte", listOf("P570"), Regex("^(?:quando morreu ($P)|quando faleceu ($P))(.+)$")),
        Regra("altura", listOf("P2048"), Regex("^(?:qual (?:e |eh )?a altura $DE |quanto mede ($P))(.+)$")),
        Regra("area", listOf("P2046"), Regex("^(?:qual (?:e |eh )?(?:o tamanho|a area) $DE )(.+)$")),
        Regra("moeda", listOf("P38"), Regex("^qual (?:e |eh )?a moeda $DE (.+)$")),
        Regra("idioma", listOf("P37"), Regex("^(?:qual (?:e |eh )?(?:a lingua|o idioma)(?: oficial)? $DE |que lingua (?:se )?fala(?:m)? (no|na|nos|nas|em) )(.+)$")),
        Regra("autor", listOf("P50"), Regex("^(?:quem escreveu ($P)|quem (?:e|eh|foi) (?:o |a )?(?:autor|autora|escritor|escritora) $DE )(.+)$")),
        Regra("diretor", listOf("P57"), Regex("^(?:quem dirigiu ($P)|quem (?:e|eh|foi) (?:o |a )?(?:diretor|diretora) do filme )(.+)$")),
        Regra("sede", listOf("P159"), Regex("^(?:onde fica a sede $DE |onde e a sede $DE )(.+)$")),
        Regra("local_nascimento", listOf("P19"), Regex("^onde nasceu ($P)(.+)$")),
    )

    /** Separado da rede para ser testado. Recebe a frase normalizada (sem acentos). */
    fun entender(c: String): Pergunta? {
        val t = c.trim().trimEnd('?', '.', '!').replace(Regex("\\s+"), " ")
        for (r in REGRAS) {
            val m = r.regex.matchEntire(t) ?: continue
            val grupos = m.groupValues.drop(1)
            val sujeito = grupos.last().trim().removePrefix("o ").removePrefix("a ").trim()
            if (sujeito.isBlank() || sujeito.length > 60) continue
            val prep = grupos.dropLast(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return Pergunta(r.tipo, r.props, sujeito, prep)
        }
        return null
    }

    fun entende(pergunta: String) = entender(ComandosLocais.normalizar(pergunta)) != null

    // ------------------------------------------------------------- rede
    private fun codificar(s: String) = URLEncoder.encode(s, "UTF-8")
    private const val API = "https://www.wikidata.org/w/api.php?format=json&"

    /** Responde usando a Wikidata. [original] é a frase com acentos, para pegar o nome bonito do assunto. */
    fun responder(original: String): Web.Resposta? {
        val p = entender(ComandosLocais.normalizar(original)) ?: return null
        val sujeitoOriginal = trechoOriginal(original, p.sujeito)
        val busca = JSONObject(Web.texto(API + "action=wbsearchentities&language=pt&uselang=pt&type=item&limit=1&search=" + codificar(sujeitoOriginal)))
        val item = busca.optJSONArray("search")?.optJSONObject(0) ?: return null
        val id = item.optString("id")
        val nome = item.optString("label").ifBlank { sujeitoOriginal }
        for (prop in p.propriedades) {
            val claims = JSONObject(Web.texto(API + "action=wbgetclaims&entity=$id&property=$prop"))
                .optJSONObject("claims")?.optJSONArray(prop) ?: continue
            val escolhidas = escolher(claims, p.tipo)
            if (escolhidas.isEmpty()) continue
            val textos = escolhidas.mapNotNull { valorLegivel(it, p.tipo) }
            if (textos.isEmpty()) continue
            val texto = frase(p, prop, nome, textos) ?: continue
            return Web.Resposta(texto, listOf(Web.Fonte("Wikidata: $nome", "https://www.wikidata.org/wiki/$id")))
        }
        return null
    }

    private fun trechoOriginal(original: String, sujeitoNormal: String): String {
        val normal = ComandosLocais.normalizar(original)
        val i = normal.lastIndexOf(sujeitoNormal)
        return if (i >= 0 && normal.length == original.length) original.substring(i, i + sujeitoNormal.length) else sujeitoNormal
    }

    /** Valores atuais: rank preferido, sem data de fim; população: a mais recente. */
    private fun escolher(claims: org.json.JSONArray, tipo: String): List<JSONObject> {
        val todas = (0 until claims.length()).mapNotNull { claims.optJSONObject(it) }
            .filter { it.optString("rank") != "deprecated" && it.optJSONObject("mainsnak")?.optString("snaktype") == "value" }
        if (todas.isEmpty()) return emptyList()
        if (tipo == "populacao") {
            val maisRecente = todas.maxByOrNull { anoQualificador(it, "P585") }
            return listOfNotNull(todas.firstOrNull { it.optString("rank") == "preferred" } ?: maisRecente)
        }
        val atuais = todas.filter { it.optJSONObject("qualifiers")?.has("P582") != true }.ifEmpty { todas }
        val preferidas = atuais.filter { it.optString("rank") == "preferred" }
        val lista = preferidas.ifEmpty { atuais }
        return if (tipo in setOf("fundador", "autor", "diretor", "idioma")) lista.take(4) else lista.take(1)
    }

    private fun anoQualificador(claim: JSONObject, prop: String): Int {
        val t = claim.optJSONObject("qualifiers")?.optJSONArray(prop)?.optJSONObject(0)
            ?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time").orEmpty()
        return Regex("^[+-](\\d{4})").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun valorLegivel(claim: JSONObject, tipo: String): String? {
        val dv = claim.optJSONObject("mainsnak")?.optJSONObject("datavalue") ?: return null
        return when (dv.optString("type")) {
            "wikibase-entityid" -> rotulo(dv.optJSONObject("value")?.optString("id").orEmpty())
            "quantity" -> {
                val v = dv.optJSONObject("value") ?: return null
                val numero = v.optString("amount").removePrefix("+").toDoubleOrNull() ?: return null
                val unidade = UNIDADES[v.optString("unit").substringAfterLast("/")].orEmpty()
                val ano = anoQualificador(claim, "P585")
                val base = Calculadora.numero(numero) + if (unidade.isNotBlank()) " $unidade" else ""
                if (tipo == "populacao" && ano > 0) "$base|$ano" else base
            }
            "time" -> dv.optJSONObject("value")?.let { formatarData(it.optString("time"), it.optInt("precision", 11)) }
            "string", "monolingualtext" -> dv.optString("value")
            else -> null
        }
    }

    private val UNIDADES = mapOf(
        "Q11573" to "m", "Q174728" to "cm", "Q828224" to "km", "Q712226" to "km²", "Q25343" to "m²",
        "Q11570" to "kg", "Q35852" to "hectares",
    )

    private fun rotulo(id: String): String? {
        if (id.isBlank()) return null
        val j = JSONObject(Web.texto(API + "action=wbgetentities&props=labels&languages=pt-br|pt|en&ids=$id"))
        val labels = j.optJSONObject("entities")?.optJSONObject(id)?.optJSONObject("labels") ?: return null
        for (l in listOf("pt-br", "pt", "en")) labels.optJSONObject(l)?.optString("value")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** "+1992-02-05T00:00:00Z" → "5 de fevereiro de 1992" (ou só o ano, se for o que se sabe). */
    fun formatarData(tempo: String, precisao: Int): String? {
        val m = Regex("^([+-])(\\d{1,4})-(\\d{2})-(\\d{2})").find(tempo) ?: return null
        val ano = m.groupValues[2].toInt()
        if (m.groupValues[1] == "-") return "$ano a.C."
        if (precisao <= 9) return "$ano"
        val mes = m.groupValues[3].toInt().coerceAtLeast(1)
        val dia = m.groupValues[4].toInt()
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(ano, mes - 1, dia.coerceAtLeast(1)) }
        val f = SimpleDateFormat(if (precisao == 10 || dia == 0) "MMMM 'de' yyyy" else "d 'de' MMMM 'de' yyyy", BR)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(c.time)
    }

    private fun idadeDe(data: String): Int? {
        val f = SimpleDateFormat("d 'de' MMMM 'de' yyyy", BR)
        val nasc = try {
            f.parse(data)
        } catch (e: Exception) {
            null
        } ?: return null
        val n = Calendar.getInstance().apply { time = nasc }
        val hoje = Calendar.getInstance()
        var anos = hoje.get(Calendar.YEAR) - n.get(Calendar.YEAR)
        if (hoje.get(Calendar.DAY_OF_YEAR) < n.get(Calendar.DAY_OF_YEAR)) anos--
        return anos
    }

    private fun lista(v: List<String>) = if (v.size <= 1) v.firstOrNull().orEmpty() else v.dropLast(1).joinToString(", ") + " e " + v.last()

    /** Monta a resposta em português. Pública para testes. */
    fun frase(p: Pergunta, prop: String, nome: String, valores: List<String>): String? {
        val de = when (p.prep) {
            "do", "da", "dos", "das", "de" -> p.prep
            "no" -> "do"; "na" -> "da"; "nos" -> "dos"; "nas" -> "das"
            else -> "de"
        }
        val v = lista(valores)
        return when (p.tipo) {
            "capital" -> "A capital $de $nome é $v."
            "populacao" -> {
                val (numero, ano) = valores.first().split("|").let { it[0] to it.getOrNull(1) }
                "$nome tem cerca de $numero habitantes" + (if (ano != null) ", segundo dados de $ano." else ".")
            }
            "presidente" -> when (prop) {
                "P169" -> "O CEO $de $nome é $v."
                "P488" -> "Quem preside $nome é $v."
                "P6" -> "Quem chefia o governo $de $nome é $v."
                else -> "O presidente $de $nome é $v, segundo a Wikidata."
            }
            "governante" -> "Quem governa $nome é $v, segundo a Wikidata."
            "ceo" -> if (prop == "P127") "$nome pertence a $v." else "Quem comanda $nome é $v, segundo a Wikidata."
            "fundador" -> if (prop == "P61") "$nome foi criado por $v." else "$nome foi fundado por $v."
            "fundacao" -> "$nome foi fundado em $v."
            "nascimento" -> "$nome nasceu em $v."
            "idade" -> idadeDe(valores.first())?.let { "$nome tem $it anos (nasceu em ${valores.first()})." }
                ?: "$nome nasceu em ${valores.first()}."
            "morte" -> "$nome morreu em $v."
            "altura" -> "$nome tem $v de altura."
            "area" -> "A área $de $nome é de $v."
            "moeda" -> "A moeda $de $nome é: $v."
            "idioma" -> if (valores.size > 1) "Os idiomas oficiais $de $nome são $v." else "O idioma oficial $de $nome é $v."
            "autor" -> "$nome foi escrito por $v."
            "diretor" -> "$nome foi dirigido por $v."
            "sede" -> "A sede $de $nome fica em $v."
            "local_nascimento" -> "$nome nasceu em $v."
            else -> null
        }
    }
}
