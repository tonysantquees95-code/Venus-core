import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.vetorialtech.venus.doisemum",
  appName: "Dois em Um",
  webDir: "dist",
  android: { allowMixedContent: false },
  server: { androidScheme: "https" }
};

export default config;
