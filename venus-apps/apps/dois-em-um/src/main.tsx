import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import { App } from "./App";
import { ConversasProvider } from "./sessao/ConversasProvider";
import { ThemingProvider } from "./theming";
import { IdProvider } from "./id/IdProvider";
import { EmpresaProvider } from "./empresa/EmpresaProvider";
import { MarketingProvider } from "./empresa/MarketingProvider";
import { CatalogoProvider } from "./empresa/CatalogoProvider";
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
          <EmpresaProvider>
          <MarketingProvider>
          <CatalogoProvider>
            <ConversasProvider>
              <App />
            </ConversasProvider>
          </CatalogoProvider>
          </MarketingProvider>
          </EmpresaProvider>
        </IdProvider>
      </PreferenciasProvider>
    </ThemingProvider>
  </StrictMode>
);
