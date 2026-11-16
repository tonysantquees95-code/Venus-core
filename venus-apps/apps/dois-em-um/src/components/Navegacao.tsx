import type { ReactNode } from "react";
import type { Tela } from "../tipos";
import { IconeAjustes, IconeChat, IconeEmpresa, IconeRobo, IconeTema } from "./Icones";

interface Props {
  tela: Tela;
  aoMudar: (t: Tela) => void;
  roboLigado: boolean;
}

const ITENS: { id: Tela; rotulo: string; icone: ReactNode }[] = [
  { id: "chat", rotulo: "Chat", icone: <IconeChat /> },
  { id: "robo", rotulo: "Robô", icone: <IconeRobo /> },
  { id: "empresa", rotulo: "Empresa", icone: <IconeEmpresa /> },
  { id: "tema", rotulo: "Tema", icone: <IconeTema /> },
  { id: "ajustes", rotulo: "Ajustes", icone: <IconeAjustes /> }
];

const ROTULOS: Record<Tela, string> = { chat: "Abrir chat", robo: "Abrir painel do Robô", empresa: "Abrir minha empresa", tema: "Abrir aparência", ajustes: "Abrir ajustes" };

/**
 * Navegação em barra lateral no tablet/computador (md+). No celular ela some: o chat é a tela principal e o resto abre pelo menu da gaveta, igual ao app Android.
 * O botão do robozinho abre o painel do Robô; a bolinha mostra se ele está ligado.
 */
export function Navegacao({ tela, aoMudar, roboLigado }: Props) {
  return (
    <nav
      aria-label="Navegação principal"
      className="order-last hidden shrink-0 justify-around border-t border-borda bg-painel pb-[env(safe-area-inset-bottom)]
                 md:order-first md:flex md:w-20 md:flex-col md:items-center md:justify-start md:gap-2 md:border-t-0 md:border-r md:py-4"
    >
      <span className="hidden font-semibold tracking-[0.3em] text-venus md:mb-4 md:block md:[writing-mode:vertical-rl]">
        VÊNUS
      </span>
      {ITENS.map((item) => {
        const ativo = tela === item.id;
        return (
          <button
            key={item.id}
            type="button"
            onClick={() => aoMudar(item.id)}
            aria-current={ativo ? "page" : undefined}
            aria-label={ROTULOS[item.id]}
            className={`relative flex flex-1 flex-col items-center gap-1 px-3 py-3 text-xs transition-colors md:w-16 md:flex-none md:rounded-2xl
              ${ativo ? "text-venus md:bg-venus/10" : "text-suave hover:text-texto"}`}
          >
            {item.icone}
            <span>{item.rotulo}</span>
            {item.id === "robo" && (
              <span
                title={roboLigado ? "Robô ligado" : "Robô desligado"}
                className={`absolute right-[28%] top-2 size-2.5 rounded-full ring-2 ring-painel md:right-2 md:top-2
                  ${roboLigado ? "bg-emerald-400" : "bg-zinc-500"}`}
              />
            )}
          </button>
        );
      })}
    </nav>
  );
}
