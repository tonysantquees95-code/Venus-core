import { useEffect, useState } from "react";
import { ESTILOS, LIMITE_GRATIS, resumoNeural, VOZES_NEURAIS, CONFIG_VOZ_PADRAO } from "../voz/config";
import { useConfigVoz, useUsoDoMes } from "../voz/armazem";
import { calar, falar, sintetizarNeural, vozesDoSistema } from "../voz/useVoz";
import { botaoSuave, campo } from "./Controles";

const AMOSTRA = "Olá! Eu sou a Vênus. Gostou desta voz?";

function Rotulo({ children }: { children: string }) {
  return <p className="mb-1.5 mt-4 text-sm font-semibold text-suave">{children}</p>;
}

function Opcao({ marcada, titulo, descricao, aoEscolher }: { marcada: boolean; titulo: string; descricao?: string; aoEscolher: () => void }) {
  return (
    <button type="button" role="radio" aria-checked={marcada} onClick={aoEscolher}
      className={`w-full rounded-2xl border px-4 py-2.5 text-left text-sm transition active:scale-[0.99] ${marcada ? "border-venus bg-venus/15" : "border-white/10 bg-white/5 hover:bg-white/10"}`}>
      <span className="font-semibold">{titulo}</span>{descricao && <span className="text-suave"> — {descricao}</span>}
    </button>
  );
}

/** Voz da Vênus: voz do Windows (velocidade e tom) e voz neural da Microsoft, iguais às do Android e sincronizadas pela conta. */
export function CartaoVoz() {
  const [c, definir] = useConfigVoz();
  const usados = useUsoDoMes();
  const [sistema, setSistema] = useState(vozesDoSistema);
  const [mostrarChave, setMostrarChave] = useState(false);
  const [teste, setTeste] = useState("");
  useEffect(() => {
    const atualizar = () => setSistema(vozesDoSistema());
    try { speechSynthesis.addEventListener("voiceschanged", atualizar); } catch { /* sem voz no sistema */ }
    return () => { try { speechSynthesis.removeEventListener("voiceschanged", atualizar); } catch { /* ok */ } };
  }, []);
  const mudarNeural = (p: Partial<typeof c.neural>) => definir({ ...c, neural: { ...c.neural, ...p } });
  const ouvir = () => { setTeste(""); falar(AMOSTRA); };
  const testarChave = async () => {
    setTeste("Testando…");
    const b = await sintetizarNeural(`Olá. Sistemas prontos. No que posso ajudar?`, c, 10_000);
    if (!b) { setTeste("A Microsoft não respondeu. Confira a chave e a região (ex.: brazilsouth, eastus)."); return; }
    setTeste("Chave funcionando.");
    const a = new Audio(URL.createObjectURL(b));
    void a.play().catch(() => undefined);
  };
  const barra = (rotulo: string, valor: number, aoMudar: (v: number) => void) => (
    <label className="mt-3 block text-sm">
      <span className="text-suave">{rotulo}: {valor.toFixed(2)}×</span>
      <input type="range" min={0.5} max={1.5} step={0.05} value={valor} className="mt-1 block w-full accent-venus"
        onChange={(e) => aoMudar(Number(e.target.value))} onPointerUp={ouvir} onKeyUp={ouvir} />
    </label>
  );
  return (
    <div>
      <Rotulo>Voz do Windows</Rotulo>
      {sistema.length === 0 ? (
        <p className="text-sm text-suave">Nenhuma voz em português encontrada neste computador. No Windows: Configurações › Hora e idioma › Fala › Adicionar vozes.</p>
      ) : (
        <div className="space-y-1.5" role="radiogroup" aria-label="Voz do Windows">
          {sistema.map((v) => <Opcao key={v.name} marcada={c.vozSistema === v.name || (!c.vozSistema && v === sistema[0])} titulo={v.name.replace(/^Microsoft /, "")} descricao={v.lang} aoEscolher={() => { definir({ ...c, vozSistema: v.name }); falar(AMOSTRA); }} />)}
        </div>
      )}
      {barra("Velocidade", c.velocidade, (v) => definir({ ...c, velocidade: v }))}
      {barra("Tom (grave ↔ agudo)", c.tom, (v) => definir({ ...c, tom: v }))}

      <Rotulo>Voz neural da Microsoft (a mesma do celular)</Rotulo>
      <p className="mb-2 text-sm text-suave">{resumoNeural(c, usados)}</p>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" className="size-5 accent-venus" checked={c.neural.ligada} onChange={(e) => mudarNeural({ ligada: e.target.checked })} />
        Usar a voz neural quando houver chave
      </label>
      <div className="mt-3 space-y-1.5" role="radiogroup" aria-label="Voz neural">
        {VOZES_NEURAIS.map((v) => <Opcao key={v.id} marcada={c.neural.voz === v.id} titulo={v.nome} descricao={v.descricao} aoEscolher={() => { mudarNeural({ voz: v.id }); void sintetizarNeural(AMOSTRA, { ...c, neural: { ...c.neural, voz: v.id } }).then((b) => { if (b) void new Audio(URL.createObjectURL(b)).play().catch(() => undefined); }); }} />)}
      </div>
      <Rotulo>Jeito de falar</Rotulo>
      <div className="space-y-1.5" role="radiogroup" aria-label="Jeito de falar">
        {ESTILOS.map((e) => <Opcao key={e.id} marcada={c.neural.estilo === e.id} titulo={e.nome} aoEscolher={() => mudarNeural({ estilo: e.id })} />)}
      </div>
      <Rotulo>Chave do Azure Speech</Rotulo>
      <div className="flex gap-2">
        <input className={campo} type={mostrarChave ? "text" : "password"} value={c.neural.chave} placeholder="Cole aqui a chave (KEY 1)" autoComplete="off" spellCheck={false} onChange={(e) => mudarNeural({ chave: e.target.value })} />
        <button type="button" className={botaoSuave} onClick={() => setMostrarChave((m) => !m)}>{mostrarChave ? "Esconder" : "Mostrar"}</button>
      </div>
      <Rotulo>Região do recurso</Rotulo>
      <input className={campo} value={c.neural.regiao} placeholder="brazilsouth" onChange={(e) => mudarNeural({ regiao: e.target.value })} />
      <p className="mt-2 text-xs text-suave">Plano grátis: {LIMITE_GRATIS.toLocaleString("pt-BR")} caracteres por mês. Perto do limite, a Vênus volta sozinha para a voz do Windows. A chave e as escolhas acompanham a sua conta, então o que você já ajustou no celular aparece aqui.</p>

      <div className="mt-4 flex flex-wrap gap-2">
        <button type="button" className={botaoSuave} onClick={ouvir}>Ouvir amostra</button>
        <button type="button" className={botaoSuave} onClick={() => void testarChave()} disabled={!c.neural.chave}>Testar a chave</button>
        <button type="button" className={botaoSuave} onClick={calar}>Parar</button>
        <button type="button" className={botaoSuave} onClick={() => definir({ ...CONFIG_VOZ_PADRAO, neural: c.neural, vozSistema: "" })}>Velocidade e tom padrão</button>
      </div>
      {teste && <p className="mt-3 text-sm" role="status">{teste}</p>}
    </div>
  );
}
