import { useEffect, useState } from "react";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import { CHAVE_DISPENSADO, mostrarTutorialIOS, navegadorIOS, PASSOS_IOS } from "./logica";

/** No iPhone, explica passo a passo como instalar a Vênus na Tela de Início (só aparece fora do app instalado). */
export function InstalarIOS() {
  const [aberto, setAberto] = useState(false);
  const [passo, setPasso] = useState(0);
  const ua = navigator.userAgent;
  const nav = navegadorIOS(ua);
  const passos = PASSOS_IOS(nav);

  useEffect(() => {
    let dispensadoEm = 0;
    try { dispensadoEm = Number(localStorage.getItem(CHAVE_DISPENSADO) ?? 0); } catch { /* ok */ }
    const standalone = (navigator as { standalone?: boolean }).standalone === true || matchMedia("(display-mode: standalone)").matches;
    if (mostrarTutorialIOS({ ua, maxToque: navigator.maxTouchPoints, standalone, dispensadoEm })) {
      const t = setTimeout(() => setAberto(true), 4000);
      return () => clearTimeout(t);
    }
  }, [ua]);

  function fechar() {
    try { localStorage.setItem(CHAVE_DISPENSADO, String(Date.now())); } catch { /* ok */ }
    setAberto(false);
  }
  if (!aberto) return null;
  const p = passos[passo]!;
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 p-3 backdrop-blur-sm sm:items-center" role="dialog" aria-modal="true" aria-label="Instalar a Vênus no iPhone">
      <div className="w-full max-w-sm rounded-3xl border border-white/15 bg-painel p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))] shadow-2xl">
        <p className="text-xs font-semibold uppercase tracking-widest text-venus">Instalar no iPhone · {passo + 1} de {passos.length}</p>
        <div className="mt-4 grid place-items-center rounded-2xl bg-black/30 py-8 text-6xl" aria-hidden>{p.icone}</div>
        <h2 className="mt-4 text-lg font-semibold">{p.titulo}</h2>
        <p className="mt-1 text-sm text-suave">{p.texto}</p>
        <div className="mt-3 flex justify-center gap-1.5" aria-hidden>
          {passos.map((_, i) => <span key={i} className={`h-1.5 rounded-full transition-all ${i === passo ? "w-6 bg-venus" : "w-1.5 bg-white/25"}`} />)}
        </div>
        <div className="mt-4 flex gap-2">
          <button className={`${botaoSuave} flex-1`} onClick={fechar}>Agora não</button>
          {passo < passos.length - 1
            ? <button className={`${botaoPrimario} flex-1`} onClick={() => setPasso(passo + 1)}>Próximo</button>
            : <button className={`${botaoPrimario} flex-1`} onClick={fechar}>Entendi</button>}
        </div>
      </div>
    </div>
  );
}
