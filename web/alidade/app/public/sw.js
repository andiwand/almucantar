// Vite fills the asset list and version. Install the whole build before it can serve pages,
// including the scripts and WebAssembly that loaded before the worker took control.
const ASSETS = [];
const PREFIX = `alidade:${self.registration.scope}:`;
const CACHE = PREFIX + "__VERSION__";

// The worker does not call skipWaiting. So it activates only when no page of the old build is
// open, and an old page does not lose its cached assets.
self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) =>
        cache.addAll([
          ...new Set([
            "./",
            "./index.html",
            "./icon.svg",
            "./manifest.webmanifest",
            ...ASSETS.map((path) => `./${path}`),
          ]),
        ]),
      ),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((k) => k.startsWith(PREFIX) && k !== CACHE).map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET" || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      if (request.mode === "navigate") {
        // The page comes from the network first, so a new deploy arrives at the next start.
        // Offline, the cached page of this build loads the cached assets of the same build.
        try {
          return await fetch(request);
        } catch {
          const cached = await cache.match("./index.html");
          if (cached) return cached;
          throw new Error("offline and no cached page");
        }
      }
      return (await cache.match(request)) ?? fetch(request);
    }),
  );
});
