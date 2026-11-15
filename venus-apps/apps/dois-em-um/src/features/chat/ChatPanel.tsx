import { useEffect, useRef, useState, type FormEvent } from "react";
import { IconeEnviar, IconeMais, IconeMenu, IconeMicrofone } from "../../components/Icones";
import { HolographicOrb } from "../../theming";
import { useChat } from "../../hooks/useChat";
import { useConversas } from "../../sessao/ConversasProvider";
import { useVoz } from "../../voz/useVoz";
import { AvatarDeConta } from "../../id/Avatar";
import { useCatalogo } from "../../empresa/CatalogoProvider";
import { AcoesDaResposta } from "./AcoesDaResposta";
import { merecemAcoes } from "../../voz/logica";

export function ChatPanel({ aoAbrirMenu }: { aoAbrirMenu: () => void }) {
  const { mensagens, enviando, enviar } = useChat();
  const { ativa, novaConversa } = useConversas();
  const catalogo = useCatalogo();
  const [texto, setTexto] = useState("");
  const fim = useRef<HTMLDivElement>(null);
  // falou: o que foi entendido vai direto para a Vênus, como no celular
  const voz = useVoz((t) => void enviar(t, true));

  const estadoOrbe = voz.estado === "gravando" ? "ouvindo" : enviando || voz.estado === "transcrevendo" ? "pensando" : "ocioso";

  useEffect(() => {
    fim.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [mensagens, enviando]);

  function aoEnviar(e: FormEvent) {
    e.preventDefault();
    void enviar(texto);
    setTexto("");
  }

  return (
    <section aria-label="Chat com a Vênus" className="flex min-h-0 flex-1 flex-col">
      <header className="flex items-center gap-2 border-b border-borda px-2 py-2.5">
        <button type="button" onClick={aoAbrirMenu} aria-label="Abrir menu e histórico" className="grid size-11 place-items-center rounded-full text-suave hover:text-texto">
          <IconeMenu />
        </button>
        <HolographicOrb tamanho={64} estado={estadoOrbe} nivelVoz={0.3} className="-mx-3 -my-4" />
        <h2 className="min-w-0 flex-1 truncate text-base font-semibold">{mensagens.length === 0 ? "Nova conversa" : ativa.titulo}</h2>
        <button
          type="button"
          onClick={novaConversa}
          aria-label="Nova conversa"
          className="grid size-11 place-items-center rounded-full bg-venus text-sobre-venus shadow-md shadow-venus/30 active:scale-95"
        >
          <IconeMais />
        </button>
        <AvatarDeConta />
      </header>

      {catalogo.estado === "atualizando" && catalogo.produtos.length === 0 && (
        <p role="status" className="border-b border-borda px-4 py-1.5 text-center text-xs text-suave">Buscando os produtos e preços da loja…</p>
      )}

      <ol className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 py-4" aria-live="polite">
        {mensagens.map((m) => (
          <li
            key={m.id}
            className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-4 py-2.5 text-sm leading-relaxed
              ${m.autor === "eu" ? "self-end bg-venus text-sobre-venus" : "self-start border border-borda bg-painel"}`}
          >
            {m.texto}
            {m.autor === "venus" && merecemAcoes(m.texto) && <AcoesDaResposta texto={m.texto} />}
            {m.autor === "venus" && m.detalhe && <span className="mt-1 block text-[10px] leading-tight text-suave/60">{m.detalhe}</span>}
          </li>
        ))}
        {mensagens.length === 0 && (
          <li className="m-auto flex max-w-xs flex-col items-center gap-4 text-center text-suave">
            <HolographicOrb tamanho={240} estado={estadoOrbe} />
            <p className="text-sm">Oi! Eu sou a Vênus. Esta é uma conversa nova: pergunte o que quiser ou peça para o Robô trabalhar por você.</p>
          </li>
        )}
        {enviando && <li className="self-start text-sm text-suave">Vênus está digitando…</li>}
        <div ref={fim} />
      </ol>

      {voz.erro && <p role="alert" className="px-4 pb-1 text-xs text-rose-400">{voz.erro}</p>}
      <form onSubmit={aoEnviar} className="flex gap-2 border-t border-borda p-3">
        <input
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          placeholder="Escreva para a Vênus…"
          className="min-w-0 flex-1 rounded-full border border-borda bg-painel px-4 py-2.5 text-sm outline-none focus:border-venus"
        />
        <button
          type="button"
          onClick={voz.alternar}
          disabled={enviando || voz.estado === "transcrevendo"}
          aria-label={voz.estado === "gravando" ? "Parar de gravar e enviar" : "Falar com a Vênus"}
          aria-pressed={voz.estado === "gravando"}
          title={voz.estado === "gravando" ? "Gravando… toque para enviar" : voz.estado === "transcrevendo" ? "Entendendo o que você disse…" : "Falar com a Vênus"}
          className={`grid size-11 place-items-center rounded-full border border-borda transition active:scale-95 disabled:opacity-40
            ${voz.estado === "gravando" ? "animate-pulse bg-rose-500 text-white" : "bg-painel text-suave hover:text-texto"}`}
        >
          <IconeMicrofone />
        </button>
        <button
          type="submit"
          disabled={enviando || !texto.trim()}
          aria-label="Enviar"
          className="grid size-11 place-items-center rounded-full bg-venus text-sobre-venus disabled:opacity-40"
        >
          <IconeEnviar />
        </button>
      </form>
    </section>
  );
}
