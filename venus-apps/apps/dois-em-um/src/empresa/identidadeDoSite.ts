import type { ApiId } from "../id/api";
import type { Empresa } from "./logica";

/** Pega do site da loja a cor, o logo, o nome e a descrição, sem apagar o que a pessoa já preencheu à mão. Devolve o que mudou (vazio = nada novo). */
export async function identidadeParaEmpresa(api: ApiId, token: string, empresa: Empresa, forcar = false): Promise<Partial<Empresa>> {
  if (!empresa.site) throw new Error("Cadastre o site da loja primeiro.");
  const r = await api.identidadeDoSite(token, empresa.site);
  const mudar: Partial<Empresa> = {};
  if (r.cor && (forcar || !empresa.cor)) { mudar.cor = r.cor; }
  if (r.logo && /^data:image\/(png|jpeg|webp);base64,/.test(r.logo) && r.logo.length <= 150_000 && (forcar || !empresa.logo)) mudar.logo = r.logo;
  if (r.nome && !empresa.loja) mudar.loja = r.nome;
  if (r.descricao && !empresa.sobre) mudar.sobre = r.descricao;
  return mudar;
}
