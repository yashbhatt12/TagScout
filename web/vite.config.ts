import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Firebase alone is ~500 kB minified; pages are already split per route.
    chunkSizeWarningLimit: 900,
  },
});
