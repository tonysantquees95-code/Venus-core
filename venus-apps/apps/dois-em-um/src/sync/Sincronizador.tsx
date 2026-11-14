import { useEffect, useRef } from "react";
import { ErroId } from "../id/api";
import { useId } from "../id/IdProvider";
import { usePreferencias } from "../ajustes/Preferencias";
import { useEmpresa } from "../empresa/EmpresaProvider";
import { empresaPreenchida, limparEmpresa } from "../empresa/logica";
import { useTema } from "../theming";
import { docDeIdeias, lerDocIdeias } from "../empresa/marketing";
import { definirTokenIA } from "../ia/cliente";
import { useMarketing } from "../empresa/MarketingProvider";
import { prefsParaEnviar, prefsRecebidas } from "../ajustes/logica";
import { decidirRemoto, temaValido, type Versoes } from "./mesclar";

const INTERVALO_MS = 15_000;

/** Mantém tema, preferências e o cadastro da empresa iguais em todos os aparelhos da conta (consulta a cada 15 s e ao voltar para o app). */
export function Sincronizador() {
  const { api, sessao } = useId();
  const { tema, definirTudo: definirTemaTudo } = useTema();
  const { prefs, definirTodas } = usePreferencias();
  const { empresa, definirTudo } = useEmpresa();
  const mk = useMarketing();
  const versoes = useRef<Versoes>({});
  const desde = useRef(0);
  const ultimoEnviado = useRef<Record<string, string>>({});
  const atual = useRef({ tema, prefs, empresa, ideias: mk.todasAsIdeias });
  atual.current = { tema, prefs, empresa, ideias: mk.todasAsIdeias };
  const receberMk = useRef(mk.receber);
  receberMk.current = mk.receber;

  definirTokenIA(sessao?.token);

  // puxar
  useEffect(() => {
    if (!sessao) return;
    let parar = false;
    const puxar = async () => {
      try {
        const r = await api.lerSync(sessao.token, desde.current);
        if (parar) return;
        for (const doc of r.docs) {
          if (decidirRemoto(doc, versoes.current) !== "aplicar") continue;
          versoes.current[doc.chave] = doc.versao;
          if (doc.chave === "tema" && temaValido(doc.valor)) {
            ultimoEnviado.current.tema = JSON.stringify(doc.valor);
            definirTemaTudo(doc.valor);
          } else if (doc.chave === "ideiaspost" || doc.chave === "postagens" || doc.chave === "stories" || doc.chave === "instagram") {
            if (doc.chave === "ideiaspost") ultimoEnviado.current.ideiaspost = JSON.stringify(docDeIdeias(lerDocIdeias(doc.valor)));
            receberMk.current(doc.chave, doc.valor);
          } else if (doc.chave === "empresa") {
            const nova = limparEmpresa(doc.valor);
            ultimoEnviado.current.empresa = JSON.stringify(nova);
            definirTudo(nova);
          } else if (doc.chave === "preferencias") {
            const nova = prefsRecebidas(atual.current.prefs, doc.valor);
            ultimoEnviado.current.preferencias = JSON.stringify(prefsParaEnviar(nova));
            definirTodas(nova);
          }
        }
        desde.current = Math.max(0, r.agora - 60_000); // folga de 1 min para relógios diferentes
      } catch {
        /* sem conexão: tenta no próximo ciclo */
      }
    };
    void puxar();
    const t = setInterval(puxar, INTERVALO_MS);
    const aoVoltar = () => document.visibilityState === "visible" && void puxar();
    document.addEventListener("visibilitychange", aoVoltar);
    return () => {
      parar = true;
      clearInterval(t);
      document.removeEventListener("visibilitychange", aoVoltar);
    };
  }, [api, sessao?.token]);

  // enviar (com um pequeno atraso para juntar vários toques)
  useEffect(() => {
    if (!sessao) return;
    const espera = setTimeout(async () => {
      const itens: [string, unknown][] = [["tema", atual.current.tema], ["preferencias", prefsParaEnviar(atual.current.prefs)]];
      if (empresaPreenchida(atual.current.empresa)) itens.push(["empresa", atual.current.empresa]);
      if (atual.current.ideias.length) itens.push(["ideiaspost", docDeIdeias(atual.current.ideias)]);
      for (const [chave, valor] of itens) {
        const texto = JSON.stringify(valor);
        if (ultimoEnviado.current[chave] === texto) continue;
        try {
          const r = await api.gravarSync(sessao.token, chave, valor, versoes.current[chave] ?? 0);
          versoes.current[chave] = r.versao;
          ultimoEnviado.current[chave] = texto;
        } catch (e) {
          if (e instanceof ErroId && e.codigo === "versao_antiga") versoes.current[chave] = (e.dados.versao as number) - 1; // o próximo "puxar" aplica o remoto
        }
      }
    }, 1500);
    return () => clearTimeout(espera);
  }, [api, sessao?.token, tema, prefs, empresa, mk.todasAsIdeias]);

  return null;
}
