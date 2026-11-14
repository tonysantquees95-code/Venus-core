import { createContext, useCallback, useContext, useMemo, type ReactNode } from "react";
import { usePersistido } from "../hooks/usePersistido";
import { chaveDeTitulo, lerDocIdeias, lerDocPosts, lerDocStories, type IdeiaDePost, type PostProgramado, type StoriesDoCelular } from "./marketing";

interface Ctx {
  /** Ideias de post vivas (as do celular e as daqui, juntas). */
  ideias: IdeiaDePost[];
  /** Tudo, com as marcas de "removida" que avisam o outro aparelho. */
  todasAsIdeias: IdeiaDePost[];
  trocarIdeias: (novas: IdeiaDePost[]) => void;
  juntarIdeias: (novas: IdeiaDePost[]) => void;
  atualizarIdeia: (id: string, mudanca: Partial<IdeiaDePost>) => void;
  tirarIdeia: (id: string) => void;
  postsDoCelular: PostProgramado[];
  storiesDoCelular: StoriesDoCelular | null;
  /** Resumo do Instagram que o celular mandou (seguidores, últimos posts). */
  resumoInstagram: string;
  /** Chegou da conta (Sincronizador). */
  receber: (chave: string, valor: unknown) => void;
}
const C = createContext<Ctx | null>(null);

const MAX_MARCAS = 60;

export function MarketingProvider({ children }: { children: ReactNode }) {
  const [brutas, setBrutas] = usePersistido<IdeiaDePost[]>("venus:ideias-post", []);
  const [posts, setPosts] = usePersistido<PostProgramado[]>("venus:posts-celular", []);
  const [stories, setStories] = usePersistido<StoriesDoCelular | null>("venus:stories-celular", null);

  const [resumoIg, setResumoIg] = usePersistido<string>("venus:resumo-instagram", "");

  const ideias = useMemo(() => brutas.filter((i) => !i.removida), [brutas]);
  const marca = (i: IdeiaDePost): IdeiaDePost => ({ id: i.id, titulo: i.titulo, formato: "feed", legenda: "", quando: "", usada: false, removida: true, ts: Date.now() });
  const limitar = (l: IdeiaDePost[]) => [...l.filter((i) => !i.removida), ...l.filter((i) => i.removida).sort((a, b) => (b.ts ?? 0) - (a.ts ?? 0)).slice(0, MAX_MARCAS)];

  const trocarIdeias = useCallback((novas: IdeiaDePost[]) => setBrutas((atuais) => {
    const ids = new Set(novas.map((n) => n.id));
    const apagadas = atuais.filter((i) => !i.removida && !ids.has(i.id)).map(marca);
    const antigasMarcas = atuais.filter((i) => i.removida && !ids.has(i.id));
    return limitar([...novas.map((n) => ({ ...n, removida: false })), ...apagadas, ...antigasMarcas]);
  }), [setBrutas]);
  const juntarIdeias = useCallback((novas: IdeiaDePost[]) => setBrutas((atuais) => {
    const ids = new Set(novas.map((n) => n.id));
    return limitar([...atuais.filter((i) => !ids.has(i.id)), ...novas.map((n) => ({ ...n, removida: false }))]);
  }), [setBrutas]);
  const atualizarIdeia = useCallback((id: string, m: Partial<IdeiaDePost>) => setBrutas((a) => a.map((i) => (i.id === id ? { ...i, ...m } : i))), [setBrutas]);
  const tirarIdeia = useCallback((id: string) => setBrutas((a) => limitar(a.map((i) => (i.id === id ? marca(i) : i)))), [setBrutas]);

  const receber = useCallback((chave: string, valor: unknown) => {
    if (chave === "ideiaspost") setBrutas(lerDocIdeias(valor));
    else if (chave === "postagens") setPosts(lerDocPosts(valor));
    else if (chave === "stories") setStories(lerDocStories(valor));
    else if (chave === "instagram") setResumoIg(typeof (valor as any)?.resumo === "string" ? (valor as any).resumo.slice(0, 1500) : "");
  }, [setBrutas, setPosts, setStories, setResumoIg]);

  const v = useMemo(() => ({ ideias, todasAsIdeias: brutas, trocarIdeias, juntarIdeias, atualizarIdeia, tirarIdeia, postsDoCelular: posts, storiesDoCelular: stories, resumoInstagram: resumoIg, receber }),
    [ideias, brutas, trocarIdeias, juntarIdeias, atualizarIdeia, tirarIdeia, posts, stories, resumoIg, receber]);
  return <C.Provider value={v}>{children}</C.Provider>;
}

export function useMarketing(): Ctx {
  const c = useContext(C);
  if (!c) throw new Error("useMarketing precisa estar dentro de <MarketingProvider>");
  return c;
}
void chaveDeTitulo;
