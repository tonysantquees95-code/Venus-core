import { useState, type CSSProperties } from "react";
import { IconeChat, IconeEnviar, IconeRobo, IconeTema } from "../components/Icones";
import { hexParaRgb, rgbCss, rgbParaHex } from "./cor";
import { HolographicOrb } from "./HolographicOrb";
import { ORBES, PACOTES_ICONES, PRESETS_CORES } from "./presets";
import { useTema } from "./ThemingProvider";
import type { AlvoCor, RGB } from "./tipos";

const titulo = "text-xs font-semibold uppercase tracking-widest text-suave";
const cartao = (ativo: boolean) =>
  `flex flex-col items-center gap-2 rounded-2xl border p-3 text-center transition-colors ${
    ativo ? "border-venus bg-venus/10" : "border-borda bg-painel hover:border-suave"
  }`;

function Canal({ rotulo, valor, cor, aoMudar }: { rotulo: string; valor: number; cor: string; aoMudar: (n: number) => void }) {
  return (
    <label className="flex items-center gap-3 text-sm">
      <span className="w-4 font-semibold" style={{ color: cor }}>{rotulo}</span>
      <input
        type="range"
        min={0}
        max={255}
        value={valor}
        onChange={(e) => aoMudar(Number(e.target.value))}
        className="h-2 min-w-0 flex-1 cursor-pointer"
        style={{ accentColor: cor }}
        aria-label={`Canal ${rotulo}`}
      />
      <input
        type="number"
        min={0}
        max={255}
        value={valor}
        onChange={(e) => aoMudar(Number(e.target.value))}
        className="w-16 rounded-lg border border-borda bg-painel px-2 py-1 text-right"
        aria-label={`Valor de ${rotulo}`}
      />
    </label>
  );
}

export function ThemingPanel() {
  const { tema, definirOrbe, definirIcones, definirCor, aplicarPreset, restaurar } = useTema();
  const [alvo, setAlvo] = useState<AlvoCor>("destaque");
  const [hexTexto, setHexTexto] = useState("");
  const atual = tema.cores[alvo];

  const mudarCanal = (canal: keyof RGB) => (n: number) => definirCor(alvo, { ...atual, [canal]: n });
  const aplicarHex = (txt: string) => {
    setHexTexto(txt);
    const rgb = hexParaRgb(txt);
    if (rgb) definirCor(alvo, rgb);
  };

  return (
    <section aria-label="Aparência" className="flex min-h-0 flex-1 flex-col overflow-y-auto">
      <header className="flex items-center gap-4 border-b border-borda px-4 py-4">
        <HolographicOrb tamanho={72} />
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-semibold">Aparência</h2>
          <p className="text-xs text-suave">Tudo muda na hora, no app inteiro.</p>
        </div>
        <button type="button" onClick={restaurar} className="rounded-full border border-borda px-3 py-1.5 text-xs text-suave hover:text-texto">
          Restaurar
        </button>
      </header>

      <div className="mx-auto flex w-full max-w-xl flex-col gap-6 p-4">
        {/* 1. Orbe */}
        <div className="flex flex-col gap-3">
          <h3 className={titulo}>Orbe holográfico</h3>
          <div role="radiogroup" aria-label="Modelo do orbe" className="grid grid-cols-3 gap-2">
            {ORBES.map((o) => (
              <button key={o.id} type="button" role="radio" aria-checked={tema.orbe === o.id} onClick={() => definirOrbe(o.id)} className={cartao(tema.orbe === o.id)}>
                <HolographicOrb variante={o.id} tamanho={56} />
                <span className="text-sm font-medium">{o.nome}</span>
                <span className="text-[11px] leading-tight text-suave">{o.descricao}</span>
              </button>
            ))}
          </div>
        </div>

        {/* 2. Ícones */}
        <div className="flex flex-col gap-3">
          <h3 className={titulo}>Pacote de ícones</h3>
          <div role="radiogroup" aria-label="Pacote de ícones" className="grid grid-cols-3 gap-2">
            {(Object.keys(PACOTES_ICONES) as (keyof typeof PACOTES_ICONES)[]).map((id) => {
              const p = PACOTES_ICONES[id];
              return (
                <button
                  key={id}
                  type="button"
                  role="radio"
                  aria-checked={tema.icones === id}
                  onClick={() => definirIcones(id)}
                  className={cartao(tema.icones === id)}
                  style={p.vars as unknown as CSSProperties} // pré-visualiza o pacote só neste botão
                >
                  <span className="flex gap-1.5 text-venus">
                    <IconeChat width={22} height={22} />
                    <IconeRobo width={22} height={22} />
                    <IconeEnviar width={22} height={22} />
                    <IconeTema width={22} height={22} />
                  </span>
                  <span className="text-sm font-medium">{p.nome}</span>
                  <span className="text-[11px] leading-tight text-suave">{p.descricao}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* 3. Cores */}
        <div className="flex flex-col gap-3">
          <h3 className={titulo}>Paleta de cores</h3>
          <div className="flex flex-wrap gap-2">
            {PRESETS_CORES.map((p) => {
              const ativo =
                rgbCss(p.cores.destaque) === rgbCss(tema.cores.destaque) && rgbCss(p.cores.secundaria) === rgbCss(tema.cores.secundaria);
              return (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => aplicarPreset(p.cores)}
                  aria-pressed={ativo}
                  className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm ${ativo ? "border-venus bg-venus/10" : "border-borda bg-painel"}`}
                >
                  <span className="size-5 rounded-full" style={{ background: `linear-gradient(135deg, ${rgbCss(p.cores.destaque)}, ${rgbCss(p.cores.secundaria)})` }} />
                  {p.nome}
                </button>
              );
            })}
          </div>

          <div className="flex flex-col gap-3 rounded-2xl border border-borda bg-painel p-4">
            <div role="tablist" aria-label="Cor a editar" className="flex gap-2">
              {(["destaque", "secundaria"] as const).map((a) => (
                <button
                  key={a}
                  type="button"
                  role="tab"
                  aria-selected={alvo === a}
                  onClick={() => { setAlvo(a); setHexTexto(""); }}
                  className={`flex items-center gap-2 rounded-full px-3 py-1.5 text-sm ${alvo === a ? "bg-venus text-sobre-venus" : "text-suave"}`}
                >
                  <span className="size-3 rounded-full ring-1 ring-white/30" style={{ background: rgbCss(tema.cores[a]) }} />
                  {a === "destaque" ? "Destaque" : "Secundária"}
                </button>
              ))}
            </div>

            <Canal rotulo="R" valor={atual.r} cor="#ff6b6b" aoMudar={mudarCanal("r")} />
            <Canal rotulo="G" valor={atual.g} cor="#4ade80" aoMudar={mudarCanal("g")} />
            <Canal rotulo="B" valor={atual.b} cor="#60a5fa" aoMudar={mudarCanal("b")} />

            <div className="flex items-center gap-3">
              <input
                type="color"
                value={rgbParaHex(atual)}
                onChange={(e) => aplicarHex(e.target.value)}
                className="h-10 w-14 cursor-pointer rounded-lg border border-borda bg-transparent"
                aria-label="Seletor de cor completo"
              />
              <input
                value={hexTexto || rgbParaHex(atual)}
                onChange={(e) => aplicarHex(e.target.value)}
                spellCheck={false}
                maxLength={7}
                className="w-28 rounded-lg border border-borda bg-fundo px-3 py-2 font-mono text-sm"
                aria-label="Código hexadecimal"
              />
              <span className="text-xs text-suave">rgb({atual.r}, {atual.g}, {atual.b})</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
