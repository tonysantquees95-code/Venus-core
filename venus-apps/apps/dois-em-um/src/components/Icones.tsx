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
      <path d="M10.32 5.00 L10.87 2.47 L13.13 2.47 L13.68 5.00 L15.76 5.86 L17.94 4.46 L19.54 6.06 L18.14 8.24 L19.00 10.32 L21.53 10.87 L21.53 13.13 L19.00 13.68 L18.14 15.76 L19.54 17.94 L17.94 19.54 L15.76 18.14 L13.68 19.00 L13.13 21.53 L10.87 21.53 L10.32 19.00 L8.24 18.14 L6.06 19.54 L4.46 17.94 L5.86 15.76 L5.00 13.68 L2.47 13.13 L2.47 10.87 L5.00 10.32 L5.86 8.24 L4.46 6.06 L6.06 4.46 L8.24 5.86 Z" strokeLinejoin="round" {...semMiolo} />
      <circle cx="12" cy="12" r="3.1" {...semMiolo} />
    </Svg>
  );
}

/** Ícones das barras laterais (Ajustes e Minha empresa), escolhidos por nome e seguindo o pacote ativo. */
const CAMINHOS: Record<string, string> = {
  aparencia: "M12 3a9 9 0 1 0 0 18c1.4 0 2-.9 2-1.8 0-1.2-.9-1.5-.9-2.6 0-1 .8-1.6 1.8-1.6H17a4 4 0 0 0 4-4c0-4.4-4-8-9-8zM7.5 12h.01M9.5 8h.01M14.5 8h.01",
  voz: "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M9 21h6",
  conexoes: "M3 4h2l2.2 10.2a1 1 0 0 0 1 .8h8.6a1 1 0 0 0 1-.8L19.5 8H6M9.5 19.5h.01M16.5 19.5h.01",
  seguranca: "M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3zM9.5 12l2 2 3.5-4",
  dispositivos: "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2",
  loja: "M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6",
  regras: "M8 4h8l3 3v13H5V4h3zM8 11h8M8 15h8M9 4v3h6V4",
  passos: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM8 12.5l2.7 2.7L16 9.5",
  conversas: "M4 5h16v11H9l-4 4v-4H4V5z",
  ideia: "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z",
  video: "M4 6h11a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1zM16 10l5-3v10l-5-3",
  story: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 8a2 2 0 0 1 2-2h1.5l1.2-2h6.6l1.2 2H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8z",
  respostas: "M12 3v3M5 8h14v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8zM9.5 13h.01M14.5 13h.01M9 16.5h6",
  qr: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2v2h-2zM14 18h2v2h-2zM18 18h2v2h-2z",
  cartao: "M3 6h18v12H3zM6.5 10h5M6.5 13.5h3M15 10h2.5v3.5H15z",
  panfleto: "M6 3h12v18H6zM9 7h6M9 11h6M9 15h3",
  vitrine: "M3 9l2-5h14l2 5M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M5 12v8h14v-8",
  conta: "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"
};

export function IconeNomeado({ nome, ...props }: { nome: string } & SVGProps<SVGSVGElement>) {
  return (
    <Svg {...props}>
      <path d={CAMINHOS[nome] ?? CAMINHOS.passos} {...semMiolo} />
    </Svg>
  );
}
