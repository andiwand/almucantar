import { defineConfig } from "vite";

export default defineConfig({
  // Relative paths, so the app works from any directory of a web server.
  base: "./",
  // The WebAssembly module of @sunradar/core loads its .wasm next to itself; Vite must not
  // pre-bundle the package, or the URL of the .wasm file breaks.
  optimizeDeps: { exclude: ["@sunradar/core"] },
  build: { target: "es2022" },
});
