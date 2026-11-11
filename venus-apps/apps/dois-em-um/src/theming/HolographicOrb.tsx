import type { CSSProperties } from "react";
import { useTema } from "./ThemingProvider";
import type { OrbeId } from "./tipos";

interface Props {
  /** Sem `variante`, usa o modelo escolhido no tema. */
  variante?: OrbeId;
  tamanho?: number;
  className?: string;
}

/** Orbe holográfico em CSS puro. Cores vêm das variáveis globais (--color-venus / --color-robo). */
export function HolographicOrb({ variante, tamanho = 48, className = "" }: Props) {
  const { tema } = useTema();
  const v = variante ?? tema.orbe;
  const estilo = { width: tamanho, height: tamanho, "--orbe-tam": `${tamanho}px` } as CSSProperties;

  return (
    <span className={`orbe orbe-${v} ${className}`} style={estilo} role="img" aria-label="Orbe da Vênus">
      {v === "aurora" && <span className="orbe-nevoa" />}
      {v === "aneis" && (
        <>
          <span className="orbe-anel orbe-anel-a" />
          <span className="orbe-anel orbe-anel-b" />
        </>
      )}
      {v === "prisma" && <span className="orbe-faces" />}
      <span className="orbe-nucleo" />
      <span className="orbe-brilho" />
    </span>
  );
}
