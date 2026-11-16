import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import { App } from "./App";
import { ConversasProvider } from "./sessao/ConversasProvider";
import { ThemingProvider } from "./theming";
import { IdProvider } from "./id/IdProvider";
import { EmpresaProvider } from "./empresa/EmpresaProvider";
import { LojasProvider } from "./empresa/LojasProvider";
import { MarketingProvider } from "./empresa/MarketingProvider";
import { CatalogoProvider } from "./empresa/CatalogoProvider";
import { FormulariosDaLojaProvider } from "./empresa/FormulariosDaLoja";
import { PreferenciasProvider } from "./ajustes/Preferencias";

// iOS/Safari: pede armazenamento persistente para o histórico e as preferências não serem apagados pelo sistema.
try {
  void navigator.storage?.persist?.();
} catch {
  /* sem suporte */
}

createRoot(document.getElementById("app")!).render(
  <StrictMode>
    <ThemingProvider>
      <PreferenciasProvider>
        <IdProvider>
          <LojasProvider>
          <EmpresaProvider>
          <MarketingProvider>
          <CatalogoProvider>
            <ConversasProvider>
              <FormulariosDaLojaProvider>
                <App />
              </FormulariosDaLojaProvider>
            </ConversasProvider>
          </CatalogoProvider>
          </MarketingProvider>
          </EmpresaProvider>
          </LojasProvider>
        </IdProvider>
      </PreferenciasProvider>
    </ThemingProvider>
  </StrictMode>
);
