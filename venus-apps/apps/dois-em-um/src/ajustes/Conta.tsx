import { ponteDesktop } from "../desktop/ponte";
import { useEffect, useState, type FormEvent } from "react";
import { ErroId, type AparelhoDaConta, type Licenca, type ListaDeAparelhos } from "../id/api";
import { useId } from "../id/IdProvider";
import { botaoPrimario, botaoSuave, campo } from "./Controles";
import { haQuantoTempo, NOMES_DE_PLATAFORMA } from "./logica";

const ICONE: Record<string, string> = { android: "🤖", ios: "📱", windows: "🖥️", mac: "💻", web: "🌐" };

/** Entrar ou criar conta, com o aviso inteligente de aparelhos (3 vinculados, 2 ativos). */
/** Recuperação de senha: pede o código (e-mail ou SMS), confirma e define a nova senha. */
function RecuperarSenha({ emailInicial, aoFechar }: { emailInicial: string; aoFechar: () => void }) {
  const { api } = useId();
  const [email, setEmail] = useState(emailInicial);
  const [canais, setCanais] = useState<{ email: boolean; sms: boolean } | null>(null);
  const [canal, setCanal] = useState<"email" | "sms">("email");
  const [enviado, setEnviado] = useState(false);
  const [codigo, setCodigo] = useState("");
  const [nova, setNova] = useState("");
  const [msg, setMsg] = useState("");
  const [pronto, setPronto] = useState(false);
  useEffect(() => { api.recuperarCanais().then((r) => { setCanais(r.canais); if (!r.canais.email && r.canais.sms) setCanal("sms"); }).catch(() => undefined); }, [api]);
  async function pedir(ev: FormEvent) {
    ev.preventDefault(); setMsg("");
    try { const r = await api.recuperarPedir(email, canal); setEnviado(true); setMsg(r.mensagem); } catch (e) { setMsg(e instanceof Error ? e.message : "Não consegui enviar."); }
  }
  async function confirmar(ev: FormEvent) {
    ev.preventDefault(); setMsg("");
    try { await api.recuperarConfirmar(email, codigo, nova); setPronto(true); } catch (e) { setMsg(e instanceof Error ? e.message : "Não deu certo."); }
  }
  return (
    <div role="dialog" aria-label="Recuperar senha" className="space-y-3 rounded-2xl border border-white/15 bg-white/10 p-4 text-sm">
      <p className="font-semibold">Recuperar a senha</p>
      {pronto ? (
        <>
          <p className="text-emerald-300">Senha trocada. Entre com a nova senha.</p>
          <button className={`${botaoPrimario} w-full`} onClick={aoFechar}>Voltar para o login</button>
        </>
      ) : !enviado ? (
        <form onSubmit={pedir} className="space-y-3">
          <input className={campo} type="email" required placeholder="E-mail da conta" value={email} onChange={(e) => setEmail(e.target.value)} />
          <div className="flex gap-2" role="radiogroup" aria-label="Como receber o código">
            {(["email", "sms"] as const).map((c) => (
              <button key={c} type="button" role="radio" aria-checked={canal === c} disabled={canais ? !canais[c] : false} onClick={() => setCanal(c)}
                className={`min-h-10 flex-1 rounded-xl text-sm font-semibold disabled:opacity-40 ${canal === c ? "bg-white/20" : "bg-black/30 text-suave"}`}>{c === "email" ? "Por e-mail" : "Por SMS"}</button>
            ))}
          </div>
          <button className={`${botaoPrimario} w-full`}>Enviar código</button>
        </form>
      ) : (
        <form onSubmit={confirmar} className="space-y-3">
          <input className={campo} inputMode="numeric" required maxLength={6} placeholder="Código de 6 dígitos" value={codigo} onChange={(e) => setCodigo(e.target.value)} autoComplete="one-time-code" />
          <input className={campo} type="password" required minLength={8} placeholder="Nova senha (8 ou mais caracteres)" value={nova} onChange={(e) => setNova(e.target.value)} autoComplete="new-password" />
          <button className={`${botaoPrimario} w-full`}>Trocar a senha</button>
        </form>
      )}
      {msg && !pronto && <p role="status" className="text-suave">{msg}</p>}
      {!pronto && <button type="button" className={`${botaoSuave} w-full`} onClick={aoFechar}>Cancelar</button>}
    </div>
  );
}

export function Entrar() {
  const { entrar, cadastrar, aviso, limparAviso } = useId();
  const [criar, setCriar] = useState(false);
  const [email, setEmail] = useState("");
  const [senha, setSenha] = useState("");
  const [nome, setNome] = useState("");
  const [telefone, setTelefone] = useState("");
  const [recuperando, setRecuperando] = useState(false);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const [escolha, setEscolha] = useState<{ tipo: "encerrar" | "remover"; itens: { id: string; titulo: string }[] } | null>(null);

  async function enviar(extra: { encerrar?: string; remover?: string } = {}) {
    setErro("");
    setOcupado(true);
    limparAviso();
    try {
      if (criar && !extra.encerrar && !extra.remover) await cadastrar(email, senha, nome, telefone);
      else await entrar(email, senha, extra);
      setEscolha(null);
    } catch (e) {
      if (e instanceof ErroId && e.codigo === "limite_simultaneo") {
        setCriar(false);
        setEscolha({ tipo: "encerrar", itens: (e.dados.ativas ?? []).map((a: any) => ({ id: a.sessao, titulo: `${a.nome} (${NOMES_DE_PLATAFORMA[a.plataforma as keyof typeof NOMES_DE_PLATAFORMA] ?? a.plataforma})` })) });
      } else if (e instanceof ErroId && e.codigo === "limite_dispositivos") {
        setCriar(false);
        setEscolha({ tipo: "remover", itens: (e.dados.dispositivos ?? []).map((d: any) => ({ id: d.id, titulo: `${d.nome} (${NOMES_DE_PLATAFORMA[d.plataforma as keyof typeof NOMES_DE_PLATAFORMA] ?? d.plataforma})` })) });
      } else setErro(e instanceof Error ? e.message : "Não deu certo.");
    }
    setOcupado(false);
  }
  const aoEnviar = (ev: FormEvent) => { ev.preventDefault(); void enviar(); };

  return (
    <div className="space-y-3">
      {aviso && <p role="alert" className="rounded-2xl bg-amber-400/15 px-4 py-3 text-sm text-amber-200">{aviso}</p>}
      <div className="flex gap-1 rounded-2xl bg-black/30 p-1" role="tablist">
        {[false, true].map((c) => (
          <button key={String(c)} role="tab" aria-selected={criar === c} onClick={() => setCriar(c)}
            className={`min-h-10 flex-1 rounded-xl text-sm font-semibold ${criar === c ? "bg-white/15 text-texto" : "text-suave"}`}>{c ? "Criar conta" : "Entrar"}</button>
        ))}
      </div>
      <form onSubmit={aoEnviar} className="space-y-3">
        {criar && <input className={campo} placeholder="Nome da loja ou o seu nome" value={nome} onChange={(e) => setNome(e.target.value)} autoComplete="organization" />}
        {criar && <input className={campo} type="tel" required placeholder="Celular com DDD (para recuperar a senha)" value={telefone} onChange={(e) => setTelefone(e.target.value)} autoComplete="tel" />}
        <input className={campo} type="email" required placeholder="E-mail" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        <input className={campo} type="password" required minLength={8} placeholder="Senha (8 ou mais caracteres)" value={senha} onChange={(e) => setSenha(e.target.value)} autoComplete={criar ? "new-password" : "current-password"} />
        <button className={`${botaoPrimario} w-full`} disabled={ocupado}>{criar ? "Criar conta" : "Entrar"}</button>
      </form>
      {erro && <p role="alert" className="text-sm text-rose-300">{erro}</p>}
      {!criar && <button type="button" className="w-full text-center text-sm text-suave underline" onClick={() => setRecuperando(true)}>Esqueci minha senha</button>}
      {recuperando && <RecuperarSenha emailInicial={email} aoFechar={() => setRecuperando(false)} />}
      {escolha && (
        <div role="alertdialog" className="rounded-2xl border border-white/15 bg-white/10 p-4 text-sm">
          <p className="font-semibold">
            {escolha.tipo === "encerrar" ? "Já há 2 aparelhos usando a conta agora. Encerre um para entrar aqui:" : "Você já tem 3 aparelhos vinculados. Remova um para vincular este:"}
          </p>
          <ul className="mt-3 space-y-2">
            {escolha.itens.map((i) => (
              <li key={i.id} className="flex items-center justify-between gap-2">
                <span>{i.titulo}</span>
                <button className={botaoSuave} onClick={() => enviar(escolha.tipo === "encerrar" ? { encerrar: i.id } : { remover: i.id })}>{escolha.tipo === "encerrar" ? "Encerrar" : "Remover"}</button>
              </li>
            ))}
          </ul>
          <button className={`${botaoSuave} mt-3 w-full`} onClick={() => setEscolha(null)}>Cancelar login neste aparelho</button>
        </div>
      )}
    </div>
  );
}

/** Só no Windows: limpar os dados locais ou desinstalar (o teste grátis NÃO volta: o computador é reconhecido). */
function ManutencaoDoWindows({ setMsg }: { setMsg: (m: string) => void }) {
  const p = ponteDesktop();
  if (!p) return null;
  const rodar = async (f: () => Promise<{ ok: boolean; erro?: string }>) => { try { const r = await f(); if (!r.ok && r.erro) setMsg(r.erro); } catch (e) { setMsg(e instanceof Error ? e.message : "Não consegui."); } };
  return (
    <div className="space-y-2 rounded-2xl border border-white/10 p-3">
      <p className="text-xs text-suave">Manutenção deste computador. Reinstalar ou limpar não renova o teste grátis: o computador continua reconhecido.</p>
      <div className="flex flex-wrap gap-2">
        <button className={botaoSuave} onClick={() => void rodar(p.limparDados)}>Limpar dados e reiniciar</button>
        <button className={botaoSuave} onClick={() => void rodar(p.desinstalar)}>Desinstalar a Vênus</button>
      </div>
    </div>
  );
}

const reais = (centavos: number) => `R$ ${(centavos / 100).toFixed(2).replace(".", ",")}`;

export function ContaLogada() {
  const { sessao, sair, api } = useId();
  const [lic, setLic] = useState<Licenca | null>(null);
  const [msg, setMsg] = useState("");
  const [pacote, setPacote] = useState("venus");
  const [forma, setForma] = useState<"recorrente" | "avulso">("recorrente");
  const [verPlanos, setVerPlanos] = useState(false);
  useEffect(() => { if (sessao) api.licenca(sessao.token).then((l) => { setLic(l); if (l.pacote) setPacote(l.pacote); }).catch(() => undefined); }, [api, sessao?.token]);
  if (!sessao) return null;
  const planos = lic?.pacotes ?? [];
  const escolhido = planos.find((p) => p.chave === pacote);
  async function pagar() {
    try {
      const l = await api.licenca(sessao!.token, true, { pacote, forma });
      setLic(l);
      if (l.cobranca?.url) window.open(l.cobranca.url, "_blank", "noopener");
    } catch (e) { setMsg(e instanceof Error ? e.message : "Não consegui gerar o link."); }
  }
  const precisa = lic?.renovar || !lic?.acesso;
  return (
    <div className="space-y-3 text-sm">
      <p>Conectada como <b>{sessao.usuario.email}</b></p>
      {lic && (
        <p className="rounded-2xl bg-black/25 p-3">
          {lic.acesso
            ? lic.emTeste ? `Teste grátis: ${lic.diasDeTesteRestantes} dia(s) restantes.` : `Plano ativo: ${lic.diasPagosRestantes} dia(s) restantes.`
            : "Acesso vencido. Renove o plano para continuar."}
        </p>
      )}
      {verPlanos && (
        <div className="space-y-3 rounded-2xl border border-white/10 p-3">
          <p className="font-semibold">1. Escolha o plano</p>
          <div className="grid gap-2" role="radiogroup" aria-label="Plano">
            {planos.map((p) => (
              <button key={p.chave} type="button" role="radio" aria-checked={pacote === p.chave} onClick={() => setPacote(p.chave)}
                className={`flex items-center justify-between rounded-2xl border px-4 py-3 text-left transition ${pacote === p.chave ? "border-venus bg-venus/15" : "border-white/15 bg-white/5"}`}>
                <span className="font-semibold">{p.nome}</span><span className="text-suave">{reais(p.precoCentavos)}/mês</span>
              </button>
            ))}
          </div>
          <p className="font-semibold">2. Escolha como pagar</p>
          <div className="grid gap-2" role="radiogroup" aria-label="Forma de pagamento">
            {([
              ["recorrente", "Cartão de crédito, todo mês", "Renova sozinho a cada 30 dias. Você cancela quando quiser."],
              ["avulso", "Pix ou cartão de débito, 30 dias", "Paga só este mês, sem renovar sozinho. Quando vencer, é só pagar mais 30 dias. Se não pagar, o acesso fica travado até você pagar."]
            ] as const).map(([id, titulo, sub]) => (
              <button key={id} type="button" role="radio" aria-checked={forma === id} onClick={() => setForma(id)}
                className={`rounded-2xl border px-4 py-3 text-left transition ${forma === id ? "border-venus bg-venus/15" : "border-white/15 bg-white/5"}`}>
                <b className="block">{titulo}</b><span className="mt-0.5 block text-xs text-suave">{sub}</span>
              </button>
            ))}
          </div>
          <button className={`${botaoPrimario} w-full`} onClick={pagar}>{forma === "avulso" ? "Pagar com Pix ou débito" : "Assinar no cartão"}{escolhido ? ` · ${reais(escolhido.precoCentavos)}` : ""}</button>
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        <button className={precisa && !verPlanos ? botaoPrimario : botaoSuave} onClick={() => { setVerPlanos((v) => !v); if (!verPlanos) setMsg(""); }}>{verPlanos ? "Fechar os planos" : precisa ? "Renovar ou escolher o plano" : "Ver planos e pagar"}</button>
        <button className={botaoSuave} onClick={() => void sair()}>Sair deste aparelho</button>
      </div>
      <ManutencaoDoWindows setMsg={setMsg} />
      {msg && <p className="text-rose-300">{msg}</p>}
    </div>
  );
}

/** Meus dispositivos: onde a conta está conectada, com encerramento remoto. */
export function MeusDispositivos() {
  const { api, sessao } = useId();
  const [lista, setLista] = useState<ListaDeAparelhos | null>(null);
  const [erro, setErro] = useState("");
  async function carregar() {
    if (!sessao) return;
    try { setLista(await api.dispositivos(sessao.token)); setErro(""); } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui carregar."); }
  }
  useEffect(() => { void carregar(); const t = setInterval(carregar, 20_000); return () => clearInterval(t); }, [api, sessao?.token]);
  if (!sessao) return <p className="text-sm text-suave">Entre com o Vetorial ID para ver onde a sua conta está conectada.</p>;
  async function agir(d: AparelhoDaConta, remover: boolean) {
    try {
      if (remover) await api.removerDispositivo(sessao!.token, d.id);
      else if (d.sessao) await api.encerrarSessao(sessao!.token, d.sessao);
      await carregar();
    } catch (e) { setErro(e instanceof Error ? e.message : "Não deu certo."); }
  }
  return (
    <div className="space-y-3">
      <p className="text-sm text-suave">Até {lista?.maxDispositivos ?? 3} aparelhos vinculados e {lista?.maxAtivas ?? 2} usando ao mesmo tempo.</p>
      <ul className="space-y-2">
        {lista?.dispositivos.map((d) => (
          <li key={d.id} className="flex items-center gap-3 rounded-2xl bg-black/25 p-3">
            <span className="text-2xl" aria-hidden>{ICONE[d.plataforma] ?? "📱"}</span>
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{d.nome}{d.atual && <span className="ml-2 rounded-full bg-venus/20 px-2 py-0.5 text-xs text-venus">este aparelho</span>}</p>
              <p className="text-xs text-suave">{d.ativo ? "Ativo agora" : `Visto ${haQuantoTempo(d.ultimo)}`}</p>
            </div>
            {!d.atual && d.ativo && <button className={botaoSuave} onClick={() => agir(d, false)}>Encerrar sessão</button>}
            {!d.atual && <button className={botaoSuave} onClick={() => agir(d, true)} aria-label={`Remover ${d.nome}`}>Remover</button>}
          </li>
        ))}
      </ul>
      {erro && <p role="alert" className="text-sm text-rose-300">{erro}</p>}
    </div>
  );
}

/** Aviso inteligente: este aparelho foi barrado porque já há 2 ativos. */
export function AvisoDeConflito() {
  const { conflito, resolverConflito } = useId();
  if (!conflito) return null;
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4 backdrop-blur-sm" role="alertdialog" aria-modal="true">
      <div className="w-full max-w-md rounded-3xl border border-white/15 bg-painel p-5 shadow-2xl">
        <h3 className="text-lg font-semibold">Aparelhos demais usando a conta</h3>
        <p className="mt-2 text-sm text-suave">Já há 2 aparelhos conectados agora. Encerre um deles para continuar aqui, ou cancele.</p>
        <ul className="mt-4 space-y-2">
          {conflito.ativas.map((a) => (
            <li key={a.sessao} className="flex items-center justify-between gap-2 rounded-2xl bg-black/25 p-3 text-sm">
              <span>{a.nome}</span>
              <button className={botaoSuave} onClick={() => void resolverConflito(a.sessao)}>Encerrar</button>
            </li>
          ))}
        </ul>
        <button className={`${botaoSuave} mt-4 w-full`} onClick={() => void resolverConflito()}>Cancelar (sair deste aparelho)</button>
      </div>
    </div>
  );
}
