# Vênus da Loja: instalação na Floot (amanhã)

Código pronto aqui; o cérebro passou em 45 testes (teste/t.ts, `node --experimental-strip-types teste/t.ts`).

## Ordem (≈ 12–15 ações)
1. read_files: pages/admin.pedidos.tsx (+ .pageLayout.tsx se houver), components/AdminNav.tsx → ver padrão de proteção e menu.
2. write_file helpers/venusLoja.tsx
3. write_file components/VenusLoja.tsx + components/VenusLoja.module.css
4. write_file pages/admin.venus.tsx (ajustar ao padrão do passo 1) + .module.css (+ .pageLayout.tsx igual ao das outras páginas admin)
5. edit_file components/AdminNav.tsx: link "Vênus" → /admin/venus
6. typecheck → corrigir
7. Conta da Natália: há DUAS contas com e-mail errado: nataliadarosacarvalho@gmail.col e nataliadarosacarvalho@gmai.com. Ver qual tem o CPF/pedidos, trocar o e-mail dela para nataliadarosacarvalho@gmail.com, role admin, gerar código de recuperação se precisar; a outra: desativar só com OK do Toni.
8. Pendentes de hoje: ajustes do orbe (rotulo z-index, raiz spacing-6), typecheck, checkpoint, publicar após OK.

## Observações
- Usa as funções do painel que já existem: /_api/admin/orders, products, stock_update (mode set), product_save (produto inteiro), order_update.
- Voz: SpeechRecognition do Safari (pt-BR) + speechSynthesis. Se o microfone não aparecer, o ditado do teclado do iPhone funciona.
- Na tela inicial do iPhone: Safari → Compartilhar → Adicionar à Tela de Início, abrindo em /admin/venus.
- Avisos push no iPhone: verificar o que a Floot permite (sem service worker próprio).

## Pedido extra do Toni (31/10)
- Nome da loja no topo do site (SiteHeader) e onde mais o nome aparece em letra vazada (ex.: faixa grande "DESEJO DE INTIMIDADE"):
  DECIDIDO pelo Toni: deixar bem parecido com o LOGO — letra de neon vazada, traço bem mais grosso (-webkit-text-stroke ~2-3px),
  rosa neon vivo (igual ao do logo, magenta forte), e bem iluminado (várias camadas de text-shadow/drop-shadow rosa: 0 0 4px, 0 0 10px, 0 0 22px, 0 0 40px).
  Talvez preencher levemente o miolo com rosa translúcido. Conferir no computador e no celular; mostrar no preview antes de publicar.

## Pedido do Toni (31/10, 22h34): preço de custo e margem de lucro
- Site: coluna products.cost_cents (int, nulo) — SÓ no painel (admin products GET + product_save + formulário "Preço de custo"),
  NUNCA no catálogo público. Opcional: order_items.unit_cost_cents guardando o custo na hora da venda (lucro histórico certo).
- App Vênus (Kotlin) e Vênus da Loja (web): "o custo do gel é 15 reais" (com confirmação), "qual a margem do gel",
  "margem média dos produtos", "lucro deste mês / da semana", "produtos com menor margem"; campo custo no cadastro por voz.
- Mostrar margem (lucro ÷ preço) e markup (lucro ÷ custo); considerar desconto Pix de 5% e, se o Toni quiser, taxa do Mercado Pago.
- Decidido pelo Toni (22h45): frete é repassado ao cliente → NÃO entra como custo; mostrar só como informação ("frete cobrado: R$ X").
  Pix não tem taxa no Mercado Pago (só o desconto de 5% ao cliente, que já vem no total do pedido). Taxa do cartão: ajuste opcional em % no app (ele vai informar).
- Próximos (app): ler nota/print de compra com Gemini → custo unitário + somar ao estoque (com confirmação);
  trocar fundo da foto do produto (recorte no aparelho + fundos da loja; fundo livre via IA de imagem, pode recusar).
