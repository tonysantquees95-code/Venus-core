import type { ReactNode } from "react";

export interface ItemLateral {
  id: string;
  rotulo: string;
  icone?: ReactNode;
  /** Título pequeno que separa grupos na barra (aparece quando muda de um item para o outro). */
  grupo?: string;
}

interface Props {
  titulo: string;
  itens: ItemLateral[];
  atual: string;
  aoEscolher: (id: string) => void;
  /** Fica no alto da barra (ex.: campo de busca). */
  topo?: ReactNode;
  /** Fica no pé da barra. */
  rodape?: ReactNode;
  children: ReactNode;
}

/**
 * Página com uma segunda barra lateral, ao lado da barra principal (Chat, Robô, Empresa, Tema, Ajustes):
 * escolhe-se a seção e ela ocupa a tela inteira. Em tela estreita a barra vira abas no alto.
 */
export function PaginaComLateral({ titulo, itens, atual, aoEscolher, topo, rodape, children }: Props) {
  let grupoAnterior: string | undefined;
  return (
    <section aria-label={titulo} className="flex min-h-0 min-w-0 flex-1 flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-borda bg-painel/60 md:w-64 md:border-b-0 md:border-r">
        <div className="hidden px-5 pb-2 pt-5 md:block">
          <h1 className="text-sm font-semibold uppercase tracking-[0.3em]">{titulo}</h1>
        </div>
        {topo && <div className="px-3 pt-3 md:px-4 md:pt-2">{topo}</div>}
        <nav aria-label={`Seções de ${titulo}`} className="flex gap-1 overflow-x-auto px-3 py-2 md:min-h-0 md:flex-1 md:flex-col md:gap-0.5 md:overflow-y-auto md:px-3 md:py-3">
          {itens.map((i) => {
            const ativo = i.id === atual;
            const cab = i.grupo && i.grupo !== grupoAnterior ? i.grupo : null;
            grupoAnterior = i.grupo ?? grupoAnterior;
            return (
              <div key={i.id} className="contents">
                {cab && <p className="hidden px-3 pb-1 pt-4 text-[11px] font-semibold uppercase tracking-widest text-suave md:block">{cab}</p>}
                <button
                  type="button"
                  onClick={() => aoEscolher(i.id)}
                  aria-current={ativo ? "page" : undefined}
                  className={`flex shrink-0 items-center gap-3 whitespace-nowrap rounded-xl px-3 py-2.5 text-left text-sm transition-colors md:w-full md:whitespace-normal
                    ${ativo ? "bg-venus/15 font-semibold text-venus ring-1 ring-venus/40" : "text-suave hover:bg-white/5 hover:text-texto"}`}
                >
                  {i.icone && <span className="grid size-5 shrink-0 place-items-center text-base" aria-hidden>{i.icone}</span>}
                  <span className="min-w-0 flex-1">{i.rotulo}</span>
                </button>
              </div>
            );
          })}
        </nav>
        {rodape && <div className="hidden border-t border-borda px-4 py-3 md:block">{rodape}</div>}
      </aside>
      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto">{children}</div>
    </section>
  );
}
