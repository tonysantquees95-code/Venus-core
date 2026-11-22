import { useEffect, useMemo, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { usePersistido } from "../hooks/usePersistido";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import { useEmpresa } from "./EmpresaProvider";
import { useLojas } from "./LojasProvider";
import { enderecoParaQr, svgDoQr } from "./qr";
import { montarCartao, montarPanfleto, type DadosDaPeca, type Peca } from "./peca";
import { pdfComImagem } from "./pdf";
import { dinheiro } from "./lojaAdmin";
import { enderecoDoProduto, useVitrine } from "./vitrineWeb";

export function Titulo({ titulo, descricao }: { titulo: string; descricao?: string }) {
  return (
    <div className="mb-5">
      <h2 className="text-2xl font-bold">{titulo}</h2>
      {descricao && <p className="mt-1 text-sm text-suave">{descricao}</p>}
    </div>
  );
}
export const campo = "w-full rounded-xl border border-white/15 bg-white/5 px-3 py-2.5 text-sm outline-none focus:border-venus";

interface Dados { telefone: string; link: string; chamada: string; oferta: string; cor: string; qr: boolean; skus: string[] }
const PADRAO: Dados = { telefone: "", link: "", chamada: "", oferta: "", cor: "#7c3aed", qr: false, skus: [] };
function useDados() { const { sufixo } = useLojas(); const [d, set] = usePersistido<Dados>(`venus:divulgacao${sufixo}`, PADRAO); return [{ ...PADRAO, ...d }, set] as const; }

/** Imprime só a folha (o resto da tela some no @media print); no diálogo do Windows dá para "Salvar como PDF". */
export function Imprimivel({ children, rotulo = "Imprimir ou salvar em PDF", antes, depois, classe = botaoPrimario }: { children: ReactNode; rotulo?: string; antes?: () => boolean; depois?: () => void; classe?: string }) {
  const [vez, setVez] = useState(0);
  useEffect(() => {
    if (!vez) return;
    const t = setTimeout(() => { window.print(); depois?.(); }, 80);
    return () => clearTimeout(t);
  }, [vez]);
  return (
    <>
      <button className={classe} onClick={() => { if (!antes || antes()) setVez((v) => v + 1); }}>{rotulo}</button>
      {createPortal(<div className="folha-impressao">{children}</div>, document.body)}
    </>
  );
}

export function baixar(nome: string, tipo: string, dados: BlobPart) {
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

export function Qr({ texto, cor = "#000000", tamanho = 160 }: { texto: string; cor?: string; tamanho?: number }) {
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

// ------------------------------------------------------------------ peças digitais (panfleto e cartão de visitas)
async function desenhar(peca: Peca): Promise<HTMLCanvasElement> {
  const img = new Image();
  const url = URL.createObjectURL(new Blob([peca.svg], { type: "image/svg+xml;charset=utf-8" }));
  await new Promise<void>((ok, falha) => { img.onload = () => ok(); img.onerror = () => falha(new Error("Não consegui montar a imagem.")); img.src = url; });
  const c = document.createElement("canvas");
  const k = peca.escala ?? 1;
  c.width = Math.round(peca.largura * k); c.height = Math.round(peca.altura * k);
  const x = c.getContext("2d")!;
  x.fillStyle = "#ffffff"; x.fillRect(0, 0, c.width, c.height);
  x.drawImage(img, 0, 0, c.width, c.height);
  URL.revokeObjectURL(url);
  return c;
}
const paraBlob = (c: HTMLCanvasElement, tipo: string, q?: number) => new Promise<Blob>((ok, falha) => c.toBlob((b) => (b ? ok(b) : falha(new Error("Não consegui gerar o arquivo."))), tipo, q));
const nomeDeArquivo = (t: string) => (t.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-zA-Z0-9]+/g, "-").replace(/^-|-$/g, "").toLowerCase() || "loja");

/** Prévia da peça digital com os atalhos: mandar por WhatsApp, salvar em PDF (com links clicáveis) ou baixar a imagem. */
export function PecaDigital({ peca, arquivo, mensagem, link, antes, depois, semLink }: { peca: Peca; arquivo: string; mensagem: string; link: string; antes?: () => boolean; depois?: () => void; semLink?: boolean }) {
  const [aviso, setAviso] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const previa = useMemo(() => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(peca.svg), [peca.svg]);
  async function fazer(acao: () => Promise<string | void>) {
    if (antes && !antes()) return;
    setOcupado(true); setAviso("");
    try { const m = await acao(); depois?.(); if (m) setAviso(m); } catch (e) { if ((e as Error)?.name !== "AbortError") setAviso(e instanceof Error ? e.message : "Não deu certo."); }
    setOcupado(false);
  }
  const whats = () => fazer(async () => {
    const png = await paraBlob(await desenhar(peca), "image/png");
    const arq = new File([png], `${arquivo}.png`, { type: "image/png" });
    if (typeof navigator.canShare === "function" && navigator.canShare({ files: [arq] })) { await navigator.share({ files: [arq], text: mensagem }); return; }
    baixar(`${arquivo}.png`, "image/png", png);
    window.open(`https://wa.me/?text=${encodeURIComponent(mensagem)}`, "_blank", "noopener");
    return "Baixei a imagem e abri o WhatsApp com a mensagem e o link. É só anexar a imagem na conversa.";
  });
  const pdf = () => fazer(async () => {
    const jpeg = new Uint8Array(await (await paraBlob(await desenhar(peca), "image/jpeg", 0.92)).arrayBuffer());
    const k = peca.escala ?? 1;
    baixar(`${arquivo}.pdf`, "application/pdf", pdfComImagem(jpeg, peca.largura * k, peca.altura * k, peca.links, k) as unknown as BlobPart);
    return peca.links.length ? "PDF salvo. Os botões, os produtos e o QR Code dele são links clicáveis." : "PDF salvo.";
  });
  const imagem = () => fazer(async () => { baixar(`${arquivo}.png`, "image/png", await paraBlob(await desenhar(peca), "image/png")); });
  const copiar = () => fazer(async () => { await navigator.clipboard.writeText(mensagem); return "Mensagem copiada, com o link da loja."; });
  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-2xl bg-white shadow-lg" style={{ maxWidth: peca.largura > peca.altura ? 460 : 340 }}>
        <img alt="Prévia" src={previa} className="block w-full" />
      </div>
      <div className="flex flex-wrap gap-2">
        <button className={botaoPrimario} disabled={ocupado} onClick={() => void whats()}>Enviar por WhatsApp</button>
        <button className={botaoSuave} disabled={ocupado} onClick={() => void pdf()}>Salvar em PDF</button>
        <button className={botaoSuave} disabled={ocupado} onClick={() => void imagem()}>Baixar imagem</button>
        {link && <button className={botaoSuave} disabled={ocupado} onClick={() => void copiar()}>Copiar mensagem com link</button>}
      </div>
      {aviso && <p role="status" className="text-sm text-suave">{aviso}</p>}
      {!link && !semLink && <p className="text-xs text-suave">Dica: sem um link (site ou vitrine) a peça não tem botão clicável. Cadastre o site em “Dados da loja” ou publique a vitrine.</p>}
    </div>
  );
}

function LinhaQr({ d, aoMudar }: { d: Dados; aoMudar: (n: Dados) => void }) {
  return (
    <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-[var(--cor-venus,#7c3aed)]" checked={d.qr} onChange={(e) => aoMudar({ ...d, qr: e.target.checked })} /> Incluir QR Code (para quem vai abrir a peça em outra tela)</label>
  );
}

/** Para onde as peças levam: o que a pessoa escreveu, a vitrine (quando tem produtos) ou o site da loja. */
function usarLink(d: Dados, site: string, vitrine: string, comProdutos: boolean): string {
  return enderecoParaQr(d.link, "") || (comProdutos && vitrine) || enderecoParaQr("", site) || vitrine;
}
const semProtocolo = (u: string) => u.replace(/^https?:\/\//, "").replace(/\/$/, "");

// ------------------------------------------------------------------ cartão de visitas
export function CartaoDeVisitas() {
  const { empresa } = useEmpresa();
  const [d, setD] = useDados();
  const v = useVitrine();
  const cor = d.cor !== PADRAO.cor ? d.cor : empresa.cor || d.cor;
  const link = usarLink(d, empresa.site, v.info?.url ?? "", v.produtos.length > 0);
  const dados: DadosDaPeca = { loja: empresa.loja, ramo: empresa.ramo, logo: empresa.logo, cor, telefone: d.telefone, link, siteTexto: semProtocolo(link || empresa.site || ""), cidade: [empresa.cidade, empresa.uf].filter(Boolean).join("/"), qr: d.qr, gerarQr: (t) => svgDoQr(t, { margem: 2 }) };
  const peca = useMemo(() => montarCartao(dados), [JSON.stringify({ ...dados, gerarQr: 0 })]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <>
      <Titulo titulo="Cartão de visitas digital" descricao="Para mandar no WhatsApp, deixar no Instagram ou anexar ao e-mail. O telefone vira botão para chamar no WhatsApp e o link leva para a sua loja." />
      <div className="mb-5 grid max-w-xl gap-3 sm:grid-cols-2">
        <div><label className="mb-1 block text-sm text-suave">Telefone / WhatsApp</label><input className={campo} value={d.telefone} onChange={(e) => setD({ ...d, telefone: e.target.value })} placeholder="(51) 99999-9999" /></div>
        <div><label className="mb-1 block text-sm text-suave">Cor de destaque</label><input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={cor} onChange={(e) => setD({ ...d, cor: e.target.value })} /></div>
        <div className="sm:col-span-2"><label className="mb-1 block text-sm text-suave">Link do cartão (opcional)</label><input className={campo} value={d.link} onChange={(e) => setD({ ...d, link: e.target.value })} placeholder={link || "https://suaLoja.com.br"} /></div>
        <div className="sm:col-span-2"><LinhaQr d={d} aoMudar={setD} /></div>
      </div>
      <PecaDigital peca={peca} arquivo={`cartao-${nomeDeArquivo(empresa.loja)}`} link={link} mensagem={`${empresa.loja || "Minha loja"}${d.telefone ? `\n${d.telefone}` : ""}${link ? `\n${link}` : ""}`} />
      <p className="mt-4 text-xs text-suave">Nome, ramo, cidade e logo vêm de “Dados da loja” e “Identidade da loja”.</p>
    </>
  );
}

// ------------------------------------------------------------------ panfleto
export function Panfleto() {
  const { empresa } = useEmpresa();
  const [d, setD] = useDados();
  const v = useVitrine();
  const cor = d.cor !== PADRAO.cor ? d.cor : empresa.cor || d.cor;
  const vendaveis = v.produtos.filter((p) => p.preco_centavos != null && (p.estoque ?? 1) > 0);
  const escolhidos = d.skus.length ? vendaveis.filter((p) => d.skus.includes(p.sku)) : vendaveis.slice(0, 4);
  const link = usarLink(d, empresa.site, v.info?.url ?? "", vendaveis.length > 0);
  const base = v.info?.url ?? "";
  const produtos = base ? escolhidos.slice(0, 4).map((p) => ({ nome: p.nome, preco: dinheiro(p.promocao_centavos ?? p.preco_centavos), url: enderecoDoProduto(base, p.sku) })) : [];
  const dados: DadosDaPeca = { loja: empresa.loja, logo: empresa.logo, cor, chamada: d.chamada, oferta: d.oferta, telefone: d.telefone, link, siteTexto: semProtocolo(link || ""), cidade: [empresa.cidade, empresa.uf].filter(Boolean).join("/"), qr: d.qr, produtos, gerarQr: (t) => svgDoQr(t, { margem: 2 }) };
  const peca = useMemo(() => montarPanfleto(dados), [JSON.stringify({ ...dados, gerarQr: 0 })]); // eslint-disable-line react-hooks/exhaustive-deps
  const alternar = (sku: string) => {
    const atuais = d.skus.length ? d.skus : escolhidos.map((p) => p.sku);
    setD({ ...d, skus: atuais.includes(sku) ? atuais.filter((x) => x !== sku) : [...atuais, sku].slice(-4) });
  };
  return (
    <>
      <Titulo titulo="Panfleto digital" descricao="Feito para a tela: vertical, no tamanho do feed do Instagram e do WhatsApp. Mande a imagem ou o PDF, com o botão da loja e os produtos clicáveis." />
      <div className="mb-5 grid max-w-2xl gap-3 sm:grid-cols-2">
        <div className="sm:col-span-2"><label className="mb-1 block text-sm text-suave">Chamada principal</label><input className={campo} value={d.chamada} onChange={(e) => setD({ ...d, chamada: e.target.value })} placeholder="Ex.: Frete grátis neste fim de semana" /></div>
        <div><label className="mb-1 block text-sm text-suave">Destaque (oferta)</label><input className={campo} value={d.oferta} onChange={(e) => setD({ ...d, oferta: e.target.value })} placeholder="Ex.: 10% de desconto no Pix" /></div>
        <div><label className="mb-1 block text-sm text-suave">Telefone / WhatsApp</label><input className={campo} value={d.telefone} onChange={(e) => setD({ ...d, telefone: e.target.value })} /></div>
        <div><label className="mb-1 block text-sm text-suave">Cor</label><input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={cor} onChange={(e) => setD({ ...d, cor: e.target.value })} /></div>
        <div><label className="mb-1 block text-sm text-suave">Link do panfleto (opcional)</label><input className={campo} value={d.link} onChange={(e) => setD({ ...d, link: e.target.value })} placeholder={link || "https://suaLoja.com.br"} /></div>
        <div className="sm:col-span-2"><LinhaQr d={d} aoMudar={setD} /></div>
        {vendaveis.length > 0 && (
          <div className="sm:col-span-2">
            <p className="mb-1 text-sm text-suave">Produtos no panfleto (até 4, cada um abre a página dele na vitrine):</p>
            <div className="flex flex-wrap gap-2">
              {vendaveis.slice(0, 40).map((p) => {
                const ativo = escolhidos.some((x) => x.sku === p.sku);
                return <button key={p.sku} type="button" aria-pressed={ativo} onClick={() => alternar(p.sku)} className={`rounded-full border px-3 py-1.5 text-xs ${ativo ? "border-venus bg-venus/20" : "border-white/15 text-suave"}`}>{p.nome.slice(0, 28)}</button>;
              })}
            </div>
          </div>
        )}
        {!vendaveis.length && !v.carregando && <p className="sm:col-span-2 text-xs text-suave">Quer produtos clicáveis no panfleto? Cadastre-os em “Vitrine e catálogo”: cada um ganha uma página própria.</p>}
      </div>
      <PecaDigital peca={peca} arquivo={`panfleto-${nomeDeArquivo(empresa.loja)}`} link={link} mensagem={`${empresa.loja || "Minha loja"}${d.chamada ? `\n${d.chamada}` : ""}${d.oferta ? `\n${d.oferta}` : ""}${link ? `\nCompre aqui: ${link}` : ""}`} />
    </>
  );
}
