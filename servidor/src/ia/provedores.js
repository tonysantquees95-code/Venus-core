// Chamadas aos provedores. As chaves vêm de `env` (segredos do Worker) e nunca saem daqui.
import { ErroHttp } from "./roteador.js";
import { lerSegredo } from "../dev/cofre.js";

const TEMPO_PADRAO = 30_000;
const TEMPO_RACIOCINIO = 110_000;

/** Erro de provedor; `recuperavel` diz se vale tentar o provedor de reserva. */
class ErroProvedor extends Error {
  constructor(provedor, status, recuperavel, codigo) {
    super(`${provedor}: ${codigo ?? status}`);
    this.provedor = provedor;
    this.status = status;
    this.recuperavel = recuperavel;
  }
}

const STATUS_RECUPERAVEIS = new Set([401, 403, 404, 408, 409, 425, 429]);

async function postar(provedor, url, cabecalhos, corpo, ms) {
  let r;
  try {
    r = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", ...cabecalhos },
      body: JSON.stringify(corpo),
      signal: AbortSignal.timeout(ms)
    });
  } catch {
    throw new ErroProvedor(provedor, 0, true, "rede_ou_tempo");
  }
  if (!r.ok) {
    const recuperavel = r.status >= 500 || STATUS_RECUPERAVEIS.has(r.status);
    throw new ErroProvedor(provedor, r.status, recuperavel);
  }
  try {
    return await r.json();
  } catch {
    throw new ErroProvedor(provedor, 502, true, "json_invalido");
  }
}

const paraOpenAI = (sistema, mensagens) => [
  ...(sistema ? [{ role: "system", content: sistema }] : []),
  ...mensagens.map((m) => ({ role: m.papel, content: m.texto }))
];

function extrairOpenAI(provedor, dados) {
  const texto = dados?.choices?.[0]?.message?.content;
  if (typeof texto !== "string" || !texto.trim()) throw new ErroProvedor(provedor, 502, true, "resposta_vazia");
  return { texto, uso: dados.usage ?? null, modelo: dados.model };
}

export async function groq(env, p) {
  if (!env.GROQ_API_KEY) throw new ErroProvedor("groq", 0, true, "sem_chave");
  const modelo = p.tarefa === "rapida" ? env.MODELO_GROQ_RAPIDO || "llama-3.1-8b-instant" : env.MODELO_GROQ || "llama-3.3-70b-versatile";
  const dados = await postar(
    "groq",
    "https://api.groq.com/openai/v1/chat/completions",
    { authorization: `Bearer ${env.GROQ_API_KEY}` },
    { model: modelo, messages: paraOpenAI(p.sistema, p.mensagens), temperature: p.temperatura, max_tokens: p.maxTokens },
    TEMPO_PADRAO
  );
  return { ...extrairOpenAI("groq", dados), modelo };
}

export async function deepseek(env, p) {
  // a chave vem do cofre do Painel Dev (cifrada) e, se não estiver lá, do segredo do Worker
  const chave = await lerSegredo(env, "DEEPSEEK_API_KEY");
  if (!chave) throw new ErroProvedor("deepseek", 0, true, "sem_chave");
  const raciocinio = p.tarefa === "logica";
  const modelo = raciocinio ? env.MODELO_DEEPSEEK_RACIOCINIO || "deepseek-reasoner" : env.MODELO_DEEPSEEK || "deepseek-chat";
  const corpo = { model: modelo, messages: paraOpenAI(p.sistema, p.mensagens), max_tokens: p.maxTokens };
  if (!raciocinio) corpo.temperature = p.temperatura; // o modelo de raciocínio ignora temperatura
  const dados = await postar(
    "deepseek",
    "https://api.deepseek.com/chat/completions",
    { authorization: `Bearer ${chave}` },
    corpo,
    raciocinio ? TEMPO_RACIOCINIO : TEMPO_PADRAO
  );
  // Só o `content` volta ao app; o encadeamento interno (reasoning_content) é descartado.
  return { ...extrairOpenAI("deepseek", dados), modelo };
}

export async function gemini(env, p) {
  if (!env.GEMINI_API_KEY) throw new ErroProvedor("gemini", 0, true, "sem_chave");
  const contents = p.mensagens.map((m, i) => {
    const parts = [{ text: m.texto }];
    if (i === p.mensagens.length - 1) {
      for (const a of p.anexos ?? []) parts.push({ inlineData: { mimeType: a.mime, data: a.dados } });
    }
    return { role: m.papel === "assistant" ? "model" : "user", parts };
  });
  const corpo = {
    contents,
    generationConfig: { temperature: p.temperatura, maxOutputTokens: p.maxTokens },
    ...(p.sistema ? { systemInstruction: { parts: [{ text: p.sistema }] } } : {})
  };
  const modelos = [env.MODELO_GEMINI || "gemini-3.8-flash", env.MODELO_GEMINI_RESERVA || "gemini-flash-lite-latest"];
  let ultimo;
  for (const modelo of modelos) {
    try {
      const dados = await postar(
        "gemini",
        `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(modelo)}:generateContent`,
        { "x-goog-api-key": env.GEMINI_API_KEY }, // chave em cabeçalho: não aparece em URL/log
        corpo,
        TEMPO_PADRAO * 2
      );
      const texto = (dados?.candidates?.[0]?.content?.parts ?? []).map((x) => x.text ?? "").join("").trim();
      if (!texto) throw new ErroProvedor("gemini", 502, true, dados?.promptFeedback?.blockReason ? "bloqueado" : "resposta_vazia");
      return { texto, uso: dados.usageMetadata ?? null, modelo };
    } catch (e) {
      ultimo = e;
      if (!(e instanceof ErroProvedor) || !e.recuperavel) break;
    }
  }
  throw ultimo;
}

export const PROVEDORES = { groq, deepseek, gemini };

/** Roda a ordem de tentativas; só cai para a reserva em falha recuperável (limite, 5xx, tempo, chave). */
export async function executar(env, ordem, p) {
  let ultimo;
  for (let i = 0; i < ordem.length; i++) {
    const nome = ordem[i];
    try {
      const r = await PROVEDORES[nome](env, p);
      return { ...r, provedor: nome, fallback: i > 0 };
    } catch (e) {
      if (!(e instanceof ErroProvedor)) throw e;
      console.error("falha de provedor", e.provedor, e.status);
      ultimo = e;
      if (!e.recuperavel) break;
    }
  }
  if (ultimo && !ultimo.recuperavel) throw new ErroHttp(502, "provedor_recusou", "O provedor recusou o pedido.");
  throw new ErroHttp(502, "provedor_indisponivel", "Nenhum provedor respondeu agora. Tente novamente em instantes.");
}
