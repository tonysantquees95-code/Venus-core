import { useEffect, useMemo, useState } from "react";
import { saudeDoServidor } from "@venus/nucleo";
import { noDesktop, ponteDesktop } from "../desktop/ponte";
import { useId } from "../id/IdProvider";
import { AvatarDeConta } from "../id/Avatar";
import { HolographicOrb, useTema } from "../theming";
import { Cartao, Chave, botaoSuave } from "./Controles";
import { Novidades, Termos, Tour } from "../apresentacao/Apresentacao";
import { NOVIDADES, novasDesde } from "../apresentacao/dados";
import { ContaLogada, Entrar, MeusDispositivos } from "./Conta";
import { buscar, CATALOGO, CATEGORIAS, MODOS, type Categoria } from "./logica";
import { usePreferencias } from "./Preferencias";
import { CartaoVoz } from "./CartaoVoz";
import { PaginaComLateral } from "../components/PaginaComLateral";
import { IconeNomeado } from "../components/Icones";

function Servidor() {
  const [estado, setEstado] = useState<{ online: boolean; ms: number | null }>({ online: false, ms: null });
  useEffect(() => {
    let parar = false;
    const medir = async () => {
      const t0 = performance.now();
      try { await saudeDoServidor(); if (!parar) setEstado({ online: true, ms: Math.round(performance.now() - t0) }); }
      catch { if (!parar) setEstado({ online: false, ms: null }); }
    };
    void medir();
    const t = setInterval(medir, 15_000);
    const off = ponteDesktop()?.aoStatusDoServidor((s) => setEstado({ online: s.online, ms: s.ms }));
    return () => { parar = true; clearInterval(t); off?.(); };
  }, []);
  return (
    <p className="flex items-center gap-2 text-sm">
      <span className={`size-3 rounded-full ${estado.online ? "bg-emerald-400" : "bg-rose-400"}`} aria-hidden />
      {estado.online ? `Conectado${estado.ms !== null ? ` (${estado.ms} ms)` : ""}` : "Sem conexão com o servidor"}
    </p>
  );
}

/** Só no Windows: versão instalada, botão "Verificar atualizações" e instalação com um toque. */
function Atualizacoes() {
  const ponte = ponteDesktop();
  const [estado, setEstado] = useState<{ texto: string; nova?: string; ocupado: boolean; pct: number | null; pronta: boolean }>({ texto: "", ocupado: false, pct: null, pronta: false });
  useEffect(() => ponte?.aoProgressoDaAtualizacao(({ baixado, total, pronta }) => {
    const pct = pronta ? 100 : total > 0 ? Math.min(100, Math.round((baixado / total) * 100)) : null;
    setEstado((e) => ({ ...e, ocupado: !pronta, pronta: !!pronta, pct, texto: pronta ? "Pronta para instalar." : total > 0 ? `Baixando… ${pct}%` : "Baixando…" }));
  }), [ponte]);
  if (!ponte) return null;
  const verificar = async () => {
    setEstado({ texto: "Consultando…", ocupado: true, pct: null, pronta: false });
    const r = await ponte.verificarAtualizacao();
    setEstado(r.erro ? { texto: r.erro, ocupado: false, pct: null, pronta: false } : r.disponivel ? { texto: `Tem uma versão nova: ${r.nova}.`, nova: r.nova, ocupado: false, pct: null, pronta: false } : { texto: `A Vênus já está na versão mais nova (${r.atual}).`, ocupado: false, pct: null, pronta: false });
  };
  const baixar = async () => {
    setEstado((e) => ({ ...e, texto: "Baixando…", ocupado: true, pct: 0 }));
    const r = await ponte.baixarAtualizacao();
    setEstado((e) => r.ok ? (r.pronta ? { ...e, ocupado: false, pronta: true, pct: 100, texto: "Pronta para instalar." } : { ...e, ocupado: false, pct: null, texto: "Abri o download no navegador." }) : { ...e, ocupado: false, pct: null, texto: r.erro ?? "Não consegui baixar." });
  };
  const instalar = async () => {
    setEstado((e) => ({ ...e, ocupado: true, texto: "Atualizando… a Vênus abre de novo em instantes." }));
    const r = await ponte.instalarAtualizacao();
    if (!r.ok) setEstado((e) => ({ ...e, ocupado: false, texto: r.erro ?? "Não consegui atualizar." }));
  };
  return (
    <div className="space-y-3">
      <p className="text-sm text-slate-300">Versão instalada: {ponte.versao}. A Vênus confere sozinha a cada poucas horas e pergunta antes de baixar. Depois de baixar, você escolhe a hora de instalar.</p>
      <div className="flex flex-wrap gap-2">
        <button className={botaoSuave} disabled={estado.ocupado} onClick={() => void verificar()}>Verificar atualizações</button>
        {estado.nova && !estado.pronta && <button className={botaoSuave} disabled={estado.ocupado} onClick={() => void baixar()}>Baixar {estado.nova}</button>}
        {estado.pronta && <button className={botaoSuave} disabled={estado.ocupado} onClick={() => void instalar()}>Instalar agora</button>}
      </div>
      {estado.pct !== null && (
        <div className="h-2 overflow-hidden rounded-full bg-white/10" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={estado.pct}>
          <div className="h-full rounded-full bg-gradient-to-r from-violet-400 to-cyan-300 transition-all" style={{ width: `${estado.pct}%` }} />
        </div>
      )}
      {estado.texto && <p className="text-sm" role="status">{estado.texto}</p>}
    </div>
  );
}

/** Ajustes: categorias, cartões de vidro, alternâncias grandes e busca rápida. */
export function Ajustes({ aoAbrirTema, alvo }: { aoAbrirTema: () => void; alvo?: string }) {
  const [sobre, setSobre] = useState<"" | "tour" | "termos" | "novidades">("");
  const { prefs, definir } = usePreferencias();
  const { sessao } = useId();
  const { tema } = useTema();
  const [termo, setTermo] = useState("");
  const [categoria, setCategoria] = useState<Categoria>(() => CATALOGO.find((i) => i.id === alvo)?.categoria ?? "aparencia");
  const achados = useMemo(() => new Set(buscar(termo).map((i) => i.id)), [termo]);
  const buscando = termo.trim() !== "";
  const mostra = (id: string, cat: Categoria) => achados.has(id) && (buscando || categoria === cat);
  const algumNaCategoria = (cat: Categoria) => [...achados].some((id) => buscar(termo).find((i) => i.id === id)?.categoria === cat);

  useEffect(() => {
    const c = CATALOGO.find((i) => i.id === alvo)?.categoria;
    if (c) { setCategoria(c); setTermo(""); setTimeout(() => document.getElementById(`aj-${alvo}`)?.scrollIntoView({ behavior: "smooth", block: "center" }), 200); }
  }, [alvo]);

  // Busca: leva direto ao primeiro resultado.
  useEffect(() => {
    if (!termo.trim()) return;
    const primeiro = buscar(termo)[0];
    if (primeiro) document.getElementById(`aj-${primeiro.id}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [termo]);

  const Titulo = ({ cat }: { cat: Categoria }) => {
    const c = CATEGORIAS.find((x) => x.id === cat)!;
    return algumNaCategoria(cat) && buscando
      ? <h2 className="mt-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-suave"><IconeNomeado nome={c.icone} className="size-4" />{c.nome}</h2> : null;
  };
  const nenhum = achados.size === 0;

  return (
    <PaginaComLateral
      titulo="Ajustes"
      itens={CATEGORIAS.map((c) => ({ id: c.id, rotulo: c.nome, icone: <IconeNomeado nome={c.icone} className="size-5" /> }))}
      atual={buscando ? "" : categoria}
      aoEscolher={(id) => { setCategoria(id as Categoria); setTermo(""); }}
      topo={
        <input
          type="search"
          value={termo}
          onChange={(e) => setTermo(e.target.value)}
          placeholder="Buscar ajustes…"
          aria-label="Buscar nos ajustes"
          className="w-full rounded-xl border border-white/15 bg-white/10 px-3 py-2 text-sm outline-none placeholder:text-suave focus:border-venus"
        />
      }
      rodape={<AvatarDeConta />}
    >
    <div className="min-h-full bg-[radial-gradient(900px_500px_at_15%_-10%,rgb(var(--venus-rgb)/.14),transparent),radial-gradient(700px_500px_at_100%_110%,rgb(var(--robo-rgb)/.12),transparent)]">
      <div className="mx-auto w-full max-w-4xl px-4 pb-10 pt-6 md:px-8">
        <h2 className="mb-4 text-2xl font-semibold">{buscando ? `Resultados para “${termo}”` : CATEGORIAS.find((c) => c.id === categoria)?.nome}</h2>

        {nenhum && <p className="mt-10 text-center text-suave">Nada encontrado para “{termo}”. Tente outra palavra.</p>}

        <div className="space-y-3">
          <Titulo cat="aparencia" />
          {mostra("tema", "aparencia") && (
            <Cartao id="tema" titulo="Tema, cores e orbe" descricao="Mude cores, modelo do orbe e ícones. Vale para todos os seus aparelhos.">
              <div className="flex items-center gap-4">
                <HolographicOrb tamanho={96} />
                <div className="flex gap-2" aria-label="Cores atuais">
                  {[tema.cores.destaque, tema.cores.secundaria].map((c, i) => <span key={i} className="size-8 rounded-full ring-2 ring-white/30" style={{ background: `rgb(${c.r} ${c.g} ${c.b})` }} />)}
                </div>
                <button className={`${botaoSuave} ml-auto`} onClick={aoAbrirTema}>Personalizar</button>
              </div>
            </Cartao>
          )}

          <Titulo cat="comportamento" />
          {mostra("modo", "comportamento") && (
            <Cartao id="modo" titulo="Como usar a Vênus" descricao="Escolha o jeito que combina com você. Dá para trocar quando quiser.">
              <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="Modo de uso">
                {MODOS.map((m) => (
                  <button key={m.id} role="radio" aria-checked={prefs.modo === m.id} onClick={() => definir("modo", m.id)}
                    className={`rounded-2xl border p-3 text-left transition ${prefs.modo === m.id ? "border-venus bg-venus/15" : "border-white/10 bg-black/20 hover:border-white/30"}`}>
                    <span className="block font-semibold">{m.nome}</span>
                    <span className="mt-1 block text-xs text-suave">{m.descricao}</span>
                  </button>
                ))}
              </div>
              {prefs.modo === "bolha" && <p className="mt-3 text-xs text-suave">A bolha flutuante usa a permissão “Exibir sobre outros apps” do Android (e a janela sempre visível no Windows).</p>}
            </Cartao>
          )}
          {mostra("escutaContinua", "comportamento") && <Chave id="escutaContinua" titulo="Escuta contínua" descricao="A Vênus fica ouvindo o seu chamado. No Android aparece o ícone verde do microfone na barra de status; gasta um pouco mais de bateria. Vale só neste aparelho." ligado={prefs.escutaContinua} aoMudar={(v) => definir("escutaContinua", v)} />}
          {mostra("acionarPeloNome", "comportamento") && <Chave id="acionarPeloNome" titulo="Acionar só dizendo o nome" descricao="Dizer “Vênus” já abre o orbe ouvindo." ligado={prefs.acionarPeloNome} aoMudar={(v) => definir("acionarPeloNome", v)} />}
          {mostra("falarRespostas", "comportamento") && <Chave id="falarRespostas" titulo="Falar as respostas" descricao="Lê em voz alta o que a Vênus responde." ligado={prefs.falarRespostas} aoMudar={(v) => definir("falarRespostas", v)} />}
          {mostra("voz", "comportamento") && <Cartao id="voz" titulo="Voz da Vênus" descricao="Escolha a voz, a velocidade e o tom, como no celular."><CartaoVoz /></Cartao>}
          {mostra("notificacoes", "comportamento") && <Chave id="notificacoes" titulo="Notificações" descricao="Avisos do sistema para pedidos, estoque e lembretes." ligado={prefs.notificacoes} aoMudar={(v) => {
            definir("notificacoes", v);
            if (v && !noDesktop() && typeof Notification !== "undefined" && Notification.permission === "default") void Notification.requestPermission();
          }} />}
          {mostra("alertasDaLoja", "comportamento") && <Chave id="alertasDaLoja" titulo="Alertas da loja" descricao="Pedido novo, estoque baixo e pagamentos." ligado={prefs.alertasDaLoja} aoMudar={(v) => definir("alertasDaLoja", v)} />}
          {mostra("iniciarComOSistema", "comportamento") && noDesktop() && <Chave id="iniciarComOSistema" titulo="Iniciar com o sistema" descricao="Abre junto com o computador, escondida na bandeja. Vale só neste computador." ligado={prefs.iniciarComOSistema} aoMudar={(v) => definir("iniciarComOSistema", v)} />}

          {mostra("atualizacoes", "comportamento") && noDesktop() && <Cartao id="atualizacoes" titulo="Atualizações"><Atualizacoes /></Cartao>}

          <Titulo cat="conexoes" />
          {mostra("servidor", "conexoes") && <Cartao id="servidor" titulo="Conexão com o servidor"><Servidor /></Cartao>}
          {mostra("canais", "conexoes") && <Cartao id="canais" titulo="Canais de venda" descricao="Nuvemshop, Mercado Livre e o seu site (até 3) são conectados na Central da loja do app Android, e o estoque sincroniza sozinho." />}

          <Titulo cat="seguranca" />
          {mostra("pagamentos", "conexoes") && (
            <Cartao id="pagamentos" titulo="Pagamentos e chaves do lojista" descricao="O que é seu e o que a Vetorial Tech já entrega.">
              <ul className="list-disc space-y-1.5 pl-5 text-sm text-slate-300">
                <li><b>Seu:</b> a chave de pagamento (Mercado Pago ou InfinitePay). Sem ela, link de cobrança, Pix e conferência automática de pagamento não funcionam. Cada lojista usa a sua, e o dinheiro cai direto na sua conta.</li>
                <li><b>Seu:</b> os dados da empresa, o nicho, as formas de pagamento aceitas e as lojas conectadas (Nuvemshop, Mercado Livre, site). Cada conta tem os seus, separados dos de outros lojistas.</li>
                <li><b>Sem site?</b> Cadastre a loja direto na Vênus: ela cria o catálogo e os links de cobrança para você.</li>
                <li><b>Da Vetorial Tech:</b> a inteligência artificial, a sincronização e o login unificado. Não precisa de chave de IA.</li>
                <li>Entre com qualquer e-mail, pessoal ou da empresa: ele vira o seu login Vetorial ID em todos os apps.</li>
              </ul>
            </Cartao>
          )}
          {mostra("conta", "seguranca") && <Cartao id="conta" titulo="Conta Vetorial ID" descricao="Um login só para Vênus, Vindex e Vortex em todos os aparelhos.">{sessao ? <ContaLogada /> : <Entrar />}</Cartao>}
          {mostra("licenca", "seguranca") && <Cartao id="licenca" titulo="Plano Fundadores" descricao="R$ 49,90 por mês, com 7 dias de teste grátis. Veja a validade e renove na seção Conta acima." />}

          <Titulo cat="dispositivos" />
          {mostra("dispositivos", "dispositivos") && <Cartao id="dispositivos" titulo="Meus dispositivos" descricao="Onde a sua conta está conectada agora."><MeusDispositivos /></Cartao>}
          <Cartao id="sobre" titulo="Sobre a Vênus" descricao="Rever a apresentação das funções, ler os termos que você aceitou ou ver tudo o que já mudou.">
            <div className="flex flex-wrap gap-2">
              <button type="button" className={botaoSuave} onClick={() => setSobre("tour")}>Rever a apresentação</button>
              <button type="button" className={botaoSuave} onClick={() => setSobre("termos")}>Termos de uso</button>
              <button type="button" className={botaoSuave} onClick={() => setSobre("novidades")}>O que há de novo</button>
            </div>
          </Cartao>
          {sobre === "tour" && <div className="fixed inset-0 z-50 flex flex-col bg-fundo"><div className="mx-auto flex min-h-0 w-full max-w-lg flex-1 flex-col px-5 pb-4 pt-6"><Tour aoTerminar={() => setSobre("")} aoFechar={() => setSobre("")} rotuloFinal="Fechar" /></div></div>}
          {sobre === "termos" && <Termos aoFechar={() => setSobre("")} />}
          {sobre === "novidades" && <Novidades itens={novasDesde(0, NOVIDADES)} aoFechar={() => setSobre("")} />}
        </div>
      </div>
    </div>
    </PaginaComLateral>
  );
}
