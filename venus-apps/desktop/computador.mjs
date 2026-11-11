// Controle do computador pela Vênus: entende o pedido (texto ou voz) e devolve uma AÇÃO de uma lista fixa.
// Nada aqui executa comando livre: o que vem do usuário só escolhe entre alternativas conhecidas (ou vira o termo de uma busca).

const semAcento = (t) => t.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();

/** Programas que a Vênus sabe abrir. `iniciar` é sempre texto nosso, nunca do usuário. */
export const PROGRAMAS = {
  "bloco de notas": { nome: "o Bloco de Notas", exe: "notepad.exe" },
  "calculadora": { nome: "a Calculadora", exe: "calc.exe" },
  "paint": { nome: "o Paint", exe: "mspaint.exe" },
  "gerenciador de tarefas": { nome: "o Gerenciador de Tarefas", exe: "taskmgr.exe" },
  "explorador": { nome: "o Explorador de Arquivos", exe: "explorer.exe" },
  "arquivos": { nome: "o Explorador de Arquivos", exe: "explorer.exe" },
  "navegador": { nome: "o navegador", url: "https://www.google.com" },
  "chrome": { nome: "o Chrome", iniciar: "chrome" },
  "edge": { nome: "o Edge", iniciar: "msedge" },
  "word": { nome: "o Word", iniciar: "winword" },
  "excel": { nome: "o Excel", iniciar: "excel" },
  "powerpoint": { nome: "o PowerPoint", iniciar: "powerpnt" },
  "whatsapp": { nome: "o WhatsApp", url: "whatsapp://" },
  "spotify": { nome: "o Spotify", url: "spotify:" },
  "recorte": { nome: "a captura de tela", url: "ms-screenclip:" },
  "captura de tela": { nome: "a captura de tela", url: "ms-screenclip:" },
  "print": { nome: "a captura de tela", url: "ms-screenclip:" },
};

const SITES = {
  youtube: "https://www.youtube.com", gmail: "https://mail.google.com", instagram: "https://www.instagram.com", facebook: "https://www.facebook.com",
  "mercado livre": "https://www.mercadolivre.com.br", "mercado pago": "https://www.mercadopago.com.br", github: "https://github.com", cloudflare: "https://dash.cloudflare.com",
  "google drive": "https://drive.google.com", drive: "https://drive.google.com", maps: "https://maps.google.com", "google maps": "https://maps.google.com", whatsapp: "https://web.whatsapp.com",
};

const CONFIGURACOES = {
  "wi-fi": "ms-settings:network-wifi", wifi: "ms-settings:network-wifi", "wi fi": "ms-settings:network-wifi", internet: "ms-settings:network", rede: "ms-settings:network",
  bluetooth: "ms-settings:bluetooth", som: "ms-settings:sound", audio: "ms-settings:sound", volume: "ms-settings:sound", tela: "ms-settings:display", video: "ms-settings:display",
  brilho: "ms-settings:display", bateria: "ms-settings:batterysaver", energia: "ms-settings:powersleep", aplicativos: "ms-settings:appsfeatures", programas: "ms-settings:appsfeatures",
  atualizacao: "ms-settings:windowsupdate", atualizacoes: "ms-settings:windowsupdate", privacidade: "ms-settings:privacy", impressoras: "ms-settings:printers",
  armazenamento: "ms-settings:storagesense", personalizacao: "ms-settings:personalization", "plano de fundo": "ms-settings:personalization-background", horario: "ms-settings:dateandtime", data: "ms-settings:dateandtime",
  teclado: "ms-settings:typing", mouse: "ms-settings:mousetouchpad", notificacoes: "ms-settings:notifications",
};

const PASTAS = {
  downloads: "shell:Downloads", baixados: "shell:Downloads", documentos: "shell:Personal", imagens: "shell:My Pictures", fotos: "shell:My Pictures",
  "area de trabalho": "shell:Desktop", desktop: "shell:Desktop", musicas: "shell:My Music", videos: "shell:My Video", lixeira: "shell:RecycleBinFolder",
};

/** Ações que desligam ou fecham a sessão: só depois de um "sim". */
const PERIGOSAS = {
  desligar: { aviso: "desligar o computador", feito: "Desligando o computador em 30 segundos. Diga “cancelar desligamento” se foi sem querer." },
  reiniciar: { aviso: "reiniciar o computador", feito: "Reiniciando em 30 segundos. Diga “cancelar desligamento” para desistir." },
  suspender: { aviso: "colocar o computador para dormir", feito: "Suspendendo o computador." },
  "sair-da-conta": { aviso: "sair da sua conta do Windows", feito: "Saindo da conta do Windows." },
};

const limpar = (t) =>
  semAcento(t)
    .replace(/[?!,;:]+/g, " ")
    .replace(/\.+(\s|$)/g, " ") // ponto final cai, mas "site.com.br" fica
    .replace(/^(oi |ola |ei |e |entao |bom |ok )?(venus|vênus)[ ,]+/, "")
    .replace(/\b(por favor|pf|pfv|pra mim|para mim|agora|ai|ae)\b/g, " ")
    .replace(/^(voce )?(pode|consegue|poderia|podia|quer)( voce)? /, "")
    .replace(/\s+/g, " ")
    .trim();

function achar(tabela, texto) {
  // a chave mais longa que aparece inteira no texto (palavras inteiras)
  const chaves = Object.keys(tabela).sort((a, b) => b.length - a.length);
  return chaves.find((k) => new RegExp(`(^| )${k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}( |$)`).test(texto));
}

const dominio = /^(https?:\/\/)?([a-z0-9-]+\.)+[a-z]{2,}(\/\S*)?$/i;

/**
 * Entende o pedido. Devolve null quando não é um comando do computador (aí vai para a conversa normal).
 * { acao: "programa"|"site"|"pasta"|"configuracoes"|"busca"|"volume"|"bloquear"|"perigosa"|"cancelar-desligamento", ... }
 */
export function interpretar(textoBruto) {
  const t = limpar(String(textoBruto ?? "").slice(0, 300));
  if (!t) return null;

  if (/^cancel(ar|a) (o )?(desligamento|reinicio|reiniciar|desligar)/.test(t)) return { acao: "cancelar-desligamento" };
  if (/^(bloque(ar|ia)|trav(ar|a)) (a |o )?(tela|computador|pc|windows)$|^bloquear$/.test(t)) return { acao: "bloquear" };
  if (/^(desligar|desliga|apagar) (o |a )?(computador|pc|maquina|notebook|windows)$|^desligar$/.test(t)) return { acao: "perigosa", tipo: "desligar" };
  if (/^(reiniciar|reinicia) (o |a )?(computador|pc|maquina|notebook|windows)$|^reiniciar$/.test(t)) return { acao: "perigosa", tipo: "reiniciar" };
  if (/^(suspender|suspende|hibernar|dormir|colocar) ?(o |a )?(computador|pc|notebook)?( para dormir)?$/.test(t) && /(suspend|hibern|dormir)/.test(t)) return { acao: "perigosa", tipo: "suspender" };
  if (/^(sair|deslogar|fazer logoff|logoff)( d[ao] (minha )?(conta|windows|usuario))?$/.test(t) && !/vetorial|venus/.test(t)) return { acao: "perigosa", tipo: "sair-da-conta" };

  if (/^(aumentar|aumenta|subir|sobe|mais) (o )?volume|^volume (mais|maior|alto)/.test(t)) return { acao: "volume", direcao: "mais" };
  if (/^(diminuir|diminui|abaixar|abaixa|baixar|baixa|menos) (o )?volume|^volume (menos|menor|baixo)/.test(t)) return { acao: "volume", direcao: "menos" };
  if (/^(silenciar|silencia|mutar|muta|tirar o som|desativar o som)|^(ativar|ligar) o mudo|^volume mudo/.test(t)) return { acao: "volume", direcao: "mudo" };

  const pesquisa = t.match(/^(pesquisar|pesquisa|procurar|procura|buscar|busca)( no google| na internet| na web)? (por |sobre )?(.{2,})$/);
  if (pesquisa) {
    // o termo da busca mantém os acentos que a pessoa falou
    const original = String(textoBruto).match(/(?:pesquisar|pesquisa|procurar|procura|buscar|busca)(?: no google| na internet| na web)? (?:por |sobre )?(.{2,})$/i);
    return { acao: "busca", termo: (original?.[1] ?? pesquisa[4]).replace(/[?!.\s]+$/, "").trim() };
  }

  const abrir = t.match(/^(abrir|abre|abra|iniciar|inicia|executar|executa|ir para|vai para|va para|acessar|acessa|mostrar|mostra|mostre) (o |a |os |as |um |uma )?(.+)$/);
  if (!abrir) return null;
  const alvo = abrir[3].trim();

  const conf = alvo.match(/^(as |a |o )?(configuracoes?|ajustes) ?(d[eoa]s? |do |da )?(.*)$/);
  if (conf) {
    const chave = achar(CONFIGURACOES, conf[4] || "");
    if (conf[4] && !chave && /venus|vetorial/.test(conf[4])) return null; // ajustes da própria Vênus ficam no app
    return { acao: "configuracoes", chave: chave ?? "geral" };
  }
  const pasta = achar(PASTAS, alvo.replace(/^(a )?(pasta )?(de |do |da )?/, ""));
  if (pasta && /^(a |o )?(pasta|lixeira|downloads|documentos|imagens|fotos|area de trabalho|desktop|musicas|videos|baixados)/.test(alvo)) return { acao: "pasta", chave: pasta };
  const prog = achar(PROGRAMAS, alvo);
  if (prog && alvo.length <= prog.length + 12) return { acao: "programa", chave: prog };
  const site = achar(SITES, alvo.replace(/^(o )?(site |pagina )?(d[eoa] )?/, ""));
  if (site) return { acao: "site", chave: site };
  const enderecoPuro = alvo.replace(/^(o )?(site |pagina )?/, "");
  if (dominio.test(enderecoPuro)) return { acao: "site", url: /^https?:\/\//i.test(enderecoPuro) ? enderecoPuro : `https://${enderecoPuro}` };
  return { acao: "desconhecido", alvo };
}

/** Só endereços web comuns (nada de file:, javascript:, etc.). */
export function urlSegura(u) {
  try {
    const x = new URL(u);
    return (x.protocol === "https:" || x.protocol === "http:") && !!x.hostname.includes(".") ? x.toString() : null;
  } catch {
    return null;
  }
}

const POWERSHELL_TECLA = { mais: 175, menos: 174, mudo: 173 };

/**
 * Transforma a ação em algo que o main.js executa: { tipo: "processo", arquivo, args } | { tipo: "url", url } | null.
 * Tudo vem de tabelas nossas; o único texto do usuário que entra é o termo da busca, já codificado na URL.
 */
export function montarExecucao(a) {
  switch (a.acao) {
    case "programa": {
      const p = PROGRAMAS[a.chave];
      if (!p) return null;
      if (p.exe) return { tipo: "processo", arquivo: p.exe, args: [] };
      if (p.iniciar) return { tipo: "processo", arquivo: "cmd.exe", args: ["/c", "start", "", p.iniciar] };
      return { tipo: "url", url: p.url, esquema: true };
    }
    case "site": {
      const u = a.url ?? SITES[a.chave];
      const seguro = u && urlSegura(u);
      return seguro ? { tipo: "url", url: seguro } : null;
    }
    case "pasta": return PASTAS[a.chave] ? { tipo: "processo", arquivo: "explorer.exe", args: [PASTAS[a.chave]] } : null;
    case "configuracoes": return { tipo: "url", url: CONFIGURACOES[a.chave] ?? "ms-settings:", esquema: true };
    case "busca": return { tipo: "url", url: `https://www.google.com/search?q=${encodeURIComponent(a.termo.slice(0, 200))}` };
    case "volume": {
      const tecla = POWERSHELL_TECLA[a.direcao];
      if (!tecla) return null;
      const vezes = a.direcao === "mudo" ? 1 : 5;
      return { tipo: "processo", arquivo: "powershell.exe", args: ["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", `$w=New-Object -ComObject WScript.Shell; 1..${vezes} | % { $w.SendKeys([char]${tecla}) }`] };
    }
    case "bloquear": return { tipo: "processo", arquivo: "rundll32.exe", args: ["user32.dll,LockWorkStation"] };
    case "cancelar-desligamento": return { tipo: "processo", arquivo: "shutdown.exe", args: ["/a"] };
    case "perigosa":
      switch (a.tipo) {
        case "desligar": return { tipo: "processo", arquivo: "shutdown.exe", args: ["/s", "/t", "30"] };
        case "reiniciar": return { tipo: "processo", arquivo: "shutdown.exe", args: ["/r", "/t", "30"] };
        case "suspender": return { tipo: "processo", arquivo: "rundll32.exe", args: ["powrprof.dll,SetSuspendState", "0,1,0"] };
        case "sair-da-conta": return { tipo: "processo", arquivo: "shutdown.exe", args: ["/l"] };
      }
      return null;
    default: return null;
  }
}

/** O que a Vênus responde depois de fazer. */
export function respostaDa(a) {
  switch (a.acao) {
    case "programa": return `Abrindo ${PROGRAMAS[a.chave]?.nome ?? a.chave}.`;
    case "site": return `Abrindo ${a.chave ? a.chave : a.url}.`;
    case "pasta": return `Abrindo a pasta ${a.chave}.`;
    case "configuracoes": return a.chave === "geral" ? "Abrindo as configurações do Windows." : `Abrindo as configurações de ${a.chave}.`;
    case "busca": return `Pesquisando “${a.termo}” no Google.`;
    case "volume": return a.direcao === "mudo" ? "Pronto, alternei o mudo." : a.direcao === "mais" ? "Aumentei o volume." : "Diminuí o volume.";
    case "bloquear": return "Bloqueando a tela.";
    case "cancelar-desligamento": return "Cancelei o desligamento.";
    case "perigosa": return PERIGOSAS[a.tipo]?.feito ?? "Feito.";
    case "desconhecido": return `Ainda não sei abrir “${a.alvo}” por aqui. Sei abrir: Bloco de Notas, Calculadora, Paint, Word, Excel, Chrome, Edge, WhatsApp, Spotify, pastas (Downloads, Documentos…), configurações do Windows e sites comuns.`;
    default: return "Feito.";
  }
}

export const perguntaDeConfirmacao = (tipo) => `Tem certeza que quer ${PERIGOSAS[tipo]?.aviso ?? "fazer isso"}? Responda “sim” para confirmar.`;
export const ehConfirmacao = (texto) => /^(sim|confirmo|confirmar|pode|pode sim|claro|isso|isso mesmo|yes)$/.test(limpar(String(texto ?? "")));
export const ehNegacao = (texto) => /^(nao|não|cancela|cancelar|deixa|deixa pra la|esquece)$/.test(limpar(String(texto ?? "")));
