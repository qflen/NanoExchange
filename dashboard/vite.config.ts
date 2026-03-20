import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Vitest pulls its config out of here to keep a single source of
// truth for path aliases. Test runs use jsdom; component tests
// mount into a real DOM via @testing-library/react.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
  },
} as any);
