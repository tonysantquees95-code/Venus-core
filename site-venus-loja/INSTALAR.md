# Vênus da Loja: instalação na Floot (amanhã)

Código pronto aqui; o cérebro passou em 45 testes (teste/t.ts, `node --experimental-strip-types teste/t.ts`).

## Ordem (≈ 12–15 ações)
1. read_files: pages/admin.pedidos.tsx (+ .pageLayout.tsx se houver), components/AdminNav.tsx → ver padrão de proteção e menu.
2. write_file helpers/venusLoja.tsx
3. write_file components/VenusLoja.tsx + components/VenusLoja.module.css
4. write_file pages/admin.venus.tsx (ajustar ao padrão do passo 1) + .module.css (+ .pageLayout.tsx igual ao das outras páginas admin)
5. edit_file components/AdminNav.tsx: link "Vênus" → /admin/venus
6. typecheck → corrigir
7. Conta de administradora da esposa: execute_sql: conferir se existe conta com nataliadarosacarvalho@gmail.com e mudar role para admin (se não existir, pedir para ela se cadastrar no site)
8. Pendentes de hoje: ajustes do orbe (rotulo z-index, raiz spacing-6), typecheck, checkpoint, publicar após OK.

## Observações
- Usa as funções do painel que já existem: /_api/admin/orders, products, stock_update (mode set), product_save (produto inteiro), order_update.
- Voz: SpeechRecognition do Safari (pt-BR) + speechSynthesis. Se o microfone não aparecer, o ditado do teclado do iPhone funciona.
- Na tela inicial do iPhone: Safari → Compartilhar → Adicionar à Tela de Início, abrindo em /admin/venus.
- Avisos push no iPhone: verificar o que a Floot permite (sem service worker próprio).
