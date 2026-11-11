import { useCallback } from "react";
import type { Automacao, EventoRobo } from "../tipos";
import { usePersistido } from "./usePersistido";

const AUTOMACOES_INICIAIS: Automacao[] = [
  { id: "whatsapp", nome: "Responder clientes", descricao: "Atende mensagens do WhatsApp com a Vênus.", ativa: true },
  { id: "estoque", nome: "Alerta de estoque", descricao: "Avisa quando um produto está acabando.", ativa: true },
  { id: "cobranca", nome: "Cobrança por link", descricao: "Envia o link e dá baixa quando pagar.", ativa: false },
  { id: "instagram", nome: "Posts no Instagram", descricao: "Publica o catálogo no automático.", ativa: false }
];

export function useRobo() {
  const [ligado, setLigado] = usePersistido("dois-em-um:robo:ligado", true);
  const [automacoes, setAutomacoes] = usePersistido<Automacao[]>("dois-em-um:robo:automacoes", AUTOMACOES_INICIAIS);
  const [eventos, setEventos] = usePersistido<EventoRobo[]>("dois-em-um:robo:eventos", []);

  const anotar = useCallback(
    (texto: string) =>
      setEventos((atual) => [{ id: crypto.randomUUID(), texto, quando: Date.now() }, ...atual].slice(0, 30)),
    [setEventos]
  );

  const alternarRobo = useCallback(() => {
    setLigado((v) => {
      anotar(v ? "Robô desligado" : "Robô ligado");
      return !v;
    });
  }, [setLigado, anotar]);

  const alternarAutomacao = useCallback(
    (id: string) =>
      setAutomacoes((lista) =>
        lista.map((a) => {
          if (a.id !== id) return a;
          anotar(`${a.nome}: ${a.ativa ? "desativada" : "ativada"}`);
          return { ...a, ativa: !a.ativa };
        })
      ),
    [setAutomacoes, anotar]
  );

  return { ligado, automacoes, eventos, alternarRobo, alternarAutomacao };
}
