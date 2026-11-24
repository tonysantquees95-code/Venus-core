import { useState } from "react";
import { URL_API } from "@venus/nucleo";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import { corpo, DECISOES, marcarRespondida, motivos, perguntaDoMotivo, type Decisao, type Fase } from "./pesquisaAssinatura";

/** A telinha "vai assinar?": 1) a decisão, 2) o motivo. No visual do app (cantos arredondados, cores do tema), com "Agora não". */
export function PesquisaDeAssinatura({ fase, dias, plataforma, versao, aoFechar }: { fase: Fase; dias: number; plataforma: string; versao: string; aoFechar: () => void }) {
  const [decisao, setDecisao] = useState<Decisao | null>(null);
  const [motivo, setMotivo] = useState("");
  const [texto, setTexto] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState("");

  async function enviar() {
    if (!decisao) return;
    setEnviando(true); setAviso("");
    try {
      const r = await fetch(`${URL_API}/feedback`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(corpo(decisao, motivo, texto, fase, dias, plataforma, versao)) });
      if (!r.ok) throw new Error(String(r.status));
      marcarRespondida();
      aoFechar();
    } catch {
      setAviso("Não consegui enviar agora. Vamos tentar de novo amanhã.");
      setEnviando(false);
    }
  }

  const lista = decisao ? motivos(decisao) : [];
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/80 p-4" role="dialog" aria-modal="true" aria-label="Você vai assinar a Vênus?">
      <div className="flex max-h-[calc(100dvh-2rem)] w-full max-w-md flex-col gap-3 overflow-y-auto rounded-2xl border border-borda p-5" style={{ background: "var(--color-painel)" }}>
        {!decisao ? (
          <>
            <div><h2 className="text-xl font-black">Como está sendo?</h2><p className="text-sm text-suave">Você pretende assinar a Vênus? Sua resposta nos ajuda a melhorar.</p></div>
            <div role="group" aria-label="Sua decisão" className="flex flex-col gap-2">
              {DECISOES.map((d) => <button key={d.id} onClick={() => setDecisao(d.id)} className={`${botaoSuave} w-full py-3 text-base`}>{d.nome}</button>)}
            </div>
            <button onClick={aoFechar} className="min-h-11 text-sm text-suave underline">Agora não</button>
          </>
        ) : (
          <>
            <div><h2 className="text-xl font-black">Só mais uma</h2><p className="text-sm text-suave">{perguntaDoMotivo(decisao)}</p></div>
            {lista.length > 0 && (
              <div role="radiogroup" aria-label="Motivo" className="flex flex-col gap-2">
                {lista.map((m) => (
                  <button key={m.id} role="radio" aria-checked={motivo === m.id} onClick={() => setMotivo(m.id)}
                    className={`min-h-11 rounded-2xl border px-4 py-2 text-left text-sm font-semibold transition ${motivo === m.id ? "border-venus bg-venus/15 text-venus" : "border-white/15 bg-white/10"}`}>
                    {motivo === m.id ? "✓ " : ""}{m.nome}
                  </button>
                ))}
              </div>
            )}
            <textarea value={texto} onChange={(e) => setTexto(e.target.value.slice(0, 600))} rows={3} aria-label="Conte mais" placeholder={decisao === "vai" ? "Conte em poucas palavras (opcional)" : "Quer contar mais? (opcional)"}
              className="w-full resize-none rounded-2xl border border-white/15 bg-white/10 p-3 text-sm outline-none focus:border-venus" />
            {aviso && <p role="alert" className="text-sm text-red-300">{aviso}</p>}
            <div className="flex gap-2">
              <button onClick={aoFechar} className={`${botaoSuave} flex-1`}>Agora não</button>
              <button onClick={() => void enviar()} disabled={enviando} className={`${botaoPrimario} flex-1`}>{enviando ? "Enviando…" : "Enviar"}</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
