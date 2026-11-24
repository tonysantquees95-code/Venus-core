import { URL_API } from "@venus/nucleo";
import { botaoSuave } from "./Controles";

/** Aprender a usar a Vênus: a página pública do servidor (vídeos curtos e passo a passo) aberta aqui dentro, sem o cabeçalho do site. */
export function AprenderDoApp({ aoFechar }: { aoFechar: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-fundo">
      <div className="mx-auto flex w-full max-w-3xl items-center justify-between gap-3 px-5 py-3">
        <span className="text-sm font-semibold tracking-wide text-suave">APRENDER A USAR A VÊNUS</span>
        <button type="button" className={botaoSuave} onClick={aoFechar}>Fechar</button>
      </div>
      <iframe title="Aprender a usar a Vênus" src={`${URL_API}/aprender.html#app=venus`} allow="fullscreen" className="mx-auto min-h-0 w-full max-w-3xl flex-1 border-0 bg-[#070a1c]" />
    </div>
  );
}
