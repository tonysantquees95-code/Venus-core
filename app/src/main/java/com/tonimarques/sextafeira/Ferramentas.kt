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
                    "aumentar_volume", "diminuir_volume", "mudo"
                )
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
                "previsao_do_tempo", "Temperatura atual e previsão do tempo de hoje ou amanhã.", emptyList(),
                "cidade" to texto("Cidade; vazio para a cidade do usuário."),
                "dia" to opcoes("Qual dia.", "hoje", "amanha")
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
        executarAqui(nome, argumentos)
    } catch (e: ActivityNotFoundException) {
        "Não encontrei nenhum app no celular capaz de fazer isso."
    } catch (e: SecurityException) {
        "O Android não deixou fazer isso: ${e.message}"
    } catch (e: Exception) {
        "Deu erro ao executar $nome: ${e.message}"
    }

    private fun executarAqui(nome: String, a: JSONObject): String = when (nome) {
        "abrir_app" -> abrirApp(a.optString("nome"))
        "tocar_musica" -> tocarMusica(a.optString("busca"), a.optString("app"))
        "controlar_midia" -> controlarMidia(a.optString("acao"))
        "enviar_whatsapp" -> enviarWhatsapp(a.optString("contato"), a.optString("mensagem"))
        "enviar_sms" -> enviarSms(a.optString("contato"), a.optString("mensagem"))
        "fazer_ligacao" -> ligar(a.optString("contato"))
        "lanterna" -> lanterna(a.optBoolean("ligar", true))
        "criar_alarme" -> alarme(a.optInt("hora"), a.optInt("minuto"), a.optString("descricao"))
        "criar_timer" -> timer(a.optInt("segundos"), a.optString("descricao"))
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

    private fun controlarMidia(acao: String): String {
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

    private fun ligar(contato: String): String {
        val achado = resolverContato(contato)
        val numero = achado.numero ?: return achado.aviso
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero))))
        registrarContato(achado.nome)
        return "Discador aberto com o número de ${achado.nome}. Toque para ligar."
    }

    private class Contato(val nome: String, val numero: String?, val aviso: String)

    private fun resolverContato(contato: String): Contato {
        val digitos = contato.filter { it.isDigit() }
        if (digitos.length >= 8 && contato.count { it.isLetter() } == 0) {
            return Contato(contato, digitos, "")
        }
        if (contato.isBlank()) return Contato("", null, "Para quem? Preciso do nome ou do número.")

        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), PEDIDO_CONTATOS)
            return Contato(
                contato, null,
                "Preciso de permissão para ler seus contatos: toque em Permitir e repita o pedido."
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

    private fun iniciar(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    companion object {
        const val PEDIDO_CONTATOS = 20
        const val PEDIDO_LOCALIZACAO = 21
        val USAM_REDE = setOf(
            "previsao_do_tempo", "resumo_wikipedia", "buscar_na_internet", "cotacao_moeda", "noticias",
            "acompanhar_tema", "atualizar_noticias", "traduzir", "consultar_cep", "consultar_cnpj", "consultar_ddd", "feriados",
            "buscar_cep", "dicionario", "ortografia", "distancia",
        )

        private const val SPOTIFY = "com.spotify.music"
        private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        private const val DEEZER = "deezer.android.app"
        private val WHATSAPPS = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val NOMES_MIDIA = mapOf(
            "tocar_pausar" to "Pronto.", "tocar" to "Tocando.", "pausar" to "Pausado.",
            "proxima" to "Próxima música.", "anterior" to "Música anterior.",
            "aumentar_volume" to "Volume aumentado.", "diminuir_volume" to "Volume diminuído.",
            "mudo" to "Som no mudo (repita para tirar).",
        )
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
