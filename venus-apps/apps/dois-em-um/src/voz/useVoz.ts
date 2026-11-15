import { useCallback, useEffect, useRef, useState } from "react";
import { URL_API } from "@venus/nucleo";
import { escolherMime, limparParaFalar } from "./logica";
import { ESTILOS, LIMITE_SEGURO, neuralAtiva, ssml, type ConfigVoz } from "./config";
import { lerConfigVoz, somarUso, usoDoMes } from "./armazem";

export type EstadoVoz = "parado" | "gravando" | "transcrevendo";

let ordem = 0;
let audioAtual: HTMLAudioElement | null = null;
const guardados = new Map<string, Blob>();

function pararAudio() {
  if (audioAtual) { try { audioAtual.pause(); } catch { /* ok */ } audioAtual.src = ""; audioAtual = null; }
}

/** Voz da Microsoft (Azure Speech), a mesma do Android. Null se não der: aí usa a voz do Windows. */
export async function sintetizarNeural(texto: string, c: ConfigVoz = lerConfigVoz(), tempoMs = 6000): Promise<Blob | null> {
  const limpo = texto.trim().slice(0, 3000);
  if (!limpo || !c.neural.chave) return null;
  const estilo = ESTILOS.find((e) => e.id === c.neural.estilo) ?? ESTILOS[0]!;
  const corpo = ssml(limpo, c.neural.voz, estilo, c.velocidade, c.tom);
  const guardado = guardados.get(corpo);
  if (guardado) return guardado;
  if (usoDoMes() + limpo.length > LIMITE_SEGURO) return null;
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), tempoMs);
  try {
    const r = await fetch(`https://${c.neural.regiao}.tts.speech.microsoft.com/cognitiveservices/v1`, {
      method: "POST", signal: ctl.signal,
      headers: { "Ocp-Apim-Subscription-Key": c.neural.chave, "Content-Type": "application/ssml+xml", "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3" },
      body: corpo,
    });
    if (!r.ok) return null;
    const blob = await r.blob();
    if (!blob.size) return null;
    somarUso(limpo.length);
    guardados.set(corpo, blob);
    if (guardados.size > 40) guardados.delete(guardados.keys().next().value as string);
    return blob;
  } catch { return null; } finally { clearTimeout(t); }
}

/** As vozes em português que o sistema tem (a primeira leitura às vezes vem vazia: o navegador carrega depois). */
export function vozesDoSistema(): SpeechSynthesisVoice[] {
  try {
    return speechSynthesis.getVoices().filter((v) => v.lang?.toLowerCase().startsWith("pt")).sort((a, b) => Number(b.lang.toLowerCase() === "pt-br") - Number(a.lang.toLowerCase() === "pt-br") || a.name.localeCompare(b.name));
  } catch { return []; }
}

function falarComVozDoSistema(t: string, c: ConfigVoz, aoTerminar?: () => void) {
  try {
    if (typeof speechSynthesis === "undefined") { aoTerminar?.(); return; }
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(t);
    u.lang = "pt-BR";
    u.rate = c.velocidade;
    u.pitch = c.tom;
    const todas = speechSynthesis.getVoices();
    const voz = todas.find((v) => v.name === c.vozSistema) ?? todas.find((v) => v.lang?.toLowerCase().startsWith("pt-br")) ?? todas.find((v) => v.lang?.toLowerCase().startsWith("pt"));
    if (voz) u.voice = voz;
    if (aoTerminar) { u.onend = aoTerminar; u.onerror = aoTerminar; }
    speechSynthesis.speak(u);
  } catch {
    aoTerminar?.();
    /* sem voz no sistema: segue só com o texto */
  }
}

/** Lê o texto em voz alta: voz neural da Microsoft se estiver ligada (com a chave), senão a voz do Windows. */
export function falar(texto: string, aoTerminar?: () => void) {
  const t = limparParaFalar(texto);
  if (!t) return;
  calar();
  const minha = ordem;
  const c = lerConfigVoz();
  if (!neuralAtiva(c, usoDoMes())) { falarComVozDoSistema(t, c, aoTerminar); return; }
  void sintetizarNeural(t, c).then((blob) => {
    if (minha !== ordem) return; // outra fala começou ou foi calada
    if (!blob) { falarComVozDoSistema(t, c, aoTerminar); return; }
    const url = URL.createObjectURL(blob);
    const a = new Audio(url);
    const fim = () => { URL.revokeObjectURL(url); if (audioAtual === a) audioAtual = null; aoTerminar?.(); };
    a.onended = fim;
    a.onerror = () => { URL.revokeObjectURL(url); if (audioAtual === a) audioAtual = null; falarComVozDoSistema(t, c, aoTerminar); };
    audioAtual = a;
    void a.play().catch(() => falarComVozDoSistema(t, c, aoTerminar));
  });
}

export function pausarFala() {
  try { audioAtual?.pause(); speechSynthesis?.pause(); } catch { /* ok */ }
}

export function continuarFala() {
  try { void audioAtual?.play(); speechSynthesis?.resume(); } catch { /* ok */ }
}

export function calar() {
  ordem++;
  pararAudio();
  try { speechSynthesis?.cancel(); } catch { /* ok */ }
}

/** Microfone: toque para começar, toque de novo para terminar. O áudio vai ao servidor, que devolve o texto. */
export function useVoz(aoTexto: (texto: string) => void) {
  const [estado, setEstado] = useState<EstadoVoz>("parado");
  const [erro, setErro] = useState("");
  const gravador = useRef<MediaRecorder | null>(null);
  const pedacos = useRef<Blob[]>([]);
  const fluxo = useRef<MediaStream | null>(null);
  const aoTextoRef = useRef(aoTexto);
  aoTextoRef.current = aoTexto;

  const soltarMic = () => { fluxo.current?.getTracks().forEach((t) => t.stop()); fluxo.current = null; };
  useEffect(() => () => { try { gravador.current?.stop(); } catch { /* ok */ } soltarMic(); }, []);

  const iniciar = useCallback(async () => {
    setErro("");
    calar();
    try {
      if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") throw new Error("sem_suporte");
      const mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      fluxo.current = mic;
      const mime = escolherMime((m) => MediaRecorder.isTypeSupported(m));
      const g = new MediaRecorder(mic, mime ? { mimeType: mime } : undefined);
      pedacos.current = [];
      g.ondataavailable = (e) => { if (e.data.size) pedacos.current.push(e.data); };
      g.onstop = async () => {
        soltarMic();
        const tipo: string = (g.mimeType || mime || "audio/webm").split(";")[0] ?? "audio/webm";
        const audio = new Blob(pedacos.current, { type: g.mimeType || mime || "audio/webm" });
        pedacos.current = [];
        if (audio.size < 800) { setEstado("parado"); return; }
        setEstado("transcrevendo");
        try {
          const r = await fetch(`${URL_API}/v1/transcrever`, { method: "POST", headers: { "content-type": tipo }, body: audio });
          if (!r.ok) throw new Error(String(r.status));
          const { texto } = (await r.json()) as { texto?: string };
          if (texto?.trim()) aoTextoRef.current(texto.trim());
          else setErro("Não entendi o que você disse. Tente de novo.");
        } catch {
          setErro("Não consegui transcrever agora. Tente de novo em instantes.");
        } finally {
          setEstado("parado");
        }
      };
      gravador.current = g;
      g.start();
      setEstado("gravando");
    } catch (e) {
      soltarMic();
      setEstado("parado");
      setErro((e as Error).message === "sem_suporte" ? "Este aparelho não grava áudio." : "Libere o microfone para falar com a Vênus.");
    }
  }, []);

  const parar = useCallback(() => {
    try { if (gravador.current?.state === "recording") gravador.current.stop(); } catch { setEstado("parado"); }
  }, []);

  const alternar = useCallback(() => (estado === "gravando" ? parar() : estado === "parado" ? void iniciar() : undefined), [estado, iniciar, parar]);
  return { estado, erro, alternar };
}
