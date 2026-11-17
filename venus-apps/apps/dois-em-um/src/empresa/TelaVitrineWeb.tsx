import { useMemo, useState } from "react";
import { botaoPrimario, botaoSuave } from "../ajustes/Controles";
import type { ItemVitrine } from "../id/api";
import { useEmpresa } from "./EmpresaProvider";
import { campo, Qr, Titulo } from "./Divulgacao";
import { centavosDe, dinheiro } from "./lojaAdmin";
import { enderecoDoProduto, useVitrine } from "./vitrineWeb";

/** Reduz a foto para JPEG de até ~250 KB (o servidor guarda a foto reduzida, como no celular). */
async function fotoReduzida(arq: File): Promise<string> {
  if (!arq.type.startsWith("image/")) throw new Error("Escolha uma imagem (JPG, PNG ou WebP).");
  const url = URL.createObjectURL(arq);
  try {
    const img = new Image();
    await new Promise<void>((ok, falha) => { img.onload = () => ok(); img.onerror = () => falha(new Error("Não consegui ler essa imagem.")); img.src = url; });
    for (const lado of [900, 700, 520]) {
      const k = Math.min(1, lado / Math.max(img.width, img.height));
      const c = document.createElement("canvas");
      c.width = Math.max(1, Math.round(img.width * k)); c.height = Math.max(1, Math.round(img.height * k));
      const x = c.getContext("2d")!;
      x.fillStyle = "#ffffff"; x.fillRect(0, 0, c.width, c.height); x.drawImage(img, 0, 0, c.width, c.height);
      for (const q of [0.82, 0.7, 0.55]) {
        const dados = c.toDataURL("image/jpeg", q).split(",")[1] ?? "";
        if (dados.length <= 330_000) return dados;
      }
    }
    throw new Error("A foto ficou grande demais mesmo reduzida. Escolha outra.");
  } finally { URL.revokeObjectURL(url); }
}

interface Linha { sku: string; nome: string; preco: string; promocao: string; estoque: string; categoria: string; descricao: string; imagem: string; foto?: string }
const daLinha = (p: ItemVitrine): Linha => ({ sku: p.sku, nome: p.nome, preco: p.preco_centavos == null ? "" : (p.preco_centavos / 100).toFixed(2).replace(".", ","), promocao: p.promocao_centavos == null ? "" : (p.promocao_centavos / 100).toFixed(2).replace(".", ","), estoque: String(p.estoque ?? 1), categoria: p.categoria, descricao: p.descricao, imagem: p.imagem });

/** Empresa › Vitrine e catálogo: a vitrine online da loja, aberta e editada também pelo computador, Mac, iPhone e navegador. */
export function VitrineECatalogo() {
  const { empresa } = useEmpresa();
  const v = useVitrine();
  const [linhas, setLinhas] = useState<Linha[] | null>(null);
  const [aviso, setAviso] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const [visual, setVisual] = useState<{ titulo: string; descricao: string; cor: string; whatsapp: string } | null>(null);
  const lista = linhas ?? v.produtos.map(daLinha);
  const dadosVisual = visual ?? { titulo: v.info?.titulo || empresa.loja, descricao: v.info?.descricao || "", cor: v.info?.cor || empresa.cor || "#7c3aed", whatsapp: v.info?.whatsapp || "" };
  const url = v.info?.url ?? "";
  const mensagem = useMemo(() => `${dadosVisual.titulo || empresa.loja}: veja os produtos e compre pelo link\n${url}`, [dadosVisual.titulo, empresa.loja, url]);

  if (!v.sessao) return <><Titulo titulo="Vitrine e catálogo" /><p className="text-sm text-suave">Entre com o seu Vetorial ID (Ajustes › Conta) para abrir a vitrine da sua loja.</p></>;
  if (v.erro && !v.info) return <><Titulo titulo="Vitrine e catálogo" /><p role="alert" className="text-sm text-rose-300">{v.erro}</p><button className={`${botaoSuave} mt-3`} onClick={() => void v.carregar()}>Tentar de novo</button></>;
  if (!v.info) return <><Titulo titulo="Vitrine e catálogo" /><p className="text-sm text-suave">Carregando a sua vitrine…</p></>;

  const mudar = (i: number, p: Partial<Linha>) => setLinhas(lista.map((l, k) => (k === i ? { ...l, ...p } : l)));
  const falha = (e: unknown) => setAviso(e instanceof Error ? e.message : "Não deu certo.");

  async function salvarVisual() {
    if (!v.sessao) return;
    setOcupado(true); setAviso("");
    try { await v.api.salvarVitrine(v.sessao.token, dadosVisual); await v.carregar(); setVisual(null); setAviso("Aparência da vitrine salva."); } catch (e) { falha(e); }
    setOcupado(false);
  }
  async function publicar() {
    if (!v.sessao) return;
    const token = v.sessao.token;
    for (const l of lista) {
      if (!l.nome.trim()) return setAviso("Todo produto precisa de um nome.");
      if (Number.isNaN(centavosDe(l.preco)) || Number.isNaN(centavosDe(l.promocao))) return setAviso(`O preço de “${l.nome}” não é válido (ex.: 29,90).`);
    }
    setOcupado(true); setAviso("");
    try {
      for (const l of lista) if (l.foto) await v.api.fotoDaVitrine(token, l.sku, l.foto); // a foto sobe antes: a vitrine usa a que estiver guardada
      const r = await v.api.salvarItens(token, lista.map((l) => ({ sku: l.sku, nome: l.nome.trim(), preco_centavos: centavosDe(l.preco), promocao_centavos: centavosDe(l.promocao), estoque: Math.max(0, Math.round(Number(l.estoque.replace(",", ".")) || 0)), categoria: l.categoria.trim(), descricao: l.descricao.trim(), imagem: l.foto ? "" : l.imagem })));
      setLinhas(null); await v.carregar(); setAviso(`Vitrine publicada com ${r.itens} produto${r.itens === 1 ? "" : "s"}.`);
    } catch (e) { falha(e); }
    setOcupado(false);
  }
  const novo = () => setLinhas([...lista, { sku: "w" + Date.now().toString(36), nome: "", preco: "", promocao: "", estoque: "1", categoria: "", descricao: "", imagem: "" }]);

  return (
    <>
      <Titulo titulo="Vitrine e catálogo" descricao="A página online da sua loja, com os produtos clicáveis. Ela funciona sem site próprio e abre em qualquer celular ou computador." />
      <section className="mb-6 rounded-3xl border border-white/10 bg-white/[0.06] p-4">
        <h3 className="mb-2 text-base font-semibold">Link da vitrine</h3>
        <p className="break-all text-sm">{url}</p>
        <div className="mt-3 flex flex-wrap gap-2">
          <button className={botaoPrimario} onClick={() => window.open(url, "_blank", "noopener")}>Abrir a vitrine</button>
          <button className={botaoSuave} onClick={() => { void navigator.clipboard.writeText(url).then(() => setAviso("Link copiado.")); }}>Copiar link</button>
          <button className={botaoSuave} onClick={() => window.open(`https://wa.me/?text=${encodeURIComponent(mensagem)}`, "_blank", "noopener")}>Enviar por WhatsApp</button>
        </div>
        <div className="mt-4 inline-block rounded-2xl bg-white p-3"><Qr texto={url} tamanho={150} /></div>
        {!v.produtos.length && <p className="mt-3 text-sm text-suave">A vitrine ainda não tem produtos. Adicione abaixo e publique.</p>}
      </section>

      <section className="mb-6 rounded-3xl border border-white/10 bg-white/[0.06] p-4">
        <h3 className="mb-3 text-base font-semibold">Aparência</h3>
        <div className="grid max-w-xl gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2"><label className="mb-1 block text-sm text-suave">Título</label><input className={campo} maxLength={80} value={dadosVisual.titulo} onChange={(e) => setVisual({ ...dadosVisual, titulo: e.target.value })} /></div>
          <div className="sm:col-span-2"><label className="mb-1 block text-sm text-suave">Descrição</label><input className={campo} maxLength={300} value={dadosVisual.descricao} onChange={(e) => setVisual({ ...dadosVisual, descricao: e.target.value })} /></div>
          <div><label className="mb-1 block text-sm text-suave">WhatsApp da loja</label><input className={campo} inputMode="tel" value={dadosVisual.whatsapp} onChange={(e) => setVisual({ ...dadosVisual, whatsapp: e.target.value })} placeholder="5551999999999" /></div>
          <div><label className="mb-1 block text-sm text-suave">Cor</label><input type="color" className="h-11 w-full rounded-xl border border-white/15 bg-white/5" value={dadosVisual.cor} onChange={(e) => setVisual({ ...dadosVisual, cor: e.target.value })} /></div>
        </div>
        <button className={`${botaoSuave} mt-3`} disabled={ocupado || !visual} onClick={() => void salvarVisual()}>Salvar aparência</button>
      </section>

      <section className="rounded-3xl border border-white/10 bg-white/[0.06] p-4">
        <h3 className="mb-3 text-base font-semibold">Produtos da vitrine</h3>
        <div className="space-y-3">
          {lista.map((l, i) => (
            <div key={l.sku} className="rounded-2xl border border-white/10 p-3">
              <div className="grid gap-2 sm:grid-cols-4">
                <input className={`${campo} sm:col-span-2`} placeholder="Nome do produto" aria-label="Nome do produto" value={l.nome} onChange={(e) => mudar(i, { nome: e.target.value })} />
                <input className={campo} inputMode="decimal" placeholder="Preço (29,90)" aria-label="Preço" value={l.preco} onChange={(e) => mudar(i, { preco: e.target.value })} />
                <input className={campo} inputMode="decimal" placeholder="Promoção (opcional)" aria-label="Preço em promoção" value={l.promocao} onChange={(e) => mudar(i, { promocao: e.target.value })} />
                <input className={campo} inputMode="numeric" placeholder="Estoque" aria-label="Estoque" value={l.estoque} onChange={(e) => mudar(i, { estoque: e.target.value })} />
                <input className={campo} placeholder="Categoria" aria-label="Categoria" value={l.categoria} onChange={(e) => mudar(i, { categoria: e.target.value })} />
                <input className={`${campo} sm:col-span-2`} placeholder="Descrição curta" aria-label="Descrição" value={l.descricao} onChange={(e) => mudar(i, { descricao: e.target.value })} />
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-suave">
                {(l.foto || l.imagem) && <img alt="" className="size-10 rounded-lg object-cover" src={l.foto ? `data:image/jpeg;base64,${l.foto}` : l.imagem} />}
                <label className="cursor-pointer rounded-full border border-white/15 px-3 py-1.5">Foto<input type="file" accept="image/*" hidden onChange={async (e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) { try { mudar(i, { foto: await fotoReduzida(f) }); } catch (x) { falha(x); } } }} /></label>
                {l.preco && !Number.isNaN(centavosDe(l.preco)) && <span>{dinheiro(centavosDe(l.preco))}</span>}
                {v.produtos.some((p) => p.sku === l.sku) && <a className="underline" href={enderecoDoProduto(url, l.sku)} target="_blank" rel="noopener noreferrer">Página do produto</a>}
                <button type="button" className="ml-auto underline" onClick={() => setLinhas(lista.filter((_, k) => k !== i))}>Remover</button>
              </div>
            </div>
          ))}
          {!lista.length && <p className="text-sm text-suave">Nenhum produto ainda.</p>}
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <button className={botaoSuave} onClick={novo}>＋ Adicionar produto</button>
          <button className={botaoPrimario} disabled={ocupado || linhas === null} onClick={() => void publicar()}>Publicar na vitrine</button>
          {linhas && <button className={botaoSuave} disabled={ocupado} onClick={() => setLinhas(null)}>Descartar mudanças</button>}
        </div>
        <p className="mt-3 text-xs text-suave">Vale o mesmo para o celular: o que você publicar aqui aparece lá e o contrário também. Só ficam visíveis os produtos com preço e estoque.</p>
      </section>
      {aviso && <p role="status" className="mt-4 text-sm text-suave">{aviso}</p>}
    </>
  );
}
