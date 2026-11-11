import type { ReactNode } from "react";

/** Cartão de vidro fosco usado em todas as seções dos ajustes. */
export function Cartao({ id, titulo, descricao, children }: { id: string; titulo: string; descricao?: string; children?: ReactNode }) {
  return (
    <section id={`aj-${id}`} className="scroll-mt-24 rounded-3xl border border-white/10 bg-white/[0.06] p-4 shadow-lg shadow-black/20 backdrop-blur-xl transition-all duration-300 sm:p-5">
      <h3 className="text-base font-semibold">{titulo}</h3>
      {descricao && <p className="mt-1 text-sm text-suave">{descricao}</p>}
      {children && <div className="mt-4">{children}</div>}
    </section>
  );
}

/** Alternância grande, fácil de tocar. */
export function Chave({ id, titulo, descricao, ligado, aoMudar, desabilitado }: {
  id: string; titulo: string; descricao: string; ligado: boolean; aoMudar: (v: boolean) => void; desabilitado?: boolean;
}) {
  return (
    <section id={`aj-${id}`} className="scroll-mt-24 flex items-center gap-4 rounded-3xl border border-white/10 bg-white/[0.06] p-4 backdrop-blur-xl sm:p-5">
      <div className="min-w-0 flex-1">
        <h3 className="text-base font-semibold">{titulo}</h3>
        <p className="mt-1 text-sm text-suave">{descricao}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={ligado}
        aria-label={titulo}
        disabled={desabilitado}
        onClick={() => aoMudar(!ligado)}
        className={`relative h-9 w-16 shrink-0 rounded-full transition-colors duration-200 disabled:opacity-40 ${ligado ? "bg-venus" : "bg-white/15"}`}
      >
        <span className={`absolute top-1 size-7 rounded-full bg-white shadow transition-transform duration-200 ${ligado ? "translate-x-8" : "translate-x-1"}`} />
      </button>
    </section>
  );
}

export const botao = "min-h-11 rounded-2xl px-4 py-2 text-sm font-semibold transition active:scale-95 disabled:opacity-50";
export const botaoPrimario = `${botao} bg-venus text-sobre-venus`;
export const botaoSuave = `${botao} border border-white/15 bg-white/10 hover:bg-white/15`;
export const campo = "w-full rounded-2xl border border-white/15 bg-black/30 px-4 py-3 text-base outline-none focus:border-venus";
