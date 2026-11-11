import { useEffect } from "react";
import { ponteDesktop } from "./ponte";

/** Atalho global do Windows (Ctrl+Alt+V): o app traz a janela para a frente e avisa aqui para focar o chat. */
export function useAtalhoGlobal(aoAcionar: () => void) {
  useEffect(() => ponteDesktop()?.aoAtalhoGlobal(aoAcionar), [aoAcionar]);
}
