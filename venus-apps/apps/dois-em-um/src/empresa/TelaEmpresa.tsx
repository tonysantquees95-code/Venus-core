import { useState } from "react";
import { IconeNomeado } from "../components/Icones";
import { PaginaComLateral, type ItemLateral } from "../components/PaginaComLateral";
import { DadosDaLoja, Historico, IdeiasDePost, IdeiasDeVideo, InstagramDaLoja, PrimeirosPassos, RegrasDeVenda, RespostasDoInstagram, StoriesDoDia } from "./secoes";

const SECOES: ItemLateral[] = [
  { id: "dados", rotulo: "Dados da loja", icone: <IconeNomeado nome="loja" className="size-5" />, grupo: "Minha loja" },
  { id: "regras", rotulo: "Regras de venda", icone: <IconeNomeado nome="regras" className="size-5" />, grupo: "Minha loja" },
  { id: "passos", rotulo: "Primeiros passos", icone: <IconeNomeado nome="passos" className="size-5" />, grupo: "Minha loja" },
  { id: "historico", rotulo: "Histórico de conversas", icone: <IconeNomeado nome="conversas" className="size-5" />, grupo: "Conversas" },
  { id: "ideias", rotulo: "Ideias de post", icone: <IconeNomeado nome="ideia" className="size-5" />, grupo: "Instagram" },
  { id: "videos", rotulo: "Ideias de vídeo (Reels)", icone: <IconeNomeado nome="video" className="size-5" />, grupo: "Instagram" },
  { id: "stories", rotulo: "Stories do dia", icone: <IconeNomeado nome="story" className="size-5" />, grupo: "Instagram" },
  { id: "respostas", rotulo: "Respostas do Instagram", icone: <IconeNomeado nome="respostas" className="size-5" />, grupo: "Instagram" },
  { id: "conta", rotulo: "Instagram da loja", icone: <IconeNomeado nome="conta" className="size-5" />, grupo: "Instagram" }
];

/** "Minha empresa": as mesmas opções do celular que fazem sentido no computador, numa barra lateral própria. */
export function TelaEmpresa() {
  const [secao, setSecao] = useState("dados");
  return (
    <PaginaComLateral titulo="Empresa" itens={SECOES} atual={secao} aoEscolher={setSecao}>
      <div className="mx-auto w-full max-w-5xl p-4 md:p-8">
        {secao === "dados" && <DadosDaLoja />}
        {secao === "regras" && <RegrasDeVenda />}
        {secao === "passos" && <PrimeirosPassos aoIr={setSecao} />}
        {secao === "historico" && <Historico />}
        {secao === "ideias" && <IdeiasDePost />}
        {secao === "videos" && <IdeiasDeVideo />}
        {secao === "stories" && <StoriesDoDia />}
        {secao === "respostas" && <RespostasDoInstagram />}
        {secao === "conta" && <InstagramDaLoja />}
      </div>
    </PaginaComLateral>
  );
}
