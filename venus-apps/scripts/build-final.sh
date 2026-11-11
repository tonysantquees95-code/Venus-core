#!/usr/bin/env bash
# Build final da Vetorial Tech: verifica tudo e gera PWA, APK corporativo (Capacitor) e app Windows (Electron).
# Uso: scripts/build-final.sh [pwa|apk|windows|tudo] [app]   (padrão: tudo, dois-em-um)
# APK release assinado: KEYSTORE_ARQUIVO, KEYSTORE_SENHA, CHAVE_ALIAS, CHAVE_SENHA (nunca grave no repositório).
set -euo pipefail
ALVO="${1:-tudo}"; APP="${2:-dois-em-um}"; TIPO="${TIPO_APK:-release}"
cd "$(dirname "$0")/.."
echo "→ Instalando e verificando (isolamento, chaves, testes, tipos, web)"
npm ci
npm run verificar
if [[ "$ALVO" == "pwa" || "$ALVO" == "tudo" ]]; then
  echo "→ PWA em apps/$APP/dist (publique em qualquer hospedagem estática, ex.: Cloudflare Pages; funciona no iPhone via 'Adicionar à Tela de Início')"
  npm run build:web -w "@venus/$APP"
fi
if [[ "$ALVO" == "apk" || "$ALVO" == "tudo" ]]; then
  [ -d "apps/$APP/android" ] || { echo "→ Criando projeto Android (Capacitor)"; npm run android:add -w "@venus/$APP"; }
  echo "→ APK $TIPO"
  npm run "apk:$TIPO" -w "@venus/$APP"
  echo "APK: $(find "apps/$APP/android/app/build/outputs/apk" -name '*.apk' | head -1)"
fi
if [[ "$ALVO" == "windows" || "$ALVO" == "tudo" ]]; then
  if [[ "${OS:-}" == "Windows_NT" || "$ALVO" == "windows" ]]; then
    echo "→ App Windows (Electron) — rode no Windows ou use o workflow 'Apps web e APK'"
    npm run build:desktop -w "@venus/dois-em-um"
    (cd desktop && npm install --no-audit --no-fund && npm run build:win)
  else
    echo "→ App Windows: pulado (precisa do Windows). O workflow do GitHub gera o instalador."
  fi
fi
