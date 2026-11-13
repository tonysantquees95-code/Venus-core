import { useState } from "react";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import { HolographicOrb } from "../theming";
import { useId } from "./IdProvider";

const moldura = "fixed inset-0 z-50 grid place-items-center overflow-y-auto bg-fundo p-4 [background-image:radial-gradient(800px_500px_at_20%_-10%,rgb(var(--venus-rgb)/.2),transparent),radial-gradient(700px_500px_at_100%_110%,rgb(var(--robo-rgb)/.16),transparent)]";

/** Teste de 7 dias acabado: o aplicativo fica fechado até haver plano ativo (o selo assinado volta com acesso). */
export function TelaBloqueio() {
  const { api, sessao, sair, validarAgora } = useId();
  const [msg, setMsg] = useState("");
  const [ocupado, setOcupado] = useState(false);
  async function pagar() {
    if (!sessao) return;
    setMsg(""); setOcupado(true);
    try {
      const l = await api.licenca(sessao.token, true);
      if (l.cobranca?.url) window.open(l.cobranca.url, "_blank", "noopener");
      else setMsg("Não consegui gerar o pagamento agora. Tente de novo.");
    } catch (e) { setMsg(e instanceof Error ? e.message : "Não consegui gerar o pagamento."); }
    setOcupado(false);
  }
  return (
    <div className={moldura}>
      <main role="alertdialog" aria-labelledby="bloq" className="w-full max-w-md space-y-5 rounded-3xl border border-white/10 bg-white/[0.06] p-6 text-center shadow-2xl backdrop-blur-xl">
        <HolographicOrb tamanho={100} />
        <h1 id="bloq" className="text-2xl font-semibold">O seu teste acabou</h1>
        <p className="text-sm text-suave">Para continuar com os serviços, adicione uma forma de pagamento e escolha um plano. Os seus dados continuam guardados.</p>
        <button className={`${botaoPrimario} w-full`} disabled={ocupado} onClick={() => void pagar()}>Escolher plano e pagar</button>
        <button className={`${botaoSuave} w-full`} onClick={() => void validarAgora()}>Já paguei: verificar de novo</button>
        <button className="text-xs text-suave underline" onClick={() => void sair()}>Sair desta conta</button>
        {msg && <p role="alert" className="text-sm text-rose-300">{msg}</p>}
      </main>
    </div>
  );
}

/** Sem selo válido (primeira abertura depois da atualização, muito tempo sem internet ou relógio mexido): valida com o servidor. */
export function TelaValidando() {
  const { estado, validarAgora, sair } = useId();
  const motivo = estado.tipo === "validar_online" ? estado.motivo : "";
  return (
    <div className={moldura}>
      <main className="w-full max-w-md space-y-5 rounded-3xl border border-white/10 bg-white/[0.06] p-6 text-center shadow-2xl backdrop-blur-xl">
        <HolographicOrb tamanho={100} />
        <h1 className="text-xl font-semibold">Confirmando a sua conta…</h1>
        <p className="text-sm text-suave">
          {motivo === "relogio" ? "A data e a hora deste aparelho não batem com a do servidor. Ajuste o relógio e conecte-se à internet."
            : motivo === "vencido" ? "Faz tempo que este aparelho não confirma a licença. Conecte-se à internet para continuar."
            : "Conecte-se à internet para confirmar o seu Vetorial ID."}
        </p>
        <button className={`${botaoPrimario} w-full`} onClick={() => void validarAgora()}>Tentar agora</button>
        <button className="text-xs text-suave underline" onClick={() => void sair()}>Sair desta conta</button>
      </main>
    </div>
  );
}
