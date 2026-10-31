package com.tonimarques.sextafeira

/**
 * Conhecimentos gerais que a Vênus sabe de cor, sem internet e sem o Gemini:
 * dúvidas de português (porque/por que, mas/mais, mal/mau, crase...), tabela periódica,
 * estados e capitais do Brasil, ciências, geografia, corpo humano e fórmulas de matemática.
 * Recebe a frase normalizada (minúsculas, sem acentos). Devolve null se não souber.
 */
object Saber {

    fun responder(c: String): String? {
        val t = c.trim().trimEnd('?', '.', '!').replace(Regex("\\s+"), " ")
        if (t.length < 4) return null
        return estado(t) ?: elemento(t) ?: portugues(t) ?: fatos(t)
    }

    // ============================================================ português
    private class Duvida(val regra: Regex, val resposta: String)

    private val DUVIDAS = listOf(
        Duvida(
            Regex(
                "\\b(?:os (?:4|quatro) porques|porques)\\b|" +
                    "\\b(?:por que|porque)\\b.*\\b(?:junto ou separado|separado ou junto|com ou sem acento|diferenca|quando (?:usa|usar|uso|se usa)|qual (?:e )?(?:o )?(?:certo|correto)|como (?:se )?(?:usa|usar|escreve))\\b|" +
                    "\\b(?:diferenca|quando (?:usa|usar|uso|se usa)|como (?:se )?usa)\\b.*\\b(?:por que|porque)\\b"
            ),
            "Os quatro porquês: \"por que\" (separado, sem acento) é para perguntas e equivale a \"por qual motivo\": Por que você saiu? " +
                "\"Porque\" (junto, sem acento) é para respostas e explicações, vale \"pois\": Saí porque choveu. " +
                "\"Por quê\" (separado, com acento) aparece no fim da frase: Você saiu por quê? " +
                "\"Porquê\" (junto, com acento) é um substantivo, vem com artigo: Não sei o porquê disso."
        ),
        Duvida(
            Regex("\\b(mas ou mais|mais ou mas|diferenca entre mas e mais|diferenca entre mais e mas|quando (?:usa|usar|uso) (?:o )?mas)\\b"),
            "\"Mas\" é conjunção de oposição, troca por \"porém\": Quis ir, mas choveu. \"Mais\" é o contrário de \"menos\", indica quantidade: Quero mais café."
        ),
        Duvida(
            Regex("\\b(mal ou mau|mau ou mal|diferenca entre mal e mau|diferenca entre mau e mal|quando (?:usa|usar|uso) (?:o )?(?:mal|mau))\\b"),
            "\"Mal\" é o contrário de \"bem\": Dormi mal. \"Mau\" é o contrário de \"bom\": Ele é um mau motorista. Dica: troque por bem/bom e veja qual encaixa."
        ),
        Duvida(
            Regex("\\b(ha ou a|a ou ha|diferenca entre ha e a|ha (?:com|sem) h|quando (?:usa|usar|uso) (?:o )?ha)\\b"),
            "\"Há\" (verbo haver) indica tempo passado ou existência: Moro aqui há dois anos; Há muita gente. \"A\" indica tempo futuro ou distância: Daqui a dois dias; Fica a 5 km."
        ),
        Duvida(
            Regex("\\b(onde ou aonde|aonde ou onde|diferenca entre onde e aonde|quando (?:usa|usar|uso) (?:o )?aonde)\\b"),
            "\"Onde\" indica lugar fixo: Onde você mora? \"Aonde\" (a + onde) é com verbos de movimento: Aonde você vai?"
        ),
        Duvida(
            Regex("\\b(afim ou a fim|a fim ou afim|diferenca entre afim e a fim)\\b"),
            "\"A fim de\" indica finalidade ou vontade: Estou a fim de sair; Estudou a fim de passar. \"Afim\" significa semelhante, com afinidade: Temos gostos afins."
        ),
        Duvida(
            Regex("\\b(senao ou se nao|se nao ou senao|diferenca entre senao e se nao)\\b"),
            "\"Senão\" significa \"caso contrário\" ou \"a não ser\": Corra, senão vai perder o ônibus. \"Se não\" é \"caso não\": Se não chover, vamos à praia."
        ),
        Duvida(
            Regex("\\b(crase|a craseado|acento grave)\\b"),
            "A crase (à) é a junção da preposição \"a\" com o artigo \"a\". Use antes de palavra feminina quando o verbo pede \"a\": Vou à praia (vou a + a praia). " +
                "Dica: troque por uma palavra masculina; se virar \"ao\", tem crase: Vou ao mercado → Vou à feira. " +
                "Não use antes de palavra masculina, de verbo, nem de \"uma\". Use sempre em horas: às 8h."
        ),
        Duvida(
            Regex("\\b(menas|menos ou menas|existe menas)\\b"),
            "\"Menas\" não existe. \"Menos\" é invariável: menos pessoas, menos dinheiro."
        ),
        Duvida(
            Regex("\\b(meio ou meia|meia ou meio|meio cansada|meia cansada)\\b"),
            "Quando significa \"um pouco\", \"meio\" não varia: Ela está meio cansada. Quando significa \"metade\", concorda: meia hora, meio-dia e meia."
        ),
        Duvida(
            Regex("\\b(ao inves de|em vez de)\\b.*\\b(diferenca|ou|qual|certo)\\b|\\b(diferenca entre ao inves e em vez)\\b"),
            "\"Em vez de\" significa \"no lugar de\": Em vez de café, tomei chá. \"Ao invés de\" significa \"ao contrário de\", para coisas opostas: Ao invés de subir, desceu. Na dúvida, \"em vez de\" serve nos dois casos."
        ),
        Duvida(
            Regex("\\b(traz ou tras|tras ou traz|traz ou atras|diferenca entre traz e tras)\\b"),
            "\"Traz\" é do verbo trazer: Ele traz o pão. \"Trás\" indica posição: Fica lá trás, para trás. \"Atrás\" também é posição: Está atrás da porta."
        ),
        Duvida(
            Regex("\\b(conserto ou concerto|concerto ou conserto)\\b"),
            "\"Conserto\" é reparo: o conserto do carro. \"Concerto\" é apresentação musical: o concerto da orquestra."
        ),
        Duvida(
            Regex("\\b(sessao|secao|cessao)\\b.*\\b(sessao|secao|cessao|diferenca)\\b"),
            "\"Sessão\" é tempo de duração de algo: sessão de cinema. \"Seção\" (ou secção) é divisão, parte: seção de frios. \"Cessão\" é o ato de ceder: cessão de direitos."
        ),
        Duvida(
            Regex("\\b(para mim fazer|pra mim fazer|para eu fazer|mim ou eu)\\b"),
            "Antes de verbo, use \"eu\": Isso é para eu fazer. \"Mim\" vem depois de preposição sem verbo em seguida: Isso é para mim."
        ),
        Duvida(
            Regex("\\b(aja ou haja|haja ou aja)\\b"),
            "\"Aja\" é do verbo agir: Aja com calma. \"Haja\" é do verbo haver: Haja paciência!"
        ),
        Duvida(
            Regex("\\b(se eu ver|se eu vir|ver ou vir|vir ou ver)\\b"),
            "No futuro do subjuntivo, o verbo ver vira \"vir\": Quando eu vir o filme, te conto; Se eu vir ele, aviso. \"Se eu ver\" está errado."
        ),
        Duvida(
            Regex("\\b(a gente ou agente|agente ou a gente)\\b"),
            "\"A gente\" (separado) significa \"nós\": A gente vai sair. \"Agente\" (junto) é quem age: agente de saúde, agente secreto."
        ),
        Duvida(
            Regex("\\b(derrepente|de repente junto|de repente ou derrepente)\\b"),
            "O certo é \"de repente\", separado. \"Derrepente\" não existe."
        ),
        Duvida(
            Regex("\\b(obrigado ou obrigada|mulher fala obrigado)\\b"),
            "Concorda com quem fala: homem diz \"obrigado\", mulher diz \"obrigada\"."
        ),
        Duvida(
            Regex("\\b(tampouco|tao pouco)\\b.*\\b(ou|diferenca|certo)\\b"),
            "\"Tampouco\" significa \"também não\": Não estudou, tampouco trabalhou. \"Tão pouco\" é \"muito pouco\": Comeu tão pouco."
        ),
        Duvida(
            Regex("\\b(acerca de|a cerca de|ha cerca de)\\b.*\\b(ou|diferenca|certo)\\b"),
            "\"Acerca de\" é \"sobre\": Falamos acerca do projeto. \"A cerca de\" indica distância aproximada: Fica a cerca de 2 km. \"Há cerca de\" é tempo passado ou quantidade: Há cerca de 10 anos."
        ),
        Duvida(
            Regex("\\b(o que e (?:um )?(?:substantivo|adjetivo|verbo|adverbio|pronome|artigo|preposicao|conjuncao|interjeicao|numeral)|classes gramaticais|classes de palavras)\\b"),
            "As classes de palavras: substantivo (nomeia: casa, Maria), adjetivo (qualifica: bonita), verbo (ação ou estado: correr, ser), " +
                "advérbio (modifica verbo, adjetivo ou advérbio: muito, ontem), pronome (substitui o nome: ele, meu), artigo (o, a, um), " +
                "preposição (liga palavras: de, para, com), conjunção (liga orações: e, mas, porque), numeral (dois, primeiro) e interjeição (oba!, ai!)."
        ),
        Duvida(
            Regex("\\b(oxitona|paroxitona|proparoxitona)s?\\b"),
            "Pela sílaba mais forte: oxítona é a última (café, sofá), paroxítona é a penúltima (casa, fácil) e proparoxítona é a antepenúltima (lâmpada, médico). Toda proparoxítona tem acento."
        ),
        Duvida(
            Regex("\\b(sujeito e predicado|o que e (?:o )?sujeito|o que e (?:o )?predicado)\\b"),
            "Sujeito é de quem se fala na frase; predicado é o que se diz dele. Em \"Maria comprou pão\", o sujeito é Maria e o predicado é \"comprou pão\"."
        ),
    )

    private fun portugues(t: String): String? {
        for (d in DUVIDAS) if (d.regra.containsMatchIn(t)) return d.resposta
        return null
    }

    // ============================================================ estados
    private val ESTADOS = listOf(
        Triple("acre", "AC", "Rio Branco"), Triple("alagoas", "AL", "Maceió"), Triple("amapa", "AP", "Macapá"),
        Triple("amazonas", "AM", "Manaus"), Triple("bahia", "BA", "Salvador"), Triple("ceara", "CE", "Fortaleza"),
        Triple("distrito federal", "DF", "Brasília"), Triple("espirito santo", "ES", "Vitória"), Triple("goias", "GO", "Goiânia"),
        Triple("maranhao", "MA", "São Luís"), Triple("mato grosso", "MT", "Cuiabá"), Triple("mato grosso do sul", "MS", "Campo Grande"),
        Triple("minas gerais", "MG", "Belo Horizonte"), Triple("minas", "MG", "Belo Horizonte"), Triple("para", "PA", "Belém"),
        Triple("paraiba", "PB", "João Pessoa"), Triple("parana", "PR", "Curitiba"), Triple("pernambuco", "PE", "Recife"),
        Triple("piaui", "PI", "Teresina"), Triple("rio de janeiro", "RJ", "Rio de Janeiro"), Triple("rio grande do norte", "RN", "Natal"),
        Triple("rio grande do sul", "RS", "Porto Alegre"), Triple("rondonia", "RO", "Porto Velho"), Triple("roraima", "RR", "Boa Vista"),
        Triple("santa catarina", "SC", "Florianópolis"), Triple("sao paulo", "SP", "São Paulo"), Triple("sergipe", "SE", "Aracaju"),
        Triple("tocantins", "TO", "Palmas"),
    )
    private val NOMES_ESTADOS = mapOf(
        "AC" to "Acre", "AL" to "Alagoas", "AP" to "Amapá", "AM" to "Amazonas", "BA" to "Bahia", "CE" to "Ceará",
        "DF" to "Distrito Federal", "ES" to "Espírito Santo", "GO" to "Goiás", "MA" to "Maranhão", "MT" to "Mato Grosso",
        "MS" to "Mato Grosso do Sul", "MG" to "Minas Gerais", "PA" to "Pará", "PB" to "Paraíba", "PR" to "Paraná",
        "PE" to "Pernambuco", "PI" to "Piauí", "RJ" to "Rio de Janeiro", "RN" to "Rio Grande do Norte",
        "RS" to "Rio Grande do Sul", "RO" to "Rondônia", "RR" to "Roraima", "SC" to "Santa Catarina", "SP" to "São Paulo",
        "SE" to "Sergipe", "TO" to "Tocantins",
    )

    /** "do Rio Grande do Sul", "da Bahia", "de São Paulo" */
    private fun contracao(sigla: String) = when (sigla) {
        "BA", "PB" -> "da"
        "AL", "GO", "MG", "PE", "RO", "RR", "SC", "SP", "SE" -> "de"
        else -> "do"
    }

    private fun estado(t: String): String? {
        Regex("^(?:qual (?:e |eh )?)?(?:a )?capital (?:do estado )?(?:d[aoe]s?) (.+)$").find(t)?.let { m ->
            val nome = m.groupValues[1].trim()
            val e = ESTADOS.firstOrNull { it.first == nome } ?: return null
            val estado = NOMES_ESTADOS[e.second]
            return if (e.second == "DF") "Brasília é a capital do Brasil e fica no Distrito Federal."
            else "A capital ${contracao(e.second)} $estado (${e.second}) é ${e.third}."
        }
        Regex("^(?:qual (?:e |eh )?)?(?:a )?sigla (?:do estado )?(?:d[aoe]s?) (.+)$").find(t)?.let { m ->
            val e = ESTADOS.firstOrNull { it.first == m.groupValues[1].trim() } ?: return null
            return "A sigla ${contracao(e.second)} ${NOMES_ESTADOS[e.second]} é ${e.second}."
        }
        Regex("^(?:de )?(?:qual|que) estado (?:e|eh) (?:a )?(?:sigla )?([a-z]{2})$").find(t)?.let { m ->
            val sigla = m.groupValues[1].uppercase()
            val nome = NOMES_ESTADOS[sigla] ?: return null
            return "$sigla é a sigla de $nome."
        }
        Regex("\\b(menor|menos populosa|maior|mais populosa) cidade (?:d[oa] |de )(brasil|italia|portugal)\\b|\\bcidade (menos populosa|mais populosa|menor|maior) (?:d[oa] |de )(brasil|italia|portugal)\\b").find(t)?.let { m ->
            val tipo = m.groupValues[1].ifBlank { m.groupValues[3] }
            val pais = m.groupValues[2].ifBlank { m.groupValues[4] }
            val menor = tipo.startsWith("men")
            return when (pais) {
                "brasil" -> if (menor) {
                    "Em população, a menor cidade do Brasil é Serra da Saudade (MG), com cerca de 833 habitantes (Censo 2022). " +
                        "Em área, a menor é Santa Cruz de Minas (MG), com uns 3,5 km²."
                } else {
                    "A maior cidade do Brasil é São Paulo, com cerca de 11,5 milhões de habitantes (Censo 2022). Depois vêm Rio de Janeiro (6,2 milhões) e Brasília (2,8 milhões)."
                }
                "italia" -> if (menor) {
                    "A menor cidade (comuna) da Itália em população é Morterone, na Lombardia, com cerca de 30 habitantes."
                } else {
                    "A maior cidade da Itália é Roma, com cerca de 2,7 milhões de habitantes. Depois vêm Milão e Nápoles."
                }
                else -> if (menor) {
                    "O menor município de Portugal em população é o Corvo, nos Açores, com cerca de 430 habitantes."
                } else {
                    "A maior cidade de Portugal é Lisboa, com cerca de 545 mil habitantes (cerca de 2,9 milhões na área metropolitana). Depois vem o Porto."
                }
            }
        }
        if (Regex("\\bquantos estados (?:tem|ha|existem|possui) (?:no |o )?brasil\\b").containsMatchIn(t)) {
            return "O Brasil tem 26 estados e o Distrito Federal, somando 27 unidades federativas."
        }
        if (Regex("\\b(?:quais sao )?(?:as )?regioes do brasil\\b").containsMatchIn(t)) {
            return "O Brasil tem 5 regiões: Norte, Nordeste, Centro-Oeste, Sudeste e Sul."
        }
        return null
    }

    // ===================================================== tabela periódica
    private val ELEMENTOS = (
        "H Hidrogênio|He Hélio|Li Lítio|Be Berílio|B Boro|C Carbono|N Nitrogênio|O Oxigênio|F Flúor|Ne Neônio|" +
            "Na Sódio|Mg Magnésio|Al Alumínio|Si Silício|P Fósforo|S Enxofre|Cl Cloro|Ar Argônio|K Potássio|Ca Cálcio|" +
            "Sc Escândio|Ti Titânio|V Vanádio|Cr Cromo|Mn Manganês|Fe Ferro|Co Cobalto|Ni Níquel|Cu Cobre|Zn Zinco|" +
            "Ga Gálio|Ge Germânio|As Arsênio|Se Selênio|Br Bromo|Kr Criptônio|Rb Rubídio|Sr Estrôncio|Y Ítrio|Zr Zircônio|" +
            "Nb Nióbio|Mo Molibdênio|Tc Tecnécio|Ru Rutênio|Rh Ródio|Pd Paládio|Ag Prata|Cd Cádmio|In Índio|Sn Estanho|" +
            "Sb Antimônio|Te Telúrio|I Iodo|Xe Xenônio|Cs Césio|Ba Bário|La Lantânio|Ce Cério|Pr Praseodímio|Nd Neodímio|" +
            "Pm Promécio|Sm Samário|Eu Európio|Gd Gadolínio|Tb Térbio|Dy Disprósio|Ho Hólmio|Er Érbio|Tm Túlio|Yb Itérbio|" +
            "Lu Lutécio|Hf Háfnio|Ta Tântalo|W Tungstênio|Re Rênio|Os Ósmio|Ir Irídio|Pt Platina|Au Ouro|Hg Mercúrio|" +
            "Tl Tálio|Pb Chumbo|Bi Bismuto|Po Polônio|At Astato|Rn Radônio|Fr Frâncio|Ra Rádio|Ac Actínio|Th Tório|" +
            "Pa Protactínio|U Urânio|Np Netúnio|Pu Plutônio|Am Amerício|Cm Cúrio|Bk Berquélio|Cf Califórnio|Es Einstênio|Fm Férmio|" +
            "Md Mendelévio|No Nobélio|Lr Laurêncio|Rf Rutherfórdio|Db Dúbnio|Sg Seabórgio|Bh Bóhrio|Hs Hássio|Mt Meitnério|Ds Darmstádtio|" +
            "Rg Roentgênio|Cn Copernício|Nh Nihônio|Fl Fleróvio|Mc Moscóvio|Lv Livermório|Ts Tenesso|Og Oganessônio"
        ).split("|").mapIndexed { i, s -> Triple(i + 1, s.substringBefore(" "), s.substringAfter(" ")) }

    private fun artigo(nome: String) = if (nome.endsWith("a")) "da" else "do"

    private fun elemento(t: String): String? {
        fun porNome(n: String) = ELEMENTOS.firstOrNull { ComandosLocais.normalizar(it.third) == n.trim() }
        Regex("^(?:qual (?:e |eh )?)?(?:o )?simbolo (?:quimico )?(?:d[oa]) (.+)$").find(t)?.let { m ->
            val e = porNome(m.groupValues[1]) ?: return null
            return "O símbolo ${artigo(e.third)} ${e.third.lowercase()} é ${e.second}, número atômico ${e.first}."
        }
        Regex("^(?:qual (?:e |eh )?)?(?:o )?numero atomico (?:d[oa]) (.+)$").find(t)?.let { m ->
            val e = porNome(m.groupValues[1]) ?: return null
            return "O número atômico ${artigo(e.third)} ${e.third.lowercase()} é ${e.first} (símbolo ${e.second})."
        }
        Regex("^(?:qual|que) (?:e |eh )?(?:o )?elemento(?: quimico)? (?:(?:de |do )?simbolo|e|eh|tem (?:o )?simbolo) ([a-z]{1,2})$").find(t)?.let { m ->
            val e = ELEMENTOS.firstOrNull { it.second.equals(m.groupValues[1], ignoreCase = true) } ?: return null
            return "${e.second} é o símbolo ${artigo(e.third)} ${e.third.lowercase()}, número atômico ${e.first}."
        }
        Regex("^(?:qual (?:e |eh )?)?(?:o )?elemento (?:de )?numero atomico (\\d+)$").find(t)?.let { m ->
            val e = ELEMENTOS.getOrNull(m.groupValues[1].toInt() - 1) ?: return null
            return "O elemento de número atômico ${e.first} é ${if (artigo(e.third) == "da") "a" else "o"} ${e.third.lowercase()} (${e.second})."
        }
        if (Regex("\\bquantos elementos (?:tem|ha|existem) (?:na )?tabela periodica\\b").containsMatchIn(t)) {
            return "A tabela periódica tem 118 elementos conhecidos."
        }
        return null
    }

    // ===================================================== ciências e geografia
    private val FATOS = listOf(
        Regex("\\bvelocidade da luz\\b") to "A luz viaja a cerca de 300 mil km por segundo no vácuo (exatamente 299.792.458 m/s).",
        Regex("\\bvelocidade do som\\b") to "No ar, a cerca de 20 °C, o som viaja a uns 343 metros por segundo (cerca de 1.235 km/h).",
        Regex("\\b(?:aceleracao da )?gravidade (?:da|na) terra\\b|\\bvalor da gravidade\\b") to "A aceleração da gravidade na Terra é de cerca de 9,8 m/s².",
        Regex("\\b(?:ponto de )?ebulicao da agua\\b|\\bagua ferve a quantos graus\\b|\\bagua ferve com quantos graus\\b") to "Ao nível do mar, a água ferve a 100 °C.",
        Regex("\\b(?:ponto de )?(?:fusao|congelamento) da agua\\b|\\bagua congela a quantos graus\\b") to "A água congela (e o gelo derrete) a 0 °C, ao nível do mar.",
        Regex("\\bformula (?:quimica )?da agua\\b|\\bagua e formada por\\b") to "A água é H₂O: duas partes de hidrogênio para uma de oxigênio.",
        Regex("\\btemperatura (?:do|no) sol\\b") to "A superfície do Sol tem cerca de 5.500 °C; o núcleo chega a uns 15 milhões de °C.",
        Regex("\\bdistancia (?:da terra (?:ate|ao|para o) sol|do sol (?:ate|a|para) (?:a )?terra|entre a terra e o sol)\\b") to "A Terra fica, em média, a cerca de 150 milhões de km do Sol. A luz leva uns 8 minutos e 20 segundos para chegar aqui.",
        Regex("\\bdistancia (?:da terra (?:ate|a|para) (?:a )?lua|da lua (?:ate|a|para) (?:a )?terra|entre a terra e a lua)\\b") to "A Lua fica, em média, a cerca de 384 mil km da Terra.",
        Regex("\\bquantos planetas\\b") to "O Sistema Solar tem 8 planetas: Mercúrio, Vênus, Terra, Marte, Júpiter, Saturno, Urano e Netuno. Plutão é considerado planeta-anão desde 2006.",
        Regex("\\bmaior planeta\\b") to "Júpiter é o maior planeta do Sistema Solar: caberiam mais de 1.300 Terras dentro dele.",
        Regex("\\bmenor planeta\\b") to "Mercúrio é o menor planeta do Sistema Solar e o mais próximo do Sol.",
        Regex("\\bplaneta mais quente\\b") to "Vênus é o planeta mais quente: cerca de 465 °C, por causa do efeito estufa da atmosfera densa. Tenho orgulho do nome!",
        Regex("\\bplaneta mais (?:frio|distante|longe)\\b") to "Netuno é o planeta mais distante do Sol, a cerca de 4,5 bilhões de km, e um dos mais frios (cerca de -200 °C).",
        Regex("\\bquantos ossos\\b") to "O corpo de um adulto tem 206 ossos. Um bebê nasce com cerca de 300, que vão se fundindo.",
        Regex("\\bquantos dentes\\b") to "Um adulto tem 32 dentes (contando os do siso); uma criança tem 20 dentes de leite.",
        Regex("\\bquantos litros de sangue\\b") to "Um adulto tem de 4,5 a 5,5 litros de sangue, mais ou menos 7% do peso do corpo.",
        Regex("\\bmaior orgao do corpo\\b") to "A pele é o maior órgão do corpo humano.",
        Regex("\\bmaior osso do corpo\\b") to "O fêmur, na coxa, é o maior osso do corpo humano. O menor é o estribo, dentro do ouvido.",
        Regex("\\bquantos musculos\\b") to "O corpo humano tem mais de 600 músculos.",
        Regex("\\bquantos cromossomos\\b") to "O ser humano tem 46 cromossomos, organizados em 23 pares.",
        Regex("\\bmaior oceano\\b") to "O Oceano Pacífico é o maior do mundo, cobrindo cerca de um terço da superfície da Terra.",
        Regex("\\bquantos oceanos\\b") to "São 5 oceanos: Pacífico, Atlântico, Índico, Antártico (Austral) e Ártico.",
        Regex("\\bquantos continentes\\b") to "Normalmente se contam 6 continentes: América, Europa, Ásia, África, Oceania e Antártida (alguns separam as Américas e contam 7).",
        Regex("\\bmaior pais do mundo\\b") to "A Rússia é o maior país do mundo em área, com cerca de 17,1 milhões de km². O Brasil é o 5º.",
        Regex("\\bmenor pais do mundo\\b") to "O Vaticano é o menor país do mundo, com menos de meio km².",
        Regex("\\bpais mais populoso\\b") to "A Índia passou a China em 2023 e é hoje o país mais populoso do mundo, com mais de 1,4 bilhão de pessoas.",
        Regex("\\bmontanha mais alta\\b|\\bmaior montanha\\b") to "O Monte Everest, no Himalaia, é a montanha mais alta do mundo, com 8.849 metros.",
        Regex("\\bponto mais alto do brasil\\b|\\bmontanha mais alta do brasil\\b") to "O Pico da Neblina, no Amazonas, é o ponto mais alto do Brasil, com cerca de 2.995 metros.",
        Regex("\\bmaior rio do mundo\\b") to "O Amazonas é o maior rio do mundo em volume de água. Em comprimento, há disputa entre o Amazonas e o Nilo, ambos com uns 6.500 km.",
        Regex("\\bmaior rio do brasil\\b") to "O Rio Amazonas é o maior do Brasil e do mundo em volume de água.",
        Regex("\\bmaior deserto\\b") to "O maior deserto do mundo é a Antártida (deserto polar). Entre os desertos quentes, o maior é o Saara.",
        Regex("\\bmaior floresta\\b") to "A Floresta Amazônica é a maior floresta tropical do mundo, com cerca de 5,5 milhões de km².",
        Regex("\\bquantos paises (?:tem|ha|existem) no mundo\\b|\\bquantos paises existem\\b") to "A ONU reconhece 193 países-membros, além de 2 observadores (Vaticano e Palestina).",
        Regex("\\bfusos? horarios? do brasil\\b|\\bquantos fusos\\b") to "O Brasil tem 4 fusos horários; o de Brasília (UTC-3) é o oficial.",
        Regex("\\bcapital do brasil\\b") to "A capital do Brasil é Brasília, inaugurada em 1960.",
        Regex("\\bdescobrimento do brasil\\b|\\bquem descobriu o brasil\\b") to "A esquadra de Pedro Álvares Cabral chegou ao Brasil em 22 de abril de 1500.",
        Regex("\\bindependencia do brasil\\b") to "A Independência do Brasil foi proclamada por Dom Pedro I em 7 de setembro de 1822.",
        Regex("\\bproclamacao da republica\\b") to "A República foi proclamada em 15 de novembro de 1889, pelo marechal Deodoro da Fonseca.",
        Regex("\\babolicao da escravatura\\b|\\blei aurea\\b") to "A Lei Áurea, assinada pela Princesa Isabel em 13 de maio de 1888, aboliu a escravidão no Brasil.",
        Regex("\\bfotossintese\\b.*\\b(como funciona|o que e)\\b|\\b(como funciona|o que e) (?:a )?fotossintese\\b") to "Fotossíntese é o processo em que as plantas usam a luz do sol, a água e o gás carbônico para produzir glicose (alimento) e liberar oxigênio.",
        Regex("\\bquanto tempo a terra (?:leva|demora) para (?:dar a volta|girar) (?:em torno|ao redor) do sol\\b|\\bduracao do ano\\b") to "A Terra leva cerca de 365 dias e 6 horas para dar a volta no Sol; por isso existe o ano bissexto, a cada 4 anos.",
        Regex("\\bano bissexto\\b") to "Ano bissexto tem 366 dias (29 de fevereiro). Acontece a cada 4 anos, exceto nos anos múltiplos de 100 que não são de 400.",
        Regex("\\bquantos segundos tem (?:um|1) dia\\b") to "Um dia tem 86.400 segundos.",
        Regex("\\bquantos segundos tem (?:uma|1) hora\\b") to "Uma hora tem 3.600 segundos.",
        Regex("\\bquantas horas tem (?:um|1) ano\\b") to "Um ano comum tem 8.760 horas (8.784 no bissexto).",
        Regex("\\bquantas semanas tem (?:um|1) ano\\b") to "Um ano tem 52 semanas e 1 dia (2 dias no bissexto).",
        // matemática
        Regex("\\bteorema de pitagoras\\b") to "Teorema de Pitágoras: num triângulo retângulo, o quadrado da hipotenusa é a soma dos quadrados dos catetos: a² = b² + c².",
        Regex("\\b(?:formula de )?bhaskara\\b|\\bequacao do segundo grau\\b") to "Para ax² + bx + c = 0: Δ = b² - 4ac e x = (-b ± √Δ) / 2a. Se Δ < 0, não há raiz real.",
        Regex("\\barea do circulo\\b") to "Área do círculo: A = π·r² (pi vezes o raio ao quadrado). Comprimento da circunferência: C = 2·π·r.",
        Regex("\\barea do triangulo\\b") to "Área do triângulo: A = (base × altura) / 2.",
        Regex("\\barea do (?:quadrado|retangulo)\\b") to "Área do retângulo: base × altura. No quadrado, lado × lado (lado²).",
        Regex("\\barea do trapezio\\b") to "Área do trapézio: A = (base maior + base menor) × altura / 2.",
        Regex("\\bvolume (?:do|da) (?:esfera|bola)\\b") to "Volume da esfera: V = (4/3)·π·r³.",
        Regex("\\bvolume do cubo\\b") to "Volume do cubo: lado³ (lado × lado × lado).",
        Regex("\\bvolume do cilindro\\b") to "Volume do cilindro: V = π·r²·h (área da base vezes a altura).",
        Regex("\\bvalor de pi\\b|\\bquanto vale (?:o )?pi\\b|\\bnumero pi\\b") to "π (pi) vale aproximadamente 3,14159. É a razão entre o comprimento de uma circunferência e o seu diâmetro.",
        Regex("\\bnumero de ouro\\b|\\bproporcao aurea\\b") to "O número de ouro (φ) vale aproximadamente 1,618, ou (1 + √5) / 2.",
        Regex("\\bo que e (?:um )?numero primo\\b") to "Número primo é o que só é divisível por 1 e por ele mesmo: 2, 3, 5, 7, 11, 13, 17, 19, 23, 29...",
        Regex("\\bregra de tres\\b") to "Regra de três: se A está para B assim como C está para X, então X = B × C / A. Ex.: 2 kg custam 10 reais, 5 kg custam 10 × 5 / 2 = 25 reais. Pode me pedir a conta direto!",
        Regex("\\bcomo (?:calcula|calcular|se calcula) (?:a )?porcentagem\\b") to "Para achar X% de um valor, multiplique o valor por X e divida por 100. Ex.: 15% de 200 = 200 × 15 / 100 = 30. É só me pedir: quanto é 15% de 200.",
        Regex("\\bcomo (?:calcula|calcular|se calcula) (?:a )?media\\b") to "Média: some todos os valores e divida pela quantidade. Ex.: (7 + 8 + 9) / 3 = 8.",
        Regex("\\bnumeros romanos\\b") to "Números romanos: I = 1, V = 5, X = 10, L = 50, C = 100, D = 500, M = 1000. Um símbolo menor antes de um maior subtrai: IV = 4, IX = 9, XL = 40.",
    )

    private val ANIMAIS = Regex("\\b(cachorro|cao|caes|gato|cavalo|tubarao|vaca|boi|peixe|passaro|animal|animais|elefante|baleia|leao|cobra|galinha|porco|rato|macaco|girafa|crianca|bebe)\\b")
    private val SOBRE_O_CORPO = Regex("\\b(ossos|dentes|sangue|musculos|cromossomos)\\b")

    private fun fatos(t: String): String? {
        // "quantos ossos tem um gato" não é sobre o corpo humano
        if (SOBRE_O_CORPO.containsMatchIn(t) && ANIMAIS.containsMatchIn(t)) return null
        for ((regra, resposta) in FATOS) if (regra.containsMatchIn(t)) return resposta
        return null
    }
}
