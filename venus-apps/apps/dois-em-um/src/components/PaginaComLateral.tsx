import { useEffect, useState, type ReactNode } from "react";

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
  /** No celular a página abre direto numa seção (vindo de um atalho) em vez de mostrar a lista. */
  abrirDireto?: boolean;
}

/**
 * Página com uma segunda barra lateral, ao lado da barra principal (Chat, Robô, Empresa, Tema, Ajustes):
 * escolhe-se a seção e ela ocupa a tela inteira. Em tela estreita a barra vira abas no alto.
 */
export function PaginaComLateral({ titulo, itens, atual, aoEscolher, topo, rodape, children, abrirDireto }: Props) {
  let grupoAnterior: string | undefined;
  // Celular: primeiro a lista de seções por pasta (como o menu do app Android), e a seção escolhida abre inteira, com "Voltar".
  // Tela larga: a barra lateral fica sempre ao lado.
  const [aberta, setAberta] = useState(!!abrirDireto);
  useEffect(() => { if (abrirDireto) setAberta(true); }, [abrirDireto]);
  useEffect(() => { if (atual === "") setAberta(true); }, [atual]); // busca ativa: mostra os resultados
  const tituloAtual = itens.find((i) => i.id === atual)?.rotulo;
  return (
    <section aria-label={titulo} className="flex min-h-0 min-w-0 flex-1 flex-col md:flex-row">
      <aside className={`${aberta ? "hidden md:flex" : "flex"} min-h-0 flex-1 flex-col bg-painel/60 md:w-64 md:flex-none md:border-r md:border-borda`}>
        <div className="hidden px-5 pb-2 pt-5 md:block">
          <h1 className="text-sm font-semibold uppercase tracking-[0.3em]">{titulo}</h1>
        </div>
        {topo && <div className="px-3 pt-3 md:px-4 md:pt-2">{topo}</div>}
        <nav aria-label={`Seções de ${titulo}`} className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto overscroll-contain px-3 py-3">
          {itens.map((i) => {
            const ativo = i.id === atual;
            const cab = i.grupo && i.grupo !== grupoAnterior ? i.grupo : null;
            grupoAnterior = i.grupo ?? grupoAnterior;
            return (
              <div key={i.id} className="contents">
                {cab && <p className="px-3 pb-1 pt-4 text-[11px] font-semibold uppercase tracking-widest text-suave">{cab}</p>}
                <button
                  type="button"
                  onClick={() => { aoEscolher(i.id); setAberta(true); }}
                  aria-current={ativo ? "page" : undefined}
                  className={`flex w-full shrink-0 items-center gap-3 rounded-xl px-3 py-3 text-left text-base transition-colors md:py-2.5 md:text-sm
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
      <div className={`${aberta ? "flex" : "hidden md:flex"} min-h-0 min-w-0 flex-1 flex-col`}>
        <button type="button" onClick={() => setAberta(false)} className="flex min-h-11 shrink-0 items-center gap-1 border-b border-borda px-4 text-sm font-semibold text-venus md:hidden">‹ {titulo}{tituloAtual ? ` · ${tituloAtual}` : ""}</button>
        <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain">{children}</div>
      </div>
    </section>
  );
}
