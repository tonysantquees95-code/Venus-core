import { useEffect, useMemo, useRef, useState, type TouchEvent } from "react";
import { useConversas } from "../sessao/ConversasProvider";
import { agrupar } from "../sessao/logica";
import { IconeChat, IconeFechar, IconeLixeira, IconeMais } from "./Icones";

interface Props {
  aberto: boolean;
  aoFechar: () => void;
  /** Chamado depois de "Nova conversa" ou de escolher uma do histórico (o App volta para o chat). */
  aoNavegar: () => void;
}

/** Menu lateral em gaveta: botão "Nova conversa" em destaque + histórico salvo. */
export function SidebarDrawer({ aberto, aoFechar, aoNavegar }: Props) {
  const { ativa, historico, novaConversa, abrir, excluir, apagarHistorico } = useConversas();
  const [confirmar, setConfirmar] = useState<string | null>(null); // id, ou "tudo"
  const botaoNova = useRef<HTMLButtonElement>(null);
  const toqueX = useRef<number | null>(null);
  const grupos = useMemo(() => agrupar(historico), [historico]);

  // Esc fecha; foco entra na gaveta ao abrir e volta ao botão de origem ao fechar; fundo não rola.
  useEffect(() => {
    if (!aberto) {
      setConfirmar(null);
      return;
    }
    const origem = document.activeElement as HTMLElement | null;
    const rolagem = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    botaoNova.current?.focus();
    const aoTeclar = (e: KeyboardEvent) => e.key === "Escape" && aoFechar();
    document.addEventListener("keydown", aoTeclar);
    return () => {
      document.removeEventListener("keydown", aoTeclar);
      document.body.style.overflow = rolagem;
      origem?.focus?.();
    };
  }, [aberto, aoFechar]);

  const iniciar = () => {
    novaConversa();
    aoNavegar();
  };
  const escolher = (id: string) => {
    abrir(id);
    aoNavegar();
  };

  // Arrastar a gaveta para a esquerda fecha.
  const aoTocar = (e: TouchEvent) => (toqueX.current = e.touches[0]?.clientX ?? null);
  const aoSoltar = (e: TouchEvent) => {
    const fim = e.changedTouches[0]?.clientX;
    if (toqueX.current !== null && fim !== undefined && fim - toqueX.current < -60) aoFechar();
    toqueX.current = null;
  };

  return (
    <>
      <div
        onClick={aoFechar}
        aria-hidden
        className={`fixed inset-0 z-30 bg-black/60 backdrop-blur-[2px] transition-opacity duration-300 ${aberto ? "opacity-100" : "pointer-events-none opacity-0"}`}
      />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="Menu e histórico de conversas"
        aria-hidden={!aberto}
        onTouchStart={aoTocar}
        onTouchEnd={aoSoltar}
        className={`fixed inset-y-0 left-0 z-40 flex w-[86vw] max-w-sm flex-col border-r border-borda bg-painel pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]
          shadow-2xl transition-[transform,visibility] duration-300 ease-out ${aberto ? "visible translate-x-0" : "invisible -translate-x-full"}`}
      >
        <header className="flex items-center justify-between px-4 py-3">
          <span className="font-semibold tracking-[0.3em] text-venus">VÊNUS</span>
          <button type="button" onClick={aoFechar} aria-label="Fechar menu" className="grid size-10 place-items-center rounded-full text-suave hover:text-texto">
            <IconeFechar />
          </button>
        </header>

        <div className="px-4 pb-3">
          <button
            ref={botaoNova}
            type="button"
            onClick={iniciar}
            className="flex w-full items-center justify-center gap-2 rounded-2xl bg-venus px-4 py-3.5 text-base font-semibold text-sobre-venus shadow-lg shadow-venus/30 active:scale-[0.98]"
          >
            <IconeMais width={22} height={22} />
            Nova conversa
          </button>
        </div>

        <nav aria-label="Conversas salvas" className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
          {grupos.length === 0 ? (
            <p className="px-3 py-6 text-center text-sm text-suave">Suas conversas aparecem aqui.</p>
          ) : (
            grupos.map((g) => (
              <section key={g.rotulo} className="mb-3">
                <h3 className="px-3 pb-1 pt-2 text-xs font-semibold uppercase tracking-widest text-suave">{g.rotulo}</h3>
                <ul className="flex flex-col gap-0.5">
                  {g.itens.map((c) => {
                    const atual = c.id === ativa.id;
                    return (
                      <li key={c.id} className={`group flex items-center rounded-xl ${atual ? "bg-venus/10" : "hover:bg-white/5"}`}>
                        <button
                          type="button"
                          onClick={() => escolher(c.id)}
                          aria-current={atual ? "true" : undefined}
                          className="flex min-w-0 flex-1 items-center gap-3 px-3 py-3 text-left"
                        >
                          <IconeChat width={18} height={18} className={atual ? "text-venus" : "text-suave"} />
                          <span className="min-w-0 flex-1 truncate text-sm">{c.titulo}</span>
                        </button>
                        {confirmar === c.id ? (
                          <button
                            type="button"
                            onClick={() => { excluir(c.id); setConfirmar(null); }}
                            className="mr-2 rounded-full bg-red-500/90 px-3 py-1.5 text-xs font-semibold text-white"
                          >
                            Apagar?
                          </button>
                        ) : (
                          <button
                            type="button"
                            onClick={() => setConfirmar(c.id)}
                            aria-label={`Apagar conversa ${c.titulo}`}
                            className="mr-1 grid size-9 place-items-center rounded-full text-suave hover:text-texto"
                          >
                            <IconeLixeira width={18} height={18} />
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))
          )}
        </nav>

        {historico.length > 0 && (
          <footer className="border-t border-borda px-4 py-3">
            {confirmar === "tudo" ? (
              <button type="button" onClick={() => { apagarHistorico(); setConfirmar(null); }} className="w-full rounded-xl bg-red-500/90 py-2 text-sm font-semibold text-white">
                Toque de novo para apagar tudo
              </button>
            ) : (
              <button type="button" onClick={() => setConfirmar("tudo")} className="w-full py-2 text-sm text-suave hover:text-texto">
                Apagar todo o histórico
              </button>
            )}
          </footer>
        )}
      </aside>
    </>
  );
}
