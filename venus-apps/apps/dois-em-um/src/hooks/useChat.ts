import { useCallback } from "react";
import { perguntarIA } from "../ia/cliente";
import { useMarketing } from "../empresa/MarketingProvider";
import { contextoDeMarketing } from "../empresa/marketing";
import type { Mensagem } from "../tipos";
import { useConversas } from "../sessao/ConversasProvider";
import { useId } from "../id/IdProvider";
import { responderComandoDeConta } from "../ajustes/executarConta";
import { useEmpresa } from "../empresa/EmpresaProvider";
import { contextoDaEmpresa } from "../empresa/logica";
import { usePreferencias } from "../ajustes/Preferencias";
import { falar } from "../voz/useVoz";
import { ponteDesktop } from "../desktop/ponte";
import { abrirNoApp } from "../id/Avatar";

const novoId = () => crypto.randomUUID();

/** Chat da conversa ATIVA. Cada resposta volta para a conversa que a pediu, mesmo que o usuário troque de conversa. */
export function useChat() {
  const { ativa, anexar, estaEnviando, marcarEnviando } = useConversas();
  const enviando = estaEnviando(ativa.id);
  const conta = useId();
  const { empresa } = useEmpresa();
  const mk = useMarketing();
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
        // no Windows, "abrir calculadora", "aumentar volume", "bloquear a tela"… a Vênus faz no próprio computador
        const pc = await ponteDesktop()?.comando(limpo).catch(() => undefined);
        if (pc?.tratado) {
          const t = pc.texto ?? "Feito.";
          anexar(id, resposta(t));
          if (prefs.falarRespostas) falar(t);
          return;
        }
        const texto2 = await perguntarIA(
          [contextoDaEmpresa(empresa), contextoDeMarketing(mk.todasAsIdeias, mk.postsDoCelular, mk.storiesDoCelular, Date.now(), mk.resumoInstagram)].filter(Boolean).join("\n\n") || undefined,
          historico.map((m) => ({ papel: m.autor === "eu" ? ("user" as const) : ("assistant" as const), texto: m.texto }))
        );
        anexar(id, resposta(texto2));
        if (prefs.falarRespostas) falar(texto2);
      } catch (e) {
        anexar(id, resposta(e instanceof Error && e.message ? e.message : "Não consegui falar com o servidor agora. Tente de novo em instantes."));
      } finally {
        marcarEnviando(id, false);
      }
    },
    [ativa, anexar, estaEnviando, marcarEnviando, conta, empresa, mk.todasAsIdeias, mk.postsDoCelular, mk.storiesDoCelular, mk.resumoInstagram, prefs.falarRespostas]
  );

  return { mensagens: ativa.mensagens, enviando, enviar };
}
