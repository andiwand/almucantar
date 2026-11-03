import { defineConfig } from "vite";

export default defineConfig({
  // Relative paths, because GitHub Pages serves the website under /almucantar/sunradar/.
  base: "./",
  // The WebAssembly module of @almucantar/core loads its .wasm next to itself; Vite must not
  // pre-bundle the package, or the URL of the .wasm file breaks.
  optimizeDeps: { exclude: ["@almucantar/core"] },
  build: { target: "es2022" },
  // The dev server reads the WASM build of the core from the repository root. Vite allows only the
  // project directory without this, and the sky then does not start.
  server: { fs: { allow: ["../../.."] } },
});
