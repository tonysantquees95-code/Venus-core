import { useCallback } from "react";
import { URL_API } from "@venus/nucleo";
import type { Mensagem } from "../tipos";
import { useConversas } from "../sessao/ConversasProvider";
import { useId } from "../id/IdProvider";
import { responderComandoDeConta } from "../ajustes/executarConta";
import { useEmpresa } from "../empresa/EmpresaProvider";
import { contextoDaEmpresa } from "../empresa/logica";
import { usePreferencias } from "../ajustes/Preferencias";
import { falar } from "../voz/useVoz";
import { abrirNoApp } from "../id/Avatar";

const novoId = () => crypto.randomUUID();

/** Chat da conversa ATIVA. Cada resposta volta para a conversa que a pediu, mesmo que o usuário troque de conversa. */
export function useChat() {
  const { ativa, anexar, estaEnviando, marcarEnviando } = useConversas();
  const enviando = estaEnviando(ativa.id);
  const conta = useId();
  const { empresa } = useEmpresa();
  const { prefs } = usePreferencias();

  const enviar = useCallback(
    async (texto: string) => {
      const limpo = texto.trim();
      const id = ativa.id;
      if (!limpo || estaEnviando(id)) return;
      const minha: Mensagem = { id: novoId(), autor: "eu", texto: limpo, quando: Date.now() };
      const historico = [...ativa.mensagens, minha];
      anexar(id, minha);
      marcarEnviando(id, true);
      const resposta = (texto: string): Mensagem => ({ id: novoId(), autor: "venus", texto, quando: Date.now() });
      try {
        // comandos de conta ("status da minha conta", "sair da minha conta aqui"…) a Vênus resolve sozinha
        const local = await responderComandoDeConta(limpo, conta);
        if (local) {
          anexar(id, resposta(local.texto));
          if (prefs.falarRespostas) falar(local.texto);
          if (local.abrir) abrirNoApp(local.abrir);
          return;
        }
        const r = await fetch(`${URL_API}/v1/chat`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            sistema: contextoDaEmpresa(empresa) || undefined,
            mensagens: historico.slice(-20).map((m) => ({ papel: m.autor === "eu" ? "user" : "assistant", texto: m.texto }))
          })
        });
        if (!r.ok) throw new Error(String(r.status));
        const dados = (await r.json()) as { resposta?: string };
        anexar(id, resposta(dados.resposta ?? "(sem resposta)"));
        if (prefs.falarRespostas && dados.resposta) falar(dados.resposta);
      } catch {
        anexar(id, resposta("Não consegui falar com o servidor agora. Tente de novo em instantes."));
      } finally {
        marcarEnviando(id, false);
      }
    },
    [ativa, anexar, estaEnviando, marcarEnviando, conta, empresa, prefs.falarRespostas]
  );

  return { mensagens: ativa.mensagens, enviando, enviar };
}
