import { URL_API } from "@venus/nucleo";
import { botaoPrimario } from "../ajustes/Controles";
import { useId } from "../id/IdProvider";
import { Titulo } from "./Divulgacao";

/** Painel ao vivo da loja (faturamento, taxas e auditoria): o mesmo do celular, aberto aqui dentro. */
export function PainelAoVivo() {
  const { sessao } = useId();
  if (!sessao) return <><Titulo titulo="Painel ao vivo" /><p className="text-sm text-suave">Entre com o seu Vetorial ID (Ajustes › Conta) para ver o painel da sua loja.</p></>;
  // o token vai no #fragmento: ele nunca é enviado ao servidor
  const endereco = `${URL_API}/painel#t=${sessao.token}`;
  return (
    <>
      <Titulo titulo="Painel ao vivo" descricao="Faturamento, taxas e auditoria da loja em tempo real, igual ao painel do celular." />
      <div className="mb-3"><button className={botaoPrimario} onClick={() => window.open(endereco, "_blank", "noopener")}>Abrir em tela cheia</button></div>
      <iframe title="Painel ao vivo da loja" src={endereco} className="h-[72vh] w-full rounded-3xl border border-white/10 bg-[#070a1c]" />
    </>
  );
}
