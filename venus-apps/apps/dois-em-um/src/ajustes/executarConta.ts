import { URL_API } from "@venus/nucleo";
import type { ApiId } from "../id/api";
import type { Sessao } from "../id/api";
import { interpretarComandoConta, textoDaAssinatura } from "../id/perfil";
import { haQuantoTempo } from "./logica";

export interface ContextoDeConta {
  api: ApiId;
  sessao: Sessao | null;
  sair: () => Promise<void>;
}
export interface Resposta {
  texto: string;
  abrir?: "login" | "ajustes";
}

/** Se a frase é um comando de conta, executa e devolve a resposta da Vênus; senão devolve null (segue para a IA). */
export async function responderComandoDeConta(texto: string, c: ContextoDeConta): Promise<Resposta | null> {
  const cmd = interpretarComandoConta(texto);
  if (!cmd) return null;
  if (!c.sessao) {
    if (cmd === "sair") return { texto: "Você não está conectada em nenhuma conta neste aparelho." };
    return { texto: cmd === "conectar" ? "Abrindo a tela de login do Vetorial ID." : "Você ainda não entrou no Vetorial ID. Abrindo o login para você.", abrir: "login" };
  }
  const t = c.sessao.token;
  try {
    if (cmd === "status") {
      const x = await c.api.conta(t);
      const l = x.licenca;
      const dias = l.emTeste ? l.diasDeTesteRestantes : l.diasPagosRestantes;
      return { texto: `Conta ${x.usuario.email}.\n${textoDaAssinatura(l)}${l.acesso && dias !== undefined && dias <= 3 ? "\nEstá acabando: renove em Ajustes › Conta." : ""}` };
    }
    if (cmd === "conectar") {
      return { texto: `Para conectar o novo aparelho, abra a Vênus nele (ou ${URL_API}/id no navegador) e entre com ${c.sessao.usuario.email} e a sua senha. Você pode ter até 3 aparelhos vinculados e 2 usando ao mesmo tempo. Quer ver os que já estão conectados? Diga "meus dispositivos".` };
    }
    if (cmd === "dispositivos") {
      const d = await c.api.dispositivos(t);
      const linhas = d.dispositivos.map((x) => `• ${x.nome}${x.atual ? " (este aparelho)" : ""} — ${x.ativo ? "ativo agora" : `visto ${haQuantoTempo(x.ultimo)}`}`);
      return { texto: `${linhas.join("\n")}\n\nPara encerrar algum, abra Ajustes › Meus dispositivos.`, abrir: undefined };
    }
    await c.sair();
    return { texto: "Pronto, saí da sua conta neste aparelho. Para voltar, diga “conectar minha conta”." };
  } catch (e) {
    return { texto: e instanceof Error ? e.message : "Não consegui consultar a conta agora." };
  }
}
