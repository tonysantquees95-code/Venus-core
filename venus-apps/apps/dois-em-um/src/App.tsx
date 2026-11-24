import { useEffect, useRef, useState, type TouchEvent } from "react";
import { SidebarDrawer } from "./components/SidebarDrawer";
import { useConversas } from "./sessao/ConversasProvider";
import { ChatPanel } from "./features/chat/ChatPanel";
import { RoboPanel } from "./features/robo/RoboPanel";
import { ThemingPanel } from "./theming";
import { TelaEmpresa } from "./empresa/TelaEmpresa";
import { Ajustes } from "./ajustes/Ajustes";
import { AvisoDeConflito } from "./ajustes/Conta";
import { InstalarIOS } from "./ios/InstalarIOS";
import { Sincronizador } from "./sync/Sincronizador";
import { useAtalhoGlobal } from "./desktop/useAtalhoGlobal";
import { meuAparelho, useId } from "./id/IdProvider";
import { ponteDesktop } from "./desktop/ponte";
import { TelaLogin } from "./id/TelaLogin";
import { TelaBloqueio, TelaValidando } from "./id/TelaBloqueio";
import { useRobo } from "./hooks/useRobo";
import type { Tela } from "./tipos";
import { Apresentacao, Novidades } from "./apresentacao/Apresentacao";
import { PesquisaDeAssinatura } from "./apresentacao/PesquisaDeAssinatura";
import { deveMostrar as devePerguntarSeAssina, diasUsados, faseDaLicenca, lerEstado as lerEstadoDaPesquisa, marcarMostrada as marcarPesquisaMostrada } from "./apresentacao/pesquisaAssinatura";
import { lerVisto, novasDesde, precisaApresentar, type Novidade } from "./apresentacao/dados";
import { CriarLoja, lojaGuiadaPulada } from "./empresa/CriarLoja";
import { useEmpresa } from "./empresa/EmpresaProvider";
import { lojaJaConfigurada } from "./empresa/logica";
import { useCatalogo } from "./empresa/CatalogoProvider";
import { usePreferencias } from "./ajustes/Preferencias";
import { CHAVE_PAINEL, chaveDoDia, deveMostrar, montarPainel } from "./ajustes/painelDoDia";

const TITULOS: Record<Tela, string> = { chat: "Chat", robo: "Vênus Robô", empresa: "Minha loja", tema: "Aparência", ajustes: "Ajustes" };

/**
 * Uma interface só, igual ao app Android: o chat é a tela principal e o resto abre pelo menu em gaveta (sem barra fixa).
 */
export function App() {
  const [tela, setTela] = useState<Tela>("chat");
  const robo = useRobo();
  const { ativa } = useConversas();
  const [menuAberto, setMenuAberto] = useState(false);
  const [alvoAjuste, setAlvoAjuste] = useState<string | undefined>();
  const [alvoEmpresa, setAlvoEmpresa] = useState<string | undefined>();
  const { empresa } = useEmpresa();
  const [criandoLoja, setCriandoLoja] = useState(false);
  const [lojaPulada, setLojaPulada] = useState(() => lojaGuiadaPulada());
  useAtalhoGlobal(() => setTela("chat"));
  const { sessao, estado, conta } = useId();
  const { anexar } = useConversas();
  const catalogo = useCatalogo();
  const { prefs } = usePreferencias();
  // painel do dia: uma vez por dia, ao abrir, escreve no chat um resumo curto (igual ao do app Android)
  const painelFeito = useRef(false);
  useEffect(() => {
    if (painelFeito.current || estado.tipo !== "livre" || precisaApresentar()) return;
    let ultimo: string | null = null;
    try { ultimo = localStorage.getItem(CHAVE_PAINEL); } catch { /* sem armazenamento: mostra só desta vez */ }
    const agora = new Date();
    if (!deveMostrar(prefs.painelDoDia, ultimo, agora)) return;
    painelFeito.current = true;
    try { localStorage.setItem(CHAVE_PAINEL, chaveDoDia(agora)); } catch { /* ok */ }
    void catalogo.garantir(15 * 60_000).catch(() => []).then((itens) => {
      const texto = montarPainel(null, itens.filter((p) => p.esgotado).map((p) => p.nome), itens.length > 0, agora);
      anexar(ativa.id, { id: crypto.randomUUID(), autor: "venus", texto, quando: Date.now() });
    });
  }, [estado.tipo]); // eslint-disable-line react-hooks/exhaustive-deps
  // Avatar, comandos de conversa e o Windows pedem telas por este evento
  useEffect(() => {
    const ouvir = (e: Event) => {
      const { tela: t, alvo } = (e as CustomEvent<{ tela: "ajustes" | "empresa" | "login"; alvo?: string }>).detail;
      if (t === "login") return;
      if (t === "empresa") { setAlvoEmpresa(alvo); return setTela("empresa"); }
      setTela("ajustes");
      setAlvoAjuste(alvo ? `${alvo}` : undefined);
    };
    window.addEventListener("venus:abrir", ouvir);
    return () => window.removeEventListener("venus:abrir", ouvir);
  }, []);
  // pedido para refazer o passo a passo da loja (Empresa › Identidade da loja)
  useEffect(() => {
    const ouvir = () => setCriandoLoja(true);
    window.addEventListener("venus:criar-loja", ouvir);
    return () => window.removeEventListener("venus:criar-loja", ouvir);
  }, []);
  // Logo depois do login: quem ainda não tem loja cadastrada (nem aqui, nem no celular) passa pelo passo a passo.
  // Espera um instante para o cadastro da conta chegar da nuvem e não perguntar de novo a quem já tem loja no celular.
  const logado = !!sessao;
  useEffect(() => {
    if (!logado || lojaPulada || lojaJaConfigurada(empresa, false)) return;
    const t = setTimeout(() => setCriandoLoja(true), 1800);
    return () => clearTimeout(t);
  }, [logado, lojaPulada, empresa]);
  const [apresentar, setApresentar] = useState(() => precisaApresentar());
  const [novidades, setNovidades] = useState<Novidade[]>(() => { const v = lerVisto(); return v === null ? [] : novasDesde(v); });
  // pesquisa "vai assinar?": depois das novidades, uma vez por dia no máximo (a regra está em pesquisaAssinatura.ts)
  const [pesquisa, setPesquisa] = useState(false);
  const pesquisaVista = useRef(false);
  useEffect(() => {
    if (pesquisaVista.current || apresentar || novidades.length > 0 || !conta) return;
    const fase = faseDaLicenca(conta.licenca), dias = diasUsados(conta.licenca), e = lerEstadoDaPesquisa();
    if (!devePerguntarSeAssina(fase, dias, e.ultimoDia, e.respondidaEm, new Date())) return;
    pesquisaVista.current = true;
    marcarPesquisaMostrada();
    const t = setTimeout(() => setPesquisa(true), 2500);
    return () => clearTimeout(t);
  }, [conta, apresentar, novidades.length]);
  const inicioToque = useRef<{ x: number; y: number } | null>(null);

  // Trocou, abriu ou criou conversa (inclui reabrir o app): volta para o chat e recolhe a gaveta.
  useEffect(() => {
    setTela("chat");
    setMenuAberto(false);
  }, [ativa.id]);

  // Deslizar a partir da borda esquerda abre a gaveta.
  const aoTocar = (e: TouchEvent) => {
    const t = e.touches[0];
    inicioToque.current = t && t.clientX < 24 ? { x: t.clientX, y: t.clientY } : null;
  };
  const aoSoltar = (e: TouchEvent) => {
    const t = e.changedTouches[0];
    const i = inicioToque.current;
    inicioToque.current = null;
    if (t && i && t.clientX - i.x > 60 && Math.abs(t.clientY - i.y) < 50) setMenuAberto(true);
  };

  // primeira abertura: tour, o que o app usa e termos (antes mesmo do login)
  if (apresentar) return <Apresentacao aoConcluir={() => { setNovidades([]); setApresentar(false); }} />;

  // Trava de segurança: sem Vetorial ID e sem selo de licença válido o aplicativo não abre.
  if (!sessao) return <TelaLogin />;
  if (estado.tipo === "teste_acabou") return <TelaBloqueio />;
  if (estado.tipo === "validar_online") return <TelaValidando />;

  return (
    <div onTouchStart={aoTocar} onTouchEnd={aoSoltar} className="flex h-dvh flex-col pt-[env(safe-area-inset-top)]">
      <SidebarDrawer aberto={menuAberto} aoFechar={() => setMenuAberto(false)} aoNavegar={() => { setTela("chat"); setMenuAberto(false); }}
        roboLigado={robo.ligado} aoIr={(t) => { setTela(t); setMenuAberto(false); }} />
      <main className="flex min-h-0 min-w-0 flex-1 flex-col">
        {tela !== "chat" && (
          <div className="flex shrink-0 items-center gap-2 border-b border-borda bg-painel px-2 py-2">
            <button type="button" onClick={() => setTela("chat")} aria-label="Voltar para o chat" className="flex min-h-11 items-center gap-1 rounded-xl px-3 text-sm font-semibold text-venus">‹ Chat</button>
            <span className="font-semibold">{TITULOS[tela]}</span>
          </div>
        )}
        {tela === "empresa" ? (
          <TelaEmpresa alvo={alvoEmpresa} />
        ) : tela === "tema" ? (
          <ThemingPanel />
        ) : tela === "ajustes" ? (
          <Ajustes aoAbrirTema={() => setTela("tema")} alvo={alvoAjuste} />
        ) : (
          <>
        <div className={`${tela === "chat" ? "flex" : "hidden"} min-w-0 flex-1`}>
          <ChatPanel aoAbrirMenu={() => setMenuAberto(true)} />
        </div>
        <div className={`${tela === "robo" ? "flex" : "hidden"} min-w-0 flex-1`}>
          <RoboPanel robo={robo} />
        </div>
          </>
        )}
      </main>
      {criandoLoja && <CriarLoja aoConcluir={() => { setCriandoLoja(false); setLojaPulada(true); }} aoIr={(secao) => { setAlvoEmpresa(secao); setTela("empresa"); }} />}
      <Sincronizador />
      <AvisoDeConflito />
      <InstalarIOS />
      {novidades.length > 0 && <Novidades itens={novidades} aoFechar={() => setNovidades([])} />}
      {pesquisa && conta && <PesquisaDeAssinatura fase={faseDaLicenca(conta.licenca)} dias={diasUsados(conta.licenca)} plataforma={["windows", "web", "ios", "mac", "android"].includes(String(meuAparelho().plataforma)) ? String(meuAparelho().plataforma) : "web"} versao={ponteDesktop()?.versao ?? ""} aoFechar={() => setPesquisa(false)} />}
    </div>
  );
}
