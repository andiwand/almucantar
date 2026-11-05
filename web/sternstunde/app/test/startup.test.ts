import { afterEach, beforeEach, expect, test, vi } from "vitest";

vi.mock("@almucantar/core", () => ({
  GALACTIC_CENTER: { longitudeDeg: 0, latitudeDeg: 0 },
  Planet: { mercury: 0, venus: 1, mars: 2, jupiter: 3, saturn: 4, uranus: 5, neptune: 6 },
  load: async () => ({ magneticDeclinationDeg: () => 0 }),
}));

class Element extends EventTarget {
  textContent = "";
  checked = false;
  value = "";
  dataset: Record<string, string> = {};
  hidden = new Set<string>();
  classList = {
    add: vi.fn((name: string) => void this.hidden.add(name)),
    remove: vi.fn((name: string) => void this.hidden.delete(name)),
    toggle: vi.fn((name: string, force?: boolean) => {
      const on = force ?? !this.hidden.has(name);
      if (on) this.hidden.add(name);
      else this.hidden.delete(name);
    }),
    contains: (name: string) => this.hidden.has(name),
  };
  style = {};
  srcObject: MediaStream | null = null;
  play = vi.fn(async () => {});
  focus = vi.fn();
  getContext = () => ({});
}

// The smallest stars.json: one star, one name, and one constellation.
const starsJson = {
  version: 1,
  stars: { ra: [101.3], dec: [-16.7], pm_ra: [0], pm_dec: [0], mag: [-1.46], bv: [0], hr: [2491] },
  names: [[0, "Sirius"]],
  constellations: [{ id: "CMa", name: "Canis Major", label: [104, -22], lines: [] }],
  attribution: "Stars: test.",
};

let elements: Map<string, Element>;
let stored: Map<string, string>;
let getCurrentPosition: ReturnType<typeof vi.fn>;

const element = (id: string) => {
  if (!elements.has(id)) elements.set(id, new Element());
  return elements.get(id)!;
};

beforeEach(() => {
  vi.resetModules();
  elements = new Map();
  stored = new Map();
  getCurrentPosition = vi.fn();
  vi.stubGlobal("document", { getElementById: element });
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("navigator", {
    geolocation: { getCurrentPosition },
    mediaDevices: { getUserMedia: vi.fn() },
  });
  vi.stubGlobal("location", { protocol: "http:" });
  vi.stubGlobal("requestAnimationFrame", vi.fn());
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(starsJson))),
  );
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => stored.get(key) ?? null,
    setItem: (key: string, value: string) => void stored.set(key, value),
  });
});

afterEach(() => vi.unstubAllGlobals());

test("the app starts, and location is available without orientation sensors", async () => {
  await import("../src/main");
  expect(element("start").textContent).toBe("Use location");
  expect(element("credits").textContent).toContain("Stars: test.");
  element("start").dispatchEvent(new Event("click"));
  expect(getCurrentPosition).toHaveBeenCalledOnce();
});

test("the red light and the constellation lines keep their setting", async () => {
  stored.set("sternstunde-night", "true");
  stored.set("sternstunde-lines-on", "false");
  await import("../src/main");
  expect(element("night").classList.contains("hidden")).toBe(false);
  expect(element("night-on").checked).toBe(true);
  expect(element("lines-on").checked).toBe(false);

  element("night-on").checked = false;
  element("night-on").dispatchEvent(new Event("change"));
  expect(element("night").classList.contains("hidden")).toBe(true);
  expect(stored.get("sternstunde-night")).toBe("false");
});

test("the alignment needs the orientation sensors", async () => {
  await import("../src/main");
  element("align-button").dispatchEvent(new Event("click"));
  expect(element("message").textContent).toContain("needs the orientation sensors");
});
