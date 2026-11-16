import { useCallback, useEffect, useState } from "react";
import { useId } from "../id/IdProvider";
import type { ApiId, ItemVitrine, Sessao, VitrineInfo } from "../id/api";

/** A vitrine online da loja (vetorial.tech/<loja>) como a web enxerga: dados da página e produtos publicados. */
export function useVitrine(): { info: VitrineInfo | null; produtos: ItemVitrine[]; erro: string; carregando: boolean; carregar: () => Promise<void>; sessao: Sessao | null; api: ApiId } {
  const { api, sessao } = useId();
  const [info, setInfo] = useState<VitrineInfo | null>(null);
  const [produtos, setProdutos] = useState<ItemVitrine[]>([]);
  const [erro, setErro] = useState("");
  const [carregando, setCarregando] = useState(false);
  const token = sessao?.token;
  const carregar = useCallback(async () => {
    if (!token) return;
    setCarregando(true); setErro("");
    try {
      const [i, p] = await Promise.all([api.vitrine(token), api.itensDaVitrine(token)]);
      setInfo(i); setProdutos(p.produtos);
    } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui falar com o servidor."); }
    setCarregando(false);
  }, [api, token]);
  useEffect(() => { void carregar(); }, [carregar]);
  return { info, produtos, erro, carregando, carregar, sessao, api };
}

/** Endereço da página de um produto na vitrine. */
export const enderecoDoProduto = (base: string, sku: string) => `${base.replace(/\/+$/, "")}/${encodeURIComponent(sku)}`;
