import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  // Relative paths, so the app works from any directory of a web server.
  base: "./",
  // The WebAssembly module of @almucantar/core loads its .wasm next to itself; Vite must not
  // pre-bundle the package, or the URL of the .wasm file breaks.
  optimizeDeps: { exclude: ["@almucantar/core"] },
  build: { target: "es2022" },
  // The app reads files from the repository root (the WASM build of the core and the offline base
  // map in assets/basemap/) and the shared package (web/shared). Vite allows only the project
  // directory without this.
  server: { fs: { allow: [fileURLToPath(new URL("../../..", import.meta.url))] } },
  plugins: [
    {
      name: "offline-assets",
      apply: "build",
      // The post order runs this hook after the generateBundle hooks of the normal plugins. So
      // the list and the version contain the files that those plugins emit or change.
      enforce: "post",
      generateBundle(_options, bundle) {
        const template = readFileSync(new URL("./public/sw.js", import.meta.url), "utf8");
        const hash = createHash("sha256").update(template);
        for (const file of Object.values(bundle)) {
          hash.update(file.fileName);
          hash.update(file.type === "chunk" ? file.code : file.source);
        }
        for (const file of ["icon.svg", "manifest.webmanifest"]) {
          hash.update(readFileSync(new URL(`./public/${file}`, import.meta.url)));
        }
        this.emitFile({
          type: "asset",
          fileName: "sw.js",
          source: template
            .replace('"__VERSION__"', JSON.stringify(hash.digest("hex")))
            .replace(
              "const ASSETS = [];",
              `const ASSETS = ${JSON.stringify(Object.keys(bundle))};`,
            ),
        });
      },
    },
  ],
});
