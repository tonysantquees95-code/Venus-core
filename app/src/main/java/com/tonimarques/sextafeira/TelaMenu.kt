package com.tonimarques.sextafeira

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_AJUSTE_VOZ
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_ASSISTENTE
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_CHAVE
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_CONTINUA
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_DIAGNOSTICO
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_FALAR
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_FUNDO
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_GESTO
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_HISTORICO
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_INDEPENDENTE
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_INTERROMPER
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_MEMORIAS
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_MODELO
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_MUSICA
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_NOVA
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_TELA_APAGADA
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_VIGILIA
import com.tonimarques.sextafeira.MainActivity.Companion.MENU_VOZ
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_MICROFONE
import com.tonimarques.sextafeira.MainActivity.Companion.PEDIDO_NOTIFICACOES
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_CONTINUA
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_FALAR
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_FUNDO
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_INDEPENDENTE
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_INTERROMPER
import com.tonimarques.sextafeira.MainActivity.Companion.PREF_VIGILIA
import java.util.Calendar
import java.util.Locale

/*
 * Menu de ajustes: montar as seções, tratar cada opção, histórico, memórias, estudo e os liga/desliga.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

internal fun MainActivity.resumoDaMemoria(): String {
    val partes = mutableListOf<String>()
    memoria.valor("apelido")?.let { partes += "seu nome é $it" }
    memoria.valor("cidade")?.let { partes += "você mora em $it" }
    for (f in memoria.fatos()) {
        if (f.categoria.startsWith("favorito:")) {
            val tipo = f.categoria.removePrefix("favorito:").let { mapOf("musica" to "música", "serie" to "série")[it] ?: it }
            partes += "seu favorito em $tipo é ${f.texto}"
        }
    }
    for (f in memoria.fatos()) {
        if (f.categoria.startsWith("lugar:")) {
            val lugar = f.categoria.removePrefix("lugar:")
            val valor = if (Regex("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$").matches(f.texto)) "o lugar que você marcou" else f.texto
            partes += "${if (lugar == "casa") "sua casa" else if (lugar == "trabalho") "seu trabalho" else lugar} fica em $valor"
        }
    }
    val fatos = memoria.fatos().filter { it.categoria in setOf("fato", "gosto", "ia") }.takeLast(8).map { it.texto }
    partes += fatos
    if (partes.isEmpty()) return "Ainda não sei nada sobre você. Diga, por exemplo: lembra que eu gosto de rock."
    return "O que eu sei sobre você: " + partes.joinToString("; ") + "."
}

internal fun MainActivity.confirmarApagarMemorias() {
    responder("Tem certeza? Confirme na tela.")
    AlertDialog.Builder(this)
        .setTitle("Apagar todas as memórias?")
        .setMessage("A ${Marca.nome} vai esquecer seu nome, sua cidade, seus favoritos, o que aprendeu e o que você mais usa. O histórico de conversas continua.")
        .setPositiveButton("Apagar") { _, _ ->
            memoria.apagarMemorias()
            cerebro.apelido = "chefe"
            cerebro.esquecerAprendidas()
            responder("Pronto, memórias apagadas. Começando do zero.")
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.abrirMenu(@Suppress("UNUSED_PARAMETER") ancora: View) {
    if (ajustes?.isShowing == true) return
    fun item(titulo: String, descricao: () -> String, id: Int, ligado: (() -> Boolean)? = null) =
        TelaDeAjustes.Ajuste(titulo, descricao, ligado) { tratarMenu(id) }

    val n = Marca.nome
    val secoes = listOf(
        TelaDeAjustes.Secao(
            "Ativação por voz", listOf(
                item("Ouvir \"$n, ativar\" com o app aberto", { "Fala a frase e ela já começa a ouvir o seu pedido." },
                    MENU_VIGILIA) { vigiliaLigada() },
                item("Ouvir com o app fechado", {
                    "Diga \"$n, ativar\" de qualquer tela e o orbe aparece num pop-up, sem abrir o app inteiro."
                }, MENU_FUNDO) { prefs.getBoolean(PREF_FUNDO, false) },
                TelaDeAjustes.Ajuste("Voz offline para a ativação", { descricaoVozOffline() }) { tratarVozOffline() },
                item("Comandos de música sem chamar", {
                    "Com música tocando, diga só: pausar música, próxima música, música anterior, aumentar volume, diminuir volume. " +
                        "Funciona com qualquer app de música. Precisa da voz offline e da escuta ligada."
                }, MENU_MUSICA) { prefs.getBoolean(VigiliaService.PREF_MUSICA, true) },
                item("Torcer o pulso 2 vezes para chamar", {
                    "Gire o celular para um lado e para o outro, duas vezes, com a tela ligada: a $n abre ouvindo, mesmo com música alta. " +
                        "Desligue a \"Câmera rápida\" no app Moto para não abrir os dois."
                }, MENU_GESTO) { prefs.getBoolean(VigiliaService.PREF_GESTO, false) },
                item("Funcionar com a tela apagada", {
                    "Ouve \"$n, ativar\", os comandos de música e o gesto mesmo com a tela apagada ou bloqueada. " +
                        "Acende a tela e aparece por cima do bloqueio; para abrir apps ou mandar mensagem, pede a senha antes. Gasta mais bateria."
                }, MENU_TELA_APAGADA) { prefs.getBoolean(VigiliaService.PREF_TELA_APAGADA, false) },
                item("Abrir segurando o botão", {
                    "Define a $n como assistente padrão: segure o botão de início ou de ligar para chamar o orbe."
                }, MENU_ASSISTENTE),
            )
        ),
        TelaDeAjustes.Secao(
            "Voz da $n", listOf(
                TelaDeAjustes.Ajuste("Voz neural (Microsoft)", { VozNeural.resumo(this) }) { configurarVozNeural() },
                item("Escolher a voz", { "Vozes em português instaladas no celular (usadas quando a voz neural está desligada ou sem internet)." }, MENU_VOZ),
                item("Velocidade e tom", { "Deixe a fala mais rápida, mais lenta, mais grave ou mais aguda." }, MENU_AJUSTE_VOZ),
                item("Responder em voz alta", { "Além de escrever, ela fala as respostas." }, MENU_FALAR) {
                    prefs.getBoolean(PREF_FALAR, true)
                },
                item("Conversa contínua", { "Depois de responder, ela volta a ouvir sem você precisar tocar no orbe." },
                    MENU_CONTINUA) { prefs.getBoolean(PREF_CONTINUA, true) },
                item("Interromper enquanto ela fala", {
                    if (ModeloDeVoz.pronto(this)) "Diga \"$n\", \"chega\", \"espera\" ou \"silêncio\" durante a fala e ela se cala e passa a ouvir. Tocar no orbe também interrompe."
                    else "Precisa da voz offline (ajuste acima). Enquanto isso, toque no orbe para interromper."
                }, MENU_INTERROMPER) { prefs.getBoolean(PREF_INTERROMPER, true) },
            )
        ),
        TelaDeAjustes.Secao(
            "Loja ${Perfil.loja}", listOf(
                TelaDeAjustes.Ajuste("Licença", { Licenca.descricao(this) }) { pedirCodigoDeLiberacao(obrigatorio = false) },
                TelaDeAjustes.Ajuste("Licenças de teste (só o dono)", {
                    if (!Licenca.isenta(this)) "Só no celular do dono da ${Marca.nome}."
                    else if (Licenca.ehAdministrador(this)) "Gerar códigos de liberação para lojistas em teste."
                    else "Criar a chave que assina os códigos (fica só neste celular)."
                }) { if (Licenca.isenta(this)) administrarLicencas() else Toast.makeText(this, "Só no celular do dono.", Toast.LENGTH_SHORT).show() },
                TelaDeAjustes.Ajuste("Cadastro guiado", { "Passo a passo: você e a loja, com ou sem site, regras de venda e as chaves das IAs." }) { cadastroGuiado() },
                TelaDeAjustes.Ajuste("Primeiros passos", { "A lista do que configurar para a $n funcionar com a sua loja." }) { primeirosPassos() },
                TelaDeAjustes.Ajuste("Perfil do dono e da loja", { Perfil.resumo() + ". A $n usa esses dados nas respostas, nos avisos e no estudo." }) { editarPerfil() },
                TelaDeAjustes.Ajuste("Site, ramo e regras de venda", {
                    "${Loja.SITE.removePrefix("https://")} · ${Perfil.ramo} · Pix ${Perfil.pixPorcento}%. É o que a $n pode prometer às clientes."
                }) { editarDadosDaLoja() },
                TelaDeAjustes.Ajuste("Plataforma da loja", {
                    val t = Plataformas.TIPOS.firstOrNull { it.id == Plataformas.tipo(this) }?.nome ?: "Floot"
                    if (Plataformas.tipo(this) == "floot") "$t. Também funciona com Shopify, WooCommerce e Nuvemshop."
                    else "$t" + (if (ferramentas.loja.conectada) ", conectada." else ", falta a chave.")
                }) { escolherPlataforma() },
                TelaDeAjustes.Ajuste("Catálogo da loja", {
                    if (Plataformas.tipo(this) == "interno") "Produtos, fotos, estoque, vendas e o catálogo em PDF para mandar às clientes."
                    else "Catálogo em PDF com os produtos do site, para mandar no WhatsApp. Sem site? Escolha \"Sem site\" em Plataforma da loja."
                }) { abrirCatalogo() },
                TelaDeAjustes.Ajuste("Conectar à loja", {
                    ferramentas.loja.email?.let { "Conectada como $it. Pergunte: como foram as vendas hoje, tem pedido novo, o que está acabando. Toque para desconectar." }
                        ?: "Entre com o login de administrador do site para a $n consultar vendas, pedidos e estoque. A senha fica criptografada só no celular."
                }) { conectarLoja() },
                TelaDeAjustes.Ajuste("Avisos de pedidos e estoque", {
                    "A cada 15 minutos, mais ou menos, a $n olha a loja e avisa por notificação quando entra um pedido pago " +
                        "ou um produto fica com 2 unidades ou menos."
                }, { VigiaDaLoja.ligado(this) }) { alternarAvisosDaLoja() },
                TelaDeAjustes.Ajuste("Relatório da semana (segunda, 9h)", {
                    "Com os avisos ligados, toda segunda chega o resumo da semana: vendas, comparação, mais vendidos e o que vai acabar. " +
                        "Também avisa quando uma retirada fica 2 dias sem agendar."
                }, { VigiaDaLoja.semanalLigado(this) }) {
                    VigiaDaLoja.definirSemanal(this, !VigiaDaLoja.semanalLigado(this))
                    ajustes?.atualizar()
                },
                TelaDeAjustes.Ajuste("Resumo da loja às 21h", {
                    "Com os avisos ligados, toda noite chega uma notificação com as vendas do dia e o que fica para amanhã."
                }, { VigiaDaLoja.resumoLigado(this) }) {
                    VigiaDaLoja.definirResumo(this, !VigiaDaLoja.resumoLigado(this))
                    ajustes?.atualizar()
                },
            )
        ),
        TelaDeAjustes.Secao(
            "Inteligência", listOf(
                item("Modo independente", {
                    "Responde só com os próprios recursos (comandos, pesquisa e notícias), sem usar o Gemini."
                }, MENU_INDEPENDENTE) { modoIndependente() },
                item("Chave do Gemini", { if (chave().isBlank()) "Não configurada" else "Configurada" }, MENU_CHAVE),
                TelaDeAjustes.Ajuste("IA reserva (Groq ou Grok)", {
                    if (!SegundaIA.configurada(this)) "Não configurada. Com uma chave grátis da Groq, a $n continua respondendo quando o Gemini falhar ou ficar sem cota."
                    else "Usando ${SegundaIA.provedor(this).nome}." + (if (SegundaIA.ultimaVez > 0) " Última vez que ajudou: ${Diario.hora(SegundaIA.ultimaVez)}." else "")
                }) { pedirIAReserva() },
                TelaDeAjustes.Ajuste("Segunda reserva: Cloudflare", {
                    if (SegundaIA.chaveCloudflare(this).isBlank()) "Não configurada. Grátis todo dia, sem cartão: entra quando a Groq atingir o limite, antes do Gemini."
                    else "Configurada: entra quando a ${SegundaIA.provedor(this).nome.substringBefore(" (")} atingir o limite, antes do Gemini."
                }) { pedirCloudflare() },
                TelaDeAjustes.Ajuste("IA reserva como principal", {
                    if (!SegundaIA.configurada(this)) "Configure a IA reserva primeiro."
                    else if (SegundaIA.principal(this)) "Ligado: a ${SegundaIA.provedor(this).nome.substringBefore(" (")} responde primeiro (mais rápida) e o Gemini entra se ela falhar. PDF, áudio e busca no Google continuam no Gemini."
                    else "Desligado: o Gemini responde primeiro e a ${SegundaIA.provedor(this).nome.substringBefore(" (")} entra se ele falhar."
                }, { SegundaIA.principal(this) }) {
                    if (!SegundaIA.configurada(this)) pedirIAReserva()
                    else {
                        SegundaIA.definirPrincipal(this, !SegundaIA.principal(this))
                        ajustes?.atualizar()
                    }
                },
                TelaDeAjustes.Ajuste("O que eu aprendi", { "Veja por tipo tudo o que a $n guardou na memória e apague o que estiver errado." }) { mostrarAprendizado() },
                TelaDeAjustes.Ajuste("Estudo diário (memória vetorial)", {
                    val r = Estudo.ultimo(this)
                    "Todo dia, em segundo plano: ${Estudo.FRASES_POR_DIA} jeitos de pedir, ${Estudo.ENIGMAS_POR_DIA} charadas, " +
                        "${Estudo.CLIENTES_POR_DIA} respostas para clientes conferidas, até ${Estudo.REVISAO_MAXIMO} correções das suas conversas, " +
                        "${Estudo.VOZ_POR_DIA} erros de voz e digitação, ${Estudo.OBJECOES_POR_DIA} objeções de venda, " +
                        "até ${Estudo.LICOES_MAXIMO} lições dos próprios erros e ${Estudo.SABERES_POR_DIA} saberes de uma área por dia (hoje: ${Estudo.disciplinaDoDia()}); " +
                        "${Estudo.IDEIAS_POR_SEMANA} ideias de post por semana. Descarta o que já sabe e limpa o que nunca usou." +
                        (r?.let { " Último: ${it.data.split("-").reversed().take(2).joinToString("/")}, +${it.total} itens." } ?: "") +
                        " Pergunte: o que você estudou hoje?"
                }, { Estudo.ligado(this) }) {
                    Estudo.definirLigado(this, !Estudo.ligado(this))
                    ajustes?.atualizar()
                },
                item("Modelo do Gemini", { modelo() }, MENU_MODELO),
            )
        ),
        TelaDeAjustes.Secao(
            "Memória e conversas", listOf(
                item("O que a $n sabe sobre mim", { "Seus gostos, favoritos e o que ela aprendeu. Dá para apagar." }, MENU_MEMORIAS),
                item("Nova conversa", { "Abre uma conversa em branco; a atual fica guardada no histórico. Para voltar a um assunto, diga: lembra do que falamos sobre, e o assunto." }, MENU_NOVA),
            )
        ),
        TelaDeAjustes.Secao(
            "Sistema", listOf(
                TelaDeAjustes.Ajuste("Atualizações", {
                    "Versão ${versaoDoApp()} instalada. A ${Marca.nome} avisa sozinha quando sai uma nova; toque para procurar agora."
                }) { procurarAtualizacao(manual = true) },
                item("Diagnóstico de conexão", { "Testa a internet e o Gemini e mostra o que está funcionando." }, MENU_DIAGNOSTICO),
                TelaDeAjustes.Ajuste("Log de ajustes e erros", {
                    val abertos = Registro.log(this).size
                    "Tudo o que a ${Marca.nome} mudou (loja, Instagram, caixa, conexões) e os erros em aberto. Os erros são enviados ao GitHub " +
                        "para o Claude corrigir e saem do log sozinhos quando a versão com o conserto é instalada. " +
                        (if (abertos == 0) "Nenhum erro em aberto." else "$abertos ${if (abertos == 1) "erro em aberto" else "erros em aberto"}.")
                }) { mostrarLog() },
            )
        ),
    )
    ajustes = TelaDeAjustes(this, versaoDoApp(), secoes).also {
        it.setOnDismissListener { ajustes = null }
        it.show()
    }
}

internal fun MainActivity.versaoDoApp(): String = try {
    packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
} catch (e: Exception) {
    ""
}

internal fun MainActivity.tratarMenu(id: Int): Boolean {
    when (id) {
        MENU_VIGILIA -> {
            val ligado = !vigiliaLigada()
            prefs.edit().putBoolean(PREF_VIGILIA, ligado).apply()
            if (ligado) {
                if (temMicrofone()) agendarVigilia(300)
                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
            } else {
                pararVigilia()
            }
            mudarEstadoOcioso()
        }
        MENU_VOZ -> escolherVoz()
        MENU_HISTORICO -> mostrarHistorico()
        MENU_MEMORIAS -> mostrarMemorias()
        MENU_FUNDO -> {
            alternarEscutaDeFundo()
            if (prefs.getBoolean(PREF_FUNDO, false) && podeSobrepor()) oferecerVozOffline(forcar = true)
        }
        MENU_ASSISTENTE -> definirAssistentePadrao()
        MENU_MUSICA -> {
            val ligado = !prefs.getBoolean(VigiliaService.PREF_MUSICA, true)
            prefs.edit().putBoolean(VigiliaService.PREF_MUSICA, ligado).apply()
            if (ligado && !ModeloDeVoz.pronto(this)) oferecerVozOffline(forcar = true)
        }
        MENU_GESTO -> alternarGesto()
        MENU_TELA_APAGADA -> alternarTelaApagada()
        MENU_INDEPENDENTE -> {
            val ligado = !modoIndependente()
            prefs.edit().putBoolean(PREF_INDEPENDENTE, ligado).apply()
            gemini = null
        }
        MENU_AJUSTE_VOZ -> ajustarVoz()
        MENU_CHAVE -> pedirChave()
        MENU_MODELO -> pedirModelo()
        MENU_DIAGNOSTICO -> diagnosticar()
        MENU_FALAR -> {
            val ligado = !prefs.getBoolean(PREF_FALAR, true)
            prefs.edit().putBoolean(PREF_FALAR, ligado).apply()
            if (!ligado) pararFala()
        }
        MENU_CONTINUA -> {
            val ligado = !prefs.getBoolean(PREF_CONTINUA, true)
            prefs.edit().putBoolean(PREF_CONTINUA, ligado).apply()
        }
        MENU_INTERROMPER -> {
            val ligado = !prefs.getBoolean(PREF_INTERROMPER, true)
            prefs.edit().putBoolean(PREF_INTERROMPER, ligado).apply()
            if (!ligado) interruptor?.parar()
        }
        MENU_NOVA -> {
            ajustes?.dismiss()
            comecarConversaNova()
            adicionarMensagem("Conversa nova. Em que posso ajudar?", false, salvar = false)
        }
        else -> return false
    }
    return true
}

// --------------------------------------------------- histórico e memórias
internal fun MainActivity.mostrarHistorico() {
    TelaDeHistorico(this, memoria, corAtual) { id -> abrirConversa(id) }.show()
}

internal fun MainActivity.mostrarMemorias() {
    val fatos = memoria.fatos()
    if (fatos.isEmpty()) {
        Toast.makeText(this, "Ainda não sei nada sobre você. Diga: lembra que eu gosto de rock.", Toast.LENGTH_LONG).show()
        return
    }
    val nomes = fatos.map { f ->
        val rotulo = when {
            f.categoria == "apelido" -> "Seu nome"
            f.categoria == "cidade" -> "Sua cidade"
            f.categoria.startsWith("favorito:") -> "Favorito (" + f.categoria.removePrefix("favorito:") + ")"
            f.categoria == "ia" -> "Aprendido na conversa"
            else -> "Você disse"
        }
        "$rotulo: ${f.texto}"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("O que eu sei sobre você")
        .setItems(nomes) { _, qual ->
            val f = fatos[qual]
            AlertDialog.Builder(this)
                .setTitle("Esquecer isto?")
                .setMessage(f.texto)
                .setPositiveButton("Esquecer") { _, _ ->
                    memoria.esquecerId(f.id)
                    if (f.categoria == "apelido") cerebro.apelido = "chefe"
                }
                .setNegativeButton("Manter", null)
                .show()
        }
        .setNeutralButton("Apagar tudo") { _, _ -> confirmarApagarMemorias() }
        .setNegativeButton("Fechar", null)
        .show()
}

internal fun MainActivity.alternarTelaApagada() {
    val ligar = !prefs.getBoolean(VigiliaService.PREF_TELA_APAGADA, false)
    prefs.edit().putBoolean(VigiliaService.PREF_TELA_APAGADA, ligar).apply()
    // O serviço relê os ajustes (liga ou solta a trava do processador)
    try {
        if (prefs.getBoolean(PREF_FUNDO, false) || prefs.getBoolean(VigiliaService.PREF_GESTO, false)) VigiliaService.iniciar(this)
    } catch (e: Exception) {
    }
    if (!ligar) return
    val energia = getSystemService(android.os.PowerManager::class.java)
    val explicacao = "Com a tela apagada, a ${Marca.nome} continua ouvindo \"${Marca.nome}, ativar\", os comandos de música e o gesto. " +
        "Para o Android não desligar ela para economizar bateria, permita na próxima tela que ela funcione sem restrição de bateria."
    if (energia?.isIgnoringBatteryOptimizations(packageName) == true) {
        if (!prefs.getBoolean(PREF_FUNDO, false) && !prefs.getBoolean(VigiliaService.PREF_GESTO, false)) {
            Toast.makeText(this, "Ligue também \"Ouvir com o app fechado\" ou o gesto.", Toast.LENGTH_LONG).show()
        }
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Tela apagada")
        .setMessage(explicacao)
        .setPositiveButton("Permitir") { _, _ ->
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                }
            }
        }
        .setNegativeButton("Agora não", null)
        .show()
}

internal fun MainActivity.alternarGesto() {
    val ligar = !prefs.getBoolean(VigiliaService.PREF_GESTO, false)
    prefs.edit().putBoolean(VigiliaService.PREF_GESTO, ligar).apply()
    if (!ligar) {
        // Atualiza o serviço (ele desliga sozinho se nada mais estiver ligado)
        try {
            VigiliaService.iniciar(this)
        } catch (e: Exception) {
        }
        return
    }
    if (!podeSobrepor()) {
        AlertDialog.Builder(this)
            .setTitle("Uma permissão a mais")
            .setMessage("Para a ${Marca.nome} abrir por cima de outros apps quando você torcer o pulso, ative \"Sobrepor a outros apps\" para ela na próxima tela.")
            .setPositiveButton("Abrir configuração") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Agora não", null)
            .show()
        return
    }
    iniciarEscutaDeFundoSePreciso()
    AlertDialog.Builder(this)
        .setTitle("Gesto ligado")
        .setMessage(
            "Com a tela ligada, gire o celular para um lado e para o outro, duas vezes, firme. A ${Marca.nome} abre já ouvindo, e a música pausa enquanto você fala.\n\n" +
                "Para não abrir a câmera junto, desligue no app Moto: Gestos → Câmera rápida (torcer o pulso)."
        )
        .setPositiveButton("Abrir o app Moto") { _, _ ->
            val moto = listOf("com.motorola.actions", "com.motorola.moto", "com.motorola.motoapp")
                .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
            try {
                startActivity(moto ?: Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
            }
        }
        .setNegativeButton("Entendi", null)
        .show()
}

internal fun MainActivity.alternarEscutaDeFundo() {
    val ligar = !prefs.getBoolean(PREF_FUNDO, false)
    prefs.edit().putBoolean(PREF_FUNDO, ligar).apply()
    if (!ligar) {
        // O gesto pode continuar ligado: o serviço se ajusta (ou desliga se nada mais estiver ligado)
        try {
            if (prefs.getBoolean(VigiliaService.PREF_GESTO, false)) VigiliaService.iniciar(this) else VigiliaService.parar(this)
        } catch (e: Exception) {
        }
        Toast.makeText(this, "Escuta com o app fechado desligada.", Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACOES)
    }
    if (!temMicrofone()) {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
    }
    if (!podeSobrepor()) {
        AlertDialog.Builder(this)
            .setTitle("Uma permissão a mais")
            .setMessage(
                "Para o orbe aparecer por cima de outros apps quando você disser \"${Marca.nome}, ativar\", " +
                    "ative \"Sobrepor a outros apps\" (ou \"Exibir sobre outros apps\") para a ${Marca.nome} na próxima tela. " +
                    "Depois é só voltar aqui."
            )
            .setPositiveButton("Abrir configuração") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Agora não", null)
            .show()
    } else {
        iniciarEscutaDeFundoSePreciso()
    }
    Toast.makeText(
        this,
        "Com o app fechado e o celular desbloqueado, diga \"${Marca.nome}, ativar\". Fica uma notificação enquanto ela escuta.",
        Toast.LENGTH_LONG
    ).show()
}

internal fun MainActivity.definirAssistentePadrao() {
    AlertDialog.Builder(this)
        .setTitle("Assistente padrão")
        .setMessage(
            "Na próxima tela, escolha a ${Marca.nome} como \"App assistente digital\" (ou \"Assistente padrão\"). " +
                "Depois, segure o botão de início (ou o botão de ligar, dependendo do celular) para abrir o orbe."
        )
        .setPositiveButton("Abrir configuração") { _, _ ->
            val opcoes = listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (i in opcoes) {
                try {
                    startActivity(i)
                    break
                } catch (e: Exception) {
                }
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

internal fun MainActivity.alternarAvisosDaLoja() {
    val ligar = !VigiaDaLoja.ligado(this)
    if (ligar && !ferramentas.loja.conectada) {
        Toast.makeText(this, "Conecte a loja primeiro.", Toast.LENGTH_LONG).show()
        return
    }
    VigiaDaLoja.definir(this, ligar)
    if (ligar && Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACOES)
    }
    if (ligar) {
        // primeira olhada já agora: só registra o que existe, para avisar apenas o que for novo
        Thread {
            try {
                VigiaDaLoja.verificar(this)
            } catch (e: Exception) {
            }
        }.start()
    }
    Toast.makeText(this, if (ligar) "Avisos da loja ligados." else "Avisos da loja desligados.", Toast.LENGTH_SHORT).show()
    ajustes?.atualizar()
}

internal fun MainActivity.estudarAgora() {
    if (chave().isBlank()) return responder("Para estudar eu uso o Gemini. Configure a chave do Gemini nos ajustes.")
    if (estudando || Estudo.ocupado()) return responder("Já estou estudando. Leva uns minutos; eu aviso quando terminar.")
    estudando = true
    responder("Vou estudar agora: jeitos de pedir, charadas, respostas para clientes, correções das nossas conversas, erros de voz e objeções de venda. Leva uns 5 minutos e eu aviso aqui.")
    Thread {
        val r = try {
            Estudo.estudar(this, forcar = true)
        } catch (e: Exception) {
            null
        }
        val contagem = try {
            memoria.contarVetores()
        } catch (e: Exception) {
            emptyMap()
        }
        runOnUiThread {
            estudando = false
            adicionarMensagem(Estudo.descrever(r, contagem), false)
        }
    }.start()
}
