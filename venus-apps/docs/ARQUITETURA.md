# Arquitetura

- **Isolamento:** `scripts/verificar-isolamento.mjs` falha o CI se aparecer pasta ou referência a loja/site.
- **IDs de app:** `com.vetorialtech.venus` (assistente) e `com.vetorialtech.venus.robo`. Se o app Kotlin atual usa outro ID, ajuste em `apps/*/capacitor.config.ts` antes da primeira publicação: o ID não muda depois.
- **Servidor:** segredos só no Cloudflare (`wrangler secret put`). Os apps falam apenas com `/saude` e rotas do Vênus.
