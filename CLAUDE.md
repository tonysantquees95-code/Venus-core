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
