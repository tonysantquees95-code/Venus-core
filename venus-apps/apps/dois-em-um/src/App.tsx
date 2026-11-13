import { useEffect, useRef, useState, type TouchEvent } from "react";
import { Navegacao } from "./components/Navegacao";
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
import { useId } from "./id/IdProvider";
import { TelaLogin } from "./id/TelaLogin";
import { TelaBloqueio, TelaValidando } from "./id/TelaBloqueio";
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
  const [alvoAjuste, setAlvoAjuste] = useState<string | undefined>();
  useAtalhoGlobal(() => setTela("chat"));
  const { sessao, estado } = useId();
  // Avatar, comandos de conversa e o Windows pedem telas por este evento
  useEffect(() => {
    const ouvir = (e: Event) => {
      const { tela: t, alvo } = (e as CustomEvent<{ tela: "ajustes" | "empresa" | "login"; alvo?: string }>).detail;
      if (t === "login") return;
      if (t === "empresa") return setTela("empresa");
      setTela("ajustes");
      setAlvoAjuste(alvo ? `${alvo}` : undefined);
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

  // Trava de segurança: sem Vetorial ID e sem selo de licença válido o aplicativo não abre.
  if (!sessao) return <TelaLogin />;
  if (estado.tipo === "teste_acabou") return <TelaBloqueio />;
  if (estado.tipo === "validar_online") return <TelaValidando />;

  return (
    <div onTouchStart={aoTocar} onTouchEnd={aoSoltar} className="flex h-dvh flex-col pt-[env(safe-area-inset-top)] md:flex-row">
      <SidebarDrawer aberto={menuAberto} aoFechar={() => setMenuAberto(false)} aoNavegar={() => { setTela("chat"); setMenuAberto(false); }} />
      <Navegacao tela={tela} aoMudar={setTela} roboLigado={robo.ligado} />
      <main className="flex min-h-0 min-w-0 flex-1">
        {tela === "empresa" ? (
          <TelaEmpresa />
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
      <Sincronizador />
      <AvisoDeConflito />
      <InstalarIOS />
    </div>
  );
}
