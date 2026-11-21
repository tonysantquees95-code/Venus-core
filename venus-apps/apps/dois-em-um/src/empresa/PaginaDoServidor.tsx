import { URL_API } from "@venus/nucleo";
import { botaoPrimario } from "../ajustes/Controles";
import { useId } from "../id/IdProvider";
import { Titulo } from "./Divulgacao";
import { useLojas } from "./LojasProvider";

/**
 * Página do servidor aberta aqui dentro (agenda de serviços e editor do site com logo): a mesma do celular.
 * O token vai no #fragmento (nunca é enviado ao servidor) e a loja aberta, se for uma das extras, vai junto em `l=`.
 */
export function PaginaDoServidor({ titulo, descricao, caminho }: { titulo: string; descricao: string; caminho: string }) {
  const { sessao } = useId();
  const { abertaId } = useLojas();
  if (!sessao) return <><Titulo titulo={titulo} /><p className="text-sm text-suave">Entre com o seu Vetorial ID (Ajustes › Conta) para abrir esta página da sua loja.</p></>;
  const endereco = `${URL_API}${caminho}#t=${sessao.token}${abertaId ? `&l=${abertaId}` : ""}`;
  return (
    <>
      <Titulo titulo={titulo} descricao={descricao} />
      <div className="mb-3"><button className={botaoPrimario} onClick={() => window.open(endereco, "_blank", "noopener")}>Abrir em tela cheia</button></div>
      <iframe key={`${caminho}-${abertaId}`} title={titulo} src={endereco} className="h-[72vh] w-full rounded-3xl border border-white/10 bg-[#070a1c]" allow="clipboard-write" />
    </>
  );
}
