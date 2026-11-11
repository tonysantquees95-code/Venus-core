import { useEffect, useState } from "react";

/** useState que guarda no localStorage (com try/catch: pode estar bloqueado). */
export function usePersistido<T>(chave: string, inicial: T) {
  const [valor, setValor] = useState<T>(() => {
    try {
      const bruto = localStorage.getItem(chave);
      return bruto ? (JSON.parse(bruto) as T) : inicial;
    } catch {
      return inicial;
    }
  });
  useEffect(() => {
    try {
      localStorage.setItem(chave, JSON.stringify(valor));
    } catch {
      /* sem armazenamento: segue só em memória */
    }
  }, [chave, valor]);
  return [valor, setValor] as const;
}
