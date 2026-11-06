import { resolve } from "node:path";
import { defineConfig } from "vite";

export default defineConfig({
  // Relative paths, because GitHub Pages serves the page under /almucantar/.
  base: "./",
  build: {
    rollupOptions: {
      // The page of the family and the privacy policy of the apps (/privacy/), which the stores
      // link to.
      input: {
        main: resolve(__dirname, "index.html"),
        privacy: resolve(__dirname, "privacy/index.html"),
      },
    },
  },
});
