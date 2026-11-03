// The place panel of the apps: the offline map (Natural Earth), the search, "Use my location",
// and "Keep offline" (the terrain of the place for a trip). It works without a network.
//
// The markup is in the index.html of each app, with the ids that this module reads (place-panel,
// place-map, place-search, place-results, place-clear, place-close, place-here, place-zoom-in,
// place-zoom-out, place-title, place-subtitle, place-keep, place-keep-status, place-kept) and a
// button place-button that opens the panel. The styles are in placePanel.css.
import type { Observer } from "@almucantar/core";
import {
  decodeBaseMap,
  drawBaseMap,
  fromWorld,
  nearestPlace,
  screenToPlace,
  searchPlaces,
  timeZoneAt,
  toWorld,
} from "./basemap";
import type { BaseMap, MapView } from "./basemap";
import { gpsPlace } from "./place";
import type { StoredPlace } from "./place";
import { keepOffline } from "./terrain";
import type { TerrainDeps, TerrainState, TileCache } from "./terrain";
import { browserTimeZone } from "./zone";
import "./placePanel.css";

export interface PlacePanelDeps {
  /** The core, for the tiles of "Keep offline". */
  almucantar: Pick<TerrainDeps["almucantar"], "mapTiles">;
  tileCache: TileCache;
  /** The URL of assets/basemap/basemap.json, for example from an import with "?url". */
  baseMapUrl: string;
  /** The place of the app now: the observer, the name, and the time zone. */
  current(): { observer: Observer; name: string; zone: string };
  /** False while the app has no place yet; "Keep offline" needs one. */
  hasPlace(): boolean;
  /** The terrain of the place: "Keep offline" keeps the fine tiles only where it matters. */
  terrainState(): TerrainState;
  /** Changes the place of the app. The app then calls refresh() of the panel. */
  setPlace(next: StoredPlace): void;
  showMessage(text: string): void;
}

export interface PlacePanel {
  /** Shows the place again, after a change of the place. */
  refresh(): void;
}

/** Connects the place panel in the document to the app. */
export function setupPlacePanel(deps: PlacePanelDeps): PlacePanel {
  const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

  let baseMap: BaseMap | null = null;
  const start = deps.current().observer;
  let mapView: MapView = { lon: start.longitudeDeg, lat: start.latitudeDeg, zoom: 5 };
  const mapCanvas = $<HTMLCanvasElement>("place-map");
  const searchInput = $<HTMLInputElement>("place-search");
  const results = $("place-results");

  async function loadBaseMap(): Promise<BaseMap | null> {
    if (!baseMap) {
      try {
        baseMap = decodeBaseMap(await (await fetch(deps.baseMapUrl)).json());
      } catch {
        deps.showMessage("The map is not available.");
      }
    }
    return baseMap;
  }

  function drawMap() {
    if (!baseMap || mapCanvas.clientWidth === 0) return;
    const dpr = window.devicePixelRatio || 1;
    const width = mapCanvas.clientWidth;
    const height = mapCanvas.clientHeight;
    mapCanvas.width = Math.round(width * dpr);
    mapCanvas.height = Math.round(height * dpr);
    const c = mapCanvas.getContext("2d")!;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    drawBaseMap(c, baseMap, mapView, width, height, deps.current().observer);
  }

  /** Drops the place at `at`, with the name of the nearest place and its time zone. */
  function dropPlace(at: Observer, name?: string) {
    if (!baseMap) return;
    const near = nearestPlace(baseMap.places, at);
    const coordinates = `${at.latitudeDeg.toFixed(3)}°, ${at.longitudeDeg.toFixed(3)}°`;
    deps.setPlace({
      observer: at,
      name: `${name ?? (near ? `near ${near.name}` : coordinates)} · ${coordinates}`,
      timeZone: timeZoneAt(baseMap.places, at, browserTimeZone()),
      manual: true,
    });
    drawMap();
  }

  /** The card at the bottom of the panel: the name of the place, its coordinates, and its zone. */
  function renderPlaceCard() {
    const { observer, name: placeText, zone } = deps.current();
    // A dropped place has the name "near X · coordinates": the card shows the coordinates below.
    $("place-title").textContent = placeText.split(" · ")[0] || "Your location";
    // The readable name of the zone ("Central European Standard Time"), not the IANA ID.
    const zoneName =
      new Intl.DateTimeFormat(undefined, { timeZone: zone, timeZoneName: "long" })
        .formatToParts(new Date())
        .find((part) => part.type === "timeZoneName")?.value ?? zone;
    $("place-subtitle").textContent =
      `${observer.latitudeDeg.toFixed(3)}°, ${observer.longitudeDeg.toFixed(3)}° · ${zoneName}`;
  }

  function centerMap() {
    const observer = deps.current().observer;
    mapView = { ...mapView, lon: observer.longitudeDeg, lat: observer.latitudeDeg };
    drawMap();
  }

  $("place-button").addEventListener("click", async () => {
    $("place-panel").classList.remove("hidden");
    renderPlaceCard();
    renderKept();
    mapView = { ...mapView, zoom: 7 };
    centerMap();
    if (await loadBaseMap()) drawMap();
  });
  $("place-close").addEventListener("click", () => $("place-panel").classList.add("hidden"));
  $("place-here").addEventListener("click", () => {
    navigator.geolocation?.getCurrentPosition(
      (position) => {
        deps.setPlace(gpsPlace(position));
        centerMap();
      },
      () => deps.showMessage("No location: the place stays."),
      { enableHighAccuracy: false, timeout: 20000 },
    );
  });
  window.addEventListener("resize", () => {
    if (!$("place-panel").classList.contains("hidden")) drawMap();
  });
  for (const [id, step] of [
    ["place-zoom-in", 1],
    ["place-zoom-out", -1],
  ] as const) {
    $(id).addEventListener("click", () => {
      mapView = { ...mapView, zoom: Math.max(1, Math.min(12, mapView.zoom + step)) };
      drawMap();
    });
  }
  const PIN_ICON =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21c-4-5-6.5-8.4-6.5-11.5a6.5 6.5 0 0 1 13 0C18.5 12.6 16 16 12 21z" /><circle cx="12" cy="9.5" r="2" class="fill" /></svg>';

  function showResults() {
    results.replaceChildren();
    $("place-clear").classList.toggle("hidden", searchInput.value === "");
    const found = baseMap ? searchPlaces(baseMap.places, searchInput.value) : [];
    results.classList.toggle("hidden", found.length === 0);
    for (const p of found) {
      const item = document.createElement("button");
      item.innerHTML = PIN_ICON;
      const text = document.createElement("span");
      const name = document.createElement("span");
      name.className = "name";
      name.textContent = p.name;
      const country = document.createElement("span");
      country.className = "country";
      country.textContent = p.country;
      text.append(name, country);
      item.append(text);
      item.addEventListener("click", () => {
        mapView = { lon: p.lon, lat: p.lat, zoom: Math.max(mapView.zoom, 9) };
        dropPlace({ latitudeDeg: p.lat, longitudeDeg: p.lon }, p.name);
        searchInput.value = "";
        searchInput.blur();
        showResults();
      });
      results.append(item);
    }
  }
  searchInput.addEventListener("input", showResults);
  $("place-clear").addEventListener("click", () => {
    searchInput.value = "";
    showResults();
    searchInput.focus();
  });

  // Drag to move the map, tap to drop the place, and the wheel to zoom.
  let mapDrag: { x: number; y: number; moved: boolean } | null = null;
  mapCanvas.addEventListener("pointerdown", (e) => {
    mapDrag = { x: e.clientX, y: e.clientY, moved: false };
    mapCanvas.setPointerCapture(e.pointerId);
  });
  mapCanvas.addEventListener("pointermove", (e) => {
    if (!mapDrag) return;
    const dx = e.clientX - mapDrag.x;
    const dy = e.clientY - mapDrag.y;
    if (!mapDrag.moved && Math.hypot(dx, dy) < 5) return;
    const [x, y] = toWorld(mapView.lon, mapView.lat, mapView.zoom);
    const [lon, lat] = fromWorld(x - dx, y - dy, mapView.zoom);
    mapView = { ...mapView, lon, lat };
    mapDrag = { x: e.clientX, y: e.clientY, moved: true };
    drawMap();
  });
  mapCanvas.addEventListener("pointerup", (e) => {
    if (mapDrag && !mapDrag.moved) {
      const rect = mapCanvas.getBoundingClientRect();
      dropPlace(
        screenToPlace(
          mapView,
          rect.width,
          rect.height,
          e.clientX - rect.left,
          e.clientY - rect.top,
        ),
      );
    }
    mapDrag = null;
  });
  // Without this, a cancelled touch leaves the drag on, and the mouse then moves the map.
  mapCanvas.addEventListener("pointercancel", () => (mapDrag = null));
  mapCanvas.addEventListener(
    "wheel",
    (e) => {
      e.preventDefault();
      mapView = {
        ...mapView,
        zoom: Math.max(1, Math.min(12, mapView.zoom - Math.sign(e.deltaY) * 0.5)),
      };
      drawMap();
    },
    { passive: false },
  );

  // "Keep offline": keep the terrain of the place for a trip.

  const keepButton = $<HTMLButtonElement>("place-keep");
  const keepStatus = $("place-keep-status");
  const keptList = $("place-kept");
  let keeping = false;

  // 1 MB is 1024 × 1024 bytes, as in the apps and in MAX_CACHE_BYTES.
  const megabytes = (bytes: number) => `${Math.round(bytes / (1024 * 1024))} MB`;

  function renderKept() {
    keptList.replaceChildren();
    for (const kept of deps.tileCache.kept) {
      const row = document.createElement("div");
      row.className = "kept";
      const text = document.createElement("span");
      text.textContent = kept.name;
      const size = document.createElement("small");
      size.textContent = `${kept.urls.length} tiles, ${megabytes(deps.tileCache.bytesOf(kept.urls))}`;
      text.append(size);
      const remove = document.createElement("button");
      remove.textContent = "Remove";
      remove.addEventListener("click", () => {
        deps.tileCache.release(kept.id);
        renderKept();
      });
      row.append(text, remove);
      keptList.append(row);
    }
    keepButton.disabled = keeping || !deps.hasPlace();
  }

  keepButton.addEventListener("click", async () => {
    if (keeping || !deps.hasPlace()) return;
    keeping = true;
    renderKept();
    // Ask the browser not to clear the storage of the app when the disk gets full.
    void navigator.storage?.persist?.();
    const { observer, name: placeText } = deps.current();
    const name = placeText.split(" · ")[0];
    try {
      // The fine tiles only where the terrain matters, as the terrain of this place decided.
      // Without a decision (the terrain is off or not ready), keep all of them.
      const state = deps.terrainState();
      const fine =
        state.kind === "ready" &&
        state.observer.latitudeDeg === observer.latitudeDeg &&
        state.observer.longitudeDeg === observer.longitudeDeg
          ? state.fine
          : true;
      const result = await keepOffline(
        deps.almucantar,
        deps.tileCache,
        observer,
        name,
        (done, total) => {
          keepStatus.textContent = `Downloading ${done}/${total}`;
        },
        fine,
      );
      keepStatus.textContent = result.missing
        ? `${result.missing} tiles missing, try again online`
        : `Ready offline: ${result.tiles} tiles, ${megabytes(result.bytes)}`;
    } catch (e) {
      // For example, the storage is full.
      keepStatus.textContent = `Download stopped: ${e instanceof Error ? e.message : String(e)}`;
    } finally {
      keeping = false;
      renderKept();
    }
  });

  return {
    refresh() {
      renderPlaceCard();
      // The panel can be open, and "Keep offline" needs a place.
      renderKept();
    },
  };
}
