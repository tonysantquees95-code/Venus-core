import { perguntarIA } from "../ia/cliente";
import { contextoDaEmpresa, type Empresa } from "./logica";

/** Pede um texto à Vênus (mesma IA do chat e do celular). Lança erro com mensagem curta se não der. */
export async function pedirTexto(pedido: string, empresa: Empresa): Promise<string> {
  return perguntarIA(contextoDaEmpresa(empresa) || undefined, [{ papel: "user", texto: pedido }]);
}
