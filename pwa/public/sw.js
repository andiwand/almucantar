// The service worker: the app works offline after the first visit. A page (index.html) comes
// from the network first, so a new deploy arrives at the next start; the cache is the fallback.
// The other files have a hash in their name (assets/*), so they come from the cache first.
const CACHE = "sunradar-v2";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(["./", "./index.html"])));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))),
  );
  self.clients.claim();
});

async function fetchAndStore(request) {
  const response = await fetch(request);
  if (response.ok) {
    const cache = await caches.open(CACHE);
    await cache.put(request, response.clone());
  }
  return response;
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET" || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    request.mode === "navigate"
      ? fetchAndStore(request).catch(() =>
          caches.match(request).then((cached) => cached ?? caches.match("./index.html")),
        )
      : caches.match(request).then((cached) => cached ?? fetchAndStore(request)),
  );
});
