# Vênus assistente

Assistente pessoal para Android com o Gemini como cérebro. Conversa por texto ou voz e faz ações no celular.

## Instalar
1. Abra a aba **Releases** deste repositório (ou `…/releases/latest`).
2. Baixe **Venus.apk** e toque nele.
3. Se o Android avisar, permita instalar apps desta fonte (o navegador).
4. Na primeira vez, cole sua chave grátis do Gemini (criada em aistudio.google.com/apikey).

Cada alteração enviada para este repositório gera um APK novo automaticamente (aba **Actions**).
O APK novo instala por cima do antigo, sem perder a chave.

## O que ela faz
- Abrir qualquer app instalado
- Tocar música no Spotify, YouTube Music ou Deezer; pausar, pular e mudar o volume de qualquer player
- Mensagens no WhatsApp e SMS para contatos salvos (você toca em Enviar)
- Abrir o discador com o número de um contato
- Lanterna, alarmes e timers
- Sites, pesquisas no Google, mapas e navegação GPS
- Câmera e telas de configurações (Wi-Fi, Bluetooth, volume, bateria…)

## Usar com voz
- Toque no microfone, ou segure o ícone do app e escolha **Falar**.
- Com **Conversa contínua** ligada (menu ⋮), ela volta a ouvir depois de responder. Fique em silêncio para parar.

## Limites do Android
- O Android não deixa apps enviarem mensagens do WhatsApp nem ligarem sozinhos, por segurança. A Vênus deixa tudo pronto e você toca em Enviar ou Ligar.
- Apps não podem ligar ou desligar Wi-Fi e Bluetooth; ela abre o painel para você tocar.

## Código
- `MainActivity.kt`: tela de chat, voz e menu.
- `Gemini.kt`: conversa com a API do Gemini (function calling).
- `Ferramentas.kt`: as habilidades. Para criar uma nova, siga o comentário no topo do arquivo.
