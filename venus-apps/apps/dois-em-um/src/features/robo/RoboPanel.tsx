import { IconeRobo } from "../../components/Icones";
import type { useRobo } from "../../hooks/useRobo";

function Interruptor({ ligado, aoAlternar, rotulo }: { ligado: boolean; aoAlternar: () => void; rotulo: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={ligado}
      aria-label={rotulo}
      onClick={aoAlternar}
      className={`relative h-7 w-12 shrink-0 rounded-full transition-colors ${ligado ? "bg-robo" : "bg-borda"}`}
    >
      <span
        className={`absolute top-1 size-5 rounded-full bg-white transition-all ${ligado ? "left-6" : "left-1"}`}
      />
    </button>
  );
}

export function RoboPanel({ robo }: { robo: ReturnType<typeof useRobo> }) {
  const { ligado, automacoes, eventos, alternarRobo, alternarAutomacao } = robo;
  const ativas = automacoes.filter((a) => a.ativa).length;

  return (
    <section aria-label="Painel do Robô" className="flex min-h-0 flex-1 flex-col overflow-y-auto">
      <header className="flex items-center gap-3 border-b border-borda px-4 py-3">
        <IconeRobo className="text-robo" width={28} height={28} />
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-semibold">Vênus Robô</h2>
          <p className="text-xs text-suave">
            {ligado ? `Trabalhando · ${ativas} automaç${ativas === 1 ? "ão" : "ões"} ativa${ativas === 1 ? "" : "s"}` : "Desligado"}
          </p>
        </div>
        <Interruptor ligado={ligado} aoAlternar={alternarRobo} rotulo="Ligar ou desligar o Robô" />
      </header>

      <div className={`flex flex-col gap-3 p-4 ${ligado ? "" : "opacity-50"}`}>
        <h3 className="text-xs font-semibold uppercase tracking-widest text-suave">Automações</h3>
        <ul className="flex flex-col gap-2">
          {automacoes.map((a) => (
            <li key={a.id} className="flex items-center gap-3 rounded-2xl border border-borda bg-painel p-3">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{a.nome}</p>
                <p className="text-xs text-suave">{a.descricao}</p>
              </div>
              <Interruptor ligado={a.ativa} aoAlternar={() => alternarAutomacao(a.id)} rotulo={`Automação ${a.nome}`} />
            </li>
          ))}
        </ul>

        <h3 className="mt-2 text-xs font-semibold uppercase tracking-widest text-suave">Atividade</h3>
        {eventos.length === 0 ? (
          <p className="text-sm text-suave">Nada por aqui ainda.</p>
        ) : (
          <ul className="flex flex-col gap-1.5">
            {eventos.map((e) => (
              <li key={e.id} className="flex justify-between gap-3 text-sm">
                <span>{e.texto}</span>
                <time className="shrink-0 text-xs text-suave">
                  {new Date(e.quando).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })}
                </time>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
