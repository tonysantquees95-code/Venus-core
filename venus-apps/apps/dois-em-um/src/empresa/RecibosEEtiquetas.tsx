import { useMemo, useState } from "react";
import { usePersistido } from "../hooks/usePersistido";
import { botaoSuave } from "../ajustes/Controles";
import { useId } from "../id/IdProvider";
import { useEmpresa } from "./EmpresaProvider";
import { useLojas } from "./LojasProvider";
import { Imprimivel, PecaDigital, Titulo, campo } from "./Divulgacao";
import {
  LIMITE_GRATIS, TIPOS_DE_DOC, comMarca, dataBR, lerReais, linhasDeTexto, mesDe, montarDocumento, nomeDoArquivoDoc, podeGerarDoc, registrarDoc,
  resumoDoUsoDoc, telefoneBonito, totalDe, dinheiroDoc, type Cota, type DadosDoc, type TipoDoc,
} from "./documentos";

interface DadosDaLojaDoc { documento: string; contato: string; endereco: string; mensagem: string }
const LOJA_PADRAO: DadosDaLojaDoc = { documento: "", contato: "", endereco: "", mensagem: "Obrigada pela compra! Volte sempre." };
interface ItemEditavel { nome: string; quantidade: string; preco: string }
interface Formulario {
  tipo: TipoDoc; numero: string; cliente: string; telefone: string; endereco: string; rastreio: string; observacao: string;
  retirada: boolean; frete: string; desconto: string; itens: ItemEditavel[];
}
const novoNumero = () => "A" + new Date().toISOString().replace(/\D/g, "").slice(2, 12);
const FORM_PADRAO = (): Formulario => ({
  tipo: "recibo", numero: novoNumero(), cliente: "", telefone: "", endereco: "", rastreio: "", observacao: "", retirada: false,
  frete: "", desconto: "", itens: [{ nome: "", quantidade: "1", preco: "" }],
});

const rotulo = "mb-1 block text-sm text-suave";

/** Recibo, notinha e etiqueta de envio: preenche, vê a prévia e manda por WhatsApp, salva em PDF ou imprime. Sem valor fiscal. */
export function RecibosEEtiquetas() {
  const { empresa } = useEmpresa();
  const { sufixo } = useLojas();
  const { estado } = useId();
  const semLimite = estado.tipo === "livre" && !estado.teste;
  const [loja, setLoja] = usePersistido<DadosDaLojaDoc>(`venus:docs-loja${sufixo}`, LOJA_PADRAO);
  const [cota, setCota] = usePersistido<Cota | null>(`venus:docs-cota${sufixo}`, null);
  const [f, setF] = useState<Formulario>(FORM_PADRAO);
  const [aviso, setAviso] = useState("");
  const L = { ...LOJA_PADRAO, ...loja };
  const mes = mesDe(Date.now());

  const dados: DadosDoc = useMemo(() => ({
    emissor: {
      nome: empresa.loja, documento: L.documento.trim(), contato: L.contato.trim(), mensagem: L.mensagem.trim(), logo: empresa.logo || undefined,
      enderecoLinhas: linhasDeTexto(L.endereco).length ? linhasDeTexto(L.endereco) : [[empresa.cidade, empresa.uf].filter(Boolean).join("/")].filter(Boolean),
    },
    numero: f.numero.trim() || "avulso", data: dataBR(Date.now()), cliente: f.cliente.trim(), telefone: telefoneBonito(f.telefone),
    enderecoLinhas: linhasDeTexto(f.endereco),
    itens: f.itens.map((i) => ({ nome: i.nome.trim(), quantidade: Math.max(1, parseInt(i.quantidade, 10) || 1), precoCentavos: lerReais(i.preco) ?? 0 })).filter((i) => i.nome || i.precoCentavos),
    freteCentavos: lerReais(f.frete) ?? 0, descontoCentavos: lerReais(f.desconto) ?? 0,
    retirada: f.retirada, rastreio: f.rastreio.trim(), observacao: f.observacao.trim(),
  }), [f, L.documento, L.contato, L.mensagem, L.endereco, empresa.loja, empresa.logo, empresa.cidade, empresa.uf]);
  const peca = useMemo(() => montarDocumento(f.tipo, dados, comMarca(semLimite)), [f.tipo, dados, semLimite]);

  const antes = () => {
    if (!dados.itens.length && f.tipo !== "etiqueta") { setAviso("Coloque pelo menos um item com o valor."); return false; }
    if (!podeGerarDoc(cota, mes, f.tipo, dados.numero, semLimite)) {
      setAviso(`Você usou os ${LIMITE_GRATIS} documentos grátis deste mês. Volte no mês que vem ou assine um plano para não ter limite.`);
      return false;
    }
    setAviso("");
    return true;
  };
  const depois = () => { if (!semLimite) setCota((c) => registrarDoc(c, mes, f.tipo, dados.numero)); };
  const mudar = <K extends keyof Formulario>(k: K, v: Formulario[K]) => setF((a) => ({ ...a, [k]: v }));
  const mudarItem = (i: number, p: Partial<ItemEditavel>) => setF((a) => ({ ...a, itens: a.itens.map((x, k) => (k === i ? { ...x, ...p } : x)) }));

  return (
    <>
      <Titulo titulo="Recibos e etiquetas" descricao="Recibo, notinha e etiqueta de envio com o nome e o logo da sua loja. Sem valor fiscal. Tudo fica no seu aparelho." />
      <p className="mb-4 text-sm text-suave">{resumoDoUsoDoc(cota, mes, semLimite)}{semLimite ? "" : " Nos planos pagos não tem limite nem a marca da Vênus."}</p>

      <div className="mb-5 flex flex-wrap gap-2" role="radiogroup" aria-label="Tipo de documento">
        {TIPOS_DE_DOC.map((t) => (
          <button key={t.id} type="button" role="radio" aria-checked={f.tipo === t.id} onClick={() => mudar("tipo", t.id)}
            className={`rounded-2xl border px-4 py-2.5 text-left text-sm ${f.tipo === t.id ? "border-venus bg-venus/20" : "border-white/15 bg-white/5"}`}>
            <span className="block font-semibold">{t.rotulo}</span><span className="block text-xs text-suave">{t.dica}</span>
          </button>
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
        <div className="space-y-5">
          <section className="grid max-w-2xl gap-3 sm:grid-cols-2">
            <div><label className={rotulo}>Nome do cliente</label><input className={campo} value={f.cliente} onChange={(e) => mudar("cliente", e.target.value)} /></div>
            <div><label className={rotulo}>Telefone</label><input className={campo} value={f.telefone} inputMode="tel" onChange={(e) => mudar("telefone", e.target.value)} /></div>
            <div><label className={rotulo}>Número do documento</label><input className={campo} value={f.numero} onChange={(e) => mudar("numero", e.target.value)} /></div>
            <div><label className={rotulo}>Código de rastreio (opcional)</label><input className={campo} value={f.rastreio} onChange={(e) => mudar("rastreio", e.target.value.toUpperCase())} /></div>
            <div className="sm:col-span-2">
              <label className={rotulo}>Endereço de entrega (uma linha por item)</label>
              <textarea className={campo} rows={4} value={f.endereco} placeholder={"Rua, número\nBairro\nCidade/UF\nCEP"} onChange={(e) => mudar("endereco", e.target.value)} />
              <label className="mt-2 flex items-center gap-2 text-sm"><input type="checkbox" className="size-4" checked={f.retirada} onChange={(e) => mudar("retirada", e.target.checked)} /> Retirada na loja (sem endereço)</label>
            </div>
            <div className="sm:col-span-2"><label className={rotulo}>Observação (opcional)</label><input className={campo} value={f.observacao} placeholder="Ex.: frágil" onChange={(e) => mudar("observacao", e.target.value)} /></div>
          </section>

          <section className="max-w-2xl">
            <p className="mb-2 text-sm font-semibold">Itens</p>
            <div className="space-y-2">
              {f.itens.map((it, i) => (
                <div key={i} className="grid grid-cols-[minmax(0,1fr)_4.5rem_6.5rem_auto] items-center gap-2">
                  <input className={campo} aria-label={`Item ${i + 1}`} placeholder="Descrição" value={it.nome} onChange={(e) => mudarItem(i, { nome: e.target.value })} />
                  <input className={campo} aria-label="Quantidade" inputMode="numeric" value={it.quantidade} onChange={(e) => mudarItem(i, { quantidade: e.target.value })} />
                  <input className={campo} aria-label="Preço unitário" inputMode="decimal" placeholder="89,90" value={it.preco} onChange={(e) => mudarItem(i, { preco: e.target.value })} />
                  <button type="button" className={botaoSuave} aria-label={`Tirar o item ${i + 1}`} disabled={f.itens.length === 1} onClick={() => setF((a) => ({ ...a, itens: a.itens.filter((_, k) => k !== i) }))}>Tirar</button>
                </div>
              ))}
            </div>
            <button type="button" className={`${botaoSuave} mt-3`} onClick={() => setF((a) => ({ ...a, itens: [...a.itens, { nome: "", quantidade: "1", preco: "" }] }))}>Adicionar item</button>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div><label className={rotulo}>Frete (R$)</label><input className={campo} inputMode="decimal" value={f.frete} onChange={(e) => mudar("frete", e.target.value)} /></div>
              <div><label className={rotulo}>Desconto (R$)</label><input className={campo} inputMode="decimal" value={f.desconto} onChange={(e) => mudar("desconto", e.target.value)} /></div>
            </div>
            <p className="mt-3 text-sm">Total: <strong>{dinheiroDoc(totalDe(dados))}</strong></p>
          </section>

          <section className="max-w-2xl">
            <p className="mb-2 text-sm font-semibold">Sua loja nos documentos</p>
            <p className="mb-2 text-xs text-suave">O nome e o logo vêm de “Dados da loja” e “Identidade da loja”. Estes campos ficam guardados para os próximos documentos.</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <div><label className={rotulo}>CNPJ ou CPF (opcional)</label><input className={campo} value={L.documento} onChange={(e) => setLoja({ ...L, documento: e.target.value })} /></div>
              <div><label className={rotulo}>Contato (WhatsApp ou Instagram)</label><input className={campo} value={L.contato} onChange={(e) => setLoja({ ...L, contato: e.target.value })} /></div>
              <div className="sm:col-span-2"><label className={rotulo}>Endereço do remetente (uma linha por item)</label><textarea className={campo} rows={3} value={L.endereco} onChange={(e) => setLoja({ ...L, endereco: e.target.value })} /></div>
              <div className="sm:col-span-2"><label className={rotulo}>Mensagem de agradecimento</label><input className={campo} value={L.mensagem} onChange={(e) => setLoja({ ...L, mensagem: e.target.value })} /></div>
            </div>
          </section>
        </div>

        <div className="space-y-3">
          <PecaDigital peca={peca} arquivo={nomeDoArquivoDoc(f.tipo, dados.numero)} link="" semLink antes={antes} depois={depois}
            mensagem={`${TIPOS_DE_DOC.find((t) => t.id === f.tipo)!.rotulo} nº ${dados.numero} · ${empresa.loja || "Minha loja"}${f.tipo === "etiqueta" ? "" : `\nTotal: ${dinheiroDoc(totalDe(dados))}`}`} />
          <Imprimivel rotulo="Imprimir" classe={botaoSuave} antes={antes} depois={depois}>
            <img alt={TIPOS_DE_DOC.find((t) => t.id === f.tipo)!.rotulo} src={"data:image/svg+xml;charset=utf-8," + encodeURIComponent(peca.svg)} style={{ width: f.tipo === "notinha" ? "80mm" : f.tipo === "etiqueta" ? "100mm" : "100%" }} />
          </Imprimivel>
          {aviso && <p role="alert" className="text-sm text-amber-300">{aviso}</p>}
        </div>
      </div>
    </>
  );
}
