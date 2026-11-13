import { Entrar } from "../ajustes/Conta";
import { HolographicOrb } from "../theming";

/** Login centralizado do Vetorial ID: o mesmo em APK, PWA e Windows (e a mesma conta vale no Vindex e no Vortex). */
export function TelaLogin() {
  return (
    <div className="fixed inset-0 z-40 grid place-items-center overflow-y-auto bg-fundo p-4 [background-image:radial-gradient(800px_500px_at_20%_-10%,rgb(var(--venus-rgb)/.2),transparent),radial-gradient(700px_500px_at_100%_110%,rgb(var(--robo-rgb)/.16),transparent)]">
      <main className="w-full max-w-md space-y-5 rounded-3xl border border-white/10 bg-white/[0.06] p-6 shadow-2xl backdrop-blur-xl">
        <div className="flex flex-col items-center gap-3 text-center">
          <HolographicOrb tamanho={120} />
          <h1 className="text-2xl font-semibold">Vetorial ID</h1>
          <p className="text-sm text-suave">Uma conta só para a Vênus, o Vindex e o Vortex. Entre com o seu Vetorial ID para usar o aplicativo. Conta nova ganha 7 dias de teste grátis.</p>
        </div>
        <Entrar />
        <p className="text-center text-xs text-suave">Até 3 aparelhos vinculados, 2 usando ao mesmo tempo. Vetorial Tech.</p>
      </main>
    </div>
  );
}
