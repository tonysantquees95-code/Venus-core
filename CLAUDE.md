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
