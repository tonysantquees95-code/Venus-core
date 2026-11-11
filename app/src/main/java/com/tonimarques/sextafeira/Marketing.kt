package com.tonimarques.sextafeira

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Intent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Central de Marketing: fila de sugestões de postagem, logotipo e cores da marca, cartão de visita, panfleto e QR Codes.
 */

// ------------------------------------------------------------------ fila de postagens
internal fun MainActivity.abrirMarketing(anterior: TelaDeAjustes? = null) {
    pedirNuvem("GET", "/marketing/fila", carregando = if (anterior == null) "Abrindo a fila…" else null) { j ->
        anterior?.dismiss()
        var tela: TelaDeAjustes? = null
        val fila = j.optJSONArray("fila").lista()
        val acoes = listOf(
            TelaDeAjustes.Ajuste("Gerar sugestões", { "A IA cruza os produtos em promoção e o estoque e escreve legenda, chamada e hashtags." }) {
                pedirNuvem("POST", "/marketing/sugestoes", JSONObject().put("quantidade", 5), "Escrevendo as sugestões…") { r ->
                    aviso(if (r.optJSONArray("criados")?.length() == 0) "Nada novo para sugerir agora." else "Sugestões prontas!"); abrirMarketing(tela)
                }
            },
            TelaDeAjustes.Ajuste("Gerar todas de novo", { "Reescreve em lote todas as sugestões que ainda não foram agendadas." }) {
                pedirNuvem("POST", "/marketing/regenerar", JSONObject().put("todos", true), "Reescrevendo…") { r -> aviso("${r.optInt("regenerados")} sugestões reescritas."); abrirMarketing(tela) }
            },
            TelaDeAjustes.Ajuste("Onde publicar", { "Conecte o Instagram (API oficial da Meta) ou um webhook do Vênus Robô para publicar sozinha no horário marcado." }) { abrirDestinos() },
            TelaDeAjustes.Ajuste("Registro de envios", { "Sucesso, tentando de novo ou erro de cada postagem automática." }) { abrirRegistro() },
            TelaDeAjustes.Ajuste("Excluir várias", { "Marque as postagens que quer tirar da fila de uma vez." }) { excluirEmLote(fila) { abrirMarketing(tela) } },
            TelaDeAjustes.Ajuste("Limpar postagens agendadas", { "Tira da fila as que você já agendou." }) {
                confirmar("Limpar as postagens agendadas?") {
                    pedirNuvem("POST", "/marketing/limpar", JSONObject().put("status", "agendado")) { r -> aviso("${r.optInt("removidos")} removidas."); abrirMarketing(tela) }
                }
            },
            TelaDeAjustes.Ajuste("Limpar sugestões", { "Esvazia as sugestões ainda não agendadas." }) {
                confirmar("Limpar todas as sugestões?") {
                    pedirNuvem("POST", "/marketing/limpar", JSONObject().put("status", "sugerido")) { r -> aviso("${r.optInt("removidos")} removidas."); abrirMarketing(tela) }
                }
            },
        )
        val itens = fila.map { p ->
            TelaDeAjustes.Ajuste(p.optString("copy").ifBlank { p.optString("produto_nome") }, {
                estadoDaPostagem(p.optString("status"), p.optLong("agendado_para"), p.optString("erro"), p.optLong("publicado_em")) + " · " + p.optString("produto_nome") + "\n" + p.optString("legenda") + "\n" + hashtagsDe(p).joinToString(" ")
            }, extra = if (p.optString("status") == "sugerido") "↻" to {
                pedirNuvem("POST", "/marketing/fila/${p.optInt("id")}", JSONObject(), "Reescrevendo esta sugestão…") { abrirMarketing(tela) }
            } else null) {
                ajustesDeMarketing = tela
                escolher(p.optString("produto_nome"), listOf(
                    "Editar legenda, hashtags e imagem…" to { editarPostagem(p) { abrirMarketing(tela) } },
                    "Publicar agora" to { publicarAgora(p) { abrirMarketing(tela) } },
                    "Copiar legenda e hashtags" to { copiar("Postagem", textoDaPostagem(p)) },
                    "Compartilhar" to { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, textoDaPostagem(p)), "Compartilhar")) },
                    "Regenerar esta" to { pedirNuvem("POST", "/marketing/fila/${p.optInt("id")}", JSONObject(), "Reescrevendo…") { abrirMarketing(tela) } },
                    "Agendar…" to { agendarPostagem(p.optInt("id"), tela) },
                    "Remover" to { pedirNuvem("DELETE", "/marketing/fila/${p.optInt("id")}") { abrirMarketing(tela) } },
                ))
            }
        }
        tela = telaDeLista("MARKETING", listOf(
            TelaDeAjustes.Secao("Fila inteligente", acoes),
            TelaDeAjustes.Secao("Postagens (${fila.size})", if (itens.isEmpty()) listOf(TelaDeAjustes.Ajuste("Fila vazia", { "Toque em Gerar sugestões." }) { }) else itens),
        ))
    }
}

private fun hashtagsDe(p: JSONObject): List<String> = p.optJSONArray("hashtags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
private fun textoDaPostagem(p: JSONObject) = p.optString("legenda") + "\n\n" + hashtagsDe(p).joinToString(" ")

internal fun MainActivity.confirmar(pergunta: String, sim: () -> Unit) {
    AlertDialog.Builder(this).setTitle(pergunta).setPositiveButton("Sim") { _, _ -> sim() }.setNegativeButton("Cancelar", null).show()
}

internal fun MainActivity.agendarPostagem(id: Int, tela: TelaDeAjustes?) {
    fun em(horas: Int = 0, diasMais: Int = 0, hora: Int = -1): Long {
        val c = Calendar.getInstance()
        if (hora >= 0) { c.add(Calendar.DAY_OF_YEAR, diasMais); c.set(Calendar.HOUR_OF_DAY, hora); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0) } else c.add(Calendar.HOUR_OF_DAY, horas)
        if (c.timeInMillis < System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
    val opcoes = listOf(
        "Daqui a 1 hora" to em(horas = 1),
        "Hoje às 19h" to em(hora = 19),
        "Amanhã às 10h" to em(diasMais = 1, hora = 10),
        "Amanhã às 19h" to em(diasMais = 1, hora = 19),
    )
    escolher("Agendar para…", opcoes.map { (nome, quando) ->
        nome to { pedirNuvem("POST", "/marketing/fila/$id/agendar", JSONObject().put("agendarPara", quando)) {
            aviso("Agendada: $nome. Ela sai das sugestões e uma nova entra no lugar.")
            Diario.anotar(this, "marketing", "Postagem $id agendada ($nome).", "tela", true)
            // repõe: uma sugestão nova ocupa o lugar da que acabou de ser agendada
            pedirNuvem("POST", "/marketing/sugestoes", JSONObject().put("quantidade", 1)) { abrirMarketing(tela) }
        } }
    })
}

// ------------------------------------------------------------------ logotipo e cores
private const val PREF_MARCA = "marca_conceito"

internal fun MainActivity.marcaAtual(): Criativos.Conceito {
    val salvo = prefs.getString(PREF_MARCA, "").orEmpty().split("|")
    if (salvo.size == 5) {
        val cor = Paleta.lerHex(salvo[3])
        if (cor != null) return Criativos.Conceito(salvo[0], salvo[1], salvo[2], cor, salvo[4])
    }
    return Criativos.Conceito(Perfil.loja, "", "circulo", Tema.cor(prefs).takeIf { it != 0 } ?: Paleta.corDoNome(Perfil.loja), "elegante")
}

private fun MainActivity.guardarMarca(k: Criativos.Conceito) {
    prefs.edit().putString(PREF_MARCA, listOf(k.nome, k.slogan, k.forma, Paleta.hex(k.cor), k.estilo).joinToString("|") { it.replace("|", " ") }).apply()
}

/** Quatro conceitos sem IA: o mesmo nome dá sempre o mesmo resultado; usado quando o servidor de IA não responde. */
internal fun conceitosDeReserva(nome: String, slogan: String): List<Criativos.Conceito> {
    val base = Tema.hsv(Paleta.corDoNome(nome))
    return (0 until 4).map { i ->
        Criativos.Conceito(nome, slogan, Criativos.FORMAS[i % Criativos.FORMAS.size], Tema.deHsv(base[0] + i * 47f, 0.62f, 0.9f), Criativos.ESTILOS[i % Criativos.ESTILOS.size])
    }
}

/** Lê a resposta da IA (JSON com uma lista de conceitos) e descarta o que for inválido. */
internal fun lerConceitos(texto: String, nome: String, slogan: String): List<Criativos.Conceito> {
    val ini = texto.indexOf('[')
    val fim = texto.lastIndexOf(']')
    if (ini < 0 || fim <= ini) return emptyList()
    return try {
        val arr = JSONArray(texto.substring(ini, fim + 1))
        (0 until minOf(arr.length(), 4)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cor = Paleta.lerHex(o.optString("cor")) ?: return@mapNotNull null
            Criativos.Conceito(
                nome, o.optString("slogan").ifBlank { slogan }.take(60),
                o.optString("forma").takeIf { it in Criativos.FORMAS } ?: Criativos.FORMAS[i % Criativos.FORMAS.size],
                cor, o.optString("estilo").takeIf { it in Criativos.ESTILOS } ?: Criativos.ESTILOS[i % Criativos.ESTILOS.size],
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun MainActivity.abrirLogo() {
    escolher("Logotipo e cores", listOf(
        "Criar conceitos de logotipo" to { criarLogotipos() },
        "Tirar as cores de uma foto ou logo" to { coresDeUmaFoto() },
        "Ver as cores da minha marca" to { mostrarCores(marcaAtual().paleta, "Cores da marca") },
    ))
}

private fun MainActivity.criarLogotipos() {
    perguntar("Logotipo", listOf("Nome da marca" to Perfil.loja, "Slogan (opcional)" to "", "Estilo (ex.: sensual, delicado, moderno)" to "elegante"), "Criar") { v ->
        val nome = v[0].ifBlank { Perfil.loja }
        aviso("Criando conceitos…")
        emFundo({
            val sistema = "Você é diretor de arte de marcas. Responda SOMENTE um JSON: lista de 4 objetos " +
                "{\"slogan\":\"até 40 caracteres\",\"forma\":\"circulo|hexagono|losango|folha|escudo\",\"cor\":\"#RRGGBB\",\"estilo\":\"moderna|serifa|elegante\"}. " +
                "Cada conceito com cor principal e estilo diferentes, combinando com a marca."
            Nuvem.conversar(this, sistema, "Marca: $nome. Estilo desejado: ${v[2]}. Slogan atual: ${v[1].ifBlank { "nenhum" }}.")
        }) { r ->
            val ia = lerConceitos(r.getOrNull().orEmpty(), nome, v[1])
            val conceitos = if (ia.size >= 2) ia else conceitosDeReserva(nome, v[1])
            mostrarConceitos(conceitos)
        }
    }
}

private fun MainActivity.mostrarConceitos(conceitos: List<Criativos.Conceito>) {
    val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
    var dialogo: AlertDialog? = null
    for (k in conceitos) {
        coluna.addView(ImageView(this).apply {
            setImageBitmap(Criativos.logo(k, 520))
            adjustViewBounds = true
            contentDescription = "Conceito de logotipo ${k.forma}"
            setOnClickListener { dialogo?.dismiss(); acoesDoLogo(k) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }
    dialogo = AlertDialog.Builder(this).setTitle("Toque no que mais gostou").setView(ScrollView(this).apply { addView(coluna) })
        .setNegativeButton("Fechar", null).setNeutralButton("Outros") { _, _ -> criarLogotipos() }.show()
}

private fun MainActivity.acoesDoLogo(k: Criativos.Conceito) {
    val imagem = Criativos.logo(k, 1080)
    val cores = coresDoBitmap(imagem)
    escolher("Este logotipo", listOf(
        "Ver os códigos de cor (Hex)" to { mostrarCores(cores, "Cores do logotipo") },
        "Compartilhar a imagem (PNG)" to { compartilharImagens(listOf(Criativos.salvar(this, imagem, "logo_${System.currentTimeMillis()}"))) },
        "Usar como identidade da loja" to { usarComoMarca(k) },
    ))
}

/** As cores de um bitmap (reduzido, para ser rápido) em Hex, das mais presentes para as menos. */
internal fun coresDoBitmap(b: Bitmap, quantas: Int = 5): List<Int> {
    val menor = Bitmap.createScaledBitmap(b, 64, 64, true)
    val px = IntArray(64 * 64)
    menor.getPixels(px, 0, 64, 0, 0, 64, 64)
    return Paleta.extrair(px, quantas)
}

internal fun MainActivity.mostrarCores(cores: List<Int>, titulo: String) {
    if (cores.isEmpty()) { aviso("Não consegui separar cores nesta imagem."); return }
    val texto = cores.joinToString("\n") { Paleta.hex(it) }
    val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
    for (c in cores) coluna.addView(TextView(this).apply {
        text = "  " + Paleta.hex(c)
        textSize = 17f
        setTextColor(0xFF000000.toInt() or Paleta.textoSobre(c))
        setBackgroundColor(0xFF000000.toInt() or c)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setOnClickListener { copiar(Paleta.hex(c), Paleta.hex(c)) }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
    AlertDialog.Builder(this).setTitle(titulo).setView(coluna)
        .setPositiveButton("Copiar todas") { _, _ -> copiar("Cores", texto) }
        .setNeutralButton("Usar a primeira como cor da loja") { _, _ -> usarCorDaLoja(cores.first()) }
        .setNegativeButton("Fechar", null).show()
}

/** A cor vira a cor do app (orbe e ícones) e a da vitrine online. */
internal fun MainActivity.usarCorDaLoja(rgb: Int) {
    Tema.definirCor(prefs, rgb)
    aplicarTema()
    pedirNuvem("PUT", "/loja/vitrine", JSONObject().put("cor", Paleta.hex(rgb))) { aviso("Cor ${Paleta.hex(rgb)} aplicada no app e na vitrine.") }
}

private fun MainActivity.usarComoMarca(k: Criativos.Conceito) {
    guardarMarca(k)
    usarCorDaLoja(k.cor)
}

private fun MainActivity.coresDeUmaFoto() {
    cadastroFoto = { uri ->
        cadastroFoto = null
        emFundo({
            val bytes = FotoProvider.prepararParaEnviar(this, uri, 400) ?: throw Nuvem.ErroNuvem("Não consegui abrir essa imagem.")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Nuvem.ErroNuvem("Não consegui abrir essa imagem.")
            coresDoBitmap(bmp, 6)
        }) { r ->
            r.onFailure { aviso(it.mensagemAmigavel()) }
            r.onSuccess { mostrarCores(it, "Cores da imagem") }
        }
    }
    pedirFotoGaleria()
}

internal fun MainActivity.compartilharImagens(uris: List<Uri>) {
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    i.type = "image/png"
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, "Compartilhar"))
}

/** Mostra as imagens prontas e oferece compartilhar. */
internal fun MainActivity.previa(titulo: String, imagens: List<Bitmap>, nome: String, extra: Pair<String, () -> Unit>? = null) {
    val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
    for (b in imagens) coluna.addView(ImageView(this).apply { setImageBitmap(b); adjustViewBounds = true },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    val b = AlertDialog.Builder(this).setTitle(titulo).setView(ScrollView(this).apply { addView(coluna) })
        .setPositiveButton("Compartilhar") { _, _ ->
            val agora = System.currentTimeMillis()
            compartilharImagens(imagens.mapIndexed { i, img -> Criativos.salvar(this, img, "${nome}_${agora}_${i + 1}") })
        }.setNegativeButton("Fechar", null)
    if (extra != null) b.setNeutralButton(extra.first) { _, _ -> extra.second() }
    b.show()
}

// ------------------------------------------------------------------ QR Codes, cartão e panfleto (tudo gerado no celular)
/** Link dos materiais: o site conectado da loja ou a vitrine criada pela Vênus, conforme o que a pessoa escolheu. */
internal fun MainActivity.linkDosMateriais(vitrine: String): String =
    DadosDaLoja.escolherLink(prefs.getString(DadosDaLoja.PREF_LINK, "site").orEmpty(), Perfil.site, vitrine)

internal fun MainActivity.escolherLinkDosMateriais(depois: () -> Unit = {}) {
    fun definir(v: String) {
        prefs.edit().putString(DadosDaLoja.PREF_LINK, v).apply()
        Diario.anotar(this, "Central da loja", "Link dos materiais: ${DadosDaLoja.nomeDoLink(v, Perfil.site)}.", "tela", true)
        aviso("Pronto: ${DadosDaLoja.nomeDoLink(v, Perfil.site)}.")
        depois()
    }
    val opcoes = mutableListOf<Pair<String, () -> Unit>>()
    if (Perfil.site.isNotBlank()) opcoes += "Site da minha loja\n${Perfil.site}" to { definir("site") }
    opcoes += "Site criado pela Vênus\nA vitrine da Vênus, com catálogo, carrinho e pedidos funcionando." to { definir("vitrine") }
    escolher("Link nos QR Codes, cartão e panfleto", opcoes)
}

/** Entrega o link da loja já escolhido. Só consulta o servidor se for usar a vitrine da Vênus; sem erro de tela se falhar. */
internal fun MainActivity.comLinkDaLoja(depois: (String) -> Unit) {
    val pref = prefs.getString(DadosDaLoja.PREF_LINK, "site").orEmpty()
    if (Perfil.site.isNotBlank() && pref != "vitrine") return depois(Perfil.site)
    aviso("Buscando o endereço da vitrine…")
    emFundo({ Nuvem.pedir(this, "GET", "/loja/vitrine").json.optString("url") }) { r ->
        val v = r.getOrNull().orEmpty()
        val link = DadosDaLoja.escolherLink(pref, Perfil.site, v)
        if (link.isBlank()) aviso("Ainda não há endereço da loja. Conecte o site em Ajustes → Site, ramo e regras de venda, ou crie a vitrine da Vênus.")
        else depois(link)
    }
}

private fun MainActivity.instagramConectado(): String? = Instagram.usuario(this).takeIf { Instagram.conectado(this) }

/** Mostra o QR Code (na cor da marca) e já oferece copiar o conteúdo. */
internal fun MainActivity.mostrarQrDoTexto(legenda: String, texto: String, copiavel: String? = texto) {
    try {
        val m = QrLocal.matriz(texto)
        val cor = marcaAtual().cor.let { if (Paleta.contraste(it, 0xFFFFFF) >= 3.0) it else 0x06081A }
        previa(legenda, listOf(Criativos.qr(m.modulos, m.tamanho, 900, cor)), "qr", if (copiavel != null) "Copiar código" to { copiar("Código", copiavel) } else null)
    } catch (e: Exception) {
        aviso(e.message ?: "Não consegui gerar o QR Code.")
    }
}

internal fun MainActivity.abrirQr() {
    val insta = instagramConectado()
    val itens = mutableListOf<Pair<String, () -> Unit>>()
    itens += "Minha loja\n${DadosDaLoja.nomeDoLink(prefs.getString(DadosDaLoja.PREF_LINK, "site").orEmpty(), Perfil.site)}." to { qrDaLoja() }
    if (insta != null) itens += "Meu Instagram (${DadosDaLoja.arroba(insta)})\nJá conectado, sem digitar nada." to { qrDoInstagram(insta) }
    itens += "Chave Pix\nUsa a chave que você já informou." to { qrPix() }
    itens += "Link de pagamento" to { qrTexto("Link de pagamento", "Endereço https do link de pagamento") }
    itens += "Outro link ou rede social" to { qrTexto("QR Code de um link", "Endereço https (ex.: https://instagram.com/sualoja)") }
    itens += "Meus links curtos\nOs que você já criou." to { abrirLinksCurtos() }
    itens += "Trocar o link da loja\nSite conectado ou site criado pela Vênus." to { escolherLinkDosMateriais { abrirQr() } }
    escolher("QR Code de…", itens)
}

internal fun MainActivity.qrDaLoja() = comLinkDaLoja { mostrarQrDoTexto("Minha loja", it) }

internal fun MainActivity.qrDoInstagram(usuario: String) {
    val url = DadosDaLoja.enderecoDoInstagram(usuario)
    mostrarQrDoTexto("Instagram ${DadosDaLoja.arroba(usuario)}", url)
}

internal fun MainActivity.qrPix() {
    perguntar("QR Code do Pix", listOf("Chave Pix (CPF, CNPJ, celular, e-mail ou aleatória)" to DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_PIX), "Nome do recebedor" to Perfil.loja, "Cidade" to Perfil.cidade, "Valor em R\$ (vazio = o cliente digita)" to ""), "Gerar") { v ->
        if (QrLocal.chavePix(v[0]) == null) return@perguntar aviso("Chave Pix inválida. Use CPF, CNPJ, celular com DDD, e-mail ou chave aleatória.")
        DadosDaLoja.lembrar(prefs, DadosDaLoja.PREF_PIX, v[0])
        val valor = v[3].replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 }
        val centavos = valor?.let { Math.round(it * 100) }
        try {
            val texto = QrLocal.pixCopiaECola(v[0], v[1], v[2], centavos)
            mostrarQrDoTexto("Pix${if (centavos != null) " de ${Nuvem.reais(centavos)}" else ""}", texto)
        } catch (e: Exception) {
            aviso(e.message ?: "Não consegui gerar o Pix.")
        }
    }
}

private fun MainActivity.qrTexto(titulo: String, dica: String) {
    perguntar(titulo, listOf(dica to "https://"), "Gerar") { v ->
        if (!v[0].trim().startsWith("https://")) return@perguntar aviso("Use um endereço https.")
        mostrarQrDoTexto(titulo, v[0].trim())
    }
}

// ------------------------------------------------------------------ cartão de visita e panfleto
internal fun MainActivity.abrirMateriais() {
    escolher("Materiais da loja", listOf(
        "Cartão de visita (frente e verso)\nJá sai com seu nome, WhatsApp, Instagram e o QR Code da loja." to { criarCartao() },
        "Panfleto de ofertas (A5)\nOfertas do catálogo, contato e QR Code, prontos." to { criarPanfleto() },
        "Trocar o link da loja\nSite conectado ou site criado pela Vênus." to { escolherLinkDosMateriais { abrirMateriais() } },
    ))
}

/** [link] = o endereço que vai no QR Code (null = o link da loja). */
internal fun MainActivity.criarCartao(link: String? = null) {
    val insta = DadosDaLoja.arroba(instagramConectado())
    perguntar("Cartão de visita", listOf(
        "Seu nome" to Perfil.dono,
        "Cargo ou ramo" to DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_CARGO).ifBlank { "Proprietária(o)" },
        "WhatsApp com DDD" to DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_WHATSAPP),
        "Instagram" to insta,
    ), "Criar") { v ->
        DadosDaLoja.lembrar(prefs, DadosDaLoja.PREF_WHATSAPP, v[2])
        DadosDaLoja.lembrar(prefs, DadosDaLoja.PREF_CARGO, v[1])
        val montar = { destino: String ->
            try {
                val qr = QrLocal.matriz(destino)
                val marca = marcaAtual()
                val contato = Criativos.Contato(v[0], v[1], DadosDaLoja.telefoneBonito(v[2]), v[3], destino)
                previa("Cartão de visita", listOf(Criativos.cartaoFrente(marca, contato), Criativos.cartaoVerso(marca, contato, qr.modulos, qr.tamanho)), "cartao")
            } catch (e: Exception) {
                aviso(e.message ?: "Não consegui montar o cartão.")
            }
        }
        if (link != null) montar(link) else comLinkDaLoja(montar)
    }
}

/** Ofertas do catálogo da loja conectada (no celular); se não der, as do servidor; sem nenhum, panfleto sem ofertas. */
private fun MainActivity.ofertasDaLoja(): List<Criativos.Oferta> {
    try {
        val l = ferramentas.loja
        if (l.conectada) {
            val v = l.produtos().filter { it.ativo && (it.estoque ?: 1) > 0 && (it.precoCentavos ?: 0) > 0 }
            val promos = v.filter { it.precoAntigo != null && it.precoAntigo > (it.precoCentavos ?: 0) }
            val tira = (promos + v.filter { it !in promos }).take(6)
            return tira.map { p ->
                if (p.precoAntigo != null && p.precoAntigo > (p.precoCentavos ?: 0)) Criativos.Oferta(p.nome, p.precoAntigo.toLong(), p.precoCentavos?.toLong())
                else Criativos.Oferta(p.nome, (p.precoCentavos ?: 0).toLong(), null)
            }
        }
    } catch (e: Exception) {
    }
    return try {
        val produtos = Nuvem.pedir(this, "GET", "/produtos").json.optJSONArray("produtos").lista()
        val vendaveis = produtos.filter { !it.optBoolean("oculto") && it.optInt("estoque") > 0 && it.optLong("preco_centavos") > 0 }
        val promos = vendaveis.filter { it.has("promocao_centavos") && !it.isNull("promocao_centavos") && it.optLong("promocao_centavos") < it.optLong("preco_centavos") }
        (promos + vendaveis.filter { it !in promos }).take(6).map {
            Criativos.Oferta(it.optString("nome"), it.optLong("preco_centavos"), if (it.isNull("promocao_centavos")) null else it.optLong("promocao_centavos"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun MainActivity.criarPanfleto(link: String? = null) {
    val whats = DadosDaLoja.guardado(prefs, DadosDaLoja.PREF_WHATSAPP)
    val insta = instagramConectado()
    perguntar("Panfleto de ofertas", listOf("Título" to "Ofertas da semana", "Rodapé (contato)" to DadosDaLoja.rodape(whats, insta, "")), "Criar") { v ->
        val montar = { destino: String ->
            aviso("Montando o panfleto…")
            emFundo({ ofertasDaLoja() }) { r ->
                try {
                    val ofertas = r.getOrNull().orEmpty()
                    val qr = QrLocal.matriz(destino)
                    val rodape = v[1].ifBlank { DadosDaLoja.rodape(whats, insta, destino) }
                    previa("Panfleto", listOf(Criativos.panfleto(marcaAtual(), v[0], ofertas, rodape, qr.modulos, qr.tamanho)), "panfleto")
                } catch (e: Exception) {
                    aviso(e.message ?: "Não consegui montar o panfleto.")
                }
            }
        }
        if (link != null) montar(link) else comLinkDaLoja(montar)
    }
}

// ------------------------------------------------------------------ links curtos já cadastrados
/** O que fazer com um link: QR Code, panfleto, cartão, copiar e compartilhar (tudo já com o link escolhido). */
internal fun MainActivity.acoesDoLink(titulo: String, link: String, extra: List<Pair<String, () -> Unit>> = emptyList()) {
    escolher(titulo, listOf(
        "QR Code deste link" to { mostrarQrDoTexto(titulo, link) },
        "Panfleto com este link" to { criarPanfleto(link) },
        "Cartão de visita com este link" to { criarCartao(link) },
        "Copiar link" to { copiar("Link", link) },
        "Compartilhar" to { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Compartilhar")) },
    ) + extra)
}

internal fun MainActivity.abrirLinksCurtos() {
    pedirNuvem("GET", "/links", carregando = "Buscando seus links…") { l ->
        val links = l.optJSONArray("links").lista()
        if (links.isEmpty()) return@pedirNuvem aviso("Você ainda não tem links curtos. Use \"Criar link curto da loja\" nos atalhos.")
        telaDeLista("MEUS LINKS CURTOS", listOf(TelaDeAjustes.Secao("${links.size} ${if (links.size == 1) "link" else "links"}", links.map { k ->
            TelaDeAjustes.Ajuste(k.optString("rotulo").ifBlank { k.optString("curto") }, { "${k.optString("curto")} · ${k.optInt("cliques")} cliques → ${k.optString("destino")}" }) {
                acoesDoLink(k.optString("rotulo").ifBlank { "Link curto" }, k.optString("curto"))
            }
        })))
    }
}

/** Cria (ou reaproveita) o link curto do link da loja. */
internal fun MainActivity.criarLinkCurtoDaLoja() = comLinkDaLoja { destino ->
    emFundo({
        val atuais = Nuvem.pedir(this, "GET", "/links")
        val ja = atuais.json.optJSONArray("links").lista().firstOrNull { it.optString("destino") == destino }
        if (ja != null) ja.optString("curto") else {
            val r = Nuvem.pedir(this, "POST", "/links", JSONObject().put("destino", destino).put("rotulo", "Minha loja"))
            if (!r.ok) throw IllegalStateException(r.erro())
            Diario.anotar(this, "Central da loja", "Link curto da loja criado.", "tela", true)
            r.json.optString("curto")
        }
    }) { r ->
        r.onFailure { aviso(it.mensagemAmigavel()) }
        r.onSuccess { curto -> acoesDoLink("Link curto da loja", curto) }
    }
}
