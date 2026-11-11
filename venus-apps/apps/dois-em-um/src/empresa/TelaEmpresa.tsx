import { useEffect, useState } from "react";
import { useId } from "../id/IdProvider";
import { Cartao, botaoPrimario, campo } from "../ajustes/Controles";
import { useEmpresa } from "./EmpresaProvider";
import { regrasEmLinhas } from "./logica";

const CAMPOS: { id: "dono" | "loja" | "ramo" | "cidade" | "uf" | "site"; rotulo: string; dica?: string }[] = [
  { id: "dono", rotulo: "Seu nome" },
  { id: "loja", rotulo: "Nome da loja" },
  { id: "ramo", rotulo: "Ramo", dica: "Ex.: moda feminina, sex shop, eletrônicos" },
  { id: "cidade", rotulo: "Cidade" },
  { id: "uf", rotulo: "UF" },
  { id: "site", rotulo: "Site da loja" }
];

/** "Minha empresa": o cadastro é um só, feito no celular ou aqui, e aparece em todos os aparelhos da conta. */
export function TelaEmpresa() {
  const { empresa, salvar } = useEmpresa();
  const { sessao } = useId();
  const [rascunho, setRascunho] = useState(empresa);
  const [salvo, setSalvo] = useState(false);
  // chegou cadastro novo da conta (celular): atualiza o formulário
  useEffect(() => setRascunho(empresa), [empresa]);
  const regras = regrasEmLinhas(empresa.regrasConfiguradas ? empresa.regras : null);

  return (
    <section aria-label="Minha empresa" className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-4 p-4 sm:p-6">
        <header>
          <h1 className="text-2xl font-semibold">Minha empresa</h1>
          <p className="mt-1 text-sm text-suave">
            {sessao
              ? "Cadastro único: o que você preenche no celular aparece aqui, e o que muda aqui chega no celular."
              : "Entre com o Vetorial ID para trazer o cadastro que você fez no celular."}
          </p>
        </header>
        <Cartao id="empresa-dados" titulo="Dados da loja">
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => { e.preventDefault(); salvar(rascunho); setSalvo(true); setTimeout(() => setSalvo(false), 2500); }}
          >
            {CAMPOS.map((c) => (
              <label key={c.id} className="flex flex-col gap-1 text-sm">
                <span className="text-suave">{c.rotulo}</span>
                <input
                  className={campo}
                  value={rascunho[c.id]}
                  maxLength={c.id === "uf" ? 2 : 200}
                  placeholder={c.dica}
                  onChange={(e) => setRascunho({ ...rascunho, [c.id]: e.target.value })}
                />
              </label>
            ))}
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-suave">Sobre a empresa</span>
              <textarea className={`${campo} min-h-28`} value={rascunho.sobre} maxLength={1500} onChange={(e) => setRascunho({ ...rascunho, sobre: e.target.value })} />
            </label>
            <div className="flex items-center gap-3">
              <button type="submit" className={botaoPrimario}>Salvar</button>
              {salvo && <span role="status" className="text-sm text-suave">Salvo{sessao ? " e enviado para a conta" : ""}.</span>}
            </div>
          </form>
        </Cartao>
        <Cartao
          id="empresa-regras"
          titulo="Regras de venda"
          descricao={empresa.regrasConfiguradas ? "Cadastradas no celular. A Vênus segue estas regras ao responder clientes." : "Ainda não cadastradas. Configure no celular (Menu → Regras de venda) e elas chegam aqui."}
        >
          {regras.length > 0 && (
            <ul className="list-disc space-y-1 pl-5 text-sm">{regras.map((l) => <li key={l}>{l}</li>)}</ul>
          )}
        </Cartao>
      </div>
    </section>
  );
}
