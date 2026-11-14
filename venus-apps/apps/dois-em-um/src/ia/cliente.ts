import { URL_API } from "@venus/nucleo";

export interface MensagemIA { papel: "user" | "assistant"; texto: string }

let tokenDaConta = "";
/** O Sincronizador avisa aqui quem está logado: com o Vetorial ID a IA é a mesma do celular. */
export function definirTokenIA(t: string | undefined) { tokenDaConta = t ?? ""; }

const MODELOS = ["llama-3.3-70b-versatile", "openai/gpt-oss-20b"];

async function pelaConta(sistema: string | undefined, mensagens: MensagemIA[]): Promise<string> {
  let ultimo = "";
  for (const model of MODELOS) {
    const r = await fetch(`${URL_API}/ia/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${tokenDaConta}` },
      body: JSON.stringify({ model, messages: [...(sistema ? [{ role: "system", content: sistema }] : []), ...mensagens.map((m) => ({ role: m.papel, content: m.texto }))] })
    });
    if (r.status === 401 || r.status === 409) throw new Error("conta");
    if (r.ok) {
      const j = (await r.json()) as { choices?: { message?: { content?: string } }[] };
      const t = j.choices?.[0]?.message?.content?.trim();
      if (t) return t;
      ultimo = "vazia";
      continue;
    }
    ultimo = String(r.status);
    if (r.status !== 429 && r.status < 500) break;
  }
  throw new Error(`ia:${ultimo}`);
}

async function pelaRota(sistema: string | undefined, mensagens: MensagemIA[]): Promise<string> {
  const r = await fetch(`${URL_API}/v1/chat`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ sistema: sistema?.slice(0, 3900) || undefined, mensagens })
  });
  if (!r.ok) {
    let codigo = "";
    try { codigo = String(((await r.json()) as { erro?: { codigo?: string } }).erro?.codigo ?? "").slice(0, 40); } catch { /* sem corpo */ }
    throw new Error(`rota:${r.status}${codigo ? `:${codigo}` : ""}`);
  }
  const d = (await r.json()) as { resposta?: string };
  if (!d.resposta) throw new Error("rota:vazia");
  return d.resposta;
}

/** Pergunta à Vênus. Logado: pela IA da conta (a do celular); se falhar (ou sem conta), pela rota pública. */
export async function perguntarIA(sistema: string | undefined, mensagens: MensagemIA[]): Promise<string> {
  const recentes = mensagens.slice(-20);
  if (tokenDaConta) {
    try { return await pelaConta(sistema, recentes); } catch { /* tenta a outra */ }
  }
  try {
    return await pelaRota(sistema, recentes);
  } catch (e) {
    const detalhe = e instanceof Error ? e.message : "";
    throw new Error(/rota:(401|403)/.test(detalhe) ? "Entre com o seu Vetorial ID (em Ajustes) para a Vênus responder." : navigator.onLine === false ? "Sem internet." : `Não consegui falar com o servidor agora${detalhe ? ` (${detalhe})` : ""}. Tente de novo em instantes.`);
  }
}
