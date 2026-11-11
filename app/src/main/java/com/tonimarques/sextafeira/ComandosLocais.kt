package com.tonimarques.sextafeira

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entende os comandos do dia a dia direto no celular, sem internet e sem IA.
 * Se a frase não for um comando conhecido, devolve null e o pedido vai para o Gemini.
 *
 * Para ensinar uma frase nova, adicione uma regra em interpretar() ou ajuste uma das regras abaixo.
 */
object ComandosLocais {

    sealed class Resultado {
        /** Executar a ferramenta [nome] (a mesma que o Gemini usa) com estes argumentos. */
        data class Acao(val nome: String, val args: Map<String, Any>) : Resultado()

        /** Responder direto, sem executar nada. */
        data class Resposta(val texto: String) : Resultado()
    }

    /** A frase original (com acentos) e uma versão sem acentos e minúscula, do mesmo tamanho. */
    class Frase(val original: String, val chave: String) {
        fun trecho(grupo: MatchGroup?): String? =
            grupo?.range?.let { original.substring(it.first, it.last + 1).trim() }
    }

    fun interpretar(texto: String): Resultado? {
        val f = limpar(texto)
        val c = f.chave
        if (c.isBlank()) return null

        // Pedidos com duas ações ("abre o YouTube e toca rock") ficam para a IA.
        if (COMPOSTO.containsMatchIn(c)) return null
        // Clima, cotações e notícias vêm antes do filtro de perguntas: "quanto está o dólar?" é um comando.
        // ...mas não quando é uma mensagem: "manda pra Maria que vai chover" é mensagem, não previsão.
        val ehRecado = RECADO.containsMatchIn(c)
        // Contas, conversões, datas, tradução, CEP...: antes do clima ("30 graus em fahrenheit" é conversão).
        // Mudanças na loja ("muda o preço do gel para 29,90") antes das contas, para o número não virar cálculo.
        if (RELATORIO_ERROS.matches(c.trimEnd('.', '!', '?'))) return Resultado.Acao("enviar_relatorio_erros", emptyMap())
        instagram(c)?.let { return it }
        palavraExiste(f)?.let { return it }
        conversaAntiga(f)?.let { return it }
        precoNaInternet(f)?.let { return it }
        if (!ehRecado) gestao(f)?.let { return it }
        if (!ehRecado) lojaAlteracao(c)?.let { return it }
        LOJA_CADASTRAR.matchEntire(c)?.let { m -> return cadastro(f.trecho(m.groups[1] ?: m.groups[2]).orEmpty()) }
        if (EDITAR_FUNDO.containsMatchIn(c)) return Resultado.Acao("editar_fundo", emptyMap())
        if (LER_NOTA.containsMatchIn(c)) return Resultado.Acao("ler_nota", emptyMap())
        if (ABRIR_ARQUIVO.containsMatchIn(c)) return Resultado.Acao("abrir_arquivo", emptyMap())
        lojaPrevisao(f)?.let { return it }
        financas(f)?.let { return it }
        if (LISTAR_POSTS.containsMatchIn(c)) return Resultado.Acao("listar_posts", emptyMap())
        if (RESPONDER_CLIENTE.containsMatchIn(c)) return Resultado.Acao("responder_cliente", emptyMap())
        PROGRAMAR_POST.matchEntire(c)?.let { m -> return programarPost(m.groupValues[1], f.trecho(m.groups[2]).orEmpty()) }
        if (!ehRecado) Calculadora.responder(c)?.let { return Resultado.Resposta(it) }
        // Conhecimentos gerais que ela sabe de cor (português, ciências, geografia, matemática)
        if (!ehRecado) Saber.responder(c)?.let { return Resultado.Resposta(it) }
        if (!ehRecado) servicos(f)?.let { return it }
        if (!ehRecado) clima(f)?.let { return it }
        if (!ehRecado) cotacao(c)?.let { return it }
        acompanhar(f)?.let { return it }
        noticias(f)?.let { return it }
        loja(f)?.let { return it }
        if (LISTAR_LEMBRETES.containsMatchIn(c)) return Resultado.Acao("listar_lembretes", emptyMap())
        mensagens(f)?.let { return it }
        CANCELAR_LEMBRETE.matchEntire(c)?.let { m -> return Resultado.Acao("cancelar_lembrete", mapOf("trecho" to (f.trecho(m.groups[1]) ?: ""))) }
        perto(f)?.let { return it }
        retomar(c)?.let { return it }
        // "como chego no shopping" é rota, não pergunta aberta
        if (Regex("^como (?:eu )?(?:chego|chegar|vou|faco para chegar|faco pra chegar)\\b").containsMatchIn(c)) mapa(f)?.let { return it }
        // Perguntas abertas também.
        if (PERGUNTA.containsMatchIn(c) && !HORA.containsMatchIn(c) && !DATA.containsMatchIn(c)) return null

        return ajuda(c)
            ?: horaOuData(c)
            ?: lanterna(c)
            ?: timer(c)
            ?: alarme(c)
            ?: configuracao(c)
            ?: midia(c)
            ?: camera(c)
            ?: mensagem(f)
            ?: ligacao(f)
            ?: mapa(f)
            ?: pesquisa(f)
            ?: site(f)
            ?: musica(f)
            ?: abrirApp(f)
    }

    // ---------------------------------------------------------------- limpeza
    fun normalizar(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
            sb.append(Character.toLowerCase(base))
        }
        return sb.toString()
    }

    fun limpar(texto: String): Frase {
        val original = texto.trim()
        val chave = normalizar(original)
        var ini = 0
        var fim = original.length
        val sobras = " ,.!?;:-"

        var mudou = true
        while (mudou) {
            mudou = false
            while (ini < fim && chave[ini] in sobras) ini++
            while (fim > ini && chave[fim - 1] in sobras) fim--
            val resto = chave.substring(ini, fim)
            val p = PREFIXO.find(resto)
            if (p != null && p.range.first == 0 && p.value.isNotEmpty()) {
                ini += p.value.length
                mudou = true
                continue
            }
            val s = SUFIXO.find(resto)
            if (s != null && s.value.isNotEmpty() && s.range.last == resto.length - 1) {
                fim = ini + s.range.first
                mudou = true
            }
        }
        return Frase(original.substring(ini, fim), chave.substring(ini, fim))
    }

    // ------------------------------------------------------------------ regras
    private fun ajuda(c: String): Resultado? {
        if (!AJUDA.matches(c)) return null
        return Resultado.Resposta(
            "Comandos que funciono mesmo sem internet: liga ou desliga a lanterna; abre o WhatsApp; " +
                "toca Henrique e Juliano; próxima música; pausa; aumenta o volume; alarme às 7; " +
                "timer de 5 minutos; manda pra Maria dizendo chego já; liga pra Maria; " +
                "me leva pra rodoviária; pesquisa receita de bolo; tira uma foto; abre o Wi-Fi; que horas são. " +
                "Para o resto, eu uso a inteligência artificial."
        )
    }

    private fun horaOuData(c: String): Resultado? {
        val agora = Date()
        val br = Locale("pt", "BR")
        return when {
            HORA.containsMatchIn(c) ->
                Resultado.Resposta("Agora são " + SimpleDateFormat("HH:mm", br).format(agora) + ".")
            DATA.containsMatchIn(c) ->
                Resultado.Resposta("Hoje é " + SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", br).format(agora) + ".")
            else -> null
        }
    }

    private fun clima(f: Frase): Resultado? {
        val c = f.chave
        if (!CLIMA.containsMatchIn(c)) return null
        if (Regex("\\b(timer|alarme|temporizador|cronometro|minutos?|segundos?)\\b").containsMatchIn(c)) return null
        val amanha = Regex("\\bamanha\\b").containsMatchIn(c)
        val dia = when {
            Regex("\\b(?:que horas?|qual (?:o )?horario|em que horario|a que horas?|horario (?:com|de) mais|hora de chuva|vai chover a que horas)\\b").containsMatchIn(c) &&
                Regex("\\bchuv|\\bchove").containsMatchIn(c) -> if (amanha) "horas_amanha" else "horas"
            Regex("\\b(?:fim de semana|final de semana|sabado e domingo)\\b").containsMatchIn(c) -> "fim_de_semana"
            Regex("\\b(?:semana|proximos dias|proximos \\d+ dias|essa semana|esta semana)\\b").containsMatchIn(c) -> "semana"
            amanha -> "amanha"
            else -> "hoje"
        }
        var cidade = ""
        // tira "no fim de semana", "para semana que vem"… (com espaços do mesmo tamanho, para não perder a posição no texto)
        val semPeriodo = Regex("\\b(?:(?:n[oa]|para|pra|neste|nesse|nessa|nesta|esse|essa|este|esta|da|de|do) )?(?:proxim[oa]s? )?(?:fim de semana|final de semana|semana que vem|semana|proximos \\d+ dias|proximos dias)\\b")
            .replace(c) { " ".repeat(it.value.length) }
        LUGAR_CLIMA.find(semPeriodo)?.let { m ->
            val bruto = f.trecho(m.groups[1]).orEmpty()
                .replace(Regex("(?i)\\s+(hoje|amanh[ãa]|agora|de manh[ãa]|a tarde|à tarde|a noite|à noite)$"), "")
                .trim()
            val periodo = Regex("\\b(?:semana|dias|fim de semana|final de semana|horas?|horario|manha|tarde|noite|hoje|amanha)\\b")
            if (bruto.isNotBlank() && normalizar(bruto) !in LUGARES_GENERICOS && !periodo.containsMatchIn(normalizar(bruto))) cidade = bruto
        }
        return Resultado.Acao("previsao_do_tempo", mapOf("cidade" to cidade, "dia" to dia))
    }

    private fun cotacao(c: String): Resultado? {
        val m = MOEDA.find(c) ?: return null
        if (!Regex("\\b(quanto|cotacao|valor|preco|ta|esta|hoje|agora|vale|custa)\\b").containsMatchIn(c) &&
            !Regex("^(o |a )?(dolar|euro|bitcoin|libra)( hoje| agora)?$").matches(c)
        ) return null
        return Resultado.Acao("cotacao_moeda", mapOf("moeda" to m.groupValues[1]))
    }

    private fun acompanhar(f: Frase): Resultado? {
        val c = f.chave
        if (Regex("^(?:quais|que) (?:sao )?(?:os )?(?:assuntos|temas|noticias)( que)? (?:voce )?(?:acompanha|segue|esta acompanhando)$").matches(c)) {
            return Resultado.Acao("acompanhar_tema", mapOf("acao" to "listar", "tema" to ""))
        }
        if (Regex("^(?:atualiza|atualize|atualizar|baixa|baixe) (?:as )?(?:noticias|novidades)$").matches(c)) {
            return Resultado.Acao("atualizar_noticias", emptyMap())
        }
        PARAR_ACOMPANHAR.matchEntire(c)?.let { m ->
            val tema = f.trecho(m.groups[1]) ?: return null
            return Resultado.Acao("acompanhar_tema", mapOf("acao" to "parar", "tema" to tema))
        }
        ACOMPANHAR.matchEntire(c)?.let { m ->
            val tema = f.trecho(m.groups[1]) ?: return null
            if (tema.isBlank()) return null
            return Resultado.Acao("acompanhar_tema", mapOf("acao" to "seguir", "tema" to tema))
        }
        return null
    }

    /** CEP, CNPJ, DDD, feriados e tradução. */
    private fun servicos(f: Frase): Resultado? {
        val c = f.chave
        val digitos = c.filter { it.isDigit() }
        if (Regex("\\bcep\\b").containsMatchIn(c) && digitos.length == 8) {
            return Resultado.Acao("consultar_cep", mapOf("cep" to digitos))
        }
        // Distância: "qual a distância daqui até Brasília", "distância entre Porto Alegre e Caxias"
        DISTANCIA_DAQUI.matchEntire(c)?.let { m ->
            val destino = f.trecho(m.groups[1] ?: m.groups[2]).orEmpty().trim()
            if (destino.length >= 2) return Resultado.Acao("distancia", mapOf("origem" to "", "destino" to destino))
        }
        DISTANCIA_ENTRE.matchEntire(c)?.let { m ->
            val origem = f.trecho(m.groups[1]).orEmpty().trim()
            val destino = f.trecho(m.groups[2]).orEmpty().trim()
            if (origem.length >= 2 && destino.length >= 2) return Resultado.Acao("distancia", mapOf("origem" to origem, "destino" to destino))
        }
        // CEP pelo endereço: "qual o CEP da Rua X em Porto Alegre"
        if (digitos.length != 8) {
            for (regra in CEP_ENDERECO) {
                val m = regra.matchEntire(c) ?: continue
                val rua = f.trecho(m.groups[1]).orEmpty()
                val cidade = f.trecho(m.groups[2]).orEmpty()
                val estado = (if (m.groups.size > 3) f.trecho(m.groups[3]) else null).orEmpty()
                if (rua.length >= 3 && cidade.length >= 2) {
                    return Resultado.Acao("buscar_cep", mapOf("logradouro" to rua, "cidade" to cidade, "estado" to estado))
                }
            }
        }
        if (Regex("\\bcnpj\\b").containsMatchIn(c) && digitos.length == 14) {
            return Resultado.Acao("consultar_cnpj", mapOf("cnpj" to digitos))
        }
        if (Regex("\\bddd\\b").containsMatchIn(c) && digitos.length == 2) {
            return Resultado.Acao("consultar_ddd", mapOf("ddd" to digitos))
        }
        if (FERIADO.containsMatchIn(c)) return Resultado.Acao("feriados", emptyMap())
        // Dicionário: significado, sinônimos, antônimos, plural e escrita certa
        for ((regra, tipo) in DICIONARIO) {
            val m = regra.matchEntire(c) ?: continue
            val palavra = f.trecho(m.groups[1]).orEmpty().trim()
            if (palavra.length >= 2) return Resultado.Acao("dicionario", mapOf("tipo" to tipo, "palavra" to palavra))
        }
        for (regra in ORTOGRAFIA) {
            val m = regra.matchEntire(c) ?: continue
            val a = f.trecho(m.groups[1]).orEmpty()
            val b = f.trecho(m.groups[2]).orEmpty()
            if (a.length >= 3 && b.length >= 3 && !a.equals(b, true)) {
                return Resultado.Acao("ortografia", mapOf("a" to a, "b" to b))
            }
        }
        for ((regra, sentido) in TRADUCAO) {
            val m = regra.matchEntire(c) ?: continue
            val texto = f.trecho(m.groups[1]).orEmpty().trim().trim('"', '“', '”', '\'', ' ')
            val idioma = Servicos.IDIOMAS[m.groupValues[2]] ?: continue
            if (texto.isBlank()) continue
            val (de, para) = when {
                sentido == "de" -> idioma to "pt-BR"
                idioma == "pt-BR" -> "en" to "pt-BR" // "o que significa good morning em português"
                else -> "pt-BR" to idioma
            }
            if (de == para) continue
            return Resultado.Acao("traduzir", mapOf("texto" to texto, "para" to para, "de" to de))
        }
        return null
    }

    private fun noticias(f: Frase): Resultado? {
        val c = f.chave
        for (regra in NOTICIAS) {
            val m = regra.matchEntire(c) ?: continue
            val tema = f.trecho(m.groups[1]).orEmpty().trimEnd('?', '.', '!')
            return Resultado.Acao("noticias", mapOf("tema" to tema.ifBlank { "Brasil" }))
        }
        return null
    }

    private fun lanterna(c: String): Resultado? {
        if (!Regex("\\blanterna\\b").containsMatchIn(c)) return null
        val desligar = Regex("\\b(deslig\\w*|apag\\w*|desativ\\w*|tira\\w*)\\b").containsMatchIn(c)
        return Resultado.Acao("lanterna", mapOf("ligar" to !desligar))
    }

    private fun timer(c: String): Resultado? {
        val gatilho = Regex("\\b(timer|temporizador|cronometro|contagem regressiva|daqui a|daqui)\\b")
            .containsMatchIn(c) || c.startsWith("me avisa em") || c.startsWith("me avise em")
        if (!gatilho) return null
        var segundos = 0
        if (Regex("\\bmeia hora\\b").containsMatchIn(c)) segundos += 1800
        for (m in DURACAO.findAll(c)) {
            val n = numero(m.groupValues[1]) ?: continue
            val unidade = m.groupValues[2]
            segundos += when {
                unidade.startsWith("h") -> n * 3600
                unidade.startsWith("m") -> n * 60
                else -> n
            }
        }
        if (Regex("\\bminutos? e meio\\b").containsMatchIn(c)) segundos += 30
        if (segundos <= 0) return null
        return Resultado.Acao("criar_timer", mapOf("segundos" to segundos, "descricao" to ""))
    }

    private fun alarme(c: String): Resultado? {
        if (!Regex("\\b(alarme|despertador|desperta|me acorda|me acorde)\\b").containsMatchIn(c)) return null
        var hora: Int
        var minuto = 0
        when {
            Regex("\\bmeio[- ]dia\\b").containsMatchIn(c) -> hora = 12
            Regex("\\bmeia[- ]noite\\b").containsMatchIn(c) -> hora = 0
            else -> {
                val m = HORARIO.find(c) ?: return null
                hora = numero(m.groupValues[1]) ?: return null
                if (m.groupValues[2].isNotEmpty()) minuto = m.groupValues[2].toInt()
            }
        }
        val eMinutos = Regex("\\be\\s+(\\d{1,2}|meia|quinze|vinte|trinta|quarenta|cinquenta)\\b").find(c)
        if (minuto == 0 && eMinutos != null) {
            minuto = if (eMinutos.groupValues[1] == "meia") 30 else numero(eMinutos.groupValues[1]) ?: 0
        }
        if (Regex("\\b(da tarde|da noite)\\b").containsMatchIn(c) && hora < 12) hora += 12
        if (hora !in 0..23 || minuto !in 0..59) return null
        return Resultado.Acao("criar_alarme", mapOf("hora" to hora, "minuto" to minuto, "descricao" to ""))
    }

    private fun configuracao(c: String): Resultado? {
        val tipo = when {
            Regex("\\bwi[- ]?fi\\b").containsMatchIn(c) -> "wifi"
            Regex("\\bbluetooth\\b").containsMatchIn(c) -> "bluetooth"
            Regex("\\b(dados moveis|modo aviao|internet movel)\\b").containsMatchIn(c) -> "internet"
            Regex("\\bnao perturbe\\b").containsMatchIn(c) -> "nao_perturbe"
            Regex("\\b(localizacao|gps)\\b").containsMatchIn(c) -> "localizacao"
            Regex("\\bbrilho\\b").containsMatchIn(c) -> "tela"
            Regex("\\b(configuracoes|ajustes) d[ae] bateria\\b").containsMatchIn(c) -> "bateria"
            Regex("^(?:(?:abre|abrir|abra|abri|entra|entrar|mostra|acessa)\\s+)?(?:(?:as|os|a|o|nas|nos)\\s+)?" +
                "(configuracoes|configuracao|config|ajustes|definicoes)(?:\\s+d[oe]\\s+(celular|telefone|sistema|aparelho))?$")
                .matches(c) -> "geral"
            else -> return null
        }
        return Resultado.Acao("abrir_configuracao", mapOf("tipo" to tipo))
    }

    /** Perguntas sobre a loja: vendas, pedidos, estoque. */
    private fun loja(f: Frase): Resultado? {
        val c = f.chave
        fun periodo(padrao: String) = when {
            Regex("\\bhoje\\b").containsMatchIn(c) -> "hoje"
            Regex("\\bontem\\b").containsMatchIn(c) -> "ontem"
            Regex("\\b(semana|7 dias|sete dias)\\b").containsMatchIn(c) -> "semana"
            Regex("\\b(mes|mensal|30 dias|trinta dias)\\b").containsMatchIn(c) -> "mes"
            else -> padrao
        }
        lojaPrevisao(f)?.let { return it }
        if (LOJA_MARGENS.containsMatchIn(c)) return Resultado.Acao("loja_margens", emptyMap())
        LOJA_MARGEM.firstNotNullOfOrNull { it.matchEntire(c) }?.let { m ->
            val prod = f.trecho(m.groups[1]).orEmpty().trim()
            val chaveProd = normalizar(prod)
            if (!Regex("^(?:(?:de |d[oa] |n[oa] |deste |desta |neste |nesta )?(?:hoje|ontem|semana|mes|mes passado|esta semana|este mes|essa semana|esse mes))$").matches(chaveProd) && prod.length >= 3) {
                return Resultado.Acao("loja_margem", mapOf("produto" to prod))
            }
        }
        if (LOJA_LUCRO.containsMatchIn(c)) return Resultado.Acao("loja_lucro", mapOf("periodo" to periodo("mes")))
        if (LOJA_MAIS_VENDIDOS.containsMatchIn(c)) return Resultado.Acao("loja_mais_vendidos", mapOf("periodo" to periodo("mes")))
        if (LOJA_COMPARAR.containsMatchIn(c)) return Resultado.Acao("loja_comparar", emptyMap())
        if (LOJA_PARADOS.containsMatchIn(c)) return Resultado.Acao("loja_parados", emptyMap())
        if (LOJA_REPOSICAO.containsMatchIn(c)) {
            val periodo = when {
                Regex("\\bhoje\\b").containsMatchIn(c) -> "hoje"
                Regex("\\bontem\\b").containsMatchIn(c) -> "ontem"
                Regex("\\b(mes|mensal|30 dias|trinta dias)\\b").containsMatchIn(c) -> "mes"
                else -> "semana"
            }
            return Resultado.Acao("loja_reposicao", mapOf("periodo" to periodo))
        }
        LOJA_VENDAS.find(c)?.let {
            val periodo = when {
                Regex("\\bontem\\b").containsMatchIn(c) -> "ontem"
                Regex("\\b(semana|7 dias|sete dias)\\b").containsMatchIn(c) -> "semana"
                Regex("\\b(mes|mensal)\\b").containsMatchIn(c) -> "mes"
                else -> "hoje"
            }
            return Resultado.Acao("loja_resumo", mapOf("periodo" to periodo))
        }
        lojaAlteracao(c)?.let { return it }
        if (LOJA_RETIRADAS.containsMatchIn(c)) return Resultado.Acao("loja_retiradas", emptyMap())
        if (LOJA_PEDIDOS.containsMatchIn(c)) return Resultado.Acao("loja_pedidos", emptyMap())
        if (LOJA_ACABANDO.containsMatchIn(c)) return Resultado.Acao("loja_acabando", emptyMap())
        if (LOJA_ESTOQUE_GERAL.matches(c)) return Resultado.Acao("loja_estoque_geral", emptyMap())
        for (regra in LOJA_ESTOQUE) {
            val m = regra.matchEntire(c) ?: continue
            val produto = f.trecho(m.groups[1]).orEmpty().trim()
                .replace(Regex("^(?i)(o|a|os|as|do|da|dos|das|de)\\s+"), "").trim()
            // "quantos produtos eu tenho em estoque" é sobre o estoque todo, não sobre um produto chamado "produtos"
            if (normalizar(produto).replace(Regex("\\b(?:diferentes|no total|ao todo|cadastrad[oa]s)\\b"), "").trim() in PALAVRAS_GERAIS) {
                return Resultado.Acao("loja_estoque_geral", emptyMap())
            }
            if (produto.length >= 3) return Resultado.Acao("loja_estoque", mapOf("produto" to produto))
        }
        // Qualquer outra frase sobre o estoque: visão geral (melhor do que mandar para a IA, que não vê a loja)
        if (Regex("\\bestoque\\b").containsMatchIn(c)) return Resultado.Acao("loja_estoque_geral", emptyMap())
        // Pergunta sobre a loja que ela não entendeu: exemplos, em vez da IA (que não enxerga a loja)
        if (Regex("\\b(?:minha loja|nossa loja|meus produtos|nossos produtos|meus pedidos|nossos pedidos|minhas vendas|nossas vendas|meus clientes|minhas clientes)\\b").containsMatchIn(c)) {
            return Resultado.Resposta(
                "Sobre a loja eu entendo pedidos assim: como foram as vendas hoje; tem pedido novo; como está o estoque; o que preciso repor; " +
                    "mais vendidos do mês; produtos parados; quais retiradas estão para agendar; pedido 15 enviado com rastreio; coloca o gel em promoção por 24,90."
            )
        }
        return null
    }

    /**
     * Mudanças na loja por voz (sempre confirmadas depois): estoque, preço, mostrar/esconder produto,
     * agendar retirada e marcar pedido como entregue.
     */
    fun lojaAlteracao(chave: String): Resultado? {
        val limpa = chave.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        // primeiro como foi dito (nomes como "Kit Dois Corações" ficam intactos), depois com os números por extenso convertidos
        return lojaAlteracaoEm(limpa) ?: Calculadora.numerosPorExtenso(limpa).takeIf { it != limpa }?.let { lojaAlteracaoEm(it) }
    }

    private fun lojaAlteracaoEm(c: String): Resultado? {
        fun produto(s: String) = s.trim().replace(Regex("^(?:o|a|os|as|do|da|dos|das|de|produto|o produto)\\s+"), "").trim()
        fun acao(tipo: String, prod: String, valor: Int? = null): Resultado? {
            val p = produto(prod)
            if (p.length < 3) return null
            val args = mutableMapOf<String, Any>("tipo" to tipo, "produto" to p)
            if (valor != null) args["valor"] = valor
            return Resultado.Acao("loja_alterar", args)
        }
        LOJA_AGENDAR.firstNotNullOfOrNull { it.matchEntire(c) }?.let {
            return Resultado.Acao("loja_agendar", mapOf("pedido" to it.groupValues[1].toInt()))
        }
        LOJA_ENTREGUE.firstNotNullOfOrNull { it.matchEntire(c) }?.let {
            return Resultado.Acao("loja_entregue", mapOf("pedido" to it.groupValues[1].toInt()))
        }
        for ((status, regras) in LOJA_STATUS) {
            val m = regras.firstNotNullOfOrNull { it.matchEntire(c) } ?: continue
            val args = mutableMapOf<String, Any>("pedido" to m.groupValues[1].toInt(), "status" to status)
            m.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }?.let { args["rastreio"] = it }
            return Resultado.Acao("loja_status", args)
        }
        val falaDeEstoque = Regex("\\b(?:unidades?|estoque|pecas?)\\b").containsMatchIn(c)
        if (falaDeEstoque) {
            LOJA_SOMAR.matchEntire(c)?.let { m -> return acao("estoque_somar", m.groupValues[2], m.groupValues[1].toInt()) }
            LOJA_TIRAR.matchEntire(c)?.let { m -> return acao("estoque_tirar", m.groupValues[2], m.groupValues[1].toInt()) }
        }
        if (!Regex("\\bvendi (?:mais )?\\d+(?:[.,]\\d+)? ?(?:reais|real|no pix|em dinheiro|no dinheiro|no cartao|por fora)\\b|\\bvendi r\\$").containsMatchIn(c)) {
            // "vendi 2 géis": na loja sem site vira uma venda registrada; nas outras, tira do estoque
            LOJA_VENDI.matchEntire(c)?.let { m -> return acao("venda", m.groupValues[2], m.groupValues[1].toInt()) }
        }
        LOJA_DEFINIR_A.matchEntire(c)?.let { m -> return acao("estoque_definir", m.groupValues[1], m.groupValues[2].toInt()) }
        LOJA_DEFINIR_B.matchEntire(c)?.let { m -> return acao("estoque_definir", m.groupValues[2], m.groupValues[1].toInt()) }
        for ((ordem, regra) in LOJA_CUSTO) {
            val m = regra.matchEntire(c) ?: continue
            val (prod, valor, extra) = if (ordem == 0) Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            else Triple(m.groupValues[3], m.groupValues[1], m.groupValues[2])
            val v = centavos(valor, extra) ?: continue
            val r = acao("custo", prod.replace(Regex("\\s+(?:cada|a unidade|por unidade)$"), ""), v)
            // "paguei 50 no gás": se não for produto da loja, vira despesa no caixa
            if (ordem == 1 && r is Resultado.Acao) return Resultado.Acao(r.nome, r.args + ("se_nao_achar" to "despesa"))
            return r
        }
        LOJA_FIM_PROMO.matchEntire(c)?.let { m -> return acao("fim_promocao", m.groupValues[1].ifBlank { m.groupValues[2] }) }
        LOJA_PROMO_PCT.matchEntire(c)?.let { m -> return acao("promocao_pct", m.groupValues[2], m.groupValues[1].toInt()) }
        for (regra in LOJA_PROMO) {
            val m = regra.matchEntire(c) ?: continue
            val centavos = centavos(m.groupValues[2], m.groupValues[3]) ?: continue
            return acao("promocao", m.groupValues[1], centavos)
        }
        for (regra in LOJA_PRECO) {
            val m = regra.matchEntire(c) ?: continue
            val centavos = centavos(m.groupValues[2], m.groupValues[3]) ?: continue
            return acao("preco", m.groupValues[1], centavos)
        }
        LOJA_DESATIVAR.firstNotNullOfOrNull { it.matchEntire(c) }?.let { return acao("desativar", it.groupValues[1]) }
        LOJA_ATIVAR.firstNotNullOfOrNull { it.matchEntire(c) }?.let { return acao("ativar", it.groupValues[1]) }
        return null
    }

    /** "129,90" → 12990; "129" + "90" (de "129 e 90") → 12990; "50" → 5000. */
    fun centavos(valor: String, extra: String = ""): Int? {
        val v = valor.replace(',', '.')
        val reais = v.substringBefore('.').toIntOrNull() ?: return null
        var cents = if ('.' in v) v.substringAfter('.').padEnd(2, '0').take(2).toIntOrNull() ?: return null else 0
        if (extra.isNotBlank() && '.' !in v) cents = extra.padStart(2, '0').takeLast(2).toIntOrNull() ?: 0
        if (reais > 100_000) return null
        return reais * 100 + cents
    }

    /** "Dá play no Deezer", "toca a última música", "continua de onde parou": retoma, não procura música nova. */
    private fun retomar(c: String): Resultado? {
        for (regra in RETOMAR) {
            val m = regra.matchEntire(c) ?: continue
            val app = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            return Resultado.Acao("controlar_midia", mapOf("acao" to "tocar", "app" to app))
        }
        return null
    }

    /** "Qual a ferragem mais próxima", "onde tem farmácia perto de mim": abre no Maps perto de você. */
    private fun perto(f: Frase): Resultado? {
        for (regra in PERTO) {
            val m = regra.matchEntire(f.chave) ?: continue
            var oque = f.trecho(m.groups[1]).orEmpty().trim()
            oque = oque.replace(Regex("^(?i)(a|o|as|os|uma|um|umas|uns|alguma|algum)\\s+"), "").trim()
            val n = normalizar(oque)
            if (oque.length < 3 || NAO_E_LUGAR.containsMatchIn(n)) return null
            val referencia = when {
                Regex("\\bda minha casa|\\bde casa\\b").containsMatchIn(f.chave) -> "casa"
                Regex("\\bdo (?:meu )?trabalho").containsMatchIn(f.chave) -> "trabalho"
                else -> ""
            }
            return Resultado.Acao("buscar_perto", mapOf("oque" to oque, "referencia" to referencia))
        }
        return null
    }

    private fun midia(c: String): Resultado? {
        val acao = when {
            PROXIMA.matches(c) -> "proxima"
            ANTERIOR.matches(c) -> "anterior"
            PAUSAR.matches(c) -> "pausar"
            CONTINUAR.matches(c) -> "tocar"
            Regex("\\bvolume\\s+(?:no\\s+|em\\s+)?(?:maximo|maxima|total|cem)\\b").containsMatchIn(c) -> "volume_maximo"
            Regex("\\bvolume\\s+(?:no\\s+|em\\s+)?(?:minimo|minima|zero)\\b").containsMatchIn(c) -> "volume_minimo"
            Regex("\\bvolume\\s+(?:no\\s+|em\\s+|pela\\s+)?(?:metade|meio)\\b").containsMatchIn(c) -> "volume_metade"
            Regex("\\bvolume\\s+(?:no\\s+|em\\s+|para\\s+)?\\d{1,3}\\b").containsMatchIn(c) -> {
                val n = Midia.nivelFalado(c.substringAfter("volume"))
                    ?: return Resultado.Acao("controlar_midia", mapOf("acao" to "aumentar_volume"))
                return Resultado.Acao("controlar_midia", mapOf("acao" to "volume_nivel", "nivel" to n.toString()))
            }
            Regex("^(?:avanca|avancar)(?:\\s+um\\s+pouco)?$").matches(c) -> "avancar"
            Regex("^(?:volta|voltar)\\s+um\\s+pouco$").matches(c) -> "voltar"
            Regex("\\b(mudo|silencia\\w*|sem som)\\b").containsMatchIn(c) -> "mudo"
            VOLUME_MAIS.containsMatchIn(c) -> "aumentar_volume"
            VOLUME_MENOS.containsMatchIn(c) -> "diminuir_volume"
            else -> return null
        }
        return Resultado.Acao("controlar_midia", mapOf("acao" to acao))
    }

    private fun camera(c: String): Resultado? {
        val pede = Regex("^(tira|tirar|tire|bate|bater)\\s+(uma\\s+)?(foto|selfie)\\b").containsMatchIn(c) ||
            Regex("^(abre|abrir|abra|abri)\\s+(a\\s+)?camera$").matches(c)
        return if (pede) Resultado.Acao("abrir_camera", emptyMap()) else null
    }

    private fun mensagem(f: Frase): Resultado? {
        val m = MENSAGEM.matchEntire(f.chave) ?: return null
        var contato = f.trecho(m.groups[3]) ?: return null
        val texto = f.trecho(m.groups[4]) ?: return null
        contato = contato.replace(Regex("^(?i)(a|o|as|os)\\s+"), "")
        if (contato.isBlank() || texto.isBlank() || ehAlguem(contato)) return null
        val canal = (m.groups[1]?.value.orEmpty() + " " + m.groups[2]?.value.orEmpty())
        val sms = "sms" in canal || "torpedo" in canal
        return Resultado.Acao(
            if (sms) "enviar_sms" else "enviar_whatsapp",
            mapOf("contato" to contato, "mensagem" to texto)
        )
    }

    private fun ligacao(f: Frase): Resultado? {
        val m = LIGACAO.matchEntire(f.chave) ?: return null
        val contato = f.trecho(m.groups[1])?.replace(Regex("^(?i)(a|o)\\s+"), "") ?: return null
        if (contato.isBlank() || ehAlguem(contato)) return null
        return Resultado.Acao("fazer_ligacao", mapOf("contato" to contato))
    }

    /** "liga pra alguém" não tem contato de verdade: deixa o cérebro perguntar para quem. */
    private fun ehAlguem(contato: String) =
        normalizar(contato).trim() in setOf("alguem", "uma pessoa", "ninguem", "um contato", "um amigo", "uma amiga")

    private fun mapa(f: Frase): Resultado? {
        // "aqui é minha casa", "salva onde eu estou como academia"
        SALVAR_AQUI.matchEntire(f.chave)?.let { m ->
            val nome = ComandosMemoria.nomeDoLugar(m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()) ?: return null
            return Resultado.Acao("salvar_lugar_aqui", mapOf("lugar" to nome))
        }
        NAVEGAR.matchEntire(f.chave)?.let { m ->
            var destino = f.trecho(m.groups[1])?.trim() ?: return null
            // jeito de ir e app: "a pé", "de ônibus", "pelo Waze", "sem pedágio"
            var modo = ""
            var app = ""
            var evitar = ""
            var mudou = true
            while (mudou) {
                mudou = false
                val n = normalizar(destino)
                val extra = MODO_ROTA.find(n) ?: continue
                when {
                    extra.value.contains("waze") -> app = "waze"
                    extra.value.contains("pedagio") -> evitar = "pedagio"
                    extra.value.contains("pe") && !extra.value.contains("pedagio") && Regex("\\ba pe\\b|\\bandando\\b|\\bcaminhando\\b").containsMatchIn(extra.value) -> modo = "a_pe"
                    Regex("bike|bicicleta").containsMatchIn(extra.value) -> modo = "bicicleta"
                    Regex("moto").containsMatchIn(extra.value) -> modo = "moto"
                    Regex("onibus|transporte|metro|trem").containsMatchIn(extra.value) -> modo = "transporte"
                    Regex("carro|dirigindo").containsMatchIn(extra.value) -> modo = "carro"
                }
                destino = destino.substring(0, extra.range.first).trim().trimEnd(',')
                mudou = true
            }
            if (destino.isNotBlank()) {
                return Resultado.Acao(
                    "abrir_mapa",
                    mapOf("destino" to destino, "navegar" to true, "modo" to modo, "app" to app, "evitar" to evitar)
                )
            }
        }
        ONDE_FICA.matchEntire(f.chave)?.let { m ->
            val destino = f.trecho(m.groups[1]) ?: return null
            if (destino.isNotBlank()) return Resultado.Acao("abrir_mapa", mapOf("destino" to destino, "navegar" to false))
        }
        return null
    }

    private fun pesquisa(f: Frase): Resultado? {
        val m = PESQUISA.matchEntire(f.chave) ?: return null
        val termo = f.trecho(m.groups[1]) ?: return null
        if (termo.isBlank()) return null
        return Resultado.Acao("pesquisar_na_web", mapOf("termo" to termo))
    }

    private fun site(f: Frase): Resultado? {
        val m = SITE.matchEntire(f.chave) ?: return null
        val endereco = m.groupValues[1].replace(Regex("\\s+ponto\\s+"), ".").replace(" ", "")
        return Resultado.Acao("abrir_site", mapOf("endereco" to endereco))
    }

    private fun musica(f: Frase): Resultado? {
        val m = MUSICA.matchEntire(f.chave) ?: return null
        var busca = f.trecho(m.groups[1]) ?: return null
        val app = m.groups[2]?.value.orEmpty()
        val prefixo = Regex(
            "^(?i)(umas?\\s+|as?\\s+|os?\\s+)?(m[uú]sicas?\\s+|can[cç](ao|ão|oes|ões)\\s+|som\\s+)?(d[oae]s?\\s+|de\\s+)?"
        ).find(busca)
        if (prefixo != null && prefixo.range.first == 0) busca = busca.substring(prefixo.value.length)
        busca = busca.trim()
        if (busca.isBlank()) return null
        // "toca minha música favorita" / "o de sempre": o cérebro usa a memória.
        if (FAVORITA.matches(normalizar(busca))) return null
        return Resultado.Acao("tocar_musica", mapOf("busca" to busca, "app" to app))
    }

    private fun abrirApp(f: Frase): Resultado? {
        val m = ABRIR.matchEntire(f.chave) ?: return null
        var nome = f.trecho(m.groups[1]) ?: return null
        // "abre um aplicativo" não diz qual: o cérebro pergunta.
        val generico = Regex("^(um |uma |algum |alguma |o |a |outro )?(app|apps|aplicativo|aplicativos|programa|aplicacao)$")
        if (generico.matches(normalizar(nome).trim())) return null
        nome = nome.replace(Regex("(?i)\\s+(app|aplicativo)$"), "").trim()
        if (nome.isBlank() || nome.split(" ").size > 4) return null
        if (normalizar(nome) in setOf("um", "uma", "o", "a", "algum", "alguma", "outro")) return null
        return Resultado.Acao("abrir_app", mapOf("nome" to nome))
    }

    // ---------------------------------------------------------------- números
    private val NUMEROS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12,
        "quinze" to 15, "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50,
    )

    private fun numero(s: String): Int? = s.toIntOrNull() ?: NUMEROS[s]

    // ---------------------------------------------------------------- padrões
    private val PREFIXO = Regex(
        "^(?:(?:ei|ok|oi|ola|hey|e ai)\\s*,?\\s+)?(?:sexta[- ]?feira|sexta|friday|venus)\\b\\s*[,.!:]?\\s*" +
            "|^(?:por favor|por gentileza|pode|voce pode|voce poderia|poderia|consegue|voce consegue|" +
            "quero que voce|preciso que voce|eu quero que voce)\\b\\s*,?\\s*"
    )
    private val SUFIXO = Regex("\\s*,?\\s*(?:por favor|por gentileza|sexta[- ]?feira|venus|valeu|obrigad[oa])\\s*[.!?]*$")

    private val COMPOSTO = Regex(
        "\\s(?:e|depois|entao|e depois|e entao)\\s+(?:toca|tocar|toque|coloca|coloque|manda|mande|envia|envie|" +
            "liga|ligue|abre|abra|abrir|pesquisa|pesquise|aumenta|diminui|cria|crie|desliga|apaga|me leva)\\b"
    )
    private val RECADO = Regex("^(?:manda|mande|mandar|envia|envie|enviar|escreve|escreva|lembra|lembre|anota|anote|liga|ligue)\\b")
    private val MOEDA = Regex("\\b(dolar canadense|dolar australiano|peso argentino|dolar|euro|bitcoin|libra|iene|ethereum)\\b")
    private val ACOMPANHAR = Regex(
        "^(?:acompanha|acompanhe|acompanhar|segue|siga|monitora|monitore|fica de olho|fique de olho)" +
            "(?:\\s+(?:em|no|na|nos|nas))?(?:\\s+(?:as|os))?(?:\\s+(?:noticias|novidades))?" +
            "(?:\\s+(?:de|do|da|dos|das|sobre|em))?\\s+(.+)$"
    )
    private val PARAR_ACOMPANHAR = Regex(
        "^(?:para|pare|parar|deixa|deixe) de (?:acompanhar|seguir|monitorar)(?:\\s+(?:as|os))?" +
            "(?:\\s+(?:noticias|novidades))?(?:\\s+(?:de|do|da|dos|das|sobre|em))?\\s+(.+)$"
    )
    private val DISTANCIA_DAQUI = Regex(
        "^(?:qual (?:e |eh )?(?:a )?distancia|a distancia|distancia|quantos (?:km|quilometros) (?:sao|tem|da|ha|faltam)?|quanto (?:da|e|eh|tem))\\s*" +
            "(?:daqui|de onde eu estou|da onde eu estou|de onde estou|da onde estou|de mim|da minha casa|de casa|da minha localizacao|de onde eu to)\\s+" +
            "(?:ate|para|pra|a|ao|à|na|no)\\s+(?:a cidade de |o |a )?(.+)$|" +
            "^(?:quao|quanto) longe (?:fica|esta|e|eh) (?:a cidade de |o |a )?(.+?)(?: daqui| de mim| de onde eu estou)?$"
    )
    private val DISTANCIA_ENTRE = Regex(
        "^(?:qual (?:e |eh )?(?:a )?distancia|a distancia|distancia|quantos (?:km|quilometros) (?:sao|tem|da|ha)?)\\s*(?:entre|de|da|do)\\s+(.+?)\\s+(?:e|ate|para|pra|a)\\s+(.+)$"
    )
    private val CEP_INICIO = "^(?:qual (?:e |eh )?(?:o )?|me (?:da|de|diz|diga|fala|passa) o |sabe o |descobre o |procura o |busca o |)cep d[aoe]s? "
    private val CEP_ENDERECO = listOf(
        Regex("$CEP_INICIO(.+) em (.+?)(?:,? (?:no |na |em )?(ac|al|ap|am|ba|ce|df|es|go|ma|mt|ms|mg|pa|pb|pr|pe|pi|rj|rn|rs|ro|rr|sc|sp|se|to))?$"),
        Regex("$CEP_INICIO(.+),? (?:de|na cidade de) ([a-z ]+?)(?:,? (ac|al|ap|am|ba|ce|df|es|go|ma|mt|ms|mg|pa|pb|pr|pe|pi|rj|rn|rs|ro|rr|sc|sp|se|to))?$"),
    )
    private val PALAVRA = "([a-z][a-z-]+)"
    private val DICIONARIO = listOf(
        Regex("^(?:o que (?:significa|quer dizer)|qual (?:e |eh )?o significado d[aeo]|significado d[aeo]|definicao d[aeo]|defina|define) (?:a palavra |o termo |a expressao )?$PALAVRA$") to "significado",
        Regex("^(?:(?:qual|quais) (?:e |eh |sao )?(?:o |os |um |uns )?)?(?:sinonimos?|outra palavra para|outra palavra pra|outras palavras para) (?:de |da |do |para )?(?:a palavra )?$PALAVRA$") to "sinonimos",
        Regex("^(?:(?:qual|quais) (?:e |eh |sao )?(?:o |os |um )?)?(?:antonimos?|contrario|oposto) (?:de |da |do )?(?:a palavra )?$PALAVRA$") to "antonimos",
        Regex("^(?:qual (?:e |eh )?)?o plural d[eao] (?:a palavra )?$PALAVRA$") to "plural",
    )
    private val ORTOGRAFIA = listOf(
        Regex("^(?:qual (?:e |eh )?o (?:certo|correto)|o (?:certo|correto) (?:e|eh)|como (?:e que )?se escreve|se escreve|como escreve|escreve|e) ([a-z]+) ou ([a-z]+)$"),
        Regex("^([a-z]+) ou ([a-z]+),? (?:qual (?:e |eh )?(?:o )?(?:certo|correto|que esta certo)|como se escreve|qual se escreve)$"),
    )
    private val FERIADO = Regex(
        "\\b(proximo feriado|proximos feriados|quando (?:e|eh|vai ser|sera) o (?:proximo )?feriado|tem feriado|" +
            "quais (?:sao )?os (?:proximos )?feriados|feriados (?:do ano|deste ano|desse ano|de \\d{4}))\\b"
    )
    private val IDIOMA = "(" + Servicos.IDIOMAS.keys.joinToString("|") + ")"
    private val TRADUCAO = listOf(
        Regex("^(?:como (?:e que )?(?:se )?(?:diz|fala|escreve|falo|digo|escrevo)|qual (?:e |eh )?a traducao de) (.+?) (?:em|no) $IDIOMA$") to "auto",
        Regex("^(?:traduz|traduza|traduzir|traduz ai|traducao de|me traduz) (.+?) (?:para|pro|pra|em) (?:o )?$IDIOMA$") to "auto",
        Regex("^(?:traduz|traduza|traduzir|me traduz) (.+?) do $IDIOMA(?: para o portugues| pro portugues)?$") to "de",
        Regex("^o que (?:significa|quer dizer) (.+?) em $IDIOMA$") to "auto",
    )
    private val NOTICIAS = listOf(
        Regex(
            "^(?:quais (?:sao )?(?:as )?|me (?:da|de|fala|fale|conta|conte|mostra|mostre) (?:as )?|tem |)?" +
                "(?:ultimas |principais )?(?:noticias|novidades)(?:\\s+(?:de|do|da|dos|das|sobre|em|no|na)\\s+(.+))?$"
        ),
        Regex("^o que (?:ha|tem) de novo (?:em|no|na|nos|nas|sobre|de) (.+)$"),
        Regex("^(?:quais (?:sao )?(?:os )?)?(?:ultimos |novos )?lancamentos (?:de|do|da|dos|das|em) (.+)$"),
        Regex("^(?:quais|que) (.+?) (?:foram|sao|estao sendo) lancad[oa]s(?: recentemente| este ano| esse ano| agora)?$"),
    )
    private val FAVORITA = Regex(
        "^(?:a |as |o |os )?(?:minhas? |meus? )?(?:musicas? |playlist |som )?(?:favoritas?|favoritos?|preferidas?|preferidos?|" +
            "de sempre|que eu gosto|que eu curto)$|^(?:minha playlist|minhas musicas|meu som|o de sempre|a de sempre|as de sempre)$"
    )
    private val CLIMA = Regex(
        "\\b(graus|temperatura|previsao do tempo|previsao|clima|chover|chovendo|chuva|vai fazer frio|vai fazer calor|" +
            "esta frio|esta calor|ta frio|ta calor|faz frio|faz calor|o tempo (hoje|amanha|agora|la fora)|como (esta|ta) o tempo|" +
            "tempo (hoje|amanha|la fora)|vai fazer sol|ensolarado|nublado)\\b"
    )
    private val LUGAR_CLIMA = Regex("\\b(?:em|no|na|de|para|pra)\\s+([a-z][a-z .'-]+?)\\s*(?:hoje|amanha|agora)?$")
    private val LUGARES_GENERICOS = setOf(
        "hoje", "amanha", "agora", "la fora", "aqui", "casa", "rua", "chuva", "tempo", "frio", "calor", "sol",
        "graus", "temperatura", "manha", "tarde", "noite", "semana", "fim de semana", "clima",
    )
    private val PERGUNTA = Regex("^(qual|quais|quanto|quantos|quantas|como|por que|porque|o que|quem|onde(?! fica)|quando)\\b")
    private val HORA = Regex("\\b(que horas? (sao|e)|horas? agora|me diz as horas|que horas tem)\\b")
    private val DATA = Regex("\\b(que dia (e|eh) hoje|qual (e )?a data( de hoje)?|data de hoje|dia de hoje)\\b")
    private val AJUDA = Regex(
        "^(ajuda|me ajuda|comandos|lista de comandos|o que (voce|vc) (sabe|consegue|pode) fazer|" +
            "quais (sao )?(os )?(seus )?comandos)$"
    )

    private val DURACAO = Regex(
        "\\b(\\d+|um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|quinze|vinte|trinta|" +
            "quarenta|cinquenta)\\s*(segundos?|seg|s|minutos?|min|horas?|h)\\b"
    )
    private val HORARIO = Regex(
        "\\b(\\d{1,2}|uma|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze)(?:\\s*(?::|h)\\s*(\\d{2}))?" +
            "(?=\\s*(?:h\\b|horas?\\b|:|e\\s|da\\s|$|\\s))"
    )

    private val PROXIMA = Regex(
        "^(?:(?:passa|pula|pular|pule|avanca|avancar|troca|trocar|muda|mudar)(?:\\s+(?:a|de|essa|esta|pra))?" +
            "(?:\\s+(?:musica|faixa|cancao))?|proxima(?:\\s+(?:musica|faixa|cancao))?|next)$"
    )
    private val ANTERIOR = Regex(
        "^(?:(?:musica|faixa|cancao) anterior|anterior|volta(?:r)?(?:\\s+(?:a|pra|para))?(?:\\s+(?:musica|faixa))?" +
            "(?:\\s+anterior)?)$"
    )
    private val LOJA_VENDAS = Regex(
        "\\b(?:como (?:foram|estao|tao|vao|anda|andam) as vendas|quanto (?:eu )?(?:vendi|faturei|a loja vendeu|a loja faturou|vendemos|faturamos)|" +
            "vendas (?:de |d[ao] )?(?:hoje|ontem|semana|mes|essa semana|esse mes|desta semana|deste mes)|resumo (?:da loja|das vendas|do dia)|" +
            "como (?:esta|ta|foi) a loja|faturamento (?:de |d[ao] )?(?:hoje|ontem|semana|mes)|quantas vendas)\\b"
    )
    private const val DE = "(?:de|do|da|dos|das|no|na|nos|nas|ao|a|o)"
    private const val QTD = "(\\d{1,4})(?: (?:unidades?|pecas?|itens?))?"
    private val LOJA_SOMAR = Regex(
        "^(?:coloca|coloque|bota|bote|poe|adiciona|adicione|acrescenta|acrescente|soma|some|entrou|entraram|chegou|chegaram|" +
            "repoe|reponha|repor|aumenta|aumente|inclui|inclua)(?: mais)? $QTD(?: a mais)?(?: (?:no|ao|em) estoque)? $DE (.+?)(?: (?:no|ao|em) estoque)?$"
    )
    private val LOJA_TIRAR = Regex(
        "^(?:tira|tire|retira|retire|remove|remova|diminui|diminua|baixa|baixe|desconta|desconte|subtrai|subtraia)(?: mais)? $QTD" +
            "(?: (?:do|no) estoque)? $DE (.+?)(?: (?:do|no) estoque)?$"
    )
    private val LOJA_VENDI = Regex(
        "^(?:eu )?vendi $QTD(?: $DE)? (.+?)(?: (?:por fora|na mao|pessoalmente|fora do site|na rua|no whatsapp|no zap))?$"
    )
    private val LOJA_DEFINIR_A = Regex(
        "^(?:(?:muda|mude|altera|altere|atualiza|atualize|ajusta|ajuste|coloca|coloque|deixa|deixe|define|defina|corrige|corrija|poe|bota) )?" +
            "o estoque (?:de|do|da|dos|das) (.+?) (?:para|pra|em|como|agora e|e|eh|ficou|ficou com|esta|ta|passa a ser) (?:de )?(\\d{1,4})(?: unidades?)?$"
    )
    private val LOJA_DEFINIR_B = Regex(
        "^(?:agora )?(?:tenho|fiquei com|ficaram|sobraram|restam|sobrou|restou) (?:so )?(\\d{1,4}) (?:unidades?|pecas?) (?:de|do|da|dos|das) (.+?)(?: (?:no|em) estoque)?$"
    )
    private const val VALOR = "(?:r\\$ ?)?(\\d{1,6}(?:[.,]\\d{1,2})?)(?: ?reais| ?real)?(?: (?:e|com) (\\d{1,2})(?: centavos?)?)?"
    private val LOJA_PRECO = listOf(
        Regex(
            "^(?:muda|mude|altera|altere|atualiza|atualize|ajusta|ajuste|coloca|coloque|deixa|deixe|define|defina|corrige|corrija|poe|bota|troca|troque|passa|passe) " +
                "o (?:preco|valor) (?:de|do|da|dos|das) (.+?) (?:para|pra|em|por|a) $VALOR$"
        ),
        Regex("^o (?:preco|valor) (?:de|do|da|dos|das) (.+?) (?:agora )?(?:e|eh|vai ser|passa a ser|fica|vai ficar) (?:de )?$VALOR$"),
        Regex("^(?:o |a |os |as )?(.+?) agora (?:custa|vai custar|passa a custar|sai por|fica por) $VALOR$"),
    )
    private const val PRECO_DE = "(?: de (?:r\\$ ?)?\\d{1,6}(?:[.,]\\d{1,2})?(?: ?reais)?)?"
    private val LOJA_PROMO = listOf(
        Regex("^(?:coloca|coloque|bota|bote|poe|deixa|deixe|faz|faca|cria|crie|lanca|lance)(?: uma)? (?:o |a |os |as )?(.+?) (?:em|na) promocao$PRECO_DE (?:por|a|pra|para) $VALOR$"),
        Regex("^(?:faz|faca|cria|crie|coloca|coloque|lanca|lance)(?: uma)? promocao (?:d[oae]s?|n[oa]s?|para o|para a|pro|pra) (.+?)$PRECO_DE (?:por|a|pra|para) $VALOR$"),
        Regex("^promocao (?:d[oae]s?|n[oa]s?) (.+?)$PRECO_DE (?:por|a) $VALOR$"),
    )
    private val LOJA_PROMO_PCT = Regex(
        "^(?:da|de|dar|coloca|coloque|faz|faca|aplica|aplique|bota|poe)(?: um| uma)? (?:desconto de |promocao de )?(\\d{1,2}) ?(?:%|por cento)(?: de desconto| off)? " +
            "(?:no|na|nos|nas|em|para o|para a|pro|pra|ao) (.+)$"
    )
    private val LOJA_FIM_PROMO = Regex(
        "^(?:tira|tire|remove|remova|acaba|acabe|encerra|encerre|termina|termine|finaliza|finalize|cancela|cancele)(?: com)? " +
            "(?:a )?(?:promocao (?:d[oae]s?|n[oa]s?) (.+)|(?:o |a |os |as )?(.+?) da promocao)$"
    )
    private val LOJA_DESATIVAR = listOf(
        Regex("^(?:desativa|desative|desativar|esconde|esconda|oculta|oculte|pausa|pause|tira|tire|remove|remova) (?:o |a |os |as )?(?:produto |anuncio )?(.+?) (?:do site|da loja|do catalogo|das vendas|da venda)$"),
        Regex("^(?:desativa|desative|desativar|esconde|esconda|oculta|oculte|pausa|pause) o (?:produto|anuncio) (.+)$"),
    )
    private val LOJA_ATIVAR = listOf(
        Regex(
            "^(?:ativa|ative|ativar|reativa|reative|mostra|mostre|volta|volte|coloca|coloque|bota|poe|publica|publique) (?:a mostrar |a vender |de volta )?" +
                "(?:o |a |os |as )?(?:produto |anuncio )?(.+?) (?:de volta )?(?:no site|na loja|no catalogo|a venda)$"
        ),
        Regex("^(?:ativa|ative|ativar|reativa|reative) o (?:produto|anuncio) (.+)$"),
    )
    private const val PEDIDO_N = "(?:o )?pedido (?:numero |n )?(\\d{1,6})"
    private val LOJA_AGENDAR = listOf(
        Regex("^(?:agenda|agende|agendar|marca|marque|combina|combine)(?: a)? retirada (?:do|no|para o|pro) $PEDIDO_N$".replace("(?:o )?pedido", "pedido")),
        Regex("^(?:chama|chame|manda mensagem|mande mensagem|manda uma mensagem|mande uma mensagem|fala|fale|escreve|escreva|chama no whatsapp|chame no whatsapp)" +
            "(?: (?:para|pra|com|a|o))? (?:o |a )?(?:cliente|comprador|compradora) do $PEDIDO_N$".replace("(?:o )?pedido", "pedido")),
    )
    private val LOJA_ENTREGUE = listOf(
        Regex("^(?:marca|marque|coloca|coloque|muda|mude|poe|bota) $PEDIDO_N (?:como|para|pra) (?:entregue|retirado)$"),
        Regex("^$PEDIDO_N (?:ja )?(?:foi|esta|ta) (?:entregue|retirado)$"),
        Regex("^(?:o |a )?(?:cliente|compradora?) do pedido (?:numero )?(\\d{1,6}) (?:ja )?(?:retirou|buscou|pegou|recebeu)$"),
    )
    private const val RASTREIO = "(?:,? (?:com )?(?:o )?(?:codigo de rastreio|codigo de rastreamento|codigo do rastreio|rastreio|rastreamento|codigo)(?: e)?:? ?([a-z0-9 ]{8,40}))?"
    private const val MARCAR = "(?:(?:marca|marque|coloca|coloque|muda|mude|poe|bota|atualiza|atualize) )?(?:o )?"
    private val LOJA_STATUS = listOf(
        "shipped" to listOf(
            Regex("^$MARCAR$PEDIDO_N,? (?:como |para |pra )?(?:ja )?(?:foi |esta |ta )?(?:enviado|despachado|postado|mandado)$RASTREIO$"),
            Regex("^(?:eu )?(?:enviei|despachei|postei|mandei) o pedido (?:numero |n )?(\\d{1,6})$RASTREIO$"),
            Regex("^(?:o )?(?:codigo de )?(?:rastreio|rastreamento) do pedido (?:numero |n )?(\\d{1,6}) (?:e|eh|fica) ([a-z0-9 ]{8,40})$"),
        ),
        "preparing" to listOf(
            Regex("^$MARCAR$PEDIDO_N (?:como |para |pra )?(?:esta |ta )?(?:em separacao|separando|sendo separado)$"),
            Regex("^(?:eu )?(?:estou|to|tou|vou|ja estou) separa(?:ndo|r) o pedido (?:numero |n )?(\\d{1,6})$"),
        ),
        "paid" to listOf(
            Regex("^$MARCAR$PEDIDO_N (?:como |para |pra )?(?:ja )?(?:foi )?pago$"),
            Regex("^(?:o |a )?(?:cliente|compradora?) do pedido (?:numero |n )?(\\d{1,6}) (?:ja )?pagou$"),
        ),
    )
    /** "Tem mensagem nova?", "o que a Natália mandou?", "responde para a Natália que chego às 8". */
    private fun mensagens(f: Frase): Resultado? {
        val c = f.chave
        if (RECADO.containsMatchIn(c)) return null // "manda mensagem para..." é outra coisa
        RESPONDER_PARA.matchEntire(c)?.let { m ->
            return Resultado.Acao("responder_mensagem", mapOf("para" to f.trecho(m.groups[1]).orEmpty(), "texto" to f.trecho(m.groups[2]).orEmpty()))
        }
        RESPONDER.matchEntire(c)?.let { m ->
            return Resultado.Acao("responder_mensagem", mapOf("para" to "", "texto" to f.trecho(m.groups[1]).orEmpty()))
        }
        DE_QUEM.matchEntire(c)?.let { m ->
            if (m.groupValues[1] !in setOf("voce", "vc", "ele", "ela", "eu", "isso", "aquilo")) {
                return Resultado.Acao("ler_mensagens", mapOf("de" to f.trecho(m.groups[1]).orEmpty()))
            }
        }
        if (LER_MENSAGENS.containsMatchIn(c)) return Resultado.Acao("ler_mensagens", mapOf("de" to ""))
        return null
    }

    private val LER_MENSAGENS = Regex(
        "\\b(?:tem (?:alguma |nova )?mensage(?:m|ns)(?: nova| novas)?|chegou (?:alguma )?mensagem|mensagens? novas?|" +
            "(?:le|leia|ler|me le|le pra mim|leia pra mim|quais sao|quais|mostra|mostre) (?:as |minhas |as minhas |a )?(?:mensagens|mensagem)|" +
            "o que (?:me )?mandaram|(?:quem|alguem) (?:me )?mandou mensagem|(?:mensagens|mensagem) do (?:whatsapp|zap))\\b"
    )
    private val DE_QUEM = Regex("^(?:o que|que|qual a mensagem que) (?:o |a )?(.+?) (?:me )?(?:mandou|disse|falou|escreveu)(?: no whatsapp| no zap)?$")
    private val RESPONDER_PARA = Regex(
        "^(?:responde|responda|responder|responde ai|responda ai) (?:pra|para|pro|a|ao|o|a) (?:o |a )?(.+?),? (?:que|dizendo que|falando que|dizendo|falando|:) (.+)$"
    )
    private val RESPONDER = Regex("^(?:responde|responda|responder|responde ai|responda ai)(?: que| dizendo que| falando que|:|,)? (.+)$")
    private val EDITAR_FUNDO = Regex(
        "\\b(?:(?:troca|troque|trocar|muda|mude|mudar|tira|tire|tirar|remove|remova|remover|apaga|apague|edita|edite|editar|coloca|coloque|poe) (?:o |um )?fundo|" +
            "fundo (?:da|de uma|dessa|desta) foto|edita(?:r)? (?:a |uma )?foto(?: do produto)?|editor de fotos?)\\b"
    )
    private val PROGRAMAR_POST = Regex(
        "^(?:programa|programe|programar|agenda|agende|agendar|cria|crie|criar|faz|faca|fazer|prepara|prepare|preparar|monta|monte|montar|posta|poste|postar)" +
            "(?: um| uma| o| a)? (?:novo |nova )?(post|postagem|publicacao|story|stories|storie)(?: (?:no|do|para o|pro|pra) instagram)?(?:,? (.+))?$"
    )
    private val RESPONDER_CLIENTE = Regex(
        "^(?:me ajuda a |ajuda a |quero )?(?:responde|responda|responder|atende|atenda|atender|escreve|escreva|escrever)(?: uma resposta)?(?: (?:pra|para|a|o))? (?:uma |um |a |o |essa |esse )?(?:cliente|compradora?|mensagem de cliente|mensagem da cliente)\\b"
    )
    // ================================================================== Instagram da loja
    private val IG = "(?:o |no |do |meu |nosso |a conta do |conta do )?(?:insta|instagram|ig)\\b"
    private val IG_CONECTAR = Regex(
        "^(?:conecta|conectar|conecte|liga|ligar|ligue|configura|configurar|configure|vincula|vincular|vincule|cadastra|cadastrar|coloca|colocar|trocar? o token d[oe]|token d[oe]) $IG"
    )
    private val IG_RESUMO = Regex(
        "\\b(?:como (?:esta|ta|anda|vai|estao|tao) $IG|(?:quantos )?seguidores (?:d[oe]|n[oe]|tem n[oe]) $IG|quantos seguidores|" +
            "(?:desempenho|resumo|resultado|engajamento|numeros|metricas|alcance)(?: d[oe]s?)? $IG|como (?:foram|estao|tao) (?:os |meus |nossos )?(?:ultimos )?posts)"
    )
    private val IG_COMENTARIOS = Regex(
        "\\b(?:comentarios? (?:d[oe]|n[oe]) $IG|(?:tem |algum |ha |chegou )?comentarios? nov|comentarios? sem resposta|" +
            "(?:responde|responder|responda|ver|mostra|le|ler|leia)(?: os| as| aos)? comentarios?)"
    )

    private const val PUBLICAR = "(?:postar|posta|poste|postem|publicar|publica|publique)"
    private val POSTAR_AGORA = Regex(
        "^(?:(?:pode|podes|entao|ok|sim|beleza|ta|tudo bem),? $PUBLICAR(?: (?:ele|ela|isso|o post|o story|a postagem|o stories))?(?: (?:agora|ja|logo|como (?:esta|ta)|assim mesmo|assim))?|" +
            "$PUBLICAR(?: (?:ele|ela|isso|o post|o story|a postagem|o stories))?(?: (?:agora|ja|logo|agora mesmo|como (?:esta|ta)|assim mesmo))?)$"
    )
    /** "muda para hoje às 19h" (vale para o post que acabou de ser programado). */
    val REPROGRAMAR_POST = Regex(
        "^(?:muda|mudar|mude|troca|trocar|troque|passa|passar|passe|reprograma|reprogramar|remarca|remarcar|deixa|deixar|coloca|colocar|bota|botar)" +
            "(?: (?:o|a) (?:post|story|stories|postagem|horario|publicacao))?(?: d[oa] (?:post|story|postagem))? (?:para|pra|pro) (.+)$"
    )
    /** "no feed", "poste no feed", "troca para story". */
    val TIPO_POST = Regex("^(?:(?:muda|mudar|mude|troca|trocar|troque|passa|poste|posta|postar|publica|publicar|faz|faca|coloca|bota)(?: (?:ele|ela|isso|o post|a postagem))? )?(?:no|pro|para o|pra|para|pelo|como) (feed|story|stories)\\b")

    private val ROBO = "(?:o |a )?(?:robo|robozinho|venus robo|atendimento automatico|resposta automatica)"
    private val ROBO_LIGAR = Regex("^(?:pode )?(liga|ligar|ligue|ativa|ativar|ative|volta|voltar|desliga|desligar|desligue|desativa|desativar|desative|pausa|pausar|pause|para|parar|pare)(?: o| a)? $ROBO\\b")
    private val ROBO_RESUMO = Regex(
        "\\b(?:como (?:esta|ta|foi|vai) $ROBO|quantos atendimentos|quantas clientes (?:o robo|a venus) (?:atendeu|respondeu)|" +
            "(?:quem|quais clientes|tem cliente|alguma cliente)(?: (?:esta|ta|estao|tao))? esperando(?: resposta| eu responder| voce)?|clientes esperando|atendimentos do robo)\\b"
    )
    private val LIMPAR_ERROS = Regex("\\b(?:limpa|limpar|limpe|apaga|apagar|apague|zera|zerar|zere)(?: o| os)? (?:log de erros|registro de erros|relatorio de erros|erros)\\b")
    private val LOG_ERROS = Regex("\\b(?:log de erros|registro de erros|quais (?:os |sao os )?erros|erros em aberto|mostra(?:r)? os erros|que erros (?:voce )?(?:teve|deu))\\b")
    private val DIARIO = Regex(
        "\\b(?:o que (?:voce |a venus )?(?:mudou|fez|alterou|ajustou|mexeu)(?: na loja| no site| no instagram)?(?: hoje| essa semana| nesta semana)?|" +
            "o que (?:foi|ja foi) (?:mudado|alterado|ajustado)(?: hoje| essa semana)?|(?:log|registro|historico|diario) (?:de|dos) ajustes|log da venus)\\b"
    )
    private val IDEIAS_POST = Regex("\\b(?:ideias? (?:de|pra|para) (?:post|posts|postagem|postagens|story|stories|instagram|insta|reels)|(?:o que|que) (?:eu )?(?:posto|postar|publico|publicar)(?: no (?:insta|instagram))?(?: essa semana| hoje| amanha)?)\\b")
    private val ESTUDO_AGORA = Regex("^(?:venus,? )?(?:vai |pode |quero que (?:voce )?|hora de )?(?:estuda|estudar|estude|treina|treinar|treine|aprende|aprender)(?: um pouco| sozinha)?(?: (?:agora|ja|hoje))?$|^(?:venus,? )?estudo agora$")
    private val APRENDIZADO = Regex(
        "\\b(?:(?:mostra|mostre|ver|veja|abre|abrir|abra|revisar|revisa|revise|lista|listar)(?: (?:o|a|os|as))? (?:(?:o )?que (?:voce )?(?:aprendeu|estudou|guardou)|aprendizados?|memoria(?: vetorial)?)|o que eu aprendi|tela do aprendizado)\\b"
    )

    private val ESTUDO_RESUMO = Regex(
        "\\b(?:o que (?:voce )?(?:estudou|aprendeu|treinou)(?: hoje| sozinha)?|como (?:esta|ta|foi) (?:o |seu |teu )?(?:estudo|treino|aprendizado)|memoria vetorial|quanto (?:voce )?(?:ja )?aprendeu)\\b"
    )

    private const val STORY_AUTO = "(?:os |o )?(?:stories|storys|story) (?:automaticos?|do dia|diarios?|de dicas?|das dicas)"
    private val STORY_AUTO_LIGA = Regex("^(?:liga|ligue|ligar|ativa|ative|ativar|volta|voltar)(?: com)? $STORY_AUTO$")
    private val STORY_AUTO_DESLIGA = Regex("^(?:desliga|desligue|desligar|desativa|desative|desativar|para|pare|parar)(?: com)? $STORY_AUTO$")
    private val STORY_AUTO_VER = Regex(
        "^(?:me )?(?:mostra|mostre|mostrar|ver|quero ver|como fica|como vai ficar)(?: o| a)?(?: proximo| proxima)? (?:story (?:de hoje|do dia|automatico|da dica)|dica do dia|previa do story)$"
    )
    private val STORY_AUTO_AGORA = Regex(
        "^(?:posta|poste|postar|publica|publique|publicar)(?: a| o| uma| um)? (?:dica|curiosidade|story do dia|story da dica|story de dica|story de curiosidade)(?: do dia)?(?: agora| ja)?$"
    )
    private val STORY_ENQUETE = Regex("^(?:prepara|prepare|preparar|faz|faca|fazer|monta|monte|montar)(?: o| um)? story (?:com|de) enquete(?: agora)?$")
    /** "stories do dia", "stories diários", "publicações diárias", "abre os stories": a página com a prévia e os botões. */
    private val STORY_PAGINA = Regex(
        "\\b(?:(?:stories|storys|storis|story|storie) (?:do dia|diarios?|de hoje|de amanha)|publicac(?:oes|ao) (?:diarias?|do dia|de hoje)|" +
            "postage(?:ns|m) (?:diarias?|do dia|de hoje)|posts? (?:diarios?|do dia|de hoje)|pagina dos? (?:stories|story)|" +
            "(?:abre|abrir|abra|mostra|mostre|ver|quero ver) (?:os |o |as )?(?:stories|storys|storis|story))\\b"
    )
    /** "abre o catálogo", "gera o catálogo em PDF": a tela do Catálogo da loja. */
    private val CATALOGO = Regex(
        "^(?:(?:abre|abrir|abra|mostra|mostre|ver|quero ver|gera|gerar|gere|faz|fazer|faca|monta|montar|monte|manda|mandar|cria|criar|crie) )?" +
            "(?:o |meu |o meu |um |uma )?catalogo(?: da loja| de produtos| dos produtos| em pdf| pdf)?$"
    )
    private val REGISTRAR_VENDA = Regex("^(?:registra|registrar|registre|anota|anotar|anote|lanca|lancar|lance) (?:uma |a |nova )?venda$")
    private val STORY_AUTO_RESUMO = Regex("\\b(?:stories automaticos|story automatico|stories do dia|stories diarios)\\b")

    /** "conecta o Instagram", "como está o Instagram?", "comentários do Instagram". */
    fun instagram(c: String): Resultado? {
        val t = c.trimEnd('.', '!', '?')
        if (CATALOGO.matches(t)) return Resultado.Acao("catalogo", emptyMap())
        if (REGISTRAR_VENDA.matches(t)) return Resultado.Acao("catalogo_venda", emptyMap())
        if (STORY_AUTO_LIGA.matches(t)) return Resultado.Acao("story_auto", mapOf("ligado" to true))
        if (STORY_AUTO_DESLIGA.matches(t)) return Resultado.Acao("story_auto", mapOf("ligado" to false))
        if (STORY_AUTO_AGORA.matches(t)) return Resultado.Acao("story_auto_agora", emptyMap())
        if (STORY_ENQUETE.matches(t)) return Resultado.Acao("story_enquete", emptyMap())
        if (STORY_AUTO_VER.matches(t) || STORY_PAGINA.containsMatchIn(t)) return Resultado.Acao("stories_pagina", emptyMap())
        if (STORY_AUTO_RESUMO.containsMatchIn(t)) return Resultado.Acao("story_auto", emptyMap())
        if (POSTAR_AGORA.matches(t)) return Resultado.Acao("postar_agora", emptyMap())
        if (ESTUDO_AGORA.matches(t)) return Resultado.Acao("estudo_agora", emptyMap())
        if (IDEIAS_POST.containsMatchIn(t)) return Resultado.Acao("estudo_ideias", emptyMap())
        ROBO_LIGAR.find(t)?.let { m -> return Resultado.Acao("robo_ligar", mapOf("ligado" to !Regex("^(?:des|pausa|para|pare|parar)").containsMatchIn(m.groupValues[1]))) }
        if (ROBO_RESUMO.containsMatchIn(t)) return Resultado.Acao("robo_resumo", emptyMap())
        if (LIMPAR_ERROS.containsMatchIn(t)) return Resultado.Acao("limpar_erros", emptyMap())
        if (LOG_ERROS.containsMatchIn(t)) return Resultado.Acao("log_erros", emptyMap())
        DIARIO.find(t)?.let { m -> return Resultado.Acao("diario_resumo", mapOf("periodo" to if (m.value.contains("semana")) "semana" else "hoje")) }
        if (APRENDIZADO.containsMatchIn(t)) return Resultado.Acao("aprendizado", emptyMap())
        if (ESTUDO_RESUMO.containsMatchIn(t)) return Resultado.Acao("estudo_resumo", emptyMap())
        if (IG_CONECTAR.containsMatchIn(t)) return Resultado.Acao("instagram_conectar", emptyMap())
        if (IG_COMENTARIOS.containsMatchIn(t)) return Resultado.Acao("instagram_comentarios", emptyMap())
        if (IG_RESUMO.containsMatchIn(t)) return Resultado.Acao("instagram_resumo", emptyMap())
        return null
    }

    private val LISTAR_POSTS = Regex(
        "\\b(?:(?:meus|minhas|quais|os|as|lista|ver|mostra) (?:os |as )?(?:posts|postagens|publicacoes|stories) (?:programad|agendad)\\w*|posts programados|postagens programadas|fila de posts)\\b"
    )

    /** "programa um post do gel amanhã às 19h" → tipo, produto (o que sobrar) e horário, se tiver. */
    fun programarPost(tipo: String, resto: String): Resultado {
        val args = mutableMapOf<String, Any>("tipo" to if (tipo.startsWith("stor")) "story" else "feed")
        var produto = resto.trim()
        if (produto.isNotBlank()) {
            Lembretes.interpretar("lembrete $produto")?.let { p ->
                args["quando"] = p.quando
                produto = if (p.texto == "Lembrete") "" else p.texto
            }
        }
        produto = produto.replace(Regex("^(?i)(?:d[oae]s?|sobre o|sobre a|com o|com a|para o|para a)\\s+"), "").trim().trim(',', '.')
        if (produto.length >= 3) args["produto"] = produto
        return Resultado.Acao("programar_post", args)
    }

    private val ABRIR_ARQUIVO = Regex(
        "^(?:(?:abre|abra|abrir|le|leia|ler|escolhe|escolha|escolher|carrega|carregue|analisa|analise|analisar) (?:um |o |uma |meu |a |esse |este )?(?:arquivo|pdf|documento|planilha|boleto|contrato)|quero (?:mandar|enviar|ler) um (?:arquivo|pdf|documento))$"
    )
        private val LER_NOTA = Regex(
        "\\b(?:(?:le|leia|ler|lanca|lance|lancar|registra|registre|registrar|cadastra|cadastre|cadastrar|importa|importe|importar|processa|processe) (?:a |uma |essa |esta )?(?:nota|notinha|nota fiscal|compra|cupom)|" +
            "nota (?:de compra|fiscal da compra|do fornecedor)|(?:entrou|chegou) (?:a )?mercadoria|compra do fornecedor)\\b"
    )
        private val LOJA_CADASTRAR = Regex(
        "^(?:(?:eu )?(?:quero|vamos|vou|preciso|pode|bora) )?(?:cadastra|cadastre|cadastrar|adiciona|adicione|adicionar|cria|crie|criar|inclui|inclua|incluir|" +
            "registra|registre|registrar|coloca|coloque|colocar)(?: um| o| uma)? (?:novo )?produto(?: novo)?(?: na loja| no site)?" +
            "(?:(?: chamado| com o nome| de nome|:|,)? (.+))?$|" +
            "^(?:(?:abre|abra|abrir|mostra|mostre) (?:a )?)?(?:tela (?:de|do) )?(?:novo produto|cadastro de produtos?|cadastrar produto)(?: na loja| no site)?(?:[:,]? (.+))?$"
    )

    /** "cadastra o produto Gel de Morango 60ml por 29,90 com 10 unidades" → nome, preço e estoque para o formulário. */
    fun cadastro(resto: String): Resultado {
        var r = resto.trim()
        val args = mutableMapOf<String, Any>()
        Regex("(?:,? (?:com|e) )?(\\d{1,4}) (?:unidades?|pecas?|no estoque|em estoque)").find(normalizar(r))?.let { m ->
            args["estoque"] = m.groupValues[1].toInt()
            r = (r.substring(0, m.range.first) + r.substring(m.range.last + 1)).trim()
        }
        Regex("(?:,? (?:por|a|custando|preco de|valor de) )$VALOR").find(normalizar(r))?.let { m ->
            centavos(m.groupValues[1], m.groupValues[2])?.let { args["preco"] = it }
            r = (r.substring(0, m.range.first) + r.substring(m.range.last + 1)).trim()
        }
        val nome = r.trim(',', ' ', '.').replace(Regex("^(?i)(?:chamado|com o nome|de nome)\\s+"), "")
        if (nome.isNotBlank()) args["nome"] = nome.replaceFirstChar { it.uppercase() }
        return Resultado.Acao("loja_cadastrar", args)
    }

    private val LISTAR_LEMBRETES = Regex(
        "\\b(?:quais (?:sao )?(?:os )?(?:meus )?lembretes|(?:os )?meus lembretes|tenho (?:algum )?lembrete|lista (?:de |dos )?lembretes|lembretes de hoje|que lembretes)\\b"
    )
    private val CANCELAR_LEMBRETE = Regex(
        "^(?:cancela|cancele|apaga|apague|remove|remova|tira|tire|exclui|exclua|esquece|esqueca)(?: o| os| todos os)? lembretes?(?: (?:de|do|da|sobre|para|pra) (.+))?$"
    )
    /** 0: produto antes do valor; 1: valor antes do produto. */
    private val LOJA_CUSTO = listOf(
        0 to Regex("^(?:o )?(?:custo|preco de custo|valor de custo|custo unitario) (?:de|do|da|dos|das) (.+?) (?:e|eh|foi|fica|ficou|sai|saiu|passa a ser) (?:de )?$VALOR$"),
        0 to Regex(
            "^(?:muda|mude|altera|altere|atualiza|atualize|coloca|coloque|define|defina|corrige|corrija|poe|bota|cadastra|cadastre|registra|registre|anota|anote) " +
                "o (?:custo|preco de custo|valor de custo) (?:de|do|da|dos|das) (.+?) (?:para|pra|em|como|de|por) $VALOR$"
        ),
        0 to Regex("^(?:o |a |os |as )?(.+?) (?:me )?custou $VALOR(?: cada| a unidade| por unidade)?$"),
        1 to Regex("^(?:eu )?paguei $VALOR (?:no|na|nos|nas|em|pelo|pela|pelos|pelas|por|cada) (.+?)$"),
    )
    /**
     * "Lembra do que falamos sobre investir em lingerie?", "volta na conversa sobre o site":
     * procura a conversa antiga e continua dali. [assunto] é só o tema (sem o resto da frase).
     */
    fun conversaAntiga(f: Frase): Resultado? {
        val c = f.chave.trimEnd('.', '!', '?')
        for (r in CONVERSA_ANTIGA) {
            val m = r.find(c) ?: continue
            var assunto = f.trecho(m.groups[1]).orEmpty()
            // "…sobre lingerie, acho que vou investir mesmo" → assunto "lingerie"
            assunto = assunto.split(Regex(",|;|\\.|\\?|!| (?:acho que|porque|pois|entao|e agora|agora|decidi|resolvi|vou|quero|e eu)\\b", RegexOption.IGNORE_CASE))
                .first().trim()
            if (BuscaDeConversas.palavras(assunto).isEmpty()) continue
            return Resultado.Acao("buscar_conversa", mapOf("assunto" to assunto))
        }
        return null
    }

    /** "Quanto custa o gel KY no Mercado Livre", "pesquisa o preço do vibrador bullet": preços em outras lojas. */
    fun precoNaInternet(f: Frase): Resultado? {
        val c = f.chave.trimEnd('.', '!', '?')
        for (r in PRECO_INTERNET) {
            val m = r.matchEntire(c) ?: continue
            val g = m.groups.drop(1).firstOrNull { it != null && it.value.isNotBlank() } ?: continue
            val produto = f.trecho(g).orEmpty().trim().replace(Regex("^(?:o|a|os|as|um|uma)\\s+", RegexOption.IGNORE_CASE), "")
            if (produto.length < 3) continue
            return Resultado.Acao("pesquisar_preco", mapOf("produto" to produto))
        }
        return null
    }

    private const val ONDE = "(?:no mercado livre|na amazon|na shopee|na magalu|no magazine luiza|na americanas|na internet|nas lojas|nas outras lojas|em outras lojas|online|nos concorrentes|na concorrencia|nos sites|no google|nos marketplaces|no mercado)"
    private val PRECO_INTERNET = listOf(
        Regex("^(?:quanto|qual (?:e )?(?:o )?(?:preco|valor)(?: de| do| da)?) ?(?:custa|custam|esta|estao|ta|sai|saem|cobram|e|eh|de|do|da)? (?:o |a |os |as |um |uma )?(.+?) $ONDE$"),
        Regex("^(?:pesquisa|pesquise|pesquisar|busca|busque|buscar|procura|procure|ve|veja|ver|compara|compare|comparar|cota|cote|levanta|levante)(?: (?:o|os|pra mim o|pra mim os|para mim o|para mim os))? (?:preco|precos|valor|valores|preco de mercado) (?:d[oae]s?|para|de um|de uma) (.+?)(?: $ONDE)?$"),
        Regex("^(?:qual|quais) (?:e )?(?:o |os )?(?:menor preco|menores precos|preco mais barato|melhor preco) (?:d[oae]s?|para|de um|de uma) (.+?)(?: $ONDE)?$"),
        Regex("^onde (?:eu )?(?:compro|encontro|acho|tem) (?:o |a |os |as |um |uma )?(.+?) mais barat[oa]s?(?: $ONDE)?$"),
        Regex("^(?:quanto|por quanto) (?:os concorrentes|a concorrencia|as outras lojas|outras lojas) (?:cobram|vendem|estao cobrando|estao vendendo|cobra|vende) (?:pelo|pela|pelos|pelas|o|a|os|as) (.+)$"),
    )

    private val RELATORIO_ERROS = Regex(
        "^(?:manda|mande|envia|envie|mandar|enviar|gera|gere)(?: o| um)? (?:relatorio|relatorios) de (?:erros?|falhas?|problemas?)(?: (?:pro|para o|pro) (?:claude|github))?$"
    )

    /** "A palavra dacueba existe?", "charada está no dicionário?": resposta pelo dicionário do app (sem internet). */
    fun palavraExiste(f: Frase): Resultado? {
        if (!Lexico.pronto) return null
        val c = f.chave.trimEnd('.', '!', '?').trim()
        for ((i, r) in PALAVRA_EXISTE.withIndex()) {
            val m = r.matchEntire(c) ?: continue
            val palavra = f.trecho(m.groups[1]).orEmpty().trim()
            // "Deus existe?" é outra pergunta: sem "palavra"/"dicionário", só responde se a palavra não existir mesmo
            if (i == 0 && !c.contains("palavra") && Lexico.existe(palavra)) return null
            return Resultado.Resposta(Lexico.responderExiste(palavra))
        }
        return null
    }

    private val PALAVRA_EXISTE = listOf(
        Regex("^(?:a palavra )?([a-z-]{3,30}) existe(?: (?:em|no) portugues| no dicionario)?$"),
        Regex("^(?:existe|tem) (?:a palavra )?([a-z-]{3,30})(?: (?:em|no) portugues| no dicionario)$"),
        Regex("^existe a palavra ([a-z-]{3,30})(?: (?:em|no) portugues| no dicionario)?$"),
        Regex("^(?:a palavra )?([a-z-]{3,30}) (?:e|eh) (?:uma )?palavra(?: de verdade| do portugues| que existe| correta| certa)?$"),
        Regex("^(?:a palavra )?([a-z-]{3,30}) (?:esta|ta|tem) no dicionario$"),
    )

    private val CONVERSA_ANTIGA = listOf(
        Regex("^(?:(?:voce|vc|tu) )?(?:se )?(?:lembra|lembras|recorda|lembrou)(?: (?:d[oa]|de|da gente))?(?: (?:aquilo|aquela conversa|aquele assunto))?(?: o)? (?:que|q) (?:a gente |nos |eu e voce |tu e eu |voce e eu )?(?:falamos|conversamos|combinamos|comentamos|vimos|discutimos|planejamos|pesquisamos|falei|conversei|te falei|te contei|te perguntei|perguntei|conversou|falou|comentou|combinou)(?: (?:sobre|de|do|da|dos|das|a respeito de|em relacao a))? (.+)$"),
        Regex("^(?:(?:voce|vc|tu) )?(?:se )?(?:lembra|lembras|recorda)(?: d[ao])? (?:conversa|assunto|papo) (?:sobre|de|do|da|dos|das) (.+)$"),
        Regex("^(?:busca|procura|acha|encontra|abre|retoma|continua|recupera|puxa)(?: (?:a|aquela|nossa))? conversa (?:sobre|de|do|da|dos|das|em que falamos de|que tivemos sobre) (.+)$"),
        Regex("^(?:vamos )?(?:continuar|retomar|voltar (?:a|para|pra|na)|volta (?:a|para|pra|na)|volte (?:a|para|pra|na))(?: a| aquela| nossa)? (?:conversa|assunto|papo) (?:sobre|de|do|da|dos|das) (.+)$"),
        Regex("^o que (?:a gente |nos |eu )?(?:falamos|conversamos|combinamos|decidimos|tinhamos falado|tinhamos combinado) (?:sobre|de|do|da|dos|das|a respeito de) (.+)$"),
    )

    /** Gestão da loja: curva ABC, o que comprar, prazo do fornecedor, recompra, datas comerciais e promoção em lote. */
    fun gestao(f: Frase): Resultado? {
        val c = f.chave.trimEnd('.', '!', '?')
        if (GESTAO_PROMO_PARADOS.containsMatchIn(c)) {
            val pct = Regex("(\\d{1,2}) ?(?:%|por cento)").find(c)?.groupValues?.get(1)?.toInt() ?: 15
            val dias = Regex("(\\d{1,3}) dias").find(c)?.groupValues?.get(1)?.toInt() ?: 30
            return Resultado.Acao("loja_promo_parados", mapOf("pct" to pct, "dias" to dias))
        }
        if (GESTAO_ABC.containsMatchIn(c)) return Resultado.Acao("loja_curva_abc", emptyMap())
        GESTAO_PRAZO.matchEntire(c)?.let { m -> return Resultado.Acao("gestao_prazo", mapOf("dias" to m.groupValues[1].toInt())) }
        if (GESTAO_COMPRAS.containsMatchIn(c)) return Resultado.Acao("loja_compras", emptyMap())
        GESTAO_CHAMAR.firstNotNullOfOrNull { it.matchEntire(c) }?.let { m ->
            val nome = f.trecho(m.groups[1]).orEmpty().trim()
            if (nome.isNotBlank()) return Resultado.Acao("loja_chamar_cliente", mapOf("nome" to nome))
        }
        if (GESTAO_RECOMPRA.containsMatchIn(c)) return Resultado.Acao("loja_recompra", emptyMap())
        if (GESTAO_DATAS.containsMatchIn(c)) {
            val dias = if (Regex("\\b(?:black friday|natal|namorados|dia do sexo|dia da mulher|carnaval|reveillon|orgasmo|solteiro)\\b").containsMatchIn(c)) 365 else 60
            return Resultado.Acao("loja_datas", mapOf("dias" to dias))
        }
        return null
    }

    private val GESTAO_PROMO_PARADOS = Regex(
        "\\b(?:promocao|desconto|liquidacao|queima)\\b.*\\b(?:produtos? parados?|parados|sem venda|que nao (?:vendem|venderam|saem|sairam))\\b" +
            "|\\b(?:produtos? parados?|parados|sem venda)\\b.*\\b(?:em promocao|na promocao|com desconto|em liquidacao)\\b"
    )
    private val GESTAO_ABC = Regex(
        "\\bcurva (?:abc|a b c)\\b|\\b(?:quais|que) produtos (?:mais )?(?:sustentam|seguram|trazem mais dinheiro|dao mais dinheiro|mais faturam|faturam mais)\\b|\\bprodutos mais importantes da loja\\b"
    )
    private val GESTAO_PRAZO = Regex("^(?:o |meu |a )?(?:fornecedor|distribuidor|fornecedora)(?: \\w+)? (?:entrega|demora|leva|manda|envia)(?: a mercadoria| os produtos)?(?: em)? (\\d{1,3}) dias?(?: (?:para|pra) (?:entregar|chegar))?$")
    private val GESTAO_COMPRAS = Regex(
        "\\b(?:sugestao de compras?|sugestoes de compras?|ponto de pedido|lista de compras? da loja|o que (?:eu )?(?:preciso|devo|tenho que) (?:comprar|encomendar|pedir) (?:pra|para|na|da) (?:a )?loja|o que (?:eu )?(?:preciso|devo|tenho que) (?:encomendar|pedir) (?:do|ao|pro) fornecedor|o que (?:eu )?(?:preciso|devo|tenho que) comprar do fornecedor|hora de comprar|quando (?:eu )?(?:devo|preciso) comprar mais)\\b"
    )
    private val GESTAO_CHAMAR = listOf(
        Regex("^(?:chama|chame|chamar|traz|traga|trazer) de volta (?:a |o |a cliente |o cliente )?(.+)$"),
        Regex("^(?:chama|chame|traz|traga) (?:a |o |a cliente |o cliente )?(.+?) de volta$"),
        Regex("^(?:manda|mande|envia|envie) (?:a |uma )?mensagem de recompra (?:para|pra|pro) (?:a |o )?(.+)$"),
    )
    private val GESTAO_RECOMPRA = Regex(
        "\\b(?:recompra|clientes? (?:sumid[oa]s?|inativ[oa]s?|que (?:nao voltaram|sumiram|nao compram mais|nao compraram mais))|quais clientes (?:eu )?(?:posso|devo|da pra|consigo) (?:chamar|trazer)(?: de volta)?|chamar clientes de volta|trazer clientes de volta)\\b"
    )
    private val GESTAO_DATAS = Regex(
        "\\b(?:datas? comercia(?:l|is)|datas? (?:que vendem|importantes para a loja|de venda)|calendario (?:comercial|de vendas|da loja)|proximas datas|quando (?:e|eh|vai ser|cai) (?:a |o )?(?:black friday|dia dos namorados|dia do sexo|dia do orgasmo|dia do solteiro|carnaval)|quanto(?:s dias)? falta(?:m)? (?:para|pra) (?:a |o )?(?:black friday|dia dos namorados|dia do sexo|dia do orgasmo|dia do solteiro|dia da mulher))\\b"
    )

    /** Caixa da loja: despesas, entradas por fora, fixas, resultado, gráficos e contas financeiras. */
    fun financas(f: Frase): Resultado? {
        val c = f.chave
        val semPonto = c.trimEnd('.', '!', '?')
        fun periodo() = when {
            Regex("\\bmes passado\\b|\\bmes anterior\\b").containsMatchIn(c) -> "mes_passado"
            Regex("\\bsemana\\b|\\b7 dias\\b").containsMatchIn(c) -> "semana"
            else -> "mes"
        }
        fun trechoDe(m: MatchResult, i: Int) = f.trecho(m.groups[i]).orEmpty().trim().trimEnd('.', '!', '?')
        CAIXA_APAGAR.matchEntire(semPonto)?.let { return Resultado.Acao("caixa_apagar", emptyMap()) }
        CAIXA_REMOVER_FIXA.matchEntire(semPonto)?.let { m -> return Resultado.Acao("caixa_remover_fixa", mapOf("trecho" to trechoDe(m, 1))) }
        for (r in CAIXA_FIXA) {
            val m = r.matchEntire(semPonto) ?: continue
            val v = centavosF(m.groupValues[2], m.groupValues[3]) ?: continue
            val dia = m.groupValues.getOrNull(4)?.toIntOrNull() ?: 1
            return Resultado.Acao("caixa_fixa", mapOf("descricao" to trechoDe(m, 1), "valor" to v, "dia" to dia))
        }
        for ((ordem, r) in CAIXA_DESPESA) {
            val m = r.matchEntire(semPonto) ?: continue
            val (desc, valor, extra) = if (ordem == 0) Triple(trechoDe(m, 3), m.groupValues[1], m.groupValues[2]) else Triple(trechoDe(m, 1), m.groupValues[2], m.groupValues[3])
            val v = centavosF(valor, extra) ?: continue
            if (desc.length < 2) continue
            return Resultado.Acao("caixa_lancar", mapOf("tipo" to "saida", "valor" to v, "descricao" to desc))
        }
        CAIXA_ENTRADA.matchEntire(semPonto)?.let { m ->
            val v = centavosF(m.groupValues[1], m.groupValues[2]) ?: return@let
            val desc = trechoDe(m, 3).ifBlank { "Venda por fora" }
            return Resultado.Acao("caixa_lancar", mapOf("tipo" to "entrada", "valor" to v, "descricao" to desc))
        }
        CALC_JUROS.matchEntire(semPonto)?.let { m ->
            val anos = m.groupValues[5].startsWith("ano")
            return Resultado.Acao("financeiro_calculo", mapOf("tipo" to "juros_compostos", "a" to num(m.groupValues[1]), "b" to num(m.groupValues[2]),
                "c" to (m.groupValues[4].toDouble() * if (anos && m.groupValues[3].contains("mes")) 12 else 1), "taxa_anual" to (m.groupValues[3].contains("ano"))))
        }
        CALC_PARCELA.matchEntire(semPonto)?.let { m ->
            return Resultado.Acao("financeiro_calculo", mapOf("tipo" to "parcela", "a" to num(m.groupValues[1]), "b" to num(m.groupValues[3]), "c" to m.groupValues[2].toDouble()))
        }
        CALC_DESCONTO.find(semPonto)?.let { m ->
            return Resultado.Acao("financeiro_calculo", mapOf("tipo" to "desconto_venda", "a" to num(m.groupValues[1])))
        }
        CALC_MARGEM.find(semPonto)?.let { m ->
            val args = mutableMapOf<String, Any>("tipo" to "markup", "a" to num(m.groupValues[1]))
            if (m.groupValues[2].isNotBlank()) args["b"] = num(m.groupValues[2])
            return Resultado.Acao("financeiro_calculo", args)
        }
        if (CAIXA_EQUILIBRIO.containsMatchIn(c)) return Resultado.Acao("caixa_equilibrio", emptyMap())
        if (CAIXA_PREVISAO.containsMatchIn(c)) return Resultado.Acao("caixa_previsao", emptyMap())
        if (CAIXA_RELATORIO.containsMatchIn(c)) return Resultado.Acao("caixa_relatorio", mapOf("periodo" to periodo()))
        if (CAIXA_GRAFICO.containsMatchIn(c)) return Resultado.Acao("caixa_grafico", emptyMap())
        if (CAIXA_DESPESAS.containsMatchIn(c)) return Resultado.Acao("caixa_despesas", mapOf("periodo" to periodo()))
        if (CAIXA_RESULTADO.containsMatchIn(c)) return Resultado.Acao("caixa_resultado", mapOf("periodo" to periodo()))
        return null
    }

    /** "1.500" → 1500; "2,5" → 2.5; "2.5" → 2.5. */
    fun num(s: String): Double {
        val t = if (',' in s) s.replace(".", "").replace(',', '.') else s.replace(Regex("\\.(?=\\d{3}(?:\\D|$))"), "")
        return t.toDoubleOrNull() ?: 0.0
    }

    /** Igual a [centavos], mas aceita milhar com ponto ("1.500,50"). */
    fun centavosF(valor: String, extra: String = ""): Int? = centavos(valor.replace(Regex("\\.(?=\\d{3}(?:\\D|$))"), ""), extra)

    private const val VALOR_F = "(?:r\\$ ?)?((?:\\d{1,3}(?:\\.\\d{3})+|\\d{1,6})(?:[.,]\\d{1,2})?)(?: ?reais| ?real)?(?: (?:e|com) (\\d{1,2})(?: centavos?)?)?"

    private const val N = "(\\d{1,9}(?:[.,]\\d{1,3})*)"
    private val CAIXA_APAGAR = Regex("^(?:apaga|apague|desfaz|desfaca|cancela|cancele|remove|remova|exclui|exclua) (?:o |a )?(?:ultimo lancamento|ultima despesa|ultima entrada|ultimo gasto|ultima saida)$")
    private val CAIXA_REMOVER_FIXA = Regex("^(?:tira|tire|remove|remova|apaga|apague|cancela|cancele|exclui|exclua) (?:a |o )?(?:despesa|conta|gasto) fix[oa] (?:de |do |da |com )?(.+)$")
    private val CAIXA_FIXA = listOf(
        Regex("^(?:(?:tenho|temos|cadastra|cadastre|anota|anote|coloca|coloque) (?:uma |um )?)?(?:despesa|conta|gasto|custo) fix[oa] (?:de |do |da |com )?(.+?) (?:de|no valor de|por) $VALOR_F(?: (?:por|todo|ao) mes| mensal| (?:todo|no|vence|vence no) dia (\\d{1,2}))*$"),
        Regex("^(?:o |a |os |as )?(.+?) (?:e|eh|custa|sai|fica) $VALOR_F (?:por|todo|ao) mes(?: (?:todo |no )?dia (\\d{1,2}))?$"),
    )
    /** 0: valor antes da descrição ("paguei 120 de internet"); 1: descrição antes ("paguei a internet de 120"). */
    private val CAIXA_DESPESA = listOf(
        0 to Regex(
            "^(?:eu )?(?:paguei|gastei|gastamos|pagamos|comprei|compramos|saiu|sairam|tive (?:um gasto|uma despesa) de|(?:lanca|lance|anota|anote|registra|registre) (?:uma |um )?(?:despesa|saida|gasto) de|despesa de|saida de|gasto de) " +
                "$VALOR_F(?: reais| real| conto| pila)? (?:de|com|em|pro|pra|para|do|da|dos|das|no|na|nos|nas) (.+)$"
        ),
        1 to Regex("^(?:eu )?(?:paguei|pagamos) (?:a |o |as |os )?(.+?) (?:de|por|no valor de|:) $VALOR_F(?: reais)?$"),
    )
    private val CAIXA_ENTRADA = Regex(
        "^(?:eu )?(?:recebi|entrou|entraram|ganhei|vendi|faturei|fiz)(?: mais)? $VALOR_F(?: reais| real)?(?: (?:de|com|em|no|na|por|pelo|pela|numa|em uma|de uma|vendendo) (.+))?$"
    )
    private val CALC_JUROS = Regex("^quanto (?:rende|rendem|fica|ficam|vira|viram|da|dao) (?:r\\$ ?)?$N(?: reais)? (?:a|com|em|aplicados? a) $N ?(?:%|por cento) (ao mes|ao ano|a\\.?m\\.?|a\\.?a\\.?) (?:por|em|durante|depois de) (\\d{1,3}) (meses|mes|anos|ano)$")
    private val CALC_PARCELA = Regex("^(?:qual|quanto) (?:a |o |e |eh |fica |fica a |da |sai )*(?:parcela|prestacao|valor da parcela) de (?:r\\$ ?)?$N(?: reais)? em (\\d{1,3}) ?(?:x|vezes) (?:com|a) (?:juros de )?$N ?(?:%|por cento)(?: ao mes| a\\.?m\\.?)?$")
    private val CALC_DESCONTO = Regex("\\bse (?:eu )?(?:der|dar|fizer|fazer|colocar) (?:um )?(?:desconto de )?$N ?(?:%|por cento)(?: de desconto)?,? (?:quanto|o quanto) (?:eu )?(?:preciso|tenho que|teria que) vender a mais\\b")
    private val CALC_MARGEM = Regex("\\b(?:qual|que) preco (?:eu )?(?:cobro|coloco|devo cobrar|vendo|cobrar) (?:para|pra) (?:ter )?(?:uma )?margem de $N ?(?:%|por cento)(?:,? (?:com|se o|sendo o) custo (?:de |e |eh |for )?(?:r\\$ ?)?$N)?")
    private val CAIXA_EQUILIBRIO = Regex("\\b(?:ponto de equilibrio|quanto (?:eu )?(?:preciso|tenho que) vender (?:pra|para|por mes pra|por mes para) (?:empatar|pagar as contas|cobrir as despesas|nao ter prejuizo|ficar no zero)|meta minima de vendas)\\b")
    private val CAIXA_PREVISAO = Regex("\\b(?:previsao (?:de|do|das) (?:faturamento|vendas|despesas|caixa|mes que vem|proximo mes|gastos)|quanto (?:eu )?vou (?:faturar|vender|gastar)(?: (?:no|este|esse) (?:proximo )?mes)?|faturamento previsto|projecao (?:de|do|das) (?:vendas|faturamento|caixa))\\b")
    private val CAIXA_RELATORIO = Regex("\\b(?:relatorio (?:financeiro|do mes|mensal|em pdf|completo|da loja|do mes passado)|gera(?:r)? (?:o |um )?relatorio|pdf do mes|pdf financeiro)\\b")
    private val CAIXA_GRAFICO = Regex("\\bgraficos?\\b")
    private val CAIXA_DESPESAS = Regex("\\b(?:quanto (?:eu |nos |a gente )?(?:gastei|gastamos|paguei|pagamos)|(?:minhas|nossas|as) despesas|lista de (?:gastos|despesas)|quais (?:foram )?(?:as )?(?:minhas )?despesas|meus gastos|gastos do mes|despesas do mes|despesas fixas|com o que (?:eu )?(?:gastei|gasto))\\b")
    private val CAIXA_RESULTADO = Regex("\\b(?:resultado (?:do|deste|desse|no) mes|resultado financeiro|resultado do mes passado|como (?:esta|ta|anda|vai|foi) (?:o )?(?:meu |nosso )?(?:caixa|financeiro|mes)|balanco(?: do mes)?|fluxo de caixa|lucro (?:real|liquido)|dre|fechamento do mes|quanto (?:sobrou|sobra|ficou) (?:de lucro|no caixa|pra mim|limpo)|como fechou o mes|meu caixa)\\b")

    /** "Quando o gel vai acabar", "previsão de estoque" (antes do clima, que também entende "previsão"). */
    private fun lojaPrevisao(f: Frase): Resultado? {
        val c = f.chave
        if (Regex("\\b(?:tempo|clima|chuva|chover|graus|temperatura|frio|calor|sol)\\b").containsMatchIn(c)) return null
        LOJA_PREVISAO.firstNotNullOfOrNull { it.matchEntire(c) }?.let { m ->
            return Resultado.Acao("loja_previsao", mapOf("produto" to f.trecho(m.groups[1]).orEmpty().replace(Regex("^(?i)(?:o|a|os|as)\\s+"), "")))
        }
        if (LOJA_PREVISAO_GERAL.containsMatchIn(c)) return Resultado.Acao("loja_previsao", mapOf("produto" to ""))
        return null
    }

    private val LOJA_PREVISAO = listOf(
        Regex("^(?:quando|em quantos dias|em quanto tempo) (?:o |a |os |as )?(.+?) (?:vai |vao )?(?:acaba|acabar|acabam|esgota|esgotar|esgotam)$"),
        Regex("^(?:quando|em quantos dias|em quanto tempo) (?:vai |vao )?(?:acaba|acabar|acabam|esgota|esgotar|esgotam) (?:o |a |os |as )?(?:estoque (?:d[oae]s? )?)?(.+)$"),
        Regex("^(?:qual a )?previsao (?:de|do) estoque (?:d[oae]s? )(.+)$"),
    )
    private val LOJA_PREVISAO_GERAL = Regex(
        "\\b(?:previsao (?:de|do) estoque|o que (?:vai|esta para|ta para|ta pra) acabar|o que acaba (?:logo|primeiro|essa semana|este mes)|vai faltar|quais produtos vao acabar)\\b"
    )
    private val LOJA_MARGENS = Regex(
        "\\b(?:margem media|margem de lucro media|margem (?:de lucro )?(?:media )?(?:dos|das|de todos os) produtos|media de margem|margem geral|" +
            "(?:quais|que) produtos? (?:me )?(?:da|dao|tem|deixam?) (?:menos|menor|pouco) (?:lucro|margem)|menores margens|menor margem|produtos sem custo)\\b"
    )
    private val LOJA_MARGEM = listOf(
        Regex("^(?:qual (?:e |eh )?(?:a |o )?)?(?:margem de lucro|margem|lucro) (?:de|do|da|dos|das|no|na|nos|nas|neste|nesta|nesse|nessa|deste|desta|desse|dessa|em cima do|em cima da|sobre o|sobre a) (.+?)(?: que (?:eu )?(?:vendi|vendo))?$"),
        Regex("^quanto (?:eu )?(?:lucro|ganho|ganhei|lucrei) (?:no|na|com o|com a|com|em cima do|em cima da|por|em cada|em) (.+?)(?: vendido| que (?:eu )?(?:vendo|vendi))?$"),
    )
    private val LOJA_LUCRO = Regex(
        "\\b(?:(?:qual|quanto) (?:foi |e |eh |esta |ta )?(?:o )?(?:meu |nosso )?lucro|quanto (?:eu |nos |a gente )?(?:lucrei|lucramos|lucrou|ganhei|ganhamos)|" +
            "lucro (?:de |d[oa] |n[oa] |desta |deste |dessa |desse )?(?:hoje|ontem|semana|mes)|meu lucro|nosso lucro)\\b"
    )
    private val LOJA_MAIS_VENDIDOS = Regex(
        "\\b(?:mais vendidos?|que mais (?:vende|vendeu|vendem|venderam|sai|saiu|saem|sairam)|campeoes de venda|top \\d* ?(?:produtos|vendas)|ranking (?:de|das|dos) (?:vendas|produtos))\\b"
    )
    private val LOJA_COMPARAR = Regex(
        "(?:\\bcompar\\w*\\b.*\\bmes (?:passado|anterior)\\b|\\bmes (?:passado|anterior)\\b.*\\b(?:vend|fatur)\\w*|\\b(?:vendas|faturamento|vendi|faturei|vendemos) (?:d[oe] |n[oa] )?mes (?:passado|anterior)\\b|" +
            "\\b(?:melhor|pior) (?:que|do que) o mes (?:passado|anterior)\\b|\\bcomparacao (?:de|das) vendas\\b)"
    )
    private val LOJA_PARADOS = Regex(
        "\\b(?:produtos? parados?|produtos? encalhados?|produtos? sem venda|produtos? que nao (?:vendem|vende|saem|sai)|" +
            "o que (?:nao )?(?:esta|ta|estao|tao) (?:parado|encalhado|sem vender)|o que nao (?:vende|vendeu|esta vendendo|ta vendendo|sai)|" +
            "(?:quais|que) produtos? (?:nao )?(?:estao|tao|esta|ta) (?:parados?|encalhados?|sem vender))\\b"
    )
    private val LOJA_REPOSICAO = Regex(
        "\\b(?:lista de reposicao|reposicao|repor|reponho|reabastecer|reabastecimento|o que repor|" +
            "(?:itens|produtos|o que) (?:que )?(?:eu |nos )?(?:foi |foram |ja foi |ja foram )?vendidos?|o que (?:eu |nos |a gente )?vend(?:i|emos|eu)|" +
            "lista (?:de|dos|das) (?:vendidos|itens vendidos|produtos vendidos|vendas))\\b"
    )
    private val LOJA_RETIRADAS = Regex(
        "\\b(?:retiradas|retirada pendente|retiradas? (?:para|pra|a) agendar|pedidos? (?:de|para|pra) retirada|pedidos? (?:para|pra) retirar|tem (?:alguma )?retirada|quem (?:vai|precisa) retirar)\\b"
    )
    private val LOJA_PEDIDOS = Regex(
        "\\b(?:tem (?:algum )?pedido(?:s)? novo|tem pedido|chegou (?:algum )?pedido|pedidos? (?:novos?|pendentes?|para (?:separar|enviar|mandar)|pra (?:separar|enviar|mandar)|de hoje|em aberto)|" +
            "(?:quais|que) pedidos (?:eu )?(?:tenho|preciso|tenho que|falta)|o que (?:eu )?(?:tenho|preciso) (?:que |para |pra )?(?:enviar|separar|mandar))\\b"
    )
    private val LOJA_ACABANDO = Regex(
        "\\b(?:(?:quais|que) produtos (?:estao|tao|que estao|que tao) (?:acabando|esgotados?|em falta|com (?:o )?estoque baixo|no fim)|" +
            "o que (?:esta|ta) (?:acabando|esgotado|em falta)(?: na loja| no estoque)?|produtos? (?:esgotados?|acabando|em falta)|estoque baixo|" +
            "o que (?:eu )?(?:preciso|tenho que) comprar(?: para a loja| pra loja)?)\\b"
    )
    private val PALAVRAS_GERAIS = setOf("produtos", "produto", "itens", "item", "unidades", "pecas", "coisas", "mercadorias", "mercadoria")
    private val LOJA_ESTOQUE_GERAL = Regex(
        "^(?:(?:e |e ai |me (?:diz|fala|conta) )?como (?:esta|ta|anda|vai|estao|tao|ficou|que esta|que ta) (?:o |a )?(?:meu |minha |nosso |o meu )?estoque(?: (?:da|na) loja| hoje| agora)?|" +
            "(?:me (?:da|de|passa|passe|faz|faca) )?(?:um |o )?(?:resumo|situacao|relatorio|panorama|balanco|visao geral|levantamento) (?:do |de |da )?(?:meu |nosso |o )?estoque(?: (?:da|na) loja)?|" +
            "estoque geral|(?:o )?meu estoque|(?:o )?estoque da loja|" +
            "(?:quantos|quantas) (?:produtos|itens|unidades|pecas|coisas|mercadorias)(?: diferentes)? (?:eu )?(?:tenho|tem|ha|temos|existem|estao)" +
            "(?: (?:no|em) estoque| na loja| cadastrad[oa]s| no site| ao todo| no total| a venda)*|" +
            "o que (?:eu )?(?:tenho|tem|temos|ha) (?:no|em) estoque|" +
            "(?:me )?(?:mostra|mostre|fala|diz|ve|veja|confere|confira|checa|cheque) (?:o |como (?:esta|ta) o )?(?:meu |nosso )?estoque(?: (?:da|na) loja)?)$"
    )
    private val LOJA_ESTOQUE = listOf(
        Regex("^(?:quanto|quantos|quantas) (?:tem|tenho|ha|ainda tem|ainda tenho|sobrou|sobraram|resta|restam) (?:de |do |da |no |em )?estoque (?:de |do |da |dos |das )(.+)$"),
        Regex("^(?:qual (?:e |eh )?o )?estoque (?:de |do |da |dos |das )(.+)$"),
        Regex("^(?:quantos|quantas) (.+?) (?:eu )?(?:tem|tenho|ainda tenho|ainda tem|sobraram|restam|ha)(?: (?:no|em) estoque| na loja)?$"),
        Regex("^(?:tem|ainda tem|tenho|ainda tenho) (.+?) (?:no|em) estoque$"),
    )
    private val APP_MUSICA = "(?: (?:no|na|pelo|pela) (spotify|youtube music|youtube|deezer|amazon music|apple music))?"
    private val RETOMAR = listOf(
        Regex("^(?:da|de|dar|aperta|aperte|manda|bota) (?:um |o )?play$APP_MUSICA$"),
        Regex(
            "^(?:toca|toque|tocar|coloca|coloque|bota|volta a tocar|voltar a tocar|continua|continue|continuar|retoma|retome|retomar|poe) " +
                "(?:a |o )?(?:ultima musica(?: (?:tocada|que tocou|que (?:eu )?(?:estava|tava) (?:ouvindo|escutando)))?|" +
                "musica que (?:eu )?(?:estava|tava) (?:ouvindo|escutando)|de onde parou|(?:o )?que (?:eu )?(?:estava|tava) (?:ouvindo|escutando)|" +
                "musica de antes|minha playlist de antes)$APP_MUSICA$"
        ),
        Regex("^(?:continua|continue|continuar|retoma|retome|retomar|volta a tocar|voltar a tocar|despausa|play)(?: a| o)?(?: musica| som)? (?:no|na|pelo|pela) (spotify|youtube music|youtube|deezer|amazon music|apple music)$"),
        Regex("^(?:toca|toque|tocar|abre e toca) (?:o |a )?(spotify|deezer|youtube music)$"),
    )
    private val PERTO = listOf(
        Regex("^(?:qual|onde) (?:e |eh |fica |tem |esta )?(?:a |o |as |os |uma |um )?(.+?) mais proxim[oa]s?(?: (?:daqui|de mim|de onde eu estou|da onde eu estou|de onde estou|da onde estou|da minha casa|de casa|do meu trabalho|do trabalho))?$"),
        Regex("^(?:onde (?:tem|ha|fica|acho|encontro|compro)|onde e que (?:tem|eu acho|eu encontro)|tem|procura|procure|ache|acha|me mostra|mostra) (?:uma |um |alguma |algum |a |o )?(.+?) (?:aqui )?(?:perto|por perto|proxim[oa])(?: daqui| de mim| de onde eu estou| da minha casa| do meu trabalho)?$"),
        Regex("^(.+?) (?:mais proxim[oa]|perto de mim|aqui perto|perto daqui)$"),
    )
    private val NAO_E_LUGAR = Regex(
        "\\b(planeta|estrela|galaxia|lua|sol|numero|data|feriado|dia|mes|ano|horario|hora|eleicao|jogo|resposta|palavra|valor|resultado)s?\\b"
    )
    private val PAUSAR = Regex("^(?:pausa|pausar|pause|para|parar|pare)(?:\\s+(?:a|o))?(?:\\s+(?:musica|som|video|audio))?$")
    private val CONTINUAR = Regex(
        "^(?:continua|continuar|continue|retoma|retomar|play|despausa|despausar|toca|tocar|volta a tocar|" +
            "solta o som)(?:\\s+(?:a|o))?(?:\\s+(?:musica|som))?$"
    )
    private val VOLUME_MAIS = Regex(
        "\\bvolume\\b.*\\b(aument\\w*|sob\\w*|maximo|mais alto)\\b|\\b(aument\\w*|sob\\w*)\\b.*\\bvolume\\b|" +
            "^(mais alto|aumenta o som|sobe o som)$"
    )
    private val VOLUME_MENOS = Regex(
        "\\bvolume\\b.*\\b(abaix\\w*|diminu\\w*|baix\\w*|mais baixo)\\b|\\b(abaix\\w*|diminu\\w*)\\b.*\\bvolume\\b|" +
            "^(mais baixo|abaixa o som|diminui o som)$"
    )

    private val MENSAGEM = Regex(
        "^(?:manda|mande|mandar|envia|envie|enviar|escreve|escreva)\\s+(?:uma?\\s+)?" +
            "(mensagem|msg|zap|whats|whatsapp|sms|torpedo)?\\s*(?:(?:no|pelo|por)\\s+(zap|whats|whatsapp|sms))?\\s*" +
            "(?:pra|para|pro|ao|a)\\s+(.+?)(?:\\s*:\\s*|\\s+(?:dizendo|falando|escrito|escrevendo|com o texto|que)\\s+)(.+)$"
    )
    private val LIGACAO = Regex(
        "^(?:liga|ligar|ligue|telefona|telefonar|telefone|faz uma ligacao|faca uma ligacao|chama)\\s+" +
            "(?:pra|para|pro|ao)\\s+(.+)$"
    )
    private val NAVEGAR = Regex(
        "^(?:me leva|me leve|leve-me|leve me|me levar|leva|me guia|me guie|navega|navegar|navegue|" +
            "(?:trace|traca|tracar|faz|faca|fazer|cria|crie|abre|abra|monta|monte|calcula|calcule|mostra|mostre|me da|me de)(?: uma| a)? rota|rota|" +
            "como (?:eu )?(?:chego|chegar|vou|faco para chegar|faco pra chegar)|" +
            "(?:(?:eu )?(?:quero|preciso|vou|tenho que) )?(?:ir|voltar)(?= (?:para|pra|pro|ate|de volta) )|vamos(?= (?:para|pra|pro|ate) )|bora(?= (?:para|pra|pro) ))" +
            "\\s+(?:(?:ate|para|pra|pro|ao|a|no|na|o|em|de volta)\\s+)*(.+)$"
    )
    private val MODO_ROTA = Regex(
        "\\s*,?\\s*(?:(?:indo |de |pelo |no |usando o |usando |com o |com )(?:bike|bicicleta|moto|onibus|transporte publico|carro)|" +
            "(?:indo )?(?:a pe|andando|caminhando|dirigindo)|(?:pelo|no|usando o|usando|com o|com o app|pelo app) waze|" +
            "(?:sem|evitando|evitar) (?:o |os )?pedagios?)\\s*$"
    )
    private val SALVAR_AQUI = Regex(
        "^(?:aqui (?:e|eh) (?:a |o )?(?:minha|meu|nossa|nosso) (\\w+(?: d[aoe] \\w+)?)|" +
            "(?:salva|salve|guarda|guarde|marca|marque) (?:aqui|onde eu estou|onde estou|essa localizacao|esse lugar|minha localizacao) como (?:a |o )?(?:minha |meu )?(\\w+(?: d[aoe] \\w+)?))$"
    )
    private val ONDE_FICA = Regex("^(?:onde fica|mostra no mapa|mostre no mapa|abre no mapa|mapa d[eoa])\\s+(?:o |a )?(.+)$")
    private val PESQUISA = Regex(
        "^(?:pesquisa|pesquise|pesquisar|procura|procure|procurar|busca|busque|buscar|googla|googlar)\\s+" +
            "(?:(?:no google|na internet|na web)\\s+)?(?:(?:por|sobre)\\s+)?(.+)$"
    )
    private val SITE = Regex(
        "^(?:abre|abrir|abra|abri|entra|entrar|entre|vai|acessa|acessar|acesse)\\s+(?:(?:no|em|o|a)\\s+)?" +
            "(?:site\\s+)?(?:(?:do|da)\\s+)?([a-z0-9-]+(?:(?:\\.|\\s+ponto\\s+)[a-z0-9-]+)+(?:/\\S*)?)$"
    )
    private val MUSICA = Regex(
        "^(?:toca|tocar|toque|coloca|coloque|bota|bote|poe|ponha|reproduz|reproduza|quero ouvir|ouvir|escutar)\\s+" +
            "(.+?)(?:\\s+(?:no|na|pelo|pela)\\s+(spotify|youtube music|youtube|deezer))?$"
    )
    private val ABRIR = Regex(
        "^(?:abre|abrir|abra|abri|inicia|iniciar|inicie|executa|executar|entra|entrar|entre|vai)\\s+" +
            "(?:(?:no|na|em|para o|pra o|pro|o|a|os|as)\\s+)?(?:(?:app|aplicativo)\\s+)?(?:(?:do|da|de)\\s+)?(.+)$"
    )
}
