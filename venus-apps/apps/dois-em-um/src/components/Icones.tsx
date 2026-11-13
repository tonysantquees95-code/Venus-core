import type { CSSProperties, SVGProps } from "react";

/**
 * Todos os ícones leem o pacote ativo pelas variáveis CSS globais
 * (--icone-traco, --icone-ponta, --icone-junta, --icone-preench), definidas pelo ThemingEngine.
 */
const estiloPacote: CSSProperties = {
  strokeWidth: "var(--icone-traco, 1.6)",
  strokeLinecap: "var(--icone-ponta, round)" as CSSProperties["strokeLinecap"],
  strokeLinejoin: "var(--icone-junta, round)" as CSSProperties["strokeLinejoin"],
  fillOpacity: "var(--icone-preench, 0)" as unknown as number,
  filter: "var(--icone-brilho, none)"
};

function Svg({ children, style, ...resto }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      width={24}
      height={24}
      viewBox="0 0 24 24"
      fill="currentColor"
      stroke="currentColor"
      aria-hidden
      {...resto}
      style={{ ...estiloPacote, ...style }}
    >
      {children}
    </svg>
  );
}

const semMiolo = { fill: "none" } as const;
const solido = { fillOpacity: 1, stroke: "none" } as const;

/** Robozinho: antena, cabeça com dois olhos e orelhas. */
export function IconeRobo(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M12 3v3" {...semMiolo} />
      <circle cx="12" cy="2.6" r=".9" {...solido} />
      <rect x="4.5" y="6.5" width="15" height="11.5" rx="3.5" />
      <circle cx="9.2" cy="11.8" r="1.3" {...solido} />
      <circle cx="14.8" cy="11.8" r="1.3" {...solido} />
      <path d="M9.5 15.2h5" {...semMiolo} />
      <path d="M2.5 11v3M21.5 11v3" {...semMiolo} />
      <path d="M8 18v2.5M16 18v2.5" {...semMiolo} />
    </Svg>
  );
}

export function IconeChat(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.6A8 8 0 1 1 21 12Z" />
    </Svg>
  );
}

export function IconeEnviar(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M5 12h14M13 6l6 6-6 6" {...semMiolo} />
    </Svg>
  );
}

/** Paleta (menu Aparência). */
export function IconeTema(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M12 3a9 9 0 1 0 0 18c1.2 0 1.8-.8 1.8-1.7 0-1.3-1-1.6-1-2.7 0-1 .8-1.6 1.8-1.6H17a4 4 0 0 0 4-4C21 7 17 3 12 3Z" />
      <circle cx="7.8" cy="11" r="1" {...solido} />
      <circle cx="10.5" cy="7.3" r="1" {...solido} />
      <circle cx="15" cy="7.8" r="1" {...solido} />
    </Svg>
  );
}

export function IconeMenu(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M4 7h16M4 12h16M4 17h10" {...semMiolo} />
    </Svg>
  );
}

export function IconeMais(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M12 5v14M5 12h14" {...semMiolo} />
    </Svg>
  );
}

export function IconeFechar(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M6 6l12 12M18 6L6 18" {...semMiolo} />
    </Svg>
  );
}

export function IconeLixeira(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12h10l1-12M9 7V4h6v3" {...semMiolo} />
    </Svg>
  );
}

/** Microfone. */
export function IconeMicrofone(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <rect x="9" y="3" width="6" height="11" rx="3" {...semMiolo} />
      <path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M9 21h6" {...semMiolo} />
    </Svg>
  );
}

/** Lojinha (Minha empresa). */
export function IconeEmpresa(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d="M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6" {...semMiolo} />
    </Svg>
  );
}

/** Engrenagem (Ajustes). */
export function IconeAjustes(props: SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <circle cx="12" cy="12" r="3.2" {...semMiolo} />
      <path d="M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7" {...semMiolo} />
    </Svg>
  );
}
