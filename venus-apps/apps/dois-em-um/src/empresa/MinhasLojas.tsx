import { useEffect, useState } from "react";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import { useId } from "../id/IdProvider";
import { campo, Titulo } from "./Divulgacao";
import { useEmpresa } from "./EmpresaProvider";
import { identidadeParaEmpresa } from "./identidadeDoSite";
import { useLojas } from "./LojasProvider";
import { normalizarSite } from "./logica";

/** Faixa no alto de Empresa: qual loja está aberta (as seções abaixo valem para ela) e atalho para trocar. */
export function SeletorDeLoja({ aoGerenciar }: { aoGerenciar: () => void }) {
  const { lojas, abertaId, trocar, max } = useLojas();
  if (lojas.length < 2) return lojas.length ? <div className="mb-4 flex items-center justify-between gap-2 text-sm text-suave"><span>Loja: <b className="text-texto">{lojas[0]!.nome || "Minha loja"}</b></span><button className="underline" onClick={aoGerenciar}>Adicionar outra loja</button></div> : null;
  return (
    <div className="mb-5">
      <div className="flex flex-wrap items-center gap-2" role="tablist" aria-label="Loja aberta">
        {lojas.map((l) => {
          const ativa = abertaId ? l.id === abertaId : l.principal;
          return <button key={l.id} role="tab" aria-selected={ativa} onClick={() => trocar(l.principal ? "" : l.id)} className={`rounded-full border px-4 py-2 text-sm ${ativa ? "border-venus bg-venus/20 font-semibold" : "border-white/15 text-suave"}`}>{l.nome || "Minha loja"}</button>;
        })}
        {lojas.length < max && <button className="rounded-full border border-dashed border-white/25 px-4 py-2 text-sm text-suave" onClick={aoGerenciar}>＋ Nova loja</button>}
      </div>
      <p className="mt-2 text-xs text-suave">Cada loja tem a sua identidade, produtos, vitrine, QR Code, cartão, panfleto e painel, separados.</p>
    </div>
  );
}

/** Empresa › Minhas lojas: até 3 lojas na mesma assinatura. */
export function MinhasLojas({ aoCriada }: { aoCriada: () => void }) {
  const { api, sessao } = useId();
  const { lojas, max, abertaId, trocar, criar, erro } = useLojas();
  const { empresa, salvar } = useEmpresa();
  const [nome, setNome] = useState("");
  const [site, setSite] = useState("");
  const [msg, setMsg] = useState("");
  const [ocupado, setOcupado] = useState(false);
  // a loja nova acabou de abrir: grava o cadastro dela e puxa a identidade do site
  const [pendente, setPendente] = useState<{ id: string; nome: string; site: string } | null>(null);
  useEffect(() => {
    if (!pendente || abertaId !== pendente.id || !sessao) return;
    const p = pendente; setPendente(null);
    salvar({ loja: p.nome, site: p.site });
    void (async () => {
      try {
        if (p.site) {
          const mudar = await identidadeParaEmpresa(api, sessao.token, { ...empresa, loja: p.nome, site: normalizarSite(p.site) });
          salvar(mudar);
          setMsg(mudar.cor || mudar.logo ? "Loja criada, com a cor e o logo do site." : "Loja criada. Não achei cor ou logo no site: escolha em Identidade da loja.");
        } else setMsg("Loja criada.");
      } catch (e) { setMsg(`Loja criada. ${e instanceof Error ? e.message : ""}`); }
      setOcupado(false);
      aoCriada();
    })();
  }, [pendente, abertaId]); // eslint-disable-line react-hooks/exhaustive-deps

  async function nova() {
    if (!nome.trim()) return setMsg("Dê um nome para a loja.");
    setOcupado(true); setMsg("");
    try { const l = await criar(nome.trim()); setPendente({ id: l.id, nome: nome.trim(), site: site.trim() }); setNome(""); setSite(""); }
    catch (e) { setMsg(e instanceof Error ? e.message : "Não consegui criar a loja."); setOcupado(false); }
  }
  if (!sessao) return <><Titulo titulo="Minhas lojas" /><p className="text-sm text-suave">Entre com o seu Vetorial ID para gerenciar as suas lojas.</p></>;
  return (
    <>
      <Titulo titulo="Minhas lojas" descricao={`Até ${max} lojas na mesma assinatura (outro site, outro nicho). Cada uma tem identidade, produtos, vitrine, QR Code, cartão, panfleto e painel separados.`} />
      <div className="mb-6 space-y-2">
        {lojas.map((l) => {
          const ativa = abertaId ? l.id === abertaId : l.principal;
          return (
            <div key={l.id} className="flex items-center gap-3 rounded-2xl border border-white/10 bg-white/[0.06] p-3">
              <span className="size-4 rounded-full" style={{ background: l.cor || "#7c3aed" }} />
              <div className="min-w-0 flex-1"><div className="truncate font-semibold">{l.nome || "Minha loja"}</div><div className="text-xs text-suave">{l.principal ? "Loja principal" : "Loja extra"} · vetorial.tech/{l.slug}</div></div>
              {ativa ? <span className="rounded-full bg-venus/20 px-3 py-1 text-xs">Aberta</span> : <button className={botaoSuave} onClick={() => trocar(l.principal ? "" : l.id)}>Abrir</button>}
            </div>
          );
        })}
        {!lojas.length && <p className="text-sm text-suave">{erro || "Carregando as suas lojas…"}</p>}
      </div>
      {lojas.length > 0 && lojas.length < max ? (
        <section className="rounded-3xl border border-white/10 bg-white/[0.06] p-4">
          <h3 className="mb-3 text-base font-semibold">Criar outra loja ({lojas.length} de {max})</h3>
          <div className="grid max-w-xl gap-3">
            <div><label className="mb-1 block text-sm text-suave">Nome da loja</label><input className={campo} value={nome} maxLength={80} onChange={(e) => setNome(e.target.value)} placeholder="Ex.: Tecnologia Já" /></div>
            <div><label className="mb-1 block text-sm text-suave">Site da loja (opcional)</label><input className={campo} value={site} onChange={(e) => setSite(e.target.value)} placeholder="minhaloja.com.br" /><p className="mt-1 text-xs text-suave">Com o site, a Vênus pega a cor e o logo dele para o cartão, o panfleto e a vitrine.</p></div>
            <div><button className={botaoPrimario} disabled={ocupado} onClick={() => void nova()}>{ocupado ? "Criando…" : "Criar loja"}</button></div>
          </div>
        </section>
      ) : lojas.length >= max ? <p className="text-sm text-suave">Você já tem as {max} lojas da conta.</p> : null}
      {msg && <p role="status" className="mt-4 text-sm text-suave">{msg}</p>}
    </>
  );
}
