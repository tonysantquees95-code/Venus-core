# Vênus (monorepo)

Assistente pessoal e comercial, **Vênus Robô** e servidor. Nada de loja ou site neste repositório.

```
apps/assistente   Vênus (web, PWA e APK)
apps/robo         Vênus Robô (web, PWA e APK)
apps/dois-em-um  Dois em Um: chat + painel do Robô na mesma interface (React + Tailwind)
apps/painel-dev   Painel Dev da Vetorial Tech (administrador): projetos, BI, tráfego e diagnóstico; web/PWA e APK Dev
packages/nucleo   código compartilhado (API, utilitários)
packages/build-config  Vite compartilhado (modos web, PWA, capacitor)
scripts           verificação de isolamento
```

## Comandos
| Objetivo | Comando |
|---|---|
| Instalar | `npm install` |
| Desenvolver | `npm run dev:assistente` / `npm run dev:robo` |
| Web + PWA | `npm run build:web` (saída em `apps/*/dist`) |
| APK de teste | `npm run apk:debug` (precisa de Java 21 e Android SDK) |
| Criar projeto Android (1ª vez) | `npm run android:add` |
| Verificar tudo | `npm run verificar` |

Modos de build: `vite build` gera web+PWA (service worker, manifest); `vite build --mode capacitor` gera o pacote do APK (`base ./`, sem service worker).

A pasta `android/` é gerada e ignorada pelo Git. Chave de assinatura e segredos nunca entram no repositório.

## Backend
O servidor (roteador de IA, avisos, atualização) fica em `../servidor` neste repositório (Venus-core).
