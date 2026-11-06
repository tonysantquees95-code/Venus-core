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
 * 1 boas-vindas · 2 você e a loja · 3 com site ou sem site · 4 regras de venda · 5 IA grátis (Groq, obrigatória)
 * · 6 opcionais (Gemini, Cloudflare, Instagram) · 7 pronto. Cada etapa salva o que foi preenchido ao continuar,
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
    var groqOk = SegundaIA.chave(this).isNotBlank()

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
                    "• Monto posts, stories e catálogo em PDF com as fotos dos produtos.", 14.5f)
                f.fecharCartao()
                f.paragrafo("São 7 etapas rápidas, uns 10 minutos. O que você não souber agora dá para fazer depois, no menu.", 13.5f, Formulario.COR_APAGADA)
                f.acoes(f.botao("Começar", primario = true) { mostrar(1) })
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
                f.opcaoGrande("Tenho site", "Eu leio os produtos, o estoque e os pedidos direto do site (Shopify, WooCommerce, Nuvemshop ou Floot).", comSite == true) {
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

            // ---------------------------------------------------------------- 5. IA grátis (Groq)
            4 -> {
                f.trocarTitulo("Minha inteligência", "Etapa 5 de $ETAPAS")
                f.paragrafo("Para conversar e responder as clientes eu uso a Groq, uma IA grátis (cerca de 1.000 perguntas por dia, sem cartão). " +
                    "Você só precisa criar uma chave, que é como uma senha de acesso.")
                f.cartao("Passo a passo")
                f.paragrafo("1. Toque em Abrir a Groq, aqui embaixo.\n" +
                    "2. Entre com a sua conta do Google (Continue with Google).\n" +
                    "3. Toque em Create API Key, escreva um nome (ex.: Vênus) e toque em Submit.\n" +
                    "4. Toque em Copy para copiar a chave (começa com gsk_). Ela só aparece uma vez!\n" +
                    "5. Volte para cá, cole a chave no campo e toque em Conferir a chave.", 14.5f, android.graphics.Color.WHITE)
                f.adicionar(f.botao("Abrir a Groq") { abrir(SegundaIA.PROVEDORES.first { it.id == "groq" }.site) }, 12)
                f.cartao("Sua chave")
                val (bChave, campo) = f.campo("Chave da Groq", "", if (groqOk) "Chave salva ✓ (cole outra só para trocar)" else "gsk_…",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                f.adicionar(bChave)
                val estado = f.aviso(if (groqOk) "✓ A Groq está funcionando." else "")
                f.adicionar(estado)
                val continuar = f.botao("Continuar", primario = true) { mostrar(5) }
                habilitar(continuar, groqOk)
                lateinit var conferir: TextView
                conferir = f.botao("Conferir a chave") {
                    val k = Instagram.limparToken(campo.text.toString())
                    if (k.isBlank()) return@botao Toast.makeText(this, "Cole a chave no campo primeiro.", Toast.LENGTH_SHORT).show()
                    habilitar(conferir, false)
                    estado.text = "Conferindo com a Groq…"
                    val antes = SegundaIA.chave(this)
                    val provedorAntes = SegundaIA.provedor(this).id
                    SegundaIA.salvar(this, "groq", k)
                    Thread {
                        val r = try { Result.success(SegundaIA.testar(applicationContext)) } catch (e: Exception) { Result.failure(e) }
                        runOnUiThread {
                            habilitar(conferir, true)
                            r.onSuccess {
                                groqOk = true
                                estado.text = "✓ Funcionou! A Groq respondeu. Pode continuar."
                                habilitar(continuar, true)
                                ajustes?.atualizar()
                            }.onFailure {
                                // chave ruim: volta a anterior (se tinha) para não deixar a Vênus sem IA
                                SegundaIA.salvar(this, provedorAntes, antes)
                                estado.text = "✗ Não funcionou: ${it.message ?: "confira se copiou a chave inteira"}. Copie de novo e confira."
                            }
                        }
                    }.start()
                }
                f.adicionar(conferir, 10)
                f.acoes(continuar, voltar)
            }

            // ---------------------------------------------------------------- 6. opcionais
            5 -> {
                f.trocarTitulo("Extras (opcionais)", "Etapa 6 de $ETAPAS")
                f.paragrafo("Dá para pular tudo aqui e fazer depois no menu. Cada um deixa a $n mais completa.")

                // Gemini
                f.cartao("Gemini do Google (grátis)")
                f.adicionar(f.aviso("Para eu ler PDF, notas fiscais e áudios, guardar memória e estudar. Também é grátis."), 2)
                f.paragrafo("1. Toque em Abrir o Google AI Studio.\n" +
                    "2. Entre com a sua conta do Google e aceite os termos.\n" +
                    "3. Toque em Create API key (Criar chave de API) e escolha o projeto que aparecer.\n" +
                    "4. Copie a chave (começa com AIza), volte aqui, cole e toque em Conferir.", 14f, android.graphics.Color.WHITE)
                f.adicionar(f.botao("Abrir o Google AI Studio") { abrir("https://aistudio.google.com/apikey") }, 12)
                val temGemini = chave().isNotBlank()
                val (bGem, gem) = f.campo("Chave do Gemini", "", if (temGemini) "Chave salva ✓ (cole outra só para trocar)" else "AIza…",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                f.adicionar(bGem, 12)
                val estadoGem = f.aviso(if (temGemini) "✓ Gemini configurado." else "")
                f.adicionar(estadoGem)
                f.adicionar(f.botao("Conferir a chave do Gemini") {
                    val k = gem.text.toString().trim()
                    if (k.isBlank()) return@botao Toast.makeText(this, "Cole a chave no campo primeiro.", Toast.LENGTH_SHORT).show()
                    estadoGem.text = "Conferindo com o Google…"
                    Thread {
                        val erro = conferirChaveGemini(k)
                        runOnUiThread {
                            if (erro == null) {
                                Cofre.guardar(prefs, MainActivity.PREF_CHAVE, k)
                                gemini = null
                                Diario.anotar(this, "conexao", "Chave do Gemini configurada no cadastro guiado", "tela")
                                estadoGem.text = "✓ Funcionou! Gemini configurado."
                                ajustes?.atualizar()
                            } else estadoGem.text = "✗ $erro"
                        }
                    }.start()
                }, 8)

                // Cloudflare
                f.cartao("Cloudflare (reserva grátis)")
                f.adicionar(f.aviso("Se a Groq chegar no limite do dia, eu pergunto para a Cloudflare. Bom para lojas com muitas conversas."), 2)
                f.paragrafo("1. Toque em Abrir a Cloudflare e crie uma conta grátis (só e-mail e senha).\n" +
                    "2. No menu, entre em AI → Workers AI → Use REST API.\n" +
                    "3. Copie o Account ID (32 letras e números) e cole no primeiro campo.\n" +
                    "4. Toque em Create a Workers AI API Token, depois em Create API Token, copie e cole no segundo campo.\n" +
                    "5. Toque em Conferir.", 14f, android.graphics.Color.WHITE)
                f.adicionar(f.botao("Abrir a Cloudflare") { abrir(SegundaIA.CLOUDFLARE.site) }, 12)
                val temCf = SegundaIA.chaveCloudflare(this).isNotBlank()
                val (bConta, conta) = f.campo("Account ID", SegundaIA.contaCloudflare(this), "32 letras e números")
                val (bCf, cf) = f.campo("API Token", "", if (temCf) "Chave salva ✓ (cole outra só para trocar)" else "Token do Workers AI",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                f.adicionar(bConta, 12)
                f.adicionar(bCf, 12)
                val estadoCf = f.aviso(if (temCf) "✓ Cloudflare configurada." else "")
                f.adicionar(estadoCf)
                f.adicionar(f.botao("Conferir a Cloudflare") {
                    val k = cf.text.toString().ifBlank { SegundaIA.chaveCloudflare(this) }
                    if (k.isBlank() || conta.text.isBlank()) return@botao Toast.makeText(this, "Cole o Account ID e o token.", Toast.LENGTH_SHORT).show()
                    val contaAntes = SegundaIA.contaCloudflare(this)
                    val chaveAntes = SegundaIA.chaveCloudflare(this)
                    SegundaIA.salvarCloudflare(this, conta.text.toString(), k)
                    estadoCf.text = "Conferindo com a Cloudflare…"
                    Thread {
                        val r = try { Result.success(SegundaIA.testar(applicationContext, segunda = true)) } catch (e: Exception) { Result.failure(e) }
                        runOnUiThread {
                            r.onSuccess {
                                estadoCf.text = "✓ Funcionou! Cloudflare configurada."
                                ajustes?.atualizar()
                            }.onFailure {
                                SegundaIA.salvarCloudflare(this, contaAntes, chaveAntes)
                                estadoCf.text = "✗ Não funcionou: ${it.message ?: "confira o Account ID e o token"}."
                            }
                        }
                    }.start()
                }, 8)

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
                else "Para eu postar no feed e nos stories sozinha. Precisa de uma conta comercial ou de criador no Instagram."), 2)
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
                    "IA grátis (Groq)" to SegundaIA.chave(this).isNotBlank(),
                    "Gemini (PDF, notas, áudio, memória)" to chave().isNotBlank(),
                    "Reserva Cloudflare" to SegundaIA.chaveCloudflare(this).isNotBlank(),
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

/** Confere a chave do Gemini listando os modelos (não gasta cota). Null = funcionou; senão, o motivo. Bloqueante. */
internal fun conferirChaveGemini(chave: String): String? {
    var c: HttpURLConnection? = null
    return try {
        c = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1&key=" + Uri.encode(chave)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        when (c.responseCode) {
            in 200..299 -> null
            400, 401, 403 -> "O Google recusou essa chave. Confira se copiou inteira (começa com AIza)."
            429 -> null // existe, só está no limite agora
            else -> "O Google respondeu ${c.responseCode}. Tente de novo em instantes."
        }
    } catch (e: Exception) {
        "Sem conexão com o Google agora. Confira a internet e tente de novo."
    } finally {
        c?.disconnect()
    }
}

/** Na primeira abertura: quem ainda não concluiu o cadastro e não tem nenhuma IA configurada. */
internal fun MainActivity.precisaDoCadastro(): Boolean =
    !prefs.getBoolean("cadastro_concluido", false) && chave().isBlank() && !SegundaIA.configurada(this)
