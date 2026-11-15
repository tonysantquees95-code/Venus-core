import { useEffect, useRef, useState } from "react";
import { calar, continuarFala, falar, pausarFala } from "../../voz/useVoz";
import { nomeDoArquivo } from "../../voz/logica";

type Leitura = "parada" | "lendo" | "pausada";

/** Embaixo de cada resposta da Vênus: ouvir (com pausa), compartilhar e guardar. */
export function AcoesDaResposta({ texto }: { texto: string }) {
  const [leitura, setLeitura] = useState<Leitura>("parada");
  const [aviso, setAviso] = useState("");
  const atual = useRef<Leitura>("parada");
  atual.current = leitura;
  // sair da conversa no meio da leitura cala a voz
  useEffect(() => () => { if (atual.current !== "parada") calar(); }, []);

  function ouvir() {
    if (leitura === "lendo") { pausarFala(); setLeitura("pausada"); return; }
    if (leitura === "pausada") { continuarFala(); setLeitura("lendo"); return; }
    setLeitura("lendo");
    falar(texto, () => setLeitura("parada"));
  }

  async function compartilhar() {
    try {
      if (typeof navigator.share === "function") { await navigator.share({ text: texto }); return; }
      await navigator.clipboard.writeText(texto);
      setAviso("Texto copiado para você colar onde quiser.");
    } catch {
      /* a pessoa fechou a janela de compartilhar */
    }
  }

  function guardar() {
    try {
      const url = URL.createObjectURL(new Blob([texto], { type: "text/plain;charset=utf-8" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = nomeDoArquivo(texto);
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
      setAviso("Texto guardado na pasta de downloads.");
    } catch {
      setAviso("Não consegui guardar o texto.");
    }
  }

  const botao = "rounded-full border border-borda px-3 py-1 text-xs text-suave transition hover:border-venus hover:text-texto active:scale-95";
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2">
      <button type="button" onClick={ouvir} className={botao} aria-label="Ouvir a resposta em voz alta">
        {leitura === "lendo" ? "Pausar" : leitura === "pausada" ? "Continuar" : "Ouvir"}
      </button>
      <button type="button" onClick={() => void compartilhar()} className={botao}>Compartilhar</button>
      <button type="button" onClick={guardar} className={botao}>Guardar</button>
      {aviso && <span role="status" className="text-[11px] text-suave">{aviso}</span>}
    </div>
  );
}
