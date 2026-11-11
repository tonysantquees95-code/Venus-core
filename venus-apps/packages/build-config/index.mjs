// Configuração Vite compartilhada. Três modos de build:
//   web        -> site estático (base "/")
//   web + PWA  -> o mesmo build, com service worker e manifest (padrão do modo "production")
//   capacitor  -> dentro do APK (base "./", sem service worker)
import { defineConfig } from "vite";
import { VitePWA } from "vite-plugin-pwa";

export function criarConfigVite({ nome, nomeCurto, descricao, corTema, corFundo, porta, plugins = [] }) {
  return defineConfig(({ mode }) => {
    const nativo = mode === "capacitor";
    return {
      base: nativo ? "./" : "/",
      server: { port: porta, strictPort: true, host: true },
      build: { outDir: "dist", sourcemap: !nativo, target: "es2020" },
      plugins: [
        ...plugins,
        !nativo &&
          VitePWA({
            registerType: "autoUpdate",
            includeAssets: ["icons/*.png"],
            manifest: {
              name: nome,
              short_name: nomeCurto,
              description: descricao,
              lang: "pt-BR",
              start_url: "/",
              scope: "/",
              display: "standalone",
              orientation: "portrait",
              theme_color: corTema,
              background_color: corFundo,
              icons: [
                { src: "icons/pwa-192.png", sizes: "192x192", type: "image/png" },
                { src: "icons/pwa-512.png", sizes: "512x512", type: "image/png" },
                { src: "icons/pwa-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" }
              ]
            },
            workbox: { navigateFallback: "/index.html", globPatterns: ["**/*.{js,css,html,png,svg,woff2}"] }
          })
      ].filter(Boolean)
    };
  });
}
