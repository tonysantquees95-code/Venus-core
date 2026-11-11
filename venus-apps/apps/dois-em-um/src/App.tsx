import { useEffect, useRef, useState, type TouchEvent } from "react";
import { Navegacao } from "./components/Navegacao";
import { SidebarDrawer } from "./components/SidebarDrawer";
import { useConversas } from "./sessao/ConversasProvider";
import { ChatPanel } from "./features/chat/ChatPanel";
import { RoboPanel } from "./features/robo/RoboPanel";
import { ThemingPanel } from "./theming";
import { Ajustes } from "./ajustes/Ajustes";
import { AvisoDeConflito } from "./ajustes/Conta";
import { InstalarIOS } from "./ios/InstalarIOS";
import { Sincronizador } from "./sync/Sincronizador";
import { useAtalhoGlobal } from "./desktop/useAtalhoGlobal";
import { useId } from "./id/IdProvider";
import { TelaLogin } from "./id/TelaLogin";
import { useRobo } from "./hooks/useRobo";
import type { Tela } from "./tipos";

/**
 * Uma interface só:
 *  - celular/tablet: navegação alterna entre Chat e Robô;
 *  - tela larga (xl): os dois aparecem lado a lado, e o ícone do robô só destaca o painel.
 */
export function App() {
  const [tela, setTela] = useState<Tela>("chat");
  const robo = useRobo();
  const { ativa } = useConversas();
  const [menuAberto, setMenuAberto] = useState(false);
  useAtalhoGlobal(() => setTela("chat"));
  const { sessao } = useId();
  const [loginAberto, setLoginAberto] = useState(() => {
    try { return !localStorage.getItem("venus:id:dispensado"); } catch { return true; }
  });
  // Avatar, comandos de conversa e o Windows pedem telas por este evento
  useEffect(() => {
    const ouvir = (e: Event) => {
      const { tela: t, alvo } = (e as CustomEvent<{ tela: "ajustes" | "login"; alvo?: string }>).detail;
      if (t === "login") return setLoginAberto(true);
      setTela("ajustes");
      if (alvo) setTimeout(() => document.getElementById(`aj-${alvo}`)?.scrollIntoView({ behavior: "smooth", block: "center" }), 250);
    };
    window.addEventListener("venus:abrir", ouvir);
    return () => window.removeEventListener("venus:abrir", ouvir);
  }, []);
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

  return (
    <div onTouchStart={aoTocar} onTouchEnd={aoSoltar} className="flex h-dvh flex-col pt-[env(safe-area-inset-top)] md:flex-row">
      <SidebarDrawer aberto={menuAberto} aoFechar={() => setMenuAberto(false)} aoNavegar={() => { setTela("chat"); setMenuAberto(false); }} />
      <Navegacao tela={tela} aoMudar={setTela} roboLigado={robo.ligado} />
      <main className="flex min-h-0 min-w-0 flex-1">
        {tela === "tema" ? (
          <ThemingPanel />
        ) : tela === "ajustes" ? (
          <Ajustes aoAbrirTema={() => setTela("tema")} />
        ) : (
          <>
        <div className={`${tela === "chat" ? "flex" : "hidden"} min-w-0 flex-1 xl:flex`}>
          <ChatPanel aoAbrirMenu={() => setMenuAberto(true)} />
        </div>
        <div
          className={`${tela === "robo" ? "flex" : "hidden"} min-w-0 flex-1 xl:flex xl:max-w-md xl:flex-none xl:border-l xl:border-borda
            ${tela === "robo" ? "xl:bg-robo/5" : ""}`}
        >
          <RoboPanel robo={robo} />
        </div>
          </>
        )}
      </main>
      <Sincronizador />
      <AvisoDeConflito />
      <InstalarIOS />
      {!sessao && loginAberto && (
        <TelaLogin aoContinuarSemConta={() => { setLoginAberto(false); try { localStorage.setItem("venus:id:dispensado", "1"); } catch { /* ok */ } }} />
      )}
    </div>
  );
}
