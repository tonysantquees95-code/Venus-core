package com.tonimarques.sextafeira

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/*
 * Cadastro guiado da primeira abertura: uma etapa por tela, com Voltar/Continuar e barrinha de progresso.
 * 1 boas-vindas · 2 você e a loja · 3 com site ou sem site · 4 regras de venda · 5 inteligência (vem do servidor, com o Vetorial ID)
 * · 6 opcionais (cobrança por link, Instagram) · 7 pronto. Cada etapa salva o que foi preenchido ao continuar,
 * então dá para fechar no meio e retomar depois pelo menu (Cadastro guiado). A lista "Primeiros passos" continua no menu.
 */

private const val ETAPAS = 7

/** Abre o cadastro guiado (na primeira abertura sozinho; depois pelo menu). */
internal fun MainActivity.cadastroGuiado(etapaInicial: Int = 0) {
    val n = Marca.nome
    val f = Formulario(this, "Cadastro da $n", "")
    val cfg = Plataformas.config(this)
    // com site? null = ainda não escolheu
    var comSite: Boolean? = when {
        cfg.tipo == "interno" -> false
        Perfil.site.isNotBlank() || ferramentas.loja.conectada || cfg.chave.isNotBlank() -> true
        else -> null
    }
    var aceitouTermos = Termos.aceitos(this)

    fun abrir(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(this, "Não encontrei um navegador para abrir o link.", Toast.LENGTH_SHORT).show()
    }

    fun habilitar(v: TextView, sim: Boolean) {
        v.isEnabled = sim
        v.alpha = if (sim) 1f else 0.4f
    }

    fun mostrar(etapa: Int) {
        f.limpar()
        f.progresso(etapa + 1, ETAPAS)
        val voltar = f.botao("Voltar") { mostrar(etapa - 1) }
        f.aoTocarVoltar = null
        if (etapa > 0) f.aoTocarVoltar = { mostrar(etapa - 1) }
        when (etapa) {
            // ---------------------------------------------------------------- 1. boas-vindas
            0 -> {
                f.trocarTitulo("Bem-vinda à $n", "Etapa 1 de $ETAPAS")
                f.paragrafo("Oi! Eu sou a $n, a assistente da sua loja. Vou te ajudar a vender mais e a cuidar do dia a dia:", 16f, android.graphics.Color.WHITE)
                f.cartao("O que eu faço")
                f.paragrafo("• Respondo as clientes no WhatsApp com o robô, com os preços, o frete e as regras da sua loja.\n" +
                    "• Cuido do estoque, das vendas e do catálogo (com site ou sem site).\n" +
                    "• Monto posts, stories e catálogo em PDF com as fotos dos produtos.\n" +
                    "• No Android sou assistente pessoal e comercial: controlo o celular e posso ser a sua assistente principal (no iPhone, pelo navegador, só atendo como assistente comercial).", 14.5f)
                f.fecharCartao()
                f.cartao("Sem chaves de IA")
                f.paragrafo("Toda a minha inteligência (conversa, PDF, áudio, fotos e imagens) vem do servidor da $n. Você não cria chave de IA nenhuma: só configura a loja e, se quiser o Instagram, a conexão com a Meta (o passo a passo está mais adiante).", 14f)
                f.fecharCartao()
                f.paragrafo("São 7 etapas rápidas, uns 10 minutos. O que você não souber agora dá para fazer depois, no menu.", 13.5f, Formulario.COR_APAGADA)
                f.cartao("Termos do teste-piloto")
                f.paragrafo(Termos.RESUMO, 14f)
                f.fecharCartao()
                f.adicionar(f.botao("Ler os termos completos") { mostrar(-1) }, 10)
                f.opcaoGrande("Li e aceito os termos do teste-piloto", "Toque para marcar.", aceitouTermos) {
                    aceitouTermos = !aceitouTermos
                    mostrar(0)
                }
                val comecar = f.botao("Começar", primario = true) {
                    if (!aceitouTermos) return@botao Toast.makeText(this, "Marque que leu e aceita os termos para continuar.", Toast.LENGTH_SHORT).show()
                    if (!Termos.aceitos(this)) Termos.aceitar(this)
                    mostrar(1)
                }
                habilitar(comecar, aceitouTermos)
                f.acoes(comecar)
            }

            // ---------------------------------------------------------------- termos completos
            -1 -> {
                f.trocarTitulo("Termos do teste-piloto", "Versão ${Termos.VERSAO}")
                f.aoTocarVoltar = { mostrar(0) }
                f.paragrafo(Termos.TEXTO, 14f, android.graphics.Color.WHITE)
                f.acoes(f.botao("Voltar", primario = true) { mostrar(0) })
            }

            // ---------------------------------------------------------------- 2. você e a loja
            1 -> {
                f.trocarTitulo("Você e a loja", "Etapa 2 de $ETAPAS")
                f.paragrafo("Com isso eu sei com quem estou falando e assino as mensagens com o nome da loja.")
                f.cartao("Seus dados")
                val novo = Perfil.loja == "sua loja"
                val (b1, dono) = f.campo("Seu nome", if (Perfil.dono == "chefe") "" else Perfil.dono, "Ex.: Ana",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                val (b2, loja) = f.campo("Nome da loja", if (novo) "" else Perfil.loja, "Ex.: Boutique da Ana",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                val (b3, cidade) = f.campo("Cidade e estado", Perfil.cidade + if (Perfil.uf.isNotBlank()) ", ${Perfil.uf}" else "", "Ex.: Porto Alegre, RS",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                f.adicionar(b1)
                f.adicionar(b2, 14)
                f.adicionar(b3, 14)
                f.acoes(f.botao("Continuar", primario = true) {
                    if (dono.text.isBlank() || loja.text.isBlank()) {
                        Toast.makeText(this, "Preencha o seu nome e o da loja.", Toast.LENGTH_SHORT).show()
                    } else {
                        Perfil.salvar(this, dono.text.toString(), loja.text.toString(), cidade.text.toString(), Perfil.sobre)
                        ajustes?.atualizar()
                        mostrar(2)
                    }
                }, voltar)
            }

            // ---------------------------------------------------------------- 3. com site ou sem site
            2 -> {
                f.trocarTitulo("Como você vende", "Etapa 3 de $ETAPAS")
                f.paragrafo("A sua loja tem site? Dá para mudar depois, no menu.")
                f.opcaoGrande("Tenho site", "Eu leio os produtos, o estoque e os pedidos direto do site (Shopify, WooCommerce, Nuvemshop ou site próprio).", comSite == true) {
                    comSite = true
                    if (Plataformas.tipo(this) == "interno") Plataformas.salvar(this, Plataformas.Config("floot", "", "", "", ""))
                    mostrar(2)
                }
                f.opcaoGrande("Não tenho site", "Vendo pelo WhatsApp, Instagram e Facebook. Os produtos, as fotos, o estoque e as vendas ficam aqui na $n, " +
                    "e eu monto o catálogo em PDF para mandar às clientes.", comSite == false) {
                    comSite = false
                    Plataformas.salvar(this, Plataformas.Config("interno", "", "", "", ""))
                    ajustes?.atualizar()
                    mostrar(2)
                }
                if (comSite == true) {
                    f.cartao("Onde fica o site")
                    val atual = Plataformas.config(this)
                    val conectado = ferramentas.loja.conectada
                    f.adicionar(f.aviso(if (conectado) "✓ Conectado: ${Plataformas.TIPOS.firstOrNull { it.id == atual.tipo }?.nome ?: atual.tipo}."
                    else "Toque na plataforma do seu site. Abre uma janela com o passo a passo para pegar a chave; depois volte aqui e toque em Continuar."))
                    for (t in Plataformas.TIPOS.filter { it.id != "interno" }) {
                        f.adicionar(f.botao(t.nome + if (atual.tipo == t.id && (conectado || atual.chave.isNotBlank())) "  ✓" else "") {
                            configurarPlataforma(t)
                            if (t.id == "floot") conectarLoja()
                        }, 10)
                    }
                    f.adicionar(f.aviso("Se não tiver a chave agora, pode continuar: depois é só ir no menu → Plataforma da loja."), 10)
                    f.fecharCartao()
                }
                val continuar = f.botao("Continuar", primario = true) { mostrar(3) }
                habilitar(continuar, comSite != null)
                f.acoes(continuar, voltar)
            }

            // ---------------------------------------------------------------- 4. regras de venda
            3 -> {
                f.trocarTitulo("Regras de venda", "Etapa 4 de $ETAPAS")
                f.paragrafo("Eu só prometo às clientes o que estiver aqui. Deixe vazio o que a loja não quiser informar.")
                val r = if (Perfil.regrasConfiguradas) Perfil.regras else RegrasDeVenda.VAZIA
                val semSite = comSite == false
                fun reais(c: Int?) = c?.let { String.format(java.util.Locale("pt", "BR"), "%.2f", it / 100.0) }.orEmpty()
                val numero = InputType.TYPE_CLASS_NUMBER
                val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

                f.cartao("A loja")
                val (bRamo, ramo) = f.campo("O que a loja vende", Perfil.ramo, "Ex.: moda feminina, cosméticos")
                f.adicionar(bRamo)
                var site: EditText? = null
                if (!semSite) {
                    val (bSite, s) = f.campo("Endereço do site", Perfil.site.removePrefix("https://"), "Ex.: minhaloja.com.br",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                    f.adicionar(bSite, 14)
                    site = s
                }

                f.cartao("Pagamento")
                val (bPix, pix) = f.campo("Desconto no Pix (%)", r.pixDescontoPct.takeIf { it > 0 }?.toString().orEmpty(), "Ex.: 5 (vazio = sem desconto)", numero)
                val (bParc, parcelas) = f.campo("Parcelas no cartão (até)", r.cartaoParcelasMax.takeIf { it > 1 }?.toString().orEmpty(), "Ex.: 3", numero)
                val (bJuros, semJuros) = f.campo("Sem juros até", r.parcelasSemJuros.takeIf { it > 1 }?.toString().orEmpty(), "Ex.: 3", numero)
                f.adicionar(bPix)
                f.lado(bParc, bJuros)

                f.cartao("Envio")
                val (bMet, metodos) = f.campo("Formas de envio", r.freteMetodos.joinToString(", "), "Ex.: PAC, SEDEX, motoboy")
                val (bCalc, calculo) = f.campo("Como o frete é calculado", r.freteCalculo.ifBlank { if (semSite) "pelo CEP, combinado na conversa" else "pelo CEP no site" }, "")
                val (bGratis, gratis) = f.campo("Frete grátis acima de R$", reais(r.freteGratisAcimaDe), "Vazio = não tem", decimal)
                val (bPost, postagem) = f.campo("Posta em até (dias úteis)", r.prazoPostagemDias?.toString().orEmpty(), "Ex.: 2", numero)
                f.adicionar(bMet)
                f.adicionar(bCalc, 14)
                f.lado(bGratis, bPost)
                val discreta = f.interruptor("Embalagem discreta, sem identificação", r.embalagemDiscreta)
                f.adicionar(discreta, 10)

                f.cartao("Retirada e atendimento")
                val retirada = f.interruptor("A cliente pode retirar pessoalmente", r.retiradaDisponivel)
                f.adicionar(retirada)
                val (bRetC, retCidade) = f.campo("Cidade da retirada", r.retiradaCidade.ifBlank { Perfil.cidade }, "")
                val (bRetComo, retComo) = f.campo("Como combina a retirada", r.retiradaComo, "Ex.: dia e horário pelo WhatsApp")
                f.adicionar(bRetC, 10)
                f.adicionar(bRetComo, 14)
                val (bCan, canais) = f.campo("Onde atende", r.canais.joinToString(", ").ifBlank { if (semSite) "WhatsApp, Instagram" else "site, WhatsApp, Instagram" }, "Ex.: WhatsApp, Instagram")
                val (bHor, horario) = f.campo("Horário de atendimento", r.horarioAtendimento.orEmpty(), "Ex.: seg a sex, 9h às 18h")
                f.adicionar(bCan, 14)
                f.adicionar(bHor, 14)
                f.adicionar(f.aviso("Trocas, idade mínima e outras regras: menu → Site, ramo e regras de venda."), 12)

                f.acoes(f.botao("Salvar e continuar", primario = true) {
                    fun int(e: EditText) = e.text.toString().trim().toIntOrNull()
                    val novas = r.copy(
                        pixDescontoPct = (int(pix) ?: 0).coerceIn(0, 50),
                        cartaoParcelasMax = (int(parcelas) ?: 1).coerceIn(1, 24),
                        parcelasSemJuros = (int(semJuros) ?: 1).coerceIn(1, 24),
                        freteMetodos = RegrasDeVenda.separar(metodos.text.toString()),
                        freteCalculo = calculo.text.toString().trim(),
                        freteGratisAcimaDe = RegrasDeVenda.centavosDe(gratis.text.toString()),
                        prazoPostagemDias = int(postagem),
                        embalagemDiscreta = discreta.isChecked,
                        retiradaDisponivel = retirada.isChecked,
                        retiradaCidade = retCidade.text.toString().trim(),
                        retiradaComo = retComo.text.toString().trim(),
                        canais = RegrasDeVenda.separar(canais.text.toString()),
                        horarioAtendimento = horario.text.toString().trim().ifBlank { null },
                        abrangencia = r.abrangencia.ifBlank { "todo o Brasil" },
                        arrependimentoDias = r.arrependimentoDias.coerceAtLeast(7),
                    )
                    Perfil.salvarLoja(this, if (semSite) "" else site?.text?.toString().orEmpty(), ramo.text.toString(), novas)
                    ajustes?.atualizar()
                    Thread { try { RegrasDaLoja.sincronizarVenda(applicationContext, memoria) } catch (e: Exception) { } }.start()
                    mostrar(4)
                }, voltar)
            }

            // ---------------------------------------------------------------- 5. inteligência (vem do servidor)
            4 -> {
                f.trocarTitulo("Minha inteligência", "Etapa 5 de $ETAPAS")
                val logado = SegundaIA.doServidorDisponivel(this)
                f.paragrafo("Para conversar e responder as clientes eu uso a inteligência do servidor da $n. Você não precisa criar chave nenhuma: " +
                    "basta estar com o seu Vetorial ID ligado neste celular.")
                f.cartao("Seu Vetorial ID")
                f.adicionar(f.aviso(if (logado) "✓ Ligado. Já posso conversar e responder as clientes."
                else "Ainda não entrou no Vetorial ID. Sem ele eu só consigo responder o básico, sem inteligência."), 2)
                if (!logado) f.adicionar(f.botao("Entrar no Vetorial ID") { f.fechar(); abrirConta() }, 10)
                f.fecharCartao()
                f.acoes(f.botao("Continuar", primario = true) { mostrar(5) }, voltar)
            }

            // ---------------------------------------------------------------- 6. opcionais
            5 -> {
                f.trocarTitulo("Extras (opcionais)", "Etapa 6 de $ETAPAS")
                f.paragrafo("Dá para pular tudo aqui e fazer depois no menu. Cada um deixa a $n mais completa.")

                // cobrança por link (loja sem site)
                if (comSite == false) {
                    f.cartao("Cobrança por link")
                    f.adicionar(f.aviso(if (Cobranca.configurada(this)) "✓ Ligada: ${Cobranca.nomeDoProvedor(this)}."
                    else "Eu crio o link de pagamento da venda (Pix ou cartão) no Mercado Pago ou na InfinitePay, você manda para a cliente e eu confiro o pagamento sozinha."), 2)
                    f.adicionar(f.botao("Escolher a plataforma de cobrança") { configurarCobranca() }, 10)
                }

                // Instagram
                f.cartao("Instagram da loja")
                f.adicionar(f.aviso(if (Instagram.conectado(this)) "✓ Instagram conectado (@${Instagram.usuario(this)})."
                else "Para eu postar no feed e nos stories sozinha, acompanhar seguidores e criar imagens. Precisa de uma conta comercial ou de criador no Instagram e da API da Meta (o passo a passo aparece ao tocar em Conectar). O Instagram não tem resposta automática: eu escrevo e você copia e cola nas respostas rápidas. Quem responde sozinho é o WhatsApp."), 2)
                f.adicionar(f.botao("Conectar o Instagram") { conectarInstagram() }, 10)

                f.acoes(f.botao("Continuar", primario = true) { mostrar(6) }, voltar)
            }

            // ---------------------------------------------------------------- 7. pronto
            else -> {
                f.trocarTitulo("Tudo pronto!", "Etapa 7 de $ETAPAS")
                prefs.edit().putBoolean("cadastro_concluido", true).putBoolean("primeiros_passos_visto", true).apply()
                Diario.anotar(this, "perfil", "Cadastro guiado concluído (${if (comSite == false) "sem site" else "com site"})", "tela")
                val semSite = comSite == false
                f.cartao("Como ficou")
                val itens = listOf(
                    "Você e a loja: ${Perfil.dono} · ${Perfil.loja}" to (Perfil.loja != "sua loja"),
                    (if (semSite) "Sem site: catálogo aqui na $n" else "Site: " + (Perfil.site.removePrefix("https://").ifBlank { "conectar depois" })) to
                        (semSite || ferramentas.loja.conectada),
                    "Regras de venda" to Perfil.regrasConfiguradas,
                    "Inteligência da $n (servidor)" to SegundaIA.doServidorDisponivel(this),
                    "Instagram" to Instagram.conectado(this),
                )
                for ((t, ok) in itens) f.adicionar(TextView(this).apply {
                    text = (if (ok) "✓  " else "○  ") + t
                    textSize = 14.5f
                    setTextColor(if (ok) android.graphics.Color.WHITE else Formulario.COR_APAGADA)
                }, 8)
                f.fecharCartao()
                f.paragrafo(if (semSite) "Primeiro passo: cadastre os seus produtos. Pode ser pela foto, pela nota fiscal de compra ou à mão. " +
                    "Depois eu monto o catálogo em PDF com o seu logo."
                else "Primeiro passo: me pergunte \"como foram as vendas hoje?\" ou \"o que está acabando?\".", 14.5f)
                f.paragrafo("O que ficou com ○ você faz quando quiser: menu → Cadastro guiado ou Primeiros passos.", 13f, Formulario.COR_APAGADA)
                f.aoTocarVoltar = { mostrar(5) }
                val principal = f.botao(if (semSite) "Cadastrar produtos" else "Começar", primario = true) {
                    f.fechar()
                    if (semSite) abrirCatalogo()
                    else responder("Pronto, ${Perfil.dono}! Pode perguntar: como foram as vendas hoje, tem pedido novo, o que está acabando.")
                }
                f.acoes(principal, if (semSite) f.botao("Depois") {
                    f.fechar()
                    responder("Pronto, ${Perfil.dono}! Quando quiser, abra o Catálogo da loja no menu para cadastrar os produtos.")
                } else null)
            }
        }
    }

    f.aoFechar {
        if (!prefs.getBoolean("cadastro_concluido", false))
            Toast.makeText(this, "Você continua o cadastro depois em Menu → Cadastro guiado.", Toast.LENGTH_LONG).show()
    }
    mostrar(etapaInicial.coerceIn(0, ETAPAS - 1))
    f.mostrar()
}

/** Na primeira abertura: quem ainda não concluiu o cadastro e não tem nenhuma IA configurada. */
internal fun MainActivity.precisaDoCadastro(): Boolean =
    !prefs.getBoolean("cadastro_concluido", false) && chave().isBlank() && !SegundaIA.configurada(this)
