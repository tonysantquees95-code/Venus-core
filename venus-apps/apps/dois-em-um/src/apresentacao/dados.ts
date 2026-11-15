// Primeira abertura (tour → permissões explicadas → termos) e "O que há de novo" da Vênus no Windows e na web.
// REGRA: toda mudança que altere o que o app faz ou como se usa ganha uma linha em NOVIDADES, com id = o maior id + 1
// (e a mesma linha em `app/src/main/java/com/tonimarques/sextafeira/Novidades.kt`, para o Android).
// Mudou o texto dos termos: suba VERSAO_DOS_TERMOS e a pessoa aceita de novo.
export const VERSAO_DOS_TERMOS = 1;
const CHAVE_TERMOS = "venus.termos";
const CHAVE_TOUR = "venus.tour";
const CHAVE_NOVIDADES = "venus.novidades.vistas";

export interface PassoDoTour { icone: string; titulo: string; texto: string; como?: string[] }
export type TipoNovidade = "novo" | "melhoria" | "correcao";
export interface Novidade { id: number; data: string; tipo: TipoNovidade; titulo: string; texto: string }

export const TOUR: PassoDoTour[] = [
  { icone: "✨", titulo: "Bem-vinda à Vênus", texto: "A assistente da sua loja: conversa com você, cuida do robô de atendimento e ajuda a vender.", como: ["Passe as telas para conhecer as funções.", "Você pode rever esta apresentação em Ajustes › Sobre a Vênus."] },
  { icone: "💬", titulo: "Chat", texto: "Peça o que precisar: preços, estoque, ideias de post, contas, lembretes. Dá para falar em voz alta ou digitar.", como: ["Toque no microfone para falar.", "As conversas ficam na gaveta à esquerda."] },
  { icone: "🤖", titulo: "Robô de atendimento", texto: "O robô responde os clientes no WhatsApp Business com as informações da sua loja, e você acompanha tudo.", como: ["Ligue e desligue o robô na aba Robô.", "Você continua responsável pelo que a loja envia."] },
  { icone: "🏪", titulo: "Minha loja", texto: "Produtos, vendas, clientes, canais de venda e marketing num lugar só.", como: ["Conecte a sua loja, o Mercado Pago e a Meta em Ajustes.", "As chaves ficam guardadas no seu aparelho."] },
  { icone: "🎨", titulo: "Seu jeito", texto: "Mude cores, orbe e ícones; tudo acompanha você nos outros aparelhos.", como: ["Em Ajustes › Tema, cores e orbe."] },
  { icone: "🔄", titulo: "Sempre atualizada", texto: "A Vênus se atualiza pelo canal oficial e mostra o que mudou depois de cada atualização.", como: ["Em Ajustes você confere se há versão nova."] },
];

export const PERMISSOES: { icone: string; titulo: string; texto: string }[] = [
  { icone: "🌐", titulo: "Internet", texto: "Para falar com a inteligência artificial, a sua loja e o seu Vetorial ID." },
  { icone: "🎤", titulo: "Microfone (opcional)", texto: "Só quando você toca no microfone para falar com a Vênus. O áudio é usado para virar texto e não fica guardado." },
  { icone: "🔔", titulo: "Notificações (opcional)", texto: "Para avisar de pedidos, estoque baixo e lembretes. Você liga e desliga em Ajustes." },
];

export const TERMOS: { titulo: string; texto: string }[] = [
  { titulo: "1. O que é", texto: "A Vênus está em teste (beta): uma versão em desenvolvimento, oferecida a lojistas convidados para testar e dar sugestões. Pode ter falhas, mudar e ficar fora do ar." },
  { titulo: "2. Suas chaves e contas", texto: "A Vênus funciona com as suas próprias chaves e contas: inteligência artificial, cobrança (Mercado Pago, InfinitePay) e Meta (Instagram, WhatsApp). Os limites, as regras e as eventuais tarifas dessas empresas valem para você." },
  { titulo: "3. Seus dados", texto: "Produtos, vendas, clientes e conversas ficam guardados no seu aparelho, e as chaves ficam cifradas. Para entender e responder, a Vênus envia o texto necessário aos serviços de inteligência artificial que você configurou. Nada é vendido nem compartilhado para outros fins." },
  { titulo: "4. Robô e mensagens", texto: "O robô responde em nome da sua loja com as informações que você cadastrou. Você continua responsável pelo que a sua loja envia aos clientes." },
  { titulo: "5. Confira antes de confirmar", texto: "Podem acontecer respostas erradas. Confira preços, estoque, cobranças e publicações antes de confirmar. A Vênus é oferecida como está, sem garantias." },
  { titulo: "6. Atualizações e saída", texto: "A Vênus se atualiza pelo canal oficial; recursos podem mudar, melhorar ou sair. Você pode parar de usar quando quiser. Suas contas nas outras empresas continuam suas." },
  { titulo: "7. Mudanças e contato", texto: "Estes termos podem mudar: quando mudarem, a Vênus pede que você aceite de novo. Dúvidas e sugestões: fale com Toni Marques, pelo mesmo contato do convite." },
];

export const NOVIDADES: Novidade[] = [
  { id: 1, data: "2026-11-14", tipo: "novo", titulo: "Apresentação e novidades", texto: "Na primeira abertura a Vênus mostra as funções, explica o que usa do aparelho e pede o aceite dos termos. Depois de cada atualização, esta tela conta o que mudou." },
  { id: 2, data: "2026-11-14", tipo: "melhoria", titulo: "Engrenagem nos Ajustes", texto: "A engrenagem de Ajustes agora é uma engrenagem de verdade, não mais parecida com um sol." },
  { id: 3, data: "2026-11-15", tipo: "melhoria", titulo: "Conversa com mais personalidade", texto: "Em papo solto (como você está, tudo bem, só queria conversar) a Vênus agora responde de verdade, lendo a conversa, em vez de repetir a mesma resposta. Ela reage ao que você disse e puxa o assunto quando faz sentido, com mais humor e jeito próprio." },
  { id: 4, data: "2026-11-15", tipo: "novo", titulo: "Ouvir, compartilhar e guardar", texto: "Embaixo de cada resposta da Vênus há três botões: Ouvir (com pausa e continuar), Compartilhar e Guardar o texto." },
  { id: 5, data: "2026-11-15", tipo: "melhoria", titulo: "Só fala quando você fala", texto: "Se você digita, a Vênus responde só por escrito. Ela responde em voz alta quando você pediu falando. Para ouvir uma resposta escrita, use o botão Ouvir." },
];

export const ROTULO: Record<TipoNovidade, string> = { novo: "Novo", melhoria: "Melhorou", correcao: "Corrigido" };
export const ultimoId = (lista: Novidade[] = NOVIDADES): number => lista.reduce((m, n) => Math.max(m, n.id), 0);
/** O que a pessoa ainda não viu, da mais nova para a mais antiga. */
export const novasDesde = (visto: number, lista: Novidade[] = NOVIDADES): Novidade[] => lista.filter((n) => n.id > visto).sort((a, b) => b.id - a.id);

/** null = nunca guardou (primeira instalação: a apresentação já explica tudo, então não mostra o histórico). */
export function lerVisto(): number | null {
  try { const v = localStorage.getItem(CHAVE_NOVIDADES); if (v === null) return null; const n = Number(v); return Number.isInteger(n) && n >= 0 ? n : null; } catch { return null; }
}
export function marcarVisto(id: number = ultimoId()): void { try { localStorage.setItem(CHAVE_NOVIDADES, String(id)); } catch { /* mostra de novo na próxima */ } }
export const termosAceitos = (): boolean => { try { return localStorage.getItem(CHAVE_TERMOS) === String(VERSAO_DOS_TERMOS); } catch { return false; } };
export const aceitarTermos = (): void => { try { localStorage.setItem(CHAVE_TERMOS, String(VERSAO_DOS_TERMOS)); } catch { /* pede de novo */ } };
export const tourVisto = (): boolean => { try { return localStorage.getItem(CHAVE_TOUR) === "1"; } catch { return false; } };
export const marcarTourVisto = (): void => { try { localStorage.setItem(CHAVE_TOUR, "1"); } catch { /* ok */ } };
/** Precisa mostrar a apresentação (tour + permissões + termos) antes de usar? */
export const precisaApresentar = (): boolean => !termosAceitos() || !tourVisto();
