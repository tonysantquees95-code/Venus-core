package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/**
 * "O que há de novo": a tela que aparece depois de uma atualização.
 * REGRA: toda mudança que altere o que o app faz ou como se usa (função nova, fluxo melhor, correção visível) ganha uma linha AQUI,
 * com id = o maior id + 1. A tela mostra só o que a pessoa ainda não viu, então não depende do número da versão.
 * Na primeira instalação ela não aparece: quem explica é o Guia do primeiro acesso.
 */
internal object Novidades {
    const val PREF_VISTAS = "novidades_vistas"

    data class Item(val id: Int, val data: String, val tipo: String, val titulo: String, val texto: String)

    val LISTA = listOf(
        Item(1, "2026-11-14", "novo", "O que há de novo", "Depois de cada atualização, esta tela conta o que mudou, o que melhorou e o que foi corrigido. Você também a encontra em Menu › O que há de novo."),
        Item(2, "2026-11-15", "correcao", "Respostas mais certas", "Perguntar a temperatura mais fria de um lugar (como a Antártica) agora busca a resposta, em vez de mostrar o clima daí onde você está. E dizer \"eu sou o administrador\" ou \"eu sou a dona da loja\" não vira mais o seu apelido. Além disso, a conta de administrador agora é reconhecida sozinha, sem precisar sair e entrar de novo."),
        Item(3, "2026-11-15", "melhoria", "Aviso sobre o Pix no guia", "No guia do primeiro acesso, o passo de aparecer sobre outros apps agora é opcional e avisa que alguns bancos bloqueiam o Pix com essa permissão ligada, dizendo onde desligar."),
        Item(4, "2026-11-15", "correcao", "Cadastro da loja volta sozinho", "Depois de reinstalar a Vênus e entrar no Vetorial ID, o nome, a loja, o site e as regras de venda voltam sozinhos da sua conta, em vez de aparecerem como \"Falta fazer\"."),
        Item(5, "2026-11-15", "melhoria", "Conversa com mais personalidade", "Em papo solto (como você está, tudo bem, obrigado, sim, não) a Vênus agora responde de verdade, lendo a conversa, em vez de soltar uma frase pronta. Ela reage ao que você disse, não repete a mesma resposta e puxa o assunto quando faz sentido, com mais humor e jeito próprio."),
        Item(6, "2026-11-15", "melhoria", "Janelinhas no visual novo", "As janelas de pergunta e confirmação (QR Code do plano, atualização, escolher destino, liberar microfone e outras permissões, entre muitas) agora seguem o visual atual da Vênus: usam a cor que você escolheu, os cantos arredondados e os botões iguais aos das telas de Ajustes, em vez do roxo e do formato antigo."),
        Item(7, "2026-11-15", "melhoria", "Respostas mais rápidas", "A resposta agora aparece enquanto a Vênus ainda está escrevendo, em vez de só no fim. Perguntas de informação (como funciona, o que é, quem foi) vão direto a quem responde, sem uma etapa a mais. A memória tem prazo, e se uma IA travar ou estourar o limite a Vênus passa para a próxima sem esperar de novo."),
        Item(8, "2026-11-15", "melhoria", "Chame por \"Ei Vênus\"", "A frase para chamar agora é \"Ei Vênus\" (os textos do app, do menu e da notificação já mostram isso). Você pode dizer o pedido na mesma frase: \"Ei Vênus, coloca a música tal\" já vai direto, sem repetir. Com \"Acionar sem dizer ei\" ligado, \"Vênus, aumentar o volume\" também funciona, mesmo sem música tocando."),
        Item(9, "2026-11-15", "melhoria", "Ela já abre ouvindo", "Ao chamar, a Vênus escreve a saudação e já abre o microfone, sem ficar falando antes. Assim o pedido sai mais rápido."),
        Item(10, "2026-11-15", "novo", "Ouvir, compartilhar e guardar", "Embaixo de cada resposta há três botões: Ouvir (com pausa e continuar), Compartilhar e Guardar. O texto guardado vai para a pasta Download › Vênus."),
        Item(11, "2026-11-15", "melhoria", "Só fala quando você fala", "Se você digita, a Vênus responde só por escrito. Ela responde em voz alta quando você pediu falando. Para ouvir uma resposta escrita, use o botão Ouvir."),
        Item(12, "2026-11-15", "novo", "Voz igual no computador", "A voz que você escolheu aqui (voz neural da Microsoft, jeito de falar, velocidade e tom) agora acompanha a sua conta Vetorial ID e aparece sozinha na Vênus do Windows. Se mudar no computador, muda aqui também."),
        Item(13, "2026-11-15", "correcao", "Conversa não vira previsão do tempo", "Quando você contava algo (como \"cheguei do show, o cover do Michael Jackson\"), a Vênus trocava \"cover\" por \"chover\" e respondia sobre o clima ou pedia a sua localização. Agora ela deixa palavras como cover, teaser e show como estão, só corrige palavras curtas quando o som é igual, e só trata \"chover\" ou \"frio\" como previsão em pedidos curtos de clima. Relatos e conversas vão para o papo normal."),
        Item(14, "2026-11-16", "correcao", "Papo sobre música não vira busca na loja", "Quando você respondia a uma pergunta da Vênus (como \"adoro a Human Nature do MJ\"), ela procurava um produto com esse nome na loja ou só dizia \"Anotado!\". Agora ela entende que é conversa, vê o que acabou de dizer antes de decidir e, quando você conta um gosto, comenta de verdade e ainda guarda na memória."),
        Item(15, "2026-11-16", "melhoria", "Papo mais natural", "No papo, a Vênus agora responde curto, no seu tom, e termina com uma pergunta só sobre o que você contou (por exemplo, o que mais marcou no show). Ela também não inventa detalhes do que você viveu: reage ao que você disse e pergunta."),
        Item(16, "2026-11-16", "correcao", "\"Play list\" não é o comando play", "Dizer \"uma play list para a loja\" não faz mais a Vênus continuar a música que estava tocando: ela entende que é conversa e responde. No papo ela também fica no assunto que você puxou, sem trazer a loja do nada, e só oferece o que realmente sabe fazer."),
        Item(17, "2026-11-16", "novo", "Escolher o plano e pagar no Pix ou no débito", "Ao renovar, agora você escolhe o plano (a Vênus sozinha, com o Vindex, com o Vortex ou o completo) e como pagar: cartão de crédito todo mês, ou Pix e cartão de débito por 30 dias. No Pix e no débito não renova sozinho: quando vencer, é só pagar mais 30 dias. Quem não tem cartão de crédito também consegue usar."),
        Item(18, "2026-11-16", "novo", "Promoção de lançamento", "Por tempo limitado, a suíte completa (Vênus, Vindex e Vortex) custa R$ 49,90 por mês, com 7 dias grátis. Quem assinar durante a promoção mantém esse preço por 6 meses. Veja em Configurações › Licença."),
        Item(19, "2026-11-16", "novo", "Até 3 lojas na mesma conta", "Em Menu › Loja › Minhas lojas você cria até 3 lojas (outro site, outro nicho) na mesma assinatura e troca entre elas. Cada loja tem o seu perfil, catálogo, vitrine e painel ao vivo."),
        Item(20, "2026-11-17", "melhoria", "A conexão da loja volta sozinha", "A chave de acesso do site, o login de administrador e o aviso de pedidos agora acompanham a sua conta Vetorial. Se você reinstalar a Vênus ou entrar em outro aparelho, é só entrar com a sua conta: não precisa configurar o site de novo."),
        Item(21, "2026-11-17", "correcao", "Música: comandos pela voz mais firmes", "Com música tocando, “Vênus, aumentar o volume”, “Vênus, trocar música” e os outros comandos agora funcionam sem precisar do “Ei”. Também corrigi o microfone que ligava e desligava sem parar e fazia a música pausar e tocar sozinha."),
        Item(22, "2026-11-17", "correcao", "Música por voz sem abrir a tela", "Dizer \"Vênus, próxima música\" (ou pausar, volume, tocar algo) agora só faz o que você pediu: não abre o pop-up, não pausa a música e não fica ouvindo de novo. Se você disser \"Ei Vênus\", aí sim ela abre e conversa. Também deixei a Vênus mais paciente quando você diz só o nome com música tocando."),
        Item(23, "2026-11-17", "melhoria", "Comandos de música e apps sem conversa", "\"Dê play no Deezer\" agora abre o Deezer fechado e dá play de verdade. E, ao pedir para tocar, pausar, mudar o volume ou abrir um app, a Vênus só faz: escreve o aviso na tela, sem ficar falando \"abrindo…\" nem voltar a ouvir."),
        Item(24, "2026-11-17", "novo", "Recuperar a senha sem e-mail", "Ao criar o Vetorial ID você define uma senha de recuperação de 6 números e uma pergunta secreta com a resposta. Se esquecer a senha, toque em Esqueci a senha, escolha um dos dois jeitos e crie a senha nova. Guarde bem esses dados."),
        Item(25, "2026-11-18", "novo", "Painel do dia", "Uma vez por dia, ao abrir a Vênus, aparece no chat um resumo curto: seus lembretes de hoje, como está a loja e uma dica do que ela sabe fazer. Para desligar, vá em Menu › Painel do dia."),
        Item(26, "2026-11-21", "novo", "Agenda de serviços para salão e barbearia", "Em Minha loja › Agenda de serviços você cadastra os serviços e a equipe, e o cliente marca o horário sozinho por um link, sem baixar nada. Quando alguém marca, a Vênus avisa no seu celular. Cada profissional vê o próprio dia num link só dele, a recepção vê todos, e dá para lembrar o cliente pelo WhatsApp com a mensagem pronta. Horário ocupado não aparece, então não tem marcação dobrada."),
        Item(27, "2026-11-21", "novo", "Meu site e logo personalizado", "Em Minha loja › Meu site e logo você escolhe entre vários logos feitos para o seu ramo (símbolo, formato, letra e cores), baixa a imagem para o Instagram e a fachada e publica um site do seu negócio com serviços, fotos, depoimentos e botões de agendar e de WhatsApp."),
        Item(28, "2026-11-21", "novo", "Feedback e relato de erros", "Em Menu › Enviar feedback e Menu › Reportar um erro você conta o que achou da Vênus, avisa quando algo não funcionou e responde uma pesquisa rápida de 1 minuto. A Vênus está em fase de testes (beta) e cada relato ajuda a melhorar."),
        Item(29, "2026-11-22", "novo", "Vendas no caixa do Vyntem", "Em Menu › Ajustes › Ligar ao Vyntem (caixa), cada venda paga e confirmada entra sozinha no caixa do Vyntem, o app grátis de finanças, com o número do pedido e o nome do cliente. Pedido aguardando pagamento não entra e venda cancelada vira estorno. Vale para as vendas a partir do momento em que você liga."),
        Item(30, "2026-11-22", "novo", "Recibos e etiquetas", "Em Minha loja › Recibos e etiquetas você gera, a partir de um pedido, o recibo, a notinha (cupom estreito) ou a etiqueta de envio 10x15, com o nome e o logo da sua loja. Compartilhe no WhatsApp, abra ou imprima. Também dá para fazer um recibo avulso. São documentos sem valor fiscal. No plano grátis são 15 por mês, com a marca da Vênus pequena no rodapé."),
        Item(31, "2026-11-23", "novo", "Vênus Vitrine dentro da Vênus", "Em Minha loja, logo abaixo, tem a nova aba Vênus Vitrine: criar o site do seu negócio em 6 passos, link curto e QR code, panfleto, logo e catálogo com pedido pelo WhatsApp, tudo num só lugar."),
        Item(32, "2026-11-23", "correcao", "Teste grátis não pede mais pagamento por engano", "Quem está nos 7 dias grátis não vê mais a tela \"O seu teste acabou\" por causa de uma informação antiga guardada no celular: o app confirma com o servidor antes de pedir qualquer pagamento."),
        Item(33, "2026-11-24", "melhoria", "Respostas à cliente com os produtos certos", "Ao escrever ou responder uma cliente, a Vênus agora acha os produtos que combinam com o que ela pediu, mesmo que as palavras sejam diferentes (e não só os primeiros da lista). Também usa sempre as regras e respostas que a loja já conferiu como base, e ignora ordens escondidas dentro da mensagem da cliente."),
        Item(34, "2026-11-24", "novo", "Conte se você vai assinar", "Durante o teste grátis (a partir do 2º dia), a Vênus pergunta de vez em quando se você pretende assinar e por quê. É rápido e dá para tocar em Agora não. Se você responder, ela não pergunta de novo por 7 dias; se fechar sem responder, ela volta amanhã. Suas respostas ajudam a gente a melhorar a Vênus."),
        Item(35, "2026-11-24", "novo", "Aprender a usar a Vênus", "Em Menu › Aprender a usar a Vênus você assiste a vídeos curtos (atendimento no WhatsApp, criar o seu site e Instagram no automático) e segue um passo a passo para deixar a loja pronta, criando um site novo ou conectando o que você já tem."),
        Item(36, "2026-11-24", "novo", "Plano anual da Vênus", "Ao renovar a Vênus, agora dá para pagar o plano anual: R$ 499 de uma vez no Pix ou no débito, valendo 365 dias (equivale a 2 meses grátis). Não renova sozinho: quando vencer, é só pagar de novo."),
        Item(37, "2026-11-24", "novo", "Loja pelo cardápio", "Na aba Vênus Vitrine, agora dá para criar a sua loja pelo cardápio: mande a foto ou o PDF, a Vênus lê os itens e os preços e monta o catálogo com pedido no WhatsApp. Você confere tudo antes de publicar. No grátis são 2 leituras por dia."),
    )

    fun rotulo(tipo: String): String = when (tipo) { "novo" -> "Novo"; "melhoria" -> "Melhorou"; "correcao" -> "Corrigido"; else -> tipo }
    fun ultimoId(lista: List<Item> = LISTA): Int = lista.maxOfOrNull { it.id } ?: 0
    /** O que a pessoa ainda não viu, da mais nova para a mais antiga. */
    fun novasDesde(visto: Int, lista: List<Item> = LISTA): List<Item> = lista.filter { it.id > visto }.sortedByDescending { it.id }
    fun texto(itens: List<Item>): String = itens.joinToString("\n\n") { "[${rotulo(it.tipo)}] ${it.titulo}\n${it.texto}" }

    /** Depois de uma atualização: mostra o que ainda não foi visto. Quem acabou de instalar já foi marcado como "em dia" na MainActivity. */
    fun mostrarSeHouver(a: MainActivity) {
        if (a.isFinishing) return
        val vistas = a.prefs.getInt(PREF_VISTAS, 0)
        val novas = novasDesde(vistas)
        if (novas.isNotEmpty()) mostrar(a, novas)
    }

    /** Menu › O que há de novo: o histórico todo. */
    fun mostrarTudo(a: MainActivity) = mostrar(a, novasDesde(0))

    private fun mostrar(a: MainActivity, itens: List<Item>) {
        val versao = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
        val t = TextView(a).apply {
            text = texto(itens)
            textSize = 15f
            val p = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        CaixaBuilder(a)
            .setTitle("O que há de novo" + if (versao.isNotBlank()) " · $versao" else "")
            .setView(ScrollView(a).apply { addView(t as View) })
            .setPositiveButton("Entendi") { _, _ -> a.prefs.edit().putInt(PREF_VISTAS, ultimoId()).apply() }
            .setCancelable(false)
            .show()
    }
}
