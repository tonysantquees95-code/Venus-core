import { useEffect, useMemo, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { usePersistido } from "../hooks/usePersistido";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import { useEmpresa } from "./EmpresaProvider";
import { enderecoParaQr, svgDoQr } from "./qr";

function Titulo({ titulo, descricao }: { titulo: string; descricao?: string }) {
  return (
    <div className="mb-5">
      <h2 className="text-2xl font-bold">{titulo}</h2>
      {descricao && <p className="mt-1 text-sm text-suave">{descricao}</p>}
    </div>
  );
}
const campo = "w-full rounded-xl border border-white/15 bg-white/5 px-3 py-2.5 text-sm outline-none focus:border-venus";

interface Dados { telefone: string; link: string; chamada: string; oferta: string; cor: string }
const PADRAO: Dados = { telefone: "", link: "", chamada: "", oferta: "", cor: "#7c3aed" };
function useDados() { return usePersistido<Dados>("venus:divulgacao", PADRAO); }

/** Imprime só a folha (o resto da tela some no @media print); no diálogo do Windows dá para "Salvar como PDF". */
function Imprimivel({ children, rotulo = "Imprimir ou salvar em PDF" }: { children: ReactNode; rotulo?: string }) {
  const [vez, setVez] = useState(0);
  useEffect(() => {
    if (!vez) return;
    const t = setTimeout(() => window.print(), 80);
    return () => clearTimeout(t);
  }, [vez]);
  return (
    <>
      <button className={botaoPrimario} onClick={() => setVez((v) => v + 1)}>{rotulo}</button>
      {createPortal(<div className="folha-impressao">{children}</div>, document.body)}
    </>
  );
}

function baixar(nome: string, tipo: string, dados: BlobPart) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([dados], { type: tipo }));
  a.download = nome;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

async function svgParaPng(svg: string, lado = 1024): Promise<Blob> {
  const img = new Image();
  const url = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
  await new Promise<void>((ok, falha) => { img.onload = () => ok(); img.onerror = () => falha(new Error("Não consegui gerar a imagem.")); img.src = url; });
  const c = document.createElement("canvas");
  c.width = c.height = lado;
  const x = c.getContext("2d")!;
  x.imageSmoothingEnabled = false;
  x.drawImage(img, 0, 0, lado, lado);
  URL.revokeObjectURL(url);
  return await new Promise<Blob>((ok, falha) => c.toBlob((b) => (b ? ok(b) : falha(new Error("Não consegui gerar a imagem."))), "image/png"));
}

function Qr({ texto, cor = "#000000", tamanho = 160 }: { texto: string; cor?: string; tamanho?: number }) {
  const svg = useMemo(() => { try { return svgDoQr(texto, { cor, margem: 2 }); } catch { return ""; } }, [texto, cor]);
  if (!svg) return null;
  return <img alt="QR Code" width={tamanho} height={tamanho} src={`data:image/svg+xml;utf8,${encodeURIComponent(svg)}`} style={{ imageRendering: "pixelated" }} />;
}

// ------------------------------------------------------------------ QR Code
export function QrCodeSecao() {
  const { empresa } = useEmpresa();
  const [d, setD] = useDados();
  const texto = enderecoParaQr(d.link, empresa.site);
  const [erro, setErro] = useState("");
  const svg = useMemo(() => { try { return texto ? svgDoQr(texto) : ""; } catch { return ""; } }, [texto]);
  return (
    <>
      <Titulo titulo="QR Code" descricao="Para o site, o Instagram, o WhatsApp ou o Pix da loja. Cole no balcão, no panfleto ou na embalagem." />
      <label className="mb-1 block text-sm text-suave">Endereço ou texto</label>
      <input className={`${campo} max-w-xl`} value={d.link} onChange={(e) => setD({ ...d, link: e.target.value })} placeholder={empresa.site || "https://suaLoja.com.br ou https://wa.me/55…"} />
      {texto ? (
        <div className="mt-5 flex flex-wrap items-start gap-6">
          <div className="rounded-2xl bg-white p-3"><Qr texto={texto} tamanho={220} /></div>
          <div className="space-y-2">
            <p className="max-w-xs break-all text-xs text-suave">{texto}</p>
            <div className="flex flex-wrap gap-2">
              <button className={botaoPrimario} onClick={() => svg && baixar("qrcode.svg", "image/svg+xml", svg)}>Baixar SVG</button>
              <button className={botaoSuave} onClick={() => { svgParaPng(svg).then((b) => baixar("qrcode.png", "image/png", b)).catch((e) => setErro(String(e.message))); }}>Baixar PNG</button>
              <Imprimivel rotulo="Imprimir"><div style={{ padding: "20mm", textAlign: "center" }}><Qr texto={texto} tamanho={500} /><p style={{ marginTop: 12, fontSize: 14 }}>{texto}</p></div></Imprimivel>
            </div>
            {erro && <p className="text-sm text-red-300">{erro}</p>}
          </div>
        </div>
      ) : <p className="mt-4 text-sm text-suave">Digite um endereço (ou cadastre o site da loja em “Dados da loja”).</p>}
    </>
  );
}

// ------------------------------------------------------------------ cartão de visitas
export function CartaoDeVisitas() {
  const { empresa } = useEmpresa();
  const [d, setD] = useDados();
  const alvo = enderecoParaQr(d.link, empresa.site);
  const cartao = (
    <div className="cartao-visita" style={{ background: "#0b0b14", color: "#fff", borderTop: `6px solid ${d.cor}` }}>
      <div className="cv-texto">
        <div className="cv-loja">{empresa.loja || "Nome da loja"}</div>
        {empresa.ramo && <div className="cv-linha cv-suave">{empresa.ramo}</div>}
        <div className="cv-espaco" />
        {empresa.dono && <div className="cv-linha">{empresa.dono}</div>}
        {d.telefone && <div className="cv-linha">{d.telefone}</div>}
        {empresa.site && <div className="cv-linha">{empresa.site.replace(/^https?:\/\//, "")}</div>}
        {(empresa.cidade || empresa.uf) && <div className="cv-linha cv-suave">{[empresa.cidade, empresa.uf].filter(Boolean).join("/")}</div>}
      </div>
      {alvo && <div className="cv-qr"><Qr texto={alvo} cor="#000000" tamanho={96} /></div>}
    </div>
  );
  return (
    <>
      <Titulo titulo="Cartão de visitas" descricao="Tamanho 9 × 5 cm com o QR Code da loja. Imprima em papel comum ou salve em PDF para mandar à gráfica." />
      <div className="mb-5 grid max-w-xl gap-3 sm:grid-cols-2">
        <div><label className="mb-1 block text-sm text-suave">Telefone / WhatsApp</label><input className={campo} value={d.telefone} onChange={(e) => setD({ ...d, telefone: e.target.value })} placeholder="(51) 99999-9999" /></div>
        <div><label className="mb-1 block text-sm text-suave">Cor de destaque</label><input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={d.cor} onChange={(e) => setD({ ...d, cor: e.target.value })} /></div>
      </div>
      <div className="mb-5 inline-block overflow-hidden rounded-xl shadow-lg">{cartao}</div>
      <div><Imprimivel>{cartao}</Imprimivel></div>
      <p className="mt-4 text-xs text-suave">Nome, ramo, cidade e site vêm de “Dados da loja”.</p>
    </>
  );
}

// ------------------------------------------------------------------ panfleto
export function Panfleto() {
  const { empresa } = useEmpresa();
  const [d, setD] = useDados();
  const alvo = enderecoParaQr(d.link, empresa.site);
  const folha = (
    <div className="panfleto" style={{ borderTop: `12mm solid ${d.cor}` }}>
      <h1 className="pf-loja">{empresa.loja || "Nome da loja"}</h1>
      <p className="pf-chamada">{d.chamada || "Escreva aqui a chamada do panfleto"}</p>
      {d.oferta && <div className="pf-oferta" style={{ background: d.cor }}>{d.oferta}</div>}
      {empresa.sobre && <p className="pf-sobre">{empresa.sobre.slice(0, 280)}</p>}
      <div className="pf-rodape">
        {alvo && <Qr texto={alvo} tamanho={140} />}
        <div>
          {d.telefone && <div className="pf-contato">{d.telefone}</div>}
          {empresa.site && <div className="pf-contato">{empresa.site.replace(/^https?:\/\//, "")}</div>}
          {(empresa.cidade || empresa.uf) && <div className="pf-suave">{[empresa.cidade, empresa.uf].filter(Boolean).join("/")}</div>}
        </div>
      </div>
    </div>
  );
  return (
    <>
      <Titulo titulo="Panfleto" descricao="Folha A5 pronta para imprimir ou salvar em PDF, com o QR Code da loja." />
      <div className="mb-5 grid max-w-2xl gap-3 sm:grid-cols-2">
        <div className="sm:col-span-2"><label className="mb-1 block text-sm text-suave">Chamada principal</label><input className={campo} value={d.chamada} onChange={(e) => setD({ ...d, chamada: e.target.value })} placeholder="Ex.: Frete grátis neste fim de semana" /></div>
        <div><label className="mb-1 block text-sm text-suave">Destaque (oferta)</label><input className={campo} value={d.oferta} onChange={(e) => setD({ ...d, oferta: e.target.value })} placeholder="Ex.: 10% de desconto no Pix" /></div>
        <div><label className="mb-1 block text-sm text-suave">Telefone / WhatsApp</label><input className={campo} value={d.telefone} onChange={(e) => setD({ ...d, telefone: e.target.value })} /></div>
        <div><label className="mb-1 block text-sm text-suave">Cor</label><input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={d.cor} onChange={(e) => setD({ ...d, cor: e.target.value })} /></div>
      </div>
      <div className="mb-5 w-[300px] overflow-hidden rounded-xl bg-white shadow-lg"><div style={{ transform: "scale(.5)", transformOrigin: "top left", width: "148mm", height: "210mm" }}>{folha}</div><div style={{ height: "calc(210mm * -0.5)" }} /></div>
      <div className="mt-[-100px]"><Imprimivel>{folha}</Imprimivel></div>
    </>
  );
}

// ------------------------------------------------------------------ vitrine, catálogo e links
export function VitrineECatalogo() {
  const { empresa } = useEmpresa();
  const site = enderecoParaQr("", empresa.site);
  return (
    <>
      <Titulo titulo="Vitrine, catálogo e links" descricao="O catálogo de produtos mora no celular (é de lá que a vitrine pública, o PDF do catálogo e os links curtos saem)." />
      <div className="space-y-3 text-sm">
        {site ? <button className={botaoSuave} onClick={() => window.open(site, "_blank", "noopener")}>Abrir o site da loja</button> : <p className="text-suave">Cadastre o site em “Dados da loja” para abrir daqui.</p>}
        <p className="text-suave">No computador você já tem QR Code, cartão de visitas e panfleto. A vitrine online, o PDF do catálogo e o encurtador de links ainda dependem do catálogo do celular: vou ligá-los a esta tela na próxima etapa.</p>
      </div>
    </>
  );
}
