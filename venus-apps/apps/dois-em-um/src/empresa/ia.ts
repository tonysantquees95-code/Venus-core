import { URL_API } from "@venus/nucleo";
import { contextoDaEmpresa, type Empresa } from "./logica";

/** Pede um texto à Vênus (mesmo servidor do chat). Lança erro com mensagem curta se não der. */
export async function pedirTexto(pedido: string, empresa: Empresa): Promise<string> {
  let r: Response;
  try {
    r = await fetch(`${URL_API}/v1/chat`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ sistema: contextoDaEmpresa(empresa) || undefined, mensagens: [{ papel: "user", texto: pedido }] })
    });
  } catch {
    throw new Error("Sem conexão com o servidor da Vênus.");
  }
  if (!r.ok) throw new Error("A Vênus não conseguiu responder agora. Tente de novo em instantes.");
  const d = (await r.json()) as { resposta?: string };
  if (!d.resposta) throw new Error("A resposta veio vazia. Tente de novo.");
  return d.resposta;
}
