import { useEffect, useMemo, useState } from "react";
import { saudeDoServidor } from "@venus/nucleo";
import { noDesktop, ponteDesktop } from "../desktop/ponte";
import { useId } from "../id/IdProvider";
import { AvatarDeConta } from "../id/Avatar";
import { HolographicOrb, useTema } from "../theming";
import { Cartao, Chave, botaoSuave } from "./Controles";
import { ContaLogada, Entrar, MeusDispositivos } from "./Conta";
import { buscar, CATEGORIAS, MODOS, type Categoria } from "./logica";
import { usePreferencias } from "./Preferencias";

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

/** Ajustes: categorias, cartões de vidro, alternâncias grandes e busca rápida. */
export function Ajustes({ aoAbrirTema }: { aoAbrirTema: () => void }) {
  const { prefs, definir } = usePreferencias();
  const { sessao } = useId();
  const { tema } = useTema();
  const [termo, setTermo] = useState("");
  const [categoria, setCategoria] = useState<Categoria | "todas">("todas");
  const achados = useMemo(() => new Set(buscar(termo).map((i) => i.id)), [termo]);
  const mostra = (id: string, cat: Categoria) => achados.has(id) && (categoria === "todas" || categoria === cat || termo.trim() !== "");
  const algumNaCategoria = (cat: Categoria) => [...achados].some((id) => buscar(termo).find((i) => i.id === id)?.categoria === cat);

  // Busca: leva direto ao primeiro resultado.
  useEffect(() => {
    if (!termo.trim()) return;
    const primeiro = buscar(termo)[0];
    if (primeiro) document.getElementById(`aj-${primeiro.id}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [termo]);

  const Titulo = ({ cat }: { cat: Categoria }) => {
    const c = CATEGORIAS.find((x) => x.id === cat)!;
    return algumNaCategoria(cat) && (categoria === "todas" || categoria === cat || termo.trim() !== "")
      ? <h2 className="mt-6 flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-suave"><span aria-hidden>{c.icone}</span>{c.nome}</h2> : null;
  };
  const nenhum = achados.size === 0;

  return (
    <section aria-label="Ajustes" className="min-h-0 flex-1 overflow-y-auto bg-[radial-gradient(900px_500px_at_15%_-10%,rgb(var(--venus-rgb)/.14),transparent),radial-gradient(700px_500px_at_100%_110%,rgb(var(--robo-rgb)/.12),transparent)]">
      <div className="mx-auto w-full max-w-3xl px-4 pb-10 pt-4">
        <header className="sticky top-0 z-10 -mx-4 mb-2 bg-fundo/70 px-4 pb-3 pt-2 backdrop-blur-xl">
          <div className="flex items-center justify-between">
            <h1 className="text-xl font-semibold">Ajustes</h1>
            <AvatarDeConta />
          </div>
          <input
            type="search"
            value={termo}
            onChange={(e) => setTermo(e.target.value)}
            placeholder="Buscar: escuta contínua, notificações, tema…"
            aria-label="Buscar nos ajustes"
            className="mt-3 w-full rounded-2xl border border-white/15 bg-white/10 px-4 py-3 text-base outline-none placeholder:text-suave focus:border-venus"
          />
          <div className="mt-3 flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Categorias">
            {[{ id: "todas" as const, nome: "Tudo", icone: "✨" }, ...CATEGORIAS].map((c) => (
              <button key={c.id} role="tab" aria-selected={categoria === c.id} onClick={() => { setCategoria(c.id); setTermo(""); }}
                className={`shrink-0 rounded-full px-4 py-2 text-sm font-medium transition ${categoria === c.id ? "bg-venus text-sobre-venus" : "bg-white/10 text-suave hover:text-texto"}`}>
                <span aria-hidden>{c.icone} </span>{c.nome}
              </button>
            ))}
          </div>
        </header>

        {nenhum && <p className="mt-10 text-center text-suave">Nada encontrado para “{termo}”. Tente outra palavra.</p>}

        <div className="space-y-3">
          <Titulo cat="aparencia" />
          {mostra("tema", "aparencia") && (
            <Cartao id="tema" titulo="Tema, cores e orbe" descricao="Mude cores, modelo do orbe e ícones. Vale para todos os seus aparelhos.">
              <div className="flex items-center gap-4">
                <HolographicOrb tamanho={56} />
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
          {mostra("notificacoes", "comportamento") && <Chave id="notificacoes" titulo="Notificações" descricao="Avisos do sistema para pedidos, estoque e lembretes." ligado={prefs.notificacoes} aoMudar={(v) => {
            definir("notificacoes", v);
            if (v && !noDesktop() && typeof Notification !== "undefined" && Notification.permission === "default") void Notification.requestPermission();
          }} />}
          {mostra("alertasDaLoja", "comportamento") && <Chave id="alertasDaLoja" titulo="Alertas da loja" descricao="Pedido novo, estoque baixo e pagamentos." ligado={prefs.alertasDaLoja} aoMudar={(v) => definir("alertasDaLoja", v)} />}
          {mostra("iniciarComOSistema", "comportamento") && noDesktop() && <Chave id="iniciarComOSistema" titulo="Iniciar com o sistema" descricao="Abre junto com o Windows, escondida na bandeja. Vale só neste computador." ligado={prefs.iniciarComOSistema} aoMudar={(v) => definir("iniciarComOSistema", v)} />}

          <Titulo cat="conexoes" />
          {mostra("servidor", "conexoes") && <Cartao id="servidor" titulo="Conexão com o servidor"><Servidor /></Cartao>}
          {mostra("canais", "conexoes") && <Cartao id="canais" titulo="Canais de venda" descricao="Nuvemshop, Mercado Livre e o seu site (até 3) são conectados na Central da loja do app Android, e o estoque sincroniza sozinho." />}

          <Titulo cat="seguranca" />
          {mostra("conta", "seguranca") && <Cartao id="conta" titulo="Conta Vetorial ID" descricao="Um login só para Vênus, Vindex e Vortex em todos os aparelhos.">{sessao ? <ContaLogada /> : <Entrar />}</Cartao>}
          {mostra("licenca", "seguranca") && <Cartao id="licenca" titulo="Plano Fundadores" descricao="R$ 49,90 por mês, com 7 dias de teste grátis. Veja a validade e renove na seção Conta acima." />}

          <Titulo cat="dispositivos" />
          {mostra("dispositivos", "dispositivos") && <Cartao id="dispositivos" titulo="Meus dispositivos" descricao="Onde a sua conta está conectada agora."><MeusDispositivos /></Cartao>}
        </div>
      </div>
    </section>
  );
}
