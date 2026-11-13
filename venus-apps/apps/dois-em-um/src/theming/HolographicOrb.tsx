import { useEffect, useRef } from "react";
import { clarear, hsvParaRgb, trio } from "./cor";
import { useTema } from "./ThemingProvider";
import type { EstadoOrbe, OrbeId, RGB } from "./tipos";

interface Props {
  /** Sem `variante`, usa o movimento escolhido no tema. */
  variante?: OrbeId;
  tamanho?: number;
  className?: string;
  estado?: EstadoOrbe;
  /** 0 a 1: volume da voz enquanto ouve. */
  nivelVoz?: number;
}

const N = 130;
const MATIZ_VENUS = 325;
const OSCILACAO = 60;

/** Esfera de pontos (Fibonacci), igual à do celular; o 4º valor é um sorteio fixo por ponto. */
const ESFERA: number[][] = (() => {
  let semente = 12345;
  const rnd = () => ((semente = (semente * 1664525 + 1013904223) >>> 0) / 4294967296);
  return Array.from({ length: N }, (_, i) => {
    const y = 1 - (i / (N - 1)) * 2;
    const raioY = Math.sqrt(Math.max(0, 1 - y * y));
    const t = i * 2.399963;
    return [Math.cos(t) * raioY, y, Math.sin(t) * raioY, rnd()];
  });
})();

const rgba = (c: RGB, a: number) => `rgba(${c.r},${c.g},${c.b},${Math.max(0, Math.min(1, a))})`;

/**
 * Orbe da Vênus: porte fiel do Orbe.kt do celular (esfera de partículas com constelações,
 * brilho, núcleo e reflexo de vidro), com os mesmos cinco movimentos e quatro estados.
 */
export function HolographicOrb({ variante, tamanho = 48, className = "", estado = "ocioso", nivelVoz = 0 }: Props) {
  const { tema } = useTema();
  const ref = useRef<HTMLCanvasElement>(null);
  const modelo = variante ?? tema.orbe;
  // valores que mudam sem reiniciar a animação
  const vivo = useRef({ modelo, estado, nivelVoz, tema });
  vivo.current = { modelo, estado, nivelVoz, tema };

  useEffect(() => {
    const cv = ref.current;
    const ctx = cv?.getContext("2d");
    if (!cv || !ctx) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    cv.width = Math.round(tamanho * dpr);
    cv.height = Math.round(tamanho * dpr);
    const reduzir = false; // a orbe sempre se move (no Windows o "reduzir animações" do sistema a deixava parada)

    let tempo = 0, energia = 0.07, giro = 0, ultimo = 0, raf = 0;
    const sx = new Float32Array(N), sy = new Float32Array(N), sz = new Float32Array(N);

    const quadro = (agora: number) => {
      const { modelo: mod, estado: est, nivelVoz: nv, tema: tm } = vivo.current;
      const dt = ultimo === 0 ? 0.016 : Math.min(0.05, Math.max(0, (agora - ultimo) / 1000));
      ultimo = agora;
      tempo += reduzir ? 0 : dt;

      const alvo =
        est === "ouvindo" ? 0.15 + nv * 0.85
        : est === "pensando" ? 0.3 + 0.12 * Math.sin(tempo * 5)
        : est === "falando" ? 0.25 + 0.5 * Math.abs(Math.sin(tempo * 7.3) * Math.sin(tempo * 2.9 + 1)) + 0.08 * Math.sin(tempo * 17)
        : 0.07 + 0.04 * Math.sin(tempo * 1.1);
      energia += (alvo - energia) * Math.min(1, dt * 9);
      const vel = est === "ouvindo" ? 0.45 + energia : est === "pensando" ? 1.6 : est === "falando" ? 0.7 : 0.22;
      giro += reduzir ? 0 : dt * vel;

      let c1: RGB, c2: RGB, c3: RGB;
      if (tm.auto) {
        const h = MATIZ_VENUS + OSCILACAO * Math.sin(tempo * 0.13);
        c1 = hsvParaRgb(h, 0.78, 1);
        c2 = hsvParaRgb(h + 30, 0.78, 1);
        c3 = hsvParaRgb(h + 60, 0.7, 1);
      } else [c1, c2, c3] = trio(tm.cores.destaque);

      const W = cv.width, H = cv.height;
      const cx = W / 2, cy = H / 2;
      // mesma proporção do celular: raio = 24% da caixa (o brilho chega às bordas sem aparecer a caixa)
      const base = Math.min(W, H) * 0.24;
      const raio = base * (1 + energia * 0.16);
      const dens = Math.max(0.5, base / 36);

      ctx.clearRect(0, 0, W, H);

      // brilho de fundo
      let g = ctx.createRadialGradient(cx, cy, 0, cx, cy, raio * 2.4);
      g.addColorStop(0, rgba(c1, 0.3 + 0.25 * energia));
      g.addColorStop(0.45, rgba(c2, 0.12));
      g.addColorStop(1, rgba(c2, 0));
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(cx, cy, raio * 2.4, 0, Math.PI * 2); ctx.fill();

      // esfera de partículas com constelações
      const r0 = raio * 1.3;
      let rotY = giro, rotX = 0.25, escala = 1, bob = 0, ondaAmp = 0;
      switch (mod) {
        case "respirar": rotY = giro * 0.6; escala = 1 + 0.06 * Math.sin(tempo * 1.3); break;
        case "giro": rotY = giro * 3.2; rotX = 0.25 + 0.15 * Math.sin(giro * 0.5); break;
        case "batida": {
          rotY = giro * 0.5;
          const ciclo = (tempo * 1.25) % 1;
          const segundo = (ciclo - 0.22 + 1) % 1;
          escala = 1 + 0.13 * (Math.exp(-ciclo * 9) + 0.7 * Math.exp(-segundo * 9));
          break;
        }
        case "onda": rotY = giro * 0.8; ondaAmp = 0.085; break;
        case "flutuar":
          rotY = giro * 0.5 + 0.5 * Math.sin(tempo * 0.6);
          rotX = 0.25 + 0.35 * Math.sin(tempo * 0.8);
          bob = r0 * 0.07 * Math.sin(tempo * 1.1);
          break;
      }
      escala *= 1 + 0.1 * energia;
      const ca = Math.cos(rotY), sa = Math.sin(rotY), cb = Math.cos(rotX), sb = Math.sin(rotX);
      const r = r0 * escala;
      for (let i = 0; i < N; i++) {
        const q = ESFERA[i]!;
        const x1 = q[0]! * ca + q[2]! * sa;
        const z1 = -q[0]! * sa + q[2]! * ca;
        const y2 = q[1]! * cb - z1 * sb;
        const z2 = q[1]! * sb + z1 * cb;
        const pulso = 1 + 0.03 * Math.sin(tempo * 1.1 + q[3]! * 9) + ondaAmp * Math.sin(tempo * 3.2 + q[1]! * 5.5);
        sx[i] = cx + x1 * r * pulso;
        sy[i] = cy + bob + y2 * r * pulso;
        sz[i] = z2;
      }
      ctx.lineWidth = 0.8 * dens;
      const lim = r * 0.2 * (r * 0.2);
      const linha = clarear(c3, 0.45);
      for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) {
        const dx = sx[i]! - sx[j]!, dy = sy[i]! - sy[j]!, d2 = dx * dx + dy * dy;
        if (d2 < lim && sz[i]! + sz[j]! > -0.4) {
          const a = (1 - d2 / lim) * (0.35 + 0.3 * energia) * ((sz[i]! + sz[j]! + 2) / 4);
          ctx.strokeStyle = rgba(linha, a);
          ctx.beginPath(); ctx.moveTo(sx[i]!, sy[i]!); ctx.lineTo(sx[j]!, sy[j]!); ctx.stroke();
        }
      }
      for (let passo = 0; passo < 2; passo++) for (let i = 0; i < N; i++) {
        const z = sz[i]!;
        if ((z >= 0) !== (passo === 1)) continue;
        const f = (z + 1) / 2;
        ctx.fillStyle = rgba(ESFERA[i]![3]! > 0.5 ? c1 : c2, 0.25 + f * 0.75);
        ctx.beginPath(); ctx.arc(sx[i]!, sy[i]!, (0.9 + f * 2.1) * dens, 0, Math.PI * 2); ctx.fill();
      }

      // núcleo luminoso
      g = ctx.createRadialGradient(cx, cy, 0, cx, cy, raio * 0.8);
      g.addColorStop(0, rgba(clarear(c2, 0.55), 0.1 + 0.3 * energia));
      g.addColorStop(0.5, rgba(c2, 0.08));
      g.addColorStop(1, rgba(c2, 0));
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(cx, cy, raio * 0.8, 0, Math.PI * 2); ctx.fill();

      // reflexo de vidro no alto, à esquerda
      const bx = cx - raio * 0.38, by = cy - raio * 0.42;
      g = ctx.createRadialGradient(bx, by, 0, bx, by, raio * 0.42);
      g.addColorStop(0, "rgba(255,255,255,0.16)");
      g.addColorStop(1, "rgba(255,255,255,0)");
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(bx, by, raio * 0.42, 0, Math.PI * 2); ctx.fill();

      raf = requestAnimationFrame(quadro);
    };
    raf = requestAnimationFrame(quadro);
    return () => cancelAnimationFrame(raf);
  }, [tamanho]);

  return (
    <canvas
      ref={ref}
      role="img"
      aria-label="Orbe da Vênus"
      className={`orbe ${className}`}
      style={{ width: tamanho, height: tamanho }}
    />
  );
}
