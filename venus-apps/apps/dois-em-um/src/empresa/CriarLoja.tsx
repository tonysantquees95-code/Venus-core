import { useEffect, useRef, useState } from "react";
import { botaoPrimario, botaoSuave, campo } from "../ajustes/Controles";
import { useEmpresa } from "./EmpresaProvider";
import { gerarSvg, MODELOS, rasterizar, urlDoSvg } from "./logos";
import { iniciaisDaLoja, NICHOS, normalizarSite, paletasDoNicho, textoSobre, type Empresa } from "./logica";

export const CHAVE_LOJA_GUIADA = "venus:loja-guiada";
export const lojaGuiadaPulada = (): boolean => { try { return localStorage.getItem(CHAVE_LOJA_GUIADA) === "1"; } catch { return false; } };
const marcarGuiada = () => { try { localStorage.setItem(CHAVE_LOJA_GUIADA, "1"); } catch { /* pergunta de novo na próxima vez */ } };

/** Lê a imagem escolhida e devolve um logo pequeno (até 256 px), leve o bastante para ir junto com o cadastro na nuvem. */
export async function lerLogo(arq: File): Promise<string> {
  if (!arq.type.startsWith("image/")) throw new Error("Escolha uma imagem (PNG, JPG ou WebP).");
  const url = URL.createObjectURL(arq);
  try {
    const img = await new Promise<HTMLImageElement>((ok, erro) => { const i = new Image(); i.onload = () => ok(i); i.onerror = () => erro(new Error("Não consegui abrir essa imagem.")); i.src = url; });
    for (const lado of [256, 192, 128]) {
      const esc = Math.min(1, lado / Math.max(img.width, img.height));
      const c = document.createElement("canvas");
      c.width = Math.max(1, Math.round(img.width * esc));
      c.height = Math.max(1, Math.round(img.height * esc));
      c.getContext("2d")?.drawImage(img, 0, 0, c.width, c.height);
      const d = c.toDataURL("image/webp", 0.85);
      if (/^data:image\/(png|jpeg|webp);base64,/.test(d) && d.length <= 140_000) return d;
    }
    throw new Error("Essa imagem ficou pesada demais. Tente uma menor.");
  } finally { URL.revokeObjectURL(url); }
}

/** A marca da loja como a pessoa montou: o logo, ou as iniciais sobre a cor principal. */
export function MarcaDaLoja({ loja, logo, cor, tamanho = 72 }: { loja: string; logo: string; cor: string; tamanho?: number }) {
  const fundo = cor || "#7c3aed";
  return logo
    ? <img src={logo} alt={`Logo de ${loja || "sua loja"}`} width={tamanho} height={tamanho} className="rounded-2xl border border-borda bg-white/5 object-contain" style={{ width: tamanho, height: tamanho }} />
    : <div aria-hidden className="grid place-items-center rounded-2xl font-black" style={{ width: tamanho, height: tamanho, background: fundo, color: textoSobre(fundo), fontSize: tamanho / 2.6 }}>{iniciaisDaLoja(loja)}</div>;
}

/** Escolher ou tirar o logo. */
export function EditorDeLogo({ empresa, aoMudar }: { empresa: Empresa; aoMudar: (p: Partial<Empresa>) => void }) {
  const arq = useRef<HTMLInputElement>(null);
  const [erro, setErro] = useState("");
  return (
    <div className="flex flex-col items-center gap-3">
      <MarcaDaLoja loja={empresa.loja} logo={empresa.logo} cor={empresa.cor} tamanho={112} />
      <input ref={arq} type="file" accept="image/*" hidden aria-label="Escolher o logo" onChange={async (e) => {
        const f = e.target.files?.[0]; e.target.value = "";
        if (!f) return;
        setErro("");
        try { aoMudar({ logo: await lerLogo(f) }); } catch (x) { setErro(x instanceof Error ? x.message : "Não consegui usar essa imagem."); }
      }} />
      <div className="flex flex-wrap justify-center gap-2">
        <button type="button" className={botaoPrimario} onClick={() => arq.current?.click()}>{empresa.logo ? "Trocar o logo" : "Escolher o logo"}</button>
        {empresa.logo && <button type="button" className={botaoSuave} onClick={() => aoMudar({ logo: "" })}>Usar as iniciais</button>}
      </div>
      {erro && <p role="alert" className="text-sm text-rose-400">{erro}</p>}
      <GaleriaDeLogos empresa={empresa} aoMudar={aoMudar} aoErro={setErro} />
    </div>
  );
}

/** 24 logos prontos: escreva o nome, troque as cores e toque no que gostar. */
function GaleriaDeLogos({ empresa, aoMudar, aoErro }: { empresa: Empresa; aoMudar: (p: Partial<Empresa>) => void; aoErro: (m: string) => void }) {
  const [aberta, setAberta] = useState(false);
  const [nome, setNome] = useState(empresa.loja || "");
  const [cor, setCor] = useState(empresa.cor || "#7c3aed");
  const [apoio, setApoio] = useState(empresa.corApoio || "#ffffff");
  if (!aberta) return <button type="button" className={botaoSuave} onClick={() => setAberta(true)}>Escolher um logo pronto (24 modelos)</button>;
  return (
    <div className="w-full rounded-3xl border border-white/10 bg-white/[0.04] p-3">
      <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Nome no logo</span>
        <input className={campo} value={nome} maxLength={28} placeholder="Nome da sua loja" onChange={(e) => setNome(e.target.value)} /></label>
      <div className="mt-3 grid grid-cols-2 gap-3">
        <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Cor principal</span>
          <input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={cor} onChange={(e) => setCor(e.target.value)} /></label>
        <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Cor de apoio</span>
          <input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={apoio} onChange={(e) => setApoio(e.target.value)} /></label>
      </div>
      <div className="mt-3 grid grid-cols-3 gap-2" role="list">
        {MODELOS.map((m) => (
          <button key={m.id} type="button" role="listitem" aria-label={`Usar o logo ${m.fonte.nome} ${m.formato}`} className="overflow-hidden rounded-2xl border border-white/15 bg-white/5"
            onClick={async () => { aoErro(""); try { aoMudar({ logo: await rasterizar(gerarSvg(m, nome, cor, apoio)), cor, corApoio: apoio, ...(nome.trim() && !empresa.loja ? { loja: nome.trim() } : {}) }); } catch (x) { aoErro(x instanceof Error ? x.message : "Não consegui usar esse logo."); } }}>
            <img alt="" className="block w-full" src={urlDoSvg(gerarSvg(m, nome, cor, apoio))} />
          </button>
        ))}
      </div>
      <p className="mt-2 text-xs text-suave">Prefere o seu próprio logo? Use “Escolher o logo” acima: a Vênus passa a usar a imagem que você enviar.</p>
    </div>
  );
}

/** Cor principal e cor de apoio: ideias pelo nicho e qualquer cor à escolha. */
export function EditorDeCores({ empresa, aoMudar }: { empresa: Empresa; aoMudar: (p: Partial<Empresa>) => void }) {
  const paletas = paletasDoNicho(empresa.ramo);
  const principal = empresa.cor || "#7c3aed";
  const apoio = empresa.corApoio || "#f5f3ff";
  return (
    <div className="flex flex-col gap-4">
      <div>
        <p className="mb-2 text-sm text-suave">Ideias para a sua loja (toque para usar):</p>
        <div className="grid grid-cols-4 gap-2">
          {paletas.map(([a, b]) => {
            const ativo = a.toLowerCase() === principal.toLowerCase() && b.toLowerCase() === apoio.toLowerCase();
            return (
              <button key={a + b} type="button" aria-label={`Usar as cores ${a} e ${b}`} aria-pressed={ativo} onClick={() => aoMudar({ cor: a, corApoio: b })}
                className={`flex h-14 overflow-hidden rounded-2xl border ${ativo ? "border-venus ring-2 ring-venus/50" : "border-white/15"}`}>
                <span className="flex-[2]" style={{ background: a }} /><span className="flex-1" style={{ background: b }} />
              </button>
            );
          })}
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Cor principal</span>
          <input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={principal} onChange={(e) => aoMudar({ cor: e.target.value })} /></label>
        <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Cor de apoio</span>
          <input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={apoio} onChange={(e) => aoMudar({ corApoio: e.target.value })} /></label>
      </div>
      <div className="rounded-2xl border border-borda p-4" style={{ background: apoio, color: textoSobre(apoio) }}>
        <div className="flex items-center gap-3">
          <MarcaDaLoja loja={empresa.loja} logo={empresa.logo} cor={principal} tamanho={48} />
          <div className="min-w-0"><b className="block truncate">{empresa.loja || "Nome da sua loja"}</b><span className="text-xs opacity-70">{empresa.ramo || "O que a loja vende"}</span></div>
        </div>
        <span className="mt-3 inline-block rounded-full px-4 py-1.5 text-sm font-semibold" style={{ background: principal, color: textoSobre(principal) }}>Ver produtos</span>
      </div>
    </div>
  );
}

type Passo = "como" | "site" | "nicho" | "nome" | "logo" | "cores" | "pronto";
const ORDEM_DA_LOJA: Passo[] = ["nicho", "nome", "logo", "cores", "pronto"];

/**
 * Primeiro acesso: "você tem loja?". Quem já tem site informa o endereço; quem vende sem site ou ainda não tem loja
 * escolhe o nicho, o nome, o logo e as cores. Toda etapa tem "Pular" e dá para refazer depois em Empresa › Identidade da loja.
 */
export function CriarLoja({ aoConcluir, aoIr }: { aoConcluir: () => void; aoIr?: (secao: string) => void }) {
  const { empresa, salvar } = useEmpresa();
  const [passo, setPasso] = useState<Passo>("como");
  const [trilha, setTrilha] = useState<"loja" | "site">("loja");
  const topo = useRef<HTMLDivElement>(null);
  useEffect(() => { topo.current?.scrollTo?.({ top: 0 }); }, [passo]);

  // a primeira paleta do nicho já aparece marcada na etapa das cores: guarda de verdade, mesmo que a pessoa só toque em Continuar
  useEffect(() => {
    if (passo === "cores" && !empresa.cor) { const [cor, corApoio] = paletasDoNicho(empresa.ramo)[0]!; salvar({ cor, corApoio }); }
  }, [passo, empresa.cor, empresa.ramo, salvar]);

  const concluir = (secao?: string) => { marcarGuiada(); aoConcluir(); if (secao) aoIr?.(secao); };
  const seguir = () => {
    if (passo === "nome" && trilha === "site") return setPasso("pronto");
    if (passo === "site") return setPasso("nome");
    const i = ORDEM_DA_LOJA.indexOf(passo);
    setPasso(ORDEM_DA_LOJA[Math.min(i + 1, ORDEM_DA_LOJA.length - 1)]!);
  };
  const voltar = () => {
    if (passo === "site" || (passo === "nicho")) return setPasso("como");
    if (passo === "nome") return setPasso(trilha === "site" ? "site" : "nicho");
    if (passo === "pronto") return setPasso(trilha === "site" ? "nome" : "cores");
    const i = ORDEM_DA_LOJA.indexOf(passo);
    setPasso(ORDEM_DA_LOJA[Math.max(i - 1, 0)]!);
  };
  const etapaDaLoja = ORDEM_DA_LOJA.indexOf(passo);

  const Rodape = ({ podePassar = true, rotuloSeguir = "Continuar", desabilitar = false }: { podePassar?: boolean; rotuloSeguir?: string; desabilitar?: boolean }) => (
    <div className="mt-6 flex flex-wrap items-center gap-3">
      <button type="button" className={botaoSuave} onClick={voltar}>‹ Voltar</button>
      <button type="button" className={`${botaoPrimario} flex-1`} disabled={desabilitar} onClick={seguir}>{rotuloSeguir}</button>
      {podePassar && <button type="button" className="px-2 text-sm text-suave underline" onClick={seguir}>Pular esta etapa</button>}
    </div>
  );

  return (
    <div className="fixed inset-0 z-50 flex flex-col pt-[env(safe-area-inset-top)]" style={{ background: "var(--color-fundo)" }} role="dialog" aria-modal="true" aria-label="Criar a sua loja">
      <div ref={topo} className="mx-auto flex min-h-0 w-full max-w-lg flex-1 flex-col overflow-y-auto px-5 pb-6 pt-5">
        <div className="mb-4 flex items-center justify-between">
          <span className="font-semibold tracking-[0.3em] text-venus">VÊNUS</span>
          <button type="button" className="text-sm text-suave underline" onClick={() => concluir()}>Pular tudo e configurar depois</button>
        </div>
        {passo !== "como" && passo !== "pronto" && trilha === "loja" && (
          <div className="mb-5 flex gap-1.5" aria-label={`Etapa ${etapaDaLoja + 1} de 4`}>
            {[0, 1, 2, 3].map((k) => <span key={k} className={`h-1.5 flex-1 rounded-full ${k <= etapaDaLoja ? "bg-venus" : "bg-white/15"}`} />)}
          </div>
        )}

        {passo === "como" && (
          <>
            <h1 className="text-2xl font-black">Vamos configurar a sua loja</h1>
            <p className="mt-1 text-suave">Como você vende hoje? Assim a Vênus já começa sabendo do seu negócio. Dá para mudar depois.</p>
            <div className="mt-5 flex flex-col gap-3">
              {([
                ["site", "Tenho loja com site", "Shopify, Nuvemshop, WooCommerce ou site próprio. A Vênus lê produtos, estoque e pedidos."],
                ["interno", "Vendo sem site", "WhatsApp, Instagram e Facebook. Produtos, fotos e estoque ficam aqui na Vênus."],
                ["nova", "Ainda não tenho loja", "Eu te ajudo a montar: nicho, nome, logo e cores. Leva poucos minutos."]
              ] as const).map(([id, titulo, sub]) => (
                <button key={id} type="button" className="rounded-2xl border border-borda bg-painel p-4 text-left transition hover:border-venus active:scale-[0.99]"
                  onClick={() => {
                    if (id === "site") { setTrilha("site"); salvar({ modo: "site" }); setPasso("site"); }
                    else { setTrilha("loja"); salvar({ modo: "interno" }); setPasso("nicho"); }
                  }}>
                  <b className="block">{titulo}</b><span className="mt-1 block text-sm text-suave">{sub}</span>
                </button>
              ))}
            </div>
          </>
        )}

        {passo === "site" && (
          <>
            <h1 className="text-2xl font-black">Qual é o site da loja?</h1>
            <p className="mt-1 text-suave">Pode ser o endereço completo. Para ligar a plataforma, é só ir em Ajustes depois.</p>
            <input className={`${campo} mt-5`} inputMode="url" autoCapitalize="none" placeholder="minhaloja.com.br" aria-label="Site da loja" defaultValue={empresa.site}
              onBlur={(e) => salvar({ site: normalizarSite(e.target.value) })} onChange={(e) => salvar({ site: e.target.value })} />
            <Rodape />
          </>
        )}

        {passo === "nicho" && (
          <>
            <h1 className="text-2xl font-black">O que a sua loja vende?</h1>
            <p className="mt-1 text-suave">Escolha o nicho mais parecido. Ele ajuda nas ideias de cores e de posts.</p>
            <div className="mt-5 flex flex-wrap gap-2">
              {NICHOS.map((n) => (
                <button key={n.id} type="button" aria-pressed={empresa.ramo === n.rotulo} onClick={() => salvar({ ramo: n.rotulo })}
                  className={`min-h-11 rounded-full border px-4 text-sm transition ${empresa.ramo === n.rotulo ? "border-venus bg-venus/15 font-semibold text-venus" : "border-white/15 bg-white/5"}`}>{n.rotulo}</button>
              ))}
            </div>
            <label className="mt-4 flex flex-col gap-1 text-sm"><span className="text-suave">Ou escreva com as suas palavras</span>
              <input className={campo} maxLength={120} value={empresa.ramo} placeholder="Ex.: acessórios para moto" onChange={(e) => salvar({ ramo: e.target.value })} /></label>
            <Rodape desabilitar={!empresa.ramo.trim()} />
          </>
        )}

        {passo === "nome" && (
          <>
            <h1 className="text-2xl font-black">{trilha === "site" ? "Conte um pouco sobre você" : "Como se chama a sua loja?"}</h1>
            <p className="mt-1 text-suave">É o nome que aparece para os clientes e nas respostas da Vênus.</p>
            <div className="mt-5 grid gap-3">
              <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Nome da loja</span>
                <input className={campo} maxLength={120} value={empresa.loja} onChange={(e) => salvar({ loja: e.target.value })} /></label>
              <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Seu nome</span>
                <input className={campo} maxLength={80} value={empresa.dono} onChange={(e) => salvar({ dono: e.target.value })} /></label>
              {trilha === "site" && <label className="flex flex-col gap-1 text-sm"><span className="text-suave">O que a loja vende</span>
                <input className={campo} maxLength={120} value={empresa.ramo} placeholder="Ex.: moda feminina" onChange={(e) => salvar({ ramo: e.target.value })} /></label>}
              <div className="grid grid-cols-[1fr_5rem] gap-3">
                <label className="flex flex-col gap-1 text-sm"><span className="text-suave">Cidade</span>
                  <input className={campo} maxLength={80} value={empresa.cidade} onChange={(e) => salvar({ cidade: e.target.value })} /></label>
                <label className="flex flex-col gap-1 text-sm"><span className="text-suave">UF</span>
                  <input className={campo} maxLength={2} value={empresa.uf} onChange={(e) => salvar({ uf: e.target.value })} /></label>
              </div>
            </div>
            <Rodape desabilitar={!empresa.loja.trim()} />
          </>
        )}

        {passo === "logo" && (
          <>
            <h1 className="text-2xl font-black">O logo da loja</h1>
            <p className="mt-1 text-suave">Escolha uma imagem do seu aparelho. Sem logo por enquanto? Eu uso as iniciais do nome e você troca quando quiser.</p>
            <div className="mt-6"><EditorDeLogo empresa={empresa} aoMudar={salvar} /></div>
            <Rodape rotuloSeguir={empresa.logo ? "Continuar" : "Continuar sem logo"} podePassar={false} />
          </>
        )}

        {passo === "cores" && (
          <>
            <h1 className="text-2xl font-black">As cores da loja</h1>
            <p className="mt-1 text-suave">Vão para o cartão de visitas, o panfleto, o QR Code e a vitrine.</p>
            <div className="mt-5"><EditorDeCores empresa={{ ...empresa, cor: empresa.cor || paletasDoNicho(empresa.ramo)[0]![0], corApoio: empresa.corApoio || paletasDoNicho(empresa.ramo)[0]![1] }} aoMudar={salvar} /></div>
            <Rodape />
          </>
        )}

        {passo === "pronto" && (
          <>
            <div className="flex flex-col items-center gap-3 text-center">
              <MarcaDaLoja loja={empresa.loja} logo={empresa.logo} cor={empresa.cor} tamanho={96} />
              <h1 className="text-2xl font-black">{empresa.loja ? `${empresa.loja} está pronta` : "Tudo certo por aqui"}</h1>
              <p className="text-suave">{trilha === "site" ? "Agora é ligar a plataforma da loja em Ajustes e cadastrar as regras de venda." : "O próximo passo é cadastrar os produtos e as regras de venda."} Falta alguma coisa? Em Empresa › Primeiros passos você vê o que ainda está pendente.</p>
            </div>
            <div className="mt-6 flex flex-col gap-3">
              {trilha === "loja" && <button type="button" className={botaoPrimario} onClick={() => concluir("vitrine")}>Cadastrar os produtos</button>}
              <button type="button" className={trilha === "loja" ? botaoSuave : botaoPrimario} onClick={() => concluir("regras")}>Cadastrar as regras de venda</button>
              {trilha === "site" && <button type="button" className={botaoSuave} onClick={() => setPasso("logo")}>Criar logo e cores agora</button>}
              <button type="button" className="py-2 text-sm text-suave underline" onClick={() => concluir()}>Começar a conversar com a Vênus</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/** Empresa › Identidade da loja: refazer o passo a passo ou ajustar logo e cores a qualquer hora. */
export function IdentidadeDaLoja({ aoRefazer }: { aoRefazer: () => void }) {
  const { empresa, salvar } = useEmpresa();
  return (
    <>
      <header className="mb-4">
        <h2 className="text-2xl font-semibold">Identidade da loja</h2>
        <p className="mt-1 text-sm text-suave">Logo e cores usados no cartão de visitas, no panfleto e na vitrine.</p>
      </header>
      <div className="grid gap-4 md:grid-cols-2">
        <section className="rounded-3xl border border-white/10 bg-white/[0.06] p-4"><h3 className="mb-4 text-base font-semibold">Logo</h3><EditorDeLogo empresa={empresa} aoMudar={salvar} /></section>
        <section className="rounded-3xl border border-white/10 bg-white/[0.06] p-4"><h3 className="mb-4 text-base font-semibold">Cores</h3><EditorDeCores empresa={empresa} aoMudar={salvar} /></section>
      </div>
      <button type="button" className={`${botaoSuave} mt-4`} onClick={aoRefazer}>Refazer o passo a passo da loja</button>
    </>
  );
}
