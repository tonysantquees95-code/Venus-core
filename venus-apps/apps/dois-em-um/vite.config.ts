import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { criarConfigVite } from "@venus/build-config";

export default criarConfigVite({
  nome: "Vênus",
  nomeCurto: "Vênus",
  descricao: "Chat da Vênus e painel do Robô em um só app",
  corTema: "#EC6FD6",
  corFundo: "#08080F",
  porta: 5175,
  plugins: [react(), tailwindcss()]
});
