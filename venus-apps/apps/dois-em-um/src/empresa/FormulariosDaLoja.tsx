import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ponteDesktop, type RespostaLoja } from "../desktop/ponte";
import { useEmpresa } from "./EmpresaProvider";
import { useCatalogo } from "./CatalogoProvider";
import { normalizarSite } from "./logica";
import { pedirTexto } from "./ia";
import { CATEGORIAS, categoriaProvavel, centavosDe, corpoDeAtualizacao, corpoDoNovo, dinheiro, type Mudancas } from "./lojaAdmin";

type Tipo = "cadastrar" | "atualizar";
interface Aberto { tipo: Tipo; aoTerminar: (texto: string) => void }
interface Ctx { abrir: (tipo: Tipo, aoTerminar: (texto: string) => void) => void }
const C = createContext<Ctx | null>(null);

export function useFormulariosDaLoja(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useFormulariosDaLoja precisa estar dentro de <FormulariosDaLojaProvider>");
  return c;
}

export function FormulariosDaLojaProvider({ children }: { children: ReactNode }) {
  const [aberto, setAberto] = useState<Aberto | null>(null);
  const abrir = useCallback((tipo: Tipo, aoTerminar: (t: string) => void) => setAberto({ tipo, aoTerminar }), []);
  const v = useMemo(() => ({ abrir }), [abrir]);
  return (
    <C.Provider value={v}>
      {children}
      {aberto && <JanelaDaLoja tipo={aberto.tipo} aoFechar={(texto) => { const a = aberto; setAberto(null); if (texto) a.aoTerminar(texto); }} />}
    </C.Provider>
  );
}

// ---------------------------------------------------------------- utilidades
async function chamar<T>(p: Promise<RespostaLoja<T>>): Promise<T> {
  const r = await p;
  if (!r.ok) throw new Error(r.erro);
  return r.dado;
}

const classeCampo = "w-full rounded-lg border border-borda bg-fundo px-3 py-2 text-sm text-texto outline-none focus:border-venus";
const classeBotao = "rounded-full border border-borda px-4 py-2 text-sm text-texto hover:border-venus disabled:opacity-50";
const classePrimario = "rounded-full bg-venus px-5 py-2 text-sm font-semibold text-sobre-venus shadow-md shadow-venus/30 disabled:opacity-50";

function Rotulo({ texto, children }: { texto: string; children: ReactNode }) {
  return <label className="block"><span className="mb-1 block text-xs text-suave">{texto}</span>{children}</label>;
}

/** Foto da câmera/galeria → JPEG de até 1280 px (em base64, sem o prefixo). */
async function prepararFoto(arquivo: File): Promise<{ base64: string; previa: string }> {
  const bmp = await createImageBitmap(arquivo);
  const escala = Math.min(1, 1280 / Math.max(bmp.width, bmp.height));
  const c = document.createElement("canvas");
  c.width = Math.max(1, Math.round(bmp.width * escala));
  c.height = Math.max(1, Math.round(bmp.height * escala));
  const g = c.getContext("2d");
  if (!g) throw new Error("Não consegui preparar a foto.");
  g.fillStyle = "#fff";
  g.fillRect(0, 0, c.width, c.height);
  g.drawImage(bmp, 0, 0, c.width, c.height);
  const url = c.toDataURL("image/jpeg", 0.85);
  return { base64: url.split(",")[1] ?? "", previa: url };
}

function CampoDeFoto({ foto, aoMudar, atual }: { foto: { base64: string; previa: string } | null; aoMudar: (f: { base64: string; previa: string } | null) => void; atual?: string | null }) {
  const [erro, setErro] = useState("");
  const entrada = useRef<HTMLInputElement>(null);
  return (
    <div>
      <span className="mb-1 block text-xs text-suave">Foto</span>
      <div className="flex items-center gap-3">
        {(foto?.previa || atual) ? <img src={foto?.previa || atual || ""} alt="Foto do produto" className="size-20 rounded-lg border border-borda object-cover" /> : <div className="grid size-20 place-items-center rounded-lg border border-dashed border-borda text-xs text-suave">sem foto</div>}
        <div className="flex flex-col gap-2">
          <button type="button" className={classeBotao} onClick={() => entrada.current?.click()}>{foto || atual ? "Trocar foto…" : "Escolher foto…"}</button>
          {foto && <button type="button" className="text-xs text-suave underline" onClick={() => aoMudar(null)}>Desfazer troca</button>}
        </div>
        <input ref={entrada} type="file" accept="image/*" hidden onChange={async (e) => {
          const f = e.target.files?.[0]; e.target.value = "";
          if (!f) return;
          setErro("");
          try { aoMudar(await prepararFoto(f)); } catch { setErro("Não consegui ler essa foto. Tente outra."); }
        }} />
      </div>
      {erro && <p className="mt-1 text-xs text-red-400">{erro}</p>}
    </div>
  );
}

// ---------------------------------------------------------------- janela
function JanelaDaLoja({ tipo, aoFechar }: { tipo: Tipo; aoFechar: (texto?: string) => void }) {
  const ponte = ponteDesktop()?.loja;
  const { empresa } = useEmpresa();
  const [estado, setEstado] = useState<"carregando" | "entrar" | "pronto">("carregando");
  const [erro, setErro] = useState("");

  useEffect(() => {
    if (!ponte) return;
    chamar(ponte.estado()).then((e) => setEstado(e.conectada ? "pronto" : "entrar")).catch((e) => { setErro(String(e.message || e)); setEstado("entrar"); });
  }, [ponte]);

  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => { if (e.key === "Escape") aoFechar(); };
    window.addEventListener("keydown", aoTeclar);
    return () => window.removeEventListener("keydown", aoTeclar);
  }, [aoFechar]);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/60 p-4" role="dialog" aria-modal="true" aria-label={tipo === "cadastrar" ? "Cadastrar produto" : "Atualizar produto"}>
      <div className="flex max-h-[92vh] w-full max-w-lg flex-col rounded-2xl border border-borda bg-painel shadow-2xl">
        <header className="flex items-center justify-between border-b border-borda px-5 py-3">
          <h2 className="text-base font-semibold">{tipo === "cadastrar" ? "Cadastrar produto" : "Atualizar produto"}</h2>
          <button type="button" aria-label="Fechar" className="text-suave hover:text-texto" onClick={() => aoFechar()}>✕</button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          {!ponte ? (
            <p className="text-sm text-suave">Esta janelinha funciona no app da Vênus para Windows. No celular, use a Vênus do próprio celular.</p>
          ) : estado === "carregando" ? (
            <p className="text-sm text-suave">Abrindo…</p>
          ) : estado === "entrar" ? (
            <LoginDaLoja site={normalizarSite(empresa.site)} erroInicial={erro} aoEntrar={() => setEstado("pronto")} />
          ) : tipo === "cadastrar" ? (
            <FormCadastro aoTerminar={aoFechar} />
          ) : (
            <FormAtualizar aoTerminar={aoFechar} aoSair={() => setEstado("entrar")} />
          )}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- login da loja
function LoginDaLoja({ site, erroInicial, aoEntrar }: { site: string; erroInicial: string; aoEntrar: () => void }) {
  const ponte = ponteDesktop()!.loja!;
  const [endereco, setEndereco] = useState(site);
  const [email, setEmail] = useState("");
  const [senha, setSenha] = useState("");
  const [erro, setErro] = useState(erroInicial);
  const [ocupado, setOcupado] = useState(false);
  async function entrar() {
    setErro(""); setOcupado(true);
    try { await chamar(ponte.entrar(endereco, email, senha)); aoEntrar(); }
    catch (e) { setErro(e instanceof Error ? e.message : "Não consegui entrar."); }
    finally { setOcupado(false); }
  }
  return (
    <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void entrar(); }}>
      <p className="text-sm text-suave">Entre uma vez com a conta de administrador da loja. A senha fica guardada com proteção do Windows, só neste computador.</p>
      <Rotulo texto="Endereço do site"><input className={classeCampo} value={endereco} onChange={(e) => setEndereco(e.target.value)} placeholder="https://sualoja.com.br" /></Rotulo>
      <Rotulo texto="E-mail do administrador"><input className={classeCampo} type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" /></Rotulo>
      <Rotulo texto="Senha"><input className={classeCampo} type="password" value={senha} onChange={(e) => setSenha(e.target.value)} autoComplete="current-password" /></Rotulo>
      {erro && <p className="text-sm text-red-400" role="alert">{erro}</p>}
      <button type="submit" className={classePrimario} disabled={ocupado || !endereco || !email || !senha}>{ocupado ? "Entrando…" : "Entrar na loja"}</button>
    </form>
  );
}

// ---------------------------------------------------------------- descrição por IA
function BotaoDescricaoIA({ nome, categoria, aoEscrever }: { nome: string; categoria: string; aoEscrever: (t: string) => void }) {
  const { empresa } = useEmpresa();
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState("");
  return (
    <div>
      <button type="button" className={classeBotao} disabled={ocupado || !nome.trim()} onClick={async () => {
        setErro(""); setOcupado(true);
        try {
          const t = await pedirTexto(`Escreva a descrição de venda do produto "${nome.trim()}" (categoria: ${categoria}) para a loja online, em até 280 caracteres, em português do Brasil, elegante, sem nudez, sem linguagem vulgar, sem promessas de saúde e sem inventar medidas, prazos ou características que não foram informados. Responda só com o texto da descrição.`, empresa);
          aoEscrever(t.replace(/^["“]|["”]$/g, "").trim().slice(0, 300));
        } catch (e) { setErro(e instanceof Error ? e.message : "A IA não conseguiu agora."); }
        finally { setOcupado(false); }
      }}>{ocupado ? "Escrevendo…" : "✦ Escrever a descrição com IA"}</button>
      {erro && <p className="mt-1 text-xs text-red-400">{erro}</p>}
    </div>
  );
}

// ---------------------------------------------------------------- cadastrar
function FormCadastro({ aoTerminar }: { aoTerminar: (texto?: string) => void }) {
  const ponte = ponteDesktop()!.loja!;
  const catalogo = useCatalogo();
  const [nome, setNome] = useState("");
  const [categoria, setCategoria] = useState(CATEGORIAS[0]![0]);
  const [categoriaManual, setCategoriaManual] = useState(false);
  const [preco, setPreco] = useState("");
  const [custo, setCusto] = useState("");
  const [estoque, setEstoque] = useState("");
  const [descricao, setDescricao] = useState("");
  const [foto, setFoto] = useState<{ base64: string; previa: string } | null>(null);
  const [mostrar, setMostrar] = useState(true);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState("");

  const centavos = centavosDe(preco);
  const custoC = centavosDe(custo);
  const margem = centavos != null && custoC != null && !Number.isNaN(centavos) && !Number.isNaN(custoC) && centavos > 0 ? Math.round(((centavos - custoC) / centavos) * 100) : null;

  async function cadastrar() {
    setErro("");
    if (!nome.trim()) return setErro("Informe o nome do produto.");
    if (preco.trim() && (centavos == null || Number.isNaN(centavos) || centavos <= 0)) return setErro("Confira o preço (ex.: 29,90).");
    if (custo.trim() && (custoC == null || Number.isNaN(custoC) || custoC < 0)) return setErro("Confira o preço de custo (ex.: 15,00).");
    if (mostrar && centavos == null) return setErro("Para mostrar no site, informe o preço.");
    const qtd = estoque.trim() ? parseInt(estoque, 10) : null;
    if (estoque.trim() && (qtd == null || Number.isNaN(qtd) || qtd < 0)) return setErro("Confira o estoque (número inteiro).");
    try {
      let imagemUrl: string | null = null;
      if (foto) { setOcupado("Enviando a foto…"); imagemUrl = await chamar(ponte.foto(foto.base64)); }
      setOcupado("Cadastrando…");
      await chamar(ponte.salvar(corpoDoNovo({ nome, categoria, descricao, precoCentavos: centavos == null || Number.isNaN(centavos) ? null : centavos, estoque: qtd, imagemUrl, ativo: mostrar, custoCentavos: custoC == null || Number.isNaN(custoC) ? null : custoC })));
      void catalogo.garantir(0);
      aoTerminar(`Pronto: ${nome.trim()} cadastrado${centavos ? ` por ${dinheiro(centavos)}` : ""} e ${mostrar ? "já aparece no site" : "ficou escondido do site até você mostrar"}.`);
    } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui cadastrar."); setOcupado(""); }
  }

  return (
    <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void cadastrar(); }}>
      <Rotulo texto="Nome do produto"><input className={classeCampo} value={nome} autoFocus onChange={(e) => { setNome(e.target.value); if (!categoriaManual) setCategoria(categoriaProvavel(e.target.value)); }} maxLength={120} /></Rotulo>
      <Rotulo texto="Categoria">
        <select className={classeCampo} value={categoria} onChange={(e) => { setCategoria(e.target.value); setCategoriaManual(true); }}>
          {CATEGORIAS.map(([id, rot]) => <option key={id} value={id}>{rot}</option>)}
        </select>
      </Rotulo>
      <div className="grid grid-cols-3 gap-3">
        <Rotulo texto="Preço de venda"><input className={classeCampo} inputMode="decimal" value={preco} onChange={(e) => setPreco(e.target.value)} placeholder="29,90" /></Rotulo>
        <Rotulo texto="Custo (só você vê)"><input className={classeCampo} inputMode="decimal" value={custo} onChange={(e) => setCusto(e.target.value)} placeholder="15,00" /></Rotulo>
        <Rotulo texto="Estoque"><input className={classeCampo} inputMode="numeric" value={estoque} onChange={(e) => setEstoque(e.target.value)} placeholder="vazio = não controla" /></Rotulo>
      </div>
      {margem != null && <p className={`text-xs ${margem <= 0 ? "text-red-400" : "text-suave"}`}>Margem: {margem}% ({dinheiro((centavos ?? 0) - (custoC ?? 0))} de lucro por unidade).</p>}
      <CampoDeFoto foto={foto} aoMudar={setFoto} />
      <Rotulo texto="Texto que aparece no site"><textarea className={classeCampo} rows={3} maxLength={300} value={descricao} onChange={(e) => setDescricao(e.target.value)} /></Rotulo>
      <BotaoDescricaoIA nome={nome} categoria={CATEGORIAS.find(([id]) => id === categoria)?.[1] ?? ""} aoEscrever={setDescricao} />
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={mostrar} onChange={(e) => setMostrar(e.target.checked)} /> Já mostrar no site</label>
      {erro && <p className="text-sm text-red-400" role="alert">{erro}</p>}
      <div className="flex gap-3 pt-1">
        <button type="submit" className={classePrimario} disabled={!!ocupado}>{ocupado || "Cadastrar produto"}</button>
        <button type="button" className={classeBotao} disabled={!!ocupado} onClick={() => aoTerminar()}>Cancelar</button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- atualizar
type Bruto = Record<string, unknown>;
const numeroOuNull = (v: unknown) => (typeof v === "number" ? v : null);

function FormAtualizar({ aoTerminar, aoSair }: { aoTerminar: (texto?: string) => void; aoSair: () => void }) {
  const ponte = ponteDesktop()!.loja!;
  const [lista, setLista] = useState<Bruto[] | null>(null);
  const [erro, setErro] = useState("");
  const [busca, setBusca] = useState("");
  const [escolhido, setEscolhido] = useState<Bruto | null>(null);

  useEffect(() => {
    chamar(ponte.produtos()).then(setLista).catch((e) => setErro(e instanceof Error ? e.message : "Não consegui ler os produtos."));
  }, [ponte]);

  if (escolhido) return <FormEdicao bruto={escolhido} aoVoltar={() => setEscolhido(null)} aoTerminar={aoTerminar} />;
  const filtro = busca.trim().toLowerCase();
  const visiveis = (lista ?? []).filter((p) => !filtro || String(p.name ?? "").toLowerCase().includes(filtro));
  return (
    <div className="space-y-3">
      <input className={classeCampo} placeholder="Buscar produto…" value={busca} autoFocus onChange={(e) => setBusca(e.target.value)} />
      {erro && <p className="text-sm text-red-400" role="alert">{erro} <button type="button" className="underline" onClick={aoSair}>Entrar de novo</button></p>}
      {!lista && !erro && <p className="text-sm text-suave">Buscando os produtos da loja…</p>}
      <ul className="divide-y divide-borda rounded-lg border border-borda">
        {visiveis.map((p) => (
          <li key={String(p.id)}>
            <button type="button" className="flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-fundo" onClick={() => setEscolhido(p)}>
              {typeof p.imageUrl === "string" && p.imageUrl ? <img src={p.imageUrl} alt="" className="size-10 rounded object-cover" /> : <span className="size-10 rounded bg-fundo" />}
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm">{String(p.name ?? "")}</span>
                <span className="block text-xs text-suave">{dinheiro(numeroOuNull(p.priceCents))} · estoque {numeroOuNull(p.stockQuantity) ?? "—"}{p.active === false ? " · escondido" : ""}</span>
              </span>
            </button>
          </li>
        ))}
        {lista && !visiveis.length && <li className="px-3 py-3 text-sm text-suave">Nenhum produto encontrado.</li>}
      </ul>
      <button type="button" className={classeBotao} onClick={() => aoTerminar()}>Cancelar</button>
    </div>
  );
}

function FormEdicao({ bruto, aoVoltar, aoTerminar }: { bruto: Bruto; aoVoltar: () => void; aoTerminar: (texto?: string) => void }) {
  const ponte = ponteDesktop()!.loja!;
  const catalogo = useCatalogo();
  const inicial = {
    nome: String(bruto.name ?? ""), descricao: String(bruto.description ?? ""),
    preco: numeroOuNull(bruto.priceCents) != null ? ((bruto.priceCents as number) / 100).toFixed(2).replace(".", ",") : "",
    custo: numeroOuNull(bruto.costCents) != null ? ((bruto.costCents as number) / 100).toFixed(2).replace(".", ",") : "",
    estoque: numeroOuNull(bruto.stockQuantity) != null ? String(bruto.stockQuantity) : "",
    ativo: bruto.active !== false
  };
  const temCusto = "costCents" in bruto;
  const [nome, setNome] = useState(inicial.nome);
  const [descricao, setDescricao] = useState(inicial.descricao);
  const [preco, setPreco] = useState(inicial.preco);
  const [custo, setCusto] = useState(inicial.custo);
  const [estoque, setEstoque] = useState(inicial.estoque);
  const [ativo, setAtivo] = useState(inicial.ativo);
  const [foto, setFoto] = useState<{ base64: string; previa: string } | null>(null);
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState("");

  async function salvar() {
    setErro("");
    const m: Mudancas = {};
    const feitas: string[] = [];
    if (nome.trim() !== inicial.nome) { if (!nome.trim()) return setErro("O nome não pode ficar vazio."); m.nome = nome; feitas.push("nome"); }
    if (descricao.trim() !== inicial.descricao.trim()) { m.descricao = descricao; feitas.push("descrição"); }
    if (preco.trim() !== inicial.preco) {
      const c = centavosDe(preco);
      if (c == null || Number.isNaN(c) || c <= 0) return setErro("Confira o preço (ex.: 29,90).");
      m.precoCentavos = c; feitas.push("preço");
    }
    if (custo.trim() !== inicial.custo) {
      const c = centavosDe(custo);
      if (c == null || Number.isNaN(c) || c < 0) return setErro("Confira o custo (ex.: 15,00).");
      if (!temCusto) return setErro("O site ainda não tem o campo de custo. Deixe o custo em branco por enquanto.");
      m.custoCentavos = c; feitas.push("custo");
    }
    if (ativo !== inicial.ativo) { m.ativo = ativo; feitas.push(ativo ? "voltou a aparecer no site" : "escondido do site"); }
    let novoEstoque: number | null = null;
    if (estoque.trim() !== inicial.estoque) {
      const q = parseInt(estoque, 10);
      if (!estoque.trim() || Number.isNaN(q) || q < 0) return setErro("Confira o estoque (número inteiro, 0 ou mais).");
      novoEstoque = q; feitas.push("estoque");
    }
    if (foto) feitas.push("foto");
    if (!feitas.length) return setErro("Você ainda não mudou nada.");
    try {
      if (foto) { setOcupado("Enviando a foto…"); m.imagemUrl = await chamar(ponte.foto(foto.base64)); }
      if (Object.keys(m).length) {
        setOcupado("Salvando…");
        const { corpo } = corpoDeAtualizacao(bruto, m);
        if (!corpo) throw new Error("Não consegui ler todos os dados desse produto. Faça essa mudança pelo painel do site.");
        await chamar(ponte.salvar(corpo));
      }
      if (novoEstoque != null) { setOcupado("Atualizando o estoque…"); await chamar(ponte.estoque(bruto.id as number, novoEstoque)); }
      void catalogo.garantir(0);
      aoTerminar(`Pronto: ${nome.trim() || inicial.nome} atualizado no site (${feitas.join(", ")}).`);
    } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui salvar."); setOcupado(""); }
  }

  return (
    <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void salvar(); }}>
      <button type="button" className="text-xs text-suave underline" onClick={aoVoltar}>← Escolher outro produto</button>
      <Rotulo texto="Nome"><input className={classeCampo} value={nome} onChange={(e) => setNome(e.target.value)} maxLength={120} /></Rotulo>
      <div className="grid grid-cols-3 gap-3">
        <Rotulo texto="Valor (preço)"><input className={classeCampo} inputMode="decimal" value={preco} onChange={(e) => setPreco(e.target.value)} /></Rotulo>
        <Rotulo texto="Custo (só você vê)"><input className={classeCampo} inputMode="decimal" value={custo} onChange={(e) => setCusto(e.target.value)} disabled={!temCusto} placeholder={temCusto ? "" : "indisponível"} /></Rotulo>
        <Rotulo texto="Estoque"><input className={classeCampo} inputMode="numeric" value={estoque} onChange={(e) => setEstoque(e.target.value)} /></Rotulo>
      </div>
      {bruto.compareAtCents != null && <p className="text-xs text-suave">Este produto está em promoção. Mudar o valor encerra a promoção.</p>}
      <CampoDeFoto foto={foto} aoMudar={setFoto} atual={typeof bruto.imageUrl === "string" ? bruto.imageUrl : null} />
      <Rotulo texto="Descrição"><textarea className={classeCampo} rows={3} maxLength={300} value={descricao} onChange={(e) => setDescricao(e.target.value)} /></Rotulo>
      <BotaoDescricaoIA nome={nome} categoria={String(bruto.category ?? "")} aoEscrever={setDescricao} />
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} /> Aparece no site</label>
      {erro && <p className="text-sm text-red-400" role="alert">{erro}</p>}
      <div className="flex gap-3 pt-1">
        <button type="submit" className={classePrimario} disabled={!!ocupado}>{ocupado || "Salvar alterações"}</button>
        <button type="button" className={classeBotao} disabled={!!ocupado} onClick={() => aoTerminar()}>Cancelar</button>
      </div>
    </form>
  );
}
