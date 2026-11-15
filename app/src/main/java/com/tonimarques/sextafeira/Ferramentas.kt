package com.tonimarques.sextafeira

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * As habilidades da Sexta-feira. Cada uma tem uma descrição (que o Gemini lê)
 * e uma implementação (que roda no celular).
 *
 * Para criar uma habilidade nova:
 *  1. adicione a descrição em declaracoes();
 *  2. adicione o nome em executarAqui();
 *  3. escreva a função que faz a ação.
 */
class Ferramentas(private val activity: Activity, private val memoria: Memoria? = null) {

    private val principal = Handler(Looper.getMainLooper())

    /** Busca no Google pela IA (precisa de faturamento na chave). Definido pelo app. */
    var buscador: ((String) -> Web.Resposta?)? = null

    /** Novidades acompanhadas e respostas guardadas, no próprio celular. */
    val conhecimento: Conhecimento? = memoria?.let { Conhecimento(it) }

    /** Fontes da última resposta vinda da internet, para o app mostrar os links. */
    @Volatile
    var ultimasFontes: List<Web.Fonte> = emptyList()

    // ------------------------------------------------------------- descrições
    fun declaracoes(): JSONArray = JSONArray()
        .put(
            funcao(
                "sobre_mim",
                "Mostra quem você (a Vênus) é: versão, IAs que usa e em que ordem, todas as suas ferramentas, o que está ligado, " +
                    "memória e estudo, limites, lições dos seus erros, erros recentes e últimos ajustes. Use para responder sobre você mesma " +
                    "ou para explicar por que algo não funcionou.",
                emptyList(),
            )
        )
        .put(
            funcao(
                "vortex",
                "Pede ao Vortex (central de jogos retrô do computador ou do celular, que precisa estar aberto) para abrir um jogo pelo nome " +
                    "ou para otimizar o próprio Vortex.",
                listOf("acao"),
                "acao" to opcoes("O que o Vortex deve fazer.", "abrir_jogo", "otimizar"),
                "jogo" to texto("Nome do jogo a abrir (só para abrir_jogo), como a pessoa falou.")
            )
        )
        .put(
            funcao(
                "vindex",
                "Pede ao Vindex (otimizador do computador ou do celular, que precisa estar aberto) para otimizar o sistema com 1 clique " +
                    "(limpeza segura de temporários e memória) ou para dizer como está o aparelho (CPU, memória, disco).",
                listOf("acao"),
                "acao" to opcoes("O que o Vindex deve fazer.", "otimizar", "diagnostico")
            )
        )
        .put(
            funcao(
                "instagram_resumo",
                "Seguidores e desempenho dos últimos posts do Instagram da loja (curtidas, comentários, post que mais engajou).",
                emptyList(),
            )
        )
        .put(
            funcao(
                "abrir_app", "Abre um aplicativo instalado no celular.", listOf("nome"),
                "nome" to texto("Nome do app, por exemplo WhatsApp, Instagram, Câmera, Spotify.")
            )
        )
        .put(
            funcao(
                "tocar_musica",
                "Toca uma música, artista, álbum ou playlist em um app de música.",
                listOf("busca"),
                "busca" to texto("O que tocar, por exemplo 'Jorge e Mateus' ou 'playlist de rock'."),
                "app" to opcoes("App preferido, se o usuário disser.", "spotify", "youtube music", "youtube", "deezer")
            )
        )
        .put(
            funcao(
                "controlar_midia",
                "Controla a música ou vídeo que já está tocando e o volume do celular.",
                listOf("acao"),
                "acao" to opcoes(
                    "O que fazer.",
                    "tocar_pausar", "tocar", "pausar", "proxima", "anterior",
                    "aumentar_volume", "diminuir_volume", "mudo",
                    "volume_maximo", "volume_minimo", "volume_metade", "volume_nivel", "parar", "avancar", "voltar"
                ),
                "nivel" to texto("Só para volume_nivel: porcentagem de 0 a 100.")
            )
        )
        .put(
            funcao(
                "enviar_whatsapp",
                "Deixa uma mensagem pronta no WhatsApp para um contato ou número. " +
                    "O usuário ainda precisa tocar em Enviar.",
                listOf("mensagem"),
                "contato" to texto("Nome do contato salvo ou número com DDD. Vazio para o usuário escolher."),
                "mensagem" to texto("Texto da mensagem.")
            )
        )
        .put(
            funcao(
                "enviar_sms",
                "Deixa um SMS pronto para um contato ou número. O usuário ainda precisa tocar em Enviar.",
                listOf("contato", "mensagem"),
                "contato" to texto("Nome do contato salvo ou número com DDD."),
                "mensagem" to texto("Texto da mensagem.")
            )
        )
        .put(
            funcao(
                "fazer_ligacao",
                "Abre o discador com o número de um contato. O usuário toca para ligar.",
                listOf("contato"),
                "contato" to texto("Nome do contato salvo ou número com DDD.")
            )
        )
        .put(
            funcao(
                "lanterna", "Liga ou desliga a lanterna do celular.", listOf("ligar"),
                "ligar" to booleano("true para ligar, false para desligar.")
            )
        )
        .put(
            funcao(
                "criar_alarme", "Cria um alarme no relógio do celular.", listOf("hora", "minuto"),
                "hora" to inteiro("Hora de 0 a 23."),
                "minuto" to inteiro("Minuto de 0 a 59."),
                "descricao" to texto("Nome do alarme, opcional.")
            )
        )
        .put(
            funcao(
                "criar_timer", "Inicia um timer (contagem regressiva).", listOf("segundos"),
                "segundos" to inteiro("Duração total em segundos."),
                "descricao" to texto("Nome do timer, opcional.")
            )
        )
        .put(
            funcao(
                "abrir_site", "Abre um site no navegador.", listOf("endereco"),
                "endereco" to texto("Endereço, por exemplo youtube.com.")
            )
        )
        .put(
            funcao(
                "pesquisar_na_web",
                "Abre a pesquisa do Google no navegador. Use só se o usuário pedir para abrir o navegador; " +
                    "para responder perguntas, prefira buscar_na_internet.",
                listOf("termo"),
                "termo" to texto("O que pesquisar.")
            )
        )
        .put(
            funcao(
                "abrir_mapa", "Mostra um lugar no mapa ou inicia a navegação até ele.", listOf("destino"),
                "destino" to texto("Endereço ou nome do lugar."),
                "navegar" to booleano("true para iniciar a rota com GPS.")
            )
        )
        .put(funcao("abrir_camera", "Abre a câmera para tirar foto.", emptyList()))
        .put(funcao("nivel_bateria", "Informa a porcentagem de bateria do celular e se está carregando.", emptyList()))
        .put(
            funcao(
                "buscar_na_internet",
                "Pesquisa na internet (Google) e devolve um resumo com fontes. Use para notícias, avaliações de lojas " +
                    "e produtos, preços, horários, resultados de jogos e qualquer informação atual.",
                listOf("pergunta"),
                "pergunta" to texto("A pergunta completa, como o usuário faria no Google.")
            )
        )
        .put(
            funcao(
                "previsao_do_tempo", "Temperatura atual e previsão do tempo: hoje, amanhã, próximos 7 dias, fim de semana ou a que horas chove.", emptyList(),
                "cidade" to texto("Cidade; vazio para a cidade do usuário."),
                "dia" to opcoes("Qual período. horas = a que horas chove hoje.", "hoje", "amanha", "semana", "fim_de_semana", "horas", "horas_amanha")
            )
        )
        .put(
            funcao(
                "traduzir", "Traduz um texto para outro idioma (sem precisar do Google).", listOf("texto", "para"),
                "texto" to texto("O texto a traduzir."),
                "para" to texto("Código do idioma de destino: en, es, fr, it, de, ja, zh-CN, pt-BR..."),
                "de" to texto("Código do idioma de origem; padrão pt-BR.")
            )
        )
        .put(
            funcao(
                "consultar_cep", "Endereço (rua, bairro, cidade) de um CEP brasileiro.", listOf("cep"),
                "cep" to texto("O CEP, só números.")
            )
        )
        .put(
            funcao(
                "buscar_cep", "Descobre o CEP de uma rua numa cidade brasileira.", listOf("logradouro", "cidade"),
                "logradouro" to texto("Nome da rua ou avenida."),
                "cidade" to texto("Cidade."),
                "estado" to texto("Sigla do estado, se souber.")
            )
        )
        .put(
            funcao(
                "salvar_lugar",
                "Guarda o endereço de um lugar do usuário (casa, trabalho, academia...) para rotas como 'me leva para casa'.",
                listOf("lugar", "endereco"),
                "lugar" to texto("Nome do lugar: casa, trabalho, academia, casa da mãe..."),
                "endereco" to texto("Endereço completo ou nome do lugar (ex.: Rua dos Andradas, 1000, Centro).")
            )
        )
        .put(
            funcao(
                "dicionario", "Dicionário de português (Wikcionário): significado, sinônimos, antônimos ou plural de uma palavra.",
                listOf("palavra"),
                "palavra" to texto("A palavra, com acentos."),
                "tipo" to opcoes("O que consultar.", "significado", "sinonimos", "antonimos", "plural")
            )
        )
        .put(
            funcao(
                "consultar_cnpj", "Dados públicos de uma empresa pelo CNPJ (razão social, situação, cidade, atividade).", listOf("cnpj"),
                "cnpj" to texto("O CNPJ, só números.")
            )
        )
        .put(
            funcao(
                "cotacao_moeda", "Cotação atual de uma moeda em reais (dólar, euro, bitcoin, libra...).", listOf("moeda"),
                "moeda" to texto("Nome da moeda, por exemplo 'dólar'.")
            )
        )
        .put(
            funcao(
                "noticias",
                "Notícias e novidades recentes sobre um assunto (por exemplo, lançamentos de celulares). " +
                    "Funciona sem o Google pago; prefira para novidades.",
                listOf("tema"),
                "tema" to texto("O assunto.")
            )
        )
        .put(
            funcao(
                "resumo_wikipedia", "Resumo da Wikipédia sobre uma pessoa, lugar, conceito ou acontecimento.", listOf("assunto"),
                "assunto" to texto("O assunto, por exemplo 'Santos Dumont'.")
            )
        )
        .put(
            funcao(
                "salvar_memoria",
                "Guarda um fato duradouro sobre o usuário (gostos, rotina, pessoas próximas, planos). " +
                    "Nunca salve senhas, documentos, dados bancários ou de saúde.",
                listOf("fato"),
                "fato" to texto("O fato, curto, por exemplo: 'gosta de rock nacional'.")
            )
        )
        .put(
            funcao(
                "abrir_configuracao",
                "Abre uma tela de configurações do celular. Usar para Wi-Fi, Bluetooth etc., " +
                    "porque o Android não deixa apps ligarem ou desligarem isso sozinhos.",
                listOf("tipo"),
                "tipo" to opcoes(
                    "Qual configuração.",
                    "wifi", "bluetooth", "internet", "volume", "tela", "bateria",
                    "localizacao", "aplicativos", "nao_perturbe", "geral"
                )
            )
        )

    private fun funcao(
        nome: String,
        descricao: String,
        obrigatorios: List<String>,
        vararg parametros: Pair<String, JSONObject>,
    ): JSONObject {
        val obj = JSONObject().put("name", nome).put("description", descricao)
        if (parametros.isNotEmpty()) {
            val propriedades = JSONObject()
            for ((chave, valor) in parametros) propriedades.put(chave, valor)
            obj.put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", propriedades)
                    .put("required", JSONArray(obrigatorios))
            )
        }
        return obj
    }

    private fun texto(descricao: String) = JSONObject().put("type", "string").put("description", descricao)
    private fun inteiro(descricao: String) = JSONObject().put("type", "integer").put("description", descricao)
    private fun booleano(descricao: String) = JSONObject().put("type", "boolean").put("description", descricao)
    private fun opcoes(descricao: String, vararg valores: String) =
        texto(descricao).put("enum", JSONArray(valores.toList()))

    // ------------------------------------------------------------- execução
    /** Chamado fora da thread principal; roda a ação na thread principal e espera o resultado. */
    fun executar(nome: String, argumentos: JSONObject): String {
        // Ferramentas que usam a internet rodam na thread de quem chamou (nunca na principal).
        if (nome in USAM_REDE) return executarSeguro(nome, argumentos)
        if (Looper.myLooper() == Looper.getMainLooper()) return executarSeguro(nome, argumentos)
        val pronto = CountDownLatch(1)
        var resultado = "A ação não respondeu."
        principal.post {
            resultado = executarSeguro(nome, argumentos)
            pronto.countDown()
        }
        return if (pronto.await(15, TimeUnit.SECONDS)) resultado else "A ação demorou demais."
    }

    /** Executa uma ação pedida pelos comandos locais (argumentos em mapa simples). */
    fun executar(nome: String, argumentos: Map<String, Any>): String =
        executar(nome, JSONObject(argumentos))

    private fun executarSeguro(nome: String, argumentos: JSONObject): String = try {
        executarAqui(nome, argumentos).also { resultado ->
            // o que mudou algo de verdade entra no diário de ajustes
            Diario.ACOES_QUE_MUDAM[nome]?.let { area ->
                val falhou = Regex("^(?:Não|Nao|Deu erro|O site recusou|Sem internet|Tudo bem, não mudei)").containsMatchIn(resultado)
                if (!resultado.endsWith("?") && !resultado.contains("Diga sim")) Diario.anotar(activity, area, resultado, "voz", ok = !falhou)
            }
        }
    } catch (e: Instagram.ErroDoInstagram) {
        e.message.orEmpty()
    } catch (e: ActivityNotFoundException) {
        "Não encontrei nenhum app no celular capaz de fazer isso."
    } catch (e: SecurityException) {
        "O Android não deixou fazer isso: ${e.message}"
    } catch (e: Exception) {
        Registro.anotar(activity, "erro", "$nome → " + Registro.resumoDoErro(e).replace("\n", " | "))
        "Deu erro ao executar $nome: ${e.message}"
    }

    private fun executarAqui(nome: String, a: JSONObject): String = when (nome) {
        "sobre_mim" -> memoria?.let { Consciencia.relatorio(activity, it, this) } ?: "Minha memória não está disponível agora."
        "abrir_app" -> abrirApp(a.optString("nome"))
        "tocar_musica" -> tocarMusica(a.optString("busca"), a.optString("app"))
        "controlar_midia" -> controlarMidia(a.optString("acao"), a.optString("app"), a.optString("nivel"))
        "vindex" -> vindexPedir(a.optString("acao"))
        "vortex" -> vortexPedir(a.optString("acao"), a.optString("jogo"))
        "conta_vetorial" -> contaVetorial(a.optString("acao"))
        "buscar_perto" -> buscarPerto(a.optString("oque"), a.optString("referencia"))
        "salvar_lugar" -> salvarLugar(a.optString("lugar"), a.optString("endereco"))
        "enviar_whatsapp" -> enviarWhatsapp(a.optString("contato"), a.optString("mensagem"))
        "enviar_sms" -> enviarSms(a.optString("contato"), a.optString("mensagem"))
        "fazer_ligacao" -> ligar(a.optString("contato"))
        "confirmar_ligacao" -> confirmarLigacao()
        "lanterna" -> lanterna(a.optBoolean("ligar", true))
        "criar_alarme" -> alarme(a.optInt("hora"), a.optInt("minuto"), a.optString("descricao"))
        "criar_timer" -> timer(a.optInt("segundos"), a.optString("descricao"))
        "criar_lembrete" -> {
            val l = Lembretes.criar(activity, Lembretes.Pedido(a.optLong("quando"), a.optString("texto")))
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 31)
            "Combinado: ${Lembretes.quandoFalado(l.quando)} eu te lembro: ${l.texto.replaceFirstChar { it.lowercase() }}."
        }
        "listar_lembretes" -> {
            val lista = Lembretes.futuros(activity)
            if (lista.isEmpty()) "Você não tem nenhum lembrete marcado."
            else "Seus lembretes: " + lista.take(8).joinToString("; ") { "${Lembretes.quandoFalado(it.quando)}, ${it.texto.replaceFirstChar { c -> c.lowercase() }}" } + "."
        }
        "cancelar_lembrete" -> {
            val n = Lembretes.cancelar(activity, a.optString("trecho"))
            when {
                n == 0 -> "Não achei nenhum lembrete assim."
                n == 1 -> "Pronto, cancelei o lembrete."
                else -> "Pronto, cancelei $n lembretes."
            }
        }
        "rotina_bom_dia" -> rotinaBomDia()
        "ler_mensagens" -> lerMensagens(a.optString("de"))
        "responder_mensagem" -> prepararResposta(a.optString("para"), a.optString("texto"))
        "confirmar_resposta" -> confirmarResposta()
        "abrir_site" -> abrirSite(a.optString("endereco"))
        "pesquisar_na_web" -> pesquisar(a.optString("termo"))
        "abrir_mapa" -> mapa(
            a.optString("destino"), a.optBoolean("navegar", false),
            a.optString("modo"), a.optString("app"), a.optString("evitar")
        )
        "salvar_lugar_aqui" -> salvarLugarAqui(a.optString("lugar"))
        "abrir_camera" -> {
            activity.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "Câmera aberta."
        }
        "abrir_configuracao" -> configuracao(a.optString("tipo"))
        "nivel_bateria" -> bateria()
        "previsao_do_tempo" -> clima(a.optString("cidade"), a.optString("dia", "hoje"))
        "resumo_wikipedia" -> wikipedia(a.optString("assunto"))
        "cotacao_moeda" -> Pesquisa.cotacao(a.optString("moeda"))
        "traduzir" -> Servicos.traduzir(a.optString("texto"), a.optString("para", "en"), a.optString("de", "pt-BR").ifBlank { "pt-BR" })
        "consultar_cep" -> Servicos.cep(a.optString("cep"))
        "loja_resumo" -> daLoja { it.resumo(a.optString("periodo", "hoje")) }
        "loja_pedidos" -> daLoja { it.pedidosPendentes() }
        "loja_acabando" -> daLoja { it.acabando() }
        "loja_estoque" -> daLoja { it.estoqueDe(a.optString("produto")) }
        "loja_retiradas" -> daLoja { it.retiradas() }
        "loja_reposicao" -> daLoja { it.reposicao(a.optString("periodo", "semana")) }
        "loja_mais_vendidos" -> daLoja { it.maisVendidos(a.optString("periodo", "mes")) }
        "loja_comparar" -> daLoja { it.comparar() }
        "loja_parados" -> daLoja { it.parados() }
        "pesquisar_preco" -> pesquisarPreco(a.optString("produto"))
        "instagram_resumo" -> Instagram.resumo(activity)
        "robo_resumo" -> daLoja { it.robo() }
        "robo_ligar" -> daLoja { it.ligarRobo(a.optBoolean("ligado", true)) }
        "loja_curva_abc" -> daLoja { Gestao.textoABC(Gestao.curvaABC(it.pedidos(), Calendar.getInstance())) }
        "loja_compras" -> daLoja { l ->
            val prazo = if (a.has("prazo")) a.optInt("prazo") else prazoDoFornecedor()
            Gestao.textoCompra(Gestao.sugestaoDeCompra(l.pedidos(), l.produtos(), Calendar.getInstance(), prazo), prazo)
        }
        "gestao_prazo" -> {
            val dias = a.optInt("dias").coerceIn(1, 120)
            activity.getSharedPreferences("gestao", 0).edit().putInt("prazo_fornecedor", dias).apply()
            "Anotado: o fornecedor entrega em ${if (dias == 1) "1 dia" else "$dias dias"}. Vou usar isso para avisar a hora de comprar."
        }
        "loja_recompra" -> daLoja { l -> Gestao.textoRecompra(Gestao.clientesParaRecompra(l.pedidos(), System.currentTimeMillis()), System.currentTimeMillis()) }
        "loja_chamar_cliente" -> daLoja { l ->
            val lista = Gestao.clientesParaRecompra(l.pedidos(), System.currentTimeMillis(), minDias = 0, maxDias = 3650)
            val c = Gestao.acharCliente(lista, a.optString("nome"))
            if (c == null) "Não achei nenhuma cliente com esse nome nos pedidos."
            else {
                val uri = Uri.parse(Gestao.linkWhatsapp(c))
                activity.runOnUiThread {
                    if (WHATSAPPS_LOJA.none { iniciar(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }) iniciar(Intent(Intent.ACTION_VIEW, uri))
                }
                "Abri o WhatsApp de ${c.nome.split(" ").first()} com a mensagem pronta. Confira e toque em Enviar."
            }
        }
        "loja_datas" -> Gestao.textoDatas(Gestao.proximasDatas(Calendar.getInstance(), a.optInt("dias", 60).coerceIn(7, 365)), a.optInt("dias", 60).coerceIn(7, 365))
        "loja_promo_parados" -> daLoja { l ->
            val preparo = Gestao.promocaoNosParados(l.pedidos(), l.produtos(), Calendar.getInstance(), a.optInt("dias", 30), a.optInt("pct", 15))
            lojaPendente = preparo.alteracao
            preparo.texto
        }
        "loja_margem" -> daLoja { it.margem(a.optString("produto")) }
        "loja_margens" -> daLoja { it.margens() }
        "loja_lucro" -> daLoja { it.lucro(a.optString("periodo", "mes")) }
        "loja_previsao" -> daLoja { it.previsao(a.optString("produto")) }
        "loja_produto" -> daLoja { it.infoProduto(a.optString("produto")) }
        "loja_estoque_geral" -> daLoja { it.estoqueGeral() }
        "caixa_lancar" -> {
            val valor = a.optInt("valor")
            if (valor <= 0) "Qual o valor? Diga, por exemplo: paguei 120 de internet."
            else {
                val tipo = if (a.optString("tipo") == "entrada") "entrada" else "saida"
                var desc = a.optString("descricao").trim().ifBlank { if (tipo == "entrada") "Venda por fora" else "Despesa" }
                if (tipo == "entrada" && Regex("^(?:pix|dinheiro|cartao|cartão|credito|crédito|debito|débito)$", RegexOption.IGNORE_CASE).matches(desc)) desc = "Venda por fora ($desc)"
                Financas.textoLancado(Financas.lancar(activity, tipo, valor, desc))
            }
        }
        "caixa_apagar" -> Financas.apagarUltimo(activity)?.let {
            "Pronto, apaguei o último lançamento: ${if (it.entrada) "entrada" else "despesa"} de ${Loja.dinheiro(it.valorCentavos)}, ${it.descricao.replaceFirstChar { c -> c.lowercase() }}."
        } ?: "O livro caixa ainda está vazio."
        "caixa_fixa" -> {
            val valor = a.optInt("valor")
            if (valor <= 0) "Qual o valor da despesa fixa?" else Financas.textoFixa(Financas.salvarFixa(activity, a.optString("descricao"), valor, a.optInt("dia", 1)))
        }
        "caixa_remover_fixa" -> when (val n = Financas.removerFixa(activity, a.optString("trecho"))) {
            0 -> "Não achei essa despesa fixa. " + Financas.fixas(activity).let { f -> if (f.isEmpty()) "Não tem nenhuma cadastrada." else "As cadastradas são: " + f.joinToString(", ") { it.descricao } + "." }
            1 -> "Pronto, tirei essa despesa fixa."
            else -> "Pronto, tirei $n despesas fixas."
        }
        "caixa_resultado" -> comCaixa { pedidos, produtos ->
            val p = Financas.periodo(a.optString("periodo", "mes"), Calendar.getInstance())
            Financas.textoResultado(Financas.resultado(pedidos, produtos, Financas.lancamentos(activity), Financas.fixas(activity), p), p)
        }
        "caixa_despesas" -> {
            val p = Financas.periodo(a.optString("periodo", "mes"), Calendar.getInstance())
            Financas.textoDespesas(Financas.lancamentos(activity), Financas.fixas(activity), p)
        }
        "caixa_equilibrio" -> comCaixa { pedidos, produtos ->
            val agora = Calendar.getInstance()
            val hist = Financas.historico(pedidos, produtos, Financas.lancamentos(activity), Financas.fixas(activity), 4, agora)
            val anteriores = hist.dropLast(1).filter { it.second.receita > 0 || it.second.despesas > 0 }
            val variaveis = if (anteriores.isNotEmpty()) anteriores.map { it.second.despesas }.average().toLong()
            else (hist.last().second.despesas / Financas.fracaoDoMes(agora)).toLong()
            val comVenda = hist.filter { it.second.receita > 0 && it.second.itensSemCusto == 0 }.ifEmpty { hist.filter { it.second.receita > 0 } }
            val margem = if (comVenda.isEmpty()) margemDoCatalogo(produtos) else comVenda.map { it.second.margemContribuicao }.average()
            Financas.textoEquilibrio(Financas.fixas(activity), variaveis, margem)
        }
        "caixa_previsao" -> comCaixa { pedidos, produtos ->
            val agora = Calendar.getInstance()
            val hist = Financas.historico(pedidos, produtos, Financas.lancamentos(activity), Financas.fixas(activity), 6, agora)
            Financas.textoPrevisao(hist, Financas.fixas(activity).sumOf { it.valorCentavos.toLong() }, agora)
        }
        "financeiro_calculo" -> Financas.textoCalculo(
            a.optString("tipo"), a.optDouble("a", 0.0), a.optDouble("b", 0.0), a.optDouble("c", 0.0), a.optBoolean("taxa_anual", false),
        )
        "loja_alterar" -> if (!loja.conectada && a.optString("se_nao_achar") == "despesa" && a.has("valor")) {
            Financas.textoLancado(Financas.lancar(activity, "saida", a.optInt("valor"), a.optString("produto")))
        } else daLoja { l ->
            val valor = if (a.has("valor")) a.optInt("valor") else null
            val preparo = l.prepararAlteracao(a.optString("tipo"), a.optString("produto"), valor)
            lojaPendente = preparo.alteracao
            if (preparo.alteracao == null && a.optString("se_nao_achar") == "despesa" && valor != null && preparo.texto.startsWith("Não achei nenhum produto")) {
                Financas.textoLancado(Financas.lancar(activity, "saida", valor, a.optString("produto")))
            } else preparo.texto
        }
        "loja_entregue" -> daLoja { l ->
            val preparo = l.prepararEntregue(a.optInt("pedido"))
            lojaPendente = preparo.alteracao
            preparo.texto
        }
        "loja_confirmar" -> {
            val alt = lojaPendente
            lojaPendente = null
            if (alt == null) "Não tinha nenhuma alteração esperando confirmação." else daLoja { l ->
                val feito = l.aplicar(alt)
                // pedido mudou de etapa: abre o WhatsApp da cliente com o aviso pronto
                val aviso = Loja.linkAviso(alt)
                if (aviso != null) {
                    val uri = Uri.parse(aviso)
                    activity.runOnUiThread {
                        if (WHATSAPPS_LOJA.none { iniciar(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }) iniciar(Intent(Intent.ACTION_VIEW, uri))
                    }
                    "$feito Abri o WhatsApp da cliente com o aviso pronto, é só tocar em Enviar."
                } else feito
            }
        }
        "loja_status" -> daLoja { l ->
            val preparo = l.prepararStatus(a.optInt("pedido"), a.optString("status"), a.optString("rastreio").ifBlank { null })
            lojaPendente = preparo.alteracao
            preparo.texto
        }
        "loja_agendar" -> daLoja { l -> agendarRetirada(l, a.optInt("pedido")) }
        "distancia" -> distancia(a.optString("origem"), a.optString("destino"))
        "dicionario" -> Dicionario.consultar(a.optString("tipo", "significado"), a.optString("palavra"))
        "ortografia" -> Dicionario.qualCerta(a.optString("a"), a.optString("b"))
        "buscar_cep" -> Servicos.buscarCep(a.optString("logradouro"), a.optString("cidade"), a.optString("estado"))
        "consultar_cnpj" -> Servicos.cnpj(a.optString("cnpj"))
        "consultar_ddd" -> Servicos.ddd(a.optString("ddd"))
        "feriados" -> Servicos.proximosFeriados()
        "noticias" -> noticias(a.optString("tema"))
        "acompanhar_tema" -> acompanhar(a.optString("acao"), a.optString("tema"))
        "atualizar_noticias" -> atualizarNoticias()
        "buscar_na_internet" -> buscar(a.optString("pergunta"))
        "salvar_memoria" -> {
            val m = memoria
            when {
                m == null -> "Memória indisponível."
                m.lembrar(a.optString("fato"), "ia") -> "Memória salva."
                else -> "Isso já estava guardado."
            }
        }
        else -> "Não conheço a ferramenta $nome."
    }

    // ------------------------------------------------------------------ apps
    private fun abrirApp(nome: String): String {
        if (nome.isBlank()) return "Qual app devo abrir?"
        val pm = activity.packageManager
        val alvo = normalizar(APELIDOS[normalizar(nome)] ?: nome)
        val lancadores = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val apps = lancadores.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }

        val escolhido = apps.firstOrNull { normalizar(it.first) == alvo }
            ?: apps.firstOrNull { normalizar(it.first).startsWith(alvo) }
            ?: apps.firstOrNull { normalizar(it.first).contains(alvo) }
            ?: apps.firstOrNull { normalizar(it.first).length >= 3 && alvo.contains(normalizar(it.first)) }
            ?: apps.firstOrNull { it.second.contains(alvo.replace(" ", "")) }
            ?: return "Não encontrei um app chamado \"$nome\" instalado."

        val intent = pm.getLaunchIntentForPackage(escolhido.second)
            ?: return "Não consegui abrir ${escolhido.first}."
        activity.startActivity(intent)
        memoria?.registrarUso("app", escolhido.first)
        return "Abri o app ${escolhido.first}."
    }

    // ---------------------------------------------------------------- música
    private fun tocarMusica(busca: String, app: String): String {
        val consulta = busca.trim()
        if (consulta.isEmpty()) return "O que devo tocar?"
        memoria?.registrarUso("musica", consulta)
        val preferido = normalizar(app)
        val pacotes = when {
            preferido.contains("youtube music") -> listOf(YOUTUBE_MUSIC)
            preferido.contains("youtube") -> emptyList()
            preferido.contains("deezer") -> listOf(DEEZER)
            preferido.contains("spotify") -> listOf(SPOTIFY)
            else -> listOf(SPOTIFY, YOUTUBE_MUSIC, DEEZER)
        }

        for (pacote in pacotes) {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pacote)
                .putExtra(SearchManager.QUERY, consulta)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            if (iniciar(intent)) return "Pedi para o ${NOMES[pacote]} tocar \"$consulta\"."
        }

        if (SPOTIFY in pacotes) {
            val buscaSpotify = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(consulta)))
            if (iniciar(buscaSpotify)) return "Abri a busca por \"$consulta\" no Spotify; é só escolher a faixa."
        }

        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(consulta))
            )
        )
        return "Abri \"$consulta\" no YouTube."
    }

    private val PACOTES_MUSICA = mapOf(
        "spotify" to SPOTIFY, "deezer" to DEEZER, "youtube music" to YOUTUBE_MUSIC, "youtube" to YOUTUBE_MUSIC,
        "amazon music" to "com.amazon.mp3", "apple music" to "com.apple.android.music",
    )

    /**
     * Continuar a música de onde parou: aperta "play" (vai para o último app que tocou).
     * Se nada tocar em 1,5 s e o app foi dito, abre o app e aperta "play" de novo.
     */
    private fun retomarMusica(app: String): String {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun play() {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
        play()
        val prefs = activity.getSharedPreferences("config", Context.MODE_PRIVATE)
        val instalados = PLAYERS_ORDEM.filter { activity.packageManager.getLaunchIntentForPackage(it) != null }
        val pacote = Midia.escolherPlayer(PACOTES_MUSICA[ComandosLocais.normalizar(app).trim()], prefs.getString(PREF_ULTIMO_PLAYER, null), instalados)
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        principal.postDelayed({
            if (audio.isMusicActive) {
                pacote?.let { prefs.edit().putString(PREF_ULTIMO_PLAYER, it).apply() }
            } else if (pacote != null) {
                activity.packageManager.getLaunchIntentForPackage(pacote)?.let { abrir ->
                    try {
                        activity.startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                    }
                    principal.postDelayed({ if (!audio.isMusicActive) play() }, 3000)
                }
            }
        }, 1500)
        val nome = mapOf("spotify" to "Spotify", "deezer" to "Deezer", "youtube music" to "YouTube Music", "youtube" to "YouTube Music")[ComandosLocais.normalizar(app).trim()]
        return if (nome != null) "Continuando a música de onde parou no $nome." else "Continuando a música de onde parou."
    }

    val loja by lazy { Loja(activity) }

    /** Consulta a loja. Os dados ficam no celular (estas ferramentas não são oferecidas ao Gemini). */
    /** Pesquisa de preços na internet (definida pela tela, que tem a chave do Gemini). Roda fora da thread principal. */
    var pesquisadorDePrecos: ((String) -> Web.Resposta?)? = null

    private fun pesquisarPreco(produto: String): String {
        if (produto.isBlank()) return "De qual produto você quer saber o preço?"
        val link = Precos.linkShopping(produto)
        val r = try {
            pesquisadorDePrecos?.invoke(produto)
        } catch (e: Exception) {
            null
        }
        val ofertas = r?.let { Precos.lerOfertas(it.texto) }.orEmpty()
        if (ofertas.isEmpty()) {
            ultimasFontes = listOf(Web.Fonte("Comparar preços de $produto no Google Shopping", link))
            return if (pesquisadorDePrecos == null) "Para eu pesquisar os preços sozinha, configure a chave do Gemini. Deixei o link do Google Shopping para $produto aqui embaixo."
            else "Não consegui ler preços confiáveis de $produto agora. Deixei o link do Google Shopping aqui embaixo para você comparar."
        }
        ultimasFontes = r!!.fontes + Web.Fonte("Mais preços no Google Shopping", link)
        var t = Precos.textoOfertas(produto, ofertas)
        if (loja.conectada) {
            try {
                val p = Loja.acharProdutos(loja.produtos(), produto).firstOrNull()
                val preco = p?.precoCentavos
                if (p != null && preco != null) Precos.comparar(p.nome, preco, p.custoCentavos, ofertas)?.let { t += " $it" }
            } catch (e: Exception) {
            }
        }
        return t
    }

    private fun prazoDoFornecedor() = activity.getSharedPreferences("gestao", 0).getInt("prazo_fornecedor", 7)

    /** Pedidos e produtos da loja para as contas do caixa (vazios se a loja não estiver conectada). Roda fora da thread principal. */
    fun dadosDoCaixa(): Pair<List<Loja.Pedido>, List<Loja.Produto>> {
        if (!loja.conectada) return emptyList<Loja.Pedido>() to emptyList()
        return try {
            loja.pedidos() to loja.produtos()
        } catch (e: Exception) {
            throw Loja.ErroDaLoja("Não consegui buscar as vendas da loja agora. Confira a internet.")
        }
    }

    private fun comCaixa(conta: (List<Loja.Pedido>, List<Loja.Produto>) -> String): String = try {
        val (pedidos, produtos) = dadosDoCaixa()
        val aviso = if (!loja.conectada) " (Sem as vendas do site: a loja não está conectada.)" else ""
        conta(pedidos, produtos) + aviso
    } catch (e: Loja.ErroDaLoja) {
        e.message ?: "Não consegui buscar as vendas da loja."
    }

    /** Margem média do catálogo (preço − custo) ÷ preço, dos produtos com custo cadastrado. */
    private fun margemDoCatalogo(produtos: List<Loja.Produto>): Double {
        val m = produtos.mapNotNull { p ->
            val preco = p.precoCentavos ?: return@mapNotNull null
            val custo = p.custoCentavos ?: return@mapNotNull null
            if (preco <= 0) null else (preco - custo).toDouble() / preco
        }
        return if (m.isEmpty()) 0.0 else m.average()
    }

    private fun daLoja(consulta: (Loja) -> String): String {
        if (!loja.conectada) return "A loja ainda não está conectada. Abra os ajustes e toque em Conectar à loja."
        ultimasFontes = listOf(Web.Fonte("Painel da loja", Loja.SITE + "/admin"))
        return try {
            consulta(loja)
        } catch (e: Loja.ErroDaLoja) {
            e.message ?: "Não consegui consultar a loja."
        } catch (e: Exception) {
            "Não consegui falar com a loja agora. Confira a internet."
        }
    }

    /** "Bom dia": cumprimento, clima de hoje, lembretes do dia e como está a loja. Roda fora da thread principal. */
    private fun rotinaBomDia(): String {
        val apelido = memoria?.valor("apelido")
        val partes = mutableListOf("Bom dia${if (!apelido.isNullOrBlank()) ", $apelido" else ""}!")
        if (!memoria?.valor("cidade").isNullOrBlank()) {
            try {
                partes += clima("", "hoje")
            } catch (e: Exception) {
            }
        }
        val hoje = Lembretes.deHoje(activity)
        if (hoje.isNotEmpty()) {
            partes += "Lembretes de hoje: " + hoje.take(5).joinToString("; ") { "${Lembretes.quandoFalado(it.quando)}, ${it.texto.replaceFirstChar { c -> c.lowercase() }}" } + "."
        }
        if (loja.conectada) {
            try {
                partes += loja.resumoDaManha()
            } catch (e: Exception) {
            }
            Gestao.avisoDeData(Calendar.getInstance())?.let { partes += it }
        }
        if (partes.size == 1) partes += "Que seu dia seja ótimo. Em que posso ajudar?"
        return partes.joinToString(" ")
    }

    /** Mudança na loja esperando o "sim" do usuário (preparada por loja_alterar ou loja_entregue). */
    @Volatile
    var lojaPendente: Loja.Alteracao? = null

    /** Resposta de WhatsApp esperando o "sim". */
    data class RespostaPendente(val nome: String, val texto: String)

    @Volatile
    var respostaPendente: RespostaPendente? = null

    /** Ligação esperando o "sim" (a Vênus entendeu o nome e pergunta antes de discar). */
    data class LigacaoPendente(val nome: String, val numero: String)

    @Volatile
    var ligacaoPendente: LigacaoPendente? = null

    val temPendente get() = lojaPendente != null || respostaPendente != null || ligacaoPendente != null
    val pendenteQualquer: Any? get() = lojaPendente ?: respostaPendente ?: ligacaoPendente

    fun esquecerPendentes() {
        lojaPendente = null
        respostaPendente = null
        ligacaoPendente = null
    }

    /** Última conversa lida, para "responde que..." sem dizer o nome. */
    @Volatile
    private var ultimaConversa: String? = null

    private fun lerMensagens(de: String): String {
        if (!LEITOR_ATIVO) return MENSAGENS_INDISPONIVEL
        if (!LeitorDeMensagens.liberado(activity)) {
            return "Para eu ler suas mensagens, libere o acesso às notificações: nos ajustes da ${Marca.nome}, toque em Ler mensagens do WhatsApp."
        }
        val conversas = LeitorDeMensagens.conversas()
            ?: return "O acesso está liberado, mas o leitor de mensagens ainda está iniciando. Tente de novo em alguns segundos."
        ultimaConversa = (if (de.isNotBlank()) LeitorDeMensagens.achar(conversas, de) else conversas.firstOrNull())?.nome ?: ultimaConversa
        return LeitorDeMensagens.montarLeitura(conversas, de)
    }

    private fun prepararResposta(para: String, texto: String): String {
        respostaPendente = null
        if (!LEITOR_ATIVO) return MENSAGENS_INDISPONIVEL
        if (texto.isBlank()) return "O que eu respondo?"
        if (!LeitorDeMensagens.liberado(activity)) return lerMensagens("")
        val conversas = LeitorDeMensagens.conversas().orEmpty()
        val nome = if (para.isBlank()) ultimaConversa ?: conversas.firstOrNull()?.nome else LeitorDeMensagens.achar(conversas, para)?.nome
        if (nome == null) {
            return if (para.isBlank()) "Não tem mensagem nova para responder."
            else "Não achei mensagem nova de $para. Para mandar uma mensagem nova, diga: manda mensagem para $para que, e a mensagem."
        }
        respostaPendente = RespostaPendente(nome, texto.replaceFirstChar { it.uppercase() })
        return "Responder para $nome: ${respostaPendente!!.texto}? Diga sim para confirmar ou não para cancelar."
    }

    private fun confirmarResposta(): String {
        val r = respostaPendente ?: return "Não tinha nenhuma resposta esperando confirmação."
        respostaPendente = null
        ultimaConversa = r.nome
        if (LeitorDeMensagens.responder(activity, r.nome, r.texto)) return "Respondido para ${r.nome}."
        // a conversa saiu das notificações (foi aberta no celular): abre o WhatsApp com o texto pronto
        val r2 = CountDownLatch(1)
        var resultado = ""
        principal.post {
            resultado = enviarWhatsapp(r.nome, r.texto)
            r2.countDown()
        }
        r2.await(10, TimeUnit.SECONDS)
        return resultado.ifBlank { "Não consegui responder agora." }
    }

    /** Abre o WhatsApp do cliente com a mensagem de agendamento da retirada já escrita. */
    private fun agendarRetirada(loja: Loja, id: Int): String {
        val p = loja.pedido(id) ?: return "Não achei o pedido $id."
        if (p.entrega != "pickup") return "O pedido $id é de envio pelos Correios, não de retirada."
        if (p.status == "canceled") return "O pedido $id está cancelado."
        if (p.status == "delivered") return "O pedido $id já foi entregue."
        val link = Loja.linkAgendar(p) ?: return "O pedido $id não tem telefone do cliente cadastrado."
        val uri = Uri.parse(link)
        activity.runOnUiThread {
            if (WHATSAPPS_LOJA.none { iniciar(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }) iniciar(Intent(Intent.ACTION_VIEW, uri))
        }
        val nome = p.cliente.trim().split(" ").firstOrNull().orEmpty()
        val aviso = if (p.status == "awaiting_payment") " Atenção: esse pedido ainda está aguardando pagamento." else ""
        return "Abri o WhatsApp${if (nome.isNotBlank()) " de $nome" else ""} com a mensagem de agendamento do pedido $id. Confira e toque em Enviar.$aviso"
    }

    /** Abre o Maps com a busca perto de você (ou perto de casa/trabalho). */
    private fun buscarPerto(oque: String, referencia: String): String {
        val endereco = if (referencia.isNotBlank()) memoria?.valor("lugar:$referencia") else null
        val busca = if (endereco != null) "$oque perto de $endereco" else oque
        val local = Uri.encode(busca)
        val perto = if (endereco != null) "perto ${if (referencia == "casa") "da sua casa" else "do seu trabalho"}" else "perto de você"
        if (iniciar(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$local")))) return "Abri no Maps: $oque $perto. O mais próximo aparece primeiro."
        iniciar(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$local")))
        return "Abri a busca por $oque $perto no Google Maps."
    }

    private fun salvarLugar(lugar: String, endereco: String): String {
        val m = memoria ?: return "Memória indisponível."
        val nome = ComandosMemoria.nomeDoLugar(ComandosLocais.normalizar(lugar)) ?: return "Não entendi o nome do lugar."
        if (endereco.isBlank()) return "Faltou o endereço."
        m.definir("lugar:$nome", endereco.trim())
        return "Endereço de $nome salvo: $endereco."
    }

    /** Conta do Vetorial ID pela conversa (status da assinatura, conectar, dispositivos, sair). Roda fora da tela principal. */
    /** Pede ao Vortex pela nuvem (Vetorial ID) e espera a resposta dele por até 50 segundos. */
    private fun vortexPedir(acao: String, jogo: String): String {
        if (!VetorialId.logado(activity)) return "Entre com o seu Vetorial ID para eu falar com o Vortex."
        val ac = if (acao == "otimizar") "otimizar" else "abrir_jogo"
        if (ac == "abrir_jogo" && jogo.isBlank()) return "Qual jogo você quer abrir no Vortex?"
        val token = VetorialId.token(activity)
        val r = Nuvem.bruto(activity, "POST", "/vortex/pedidos", JSONObject().put("comando", ac).put("argumento", jogo), token)
        if (!r.ok) return when (r.json.optString("codigo")) {
            "vortex_fechado" -> "O Vortex não está aberto no seu aparelho agora. Abra o Vortex e me peça de novo."
            "vortex_nao_liberado" -> "O Vortex não está liberado na sua conta. Veja os pacotes em Conta."
            else -> "Não consegui falar com o Vortex: ${r.erro()}"
        }
        val id = r.json.optString("id")
        val fim = System.currentTimeMillis() + 50_000
        while (System.currentTimeMillis() < fim) {
            Thread.sleep(2_000)
            val s = Nuvem.bruto(activity, "GET", "/vortex/pedidos/$id", null, token)
            if (!s.ok) return "Não consegui acompanhar o pedido ao Vortex: ${s.erro()}"
            when (s.json.optString("status")) {
                "concluido" -> return "Vortex: " + s.json.optString("resumo").ifBlank { "pronto." }
                "falhou" -> return "O Vortex não conseguiu: " + s.json.optString("resumo").ifBlank { "erro desconhecido." }
                "expirado" -> return "O Vortex não respondeu a tempo."
            }
        }
        return "Pedi ao Vortex, mas ele ainda não respondeu. Veja a tela dele."
    }

    /** Pede a ação ao Vindex pela nuvem (Vetorial ID) e espera a resposta dele por até 50 segundos. */
    private fun vindexPedir(acao: String): String {
        if (!VetorialId.logado(activity)) return "Entre com o seu Vetorial ID para eu falar com o Vindex."
        val ac = if (acao == "diagnostico") "diagnostico" else "otimizar"
        val token = VetorialId.token(activity)
        val r = Nuvem.bruto(activity, "POST", "/vindex/pedidos", JSONObject().put("comando", ac), token)
        if (!r.ok) return if (r.json.optString("codigo") == "vindex_fechado") "O Vindex não está aberto no seu aparelho agora. Abra o Vindex e me peça de novo." else "Não consegui falar com o Vindex: ${r.erro()}"
        val id = r.json.optString("id")
        val fim = System.currentTimeMillis() + 50_000
        while (System.currentTimeMillis() < fim) {
            Thread.sleep(2_000)
            val s = Nuvem.bruto(activity, "GET", "/vindex/pedidos/$id", null, token)
            if (!s.ok) return "Não consegui acompanhar o pedido ao Vindex: ${s.erro()}"
            when (s.json.optString("status")) {
                "concluido" -> return "Vindex: " + s.json.optString("resumo").ifBlank { "pronto." }
                "falhou" -> return "O Vindex não conseguiu: " + s.json.optString("resumo").ifBlank { "erro desconhecido." }
                "expirado" -> return "O Vindex não respondeu a tempo."
            }
        }
        return "Pedi ao Vindex, mas ele ainda não respondeu. Veja a tela dele."
    }

    private fun contaVetorial(acao: String): String {
        val a = activity as? MainActivity ?: return "Abra o app da Vênus para mexer na conta."
        fun naTela(f: MainActivity.() -> Unit) = a.runOnUiThread { if (!a.isFinishing) a.f() }
        if (!VetorialId.logado(a)) {
            if (acao == "sair") return "Você não está conectada em nenhuma conta neste aparelho."
            naTela { abrirConta() }
            return if (acao == "conectar") "Abrindo a tela de login do Vetorial ID." else "Você ainda não entrou no Vetorial ID. Abrindo o login para você."
        }
        return when (acao) {
            "status" -> {
                val r = VetorialId.licenca(a, false)
                if (!r.ok) r.erro() else "Conta ${VetorialId.email(a)}.\n" + textoDaAssinatura(r.json) +
                    if (r.json.optBoolean("acesso") && (if (r.json.optBoolean("emTeste")) r.json.optInt("diasDeTesteRestantes") else r.json.optInt("diasPagosRestantes")) <= 3) "\nEstá acabando: diga \"renovar\" ou abra Menu › Vetorial ID › Licença." else ""
            }
            "conectar" -> "Para conectar o novo aparelho, abra a Vênus nele (ou o endereço ${Servidor.ENDERECO}/id no navegador) e entre com ${VetorialId.email(a)} e a sua senha. São até 3 aparelhos vinculados e 2 usando ao mesmo tempo."
            "dispositivos" -> { naTela { abrirDispositivos() }; "Abrindo os dispositivos da sua conta." }
            else -> {
                naTela {
                    CaixaBuilder(this).setTitle("Sair da conta?").setMessage("Você vai sair do Vetorial ID só neste aparelho (${VetorialId.email(this)}).")
                        .setPositiveButton("Sair") { _, _ -> emFundo({ VetorialId.sair(this) }) { aviso("Você saiu do Vetorial ID neste aparelho."); gaveta?.atualizarPerfil() } }
                        .setNegativeButton("Cancelar", null).show()
                }
                "Confirme na tela para eu sair da sua conta neste aparelho."
            }
        }
    }

    private fun controlarMidia(acao: String, app: String = "", nivel: String = ""): String {
        if (acao == "volume_nivel") {
            val pct = nivel.toIntOrNull()?.takeIf { it in 0..100 } ?: return "Qual volume, de 0 a 100?"
            val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Midia.indiceDeVolume(pct, max), AudioManager.FLAG_SHOW_UI)
            return "Volume em $pct%."
        }
        if (acao in setOf("volume_maximo", "volume_minimo", "volume_metade", "parar", "avancar", "voltar")) {
            return Midia.executar(activity, acao)
        }
        if (acao == "tocar" || (acao == "tocar_pausar" && app.isNotBlank())) return retomarMusica(app)
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun tecla(codigo: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, codigo))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, codigo))
        }
        fun volume(direcao: Int) =
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direcao, AudioManager.FLAG_SHOW_UI)

        when (acao) {
            "tocar_pausar" -> tecla(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "tocar" -> tecla(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pausar" -> tecla(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "proxima" -> tecla(KeyEvent.KEYCODE_MEDIA_NEXT)
            "anterior" -> tecla(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "aumentar_volume" -> repeat(2) { volume(AudioManager.ADJUST_RAISE) }
            "diminuir_volume" -> repeat(2) { volume(AudioManager.ADJUST_LOWER) }
            "mudo" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            else -> return "Ação de mídia desconhecida: $acao."
        }
        return NOMES_MIDIA[acao] ?: "Feito."
    }

    // ------------------------------------------------------- mensagens/ligação
    private fun enviarWhatsapp(contato: String, mensagem: String): String {
        if (contato.isBlank()) {
            val compartilhar = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, mensagem)
            for (pacote in WHATSAPPS) {
                if (iniciar(Intent(compartilhar).setPackage(pacote))) {
                    return "Abri o WhatsApp com a mensagem: escolha o contato e toque em Enviar."
                }
            }
            return "O WhatsApp não está instalado."
        }

        val achado = resolverContato(contato)
        val numero = achado.numero ?: return achado.aviso
        val internacional = numeroInternacional(numero)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$internacional&text=" + Uri.encode(mensagem))
        for (pacote in WHATSAPPS) {
            if (iniciar(Intent(Intent.ACTION_VIEW, uri).setPackage(pacote))) {
                registrarContato(achado.nome)
                return "Mensagem para ${achado.nome} pronta no WhatsApp. Toque em Enviar."
            }
        }
        return "O WhatsApp não está instalado."
    }

    private fun enviarSms(contato: String, mensagem: String): String {
        val achado = resolverContato(contato)
        val numero = achado.numero ?: return achado.aviso
        activity.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numero)))
                .putExtra("sms_body", mensagem)
        )
        registrarContato(achado.nome)
        return "SMS para ${achado.nome} pronto. Toque em Enviar."
    }

    /** Entende para quem ligar e pergunta antes: só disca depois do "sim". */
    private fun ligar(contato: String): String {
        val achado = resolverContato(contato)
        val numero = achado.numero ?: return achado.aviso
        lojaPendente = null
        respostaPendente = null
        ligacaoPendente = LigacaoPendente(achado.nome, numero)
        return "Entendi que você quer ligar para ${achado.nome}. Quer ligar? Diga sim para confirmar ou não para cancelar."
    }

    /** O "sim": liga direto (com a permissão de ligação) ou, sem ela, abre o discador com o número. */
    private fun confirmarLigacao(): String {
        val l = ligacaoPendente ?: return "Não tinha nenhuma ligação esperando confirmação."
        ligacaoPendente = null
        val uri = Uri.parse("tel:" + Uri.encode(l.numero))
        registrarContato(l.nome)
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread {
                try { activity.startActivity(Intent(Intent.ACTION_CALL, uri)) }
                catch (e: Exception) { activity.startActivity(Intent(Intent.ACTION_DIAL, uri)) }
            }
            return "Ligando para ${l.nome}."
        }
        activity.runOnUiThread {
            activity.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), PEDIDO_LIGACAO)
            activity.startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
        return "Abri o discador com o número de ${l.nome}. Para eu ligar direto da próxima vez, permita \"Fazer e gerenciar ligações\"."
    }

    private class Contato(val nome: String, val numero: String?, val aviso: String)

    private fun resolverContato(contato: String): Contato {
        val digitos = contato.filter { it.isDigit() }
        if (digitos.length >= 8 && contato.count { it.isLetter() } == 0) {
            return Contato(contato, digitos, "")
        }
        if (contato.isBlank()) return Contato("", null, "Para quem? Preciso do nome ou do número.")

        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread { activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), PEDIDO_CONTATOS) }
            return Contato(
                contato, null,
                "Preciso de permissão para ler seus contatos: toque em Permitir e repita o pedido. Se o aviso não aparecer, vá em Ajustes > Sistema > Contatos e ligações."
            )
        }

        val procurado = normalizar(contato)
        val encontrados = linkedMapOf<String, String>()
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val nome = cursor.getString(0) ?: continue
                val numero = cursor.getString(1) ?: continue
                if (normalizar(nome).contains(procurado) && nome !in encontrados) {
                    encontrados[nome] = numero
                }
            }
        }

        if (encontrados.isEmpty()) return Contato(contato, null, "Não encontrei \"$contato\" nos contatos.")
        val exato = encontrados.entries.firstOrNull { normalizar(it.key) == procurado }
        if (exato != null) return Contato(exato.key, exato.value, "")
        if (encontrados.size == 1) {
            val unico = encontrados.entries.first()
            return Contato(unico.key, unico.value, "")
        }
        val nomes = encontrados.keys.take(5).joinToString(", ")
        return Contato(contato, null, "Achei mais de um contato: $nomes. Diga o nome completo.")
    }

    private fun numeroInternacional(numero: String): String {
        var d = numero.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2) else if (d.startsWith("0")) d = d.drop(1)
        if (d.length == 10 || d.length == 11) d = "55$d"
        return d
    }

    // ----------------------------------------------------------- utilidades
    private fun lanterna(ligar: Boolean): String {
        val cameras = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este celular não tem lanterna."
        cameras.setTorchMode(id, ligar)
        return if (ligar) "Lanterna ligada." else "Lanterna desligada."
    }

    private fun alarme(hora: Int, minuto: Int, descricao: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hora)
            .putExtra(AlarmClock.EXTRA_MINUTES, minuto)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (descricao.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, descricao)
        activity.startActivity(intent)
        return "Alarme criado para %02d:%02d.".format(hora, minuto)
    }

    private fun timer(segundos: Int, descricao: String): String {
        if (segundos <= 0) return "Quanto tempo deve ter o timer?"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, segundos)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (descricao.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, descricao)
        activity.startActivity(intent)
        return "Timer de $segundos segundos iniciado."
    }

    private fun abrirSite(endereco: String): String {
        var url = endereco.trim()
        if (url.isEmpty()) return "Qual site?"
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return "Abri $url."
    }

    private fun pesquisar(termo: String): String {
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(termo)))
        )
        return "Pesquisa por \"$termo\" aberta."
    }

    /**
     * "casa", "trabalho", "academia", "casa da mãe"... viram o endereço salvo.
     * Devolve (endereço para o mapa, nome para falar) ou null se for um lugar pessoal ainda sem endereço.
     */
    private fun resolverLugar(destino: String): Pair<String, String>? {
        val n = ComandosLocais.normalizar(destino).trim()
        val pessoal = Regex("\\b(?:minha|meu|nossa|nosso)\\b").containsMatchIn(n) ||
            n in setOf("casa", "trabalho", "servico", "emprego", "firma", "escritorio", "lar")
        val nome = ComandosMemoria.nomeDoLugar(n) ?: return destino to destino
        val salvo = memoria?.valor("lugar:$nome")
        if (salvo != null) return salvo to (if (nome == "casa") "sua casa" else if (nome == "trabalho") "seu trabalho" else nome)
        return if (pessoal || nome == "casa" || nome == "trabalho") null else destino to destino
    }

    private fun mapa(destino: String, navegar: Boolean, modo: String = "", app: String = "", evitar: String = ""): String {
        val (endereco, nomeFalado) = resolverLugar(destino) ?: run {
            val nome = ComandosMemoria.nomeDoLugar(ComandosLocais.normalizar(destino)) ?: destino
            val artigo = if (nome == "casa") "da sua casa" else "de $nome"
            return "Ainda não sei o endereço $artigo. Me diga, por exemplo: minha $nome fica na Rua tal, número tal. " +
                "Ou, quando estiver lá: aqui é minha $nome."
        }
        val local = Uri.encode(endereco)
        if (navegar && app == "waze" && iniciar(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$local&navigate=yes")))) {
            return "Abri o Waze com a rota até $nomeFalado."
        }
        if (navegar && modo == "transporte") {
            val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$local&travelmode=transit")
            if (iniciar(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")) || iniciar(Intent(Intent.ACTION_VIEW, uri))) {
                return "Abri a rota de transporte público até $nomeFalado."
            }
        }
        val letraModo = when (modo) {
            "a_pe" -> "w"
            "bicicleta" -> "b"
            "moto" -> "l"
            else -> "d"
        }
        val semPedagio = if (evitar == "pedagio") "&avoid=t" else ""
        if (navegar && iniciar(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$local&mode=$letraModo$semPedagio")))) {
            val jeito = when (modo) {
                "a_pe" -> " a pé"
                "bicicleta" -> " de bicicleta"
                "moto" -> " de moto"
                else -> ""
            }
            return "Rota$jeito até $nomeFalado iniciada no Maps" + (if (semPedagio.isNotEmpty()) ", evitando pedágios." else ".")
        }
        if (iniciar(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$local")))) {
            return "Mostrei $destino no mapa."
        }
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$local"))
        )
        return "Abri $destino no Google Maps pelo navegador."
    }

    private fun configuracao(tipo: String): String {
        val paineis = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val acao = when (tipo) {
            "wifi" -> if (paineis) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (paineis) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "volume" -> if (paineis) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "tela" -> Settings.ACTION_DISPLAY_SETTINGS
            "bateria" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "localizacao" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "aplicativos" -> Settings.ACTION_APPLICATION_SETTINGS
            "nao_perturbe" -> "android.settings.ZEN_MODE_SETTINGS"
            else -> Settings.ACTION_SETTINGS
        }
        if (!iniciar(Intent(acao))) activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        return "Abri as configurações de ${NOMES_CONFIG[tipo] ?: tipo}. É só tocar para mudar."
    }

    private fun bateria(): String {
        val gerente = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val nivel = gerente.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val estado = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val carregando = estado == BatteryManager.BATTERY_STATUS_CHARGING || estado == BatteryManager.BATTERY_STATUS_FULL
        if (nivel <= 0) return "Não consegui ler a bateria agora."
        val comentario = when {
            carregando -> "e carregando"
            nivel <= 15 -> "Melhor colocar para carregar"
            else -> ""
        }
        return when {
            carregando -> "A bateria está em $nivel por cento $comentario."
            comentario.isNotEmpty() -> "A bateria está em $nivel por cento. $comentario."
            else -> "A bateria está em $nivel por cento."
        }
    }

    private fun registrarContato(nome: String) {
        if (nome.any { it.isLetter() }) memoria?.registrarUso("contato", nome)
    }

    // ------------------------------------------------------ internet
    private fun clima(cidade: String, dia: String): String {
        val alvo = cidade.ifBlank { memoria?.valor("cidade").orEmpty() }
        val resposta = if (alvo.isNotBlank()) {
            val lugar = Web.procurarCidade(alvo) ?: return "Não encontrei a cidade $alvo."
            Web.previsao(lugar.latitude, lugar.longitude, lugar.nome, dia)
        } else {
            val local = ultimaLocalizacao() ?: return pedirLocalizacao()
            Web.previsao(local.latitude, local.longitude, "", dia)
        }
        ultimasFontes = resposta.fontes
        return resposta.texto
    }

    private fun distancia(origem: String, destino: String): String {
        val b = Web.procurarCidade(destino) ?: return "Não encontrei o lugar $destino."
        val nomeB = b.nome
        val (lat, lon, nomeA) = if (origem.isBlank()) {
            val local = ultimaLocalizacao()
            val salva = memoria?.valor("cidade")
            when {
                local != null -> Triple(local.latitude, local.longitude, "")
                !salva.isNullOrBlank() -> Web.procurarCidade(salva)?.let { Triple(it.latitude, it.longitude, it.nome) }
                    ?: return pedirLocalizacao("calcular a distância daí")
                else -> return pedirLocalizacao("calcular a distância daí")
            }
        } else {
            val a = Web.procurarCidade(origem) ?: return "Não encontrei o lugar $origem."
            Triple(a.latitude, a.longitude, a.nome)
        }
        ultimasFontes = listOf(Web.Fonte("Open-Meteo (localização das cidades)", "https://open-meteo.com"))
        return Calculadora.fraseDistancia(nomeA, nomeB, Calculadora.distanciaKm(lat, lon, b.latitude, b.longitude))
    }

    /** "Aqui é minha casa": guarda a localização atual com um nome. */
    private fun salvarLugarAqui(lugar: String): String {
        val m = memoria ?: return "Memória indisponível."
        val local = ultimaLocalizacao() ?: return pedirLocalizacao("salvar este lugar")
        m.definir("lugar:$lugar", "%.6f,%.6f".format(java.util.Locale.US, local.latitude, local.longitude))
        val falado = if (lugar == "casa") "sua casa" else if (lugar == "trabalho") "seu trabalho" else lugar
        return "Guardei este lugar como $falado. Agora é só dizer: me leva para ${if (lugar == "casa") "casa" else lugar}."
    }

    private fun ultimaLocalizacao(): Location? {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val gerente = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> try { gerente.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    }

    private fun pedirLocalizacao(para: String = "ver o clima aí"): String {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread {
                activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), PEDIDO_LOCALIZACAO)
            }
            return "Para $para, permita a localização e pergunte de novo. " +
                "Ou me diga: minha cidade é, e o nome da cidade."
        }
        return "Não consegui achar sua localização agora. Me diga: minha cidade é, e o nome da cidade."
    }

    private fun atualizarNoticias(): String {
        val c = conhecimento ?: return "Memória indisponível."
        val novas = c.atualizar()
        return "Pronto, atualizei as novidades: $novas novas. Tenho ${c.totalDeNovidades()} guardadas."
    }

    private fun noticias(tema: String): String {
        val t = tema.ifBlank { "Brasil" }
        conhecimento?.buscar(t)?.takeIf { it.isNotEmpty() }?.let {
            val r = Pesquisa.formatarNoticias(it, "Pelas novidades que eu acompanho:")
            ultimasFontes = r.fontes
            return r.texto
        }
        val lista = Pesquisa.noticias("$t when:30d")
        if (lista.isEmpty()) return "Não encontrei notícias recentes sobre $t."
        conhecimento?.guardar(t, lista)
        val r = Pesquisa.formatarNoticias(lista, "Encontrei estas notícias recentes sobre $t:")
        ultimasFontes = r.fontes
        return r.texto
    }

    private fun acompanhar(acao: String, tema: String): String {
        val c = conhecimento ?: return "Memória indisponível."
        return when (acao) {
            "listar" -> "Eu acompanho: " + c.temas().joinToString(", ") + "."
            "parar" -> if (c.parar(tema) > 0) "Certo, parei de acompanhar $tema." else "Eu não estava acompanhando $tema."
            else -> {
                c.seguir(tema)
                val novas = try {
                    c.guardar(tema, Pesquisa.noticias("$tema when:7d", 15))
                } catch (e: Exception) {
                    0
                }
                "Combinado, vou acompanhar as novidades sobre $tema" +
                    if (novas > 0) " e já guardei $novas." else "."
            }
        }
    }

    private fun wikipedia(assunto: String): String {
        if (assunto.isBlank()) return "Sobre o que você quer saber?"
        val r = Web.wikipedia(assunto) ?: return "Não encontrei nada sobre $assunto na Wikipédia."
        ultimasFontes = r.fontes
        return r.texto
    }

    private fun buscar(pergunta: String): String {
        if (pergunta.isBlank()) return "O que devo pesquisar?"
        val r = buscador?.invoke(pergunta)
            ?: return "A busca na internet não está disponível (a chave do Gemini precisa de faturamento ativado). " +
                "Responda com o que você já sabe e avise que pode estar desatualizado."
        ultimasFontes = r.fontes
        return r.texto
    }

    private fun iniciar(intent: Intent): Boolean {
        // Celular bloqueado (Vênus aberta por cima da tela de bloqueio): pede a senha/digital e só então abre.
        val bloqueio = activity.getSystemService(android.app.KeyguardManager::class.java)
        if (bloqueio?.isKeyguardLocked == true && intent.resolveActivity(activity.packageManager) != null) {
            bloqueio.requestDismissKeyguard(activity, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                    }
                }
            })
            return true
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    companion object {
        /**
         * Ler mensagens precisa do acesso às notificações. No Brasil, o Android bloqueia a instalação de apps
         * de fora da Play Store que pedem esse acesso (proteção contra golpes), então o leitor fica desligado.
         */
        const val LEITOR_ATIVO = false
        const val MENSAGENS_INDISPONIVEL =
            "Ler e responder mensagens do WhatsApp não funciona nesta versão: o Android bloqueia a instalação de apps de fora da Play Store " +
                "que leem notificações. Para mandar uma mensagem nova, diga: manda mensagem para a Natália que, e a mensagem."
        const val PEDIDO_CONTATOS = 20
        const val PEDIDO_LIGACAO = 22
        const val PEDIDO_LOCALIZACAO = 21
        val USAM_REDE = setOf(
            "sobre_mim", "previsao_do_tempo", "resumo_wikipedia", "buscar_na_internet", "cotacao_moeda", "noticias",
            "acompanhar_tema", "atualizar_noticias", "traduzir", "consultar_cep", "consultar_cnpj", "consultar_ddd", "feriados",
            "buscar_cep", "dicionario", "ortografia", "distancia",
            "loja_resumo", "loja_pedidos", "loja_acabando", "loja_estoque",
            "rotina_bom_dia", "responder_mensagem", "confirmar_resposta", "fazer_ligacao", "confirmar_ligacao",
            "loja_retiradas", "loja_estoque_geral", "loja_alterar", "loja_status", "loja_reposicao", "loja_mais_vendidos", "loja_comparar", "loja_parados", "loja_margem", "loja_margens", "loja_lucro", "loja_previsao", "loja_produto", "loja_entregue", "loja_confirmar", "loja_agendar",
            "caixa_resultado", "caixa_equilibrio", "caixa_previsao",
            "pesquisar_preco", "loja_curva_abc", "loja_compras", "loja_recompra", "loja_chamar_cliente", "loja_promo_parados",
            "instagram_resumo", "robo_resumo", "robo_ligar", "vindex", "vortex",
        )

        private const val SPOTIFY = "com.spotify.music"
        private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        private const val DEEZER = "deezer.android.app"
        private val WHATSAPPS = listOf("com.whatsapp", "com.whatsapp.w4b")
        /** Mensagens da loja para clientes saem pelo WhatsApp Business (número da loja); sem ele, pelo comum. */
        private val WHATSAPPS_LOJA = listOf("com.whatsapp.w4b", "com.whatsapp")
        private val NOMES_MIDIA = mapOf(
            "tocar_pausar" to "Pronto.", "tocar" to "Tocando.", "pausar" to "Pausado.",
            "proxima" to "Próxima música.", "anterior" to "Música anterior.",
            "aumentar_volume" to "Volume aumentado.", "diminuir_volume" to "Volume diminuído.",
            "mudo" to "Som no mudo (repita para tirar).",
        )
        private val PLAYERS_ORDEM = listOf(DEEZER, SPOTIFY, YOUTUBE_MUSIC)
        private const val PREF_ULTIMO_PLAYER = "ultimo_player"
        private val NOMES_CONFIG = mapOf(
            "wifi" to "Wi-Fi", "internet" to "internet", "nao_perturbe" to "Não perturbe",
            "localizacao" to "localização", "aplicativos" to "aplicativos", "geral" to "do celular",
        )
        private val NOMES = mapOf(SPOTIFY to "Spotify", YOUTUBE_MUSIC to "YouTube Music", DEEZER to "Deezer")

        private val APELIDOS = mapOf(
            "zap" to "whatsapp",
            "zapzap" to "whatsapp",
            "wpp" to "whatsapp",
            "insta" to "instagram",
            "face" to "facebook",
            "yt" to "youtube",
            "relogio" to "relogio",
            "configuracao" to "configuracoes",
            "ajustes" to "configuracoes",
        )
    }
}
