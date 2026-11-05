package com.tonimarques.sextafeira

import android.content.Context
import org.json.JSONObject

/**
 * Entender pelo sentido, no próprio celular: a frase vira um vetor (embedding do Gemini, uma chamada leve e rápida)
 * e é comparada pela similaridade de cosseno com tudo o que a Vênus já aprendeu (memória vetorial, SQLite).
 * Se achar algo com o mesmo sentido, ela resolve sozinha, sem chamar o modelo principal do Gemini:
 *  - "como tá nosso insta" ≈ "como está o Instagram" → instagram_resumo;
 *  - uma charada já resolvida, dita de outro jeito → a resposta conferida;
 *  - uma pergunta atemporal já respondida → a mesma resposta.
 */
object Semantica {

    /** O que fazer com o que foi achado. */
    sealed class Decisao(val nota: Double, val id: Long) {
        class Executar(val acao: String, val args: Map<String, Any>, nota: Double, id: Long) : Decisao(nota, id)
        /** Frase aprendida do vocabulário: o cérebro local resolve usando a frase guardada. */
        class Frase(val frase: String, nota: Double, id: Long) : Decisao(nota, id)
        class Responder(val texto: String, nota: Double, id: Long) : Decisao(nota, id)
    }

    /** Notas mínimas (0 a 1). Comandos sem argumentos aceitam mais variação; com argumentos, só frases quase iguais. */
    const val LIMIAR_COMANDO = 0.88
    const val LIMIAR_COMANDO_COM_ARGS = 0.96
    const val LIMIAR_INTENCAO = 0.88
    const val LIMIAR_RESPOSTA = 0.93
    const val LIMIAR_ENIGMA = 0.91

    private val cache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 60
    }

    private fun chave(frase: String) = ComandosLocais.normalizar(frase).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** O vetor da frase (guardado por alguns minutos para não pedir de novo ao aprender depois). */
    fun vetorDe(frase: String, chaveApi: String): FloatArray? {
        val k = chave(frase)
        if (k.isEmpty()) return null
        synchronized(cache) { cache[k] }?.let { return it }
        val v = EmbeddingsGemini.gerar(frase, chaveApi) ?: return null
        synchronized(cache) { cache[k] = v }
        return v
    }

    /** Já vetorizada nesta sessão (sem chamar a internet). */
    private fun vetorGuardado(frase: String): FloatArray? = synchronized(cache) { cache[chave(frase)] }

    /**
     * Procura na memória vetorial. [soRespostas]: para perguntas de raciocínio, só respostas e enigmas
     * (nunca transforma uma pergunta em comando). Chamada de rede: rode fora da thread principal.
     */
    fun entender(memoria: Memoria, chaveApi: String, frase: String, soRespostas: Boolean = false): Decisao? {
        if (chave(frase).split(" ").size < 2) return null
        val v = vetorDe(frase, chaveApi) ?: return null
        val tipos = if (soRespostas) setOf("resposta", "enigma", RegrasDaLoja.TIPO) else setOf("comando", "intencao", "resposta", "enigma", RegrasDaLoja.TIPO)
        val achados = memoria.buscarVetores(v, EmbeddingsGemini.MODELO, tipos, minimo = 0.8, quantos = 3)
        for (a in achados) decidir(a, frase)?.let { return it }
        return null
    }

    /** A regra de decisão, separada para poder ser testada. */
    fun decidir(a: Memoria.Achado, frase: String): Decisao? {
        val i = a.item
        return when (i.tipo) {
            "comando" -> {
                val rota = Roteador.lerRota(JSONObject().put("acao", i.acao ?: return null).put("args", jsonOuVazio(i.args))) ?: return null
                when {
                    rota.args.isEmpty() && a.nota >= LIMIAR_COMANDO -> Decisao.Executar(rota.acao, rota.args, a.nota, i.id)
                    a.nota >= LIMIAR_COMANDO_COM_ARGS -> Decisao.Executar(rota.acao, rota.args, a.nota, i.id)
                    else -> null
                }
            }
            "intencao" -> if (a.nota >= LIMIAR_INTENCAO) Decisao.Frase(i.texto, a.nota, i.id) else null
            "resposta" -> if (a.nota >= LIMIAR_RESPOSTA && !i.resposta.isNullOrBlank() && vale(frase, i.resposta)) Decisao.Responder(i.resposta, a.nota, i.id) else null
            RegrasDaLoja.TIPO -> if (a.nota >= LIMIAR_RESPOSTA && !i.resposta.isNullOrBlank()) Decisao.Responder(i.resposta, a.nota, i.id) else null
            "enigma" -> if (a.nota >= LIMIAR_ENIGMA && !i.resposta.isNullOrBlank()) Decisao.Responder(i.resposta, a.nota, i.id) else null
            else -> null
        }
    }

    private fun jsonOuVazio(s: String?): JSONObject = try {
        JSONObject(s ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    // ------------------------------------------------------------------ aprender (em segundo plano)
    /** Frase que virou ação: guarda com o vetor que já foi gerado ao entender (sem nova chamada). */
    fun aprenderComando(memoria: Memoria, frase: String, acao: String, args: Map<String, Any>, fonte: String) {
        if (acao == "criar_lembrete" || chave(frase).length > 160) return
        val v = vetorGuardado(frase)
        memoria.guardarVetor("comando", frase, acao = acao, args = JSONObject(args).toString(), fonte = fonte, vetor = v, modelo = v?.let { EmbeddingsGemini.MODELO })
    }

    /** Pergunta atemporal respondida pela IA: fica para a próxima vez, mesmo dita de outro jeito. */
    fun aprenderResposta(memoria: Memoria, pergunta: String, resposta: String, tipo: String = "resposta") {
        if (!vale(pergunta, resposta)) return
        val v = vetorGuardado(pergunta)
        memoria.guardarVetor(tipo, pergunta, resposta = resposta, fonte = "conversa", vetor = v, modelo = v?.let { EmbeddingsGemini.MODELO })
    }

    private val PESSOAL = Regex("\\b(?:meu|minha|meus|minhas|eu|comigo|voce lembra|loja|pedido|cliente|venda|estoque|caixa)\\b")

    /** Só guarda respostas que servem de novo: sem data, sem assunto pessoal ou da loja, nem respostas de erro. */
    fun vale(pergunta: String, resposta: String): Boolean {
        val p = ComandosLocais.normalizar(pergunta)
        return resposta.length in 10..2500 && Conhecimento.vale(pergunta) && !PESSOAL.containsMatchIn(p) &&
            !resposta.startsWith("Não consegui") && !resposta.startsWith("Algo deu errado") &&
            !RECUSA.containsMatchIn(ComandosLocais.normalizar(resposta))
    }

    /** Respostas de "não sei / não consigo": nunca viram memória (senão ela repete a recusa para sempre). */
    private val RECUSA = Regex(
        "\\b(?:(?:ainda )?nao (?:consigo|posso|tenho como|tenho acesso|sei fazer|e possivel|da para)|infelizmente|" +
            "sou (?:apenas |so )?uma (?:ia|inteligencia artificial|assistente virtual)|nao tenho (?:essa|esta) (?:funcao|capacidade))\\b"
    )

    /** Tira da memória as respostas guardadas que não valem mais (recusas, assuntos pessoais). */
    fun limparRespostasRuins(memoria: Memoria): Int = memoria.apagarRespostas { pergunta, resposta -> !vale(pergunta, resposta) }

    // ------------------------------------------------------------------ banco antigo → vetores
    /** Uma vez: copia o que ela sabia em texto para a memória vetorial. */
    fun migrarSePreciso(ctx: Context, memoria: Memoria): Int {
        val prefs = ctx.getSharedPreferences("semantica", Context.MODE_PRIVATE)
        if (prefs.getInt("migrado", 0) >= VERSAO_MIGRACAO) return 0
        val n = memoria.migrarTextoParaVetores(Roteador.todas(ctx))
        prefs.edit().putInt("migrado", VERSAO_MIGRACAO).apply()
        return n
    }

    private const val VERSAO_MIGRACAO = 1

    /** Gera os vetores que faltam, em lotes de 100. Devolve quantos foram feitos. */
    fun completar(memoria: Memoria, chaveApi: String, limite: Int = 400): Int {
        var feitos = 0
        while (feitos < limite) {
            val modelo = EmbeddingsGemini.MODELO
            val pendentes = memoria.vetoresPendentes(modelo, minOf(100, limite - feitos))
            if (pendentes.isEmpty()) break
            val vetores = EmbeddingsGemini.gerarVarios(pendentes.map { it.second }, chaveApi) ?: break
            memoria.salvarVetores(pendentes.map { it.first }.zip(vetores), EmbeddingsGemini.MODELO)
            feitos += pendentes.size
        }
        // as frases antigas do vocabulário (tabela do seu código) também
        try {
            feitos += memoria.completarVetores(chaveApi, limite = 50)
        } catch (e: Exception) {
        }
        return feitos
    }
}
