import { useEffect, useState } from "react";
import { IdentidadeDaLoja } from "./CriarLoja";
import { IconeNomeado } from "../components/Icones";
import { PaginaComLateral, type ItemLateral } from "../components/PaginaComLateral";
import { CartaoDeVisitas, Panfleto, QrCodeSecao } from "./Divulgacao";
import { VitrineECatalogo } from "./VitrineWeb";
import { PainelAoVivo } from "./PainelAoVivo";
import { MinhasLojas, SeletorDeLoja } from "./MinhasLojas";
import { DadosDaLoja, Historico, IdeiasDePost, IdeiasDeVideo, InstagramDaLoja, PrimeirosPassos, RegrasDeVenda, RespostasDoInstagram, StoriesDoDia } from "./secoes";

const SECOES: ItemLateral[] = [
  { id: "lojas", rotulo: "Minhas lojas", icone: <IconeNomeado nome="loja" className="size-5" />, grupo: "Minha loja" },
  { id: "dados", rotulo: "Dados da loja", icone: <IconeNomeado nome="loja" className="size-5" />, grupo: "Minha loja" },
  { id: "identidade", rotulo: "Identidade da loja", icone: <IconeNomeado nome="vitrine" className="size-5" />, grupo: "Minha loja" },
  { id: "regras", rotulo: "Regras de venda", icone: <IconeNomeado nome="regras" className="size-5" />, grupo: "Minha loja" },
  { id: "passos", rotulo: "Primeiros passos", icone: <IconeNomeado nome="passos" className="size-5" />, grupo: "Minha loja" },
  { id: "painel", rotulo: "Painel ao vivo", icone: <IconeNomeado nome="painel" className="size-5" />, grupo: "Financeiro" },
  { id: "historico", rotulo: "Histórico de conversas", icone: <IconeNomeado nome="conversas" className="size-5" />, grupo: "Conversas" },
  { id: "ideias", rotulo: "Ideias de post", icone: <IconeNomeado nome="ideia" className="size-5" />, grupo: "Instagram" },
  { id: "videos", rotulo: "Ideias de vídeo (Reels)", icone: <IconeNomeado nome="video" className="size-5" />, grupo: "Instagram" },
  { id: "stories", rotulo: "Stories do dia", icone: <IconeNomeado nome="story" className="size-5" />, grupo: "Instagram" },
  { id: "respostas", rotulo: "Respostas do Instagram", icone: <IconeNomeado nome="respostas" className="size-5" />, grupo: "Instagram" },
  { id: "conta", rotulo: "Instagram da loja", icone: <IconeNomeado nome="conta" className="size-5" />, grupo: "Instagram" },
  { id: "qr", rotulo: "QR Code", icone: <IconeNomeado nome="qr" className="size-5" />, grupo: "Divulgação" },
  { id: "cartao", rotulo: "Cartão de visitas", icone: <IconeNomeado nome="cartao" className="size-5" />, grupo: "Divulgação" },
  { id: "panfleto", rotulo: "Panfleto", icone: <IconeNomeado nome="panfleto" className="size-5" />, grupo: "Divulgação" },
  { id: "vitrine", rotulo: "Vitrine e catálogo", icone: <IconeNomeado nome="vitrine" className="size-5" />, grupo: "Divulgação" }
];

/** "Minha empresa": as mesmas opções do celular que fazem sentido no computador, numa barra lateral própria. */
export function TelaEmpresa({ alvo }: { alvo?: string }) {
  const [secao, setSecao] = useState(alvo && SECOES.some((i) => i.id === alvo) ? alvo : "dados");
  useEffect(() => { if (alvo && SECOES.some((i) => i.id === alvo)) setSecao(alvo); }, [alvo]);
  return (
    <PaginaComLateral titulo="Empresa" itens={SECOES} atual={secao} aoEscolher={setSecao} abrirDireto={!!alvo && SECOES.some((i) => i.id === alvo)}>
      <div className="mx-auto w-full max-w-5xl p-4 md:p-8">
        {secao !== "lojas" && <SeletorDeLoja aoGerenciar={() => setSecao("lojas")} />}
        {secao === "lojas" && <MinhasLojas aoCriada={() => { setSecao("identidade"); window.dispatchEvent(new Event("venus:criar-loja")); }} />}
        {secao === "dados" && <DadosDaLoja />}
        {secao === "identidade" && <IdentidadeDaLoja aoRefazer={() => window.dispatchEvent(new Event("venus:criar-loja"))} />}
        {secao === "regras" && <RegrasDeVenda />}
        {secao === "passos" && <PrimeirosPassos aoIr={setSecao} />}
        {secao === "historico" && <Historico />}
        {secao === "ideias" && <IdeiasDePost />}
        {secao === "videos" && <IdeiasDeVideo />}
        {secao === "stories" && <StoriesDoDia />}
        {secao === "respostas" && <RespostasDoInstagram />}
        {secao === "conta" && <InstagramDaLoja />}
        {secao === "qr" && <QrCodeSecao />}
        {secao === "cartao" && <CartaoDeVisitas />}
        {secao === "panfleto" && <Panfleto />}
        {secao === "vitrine" && <VitrineECatalogo />}
        {secao === "painel" && <PainelAoVivo />}
      </div>
    </PaginaComLateral>
  );
}
