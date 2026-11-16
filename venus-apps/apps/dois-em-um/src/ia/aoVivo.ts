// Respostas "ao vivo" da Vênus web, como no celular: cotação (AwesomeAPI), previsão (Open-Meteo)
// e pesquisa na internet (rota /chat do servidor: Tavily + Gemini com busca do Google).
// Sem imports relativos para poder ser testado direto pelo node.

export const semAcento = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

export interface Resposta { texto: string; fontes?: { titulo: string; url: string }[] }
type Busca = (url: string, init?: RequestInit) => Promise<Response>;

// ------------------------------------------------------------------ cotação
const MOEDAS: [string, string, string][] = [
  ["dolar canadense", "CAD", "dólar canadense"], ["dolar australiano", "AUD", "dólar australiano"],
  ["peso argentino", "ARS", "peso argentino"], ["dolar", "USD", "dólar"], ["euro", "EUR", "euro"],
  ["bitcoin", "BTC", "bitcoin"], ["ethereum", "ETH", "ethereum"], ["libra", "GBP", "libra"], ["iene", "JPY", "iene"]
];

export function moedaDaPergunta(texto: string): { codigo: string; nome: string } | null {
  const t = semAcento(texto);
  if (!/(cotac|valor|preco|quanto (esta|ta|vale|custa)|vale hoje|fechou|em reais|\bhoje\b)/.test(t) && !/^(dolar|euro|bitcoin)\b/.test(t.trim())) return null;
  const m = MOEDAS.find(([n]) => new RegExp(`\\b${n}\\b`).test(t));
  return m ? { codigo: m[1]!, nome: m[2]! } : null;
}

export function montarCotacao(json: Record<string, { bid?: string; pctChange?: string }>, codigo: string, nome: string): string {
  const d = json[`${codigo}BRL`];
  const valor = Number(d?.bid);
  if (!d || !Number.isFinite(valor)) throw new Error("cotacao");
  const variacao = Number(d.pctChange ?? 0) || 0;
  const dinheiro = valor.toLocaleString("pt-BR", { style: "currency", currency: "BRL", maximumFractionDigits: valor < 1 ? 4 : 2 });
  const pct = (n: number) => Math.abs(n).toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const tendencia = variacao > 0.005 ? `, alta de ${pct(variacao)}% hoje` : variacao < -0.005 ? `, queda de ${pct(variacao)}% hoje` : ", estável hoje";
  return `O ${nome} está em ${dinheiro}${tendencia}.`;
}

export async function cotacao(moeda: { codigo: string; nome: string }, f: Busca = (u, i) => fetch(u, i)): Promise<Resposta> {
  const r = await f(`https://economia.awesomeapi.com.br/json/last/${moeda.codigo}-BRL`, { signal: AbortSignal.timeout(8_000) });
  if (!r.ok) throw new Error(`cotacao:${r.status}`);
  return { texto: montarCotacao(await r.json(), moeda.codigo, moeda.nome), fontes: [{ titulo: "AwesomeAPI", url: "https://docs.awesomeapi.com.br" }] };
}

// ------------------------------------------------------------------ previsão do tempo
export type Dia = "hoje" | "amanha" | "semana";

export function pedidoDeClima(texto: string): { dia: Dia; cidade: string } | null {
  const t = semAcento(texto);
  if (!/(vai chover|chuva|chover|previsao|tempo (hoje|amanha|esta|ta|faz)|clima|temperatura|quantos graus|faz (frio|calor)|esta (frio|calor)|guarda.?chuva)/.test(t)) return null;
  const dia: Dia = /(semana|proximos dias|7 dias)/.test(t) ? "semana" : /amanha/.test(t) ? "amanha" : "hoje";
  const c = /\b(?:em|de|para|pra|na|no)\s+([a-zà-ú][a-zà-ú' .-]{2,40}?)(?=\s+(?:hoje|amanha|amanhã|agora|essa|esta|nesta|nessa|na|no|semana)\b|[?!.,]|$)/i.exec(texto.replace(/\s+/g, " "));
  const cidade = c && !/^(hoje|amanha|amanhã|agora|semana|dia|casa|aqui)$/i.test(c[1]!.trim()) ? c[1]!.trim() : "";
  return { dia, cidade };
}

const CEU: Record<number, string> = {
  0: "céu limpo", 1: "céu quase limpo", 2: "parcialmente nublado", 3: "nublado", 45: "neblina", 48: "neblina",
  51: "garoa fraca", 53: "garoa", 55: "garoa forte", 61: "chuva fraca", 63: "chuva", 65: "chuva forte",
  66: "chuva congelante", 67: "chuva congelante", 71: "neve fraca", 73: "neve", 75: "neve forte", 80: "pancadas de chuva fracas",
  81: "pancadas de chuva", 82: "pancadas de chuva fortes", 95: "trovoadas", 96: "trovoadas com granizo", 99: "trovoadas com granizo"
};
const ceu = (c: number) => CEU[c] ?? "tempo instável";
const DIAS = ["domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado"];

interface PrevisaoJson {
  current?: { temperature_2m: number; apparent_temperature: number; weather_code: number };
  daily: { time: string[]; temperature_2m_max: number[]; temperature_2m_min: number[]; precipitation_probability_max?: number[]; weather_code: number[] };
}

export function montarPrevisao(j: PrevisaoJson, lugar: string, dia: Dia): string {
  const onde = lugar ? `Em ${lugar}` : "Aí onde você está";
  const d = j.daily;
  const linha = (i: number) => {
    const chuva = d.precipitation_probability_max?.[i];
    return { max: Math.round(d.temperature_2m_max[i]!), min: Math.round(d.temperature_2m_min[i]!), chuva: typeof chuva === "number" ? chuva : -1, cod: d.weather_code[i]! };
  };
  if (dia === "semana") {
    const itens = d.time.slice(1, 8).map((data, k) => {
      const l = linha(k + 1);
      const [a = 0, m = 1, dd = 1] = data.split("-").map(Number);
      return `${DIAS[new Date(a, m - 1, dd).getDay()]}: ${l.min} a ${l.max} graus, ${ceu(l.cod)}${l.chuva >= 40 ? `, ${l.chuva}% de chuva` : ""}`;
    });
    return `${onde}, próximos dias — ${itens.join("; ")}.`;
  }
  const i = dia === "amanha" ? 1 : 0;
  const l = linha(i);
  const chuva = l.chuva >= 0 ? `, com ${l.chuva}% de chance de chuva` : "";
  if (dia === "amanha") return `${onde}, amanhã: ${ceu(l.cod)}, máxima de ${l.max} e mínima de ${l.min} graus${chuva}.`;
  const at = j.current;
  const agora = at ? `${onde} agora faz ${Math.round(at.temperature_2m)} graus${Math.round(at.apparent_temperature) !== Math.round(at.temperature_2m) ? `, sensação de ${Math.round(at.apparent_temperature)}` : ""}, ${ceu(at.weather_code)}. ` : "";
  return `${agora}${at ? "Hoje" : `${onde} hoje`} a máxima é de ${l.max} e a mínima de ${l.min}${chuva}.`;
}

function posicaoDoAparelho(): Promise<{ latitude: number; longitude: number } | null> {
  return new Promise((ok) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) return ok(null);
    navigator.geolocation.getCurrentPosition((p) => ok({ latitude: p.coords.latitude, longitude: p.coords.longitude }), () => ok(null), { timeout: 8_000, maximumAge: 600_000 });
  });
}

export async function previsao(pedido: { dia: Dia; cidade: string }, cidadePadrao = "", f: Busca = (u, i) => fetch(u, i)): Promise<Resposta> {
  let lat: number, lon: number, nome = "";
  const cidade = pedido.cidade || cidadePadrao;
  if (cidade) {
    const g = await f(`https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name=${encodeURIComponent(cidade)}`, { signal: AbortSignal.timeout(8_000) });
    const r = ((await g.json()) as { results?: { name: string; admin1?: string; latitude: number; longitude: number }[] }).results?.[0];
    if (!r) return { texto: `Não encontrei a cidade "${cidade}". Me diga o nome de outra forma?` };
    lat = r.latitude; lon = r.longitude; nome = r.name + (r.admin1 && r.admin1 !== r.name ? `, ${r.admin1}` : "");
  } else {
    const p = await posicaoDoAparelho();
    if (!p) return { texto: "Me diga a cidade (ex.: “vai chover amanhã em Porto Alegre?”) ou libere a localização do navegador." };
    lat = p.latitude; lon = p.longitude;
  }
  const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,apparent_temperature,weather_code` +
    `&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=${pedido.dia === "semana" ? 8 : 2}`;
  const r = await f(url, { signal: AbortSignal.timeout(8_000) });
  if (!r.ok) throw new Error(`clima:${r.status}`);
  return { texto: montarPrevisao((await r.json()) as PrevisaoJson, nome, pedido.dia), fontes: [{ titulo: "Open-Meteo", url: "https://open-meteo.com" }] };
}

// ------------------------------------------------------------------ pesquisa na internet
/** Perguntas sobre algo que muda (notícias, resultados, preços, "hoje", "agora"…): vão para a busca do servidor. */
export function precisaPesquisar(texto: string): boolean {
  const t = semAcento(texto);
  return /(noticia|ultimas|resultado d[oa]|placar|quem ganhou|quem venceu|cotacao|bolsa|selic|inflacao|ipca|horario d[oa]|aberto agora|agora mesmo|em tempo real|hoje.*(jogo|noticia|preco)|quanto custa|lancamento)/.test(t);
}

export async function pesquisarNaWeb(urlApi: string, mensagens: { papel: "user" | "assistant"; texto: string }[], f: Busca = (u, i) => fetch(u, i)): Promise<Resposta> {
  const r = await f(`${urlApi}/chat`, {
    method: "POST",
    signal: AbortSignal.timeout(25_000),
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ messages: mensagens.slice(-8).map((m) => ({ role: m.papel, content: m.texto })) })
  });
  if (!r.ok) throw new Error(`pesquisa:${r.status}`);
  const d = (await r.json()) as { reply?: string; sources?: { title?: string; url: string }[] };
  if (!d.reply) throw new Error("pesquisa:vazia");
  return { texto: d.reply, fontes: (d.sources ?? []).slice(0, 4).map((s) => ({ titulo: s.title || s.url, url: s.url })) };
}

/** Tenta responder com dados ao vivo; devolve undefined se a pergunta não é desse tipo (segue para a IA normal). */
export async function responderAoVivo(texto: string, opcoes: { cidadePadrao?: string; urlApi: string; historico: { papel: "user" | "assistant"; texto: string }[] }, f?: Busca): Promise<Resposta | undefined> {
  const clima = pedidoDeClima(texto);
  if (clima) { try { return await previsao(clima, opcoes.cidadePadrao, f); } catch { /* cai para a pesquisa */ } }
  const moeda = moedaDaPergunta(texto);
  if (moeda) { try { return await cotacao(moeda, f); } catch { /* cai para a pesquisa */ } }
  if (clima || moeda || precisaPesquisar(texto)) return pesquisarNaWeb(opcoes.urlApi, opcoes.historico, f);
  return undefined;
}
