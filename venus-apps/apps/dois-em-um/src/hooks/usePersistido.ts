import { useCallback, useEffect, useState } from "react";

function ler<T>(chave: string, inicial: T): T {
  try {
    const bruto = localStorage.getItem(chave);
    return bruto ? (JSON.parse(bruto) as T) : inicial;
  } catch {
    return inicial;
  }
}

/** useState que guarda no localStorage (com try/catch: pode estar bloqueado). Se a chave mudar (ex.: troca de loja), lê o valor da nova chave. */
export function usePersistido<T>(chave: string, inicial: T) {
  const [par, setPar] = useState<{ chave: string; valor: T }>(() => ({ chave, valor: ler(chave, inicial) }));
  let atual = par;
  if (par.chave !== chave) { atual = { chave, valor: ler(chave, inicial) }; setPar(atual); }
  const setValor = useCallback((v: T | ((anterior: T) => T)) => {
    setPar((p) => ({ chave: p.chave, valor: typeof v === "function" ? (v as (a: T) => T)(p.valor) : v }));
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(atual.chave, JSON.stringify(atual.valor));
    } catch {
      /* sem armazenamento: segue só em memória */
    }
  }, [atual.chave, atual.valor]);
  return [atual.valor, setValor] as const;
}
