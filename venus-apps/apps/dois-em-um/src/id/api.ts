import { URL_API } from "@venus/nucleo";

/** Cliente do Vetorial ID (servidor da Vênus). Sem segredos: só o token da sessão do próprio usuário. */
export class ErroId extends Error {
  constructor(
    public status: number,
    public codigo: string,
    mensagem: string,
    public dados: Record<string, any> = {}
  ) {
    super(mensagem);
  }
}

export interface Aparelho {
  id: string;
  nome: string;
  plataforma: Plataforma;
}
export type Plataforma = "android" | "ios" | "windows" | "mac" | "web";

export interface Sessao {
  token: string;
  sessao: string;
  /** Selo de licença assinado que veio junto da entrada (guardado à parte). */
  selo?: string;
  usuario: { id: string; email: string; nome: string };
}

export interface AparelhoDaConta {
  id: string;
  nome: string;
  plataforma: Plataforma;
  criado: number;
  ultimo: number;
  atual: boolean;
  ativo: boolean;
  sessao: string | null;
}
export interface ListaDeAparelhos {
  maxDispositivos: number;
  maxAtivas: number;
  dispositivos: AparelhoDaConta[];
}
export interface DocSync {
  chave: string;
  valor: any;
  versao: number;
  atualizado: number;
  deEsteAparelho: boolean;
}
export interface Perfil {
  id: string;
  email: string;
  nome: string;
  foto: string;
  atualizado: number;
}
export interface Licenca {
  acesso: boolean;
  plano?: string;
  renovar?: boolean;
  diasDeTesteRestantes?: number;
  diasPagosRestantes?: number;
  emTeste?: boolean;
  cobranca?: { url: string; valorCentavos: number; pacote?: string; forma?: "recorrente" | "avulso" };
  pacote?: string;
  /** Os planos à venda (preço em centavos), como o servidor entrega. */
  pacotes?: { chave: string; nome: string; precoCentavos: number; precoCheioCentavos?: number; promocao?: boolean }[];
  /** Promoção de lançamento aberta (fim em ms): a suíte completa com preço mantido por alguns meses. */
  promocao?: { fim: number; meses: number; pacote: string; precoCentavos: number } | null;
  selo?: string;
  fimDoAcesso?: number;
}

/** Vitrine online da loja (vetorial.tech/<loja>): o que o servidor guarda e entrega. */
export interface VitrineInfo { url: string; titulo: string; descricao: string; cor: string; logo: string; whatsapp: string; itensDoApp: number }
export interface ItemVitrine { sku: string; nome: string; preco_centavos: number | null; promocao_centavos: number | null; estoque: number | null; categoria: string; imagem: string; descricao: string }

export function criarApi(base: string = URL_API, f: typeof fetch = (...a) => fetch(...a)) {
  async function chamar<T>(metodo: string, caminho: string, corpo?: unknown, token?: string): Promise<T> {
    let r: Response;
    try {
      r = await f(`${base}${caminho}`, {
        method: metodo,
        headers: { ...(corpo !== undefined ? { "content-type": "application/json" } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
        body: corpo !== undefined ? JSON.stringify(corpo) : undefined
      });
    } catch {
      throw new ErroId(0, "sem_conexao", "Sem conexão com o servidor.");
    }
    const d = await r.json().catch(() => ({}));
    if (!r.ok) throw new ErroId(r.status, d.codigo ?? "erro", d.error ?? "Algo deu errado. Tente de novo.", d);
    return d as T;
  }
  return {
    entrar: (email: string, senha: string, dispositivo: Aparelho, extra: { encerrar?: string; remover?: string } = {}) =>
      chamar<Sessao>("POST", "/id/entrar", { email, senha, dispositivo, ...extra }),
    cadastrar: (email: string, senha: string, nome: string, telefone: string, dispositivo: Aparelho) =>
      chamar<Sessao>("POST", "/id/cadastrar", { email, senha, nome, telefone, dispositivo }),
    recuperarCanais: () => chamar<{ canais: { email: boolean; sms: boolean } }>("GET", "/id/recuperar/canais"),
    recuperarPedir: (email: string, canal: "email" | "sms") => chamar<{ ok: boolean; mensagem: string }>("POST", "/id/recuperar/pedir", { email, canal }),
    recuperarConfirmar: (email: string, codigo: string, novaSenha: string) => chamar<{ ok: boolean }>("POST", "/id/recuperar/confirmar", { email, codigo, novaSenha }),
    salvarTelefone: (t: string, telefone: string) => chamar<{ ok: boolean }>("PUT", "/id/telefone", { telefone }, t),
    ping: (t: string) => chamar<{ ok: boolean; perfil: number; selo?: string; licenca: { acesso: boolean; renovar: boolean } }>("POST", "/id/ping", {}, t),
    conta: (t: string) => chamar<{ usuario: Perfil; licenca: Licenca; sessao: string; dispositivo: string }>("GET", "/id/conta", undefined, t),
    salvarPerfil: (t: string, p: { nome?: string; foto?: string }) => chamar<{ usuario: Perfil }>("PUT", "/id/perfil", p, t),
    tokenDeApp: (t: string, app: string) => chamar<{ app: string; token: string }>("POST", "/id/app-token", { app }, t),
    renovar: (t: string) => chamar<{ token: string }>("POST", "/id/renovar", {}, t),
    reativar: (t: string, encerrar: string) => chamar<{ ok: boolean }>("POST", "/id/reativar", { encerrar }, t),
    sair: (t: string) => chamar<{ ok: boolean }>("POST", "/id/sair", {}, t),
    dispositivos: (t: string) => chamar<ListaDeAparelhos>("GET", "/id/dispositivos", undefined, t),
    removerDispositivo: (t: string, id: string) => chamar<{ ok: boolean }>("DELETE", `/id/dispositivos/${id}`, undefined, t),
    encerrarSessao: (t: string, sessao: string) => chamar<{ ok: boolean }>("POST", `/id/sessoes/${sessao}/encerrar`, {}, t),
    licenca: (t: string, gerar = false, escolha?: { pacote: string; forma: "recorrente" | "avulso" }) =>
      chamar<Licenca>("GET", `/id/licenca${gerar ? `?gerar=1${escolha ? `&pacote=${encodeURIComponent(escolha.pacote)}&forma=${escolha.forma}` : ""}` : ""}`, undefined, t),
    vitrine: (t: string) => chamar<VitrineInfo>("GET", "/loja/vitrine", undefined, t),
    salvarVitrine: (t: string, d: { titulo?: string; descricao?: string; cor?: string; whatsapp?: string }) => chamar<{ ok: boolean; url: string }>("PUT", "/loja/vitrine", d, t),
    itensDaVitrine: (t: string) => chamar<{ url: string; produtos: ItemVitrine[] }>("GET", "/loja/vitrine/itens", undefined, t),
    salvarItens: (t: string, produtos: Partial<ItemVitrine>[]) => chamar<{ ok: boolean; itens: number; url: string }>("PUT", "/loja/vitrine/itens", { produtos }, t),
    fotoDaVitrine: (t: string, sku: string, dados: string) => chamar<{ ok: boolean; url: string }>("PUT", "/loja/vitrine/foto", { sku, dados }, t),
    lerSync: (t: string, desde: number) => chamar<{ agora: number; docs: DocSync[] }>("GET", `/id/sync?desde=${desde}`, undefined, t),
    gravarSync: (t: string, chave: string, valor: unknown, versao: number) =>
      chamar<{ versao: number; atualizado: number }>("PUT", `/id/sync/${chave}`, { valor, versao }, t)
  };
}
export type ApiId = ReturnType<typeof criarApi>;

/** Endereço do login/entrada única do Vetorial ID para outro app da suíte (Vindex, Vortex, portais). */
export function enderecoDoLogin(app: string, voltar: string, base: string = URL_API, silencioso = true): string {
  return `${base}/id${silencioso ? "/sso" : ""}?app=${encodeURIComponent(app)}&voltar=${encodeURIComponent(voltar)}`;
}
