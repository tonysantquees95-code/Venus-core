import { useCallback, useEffect, useRef, useState } from "react";
import { URL_API } from "@venus/nucleo";
import { escolherMime, limparParaFalar } from "./logica";

export type EstadoVoz = "parado" | "gravando" | "transcrevendo";

/** Lê o texto em voz alta com a voz em português do sistema. */
export function falar(texto: string) {
  try {
    const t = limparParaFalar(texto);
    if (!t || typeof speechSynthesis === "undefined") return;
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(t);
    u.lang = "pt-BR";
    const voz = speechSynthesis.getVoices().find((v) => v.lang?.toLowerCase().startsWith("pt-br")) ?? speechSynthesis.getVoices().find((v) => v.lang?.toLowerCase().startsWith("pt"));
    if (voz) u.voice = voz;
    speechSynthesis.speak(u);
  } catch {
    /* sem voz no sistema: segue só com o texto */
  }
}

export function calar() {
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
