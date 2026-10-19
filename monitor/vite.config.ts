import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const engineUrl = loadEnv(mode, ".", "").ENGINE_URL || "http://localhost:8080";
  const proxy = {
    "/api": {
      target: engineUrl,
      changeOrigin: true,
      rewrite: (path: string) => path.replace(/^\/api/, ""),
    },
  };

  return {
    plugins: [react()],
    server: { proxy },
    preview: { proxy },
  };
});
