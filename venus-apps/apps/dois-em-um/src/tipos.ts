export type Tela = "chat" | "robo" | "empresa" | "tema" | "ajustes";

export interface Mensagem {
  id: string;
  autor: "eu" | "venus";
  texto: string;
  quando: number;
  /** Só nas respostas da IA: por onde veio e quanto demorou (ajuda a achar lentidão). */
  detalhe?: string;
}

export interface Automacao {
  id: string;
  nome: string;
  descricao: string;
  ativa: boolean;
}

export interface EventoRobo {
  id: string;
  texto: string;
  quando: number;
}

export interface Conversa {
  id: string;
  titulo: string;
  criada: number;
  atualizada: number;
  mensagens: Mensagem[];
}
