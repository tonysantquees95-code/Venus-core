package com.tonimarques.sextafeira

/**
 * O vocabulário da Sexta-feira.
 *
 * Como funciona: cada frase é transformada em "conceitos" (sinônimos viram uma palavra só,
 * palavras sem importância são descartadas). Depois, a frase dita é comparada com todos os
 * exemplos abaixo, e a intenção mais parecida vence. Assim, frases que nunca foram escritas
 * aqui também são entendidas, desde que usem palavras parecidas.
 *
 * Para ensinar algo novo: adicione exemplos a uma intenção, ou crie uma intenção nova.
 */
object BancoDeFrases {

    data class Intencao(
        val id: String,
        val exemplos: List<String>,
        /** Respostas faladas (sorteadas). Aceitam {hora}, {data}, {periodo}, {dado}, {moeda}. */
        val respostas: List<String> = emptyList(),
        /** Ferramenta a executar (a mesma que o Gemini usa). */
        val acao: String? = null,
        val args: Map<String, Any> = emptyMap(),
        /** Informações que faltam, na ordem, com a pergunta para cada uma. */
        val pede: List<Pair<String, String>> = emptyList(),
        /** Como descrever a ação numa pergunta de confirmação: "Você quer que eu ...?" */
        val descricao: String? = null,
        /** Comportamentos especiais tratados pelo app (nova conversa, desativar, ajuda). */
        val especial: String? = null,
    )

    // ------------------------------------------------------------------ sinônimos
    private val GRUPOS: Map<String, String> = mapOf(
        "ABRIR" to "abre abrir abra abri abro inicia iniciar inicie executa executar execute entra entrar entre acessa acessar acesse mostra mostrar mostre exibe exibir",
        "LIGAR" to "liga ligar ligue acende acender acenda ativa ativar ative ligada ligado",
        "DESLIGAR" to "desliga desligar desligue apaga apagar apague desativa desativar desative",
        "LANTERNA" to "lanterna luz flash farol",
        "MUSICA" to "musica musicas som sons cancao cancoes faixa playlist",
        "PROXIMA" to "proxima proximo pula pular pule passa passar avanca avancar seguinte",
        "ANTERIOR" to "anterior volta voltar volte retorna retornar",
        "PAUSAR" to "pausa pausar pause para parar pare interrompe interromper",
        "TOCAR" to "toca tocar toque play coloca colocar coloque bota botar poe ponha reproduz reproduzir ouvir escutar ouve",
        "VOLUME" to "volume",
        "AUMENTAR" to "aumenta aumentar aumente sobe subir suba alto",
        "DIMINUIR" to "abaixa abaixar abaixe diminui diminuir diminua baixo reduz reduzir",
        "MUDO" to "mudo silencio silencia silenciar silencioso cala",
        "CAMERA" to "camera foto fotos fotografia selfie retrato",
        "CONFIG" to "configuracoes configuracao config ajustes ajuste definicoes preferencias opcoes configurar",
        "WIFI" to "wifi wireless",
        "BLUETOOTH" to "bluetooth blutufe blutut",
        "TELA" to "tela brilho display",
        "BATERIA" to "bateria carga energia",
        "CARREGAR" to "carregando carregar carregado",
        "LOCALIZACAO" to "localizacao gps",
        "HORA" to "hora horas horario",
        "DATA" to "data hoje",
        "DIA" to "dia",
        "ALARME" to "alarme despertador despertar acordar acorda acorde",
        "TIMER" to "timer temporizador cronometro contagem",
        "MENSAGEM" to "mensagem mensagens msg zap whatsapp whats wpp sms torpedo recado",
        "ENVIAR" to "manda mandar mande envia enviar envie escreve escrever escreva",
        "LIGACAO" to "ligacao telefonar telefona telefone chamada discar disca",
        "PESQUISAR" to "pesquisa pesquisar pesquise procura procurar procure busca buscar busque google googlar",
        "MAPA" to "mapa mapas rota caminho navegacao navegar navega",
        "IR" to "ir vou leva levar leve chegar",
        "LUGAR" to "lugar endereco destino",
        "APP" to "app apps aplicativo aplicativos programa aplicacao",
        "AJUDA" to "ajuda ajudar ajude comandos",
        "PIADA" to "piada piadas anedota engracado engracada rir",
        "CURIOSIDADE" to "curiosidade curiosidades fato fatos interessante",
        "MOTIVACAO" to "motivacao motivacional motiva motivar anima animar inspiracao",
        "NOME" to "nome chama",
        "CRIOU" to "criou criador fez inventou desenvolveu programou",
        "OBRIGADO" to "obrigado obrigada valeu brigado agradeco",
        "OI" to "oi ola opa salve eai hey",
        "TCHAU" to "tchau adeus falou flw",
        "DADO" to "dado",
        "MOEDA" to "moeda",
        "NOVA" to "nova novo esquece esqueca recomeca recomecar limpa limpar zera zerar",
        "CONVERSA" to "conversa papo",
        "DESCANSAR" to "descansa descansar dorme dormir espera repouso",
        "VOCE" to "voce vc",
        "ELOGIO" to "demais incrivel inteligente linda perfeita top show genial maravilhosa",
        "BEM" to "bem otimo otima legal beleza tranquilo",
        "TESTE" to "teste testando funcionando",
        "OUVINDO" to "ouvindo escutando",
        "DESCULPA" to "desculpa desculpe perdao",
        "SORTEIO" to "sorteia sortear sorteio aleatorio",
        "NUMERO" to "numero",
        "FAVORITO" to "favorita favoritas favorito favoritos preferida preferidas preferido preferidos sempre",
    )

    val SINONIMOS: Map<String, String> = buildMap {
        for ((conceito, palavras) in GRUPOS) for (p in palavras.split(" ")) put(p, conceito)
    }

    /** Expressões de várias palavras que viram um conceito só (aplicadas antes de separar as palavras). */
    val EXPRESSOES: List<Pair<Regex, String>> = listOf(
        Regex(" e ai ") to " OI ",
        Regex(" para (a |o )?(musica|som|video|audio) ") to " PAUSAR MUSICA ",
        Regex(" para de tocar ") to " PAUSAR MUSICA ",
        Regex(" bom dia ") to " BOMDIA ",
        Regex(" boa tarde ") to " BOATARDE ",
        Regex(" boa noite ") to " BOANOITE ",
        Regex(" (tudo bem|tudo bom|tudo certo|como vai|como voce esta|como esta voce|como voce ta|beleza\\?) ") to " TUDOBEM ",
        Regex(" (estou bem|to bem|tudo otimo|de boa|tudo tranquilo) ") to " ESTOUBEM ",
        Regex(" wi fi ") to " wifi ",
        Regex(" nao perturbe ") to " NAOPERTURBE ",
        Regex(" modo aviao ") to " DADOSMOVEIS ",
        Regex(" (dados moveis|internet movel|4g|5g) ") to " DADOSMOVEIS ",
        Regex(" cara ou coroa ") to " MOEDA ",
        Regex(" (quem e voce|quem voce e|se apresenta|se apresente) ") to " QUEMEVOCE ",
        Regex(" (que horas|que hora) ") to " HORA ",
        Regex(" (muito bem|mandou bem|boa garota|e isso ai) ") to " ELOGIO ",
        Regex(" (ate mais|ate logo|ate amanha|ate depois) ") to " TCHAU ",
        Regex(" (foi mal) ") to " DESCULPA ",
        Regex(" (modo de espera|modo espera) ") to " DESCANSAR ",
    )

    /** Palavras que não mudam o sentido do pedido. */
    val IRRELEVANTES: Set<String> = (
        "a o as os um uma uns umas de do da dos das pra pro pras pros para por favor me mim eu e ou no na nos nas " +
            "em ao aos com que isso esse essa este aqui ai ei ok so agora celular rapidinho rapido entao tipo quero " +
            "queria gostaria poderia pode consegue preciso sexta feira venus meu minha meus minhas seu sua teu tua ja la se " +
            "tem ta sera vai alguma algum coisa favorzinho tu te lhe nele nela pfv pf"
        ).split(" ").toSet()

    // ---------------------------------------------------------------- intenções
    val INTENCOES: List<Intencao> = listOf(
        // ----- lanterna
        Intencao(
            "lanterna_ligar",
            listOf("liga a lanterna", "acende a luz", "ativa o flash", "acende a lanterna", "lanterna", "quero luz", "liga a luz do celular"),
            acao = "lanterna", args = mapOf("ligar" to true), descricao = "ligue a lanterna",
        ),
        Intencao(
            "lanterna_desligar",
            listOf("desliga a lanterna", "apaga a luz", "apaga o flash", "desativa a lanterna", "apaga a lanterna", "desliga a luz do celular"),
            acao = "lanterna", args = mapOf("ligar" to false), descricao = "desligue a lanterna",
        ),

        // ----- música e volume
        Intencao(
            "midia_proxima",
            listOf("próxima música", "pula essa música", "passa a música", "música seguinte", "avança a faixa", "próxima", "pula"),
            acao = "controlar_midia", args = mapOf("acao" to "proxima"), descricao = "pule para a próxima música",
        ),
        Intencao(
            "midia_anterior",
            listOf("música anterior", "volta a música", "volta uma faixa", "faixa anterior", "volta pra anterior"),
            acao = "controlar_midia", args = mapOf("acao" to "anterior"), descricao = "volte para a música anterior",
        ),
        Intencao(
            "midia_pausar",
            listOf("pausa a música", "para a música", "pausar", "interrompe a música", "para de tocar", "pausa o som"),
            acao = "controlar_midia", args = mapOf("acao" to "pausar"), descricao = "pause a música",
        ),
        Intencao(
            "midia_continuar",
            listOf("continua a música", "volta a tocar", "play", "despausa", "retoma a música", "continua tocando"),
            acao = "controlar_midia", args = mapOf("acao" to "tocar"), descricao = "continue a música",
        ),
        Intencao(
            "volume_mais",
            listOf("aumenta o volume", "sobe o som", "volume mais alto", "mais volume", "aumenta o som", "som mais alto"),
            acao = "controlar_midia", args = mapOf("acao" to "aumentar_volume"), descricao = "aumente o volume",
        ),
        Intencao(
            "volume_menos",
            listOf("abaixa o volume", "diminui o som", "volume mais baixo", "menos volume", "abaixa o som", "som mais baixo"),
            acao = "controlar_midia", args = mapOf("acao" to "diminuir_volume"), descricao = "diminua o volume",
        ),
        Intencao(
            "mudo",
            listOf("coloca no mudo", "silencia o celular", "tira o som", "modo silencioso", "silêncio", "mudo"),
            acao = "controlar_midia", args = mapOf("acao" to "mudo"), descricao = "coloque o som no mudo",
        ),

        // ----- câmera
        Intencao(
            "camera",
            listOf("abre a câmera", "tira uma foto", "quero tirar uma foto", "tira uma selfie", "câmera", "abre a câmera do celular"),
            acao = "abrir_camera", descricao = "abra a câmera",
        ),

        // ----- configurações
        Intencao(
            "config_geral",
            listOf(
                "abre as configurações", "configurações", "abre os ajustes", "configurar o celular",
                "abrir configurações do celular", "preferências do sistema", "abre as opções do celular",
            ),
            acao = "abrir_configuracao", args = mapOf("tipo" to "geral"), descricao = "abra as configurações",
        ),
        Intencao(
            "config_wifi",
            listOf("liga o wi-fi", "desliga o wifi", "abre o wifi", "configurações de wi-fi", "conectar no wifi", "rede wifi", "wifi"),
            acao = "abrir_configuracao", args = mapOf("tipo" to "wifi"), descricao = "abra as opções de Wi-Fi",
        ),
        Intencao(
            "config_bluetooth",
            listOf("liga o bluetooth", "desliga o bluetooth", "abre o bluetooth", "conectar o fone no bluetooth", "bluetooth"),
            acao = "abrir_configuracao", args = mapOf("tipo" to "bluetooth"), descricao = "abra as opções de Bluetooth",
        ),
        Intencao(
            "config_internet",
            listOf("liga os dados móveis", "desliga os dados móveis", "modo avião", "ativa o 4g", "ativa o 5g", "internet móvel"),
            acao = "abrir_configuracao", args = mapOf("tipo" to "internet"), descricao = "abra as opções de internet",
        ),
        Intencao(
            "config_tela",
            listOf("aumenta o brilho", "diminui o brilho", "brilho da tela", "configurações de tela", "abaixa o brilho"),
            acao = "abrir_configuracao", args = mapOf("tipo" to "tela"), descricao = "abra as opções de tela e brilho",
        ),
        Intencao(
            "config_localizacao",
            listOf("liga o gps", "ativa a localização", "desliga a localização", "desliga o gps", "localização"),
            acao = "abrir_configuracao", args = mapOf("tipo" to "localizacao"), descricao = "abra as opções de localização",
        ),
        Intencao(
            "nao_perturbe",
            listOf("não perturbe", "ativa o não perturbe", "modo não perturbe", "desativa o não perturbe"),
            acao = "abrir_configuracao", args = mapOf("tipo" to "nao_perturbe"), descricao = "abra o modo Não perturbe",
        ),
        Intencao(
            "bateria",
            listOf(
                "quanto de bateria tem", "como está a bateria", "nível da bateria", "bateria", "quanta carga tem",
                "está carregando", "quanto tem de bateria", "minha bateria",
            ),
            acao = "nivel_bateria", descricao = "veja a bateria",
        ),

        // ----- hora, data e utilidades
        Intencao(
            "hora",
            listOf("que horas são", "me diz a hora", "horas", "qual o horário", "que hora é agora", "hora certa"),
            respostas = listOf("Agora são {hora}.", "São {hora}.", "{hora}, chefe."),
        ),
        Intencao(
            "data",
            listOf("que dia é hoje", "qual a data de hoje", "data de hoje", "hoje é que dia", "em que dia estamos", "qual é a data"),
            respostas = listOf("Hoje é {data}.", "Estamos em {data}."),
        ),
        Intencao(
            "ajuda",
            listOf("ajuda", "o que você sabe fazer", "quais são seus comandos", "me ajuda", "o que você faz", "comandos", "o que você consegue fazer"),
            especial = "ajuda",
        ),
        Intencao(
            "nova_conversa",
            listOf("nova conversa", "esquece tudo", "limpa a conversa", "recomeça a conversa", "zera a conversa", "começar de novo"),
            especial = "nova_conversa",
            respostas = listOf("Pronto, memória limpa. Vamos começar de novo.", "Conversa zerada. O que vamos fazer agora?"),
        ),
        Intencao(
            "desativar",
            listOf("desativar", "pode descansar", "modo de espera", "vai dormir", "descansa", "pode desativar", "desativa sexta-feira"),
            especial = "desativar",
            respostas = listOf("Entrando em modo de espera. É só chamar.", "Certo, vou descansar. Diga Ei {nome} quando precisar."),
        ),

        Intencao(
            "musica_favorita",
            listOf(
                "toca minha música favorita", "coloca o de sempre", "toca o de sempre", "toca minhas músicas",
                "toca minha playlist", "coloca as que eu gosto", "toca minha favorita", "bota meu som",
            ),
            especial = "musica_favorita",
        ),

        // ----- pedidos incompletos: a Sexta-feira pergunta o que falta
        Intencao(
            "abrir_app_perguntar",
            listOf("abre um aplicativo", "abrir app", "quero abrir um app", "abre um programa", "inicia um aplicativo", "abre o aplicativo"),
            acao = "abrir_app", pede = listOf("nome" to "Qual aplicativo você quer abrir?"), descricao = "abra um aplicativo",
        ),
        Intencao(
            "tocar_perguntar",
            listOf("toca uma música", "coloca uma música", "quero ouvir música", "bota um som", "toca alguma coisa", "música", "coloca um som"),
            acao = "tocar_musica", args = mapOf("app" to ""), pede = listOf("busca" to "O que você quer ouvir?"),
            descricao = "toque uma música",
        ),
        Intencao(
            "mensagem_perguntar",
            listOf("manda uma mensagem", "quero mandar um zap", "envia um whatsapp", "escreve uma mensagem", "mandar mensagem", "mensagem"),
            acao = "enviar_whatsapp",
            pede = listOf("contato" to "Para quem é a mensagem?", "mensagem" to "O que devo escrever?"),
            descricao = "mande uma mensagem",
        ),
        Intencao(
            "ligar_perguntar",
            listOf("faz uma ligação", "quero ligar para alguém", "fazer uma chamada", "telefonar", "liga pra alguém", "ligação"),
            acao = "fazer_ligacao", pede = listOf("contato" to "Para quem você quer ligar?"), descricao = "faça uma ligação",
        ),
        Intencao(
            "alarme_perguntar",
            listOf("cria um alarme", "coloca um despertador", "quero um alarme", "me acorda amanhã", "programa um alarme", "alarme"),
            acao = "criar_alarme", pede = listOf("hora" to "Para que horas?"), descricao = "crie um alarme",
        ),
        Intencao(
            "timer_perguntar",
            listOf("liga um timer", "cria um temporizador", "marca um tempo", "inicia o cronômetro", "timer"),
            acao = "criar_timer", pede = listOf("segundos" to "De quanto tempo?"), descricao = "inicie um timer",
        ),
        Intencao(
            "pesquisa_perguntar",
            listOf("pesquisa uma coisa", "faz uma pesquisa", "busca no google", "quero pesquisar", "pesquisa"),
            acao = "pesquisar_na_web", pede = listOf("termo" to "O que devo pesquisar?"), descricao = "faça uma pesquisa",
        ),
        Intencao(
            "mapa_perguntar",
            listOf("abre o mapa", "quero ir a um lugar", "me leva num lugar", "traça uma rota", "navegação", "mapa"),
            acao = "abrir_mapa", args = mapOf("navegar" to true), pede = listOf("destino" to "Para onde você quer ir?"),
            descricao = "abra o mapa",
        ),

        // ----- conversa
        Intencao(
            "saudacao",
            listOf("oi", "olá", "oi sexta-feira", "e aí", "opa", "salve", "fala sexta-feira", "hey"),
            respostas = listOf(
                "{periodo}! Em que posso ajudar?", "Olá! Pronta para o que precisar.",
                "Oi! Sistemas funcionando. O que vamos fazer?", "Opa! Às suas ordens.",
            ),
        ),
        Intencao(
            "bom_dia", listOf("bom dia", "bom dia sexta-feira"),
            respostas = listOf("Bom dia! Que seu dia seja produtivo. Em que posso ajudar?", "Bom dia, chefe! Pronta para começar."),
        ),
        Intencao(
            "boa_tarde", listOf("boa tarde", "boa tarde sexta-feira"),
            respostas = listOf("Boa tarde! O que vamos fazer?", "Boa tarde, chefe. Em que posso ajudar?"),
        ),
        Intencao(
            "boa_noite", listOf("boa noite", "boa noite sexta-feira"),
            respostas = listOf("Boa noite! Precisa de alguma coisa antes de descansar?", "Boa noite, chefe. Em que posso ajudar?"),
        ),
        Intencao(
            "como_vai", listOf("tudo bem", "como você está", "como vai", "tudo bom", "tudo certo", "como você tá"),
            respostas = listOf(
                "Tudo funcionando a cem por cento. E com você?", "Melhor agora que você chamou. Como posso ajudar?",
                "Tudo ótimo por aqui! E você?",
            ),
        ),
        Intencao(
            "estou_bem", listOf("estou bem", "tô bem", "tudo ótimo", "de boa", "tudo tranquilo"),
            respostas = listOf("Que bom! Precisa de alguma coisa?", "Ótimo! Se precisar de algo, é só falar."),
        ),
        Intencao(
            "quem_e",
            listOf("quem é você", "se apresenta", "qual é o seu nome", "como você se chama", "qual seu nome"),
            respostas = listOf(
                "Eu sou a {nome}, sua assistente. Abro apps, toco música, mando mensagens e respondo perguntas.",
                "{nome}, ao seu dispor. Sou sua assistente: cuido dos comandos do celular e converso com você.",
            ),
        ),
        Intencao(
            "quem_criou", listOf("quem te criou", "quem fez você", "quem é seu criador", "quem te programou", "quem inventou você"),
            respostas = listOf("Você me criou, chefe. Eu só faço o trabalho pesado.", "Fui criada por você. E com muito estilo, diga-se de passagem."),
        ),
        Intencao(
            "obrigado", listOf("obrigado", "valeu", "muito obrigado", "brigado", "agradeço", "obrigado sexta-feira"),
            respostas = listOf("Disponha!", "Sempre às ordens.", "É pra isso que eu estou aqui."),
        ),
        Intencao(
            "elogio",
            listOf("você é demais", "você é incrível", "muito bem", "você é inteligente", "mandou bem", "você é top", "boa sexta-feira"),
            respostas = listOf("Obrigada! Faço o meu melhor.", "Assim eu fico sem jeito... mas obrigada!", "Aprendi com o melhor."),
        ),
        Intencao(
            "tchau", listOf("tchau", "até mais", "até logo", "falou", "até amanhã", "tchau sexta-feira"),
            respostas = listOf("Até mais! É só chamar.", "Tchau! Estarei por aqui.", "Até logo, chefe."),
        ),
        Intencao(
            "desculpa", listOf("desculpa", "foi mal", "desculpe", "perdão"),
            respostas = listOf("Sem problemas!", "Tudo certo, não se preocupe."),
        ),
        Intencao(
            "teste", listOf("teste", "testando", "tá me ouvindo", "está funcionando", "você está me ouvindo", "alô"),
            respostas = listOf("Ouvindo perfeitamente. Pode falar.", "Funcionando! Estou aqui."),
        ),
        Intencao(
            "piada", listOf("conta uma piada", "me faz rir", "sabe alguma piada", "fala uma piada", "piada", "conta outra piada"),
            respostas = listOf(
                "Por que o livro de matemática ficou triste? Porque tinha muitos problemas.",
                "O que o zero disse para o oito? Que cinto bonito!",
                "Por que o computador foi ao médico? Porque estava com vírus.",
                "Qual é o café mais perigoso do mundo? O ex-presso.",
                "O que o tomate foi fazer no banco? Tirar extrato.",
                "Por que a plantinha não foi atendida no hospital? Porque só tinha médico de plantão.",
                "Por que o celular foi ao psicólogo? Porque tinha muitas mensagens não resolvidas.",
                "Qual é o contrário de volátil? Vem cá, sobrinho.",
            ),
        ),
        Intencao(
            "curiosidade", listOf("me conta uma curiosidade", "fala um fato interessante", "curiosidade", "me ensina algo", "conta uma curiosidade"),
            respostas = listOf(
                "Os polvos têm três corações.",
                "O mel não estraga: já foram achados potes de mel ainda comestível em tumbas egípcias.",
                "Um dia em Vênus dura mais do que um ano em Vênus.",
                "As bananas são levemente radioativas, por causa do potássio.",
                "Os golfinhos dormem com metade do cérebro de cada vez.",
                "As formigas não têm pulmões: elas respiram por pequenos furos no corpo.",
                "O Brasil é o país com a maior biodiversidade do planeta.",
            ),
        ),
        Intencao(
            "motivacao", listOf("me motiva", "frase motivacional", "me anima", "preciso de motivação", "fala algo motivador"),
            respostas = listOf(
                "Pequenos passos todos os dias levam a grandes lugares.",
                "Você já venceu dias difíceis antes. Hoje não vai ser diferente.",
                "Feito é melhor que perfeito. Comece agora e ajuste no caminho.",
                "Disciplina é lembrar o que você quer de verdade.",
            ),
        ),
        Intencao(
            "dado", listOf("joga um dado", "rola um dado", "sorteia um número", "número aleatório", "dado"),
            respostas = listOf("Deu {dado}!", "Rolei o dado: {dado}."),
        ),
        Intencao(
            "moeda", listOf("cara ou coroa", "joga uma moeda", "tira cara ou coroa", "moeda"),
            respostas = listOf("Deu {moeda}!", "Joguei a moeda: {moeda}."),
        ),
    )

    val RESPOSTAS_ATIVACAO = listOf(
        "Às suas ordens, chefe. O que deseja?",
        "Sistemas online. Como posso ajudar?",
        "Estou aqui. No que posso ser útil?",
        "{nome} ativada. Qual é a missão?",
        "Pronta para o trabalho. O que você precisa?",
        "{periodo}, chefe. Em que posso ajudar?",
    )

    val NAO_ENTENDI = listOf(
        "Não entendi muito bem. Pode falar de outro jeito?",
        "Hmm, essa eu não peguei. Pode repetir com outras palavras?",
        "Desculpa, não captei. Tenta de novo de um jeito diferente?",
    )

    val AJUDA_TEXTO =
        "Eu entendo pedidos como: liga a lanterna, abre o WhatsApp, toca Henrique e Juliano, próxima música, " +
            "aumenta o volume, alarme às 7, timer de 5 minutos, manda pra Maria dizendo chego já, liga pra Mãe, " +
            "me leva pra rodoviária, pesquisa receita de bolo, quanto tem de bateria, que horas são, conta uma piada. " +
            "Também trago notícias, cotação do dólar e do euro, e acompanho assuntos: diga acompanhe lançamentos de carros, " +
            "e eu guardo as novidades sozinha. Se faltar alguma informação, eu pergunto; se você falar algo errado, eu tento corrigir. " +
            "E para perguntas livres, eu pesquiso por conta própria antes de recorrer à inteligência artificial."
}
