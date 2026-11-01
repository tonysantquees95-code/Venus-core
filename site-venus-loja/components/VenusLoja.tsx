import React, { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import superjson from "superjson";
import { Mic, MicOff, Send, Volume2, VolumeX, Check, X, RefreshCw } from "lucide-react";
import { VenusOrbe, type VenusOrbeEstado } from "./VenusOrbe";
import { venusLoja, type VLAlteracao, type VLAcao, type VLPedido, type VLProduto } from "../helpers/venusLoja";
import styles from "./VenusLoja.module.css";

/* ------------------------------------------------------------------ dados do painel */

type Dados = { pedidos: VLPedido[]; produtos: VLProduto[] };

async function pedirAoPainel<T>(caminho: string, corpo?: unknown): Promise<T> {
  const r = await fetch(caminho, {
    method: corpo === undefined ? "GET" : "POST",
    headers: { "Content-Type": "application/json" },
    body: corpo === undefined ? undefined : superjson.stringify(corpo),
  });
  const texto = await r.text();
  let dados: unknown = null;
  try {
    dados = superjson.parse(texto);
  } catch {
    dados = null;
  }
  if (!r.ok) {
    const erro = (dados as { error?: string; message?: string } | null)?.error ?? (dados as { message?: string } | null)?.message;
    if (r.status === 401 || r.status === 403) throw new Error("Sua sessão expirou. Entre de novo no painel.");
    throw new Error(erro || `O site respondeu com erro ${r.status}.`);
  }
  return dados as T;
}

const data = (v: unknown): Date | null => (v instanceof Date ? v : v ? new Date(String(v)) : null);

async function carregarLoja(): Promise<Dados> {
  const [o, p] = await Promise.all([
    pedirAoPainel<{ orders: Record<string, unknown>[] }>("/_api/admin/orders"),
    pedirAoPainel<{ products: Record<string, unknown>[] }>("/_api/admin/products"),
  ]);
  const pedidos: VLPedido[] = (o?.orders ?? []).map((x) => ({
    id: Number(x.id),
    status: String(x.status ?? ""),
    totalCents: Number(x.totalCents ?? 0),
    customerName: String(x.customerName ?? ""),
    customerPhone: String(x.customerPhone ?? ""),
    deliveryMethod: String(x.deliveryMethod ?? ""),
    createdAt: data(x.createdAt) ?? new Date(0),
    paidAt: data(x.paidAt),
    trackingCode: x.trackingCode ? String(x.trackingCode) : null,
    items: Array.isArray(x.items)
      ? (x.items as Record<string, unknown>[]).map((i) => ({
          name: String(i.name ?? ""),
          quantity: Number(i.quantity ?? 1),
          unitPriceCents: Number(i.unitPriceCents ?? 0),
        }))
      : [],
  }));
  const produtos: VLProduto[] = (p?.products ?? []).map((x) => ({
    id: Number(x.id),
    name: String(x.name ?? ""),
    active: x.active !== false,
    stockQuantity: x.stockQuantity === null || x.stockQuantity === undefined ? null : Number(x.stockQuantity),
    priceCents: x.priceCents === null || x.priceCents === undefined ? null : Number(x.priceCents),
    compareAtCents: x.compareAtCents === null || x.compareAtCents === undefined ? null : Number(x.compareAtCents),
    bruto: x,
  }));
  return { pedidos, produtos };
}

async function aplicar(a: VLAlteracao): Promise<void> {
  switch (a.tipo) {
    case "estoque":
      await pedirAoPainel("/_api/admin/stock_update", { productId: a.produto.id, quantity: a.valor, mode: "set" });
      return;
    case "preco":
    case "ativar":
    case "desativar":
    case "promocao":
    case "fim_promocao": {
      // relê o produto na hora, para não desfazer outra mudança feita pelo painel
      const atual = (await carregarLoja()).produtos.find((p) => p.id === a.produto.id);
      if (!atual) throw new Error(`Não achei mais ${a.produto.name} na loja.`);
      const corpo = venusLoja.corpoDoProduto(atual.bruto, a.tipo, a.tipo === "preco" || a.tipo === "promocao" ? a.valor : undefined);
      if (!corpo) throw new Error(`Não consegui ler todos os dados de ${a.produto.name}. Faça pelo painel.`);
      await pedirAoPainel("/_api/admin/product_save", corpo);
      return;
    }
    case "entregue":
      await pedirAoPainel("/_api/admin/order_update", { id: a.pedido.id, status: "delivered", trackingCode: a.pedido.trackingCode });
      return;
    case "status":
      await pedirAoPainel("/_api/admin/order_update", { id: a.pedido.id, status: a.status, trackingCode: a.rastreio ?? a.pedido.trackingCode });
      return;
  }
}

/* ------------------------------------------------------------------ voz do navegador */

type Reconhecedor = {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
};

function criarReconhecedor(): Reconhecedor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: new () => Reconhecedor; webkitSpeechRecognition?: new () => Reconhecedor };
  const Classe = w.SpeechRecognition ?? w.webkitSpeechRecognition;
  return Classe ? new Classe() : null;
}

function vozPortugues(): SpeechSynthesisVoice | null {
  const vozes = window.speechSynthesis?.getVoices() ?? [];
  return (
    vozes.find((v) => v.lang === "pt-BR" && /luciana|feminin|female|francisca|vitoria/i.test(v.name)) ??
    vozes.find((v) => v.lang === "pt-BR") ??
    vozes.find((v) => v.lang.startsWith("pt")) ??
    null
  );
}

const semEmoji = (t: string) => t.replace(/[\u{1F000}-\u{1FFFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, "").replace(/R\$\s?/g, "").trim();

/* ------------------------------------------------------------------ tela */

type Mensagem = {
  id: number;
  de: "eu" | "venus";
  texto: string;
  acoes?: VLAcao[];
  alteracao?: VLAlteracao;
  situacao?: "esperando" | "salvando" | "feito" | "cancelado" | "erro";
};

const ATALHOS = ["Novidades", "Vendas de hoje", "Pedidos para separar", "Como está o estoque", "O que está acabando", "Retiradas para agendar", "Ajuda"];
const CHAVE_VOZ = "venus-loja-falar";

/**
 * A Vênus da Loja: atendente interna para quem cuida da loja.
 * Fala e escreve; consulta e muda estoque, preços, produtos e pedidos pelas mesmas funções do painel.
 * Toda mudança espera confirmação (botão ou "sim").
 */
export const VenusLoja = () => {
  const queryClient = useQueryClient();
  const [mensagens, setMensagens] = useState<Mensagem[]>([]);
  const [texto, setTexto] = useState("");
  const [estado, setEstado] = useState<VenusOrbeEstado>("ocioso");
  const [ouvindo, setOuvindo] = useState(false);
  const [falar, setFalar] = useState(true);
  const [temMicrofone, setTemMicrofone] = useState(false);
  const proximoId = useRef(1);
  const pendente = useRef<{ id: number; alteracao: VLAlteracao } | null>(null);
  const reconhecedor = useRef<Reconhecedor | null>(null);
  const fimRef = useRef<HTMLDivElement>(null);
  const falandoTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    setTemMicrofone(criarReconhecedor() !== null);
    try {
      setFalar(localStorage.getItem(CHAVE_VOZ) !== "0");
    } catch {
      // sem armazenamento: fala ligada
    }
    // as vozes do iPhone carregam depois
    window.speechSynthesis?.getVoices();
  }, []);

  useEffect(() => {
    fimRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [mensagens]);

  const dizer = useCallback(
    (t: string) => {
      if (falandoTimer.current) clearTimeout(falandoTimer.current);
      setEstado("falando");
      falandoTimer.current = setTimeout(() => setEstado("ocioso"), Math.min(9000, Math.max(1500, t.length * 55)));
      if (!falar || !window.speechSynthesis) return;
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(semEmoji(t));
      u.lang = "pt-BR";
      const v = vozPortugues();
      if (v) u.voice = v;
      u.onend = () => setEstado("ocioso");
      window.speechSynthesis.speak(u);
    },
    [falar],
  );

  const adicionar = useCallback((m: Omit<Mensagem, "id">) => {
    const id = proximoId.current++;
    setMensagens((lista) => [...lista.slice(-60), { ...m, id }]);
    return id;
  }, []);

  const mudarSituacao = (id: number, situacao: Mensagem["situacao"]) =>
    setMensagens((lista) => lista.map((m) => (m.id === id ? { ...m, situacao } : m)));

  /** Dados frescos (no máximo 20 s de idade) antes de responder. */
  const dados = useCallback(
    () => queryClient.fetchQuery({ queryKey: ["venus-loja", "dados"], queryFn: carregarLoja, staleTime: 20_000 }),
    [queryClient],
  );

  // Ao abrir: o que tem para fazer hoje.
  const abriu = useRef(false);
  useEffect(() => {
    if (abriu.current) return;
    abriu.current = true;
    setEstado("pensando");
    dados()
      .then((d) => {
        const r = venusLoja.novidades(d.pedidos, d.produtos);
        adicionar({ de: "venus", texto: `Oi! ${r.texto} Pode falar ou escrever o que precisa.` });
        setEstado("ocioso");
      })
      .catch((e: Error) => {
        adicionar({ de: "venus", texto: `Não consegui abrir a loja: ${e.message}` });
        setEstado("ocioso");
      });
  }, [adicionar, dados]);

  const confirmar = async (id: number, alteracao: VLAlteracao) => {
    pendente.current = null;
    mudarSituacao(id, "salvando");
    setEstado("pensando");
    try {
      await aplicar(alteracao);
      mudarSituacao(id, "feito");
      queryClient.invalidateQueries({ queryKey: ["venus-loja", "dados"] });
      queryClient.invalidateQueries({ queryKey: ["admin"] });
      const t = venusLoja.confirmado(alteracao);
      const aviso = venusLoja.linkAviso(alteracao, window.location.origin);
      adicionar({ de: "venus", texto: t, acoes: aviso ? [{ rotulo: "Avisar a cliente no WhatsApp", url: aviso }] : undefined });
      dizer(t);
    } catch (e) {
      mudarSituacao(id, "erro");
      const t = `Não consegui salvar: ${e instanceof Error ? e.message : "erro desconhecido"} Nada foi mudado.`;
      adicionar({ de: "venus", texto: t });
      dizer(t);
    }
  };

  const cancelar = (id: number, avisar = true) => {
    if (pendente.current?.id === id) pendente.current = null;
    mudarSituacao(id, "cancelado");
    if (avisar) {
      const t = "Tudo bem, não mudei nada.";
      adicionar({ de: "venus", texto: t });
      dizer(t);
    }
  };

  const enviar = async (bruto: string) => {
    const frase = bruto.trim().slice(0, 300);
    if (!frase || estado === "pensando") return;
    // no iPhone a fala só funciona se começar a partir de um toque: "destrava" agora
    if (falar && window.speechSynthesis && !window.speechSynthesis.speaking) {
      window.speechSynthesis.speak(new SpeechSynthesisUtterance(""));
    }
    setTexto("");
    adicionar({ de: "eu", texto: frase });

    // resposta a uma confirmação pendente
    const esperando = pendente.current;
    if (esperando !== null) {
      const sn = venusLoja.simOuNao(frase);
      if (sn === true) return confirmar(esperando.id, esperando.alteracao);
      if (sn === false) return cancelar(esperando.id);
      cancelar(esperando.id, false); // falou outra coisa: deixa a mudança de lado
    }

    const intencao = venusLoja.interpretar(frase);
    if (!intencao) {
      adicionar({ de: "venus", texto: venusLoja.NAO_ENTENDI });
      dizer(venusLoja.NAO_ENTENDI);
      return;
    }
    setEstado("pensando");
    try {
      const d = await dados();
      const r = venusLoja.responder(intencao, d.pedidos, d.produtos);
      const id = adicionar({ de: "venus", texto: r.texto, acoes: r.acoes, alteracao: r.alteracao, situacao: r.alteracao ? "esperando" : undefined });
      if (r.alteracao) pendente.current = { id, alteracao: r.alteracao };
      dizer(r.texto);
    } catch (e) {
      const t = `Não consegui consultar a loja: ${e instanceof Error ? e.message : "erro desconhecido"}`;
      adicionar({ de: "venus", texto: t });
      dizer(t);
    }
  };

  const ouvir = () => {
    if (ouvindo) {
      reconhecedor.current?.stop();
      return;
    }
    const r = criarReconhecedor();
    if (!r) return;
    window.speechSynthesis?.cancel();
    r.lang = "pt-BR";
    r.interimResults = false;
    r.continuous = false;
    r.onresult = (e) => {
      const fala = e.results[0]?.[0]?.transcript ?? "";
      if (fala) void enviar(fala);
    };
    r.onerror = (e) => {
      if (e.error === "not-allowed" || e.error === "service-not-allowed") {
        adicionar({ de: "venus", texto: "O Safari não liberou o microfone. Toque em aA na barra de endereço, Ajustes do Site, e permita o Microfone." });
      }
    };
    r.onend = () => {
      setOuvindo(false);
      setEstado((s) => (s === "falando" ? s : "ocioso"));
    };
    reconhecedor.current = r;
    setOuvindo(true);
    setEstado("falando");
    try {
      r.start();
    } catch {
      setOuvindo(false);
    }
  };

  const alternarFala = () => {
    const novo = !falar;
    setFalar(novo);
    if (!novo) window.speechSynthesis?.cancel();
    try {
      localStorage.setItem(CHAVE_VOZ, novo ? "1" : "0");
    } catch {
      // ignora
    }
  };

  return (
    <section className={styles.raiz} aria-label="Vênus da Loja">
      <header className={styles.topo}>
        <button type="button" className={styles.orbe} onClick={ouvir} aria-label={ouvindo ? "Parar de ouvir" : "Falar com a Vênus"} disabled={!temMicrofone}>
          <VenusOrbe tamanho={150} estado={ouvindo ? "falando" : estado} />
        </button>
        <p className={styles.status} aria-live="polite">
          {ouvindo ? "Ouvindo…" : estado === "pensando" ? "Consultando a loja…" : temMicrofone ? "Toque no orbe e fale" : "Escreva sua pergunta abaixo"}
        </p>
        <div className={styles.botoesTopo}>
          <button type="button" className={styles.icone} onClick={alternarFala} aria-label={falar ? "Desligar a voz" : "Ligar a voz"} title={falar ? "Voz ligada" : "Voz desligada"}>
            {falar ? <Volume2 size={18} /> : <VolumeX size={18} />}
          </button>
          <button type="button" className={styles.icone} onClick={() => void enviar("novidades")} aria-label="Atualizar novidades" title="Novidades">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      <div className={styles.conversa}>
        {mensagens.map((m) =>
          m.de === "eu" ? (
            <p key={m.id} className={`${styles.balao} ${styles.eu}`}>
              {m.texto}
            </p>
          ) : (
            <div key={m.id} className={styles.bloco}>
              <p className={`${styles.balao} ${styles.venus}`}>{m.texto}</p>
              {m.alteracao ? (
                m.situacao === "esperando" ? (
                  <div className={styles.confirmacao}>
                    <button type="button" className={styles.confirmar} onClick={() => m.alteracao && void confirmar(m.id, m.alteracao)}>
                      <Check size={16} /> Confirmar
                    </button>
                    <button type="button" className={styles.cancelar} onClick={() => cancelar(m.id)}>
                      <X size={16} /> Cancelar
                    </button>
                  </div>
                ) : (
                  <span className={styles.situacao} data-situacao={m.situacao}>
                    {{ salvando: "Salvando…", feito: "Feito ✓", cancelado: "Cancelado", erro: "Não salvou", esperando: "" }[m.situacao ?? "esperando"]}
                  </span>
                )
              ) : null}
              {m.acoes?.length ? (
                <div className={styles.acoes}>
                  {m.acoes.map((a) =>
                    a.url.startsWith("/") ? (
                      <Link key={a.url} to={a.url} className={styles.acao}>
                        {a.rotulo}
                      </Link>
                    ) : (
                      <a key={a.url} href={a.url} target="_blank" rel="noopener noreferrer" className={styles.acao}>
                        {a.rotulo}
                      </a>
                    ),
                  )}
                </div>
              ) : null}
            </div>
          ),
        )}
        <div ref={fimRef} />
      </div>

      <div className={styles.atalhos}>
        {ATALHOS.map((a) => (
          <button key={a} type="button" className={styles.atalho} onClick={() => void enviar(a)}>
            {a}
          </button>
        ))}
      </div>

      <form
        className={styles.envio}
        onSubmit={(e) => {
          e.preventDefault();
          void enviar(texto);
        }}
      >
        <input
          className={styles.campo}
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          placeholder="Ex.: coloca mais 5 unidades do gel"
          maxLength={300}
          aria-label="Mensagem para a Vênus"
          enterKeyHint="send"
        />
        {temMicrofone ? (
          <button type="button" className={`${styles.botao} ${ouvindo ? styles.ativo : ""}`} onClick={ouvir} aria-label={ouvindo ? "Parar de ouvir" : "Falar"}>
            {ouvindo ? <MicOff size={18} /> : <Mic size={18} />}
          </button>
        ) : null}
        <button type="submit" className={`${styles.botao} ${styles.enviar}`} disabled={!texto.trim()} aria-label="Enviar">
          <Send size={18} />
        </button>
      </form>
    </section>
  );
};
