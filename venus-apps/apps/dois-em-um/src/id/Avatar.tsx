import { useEffect, useId as useIdReact, useRef, useState } from "react";
import { botaoPrimario, botaoSuave, campo } from "../ajustes/Controles";
import { useId } from "./IdProvider";
import { corDoAvatar, iniciais, textoDaAssinatura } from "./perfil";

/** Navegação entre telas a partir do popover (o App escuta este evento). */
export const abrirNoApp = (tela: "ajustes" | "login", alvo?: string) =>
  window.dispatchEvent(new CustomEvent("venus:abrir", { detail: { tela, alvo } }));

/** Foto da pessoa, ou as iniciais num círculo colorido (a cor é a mesma em todos os aparelhos). */
export function Foto({ nome, email, foto, tamanho = 36 }: { nome: string; email: string; foto?: string; tamanho?: number }) {
  const [falhou, setFalhou] = useState(false);
  useEffect(() => setFalhou(false), [foto]);
  const estilo = { width: tamanho, height: tamanho, fontSize: tamanho * 0.38 };
  if (foto && !falhou) return <img src={foto} alt="" onError={() => setFalhou(true)} style={estilo} className="shrink-0 rounded-full object-cover" referrerPolicy="no-referrer" />;
  return <span aria-hidden style={{ ...estilo, background: corDoAvatar(email || nome) }} className="grid shrink-0 place-items-center rounded-full font-semibold text-white">{iniciais(nome, email)}</span>;
}

/** Reduz a foto escolhida para ~128 px (JPEG) para caber no perfil e sincronizar rápido. */
export async function reduzirFoto(arquivo: File, lado = 128): Promise<string> {
  const bmp = await createImageBitmap(arquivo);
  const c = document.createElement("canvas");
  c.width = c.height = lado;
  const ctx = c.getContext("2d")!;
  const corte = Math.min(bmp.width, bmp.height);
  ctx.drawImage(bmp, (bmp.width - corte) / 2, (bmp.height - corte) / 2, corte, corte, 0, 0, lado, lado);
  return c.toDataURL("image/jpeg", 0.8);
}

function Gerenciar({ aoFechar }: { aoFechar: () => void }) {
  const { conta, salvarPerfil } = useId();
  const [nome, setNome] = useState(conta?.usuario.nome ?? "");
  const [foto, setFoto] = useState(conta?.usuario.foto ?? "");
  const [erro, setErro] = useState("");
  const [ocupado, setOcupado] = useState(false);
  if (!conta) return null;
  async function salvar() {
    setOcupado(true);
    try { await salvarPerfil({ nome, foto }); aoFechar(); } catch (e) { setErro(e instanceof Error ? e.message : "Não consegui salvar."); }
    setOcupado(false);
  }
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-label="Gerenciar conta">
      <div className="w-full max-w-sm space-y-4 rounded-3xl border border-white/15 bg-painel p-5 shadow-2xl">
        <h2 className="text-lg font-semibold">Gerenciar conta</h2>
        <div className="flex items-center gap-4">
          <Foto nome={nome} email={conta.usuario.email} foto={foto} tamanho={72} />
          <div className="flex flex-col gap-2">
            <label className={`${botaoSuave} cursor-pointer text-center`}>
              Trocar foto
              <input type="file" accept="image/*" className="sr-only" onChange={async (e) => {
                const f = e.target.files?.[0];
                if (f) try { setFoto(await reduzirFoto(f)); setErro(""); } catch { setErro("Não consegui ler essa imagem."); }
              }} />
            </label>
            {foto && <button className={botaoSuave} onClick={() => setFoto("")}>Usar iniciais</button>}
          </div>
        </div>
        <label className="block text-sm text-suave">Nome<input className={`${campo} mt-1`} value={nome} onChange={(e) => setNome(e.target.value)} maxLength={80} /></label>
        <p className="text-sm text-suave">E-mail: <span className="text-texto">{conta.usuario.email}</span></p>
        {erro && <p role="alert" className="text-sm text-rose-300">{erro}</p>}
        <div className="flex gap-2">
          <button className={`${botaoSuave} flex-1`} onClick={aoFechar}>Cancelar</button>
          <button className={`${botaoPrimario} flex-1`} disabled={ocupado} onClick={() => void salvar()}>Salvar</button>
        </div>
      </div>
    </div>
  );
}

/** Fotinho de acesso à conta (padrão Gemini): abre um menu com perfil, assinatura e atalhos. */
export function AvatarDeConta({ tamanho = 36 }: { tamanho?: number }) {
  const { sessao, conta, sair } = useId();
  const [aberto, setAberto] = useState(false);
  const [gerenciando, setGerenciando] = useState(false);
  const raiz = useRef<HTMLDivElement>(null);
  const idMenu = useIdReact();

  useEffect(() => {
    if (!aberto) return;
    const fora = (e: MouseEvent | TouchEvent) => { if (!raiz.current?.contains(e.target as Node)) setAberto(false); };
    const tecla = (e: KeyboardEvent) => { if (e.key === "Escape") setAberto(false); };
    document.addEventListener("mousedown", fora);
    document.addEventListener("touchstart", fora);
    document.addEventListener("keydown", tecla);
    return () => { document.removeEventListener("mousedown", fora); document.removeEventListener("touchstart", fora); document.removeEventListener("keydown", tecla); };
  }, [aberto]);

  const nome = conta?.usuario.nome || sessao?.usuario.nome || "";
  const email = conta?.usuario.email || sessao?.usuario.email || "";
  const ir = (f: () => void) => () => { setAberto(false); f(); };
  const item = "flex min-h-11 w-full items-center gap-3 rounded-xl px-3 text-left text-sm hover:bg-white/10";

  return (
    <div ref={raiz} className="relative">
      <button type="button" onClick={() => setAberto((a) => !a)} aria-haspopup="dialog" aria-expanded={aberto} aria-controls={idMenu}
        aria-label={sessao ? `Conta de ${nome || email}` : "Entrar na conta"} className="grid place-items-center rounded-full ring-2 ring-transparent transition hover:ring-venus/60 focus-visible:ring-venus">
        {sessao ? <Foto nome={nome} email={email} foto={conta?.usuario.foto} tamanho={tamanho} /> :
          <span style={{ width: tamanho, height: tamanho }} className="grid place-items-center rounded-full bg-white/10 text-suave" aria-hidden>
            <svg width="55%" height="55%" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></svg>
          </span>}
      </button>
      {aberto && (
        <div id={idMenu} role="dialog" aria-label="Menu da conta" className="absolute right-0 top-[calc(100%+8px)] z-40 w-[min(20rem,calc(100vw-1.5rem))] rounded-3xl border border-white/15 bg-painel/95 p-3 shadow-2xl backdrop-blur-xl">
          {sessao ? (
            <>
              <div className="flex flex-col items-center gap-2 px-3 pb-3 pt-2 text-center">
                <Foto nome={nome} email={email} foto={conta?.usuario.foto} tamanho={64} />
                <div className="min-w-0">
                  <p className="truncate font-semibold">{nome || "Sem nome"}</p>
                  <p className="truncate text-sm text-suave">{email}</p>
                </div>
                <p className="rounded-full bg-venus/15 px-3 py-1 text-xs text-venus">{textoDaAssinatura(conta?.licenca)}</p>
              </div>
              <button className={item} onClick={ir(() => setGerenciando(true))}>👤 Gerenciar conta</button>
              <button className={item} onClick={ir(() => abrirNoApp("ajustes", "dispositivos"))}>📱 Meus dispositivos conectados</button>
              <button className={item} onClick={ir(() => abrirNoApp("ajustes"))}>⚙️ Configurações</button>
              <button className={`${item} text-rose-300`} onClick={ir(() => void sair())}>↩ Sair</button>
            </>
          ) : (
            <div className="space-y-3 p-3 text-center">
              <p className="text-sm text-suave">Entre com o Vetorial ID para usar a mesma conta em todos os aparelhos.</p>
              <button className={`${botaoPrimario} w-full`} onClick={ir(() => abrirNoApp("login"))}>Entrar</button>
            </div>
          )}
        </div>
      )}
      {gerenciando && <Gerenciar aoFechar={() => setGerenciando(false)} />}
    </div>
  );
}
