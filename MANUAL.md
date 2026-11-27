# Manual — Vênus

> Escrito para a Vetorial Tech. Não contém segredos: só diz ONDE ficam. Visão de todos os apps: Manual da Vetorial Tech (Drive).

Assistente de IA e plataforma do lojista. Android (Kotlin) e `venus-apps` (web, Windows, Mac).

## Onde fica cada coisa
- `app/src/main/java/com/tonimarques/sextafeira/*.kt`: o app Android (nome antigo do projeto; não mude, é a identidade do app). Assistente/IA: `Assistente.kt`, `Cerebro.kt`, `Gemini.kt`. Loja: `Loja.kt`, `CatalogoInterno.kt`, `Instagram.kt`. Caixa: `CaixaActivity.kt`, `Pdv.kt`, `Cobranca.kt`, `Caderninho*.kt`. Documentos: `Documentos.kt`, `GeradorDeDocumentos.kt`. Robô: `AtendenteDoRobo.kt`. Conta: `VetorialId.kt`, `Licenca.kt`, `Atualizador.kt`. Novidades: `Novidades.kt` (último id 50).
- `app/src/test/`: testes. `app/src/main/assets/erros_consertados.txt`: erros já consertados (formato no `CLAUDE.md`).
- `venus-apps/apps/{assistente,dois-em-um,robo}`: Vênus web/Windows e robô. `venus-apps/desktop`: casca Electron. `venus-apps/teste`: testes e segurança.
- `.github/workflows/gerar-apk.yml`: testa, compila, assina e publica `v1.N` (~4 min). `venus-apps.yml`: instaladores `win-1.N`/`mac-1.N`. Os fluxos `criar-chave`, `guardar-chave-antiga`, `exportar-segredos`, `teste-chave` mexem na chave de assinatura: não rode sem saber o motivo.

## Regras
- Toda mudança visível: linha em `Novidades.kt` E em `venus-apps/apps/dois-em-um/src/apresentacao/dados.ts`.
- Pagamento, WhatsApp, notificação e leitor de QR só se confirmam no celular.


## Como voltar atrás se algo quebrar
- No GitHub: aba **Actions** mostra o fluxo vermelho; **Code › Commits** mostra o commit culpado; abra o commit e toque em **⋯ › Revert** (cria um commit novo que desfaz o anterior).
- O número da versão sempre sobe: a versão boa volta como número novo, nunca se republica um número antigo.
- Banco de dados e dados dos usuários não voltam com o código (migrações só andam para frente).

## Movimentações importantes
Uma linha por mudança que valha lembrar (a mais nova em cima). O Painel Dev também mostra os últimos commits automaticamente.

| Data | O que mudou | Onde |
| --- | --- | --- |
| 2026-11-27 | Caderninho: botão "Gerar link de pagamento" nos fiados antigos (sem link) quando há cobrança configurada. Novidade 51. | `CaixaActivity.kt` (`gerarLinkDoFiado`), `Novidades.kt` |
| 2026-11-27 | Manual criado | `MANUAL.md` |
