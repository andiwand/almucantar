import { defineConfig } from "vite";

export default defineConfig({
  // Relative paths, because GitHub Pages serves the website under /sunradar/.
  base: "./",
  // The WebAssembly module of @sunradar/core loads its .wasm next to itself; Vite must not
  // pre-bundle the package, or the URL of the .wasm file breaks.
  optimizeDeps: { exclude: ["@sunradar/core"] },
  build: { target: "es2022" },
});
