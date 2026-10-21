import { defineConfig } from "vitest/config";
export default defineConfig({
  server: {
    proxy: {
      "/api": {
        target: process.env.API_PROXY || "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/setup.ts"],
    exclude: ["e2e/**", "node_modules/**"],
  },
});
