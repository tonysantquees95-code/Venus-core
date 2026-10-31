package com.tonimarques.sextafeira

import kotlin.math.abs
import kotlin.math.min

/**
 * Corrige palavras que o reconhecimento de voz (ou a digitação) entendeu errado,
 * comparando com o vocabulário que a Vênus conhece:
 *  - pelo SOM (chave fonética do português: "lanterma" ~ "lanterna", "xuva" ~ "chuva");
 *  - pela ESCRITA (letras trocadas, faltando ou sobrando);
 *  - pelo CONTEXTO (qual palavra costuma vir depois da anterior nos exemplos).
 * Nomes próprios (com maiúscula no meio da frase) e números não são mexidos.
 */
object Corretor {

    private val EXTRAS = (
        "abre abrir toca tocar coloca liga ligar desliga desligar acende apaga lanterna musica musicas proxima anterior " +
            "volume aumenta abaixa diminui pausa pausar continua alarme despertador timer temporizador minutos minuto " +
            "segundos horas hora mensagem manda mande envia whatsapp zap pesquisa pesquisar procura previsao tempo graus " +
            "chuva chover chovendo amanha hoje agora configuracoes bluetooth wifi camera foto selfie bateria cotacao dolar " +
            "euro bitcoin libra noticias noticia novidades lancamentos lancamento celular celulares smartphone acompanha " +
            "acompanhar lembra lembre esquece favorita favorito playlist spotify youtube instagram facebook telegram mapa " +
            "rota navegar ligacao contato contatos alarme acorda frio calor temperatura clima loja produto produtos estoque " +
            "pedido pedidos vendas cadastra cadastrar preco valor quanto custa tecnologia futebol jogo resultado " +
            "some soma somar subtrai tira divide multiplica metade dobro triplo porcentagem desconto frete total significa sinonimo plural"
        ).split(" ")

    /** Vocabulário conhecido, com frequência (mais usado = mais provável). */
    private val VOCABULARIO: Map<String, Int> by lazy {
        val contagem = HashMap<String, Int>()
        fun somar(p: String, peso: Int) {
            if (p.length >= 2 && p.none { it.isDigit() }) contagem[p] = (contagem[p] ?: 0) + peso
        }
        for (i in BancoDeFrases.INTENCOES) for (ex in i.exemplos) for (p in palavras(ex)) somar(p, 2)
        for (p in BancoDeFrases.SINONIMOS.keys) somar(p, 1)
        for (p in EXTRAS) somar(p, 3)
        contagem
    }

    /** Pares "palavra anterior → palavra" vistos nos exemplos, para usar o contexto. */
    private val PARES: Set<String> by lazy {
        val pares = HashSet<String>()
        for (i in BancoDeFrases.INTENCOES) for (ex in i.exemplos) {
            val ps = palavras(ex).filter { it !in BancoDeFrases.IRRELEVANTES }
            for (k in 1 until ps.size) pares += ps[k - 1] + " " + ps[k]
        }
        pares
    }

    private val POR_CHAVE: Map<String, List<String>> by lazy {
        VOCABULARIO.keys.groupBy { chaveFonetica(it) }
    }

    fun palavras(texto: String): List<String> =
        ComandosLocais.normalizar(texto).replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.isNotBlank() }

    fun conhece(palavra: String) = palavra in VOCABULARIO

    /**
     * Devolve a frase corrigida (palavras trocadas ficam sem acento) ou a própria frase,
     * se não houver nada para corrigir.
     */
    fun corrigir(frase: String): String {
        val pedacos = frase.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        var anterior: String? = null
        var mudou = false
        val saida = pedacos.mapIndexed { i, original ->
            val limpa = ComandosLocais.normalizar(original).replace(Regex("[^a-z0-9]"), "")
            val trocar = if (deveCorrigir(original, limpa, i)) melhorCandidata(limpa, anterior) else null
            val final = trocar ?: limpa
            if (final.isNotBlank() && final !in BancoDeFrases.IRRELEVANTES) anterior = final
            if (trocar != null && trocar != limpa) {
                mudou = true
                // mantém a pontuação que vinha grudada na palavra
                val sobra = original.takeLastWhile { !it.isLetterOrDigit() }
                trocar + sobra
            } else {
                original
            }
        }
        return if (mudou) saida.joinToString(" ") else frase
    }

    private fun deveCorrigir(original: String, limpa: String, posicao: Int): Boolean {
        if (limpa.length < 4) return false
        if (limpa.any { it.isDigit() }) return false
        if (limpa in VOCABULARIO) return false
        // Nome próprio no meio da frase: deixa como está ("toca Anitta", "manda pra Marcela").
        if (posicao > 0 && original.firstOrNull()?.isUpperCase() == true) return false
        return true
    }

    private fun melhorCandidata(palavra: String, anterior: String?): String? {
        val chave = chaveFonetica(palavra)
        val candidatas = HashSet<String>()
        POR_CHAVE[chave]?.let { candidatas += it }
        val limite = if (palavra.length >= 7) 2 else 1
        for (v in VOCABULARIO.keys) {
            if (abs(v.length - palavra.length) > limite) continue
            if (v.length < 3) continue
            if (distancia(palavra, v) <= limite) candidatas += v
        }
        if (candidatas.isEmpty()) return null
        return candidatas.minByOrNull { v ->
            var custo = distancia(palavra, v).toDouble()
            if (chaveFonetica(v) == chave) custo -= 0.8
            if (anterior != null && "$anterior $v" in PARES) custo -= 0.6
            if (v.firstOrNull() == palavra.firstOrNull()) custo -= 0.2
            custo -= min(VOCABULARIO[v] ?: 0, 10) * 0.02
            custo
        }
    }

    /** Uma "chave de som" do português: palavras que soam igual ficam com a mesma chave. */
    fun chaveFonetica(palavra: String): String {
        var s = ComandosLocais.normalizar(palavra).replace(Regex("[^a-z]"), "")
        if (s.isEmpty()) return s
        s = s.replace("ph", "f")
            .replace("ch", "x").replace("sh", "x")
            .replace("lh", "li").replace("nh", "ni")
            .replace(Regex("qu(?=[ei])"), "k").replace("qu", "ku")
            .replace(Regex("gu(?=[ei])"), "g")
            .replace(Regex("c(?=[ei])"), "s").replace("c", "k")
            .replace(Regex("g(?=[ei])"), "j")
            .replace("ss", "s").replace("sc", "s").replace("xc", "s")
            .replace("z", "s").replace("y", "i").replace("w", "u").replace("k", "k")
        if (s.startsWith("h")) s = s.drop(1)
        s = s.replace("h", "")
        s = s.replace(Regex("l$"), "u").replace(Regex("am$"), "ao").replace(Regex("m$"), "n")
            .replace(Regex("e$"), "i").replace(Regex("o$"), "u").replace(Regex("os$"), "us").replace(Regex("es$"), "is")
        // letras repetidas viram uma só
        val sb = StringBuilder()
        for (ch in s) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    /** Distância de edição com troca de letras vizinhas contando como um erro só. */
    fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + custo)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
            }
        }
        return d[n][m]
    }
}
