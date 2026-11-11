#!/usr/bin/env bash
# Build final da Vetorial Tech: verifica tudo, gera o PWA e o APK corporativo (Capacitor) do app escolhido.
# Uso: scripts/build-final.sh [assistente|robo|dois-em-um] [debug|release]   (padrão: dois-em-um release)
# Release assinado: defina KEYSTORE_ARQUIVO, KEYSTORE_SENHA, CHAVE_ALIAS, CHAVE_SENHA (nunca grave no repositório).
set -euo pipefail
APP="${1:-dois-em-um}"; TIPO="${2:-release}"
cd "$(dirname "$0")/.."
echo "→ Instalando e verificando (isolamento, chaves, testes, tipos, web)"
npm ci
npm run verificar
echo "→ PWA em apps/$APP/dist (publique em qualquer hospedagem estática, ex.: Cloudflare Pages)"
npm run build:web -w "@venus/$APP"
if [ ! -d "apps/$APP/android" ]; then
  echo "→ Criando projeto Android (Capacitor)"; npm run android:add -w "@venus/$APP"
fi
echo "→ APK $TIPO"
npm run "apk:$TIPO" -w "@venus/$APP"
SAIDA=$(find "apps/$APP/android/app/build/outputs/apk" -name '*.apk' | head -1)
echo "Pronto: ${SAIDA:-APK não encontrado}"
