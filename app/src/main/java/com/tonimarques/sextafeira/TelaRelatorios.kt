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
import java.util.Calendar
import java.util.Locale

/*
 * Relatórios, diário, erros e atualização do app.
 * Funções da tela principal (MainActivity) separadas por assunto; o comportamento é o mesmo de antes.
 */

/** Manda o relatório de erros para o GitHub (sozinho a cada 12 horas, ou na hora se o app travou). */
internal fun MainActivity.enviarRelatorioDeErros(manual: Boolean) {
    if (!manual && !Registro.deveEnviar(this)) return
    if (manual && Registro.pendentes(this).isEmpty()) {
        Toast.makeText(this, "Nenhum erro anotado desde o último relatório. 👏", Toast.LENGTH_SHORT).show()
        return
    }
    Thread {
        val erro = Registro.enviar(this, atualizador.tokenParaRelatorio())
        if (manual) runOnUiThread {
            Toast.makeText(this, erro ?: "Relatório enviado. Na próxima atualização o Claude lê e corrige.", Toast.LENGTH_LONG).show()
        }
    }.start()
}

internal fun MainActivity.diagnosticar() {
    if (ocupado) return
    if (chave().isBlank()) {
        pedirChave()
        return
    }
    pararVigilia()
    ocupado = true
    mudarEstado(Orbe.Estado.PENSANDO, "TESTANDO A CONEXÃO…")
    val balao = adicionarMensagem("Testando a conexão, aguarde até 1 minuto…", false, salvar = false)
    val k = chave()
    val m = modelo()
    Thread {
        val relatorio = try {
            Diagnostico.executar(applicationContext, k, m, ferramentas)
        } catch (e: Exception) {
            "O diagnóstico falhou: ${e.javaClass.simpleName}: ${e.message}"
        }
        runOnUiThread {
            balao.text = relatorio
            rolarParaBaixo()
            ocupado = false
            mudarEstadoOcioso()
            agendarVigilia(500)
        }
    }.start()
}

// ----------------------------------------------------------- finanças: gráfico e relatório
internal fun MainActivity.mostrarGrafico() {
    comTempoLimite("MONTANDO O GRÁFICO…") {
        val (pedidos, produtos) = try {
            ferramentas.dadosDoCaixa()
        } catch (e: Loja.ErroDaLoja) {
            return@comTempoLimite { responder(e.message ?: "Não consegui buscar as vendas.") }
        }
        val agora = java.util.Calendar.getInstance()
        val hist = Financas.historico(pedidos, produtos, Financas.lancamentos(this), Financas.fixas(this), 6, agora)
        val imagem = Relatorio.grafico(hist)
        val jpeg = Relatorio.jpeg(imagem)
        val atual = hist.last().second
        val fala = if (atual.receita == 0L && hist.all { it.second.receita == 0L }) {
            "Ainda não tem vendas para mostrar no gráfico, mas já deixei ele pronto: conforme as vendas e despesas entrarem, ele vai se enchendo."
        } else {
            "Aqui está o gráfico dos últimos 6 meses. Neste mês, até agora: vendas de ${Loja.dinheiro(atual.receita.toInt())} e " +
                (if (atual.lucro >= 0) "lucro de ${Loja.dinheiro(atual.lucro.toInt())}." else "prejuízo de ${Loja.dinheiro((-atual.lucro).toInt())}.")
        }
        val acao: () -> Unit = {
            responder(fala)
            mostrarImagem("Gráfico financeiro", imagem, jpeg)
        }
        acao
    }
}

internal fun MainActivity.gerarRelatorio(periodo: String) {
    comTempoLimite("GERANDO O RELATÓRIO…") {
        val (pedidos, produtos) = try {
            ferramentas.dadosDoCaixa()
        } catch (e: Loja.ErroDaLoja) {
            return@comTempoLimite { responder(e.message ?: "Não consegui buscar as vendas.") }
        }
        val agora = java.util.Calendar.getInstance()
        val p = Financas.periodo(periodo, agora)
        val lancamentos = Financas.lancamentos(this)
        val fixas = Financas.fixas(this)
        val res = Financas.resultado(pedidos, produtos, lancamentos, fixas, p)
        val hist = Financas.historico(pedidos, produtos, lancamentos, fixas, 6, agora)
        val nomeDoMes = java.text.SimpleDateFormat("MMMM 'de' yyyy", Locale("pt", "BR"))
        val titulo = when (periodo) {
            "mes_passado" -> nomeDoMes.format(java.util.Date(p.ini))
            "semana" -> "últimos 7 dias"
            else -> nomeDoMes.format(agora.time) + " (até hoje)"
        }
        val previsao = if (periodo == "mes") Financas.textoPrevisao(hist, fixas.sumOf { it.valorCentavos.toLong() }, agora) else ""
        val bytes = Relatorio.pdf(
            Relatorio.Dados(p, titulo, res, hist, lancamentos, fixas, Financas.maisVendidos(pedidos, p), previsao)
        )
        val (arquivo, uri) = FotoProvider.novoArquivo(this, "relatorio_${System.currentTimeMillis()}.pdf")
        arquivo.writeBytes(bytes)
        val fala = "O relatório de $titulo está pronto. " +
            (if (res.lucro >= 0) "Lucro de ${Loja.dinheiro(res.lucro.toInt())}" else "Prejuízo de ${Loja.dinheiro((-res.lucro).toInt())}") +
            " sobre ${Loja.dinheiro(res.receita.toInt())} em vendas. Você pode abrir, imprimir ou mandar para a Natália."
        val acao: () -> Unit = {
            responder(fala)
            oferecerPdf(uri)
        }
        acao
    }
}

internal fun MainActivity.oferecerPdf(uri: Uri) {
    CaixaBuilder(this)
        .setTitle("Relatório financeiro (PDF)")
        .setMessage("O que você quer fazer com o relatório?")
        .setPositiveButton("Compartilhar") { _, _ ->
            val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = android.content.ClipData.newRawUri("relatorio", uri)
            startActivity(Intent.createChooser(i, "Enviar relatório"))
        }
        .setNeutralButton("Abrir") { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Nenhum app de PDF encontrado. Use Compartilhar e abra no Drive.", Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton("Fechar", null)
        .show()
}

// ----------------------------------------------------------- log de ajustes e erros
/** Tela com os ajustes feitos (diário) e os erros em aberto, com Enviar relatório e Limpar erros. */
internal fun MainActivity.mostrarLog() {
    Thread {
        val ajustes = Diario.de(this).ultimos(60)
        val erros = Registro.log(this)
        runOnUiThread {
            val f = Formulario(this, "Log da ${Marca.nome}", "Ajustes feitos e erros em aberto")
            fun linha(titulo: String, texto: String, ruim: Boolean = false) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(this@mostrarLog).apply {
                    text = titulo
                    textSize = 11.5f
                    letterSpacing = 0.05f
                    setTextColor(if (ruim) Color.parseColor("#FF8A80") else Formulario.COR_APAGADA)
                })
                addView(TextView(this@mostrarLog).apply {
                    text = texto
                    textSize = 14.5f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            f.cartao("Erros em aberto (${erros.size})")
            if (erros.isEmpty()) f.adicionar(f.aviso("Nenhum erro em aberto. Quando a ${Marca.nome} anotar um erro, ele aparece aqui até ser consertado."), 2)
            for (o in erros.take(40)) {
                val quando = Diario.hora(o.ultima)
                val estado = if (o.status == "enviado") "enviado ao GitHub" else "aguardando envio"
                val nome = Registro.NOMES[o.tipo] ?: o.tipo
                val texto = listOf(o.frase.takeIf { it.isNotBlank() }?.let { "“$it”" }, o.detalhe.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" → ")
                f.adicionar(linha("${o.vezes}x · $nome · v${o.versao} · $quando · $estado", texto, ruim = o.tipo == "falha"), 2)
            }
            f.cartao("Últimos ajustes")
            if (ajustes.isEmpty()) f.adicionar(f.aviso("Ainda não há ajustes registrados."), 2)
            for (a in ajustes) {
                f.adicionar(linha("${Diario.hora(a.quando)} · ${Diario.NOMES_AREA[a.area] ?: a.area}" + if (!a.ok) " · falhou" else "", a.texto, ruim = !a.ok), 2)
            }
            f.acoes(
                f.botao("Enviar relatório", primario = true) { enviarRelatorioDeErros(manual = true) },
                f.botao("Limpar erros") {
                    f.fechar()
                    limparLogDeErros()
                },
            )
            f.mostrar()
        }
    }.start()
}

internal fun MainActivity.limparLogDeErros() {
    val n = Registro.log(this).size
    if (n == 0) return responder("O log de erros já está limpo.")
    CaixaBuilder(this)
        .setTitle("Limpar o log de erros?")
        .setMessage("Os $n erros em aberto saem do log. Use quando já foram consertados; os que ainda não foram enviados ao GitHub não serão mais enviados.")
        .setPositiveButton("Limpar") { _, _ ->
            Registro.limparTudo(this)
            Diario.anotar(this, "erro", "Log de erros limpo à mão ($n erros)", "tela")
            responder("Pronto, limpei o log de erros.")
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

/** Procura versão nova. Automática (ao abrir, no máximo a cada 6 h e sem incomodar) ou pelo menu. */
internal fun MainActivity.procurarAtualizacao(manual: Boolean) {
    if (!manual && (atualizador.procurouRecentemente() || !atualizador.temToken)) return
    if (manual) Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show()
    Thread {
        val resultado = try {
            Result.success(atualizador.procurar())
        } catch (e: Exception) {
            Result.failure(e)
        }
        // a Vênus primeiro; o robô só quando ela já está em dia (ou se a busca dela falhou)
        val robo = if (resultado.getOrNull() == null) atualizador.procurarRobo() else null
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            resultado.onSuccess { v ->
                when {
                    v != null -> oferecerAtualizacao(v)
                    robo != null -> oferecerAtualizacao(robo)
                    manual -> Toast.makeText(this, "Você já está com a versão mais nova (${versaoDoApp()}).", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                if (robo != null) {
                    oferecerAtualizacao(robo)
                    return@onFailure
                }
                if (!manual) return@onFailure
                if (e is Atualizador.ErroDeAtualizacao && e.precisaToken) pedirTokenDoGithub(e.message)
                else Toast.makeText(this, e.message ?: "Não consegui procurar agora. Confira a internet.", Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

internal fun MainActivity.oferecerAtualizacao(v: Atualizador.Versao) {
    val novidades = v.notas.ifBlank { "Melhorias e correções." }
    if (v.ehRobo) {
        CaixaBuilder(this)
            .setTitle("Nova versão do Vênus Robô: 1.${v.numero}")
            .setMessage("$novidades\n\nO robô continua com a chave, os canais e o acesso às notificações. Toque em Atualizar na tela do Android.")
            .setPositiveButton("Atualizar o robô") { _, _ -> instalarAtualizacao(v) }
            .setNegativeButton("Depois", null)
            .show()
        return
    }
    CaixaBuilder(this)
        .setTitle("Nova versão da ${Marca.nome}: 1.${v.numero}")
        .setMessage("$novidades\n\nA atualização mantém suas memórias, ajustes e a conexão com a loja.")
        .setPositiveButton("Atualizar agora") { _, _ -> instalarAtualizacao(v) }
        .setNegativeButton("Depois", null)
        .show()
}

internal fun MainActivity.instalarAtualizacao(v: Atualizador.Versao) {
    val aviso = TextView(this).apply {
        text = "Baixando… 0%"
        setPadding(dp(24), dp(16), dp(24), dp(8))
        textSize = 16f
    }
    val dialogo = CaixaBuilder(this).setTitle(if (v.ehRobo) "Atualizando o Vênus Robô" else "Atualizando a ${Marca.nome}")
        .setView(aviso).setCancelable(false).show()
    // depois que a Vênus reabrir atualizada, ela procura de novo e já oferece o robô
    if (!v.ehRobo) atualizador.esquecerBusca()
    Thread {
        try {
            atualizador.baixarEInstalar(v) { pct -> runOnUiThread { aviso.text = "Baixando… $pct%" } }
            runOnUiThread {
                aviso.text = "Pronto. Confirme a instalação na próxima tela."
                principal.postDelayed({ if (dialogo.isShowing) dialogo.dismiss() }, 2500)
            }
        } catch (e: Exception) {
            runOnUiThread {
                dialogo.dismiss()
                Toast.makeText(this, e.message ?: "Não consegui baixar a atualização.", Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

/** O Android avisa como foi a instalação; quando precisa da confirmação, abre a tela dele. */
internal fun MainActivity.resultadoDaInstalacao(intent: Intent) {
    when (intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
        android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            @Suppress("DEPRECATION")
            val confirmar = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmar != null) {
                try {
                    startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "Não consegui abrir a instalação.", Toast.LENGTH_LONG).show()
                }
            }
        }
        android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
            val robo = intent.getBooleanExtra(Atualizador.EXTRA_ROBO, false)
            val numero = intent.getIntExtra(Atualizador.EXTRA_VERSAO, 0)
            if (robo) Diario.anotar(this, "app", "Vênus Robô atualizado para a versão 1.$numero", "atualizacao")
            Toast.makeText(this, if (robo) "Vênus Robô atualizado!" else "${Marca.nome} atualizada!", Toast.LENGTH_SHORT).show()
        }
        -999 -> {}
        else -> {
            val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            Toast.makeText(this, "A instalação não foi concluída. $msg".trim(), Toast.LENGTH_LONG).show()
        }
    }
}

internal fun MainActivity.pedirTokenDoGithub(motivo: String?) {
    val campo = campoDeDialogo("", "Cole o token aqui (github_pat_…)")
    CaixaBuilder(this)
        .setTitle("Token do GitHub")
        .setMessage(
            (motivo?.let { "$it\n\n" } ?: "") +
                "Como as versões ficam num repositório privado, a ${Marca.nome} precisa de uma chave só de leitura:\n\n" +
                "1. No GitHub: Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.\n" +
                "2. Repository access: Only select repositories → Venus-core.\n" +
                "3. Permissions → Repository → Contents: Read-only.\n" +
                "4. Gere, copie e cole aqui.\n\n" +
                "Ela fica criptografada só no celular e não deixa mudar nada no GitHub."
        )
        .setView(campo)
        .setPositiveButton("Salvar e procurar") { _, _ ->
            val t = campo.text.toString().trim()
            if (t.length < 20) {
                Toast.makeText(this, "Esse token parece incompleto.", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            atualizador.guardarToken(t)
            procurarAtualizacao(manual = true)
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
