import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, "..", "");
  const backend = "http://localhost:" + (env.API_PORT || "8080");
  return {
    plugins: [react()],
    server: {
      proxy: {
        "/api": {
          target: backend,
          headers: { "X-QueryFence-Local": env.LOCAL_DEMO_SECRET || "" },
        },
        "/swagger-ui": backend,
        "/v3/api-docs": backend,
      },
    },
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test-setup.ts"],
      restoreMocks: true,
    },
  };
});
