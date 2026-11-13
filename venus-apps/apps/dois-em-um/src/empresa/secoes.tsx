import { useMemo, useState, type ReactNode } from "react";
import { useConversas } from "../sessao/ConversasProvider";
import { useId } from "../id/IdProvider";
import { usePersistido } from "../hooks/usePersistido";
import { useMarketing } from "./MarketingProvider";
import { Cartao, botaoPrimario, botaoSuave, campo } from "../ajustes/Controles";
import { useEmpresa } from "./EmpresaProvider";
import { pedirTexto } from "./ia";
import { contextoDaEmpresa, regrasEmLinhas } from "./logica";
import {
  buscarConversas, inspiracao, lerIdeiasDePost, lerIdeiasDeVideo, lerStories, limparResposta, MAXIMO_RESPOSTA, MODELOS_DE_RESPOSTA,
  pedidoDeIdeiasDePost, pedidoDeIdeiasDeVideo, pedidoDeResposta, pedidoDeStories, primeirosPassos, resumirConversa,
  type IdeiaDePost, type IdeiaDeVideo, type StoryDoDia
} from "./marketing";

const hoje = () => new Date().toISOString().slice(0, 10);
const dataBr = (iso: string) => (iso ? iso.split("-").reverse().slice(0, 2).join("/") : "quando quiser");

function Titulo({ titulo, descricao }: { titulo: string; descricao?: string }) {
  return (
    <header className="mb-4">
      <h2 className="text-2xl font-semibold">{titulo}</h2>
      {descricao && <p className="mt-1 text-sm text-suave">{descricao}</p>}
    </header>
  );
}

function BotaoCopiar({ texto, rotulo = "Copiar" }: { texto: string; rotulo?: string }) {
  const [ok, setOk] = useState(false);
  return (
    <button type="button" className={botaoSuave} onClick={() => {
      void navigator.clipboard?.writeText(texto).then(() => { setOk(true); setTimeout(() => setOk(false), 1800); }).catch(() => undefined);
    }}>{ok ? "Copiado ✓" : rotulo}</button>
  );
}

/** Estado de uma geração por IA: ocupado, erro curto e a função que roda. */
function useGeracao() {
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState("");
  const rodar = async <T,>(fazer: () => Promise<T>): Promise<T | undefined> => {
    setOcupado(true); setErro("");
    try { return await fazer(); } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui agora."); return undefined; } finally { setOcupado(false); }
  };
  return { ocupado, erro, rodar };
}

const Erro = ({ texto }: { texto: string }) => (texto ? <p role="alert" className="text-sm text-rose-400">{texto}</p> : null);
const Vazio = ({ children }: { children: ReactNode }) => <p className="rounded-2xl border border-dashed border-white/15 p-6 text-center text-sm text-suave">{children}</p>;

// ------------------------------------------------------------------ dados e regras
export function DadosDaLoja() {
  const { empresa, salvar } = useEmpresa();
  const { sessao } = useId();
  const [rascunho, setRascunho] = useState(empresa);
  const [salvo, setSalvo] = useState(false);
  const [versao, setVersao] = useState(empresa);
  if (versao !== empresa) { setVersao(empresa); setRascunho(empresa); } // chegou cadastro novo da conta (celular)
  const CAMPOS = [
    { id: "dono", rotulo: "Seu nome" }, { id: "loja", rotulo: "Nome da loja" },
    { id: "ramo", rotulo: "Ramo", dica: "Ex.: moda feminina, sex shop, eletrônicos" },
    { id: "cidade", rotulo: "Cidade" }, { id: "uf", rotulo: "UF" }, { id: "site", rotulo: "Site da loja" }
  ] as const;
  return (
    <>
      <Titulo titulo="Dados da loja" descricao={sessao ? "Cadastro único: o que você preenche no celular aparece aqui, e o que muda aqui chega no celular." : "Entre com o Vetorial ID (em Ajustes) para trazer o cadastro que você fez no celular."} />
      <Cartao id="empresa-dados" titulo="Perfil do dono e da loja">
        <form className="grid gap-3 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); salvar(rascunho); setSalvo(true); setTimeout(() => setSalvo(false), 2500); }}>
          {CAMPOS.map((c) => (
            <label key={c.id} className="flex flex-col gap-1 text-sm">
              <span className="text-suave">{c.rotulo}</span>
              <input className={campo} value={rascunho[c.id]} maxLength={c.id === "uf" ? 2 : 200} placeholder={"dica" in c ? c.dica : undefined}
                onChange={(e) => setRascunho({ ...rascunho, [c.id]: e.target.value })} />
            </label>
          ))}
          <label className="flex flex-col gap-1 text-sm sm:col-span-2">
            <span className="text-suave">Sobre a empresa</span>
            <textarea className={`${campo} min-h-28`} value={rascunho.sobre} maxLength={1500} onChange={(e) => setRascunho({ ...rascunho, sobre: e.target.value })} />
          </label>
          <div className="flex items-center gap-3 sm:col-span-2">
            <button type="submit" className={botaoPrimario}>Salvar</button>
            {salvo && <span role="status" className="text-sm text-suave">Salvo{sessao ? " e enviado para a conta" : ""}.</span>}
          </div>
        </form>
      </Cartao>
    </>
  );
}

export function RegrasDeVenda() {
  const { empresa } = useEmpresa();
  const regras = regrasEmLinhas(empresa.regrasConfiguradas ? empresa.regras : null);
  return (
    <>
      <Titulo titulo="Regras de venda" />
      <Cartao id="empresa-regras" titulo="Pagamento, frete, trocas e atendimento"
        descricao={empresa.regrasConfiguradas ? "Cadastradas no celular. A Vênus segue estas regras ao responder clientes." : "Ainda não cadastradas. Configure no celular (Menu → Regras de venda) e elas chegam aqui."}>
        {regras.length > 0 && <ul className="list-disc space-y-1 pl-5 text-sm">{regras.map((l) => <li key={l}>{l}</li>)}</ul>}
      </Cartao>
    </>
  );
}

export function PrimeirosPassos({ aoIr }: { aoIr: (id: string) => void }) {
  const { empresa } = useEmpresa();
  const { sessao } = useId();
  const passos = primeirosPassos(empresa, !!sessao);
  const feitos = passos.filter((p) => p.feito).length;
  return (
    <>
      <Titulo titulo="Primeiros passos" descricao="A lista do que configurar para a Vênus funcionar com a sua loja." />
      <Cartao id="empresa-passos" titulo={`${feitos} de ${passos.length} prontos`}>
        <ul className="space-y-2">
          {passos.map((p) => (
            <li key={p.id} className="flex items-center gap-3 text-sm">
              <span className={`grid size-6 place-items-center rounded-full text-xs ${p.feito ? "bg-emerald-500/20 text-emerald-300" : "bg-white/10 text-suave"}`}>{p.feito ? "✓" : "•"}</span>
              <span className={p.feito ? "text-suave line-through" : ""}>{p.texto}</span>
              {!p.feito && p.id === "dados" && <button className="ml-auto text-venus underline" onClick={() => aoIr("dados")}>Preencher</button>}
              {!p.feito && p.id === "id" && <button className="ml-auto text-venus underline" onClick={() => window.dispatchEvent(new CustomEvent("venus:abrir", { detail: { tela: "ajustes", alvo: "conta" } }))}>Entrar</button>}
            </li>
          ))}
        </ul>
      </Cartao>
    </>
  );
}

// ------------------------------------------------------------------ histórico
const quandoCurto = (t: number) => new Date(t).toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });

/** Histórico de conversas do dono e da loja: busca por assunto em todas, e continuar de onde parou. */
export function Historico() {
  const { historico, abrir, excluir, apagarHistorico } = useConversas();
  const [termo, setTermo] = useState("");
  const [aberta, setAberta] = useState<string | null>(null);
  const [confirmar, setConfirmar] = useState<string | null>(null);
  const achadas = useMemo(() => buscarConversas(historico.filter((c) => c.mensagens.length > 0), termo), [historico, termo]);
  const conversa = achadas.find((c) => c.id === aberta) ?? null;
  return (
    <>
      <Titulo titulo="Histórico de conversas" descricao="Tudo o que você e a loja conversaram com a Vênus neste computador. Procure por assunto e continue de onde parou." />
      <div className="mb-4 flex flex-wrap gap-2">
        <input type="search" className={`${campo} max-w-md flex-1`} placeholder="Buscar em todas as conversas…" aria-label="Buscar nas conversas" value={termo} onChange={(e) => { setTermo(e.target.value); setAberta(null); }} />
        {historico.length > 0 && (confirmar === "tudo"
          ? <button className={`${botaoSuave} border-rose-400/50 text-rose-300`} onClick={() => { apagarHistorico(); setConfirmar(null); setAberta(null); }}>Confirmar: apagar tudo</button>
          : <button className={botaoSuave} onClick={() => setConfirmar("tudo")}>Apagar histórico</button>)}
      </div>
      <div className="grid gap-4 lg:grid-cols-[minmax(0,360px)_1fr]">
        <ul className="flex flex-col gap-2" aria-label="Conversas">
          {achadas.length === 0 && <Vazio>{termo ? `Nada encontrado para “${termo}”.` : "Ainda não há conversas. Fale com a Vênus no Chat."}</Vazio>}
          {achadas.map((c) => {
            const r = resumirConversa(c);
            return (
              <li key={c.id}>
                <button type="button" onClick={() => setAberta(c.id)} aria-current={aberta === c.id}
                  className={`w-full rounded-2xl border p-3 text-left transition ${aberta === c.id ? "border-venus bg-venus/10" : "border-white/10 bg-white/[0.05] hover:border-white/30"}`}>
                  <span className="flex items-baseline justify-between gap-2"><span className="truncate font-semibold">{r.titulo}</span><span className="shrink-0 text-[11px] text-suave">{quandoCurto(r.atualizada)}</span></span>
                  <span className="mt-1 line-clamp-2 block text-xs text-suave">{r.resumo}</span>
                  <span className="mt-1 block text-[11px] text-suave">{r.mensagens} mensagens</span>
                </button>
              </li>
            );
          })}
        </ul>
        <div className="min-w-0">
          {conversa ? (
            <Cartao id="hist-conversa" titulo={conversa.titulo || "Conversa"} descricao={`Atualizada em ${quandoCurto(conversa.atualizada)}`}>
              <ol className="flex max-h-[55vh] flex-col gap-2 overflow-y-auto pr-1">
                {conversa.mensagens.map((m) => (
                  <li key={m.id} className={`max-w-[88%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm ${m.autor === "eu" ? "self-end bg-venus text-sobre-venus" : "self-start border border-borda bg-painel"}`}>{m.texto}</li>
                ))}
              </ol>
              <div className="mt-4 flex flex-wrap gap-2">
                <button className={botaoPrimario} onClick={() => abrir(conversa.id)}>Continuar esta conversa</button>
                {confirmar === conversa.id
                  ? <button className={`${botaoSuave} border-rose-400/50 text-rose-300`} onClick={() => { excluir(conversa.id); setConfirmar(null); setAberta(null); }}>Confirmar: apagar</button>
                  : <button className={botaoSuave} onClick={() => setConfirmar(conversa.id)}>Apagar</button>}
              </div>
            </Cartao>
          ) : achadas.length > 0 && <Vazio>Escolha uma conversa na lista para ler.</Vazio>}
        </div>
      </div>
    </>
  );
}

// ------------------------------------------------------------------ ideias de post
export function IdeiasDePost() {
  const { empresa } = useEmpresa();
  const mk = useMarketing();
  const ideias = mk.ideias;
  const setIdeias = (l: IdeiaDePost[]) => mk.trocarIdeias(l);
  const g = useGeracao();
  const gerar = (quantos: number, manter: IdeiaDePost[]) => g.rodar(async () => {
    const t = await pedirTexto(pedidoDeIdeiasDePost(empresa, [...ideias, ...manter].map((i) => i.titulo), quantos, hoje()), empresa);
    const novas = lerIdeiasDePost(t);
    if (!novas.length) throw new Error("Não consegui criar as ideias agora. Tente de novo daqui a pouco.");
    return novas;
  });
  return (
    <>
      <Titulo titulo="Ideias de post" descricao="Sugestões da Vênus para as próximas semanas, prontas para copiar e postar." />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <button className={botaoPrimario} disabled={g.ocupado} onClick={async () => { const n = await gerar(6, []); if (n) setIdeias(n); }}>{g.ocupado ? "Criando…" : ideias.length ? "Gerar todas de novo" : "Gerar ideias agora"}</button>
        {ideias.length > 0 && <button className={botaoSuave} disabled={g.ocupado} onClick={async () => { const n = await gerar(3, []); if (n) mk.juntarIdeias(n); }}>Mais 3 ideias</button>}
        <Erro texto={g.erro} />
      </div>
      {!ideias.length && !g.ocupado && <Vazio>Ainda não há ideias. Toque em “Gerar ideias agora”.</Vazio>}
      <div className="grid gap-3 xl:grid-cols-2">
        {ideias.map((i) => (
          <Cartao key={i.id} id={`ideia-${i.id}`} titulo={i.titulo} descricao={`${i.formato === "story" ? "Story" : i.formato === "reels" ? "Reels" : "Feed"}${i.quando ? ` · sugerido para ${dataBr(i.quando)}` : ""}${i.origem === "celular" ? " · do celular" : ""}${i.usada ? " · já postada" : ""}`}>
            <p className="whitespace-pre-wrap text-sm">{i.legenda}</p>
            <div className="mt-3 flex flex-wrap gap-2">
              <BotaoCopiar texto={i.legenda} rotulo="Copiar legenda" />
              <button className={botaoSuave} disabled={g.ocupado} onClick={async () => { const n = await gerar(1, [i]); if (n) { mk.tirarIdeia(i.id); mk.juntarIdeias(n); } }}>↻ Outra ideia</button>
              <button className={botaoSuave} onClick={() => mk.atualizarIdeia(i.id, { usada: !i.usada })}>{i.usada ? "Desmarcar" : "Marcar como postada"}</button>
              <button className={botaoSuave} onClick={() => mk.tirarIdeia(i.id)}>Tirar</button>
            </div>
          </Cartao>
        ))}
      </div>
      <PostsProgramados />
      <p className="mt-4 text-xs text-suave">Para programar o post com foto e hora marcada, use o celular (Publicações → Posts no Instagram): o Instagram só aceita publicar pelo aplicativo conectado.</p>
    </>
  );
}

function PostsProgramados() {
  const { postsDoCelular } = useMarketing();
  const agora = Date.now();
  const lista = [...postsDoCelular].sort((a, b) => a.quando - b.quando);
  const quando = (ms: number) => new Date(ms).toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
  return (
    <div className="mt-6">
      <h3 className="mb-2 text-sm font-semibold">Posts do celular</h3>
      {!lista.length ? <Vazio>Nenhum post programado no celular. Quando houver, aparece aqui.</Vazio> : (
        <div className="grid gap-3 xl:grid-cols-2">
          {lista.map((p) => (
            <Cartao key={p.id} id={`post-${p.id}`} titulo={`${p.tipo === "story" ? "Story" : "Feed"} · ${quando(p.quando)}`} descricao={p.quando > agora ? "Programado" : "Já passou"}>
              <p className="whitespace-pre-wrap text-sm">{p.legenda || "(sem legenda)"}</p>
              <div className="mt-3 flex gap-2">{p.legenda && <BotaoCopiar texto={p.legenda} rotulo="Copiar legenda" />}</div>
            </Cartao>
          ))}
        </div>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ ideias de vídeo
export function IdeiasDeVideo() {
  const { empresa } = useEmpresa();
  const [ideias, setIdeias] = usePersistido<IdeiaDeVideo[]>("venus:ideias-video", []);
  const [foco, setFoco] = useState("");
  const g = useGeracao();
  return (
    <>
      <Titulo titulo="Ideias de vídeo (Reels)" descricao="Roteiro, pedido pronto para um gerador de vídeo por IA, legenda e hashtags." />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <input className={`${campo} max-w-md flex-1`} value={foco} onChange={(e) => setFoco(e.target.value)} placeholder="Sobre qual produto ou assunto? (opcional)" aria-label="Assunto dos vídeos" />
        <button className={botaoPrimario} disabled={g.ocupado} onClick={async () => {
          const n = await g.rodar(async () => {
            const v = lerIdeiasDeVideo(await pedirTexto(pedidoDeIdeiasDeVideo(empresa, 4, foco), empresa));
            if (!v.length) throw new Error("Não consegui criar as ideias agora. Tente de novo daqui a pouco.");
            return v;
          });
          if (n) setIdeias(n);
        }}>{g.ocupado ? "Criando…" : "Criar ideias"}</button>
        <Erro texto={g.erro} />
      </div>
      {!ideias.length && !g.ocupado && <Vazio>Ainda não há ideias de vídeo.</Vazio>}
      <div className="grid gap-3 xl:grid-cols-2">
        {ideias.map((v) => (
          <Cartao key={v.id} id={`video-${v.id}`} titulo={v.titulo}>
            <ol className="list-decimal space-y-1 pl-5 text-sm">{v.roteiro.map((c, k) => <li key={k}>{c}</li>)}</ol>
            <p className="mt-3 text-xs uppercase tracking-widest text-suave">Pedido para o gerador de vídeo por IA</p>
            <p className="mt-1 rounded-xl bg-black/30 p-3 text-xs">{v.pedidoIA}</p>
            <p className="mt-3 text-sm">{v.legenda} <span className="text-venus">{v.hashtags}</span></p>
            <div className="mt-3 flex flex-wrap gap-2">
              <BotaoCopiar texto={v.pedidoIA} rotulo="Copiar pedido" />
              <BotaoCopiar texto={`${v.legenda}\n\n${v.hashtags}`} rotulo="Copiar legenda" />
              {inspiracao(v.titulo).slice(0, 2).map((l) => <a key={l.nome} className={botaoSuave} href={l.url} target="_blank" rel="noreferrer noopener">{l.nome} ↗</a>)}
            </div>
          </Cartao>
        ))}
      </div>
      {ideias.length > 0 && <p className="mt-4 text-xs text-suave">Cole o pedido em um gerador de vídeo por IA (Veo no Gemini, Kling, Runway, Pika ou Sora) e, se puder, anexe a foto do produto. Depois é só postar como Reels.</p>}
    </>
  );
}

// ------------------------------------------------------------------ stories do dia
export function StoriesDoDia() {
  const { empresa } = useEmpresa();
  const [stories, setStories] = usePersistido<{ dia: string; itens: StoryDoDia[] }>("venus:stories", { dia: "", itens: [] });
  const g = useGeracao();
  const atuais = stories.dia === hoje() ? stories.itens : [];
  return (
    <>
      <Titulo titulo="Stories do dia" descricao="A Vênus prepara o texto do story da manhã e o da tarde (com enquete). A arte e a publicação ficam no celular." />
      <div className="mb-4 flex items-center gap-2">
        <button className={botaoPrimario} disabled={g.ocupado} onClick={async () => {
          const n = await g.rodar(async () => {
            const s = lerStories(await pedirTexto(pedidoDeStories(empresa, hoje()), empresa));
            if (!s.length) throw new Error("Não consegui criar os stories agora. Tente de novo.");
            return s;
          });
          if (n) setStories({ dia: hoje(), itens: n });
        }}>{g.ocupado ? "Criando…" : atuais.length ? "Gerar outros" : "Gerar os stories de hoje"}</button>
        <Erro texto={g.erro} />
      </div>
      <StoriesDoCelularBloco />
      {!atuais.length && !g.ocupado && <Vazio>Nenhum story para hoje ainda.</Vazio>}
      <div className="grid gap-3 xl:grid-cols-2">
        {atuais.map((s, k) => (
          <Cartao key={k} id={`story-${k}`} titulo={`${s.periodo === "manha" ? "Manhã" : "Tarde"}${s.titulo ? ` · ${s.titulo}` : ""}`}>
            <p className="whitespace-pre-wrap text-sm">{s.texto}</p>
            {s.enquete && <p className="mt-2 rounded-xl bg-white/10 p-3 text-sm">📊 Enquete: {s.enquete}</p>}
            <div className="mt-3 flex gap-2"><BotaoCopiar texto={s.texto} />{s.enquete && <BotaoCopiar texto={s.enquete} rotulo="Copiar enquete" />}</div>
          </Cartao>
        ))}
      </div>
    </>
  );
}

function StoriesDoCelularBloco() {
  const { storiesDoCelular: c } = useMarketing();
  if (!c) return null;
  return (
    <div className="mb-5 rounded-2xl border border-white/10 bg-white/5 p-4">
      <h3 className="mb-1 text-sm font-semibold">Stories automáticos do celular</h3>
      <p className="mb-2 text-xs text-suave">
        {c.ligado ? "Ligado" : "Desligado"}{c.manha ? ` · manhã ${c.manha}` : ""}{c.tarde ? ` · tarde ${c.tarde}${c.tardeInterativa ? " (com enquete)" : ""}` : ""}{c.ultimo ? ` · último: ${c.ultimo}` : ""}
      </p>
      {c.fila.length ? (
        <ul className="space-y-2 text-sm">
          {c.fila.map((f, k) => (
            <li key={k} className="rounded-xl bg-white/5 p-3">
              <span className="font-medium">{f.titulo || f.tipo}</span>
              <p className="whitespace-pre-wrap text-suave">{f.texto}</p>
              {f.enquete && <p className="mt-1 text-xs">📊 {f.enquete}</p>}
            </li>
          ))}
        </ul>
      ) : <p className="text-xs text-suave">Nenhuma dica na fila.</p>}
      {c.usados.length > 0 && <p className="mt-2 text-xs text-suave">Já usados: {c.usados.join(", ")}</p>}
    </div>
  );
}

// ------------------------------------------------------------------ respostas do Instagram
type Salvas = Record<string, { texto: string; editada: boolean }>;

export function RespostasDoInstagram() {
  const { empresa } = useEmpresa();
  const [salvas, setSalvas] = usePersistido<Salvas>("venus:respostas-instagram", {});
  const [fazendo, setFazendo] = useState<string | null>(null);
  const [erro, setErro] = useState("");
  const gerar = async (id: string) => {
    const m = MODELOS_DE_RESPOSTA.find((x) => x.id === id)!;
    setFazendo(id); setErro("");
    try {
      const t = limparResposta(await pedirTexto(pedidoDeResposta(empresa, m, contextoDaEmpresa(empresa)), empresa));
      setSalvas((s) => ({ ...s, [id]: { texto: t, editada: false } }));
    } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui agora."); } finally { setFazendo(null); }
  };
  const todas = async () => { for (const m of MODELOS_DE_RESPOSTA) if (!salvas[m.id]?.editada) await gerar(m.id); };
  return (
    <>
      <Titulo titulo="Respostas do Instagram (Robô)" descricao="Respostas prontas para colar nas “Perguntas frequentes” (até 4) e nas “Respostas salvas” do Direct do Instagram." />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <button className={botaoPrimario} disabled={fazendo !== null} onClick={() => void todas()}>{fazendo ? "Escrevendo…" : "Gerar todas"}</button>
        <Erro texto={erro} />
      </div>
      <div className="grid gap-3 xl:grid-cols-2">
        {MODELOS_DE_RESPOSTA.map((m) => {
          const r = salvas[m.id];
          return (
            <Cartao key={m.id} id={`resp-${m.id}`} titulo={m.pergunta} descricao={`Atalho: /${m.atalho}${m.faq ? " · pergunta frequente" : ""}`}>
              <textarea className={`${campo} min-h-24 text-sm`} maxLength={MAXIMO_RESPOSTA} value={r?.texto ?? ""} placeholder="Ainda sem resposta. Toque em Gerar."
                onChange={(e) => setSalvas((s) => ({ ...s, [m.id]: { texto: e.target.value, editada: true } }))} aria-label={`Resposta para: ${m.pergunta}`} />
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <button className={botaoSuave} disabled={fazendo !== null} onClick={() => void gerar(m.id)}>{fazendo === m.id ? "Escrevendo…" : r?.texto ? "↻ Gerar de novo" : "Gerar"}</button>
                {r?.texto && <BotaoCopiar texto={r.texto} />}
                <span className="ml-auto text-[11px] text-suave">{(r?.texto ?? "").length}/{MAXIMO_RESPOSTA}{r?.editada ? " · editada" : ""}</span>
              </div>
            </Cartao>
          );
        })}
      </div>
    </>
  );
}

export function InstagramDaLoja() {
  return (
    <>
      <Titulo titulo="Instagram da loja" />
      <Cartao id="insta-conta" titulo="Conexão e publicação automática" descricao="A conexão com a conta do Instagram (token da Meta) e a publicação de posts e stories ficam no app do celular, em Publicações → Contas e respostas.">
        <p className="text-sm text-suave">Aqui no Windows você cria as ideias, as legendas, os roteiros e as respostas e copia para postar. Nada se perde: o cadastro da loja é o mesmo nos dois.</p>
        <div className="mt-3 flex flex-wrap gap-2">
          <a className={botaoSuave} href="https://www.instagram.com/" target="_blank" rel="noreferrer noopener">Abrir o Instagram ↗</a>
          <a className={botaoSuave} href="https://business.facebook.com/latest/home" target="_blank" rel="noreferrer noopener">Meta Business Suite ↗</a>
        </div>
      </Cartao>
    </>
  );
}
