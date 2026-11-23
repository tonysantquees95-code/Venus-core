import { useEffect, useState } from "react";
import { IdentidadeDaLoja } from "./CriarLoja";
import { IconeNomeado } from "../components/Icones";
import { PaginaComLateral, type ItemLateral } from "../components/PaginaComLateral";
import { CartaoDeVisitas, Panfleto, QrCodeSecao } from "./Divulgacao";
import { VitrineECatalogo } from "./TelaVitrineWeb";
import { PainelAoVivo } from "./PainelAoVivo";
import { PaginaDoServidor } from "./PaginaDoServidor";
import { RecibosEEtiquetas } from "./RecibosEEtiquetas";
import { MinhasLojas, SeletorDeLoja } from "./MinhasLojas";
import { DadosDaLoja, Historico, IdeiasDePost, IdeiasDeVideo, InstagramDaLoja, PrimeirosPassos, RegrasDeVenda, RespostasDoInstagram, StoriesDoDia } from "./secoes";

const SECOES: ItemLateral[] = [
  { id: "lojas", rotulo: "Minhas lojas", icone: <IconeNomeado nome="loja" className="size-5" />, grupo: "Minha loja" },
  { id: "dados", rotulo: "Dados da loja", icone: <IconeNomeado nome="loja" className="size-5" />, grupo: "Minha loja" },
  { id: "identidade", rotulo: "Identidade da loja", icone: <IconeNomeado nome="vitrine" className="size-5" />, grupo: "Minha loja" },
  { id: "agenda", rotulo: "Agenda de serviços", icone: <IconeNomeado nome="agenda" className="size-5" />, grupo: "Minha loja" },
  { id: "meusite", rotulo: "Meu site e logo", icone: <IconeNomeado nome="site" className="size-5" />, grupo: "Minha loja" },
  { id: "recibos", rotulo: "Recibos e etiquetas", icone: <IconeNomeado nome="recibo" className="size-5" />, grupo: "Minha loja" },
  { id: "regras", rotulo: "Regras de venda", icone: <IconeNomeado nome="regras" className="size-5" />, grupo: "Minha loja" },
  { id: "passos", rotulo: "Primeiros passos", icone: <IconeNomeado nome="passos" className="size-5" />, grupo: "Minha loja" },
  { id: "vv-site", rotulo: "Criar meu site", icone: <IconeNomeado nome="site" className="size-5" />, grupo: "Vênus Vitrine" },
  { id: "vv-link", rotulo: "Link curto e QR code", icone: <IconeNomeado nome="conta" className="size-5" />, grupo: "Vênus Vitrine" },
  { id: "vv-panfleto", rotulo: "Panfleto grátis", icone: <IconeNomeado nome="panfleto" className="size-5" />, grupo: "Vênus Vitrine" },
  { id: "vv-logo", rotulo: "Logo grátis", icone: <IconeNomeado nome="ideia" className="size-5" />, grupo: "Vênus Vitrine" },
  { id: "vv-catalogo", rotulo: "Catálogo no WhatsApp", icone: <IconeNomeado nome="vitrine" className="size-5" />, grupo: "Vênus Vitrine" },
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
        {secao === "recibos" && <RecibosEEtiquetas />}
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
        {secao === "vv-site" && <PaginaDoServidor titulo="Criar meu site" descricao="Assistente em 6 passos: logo, formato, serviços, fotos e publicação, com prévia ao vivo." caminho="/vitrine/" extra="&app=venus" />}
        {secao === "vv-link" && <PaginaDoServidor titulo="Link curto e QR code" descricao="Encurte um endereço e gere o QR code para balcão, vitrine e cartão." caminho="/vitrine/" extra="&app=venus&ir=ferramentas&aba=link" />}
        {secao === "vv-panfleto" && <PaginaDoServidor titulo="Panfleto grátis" descricao="Panfleto pronto para imprimir ou postar, com o QR do seu WhatsApp." caminho="/vitrine/" extra="&app=venus&ir=ferramentas&aba=panfleto" />}
        {secao === "vv-logo" && <PaginaDoServidor titulo="Logo grátis" descricao="48 modelos prontos e o editor para personalizar do seu jeito." caminho="/vitrine/" extra="&app=venus&ir=ferramentas&aba=logo" />}
        {secao === "vv-catalogo" && <PaginaDoServidor titulo="Catálogo no WhatsApp" descricao="Catálogo online em que o cliente monta o pedido e manda pelo WhatsApp." caminho="/vitrine/" extra="&app=venus&ir=ferramentas&aba=catalogo" />}
        {secao === "agenda" && <PaginaDoServidor titulo="Agenda de serviços" descricao="Para salão, barbearia e quem atende com hora marcada: o cliente escolhe o serviço e o horário, e a equipe vê a agenda por um link." caminho="/agenda/painel" />}
        {secao === "meusite" && <PaginaDoServidor titulo="Meu site e logo" descricao="Crie o site do seu negócio e escolha um logo personalizado, com serviços, fotos e botão de agendar." caminho="/site/editor" />}
      </div>
    </PaginaComLateral>
  );
}
