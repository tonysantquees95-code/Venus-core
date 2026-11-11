import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import { App } from "./App";
import { ConversasProvider } from "./sessao/ConversasProvider";
import { ThemingProvider } from "./theming";

createRoot(document.getElementById("app")!).render(
  <StrictMode>
    <ThemingProvider>
      <ConversasProvider>
        <App />
      </ConversasProvider>
    </ThemingProvider>
  </StrictMode>
);
