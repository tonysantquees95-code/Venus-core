// Copia o build do Dois em Um (modo desktop) para dentro do app Windows.
import { cpSync, existsSync, rmSync } from "node:fs";
const origem = new URL("../apps/dois-em-um/dist-desktop/", import.meta.url);
const destino = new URL("./app/", import.meta.url);
if (!existsSync(origem)) {
  console.error("Falta o build: rode  npm run build:desktop -w @venus/dois-em-um  na pasta venus-apps.");
  process.exit(1);
}
rmSync(destino, { recursive: true, force: true });
cpSync(origem, destino, { recursive: true });
console.log("App copiado para desktop/app");
