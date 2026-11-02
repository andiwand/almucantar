import { afterEach, beforeEach, expect, test, vi } from "vitest";

vi.mock("@sunradar/core", () => ({
  DayKind: {},
  load: async () => ({ magneticDeclinationDeg: () => 0 }),
}));

class Element extends EventTarget {
  textContent = "";
  classList = { add: vi.fn(), toggle: vi.fn() };
  style = {};
  srcObject: MediaStream | null = null;
  play = vi.fn(async () => {});
  getContext = () => ({});
}

let elements: Map<string, Element>;
let getCurrentPosition: ReturnType<typeof vi.fn>;
let getUserMedia: ReturnType<typeof vi.fn>;

const element = (id: string) => {
  if (!elements.has(id)) elements.set(id, new Element());
  return elements.get(id)!;
};

beforeEach(() => {
  vi.resetModules();
  elements = new Map();
  getCurrentPosition = vi.fn();
  getUserMedia = vi.fn();
  vi.stubGlobal("document", { getElementById: element });
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("navigator", {
    geolocation: { getCurrentPosition },
    mediaDevices: { getUserMedia },
  });
  vi.stubGlobal("location", { protocol: "http:" });
  vi.stubGlobal("requestAnimationFrame", vi.fn());
});

afterEach(() => vi.unstubAllGlobals());

test("location is available in browsers without orientation sensors", async () => {
  await import("../src/main");
  expect(element("start").textContent).toBe("Use location");
  element("start").dispatchEvent(new Event("click"));
  expect(getCurrentPosition).toHaveBeenCalledOnce();
});

test("denying orientation permission still allows location access", async () => {
  const orientation = { requestPermission: vi.fn(async () => "denied") };
  vi.stubGlobal("DeviceOrientationEvent", orientation);
  Object.assign(window, { DeviceOrientationEvent: orientation });
  await import("../src/main");
  element("start").dispatchEvent(new Event("click"));
  await vi.waitFor(() => expect(getCurrentPosition).toHaveBeenCalledOnce());
  expect(element("message").textContent).toBe("The orientation sensors are not allowed.");
});

test("camera requests do not overlap and failed playback releases the stream", async () => {
  const stop = vi.fn();
  let grant!: (stream: unknown) => void;
  getUserMedia.mockReturnValue(new Promise((resolve) => (grant = resolve)));
  element("camera").play.mockRejectedValue(new Error("Playback failed"));
  await import("../src/main");
  element("camera-button").dispatchEvent(new Event("click"));
  element("camera-button").dispatchEvent(new Event("click"));
  expect(getUserMedia).toHaveBeenCalledOnce();
  grant({ getTracks: () => [{ stop }] });
  await vi.waitFor(() => expect(stop).toHaveBeenCalledOnce());
  expect(element("camera").srcObject).toBeNull();
  expect(element("camera-button").textContent).toBe("Camera");
});
