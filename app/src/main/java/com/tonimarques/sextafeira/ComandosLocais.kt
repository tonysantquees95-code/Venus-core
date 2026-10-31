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
        if (!ehRecado) Calculadora.responder(c)?.let { return Resultado.Resposta(it) }
        if (!ehRecado) servicos(f)?.let { return it }
        if (!ehRecado) clima(f)?.let { return it }
        if (!ehRecado) cotacao(c)?.let { return it }
        acompanhar(f)?.let { return it }
        noticias(f)?.let { return it }
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
        val dia = if (Regex("\\bamanha\\b").containsMatchIn(c)) "amanha" else "hoje"
        var cidade = ""
        LUGAR_CLIMA.find(c)?.let { m ->
            val bruto = f.trecho(m.groups[1]).orEmpty()
                .replace(Regex("(?i)\\s+(hoje|amanh[ãa]|agora|de manh[ãa]|a tarde|à tarde|a noite|à noite)$"), "")
                .trim()
            if (bruto.isNotBlank() && normalizar(bruto) !in LUGARES_GENERICOS) cidade = bruto
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
        if (Regex("\\bcnpj\\b").containsMatchIn(c) && digitos.length == 14) {
            return Resultado.Acao("consultar_cnpj", mapOf("cnpj" to digitos))
        }
        if (Regex("\\bddd\\b").containsMatchIn(c) && digitos.length == 2) {
            return Resultado.Acao("consultar_ddd", mapOf("ddd" to digitos))
        }
        if (FERIADO.containsMatchIn(c)) return Resultado.Acao("feriados", emptyMap())
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

    private fun midia(c: String): Resultado? {
        val acao = when {
            PROXIMA.matches(c) -> "proxima"
            ANTERIOR.matches(c) -> "anterior"
            PAUSAR.matches(c) -> "pausar"
            CONTINUAR.matches(c) -> "tocar"
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
        NAVEGAR.matchEntire(f.chave)?.let { m ->
            val destino = f.trecho(m.groups[1]) ?: return null
            if (destino.isNotBlank()) return Resultado.Acao("abrir_mapa", mapOf("destino" to destino, "navegar" to true))
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
        "^(?:me leva|me leve|leva|navega|navegar|navegue|traca uma rota|traca rota|rota|" +
            "como (?:eu )?(?:chego|chegar|vou))\\s+(?:(?:ate|para|pra|pro|ao|a|no|na|o|em)\\s+)*(.+)$"
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
