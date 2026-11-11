import "./estilo.css";
import { rodandoNoApp, saudeDoServidor } from "@venus/nucleo";

const app = document.querySelector<HTMLDivElement>("#app")!;
app.innerHTML = `<main><h1>VÊNUS</h1><p>Assistente pessoal e comercial</p><p id="estado">Conectando…</p></main>`;

const estado = document.querySelector<HTMLParagraphElement>("#estado")!;
saudeDoServidor()
  .then(() => (estado.textContent = rodandoNoApp() ? "Online · app" : "Online"))
  .catch(() => (estado.textContent = "Sem conexão com o servidor"));
