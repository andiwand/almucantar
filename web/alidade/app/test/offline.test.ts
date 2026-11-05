import { runInNewContext } from "node:vm";
import { build } from "vite";
import { expect, test, vi } from "vitest";

test("the first worker install caches the whole build and preserves other apps", async () => {
  const result = await build({ build: { write: false }, logLevel: "silent" });
  if (Array.isArray(result) || !("output" in result)) throw new Error("Expected one build");
  const worker = result.output.find((file) => file.fileName === "sw.js");
  if (worker?.type !== "asset") throw new Error("Missing service worker");

  const scope = "https://example.test/alidade/app/";
  const prefix = `alidade:${scope}:`;
  const stored = new Map<string, Response>();
  const cache = {
    addAll: vi.fn(async (paths: string[]) => {
      const urls = paths.map((path) => new URL(path, scope).href);
      // Cache.addAll rejects duplicate requests.
      expect(new Set(urls).size).toBe(urls.length);
      for (const url of urls) stored.set(url, new Response(url));
    }),
    match: vi.fn(async (request: string | Request) =>
      stored.get(typeof request === "string" ? new URL(request, scope).href : request.url),
    ),
  };
  const caches = {
    open: vi.fn(async () => cache),
    keys: vi.fn(async () => [
      `${prefix}old`,
      "another-app",
      "alidade:https://example.test/other/:old",
      "sternstunde:https://example.test/sternstunde/app/:old",
    ]),
    delete: vi.fn(async () => true),
  };
  const listeners = new Map<string, (event: unknown) => void>();
  const skipWaiting = vi.fn();
  const claim = vi.fn();
  const fetch = vi.fn(async () => {
    throw new Error("Offline");
  });
  runInNewContext(String(worker.source), {
    URL,
    caches,
    fetch,
    self: {
      registration: { scope },
      location: new URL("sw.js", scope),
      addEventListener: (type: string, listener: (event: unknown) => void) =>
        listeners.set(type, listener),
      skipWaiting,
      clients: { claim },
    },
  });
  const dispatch = (type: string) => {
    let done: Promise<unknown> | undefined;
    listeners.get(type)!({
      waitUntil: (promise: Promise<unknown>) => (done = promise),
    });
    return done;
  };
  await dispatch("install");
  expect(skipWaiting).not.toHaveBeenCalled();
  for (const file of result.output.filter((file) => file.fileName !== "sw.js")) {
    expect(stored.has(new URL(file.fileName, scope).href), file.fileName).toBe(true);
  }
  expect(result.output.some((file) => file.fileName.endsWith(".wasm"))).toBe(true);
  expect(result.output.some((file) => /basemap-.*\.json$/.test(file.fileName))).toBe(true);

  await dispatch("activate");
  expect(caches.delete.mock.calls).toEqual([[`${prefix}old`]]);
  expect(claim).toHaveBeenCalledOnce();

  // A later offline visit can load the page and every generated asset.
  for (const file of result.output.filter((file) => file.fileName !== "sw.js")) {
    let response: Promise<Response> | undefined;
    listeners.get("fetch")!({
      request: {
        method: "GET",
        mode: file.fileName === "index.html" ? "navigate" : "cors",
        url: new URL(file.fileName, scope).href,
      },
      respondWith: (promise: Promise<Response>) => (response = promise),
    });
    expect((await response)?.ok, file.fileName).toBe(true);
  }
  // Only the page tries the network first.
  expect(fetch).toHaveBeenCalledOnce();
});
