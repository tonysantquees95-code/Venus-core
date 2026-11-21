import { URL_API } from "@venus/nucleo";
import { ponteDesktop } from "../desktop/ponte";
import { meuAparelho } from "../id/IdProvider";
import { botaoSuave } from "./Controles";

/** Feedback, relato de erro e pesquisa rápida: a página do servidor aberta aqui dentro, com o app, a versão e a plataforma. */
export function FeedbackDoApp({ aoFechar }: { aoFechar: () => void }) {
  const p = String(meuAparelho().plataforma);
  const plataforma = ["windows", "web", "ios", "mac", "android"].includes(p) ? p : "web";
  const endereco = `${URL_API}/feedback?app=venus&v=${encodeURIComponent(ponteDesktop()?.versao ?? "")}&p=${plataforma}`;
  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-fundo">
      <div className="mx-auto flex w-full max-w-2xl items-center justify-between gap-3 px-5 py-3">
        <span className="text-sm font-semibold tracking-wide text-suave">FEEDBACK</span>
        <button type="button" className={botaoSuave} onClick={aoFechar}>Fechar</button>
      </div>
      <iframe title="Feedback e relato de erro" src={endereco} className="mx-auto min-h-0 w-full max-w-2xl flex-1 border-0 bg-[#070a1c]" />
    </div>
  );
}
