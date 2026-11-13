import { useState, type CSSProperties } from "react";
import { IconeChat, IconeEnviar, IconeMenu, IconeMicrofone, IconeRobo } from "../components/Icones";
import { hexParaRgb, paraSecundaria, rgbCss, rgbParaHex } from "./cor";
import { HolographicOrb } from "./HolographicOrb";
import { ORBES, PACOTES_ICONES, PRESETS_CORES } from "./presets";
import { useTema } from "./ThemingProvider";
import type { RGB } from "./tipos";

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
  const { tema, definirOrbe, definirIcones, aplicarPreset, restaurar } = useTema();
  const [hexTexto, setHexTexto] = useState("");
  const atual = tema.cores.destaque;
  const usar = (c: RGB) => aplicarPreset({ destaque: c, secundaria: paraSecundaria(c) }, false);
  const mudarCanal = (canal: keyof RGB) => (n: number) => usar({ ...atual, [canal]: n });
  const aplicarHex = (txt: string) => {
    setHexTexto(txt);
    const rgb = hexParaRgb(txt);
    if (rgb) usar(rgb);
  };
  const ids = Object.keys(PACOTES_ICONES) as (keyof typeof PACOTES_ICONES)[];

  return (
    <section aria-label="Aparência" className="flex min-h-0 flex-1 flex-col overflow-y-auto">
      <header className="flex items-center gap-4 border-b border-borda px-6 py-4">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold uppercase tracking-[0.3em]">Aparência</h2>
          <p className="text-xs text-suave">Cada toque vale na hora, no app inteiro. É o mesmo tema do celular.</p>
        </div>
        <button type="button" onClick={restaurar} className="rounded-full border border-borda px-3 py-1.5 text-xs text-suave hover:text-texto">
          Restaurar
        </button>
      </header>

      <div className="mx-auto grid w-full max-w-5xl gap-6 p-6 lg:grid-cols-[320px_1fr]">
        {/* prévia fixa: o orbe e quatro botões já com o movimento, os ícones e a cor escolhidos */}
        <div className="lg:sticky lg:top-0 lg:self-start">
          <div className="flex flex-col items-center gap-3 rounded-3xl border border-borda bg-white/[0.04] p-4">
            <HolographicOrb tamanho={300} />
            <div className="flex gap-3 text-venus">
              {[IconeMenu, IconeMicrofone, IconeEnviar, IconeChat].map((I, k) => (
                <span key={k} className="grid size-12 place-items-center rounded-full border border-borda bg-painel"><I width={22} height={22} /></span>
              ))}
            </div>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-7">
          <div className="flex flex-col gap-3">
            <h3 className={titulo}>Movimento do orbe</h3>
            <div role="radiogroup" aria-label="Movimento do orbe" className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-5">
              {ORBES.map((o) => (
                <button key={o.id} type="button" role="radio" aria-checked={tema.orbe === o.id} onClick={() => definirOrbe(o.id)} className={cartao(tema.orbe === o.id)}>
                  <HolographicOrb variante={o.id} tamanho={110} />
                  <span className="text-sm font-medium">{o.nome}</span>
                  <span className="text-[11px] leading-tight text-suave">{o.descricao}</span>
                </button>
              ))}
            </div>
          </div>

          <div className="flex flex-col gap-3">
            <h3 className={titulo}>Pacote de ícones</h3>
            <div role="radiogroup" aria-label="Pacote de ícones" className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-5">
              {ids.map((id) => {
                const p = PACOTES_ICONES[id];
                return (
                  <button key={id} type="button" role="radio" aria-checked={tema.icones === id} onClick={() => definirIcones(id)}
                    className={cartao(tema.icones === id)} style={p.vars as unknown as CSSProperties}>
                    <span className="flex gap-1.5 text-venus">
                      <IconeChat width={22} height={22} />
                      <IconeRobo width={22} height={22} />
                      <IconeEnviar width={22} height={22} />
                    </span>
                    <span className="text-sm font-medium">{p.nome}</span>
                    <span className="text-[11px] leading-tight text-suave">{p.descricao}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="flex flex-col gap-3">
            <h3 className={titulo}>Paleta de cores</h3>
            <div className="flex flex-wrap gap-2">
              {PRESETS_CORES.map((p) => {
                const ativo = p.auto ? tema.auto : !tema.auto && rgbCss(p.cores.destaque) === rgbCss(tema.cores.destaque);
                return (
                  <button key={p.id} type="button" onClick={() => aplicarPreset(p.cores, !!p.auto)} aria-pressed={ativo}
                    className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm ${ativo ? "border-venus bg-venus/10" : "border-borda bg-painel"}`}>
                    <span className="size-5 rounded-full"
                      style={{ background: p.auto ? "linear-gradient(135deg,#E36BD0,#36D6FF,#F5B93A)" : rgbCss(p.cores.destaque) }} />
                    {p.nome}
                  </button>
                );
              })}
            </div>
            <p className="text-xs text-suave">Automática: as cores mudam devagar, nos tons da Vênus.</p>

            <div className="flex flex-col gap-3 rounded-2xl border border-borda bg-painel p-4">
              <span className="w-fit rounded-full bg-venus px-4 py-1.5 text-sm font-medium text-sobre-venus">Destaque</span>
              <Canal rotulo="R" valor={atual.r} cor="#ff5a6e" aoMudar={mudarCanal("r")} />
              <Canal rotulo="G" valor={atual.g} cor="#4ade80" aoMudar={mudarCanal("g")} />
              <Canal rotulo="B" valor={atual.b} cor="#5aa9ff" aoMudar={mudarCanal("b")} />
              <div className="flex items-center gap-3">
                <input type="color" value={rgbParaHex(atual)} onChange={(e) => aplicarHex(e.target.value)}
                  className="h-10 w-14 cursor-pointer rounded-lg border border-borda bg-transparent" aria-label="Seletor de cor completo" />
                <input value={hexTexto || rgbParaHex(atual)} onChange={(e) => aplicarHex(e.target.value)} spellCheck={false} maxLength={7}
                  className="w-28 rounded-lg border border-borda bg-fundo px-3 py-2 font-mono text-sm" aria-label="Código hexadecimal" />
                <span className="text-xs text-suave">rgb({atual.r}, {atual.g}, {atual.b})</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
