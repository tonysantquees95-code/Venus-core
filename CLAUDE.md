# Vênus (app Android) — notas para quem mexe no código

## Ao corrigir erros dos relatórios
- Os relatórios chegam como issues "[Vênus] Relatório automático de erros" no GitHub.
- Ao corrigir um erro, acrescente uma linha em `app/src/main/assets/erros_consertados.txt`:
  `começo da assinatura | número da versão que conserta | descrição curta`.
  A assinatura é `tipo:` + detalhe normalizado (sem acento, minúsculas, números viram `#`), como em `Registro.assinatura`.
  A versão é o número do build do CI que vai sair com o conserto (versionName `1.<run>`).
- Ao instalar a versão, `Registro.limparConsertados` tira essas ocorrências do log e anota no `Diario`.
- Feche a issue com um comentário dizendo a versão do conserto.

## Diário de ajustes
- Toda ação que muda algo de verdade deve anotar em `Diario` (área, texto legível, origem, ok).
- Ferramentas em `Diario.ACOES_QUE_MUDAM` são anotadas sozinhas em `Ferramentas.executarSeguro`.

## Modelos do Gemini
- `gemini-2.5-flash` não está disponível para chaves novas; reserva atual: `gemini-3.8-flash` e `gemini-flash-lite-latest`.
- Embeddings: `EmbeddingsGemini` tenta `gemini-embedding-2`, `-2-preview`, `gemini-embedding-001` (768 dimensões).

## Regra de ouro: toda mudança de função vai para "O que há de novo"
- Qualquer melhoria que mude o que o app faz ou como se usa (função nova, fluxo melhorado, correção de erro visível) ganha uma linha **nos dois lugares**:
  - Android: `app/src/main/java/com/tonimarques/sextafeira/Novidades.kt` (`LISTA`).
  - Windows/web: `venus-apps/apps/dois-em-um/src/apresentacao/dados.ts` (`NOVIDADES`).
- `id` = o maior id existente + 1; `tipo` = `novo`, `melhoria` ou `correcao`; `titulo` curto; `texto` em português simples, dizendo o que mudou para a pessoa (não o que mudou no código).
- A tela aparece sozinha depois da atualização e mostra só o que a pessoa ainda não viu (não depende do número da versão). Na primeira instalação ela não aparece: quem explica é o Guia do primeiro acesso.
- Testes: `NovidadesTest.kt` (Android) e `teste/novidades.test.mjs` (Windows/web) conferem ids, tipos e textos.

## Apresentação da primeira abertura
- Android: `Onboarding.kt` (guia de permissões, cada uma explicada antes de pedir) → `TelaCadastro.kt` (termos em `Termos.kt`; mudou o texto: suba `Termos.VERSAO`).
- Windows/web: `apresentacao/Apresentacao.tsx` + `dados.ts` (tour → permissões → termos; mudou o texto: suba `VERSAO_DOS_TERMOS`).
- Função nova: acrescente ou ajuste um passo do tour. Permissão nova: explique o porquê ANTES de pedir.

## Agenda de serviços e Meu site (páginas do servidor dentro do app)
- A agenda (`/agenda/painel`) e o editor do site com logo (`/site/editor`) são páginas hospedadas no servidor (repositório `vetorial-servidor`, pastas `agenda/` e `meusite/`). No Android abrem em `TelaPaginaDoServidor.kt` (WebView em tela cheia, com galeria para fotos e download do logo pela `VenusAndroid`); no Windows/web, em `empresa/PaginaDoServidor.tsx` (iframe). Menu: Minha loja › Agenda de serviços / Meu site e logo.
- Aviso de nova marcação: `AgendaAvisos.kt` (roda no `VigiaDaLoja`, `GET /loja/agenda/novos`). Só liga depois que a pessoa abre a agenda a primeira vez.
- Mudou rota ou regra lá? Confira o servidor.

## Recibos, notinhas e etiquetas de envio (sem valor fiscal)
- Android: `Documentos.kt` (lógica pura: dinheiro em centavos, datas no fuso de Brasília, limite de 15 documentos grátis por mês, testada em `DocumentosTest.kt`), `GeradorDeDocumentos.kt` (desenha recibo A4, notinha 80 mm e etiqueta 10x15 em Canvas/PdfDocument) e `TelaDocumentos.kt` (Minha loja › Recibos e etiquetas: pedidos recentes, recibo avulso, dados da loja, prévia, compartilhar, abrir e imprimir). `Loja.Pedido.endereco` vem do `shippingAddress` do pedido.
- Windows/web: `empresa/documentos.ts` (mesma lógica + SVG de cada documento, testada em `teste/documentos.test.mjs`) e `empresa/RecibosEEtiquetas.tsx` (preenchimento manual: o web não lê os pedidos da loja). Reaproveita `PecaDigital` e `Imprimivel` de `Divulgacao.tsx`; `Peca.escala` e `pdfComImagem(..., escala)` dão nitidez de impressão.
- Plano grátis: 15 documentos por mês (refazer o mesmo documento não gasta outro) e marca "Feito com a Vênus" no rodapé; plano pago/isento: sem limite e sem marca. Tudo fica no aparelho.

## Pesquisa "vai assinar?" (teste grátis)
- Telinha ao abrir o app para quem está no teste grátis (a partir do 2º dia) ou já ficou sem acesso: "Vou assinar / Ainda não sei / Não vou assinar" + motivo. Respondeu: some por 7 dias; fechou sem responder: volta no dia seguinte (8h às 21h, uma vez por dia). Quem já paga não vê.
- Android: `PesquisaDeAssinatura.kt` (regra pura testada em `PesquisaDeAssinaturaTest.kt`; a fase vem de `/id/conta` em `VetorialId.atualizarPerfil`). Windows/web: `apresentacao/pesquisaAssinatura.ts` (+ `PesquisaDeAssinatura.tsx`, teste `teste/pesquisa-assinatura.test.mjs`).
- Envia `POST /feedback` com `tipo: "assinatura"` e `respostas {decisao, motivo, plano, dias_de_uso}`; aparece no Painel Dev › Feedback ("Vai assinar?"). Servidor: `feedback/dados.js` (`DECISAO`, `MOTIVO`). Mudou a regra de um lado? Mude do outro.

## Sua loja hoje e resumo da manhã (só Android)
- `Resultado.kt` (lógica pura testada em `ResultadoTest.kt`): conta os atendimentos do robô por dia (`AtendenteDoRobo.atender` chama `Resultado.anotarResposta`; guarda só o hash do cliente, 35 dias) e calcula as vendas pagas. Tela: `TelaSuaLoja.kt` (Minha loja › Sua loja hoje). NUNCA diga que uma venda "veio da Vênus": ela só sabe o que respondeu e o que a loja vendeu, separados.
- Aviso da manhã: dentro de `VigiaDaLoja.verificar` (precisa dos avisos da loja ligados), uma vez por dia, na hora escolhida em Menu (padrão 8h, janela até 13h). Não existe no Windows/web porque lá não há robô nem leitura dos pedidos; por isso a novidade (id 38) é só do Android.

## Caixa (balcão, `CaixaActivity.kt`, lógica pura em `Pdv.kt` e `PdvVendas.kt`)
- Quatro etapas na mesma Activity (`Etapa`): LOJA (só se a conta tem mais de uma; `Lojas.listar/abrir`, e abrir uma loja aqui troca a loja aberta da Vênus toda) → PRODUTOS (busca, carrinho, "Ler etiqueta" por QR) → PAGAMENTO (resumo com nomes e valores, editável; Pix, dinheiro ou cartão; a venda só é registrada e o estoque só baixa ao confirmar o recebimento, em `fechar`) → RECIBO (WhatsApp e CPF do cliente, os dois opcionais; "Cliente não quer CPF na nota").
- O CPF NUNCA é guardado: só vive na tela do recibo e vai no texto do WhatsApp ou na notinha em PDF (`Pdv.cpfValido` confere os dígitos). O recibo pelo WhatsApp (`Pdv.textoDoRecibo` + `Pdv.linkWhatsApp`, abre o WhatsApp com a mensagem pronta) não gasta o limite de 15 documentos/mês; a notinha em PDF gasta, igual aos outros documentos (`cotaDeDocumentos`, `emissorDosDocumentos` ficaram `internal` em `TelaDocumentos.kt`).
- Formas de pagamento (rodada 2): **Pix** — se `Cobranca.pixAutomaticoPronto` (Mercado Pago cadastrado com chave), `Cobranca.criarPixMp` cria o Pix em `/v1/payments` e o Caixa confere `statusDoPixMp` a cada 3 s; `approved` fecha a venda sozinha ("Confirmar à mão" é a reserva); se falhar, cai na chave Pix da loja. **Dinheiro** — calculadora de teclas grandes (`Pdv.teclar`, dígitos entram pela direita como maquininha), troco ao vivo, "Valor exato" dá baixa direto; nada fecha sozinho ao digitar (valores intermediários enganariam). **Cartão** — com provedor de cobrança (Mercado Pago/InfinitePay) abre a página segura do provedor (`Cobranca.criarLink`) para o cliente digitar o cartão; os dados NUNCA passam pela Vênus; `Cobranca.conferir` a cada 4 s fecha quando aprovado; sem provedor, registro manual com taxa da maquininha. Só dá para testar de verdade no aparelho com conta real.
- Número da venda: `B<n>` (catálogo interno = id do pedido; loja com site = `PdvVendas.numeroAmigavel`). Só dá para testar de verdade no aparelho (leitor de QR, WhatsApp, PDF).

## Teste de segurança (igual em todos os apps da suíte)
- `venus-apps/teste/seguranca.test.mjs` roda junto com os outros testes e na publicação: se falhar, a publicação para. Confere o repositório inteiro: segredo escrito no código (chaves, tokens, senhas), arquivo que nunca pode ser versionado (.env, chaves de assinatura), CSP do Tauri sem `unsafe-eval`/curinga, manifesto do Android (debug, tráfego http, componente exposto sem permissão, `QUERY_ALL_PACKAGES`), `eval`, HTML montado com texto (`innerHTML`), endereço `http://` fora da máquina local, segredo no log, HTTPS desligado e WebView com acesso perigoso.
- Exceção justificada = uma linha em `seguranca.permitidos.json` (regra, arquivo, trecho e MOTIVO de verdade); sem motivo, não vale. Texto digitado pela pessoa ou vindo de site/servidor nunca entra em HTML sem escapar (`textContent` ou `esc()`).
- O arquivo é o mesmo em todos os apps: mudou uma regra? Mude em todos. Achou uma brecha nova? Escreva primeiro um teste que a reproduz.
- O passo "Teste de segurança" também roda em `gerar-apk.yml` (Kotlin) e em `npm test` de `venus-apps`. O provider `ProvedorDeRegras` é exportado de propósito, com permissão de assinatura.
