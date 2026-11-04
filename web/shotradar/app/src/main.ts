// The Shotradar PWA, the photo planner (PLAN.md 14.1). Every way through the app ends in the shot:
// the first-person photo from the spot, with the terrain, the landmark, and the subject.
//
// - "I have a landmark": the subject, the landmark on the map, then "I know my spot" (the dates)
//   or "Find me a spot" (a moment, then the line of the spots on the map).
// - "From where I am": the place and the days, then the sky of the place (each subject over the
//   real horizon) and the shots behind the peaks of its horizon.
//
// The core (@almucantar/core, WebAssembly) calculates; this module is the screens.

import { Body, load } from "@almucantar/core";
import type { AlignmentLine, LinePoint, Observer, Site } from "@almucantar/core";
import {
  decodeBaseMap,
  nearestPlace,
  searchPlaces,
  timeZoneAt,
} from "@almucantar/web-shared/basemap";
import type { BaseMap, MapView } from "@almucantar/web-shared/basemap";
import { milkyWayDust } from "@almucantar/web-shared/milkyway";
import { discCoverage, drawPhoto, lensSpanDeg } from "@almucantar/web-shared/photo";
import type { PhotoFrame } from "@almucantar/web-shared/photo";
import { decodeStars, limitingMagnitude } from "@almucantar/web-shared/stars";
import type { Stars, StarsJson } from "@almucantar/web-shared/stars";
import { browserTimeZone, fromWallTime, wallTime } from "@almucantar/web-shared/zone";
import baseMapUrl from "../../../../assets/basemap/basemap.json?url";
import starsUrl from "../../../../assets/stars/stars.json?url";
import { computeHorizon, profileAltitude } from "./horizon";
import type { Horizon } from "./horizon";
import { MapCanvas, destination } from "./mapview";
import {
  ECLIPSE_NAMES,
  SUBJECTS,
  datesFor,
  localEclipse,
  lunarUmbra,
  upcomingEclipses,
  light,
  momentsFor,
  noMatchReason,
  peakTargets,
  shotsFromPlace,
  skyFromPlace,
  subjectOf,
} from "./search";
import type { Eclipse, Shot, SubjectId, Target } from "./search";
import { groundAt, gridsAround, metersPerPixel, summitNear } from "./tiles";

const almucantar = await load();

// --- State --------------------------------------------------------------------------------------

/** A place that the user tapped, with the height of the ground once it is known. */
interface Point {
  latitudeDeg: number;
  longitudeDeg: number;
  name: string;
  groundM: number | null;
}

interface Saved {
  subject: SubjectId;
  landmark: Point | null;
  extraM: number;
  spot: Point | null;
  view: MapView;
  /** The days of "From where I am": the first day (yyyy-mm-dd), or null for now. */
  fromDay: string | null;
  days: number;
}

const STATE_KEY = "shotradar-state-v2";
const EYE_HEIGHT_M = 1.6;
const LINE_DISTANCE_M = 30_000;
const GRID_ZOOM = 11;
const LENSES = [24, 50, 135, 300, 600];

/**
 * The ground before a landmark that is the landmark itself, for lineOfSight(): the DEM smooths a
 * summit, so its flanks just below the top can hide the top. 8 % of the distance, 300 m to 1.5 km.
 */
const targetMargin = (distanceM: number) => ({
  targetMarginM: Math.min(1500, Math.max(300, 0.08 * distanceM)),
});

function loadState(): Saved {
  const fallback: Saved = {
    subject: "moon",
    landmark: null,
    extraM: 0,
    spot: null,
    view: { lon: 11.39, lat: 47.27, zoom: 10 },
    fromDay: null,
    days: 14,
  };
  try {
    const saved = JSON.parse(localStorage.getItem(STATE_KEY) ?? "null") as Saved | null;
    if (saved && Number.isFinite(saved.view?.zoom)) return { ...fallback, ...saved };
  } catch {
    // A damaged entry: start again.
  }
  return fallback;
}

const state = loadState();
const save = () => localStorage.setItem(STATE_KEY, JSON.stringify(state));
const subject = () => subjectOf(state.subject);
const observerOf = (p: Point | Observer): Observer => ({
  latitudeDeg: p.latitudeDeg,
  longitudeDeg: p.longitudeDeg,
});
const siteOf = (p: Point, aboveM: number): Site | null =>
  p.groundM === null ? null : { ...observerOf(p), elevationM: p.groundM + aboveM };

let baseMap: BaseMap | null = null;
/** The time zone of a place: all dates and times of a shot use the zone of its spot. */
const zoneAt = (at: Observer | null) =>
  at && baseMap ? timeZoneAt(baseMap.places, at, browserTimeZone()) : browserTimeZone();
const nameNear = (at: Observer) => (baseMap ? (nearestPlace(baseMap.places, at)?.name ?? "") : "");

// --- Formatting ---------------------------------------------------------------------------------

function formatTime(unix: number, zone: string, parts: "date" | "time" | "both" = "both") {
  return new Intl.DateTimeFormat(undefined, {
    timeZone: zone,
    weekday: parts === "time" ? undefined : "short",
    day: parts === "time" ? undefined : "numeric",
    month: parts === "time" ? undefined : "short",
    year: parts === "time" ? undefined : "numeric",
    hour: parts === "date" ? undefined : "2-digit",
    minute: parts === "date" ? undefined : "2-digit",
  }).format(new Date(unix * 1000));
}

const km = (m: number) => `${(m / 1000).toFixed(m < 10_000 ? 2 : 1)} km`;
const deg = (d: number, digits = 1) => `${d.toFixed(digits)}°`;
const escape = (s: string) =>
  s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
const isoDay = (zone: string) => {
  const w = wallTime(zone, Date.now());
  return `${w.year}-${String(w.month).padStart(2, "0")}-${String(w.day).padStart(2, "0")}`;
};

/** A small picture of a subject, like the label on a box of film. */
function icon(id: SubjectId | "eclipse"): string {
  switch (id) {
    case "eclipse":
      return `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="15" fill="#f0f2ff" opacity="0.25"/><circle cx="20" cy="20" r="10" fill="#fffaf0"/><circle cx="22.5" cy="19" r="10" fill="#1d1426"/></svg>`;
    case "moon":
      return `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="11" fill="#fff3d6"/><circle cx="16" cy="17" r="2.5" fill="#e9dcc0"/><circle cx="23" cy="23" r="3" fill="#e9dcc0"/></svg>`;
    case "milkyway":
      return `<svg viewBox="0 0 40 40"><path d="M4 30 C14 22 26 18 36 8" stroke="#e7dcf2" stroke-width="7" stroke-linecap="round" opacity="0.45" fill="none"/><g fill="#fff3d6"><circle cx="10" cy="26" r="1.2"/><circle cx="18" cy="20" r="1.6"/><circle cx="25" cy="16" r="1"/><circle cx="31" cy="11" r="1.3"/><circle cx="14" cy="14" r="0.9"/><circle cx="28" cy="25" r="0.9"/></g></svg>`;
    case "sun": {
      const rays = [0, 45, 90, 135, 180, 225, 270, 315]
        .map((a) => {
          const r = (a * Math.PI) / 180;
          return `<line x1="${20 + 12 * Math.cos(r)}" y1="${20 + 12 * Math.sin(r)}" x2="${20 + 16 * Math.cos(r)}" y2="${20 + 16 * Math.sin(r)}"/>`;
        })
        .join("");
      return `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="8" fill="#ffd9a0"/><g stroke="#ffd9a0" stroke-width="2" stroke-linecap="round">${rays}</g></svg>`;
    }
    case "saturn":
      return `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="7" fill="#f6dcb0"/><ellipse cx="20" cy="20" rx="15" ry="4.5" stroke="#f6dcb0" stroke-width="1.6" fill="none" transform="rotate(-18 20 20)"/></svg>`;
    default: {
      // A planet: a bright point with a soft glow, in its color.
      const color = { venus: "#fffcec", jupiter: "#fff0d6", mars: "#ffaa78" }[id] ?? "#fff3d6";
      return `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="12" fill="${color}" opacity="0.18"/><circle cx="20" cy="20" r="6" fill="${color}"/></svg>`;
    }
  }
}

// --- Elements and navigation --------------------------------------------------------------------

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const page = $("page");
const mapScreen = $("map-screen");
const mapCard = $("map-card");
const map = new MapCanvas($<HTMLCanvasElement>("map"), state.view);
map.onView = (view) => {
  state.view = { ...view };
  save();
};

type Screen =
  | { kind: "home" }
  | { kind: "subject" }
  | { kind: "landmark" }
  | { kind: "from" }
  | { kind: "spot"; purpose: "dates" | "place" | "eclipse" }
  | { kind: "eclipses" }
  | { kind: "eclipse"; event: Eclipse }
  | { kind: "dates" }
  | { kind: "moments" }
  | { kind: "spots"; moment: number }
  | { kind: "when" }
  | { kind: "place" }
  | { kind: "shot"; shot: Shot }
  | { kind: "shot-map"; shot: Shot };

/** The steps of each way, for the strip of film at the top. */
const TRAILS = {
  landmark: ["Subject", "Landmark", "From where", "Shots"],
  place: ["Place", "Days", "Shots"],
  eclipse: ["Eclipses", "The map", "Shot"],
};
const TRAIL_INDEX = {
  landmark: { subject: 0, landmark: 1, from: 2, spot: 2, moments: 2, spots: 3, dates: 3 } as Record<
    string,
    number
  >,
  place: { spot: 0, when: 1, place: 2 } as Record<string, number>,
  eclipse: { eclipses: 0, spot: 0, eclipse: 1 } as Record<string, number>,
};

let stack: Screen[] = [];
let flow: "landmark" | "place" | "eclipse" = "landmark";

function go(screen: Screen) {
  stack.push(screen);
  history.pushState(stack.length, "");
  render();
}

window.addEventListener("popstate", () => {
  if (stack.length > 1) stack.pop();
  render();
});
$("back").addEventListener("click", () => history.back());
$("home").addEventListener("click", () => {
  stack = [{ kind: "home" }];
  render();
});

function trail(screen: Screen) {
  const steps = screen.kind === "home" ? [] : TRAILS[flow];
  const at =
    screen.kind === "shot" || screen.kind === "shot-map"
      ? steps.length - 1
      : (TRAIL_INDEX[flow][screen.kind] ?? -1);
  $("trail").innerHTML = steps
    .map(
      (s, i) =>
        `<li class="${i < at ? "done" : i === at ? "active" : ""}"><i>${String(i + 1).padStart(2, "0")}</i><span>${s}</span></li>`,
    )
    .join("");
  $("back").classList.toggle("hidden", stack.length <= 1);
}

let renderGeneration = 0;

function render() {
  const screen = stack[stack.length - 1];
  renderGeneration++;
  trail(screen);
  const onMap = ["landmark", "spot", "spots", "shot-map", "eclipse"].includes(screen.kind);
  mapScreen.classList.toggle("hidden", !onMap);
  page.classList.toggle("hidden", onMap);
  document.body.classList.toggle("on-map", onMap);
  window.scrollTo(0, 0);
  switch (screen.kind) {
    case "home":
      return home();
    case "subject":
      return subjectScreen();
    case "landmark":
      return landmarkScreen();
    case "from":
      return fromScreen();
    case "spot":
      return spotScreen(screen.purpose);
    case "dates":
      return void datesScreen();
    case "moments":
      return momentsScreen();
    case "spots":
      return void spotsScreen(screen.moment);
    case "when":
      return whenScreen();
    case "place":
      return void placeScreen();
    case "shot":
      return void shotScreen(screen.shot);
    case "shot-map":
      return shotMapScreen(screen.shot);
    case "eclipses":
      return eclipsesScreen();
    case "eclipse":
      return void eclipseScreen(screen.event);
  }
}

/** The card head: the number of the frame and the title, like the edge of a film. */
const head = (n: string, label: string) => `<p class="step"><span>${n}</span>${label}</p>`;

function on(selector: string, handler: (el: HTMLElement) => void, root: ParentNode = document) {
  for (const el of root.querySelectorAll<HTMLElement>(selector)) {
    el.addEventListener("click", () => handler(el));
  }
}

// --- The start ----------------------------------------------------------------------------------

function home() {
  page.innerHTML = `
    <section class="hero">
      <h1>Plan the shot.</h1>
      <p class="lead">The full moon behind a summit, the Milky Way over a ridge, the sun in the gap of
        the mountains. Start from what you want in the frame, or from where you are.</p>
      <div class="choices">
        <button class="choice" data-go="place">
          <span class="art">${icon("milkyway")}</span>
          ${head("A", "From where I am")}
          <h2>I am here, or I will be</h2>
          <p>Shotradar reads the real horizon of the place: when the moon, the Milky Way, the sun,
            and the planets rise over it, and which peaks they pass.</p>
        </button>
        <button class="choice" data-go="landmark">
          <span class="art">${icon("moon")}</span>
          ${head("B", "Behind a landmark")}
          <h2>I have a landmark</h2>
          <p>Pick the subject and the peak or the tower. Shotradar finds when the subject is behind
            it, and where to stand.</p>
        </button>
        <button class="choice" data-go="eclipse">
          <span class="art">${icon("eclipse")}</span>
          ${head("C", "An eclipse")}
          <h2>The next eclipses</h2>
          <p>The solar and the lunar eclipses: the path of the shadow on the map of the Earth, and
            how much your place sees of them, over the real horizon.</p>
        </button>
      </div>
    </section>`;
  on("[data-go]", (el) => {
    const way = el.dataset.go;
    flow = way === "place" ? "place" : way === "eclipse" ? "eclipse" : "landmark";
    go(
      flow === "place"
        ? { kind: "spot", purpose: "place" }
        : flow === "eclipse"
          ? { kind: "eclipses" }
          : { kind: "subject" },
    );
  });
}

// --- "I have a landmark" ------------------------------------------------------------------------

function subjectScreen() {
  const boxes: { id: SubjectId; name: string; note: string }[] = [
    { id: "moon", name: "The moon", note: "rising, setting, the full moon" },
    { id: "milkyway", name: "The Milky Way", note: "its bright center, in a dark sky" },
    { id: "sun", name: "The sun", note: "sunrise and sunset" },
    { id: "venus", name: "Venus", note: "the evening and the morning star" },
    { id: "jupiter", name: "Jupiter", note: "the bright planet of the night" },
    { id: "saturn", name: "Saturn", note: "the planet with the rings" },
    { id: "mars", name: "Mars", note: "the red planet" },
  ];
  page.innerHTML = `
    <section class="sheet-page">
      ${head("01", "The subject")}
      <h2 class="title">What do you want in the frame?</h2>
      <div class="subjects">
        ${boxes
          .map(
            (
              b,
            ) => `<button class="subject ${b.id === state.subject ? "active" : ""}" data-id="${b.id}">
              <span class="art">${icon(b.id)}</span><b>${b.name}</b><small>${b.note}</small></button>`,
          )
          .join("")}
      </div>
      <button class="button primary wide" id="next">Next: the landmark</button>
    </section>`;
  on(".subject", (el) => {
    state.subject = el.dataset.id as SubjectId;
    save();
    for (const b of document.querySelectorAll(".subject")) b.classList.toggle("active", b === el);
  });
  on("#next", () => go({ kind: "landmark" }));
}

function showMap(marks: Partial<typeof map.marks>) {
  map.marks = { landmark: null, spot: null, lines: [], dots: [], ...marks };
  map.onTap = () => {};
  map.resize();
}

function landmarkScreen() {
  showMap({ landmark: state.landmark && observerOf(state.landmark) });
  if (state.landmark) map.center(observerOf(state.landmark));
  const draw = () => {
    const l = state.landmark;
    mapCard.innerHTML = `
      ${head("02", "The landmark")}
      <h2>${l ? (l.name ? `Near ${escape(l.name)}` : "Your landmark") : "Tap the landmark"}</h2>
      ${
        l
          ? `<dl class="exif">
              <div><dt>Ground</dt><dd>${l.groundM === null ? "…" : `${Math.round(l.groundM)} m`}</dd></div>
              <div><dt>Place</dt><dd>${l.latitudeDeg.toFixed(4)}°, ${l.longitudeDeg.toFixed(4)}°</dd></div>
            </dl>
            <label class="field">A building on it <input id="extra" type="number" min="0" max="900" value="${state.extraM}" /> m</label>`
          : `<p class="muted">A summit, a tower, a church. A tap snaps to the highest point nearby.</p>`
      }
      <button class="button primary wide" id="next" ${l && l.groundM !== null ? "" : "disabled"}>Next: from where?</button>`;
    document.getElementById("extra")?.addEventListener("input", (e) => {
      state.extraM = Math.max(0, Number((e.target as HTMLInputElement).value) || 0);
      save();
    });
    on("#next", () => go({ kind: "from" }), mapCard);
  };
  map.onTap = (at) => {
    // At a large zoom the radius is small, so the user can tap a tower in a valley.
    const radius = Math.min(300, 14 * metersPerPixel(map.view.zoom, at.latitudeDeg));
    void summitNear(at, radius).then(async (top) => {
      const p: Point = { ...(top ?? at), name: nameNear(at), groundM: null };
      state.landmark = p;
      save();
      map.marks.landmark = observerOf(p);
      map.draw();
      draw();
      p.groundM = await groundAt(observerOf(p));
      save();
      draw();
    });
  };
  draw();
}

function fromScreen() {
  const s = subject();
  const where = state.landmark?.name ? `the landmark near ${state.landmark.name}` : "your landmark";
  page.innerHTML = `
    <section class="sheet-page">
      ${head("03", "From where")}
      <h2 class="title">${escape(s.name)} behind ${escape(where)}. From where?</h2>
      <div class="choices">
        <button class="choice" data-go="spot">
          ${head("A", "I know my spot")}
          <h2>I stand here</h2>
          <p>Tap your spot or use your location. Shotradar lists the dates of the next 12 months, as
            photos.</p>
        </button>
        <button class="choice" data-go="moments">
          ${head("B", "Find me a spot")}
          <h2>Where must I stand?</h2>
          <p>Pick a moment, for example the next rise of the full moon. Shotradar draws the line of
            places and the spots with a clear view.</p>
        </button>
      </div>
    </section>`;
  on("[data-go]", (el) =>
    go(el.dataset.go === "spot" ? { kind: "spot", purpose: "dates" } : { kind: "moments" }),
  );
}

function spotScreen(purpose: "dates" | "place" | "eclipse") {
  showMap({
    landmark: purpose === "dates" && state.landmark ? observerOf(state.landmark) : null,
    spot: state.spot && observerOf(state.spot),
  });
  if (state.spot) map.center(observerOf(state.spot));
  const draw = () => {
    const s = state.spot;
    const title = s
      ? s.name
        ? `Near ${escape(s.name)}`
        : "Your spot"
      : purpose === "dates"
        ? "Where do you stand?"
        : "Where are you, or where will you be?";
    mapCard.innerHTML = `
      ${head(purpose === "dates" ? "03" : "01", purpose === "dates" ? "Your spot" : "The place")}
      <h2>${title}</h2>
      ${
        s
          ? `<dl class="exif">
              <div><dt>Ground</dt><dd>${s.groundM === null ? "…" : `${Math.round(s.groundM)} m`}</dd></div>
              <div><dt>Place</dt><dd>${s.latitudeDeg.toFixed(4)}°, ${s.longitudeDeg.toFixed(4)}°</dd></div>
            </dl>`
          : `<p class="muted" id="spot-hint">Tap the map, or use the location of your phone.</p>`
      }
      <div class="row"><button class="button" id="locate">Use my location</button>
        <button class="button primary" id="next" ${s && s.groundM !== null ? "" : "disabled"}>${purpose === "dates" ? "Find the dates" : purpose === "eclipse" ? "Use this place" : "Next: the days"}</button></div>`;
    on(
      "#next",
      () =>
        purpose === "eclipse"
          ? history.back()
          : go(purpose === "dates" ? { kind: "dates" } : { kind: "when" }),
      mapCard,
    );
    on(
      "#locate",
      () =>
        navigator.geolocation?.getCurrentPosition(
          (pos) => {
            const at = { latitudeDeg: pos.coords.latitude, longitudeDeg: pos.coords.longitude };
            map.center(at, 12);
            void setSpot({ ...at, name: "your location", groundM: null });
          },
          () => {
            const hint = document.getElementById("spot-hint");
            if (hint) hint.textContent = "No location access. Tap the map.";
          },
        ),
      mapCard,
    );
  };
  const setSpot = async (p: Point) => {
    state.spot = p;
    save();
    map.marks.spot = observerOf(p);
    map.draw();
    draw();
    p.groundM = await groundAt(observerOf(p));
    save();
    draw();
  };
  map.onTap = (at) => void setSpot({ ...at, name: nameNear(at), groundM: null });
  draw();
}

// --- The horizon of a spot and the stars --------------------------------------------------------

const horizons = new Map<string, Promise<Horizon>>();

function horizonOf(at: Observer, onProgress?: (done: number, total: number) => void) {
  const key = `${at.latitudeDeg.toFixed(5)},${at.longitudeDeg.toFixed(5)}`;
  let h = horizons.get(key);
  if (!h) {
    h = computeHorizon(almucantar, at, onProgress);
    horizons.set(key, h);
  }
  return h;
}

let stars: Stars | null = null;
const dust = milkyWayDust(2400, 3600);
let starsLoading: Promise<void> | null = null;

function loadStars() {
  starsLoading ??= fetch(starsUrl)
    .then((r) => r.json())
    .then((json: StarsJson) => {
      stars = decodeStars(json);
    })
    .catch(() => {});
  return starsLoading;
}

/** A frame of the photo of a shot at the time `t`, with the lens `focalMm`. */
/** The pan of the photo: the change of the direction of the view, and a zoom of the view. */
interface Pan {
  azimuthDeg: number;
  altitudeDeg: number;
  zoom: number;
}

const NO_PAN: Pan = { azimuthDeg: 0, altitudeDeg: 0, zoom: 1 };

function frameOf(
  shot: Shot,
  horizon: Horizon,
  t: number,
  focalMm: number,
  thumb: boolean,
  pan: Pan = NO_PAN,
): PhotoFrame {
  const at = shot.spot;
  const sun = almucantar.sunPosition(t, at);
  const moon = almucantar.moonPosition(t, at);
  const s = subjectOf(shot.subject);
  const body = almucantar.bodyPosition(s.body, t, at);
  // The view: on the landmark, or on the subject alone at the time of the shot; a little wider
  // than the lens, so the frame of the lens shows.
  const center = shot.target?.direction ?? almucantar.bodyPosition(s.body, shot.unixUtc, at);
  const span = (lensSpanDeg(focalMm) * (thumb ? 1 : 1.3)) / pan.zoom;
  // A subject alone: the horizon stays in the lower part of the photo, if the subject is low
  // enough for that; a high subject stays in the photo.
  // An eclipse aims at the sun or the moon itself.
  const altitude = shot.eclipse
    ? center.altitudeDeg
    : shot.target
      ? Math.max(center.altitudeDeg, -1) + span * 0.12
      : Math.max(span * 0.22, center.altitudeDeg - span * 0.3);
  const view = {
    azimuthDeg: center.azimuthDeg + pan.azimuthDeg,
    altitudeDeg: Math.min(89, Math.max(-30, altitude + pan.altitudeDeg)),
    spanDeg: span,
  };
  const sunAlt = almucantar.sunPosition(t, at, { refraction: false }).altitudeDeg;
  const limit = limitingMagnitude(sunAlt);
  const night = sunAlt < -8;
  const zone = zoneAt(at);
  // The path of the subject, with the full hours.
  const points = [];
  const ticks = [];
  for (let u = shot.pathStartUtc; u <= shot.pathEndUtc; u += 300) {
    const d = almucantar.bodyPosition(s.body, u, at);
    points.push(d);
    if (Math.round(u) % 3600 < 300) {
      ticks.push({ direction: d, label: formatTime(Math.floor(u / 3600) * 3600, zone, "time") });
    }
  }
  const fraction = almucantar.moonIlluminatedFraction(t);
  const eclipse = shot.eclipse;
  const solarEclipse =
    eclipse?.event.type === "solar"
      ? {
          sunRadiusDeg: eclipse.local.sunRadiusDeg,
          moonRadiusDeg: eclipse.local.moonRadiusDeg,
          coverage: discCoverage(
            angleDeg(sun, moon),
            eclipse.local.sunRadiusDeg,
            eclipse.local.moonRadiusDeg,
          ),
        }
      : null;
  return {
    solarEclipse,
    lunarUmbra: eclipse?.event.lunar ? lunarUmbra(eclipse.event.lunar, t) : 0,
    view,
    sun,
    moon,
    moonFraction: fraction,
    terrain: horizon.terrain,
    track: { points, ticks },
    trackAlpha: thumb ? 0.6 : 0.9,
    body:
      s.body === Body.moon || s.body === Body.galacticCenter
        ? null
        : { kind: s.body === Body.sun ? "sun" : "planet", direction: body, label: s.name },
    stars:
      night && stars && !thumb
        ? { stars, directions: almucantar.starPositions(stars.catalog, t, at), limitMag: limit }
        : null,
    dust:
      night && s.body === Body.galacticCenter
        ? {
            milkyWay: dust,
            directions: almucantar.starPositions(dust.catalog, t, at),
            strength: Math.min(1, Math.max(0, (limit - 3.5) / 2.5)),
          }
        : null,
    pin:
      shot.target && !thumb
        ? {
            direction: shot.target.direction,
            title: shot.target.name.toUpperCase(),
            detail: `${shot.target.elevationM === null ? "" : `${Math.round(shot.target.elevationM)} M · `}${km(shot.target.distanceM).toUpperCase()}`,
            alpha: 1,
          }
        : null,
    finder: thumb ? null : { center: view, focalMm, alpha: 0.9 },
    hud: thumb
      ? null
      : {
          topLeft: `AZ ${deg(body.azimuthDeg)} · ALT ${deg(body.altitudeDeg)}`,
          topRight:
            s.body === Body.moon ? `MOON ${Math.round(fraction * 100)} %` : s.name.toUpperCase(),
          bottom: formatTime(t, zone).toUpperCase(),
        },
  };
}

/** The angle between two directions, with the haversine formula. */
function angleDeg(a: { azimuthDeg: number; altitudeDeg: number }, b: typeof a): number {
  const rad = Math.PI / 180;
  const h1 = a.altitudeDeg * rad;
  const h2 = b.altitudeDeg * rad;
  const dz = (b.azimuthDeg - a.azimuthDeg) * rad;
  const s = Math.sin((h2 - h1) / 2) ** 2 + Math.cos(h1) * Math.cos(h2) * Math.sin(dz / 2) ** 2;
  return (2 * Math.asin(Math.min(1, Math.sqrt(s)))) / rad;
}

/** Draws a frame into a canvas of its CSS size. */
function paint(canvas: HTMLCanvasElement, frame: PhotoFrame) {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (!w || !h) return;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  const ctx = canvas.getContext("2d")!;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, w, h);
  ctx.clip();
  drawPhoto(ctx, { x: 0, y: 0, w, h }, frame);
  ctx.restore();
}

/**
 * The contact sheet: one small photo for each shot, a tap opens the shot. `more` gives, for a
 * shot, how many more nights of the same subject the list leaves out, and the action for them.
 */
function contactSheet(
  list: HTMLElement,
  shots: Shot[],
  horizon: Horizon | null,
  more?: (shot: Shot) => { count: number; show: () => void } | null,
) {
  const zone = zoneAt(shots[0]?.spot ?? null);
  list.innerHTML = shots
    .map((s, i) => {
      const sky = light(almucantar, s.unixUtc, s.spot);
      const fraction = almucantar.moonIlluminatedFraction(s.unixUtc);
      const what = s.target
        ? escape(s.target.name)
        : s.riseUtc
          ? "rises over the terrain"
          : "over the horizon";
      const tags = [
        s.subject === "moon" ? `Moon ${Math.round(fraction * 100)} %` : subjectOf(s.subject).name,
        sky.label,
      ];
      return `<li><button data-i="${i}">
        <canvas></canvas>
        <span class="caption"><b>${formatTime(s.unixUtc, zone, "date")}</b>
          <span>${formatTime(s.unixUtc, zone, "time")} · ${what}</span>
          <small>${tags.join(" · ")}</small></span>
      </button>${(() => {
        const m = more?.(s);
        return m && m.count > 0
          ? `<button class="more" data-more="${i}">+ ${m.count} more ${m.count === 1 ? "night" : "nights"}</button>`
          : "";
      })()}</li>`;
    })
    .join("");
  on("[data-i]", (el) => go({ kind: "shot", shot: shots[Number(el.dataset.i)] }), list);
  on("[data-more]", (el) => more?.(shots[Number(el.dataset.more)])?.show(), list);
  if (!horizon) return;
  // The photos one after the other, so the list appears at once.
  const canvases = [...list.querySelectorAll("canvas")];
  const generation = renderGeneration;
  let i = 0;
  const next = () => {
    if (generation !== renderGeneration || i >= canvases.length) return;
    const shot = shots[i];
    paint(canvases[i], frameOf(shot, horizon, shot.unixUtc, shot.target ? 200 : 35, true));
    i++;
    setTimeout(next, 0);
  };
  next();
}

const developing = (text: string) => `<p class="developing"><span></span>${text}</p>`;

// --- The dates ("I know my spot") ---------------------------------------------------------------

async function datesScreen() {
  const spot = state.spot!;
  const landmark = state.landmark!;
  const eye = siteOf(spot, EYE_HEIGHT_M)!;
  const top = siteOf(landmark, state.extraM)!;
  const sight = almucantar.sight(eye, top);
  const s = subject();
  const name = landmark.name ? `Landmark near ${landmark.name}` : "Your landmark";
  page.innerHTML = `
    <section class="sheet-page">
      ${head("04", "The dates")}
      <h2 class="title">${escape(s.name)} behind the landmark, from your spot</h2>
      <dl class="exif">
        <div><dt>Landmark</dt><dd>${km(sight.distanceM)} · ${Math.round(top.elevationM)} m</dd></div>
        <div><dt>Direction</dt><dd>${deg(sight.direction.azimuthDeg)} · ${deg(sight.direction.altitudeDeg, 2)} up</dd></div>
        <div><dt>The view</dt><dd id="view">checking…</dd></div>
        <div><dt>Search</dt><dd>the next 12 months</dd></div>
      </dl>
      <div id="status">${developing("Reading the horizon of your spot…")}</div>
      <ol class="contact" id="list"></ol>
    </section>`;
  const generation = renderGeneration;
  const shots = datesFor(almucantar, state.subject, eye, top, name, Date.now() / 1000);
  // The view to the landmark over the terrain, if it is near enough for the tiles.
  if (sight.distanceM <= 40_000) {
    const middle = destination(observerOf(spot), sight.direction.azimuthDeg, sight.distanceM / 2);
    void gridsAround(almucantar, middle, sight.distanceM / 2 + 1000, GRID_ZOOM).then((tiles) => {
      if (generation !== renderGeneration || tiles.length === 0) return;
      const v = almucantar.lineOfSight(eye, top, tiles, targetMargin(sight.distanceM));
      $("view").innerHTML = v.visible
        ? `<span class="tag good">clear</span>`
        : `<span class="tag bad">blocked</span> ${km(v.blockingDistanceM)} from you`;
    });
  } else {
    $("view").textContent = "too far to check";
  }
  const horizon = await horizonOf(observerOf(spot), (d, t) => {
    if (generation === renderGeneration && d < t) {
      $("status").innerHTML = developing(`Reading the horizon of your spot… ${d + 1} of ${t}`);
    }
  });
  if (generation !== renderGeneration) return;
  $("status").innerHTML = shots.length
    ? `<p class="muted">${shots.length} ${shots.length === 1 ? "date" : "dates"}. Tap a photo for the shot.</p>`
    : `<p class="muted">${escape(noMatchReason(almucantar, state.subject, sight.direction, observerOf(spot), Date.now() / 1000))}</p>`;
  contactSheet($("list"), shots, horizon);
}

// --- The moments and the spots ("Find me a spot") -----------------------------------------------

function momentsScreen() {
  const landmark = state.landmark!;
  const at = observerOf(landmark);
  const zone = zoneAt(at);
  const s = subject();
  const moments = momentsFor(almucantar, state.subject, at, Date.now() / 1000);
  const note =
    s.id === "moon" ? ", near the full moon" : s.maxSunDeg !== null ? ", in a dark sky" : "";
  page.innerHTML = `
    <section class="sheet-page">
      ${head("03", "The moment")}
      <h2 class="title">When should the ${escape(s.name.toLowerCase())} be behind the landmark?</h2>
      <p class="muted">The next times when it is low over the horizon, so a landmark can stand in
        front of it${note}.</p>
      <ol class="moments">
        ${moments
          .map(
            (m) => `<li><button data-t="${m.unixUtc}">
              <b>${formatTime(m.unixUtc, zone, "date")}</b>
              <span>${formatTime(m.unixUtc, zone, "time")} · ${m.rising ? "after the rise" : "before the set"}</span>
              <small>${m.fraction === null ? light(almucantar, m.unixUtc, at).label : `${Math.round(m.fraction * 100)} % lit`}</small>
            </button></li>`,
          )
          .join("")}
      </ol>
      ${moments.length ? "" : `<p class="muted">No good moment in the next months.</p>`}
      <div class="custom">
        <p class="step"><span>…</span>Or another time</p>
        <div class="row">
          <input id="day" type="date" value="${isoDay(zone)}" />
          <input id="clock" type="time" value="20:00" />
          <button class="button" id="custom">Show the spots</button>
        </div>
      </div>
    </section>`;
  on("[data-t]", (el) => go({ kind: "spots", moment: Number(el.dataset.t) }));
  on("#custom", () => {
    const [y, mo, d] = ($("day") as HTMLInputElement).value.split("-").map(Number);
    const [h, mi] = ($("clock") as HTMLInputElement).value.split(":").map(Number);
    if (y) go({ kind: "spots", moment: fromWallTime(zone, y, mo, d, h || 0, mi || 0) / 1000 });
  });
}

async function spotsScreen(moment: number) {
  const landmark = state.landmark!;
  const at = observerOf(landmark);
  const target = siteOf(landmark, state.extraM)!;
  const s = subject();
  const zone = zoneAt(at);
  showMap({ landmark: at });
  map.center(at, Math.max(map.view.zoom, 11));
  let t = moment;
  let line: AlignmentLine | null = null;
  let views = new Map<LinePoint, boolean>();
  let selected: LinePoint | null = null;
  const generation = renderGeneration;
  const tiles = gridsAround(almucantar, at, LINE_DISTANCE_M, GRID_ZOOM);

  const card = () => {
    const body = almucantar.bodyPosition(s.body, t, at);
    const clear = [...views.values()].filter(Boolean).length;
    const spots = line
      ? `${clear} clear · ${line.spots.length - clear} blocked`
      : body.altitudeDeg < -0.5
        ? "below the horizon"
        : "…";
    mapCard.innerHTML = `
      ${head("04", "The spots")}
      <h2>${formatTime(t, zone, "time")} <small>${formatTime(t, zone, "date")}</small></h2>
      <input id="shift" type="range" min="-120" max="120" step="2" value="${Math.round((t - moment) / 60)}" aria-label="The time" />
      <dl class="exif">
        <div><dt>${escape(s.name)}</dt><dd>${deg(body.azimuthDeg, 0)} · ${deg(body.altitudeDeg)} up</dd></div>
        <div><dt>Spots</dt><dd>${spots}</dd></div>
      </dl>
      ${
        selected
          ? `<div class="picked"><b>A spot ${km(selected.distanceM)} from the landmark</b>
               <span class="muted">${Math.round(selected.groundElevationM)} m · ${views.get(selected) ? "clear view" : "the terrain blocks the view"}</span>
               <button class="button primary wide" id="see">See the shot</button></div>`
          : `<p class="muted">The full dots have a clear view of the landmark. Tap one.</p>`
      }`;
    $("shift").addEventListener("change", (e) => {
      t = moment + Number((e.target as HTMLInputElement).value) * 60;
      void update();
    });
    on(
      "#see",
      () => {
        const p = selected!;
        const eye: Site = { ...p.place, elevationM: p.groundElevationM + EYE_HEIGHT_M };
        const sight = almucantar.sight(eye, target);
        const tg: Target = {
          direction: sight.direction,
          name: landmark.name ? `Landmark near ${landmark.name}` : "Landmark",
          distanceM: sight.distanceM,
          elevationM: target.elevationM,
          place: at,
        };
        go({
          kind: "shot",
          shot: {
            subject: s.id,
            unixUtc: t,
            spot: p.place,
            target: tg,
            separationDeg: 0,
            body: p.body,
            pathStartUtc: t - 3600,
            pathEndUtc: t + 3600,
          },
        });
      },
      mapCard,
    );
  };

  const marks = () => {
    const toward = line?.points[0]?.body.azimuthDeg;
    map.marks.lines = line
      ? [
          {
            points: line.points.map((p) => p.place),
            color: "rgba(138, 74, 85, 0.8)",
            width: 3,
            dash: [8, 6],
          },
          ...(toward === undefined
            ? []
            : [
                {
                  points: [at, destination(at, toward, 4000)],
                  color: "rgba(232, 149, 94, 0.95)",
                  width: 3,
                },
              ]),
        ]
      : [];
    map.marks.dots = line
      ? line.spots.map((p) => ({
          at: p.place,
          full: views.get(p) ?? false,
          selected: p === selected,
        }))
      : [];
    map.draw();
  };

  const update = async () => {
    const body = almucantar.bodyPosition(s.body, t, at);
    const grids = await tiles;
    if (generation !== renderGeneration) return;
    line = null;
    selected = null;
    views = new Map();
    if (body.altitudeDeg >= -0.5) {
      try {
        line = almucantar.alignmentLine(s.body, target, t, grids, {
          maxDistanceM: LINE_DISTANCE_M,
          stepM: 50,
          eyeHeightM: EYE_HEIGHT_M,
        });
        for (const p of line.spots) {
          const eye = { ...p.place, elevationM: p.groundElevationM + EYE_HEIGHT_M };
          views.set(
            p,
            grids.length > 0 &&
              almucantar.lineOfSight(eye, target, grids, targetMargin(p.distanceM)).visible,
          );
        }
      } catch {
        line = null;
      }
    }
    marks();
    card();
  };

  map.onTap = (_at, x, y) => {
    if (!line) return;
    let best: LinePoint | null = null;
    let bestPx = 28;
    for (const p of line.spots) {
      const [sx, sy] = map.screen(p.place);
      const d = Math.hypot(sx - x, sy - y);
      if (d < bestPx) {
        best = p;
        bestPx = d;
      }
    }
    selected = best;
    marks();
    card();
  };
  card();
  await update();
}

// --- "From where I am" --------------------------------------------------------------------------

function whenScreen() {
  const zone = zoneAt(observerOf(state.spot!));
  const today = isoDay(zone);
  page.innerHTML = `
    <section class="sheet-page">
      ${head("02", "The days")}
      <h2 class="title">When are you there?</h2>
      <div class="choices">
        <button class="choice" data-go="now">
          ${head("A", "I am here")}
          <h2>Now</h2>
          <p>The next 14 days, from today.</p>
        </button>
        <div class="choice">
          ${head("B", "I will be here")}
          <h2>On these days</h2>
          <div class="row">
            <input id="from" type="date" value="${state.fromDay ?? today}" />
            <select id="days">${[1, 2, 3, 7, 14, 30]
              .map(
                (d) =>
                  `<option value="${d}" ${d === state.days ? "selected" : ""}>${d} ${d === 1 ? "night" : "days"}</option>`,
              )
              .join("")}</select>
          </div>
          <button class="button primary wide" data-go="later">Find the shots</button>
        </div>
      </div>
    </section>`;
  on("[data-go]", (el) => {
    if (el.dataset.go === "now") {
      state.fromDay = null;
      state.days = 14;
    } else {
      state.fromDay = ($("from") as HTMLInputElement).value || today;
      state.days = Number(($("days") as HTMLSelectElement).value);
    }
    save();
    go({ kind: "place" });
  });
}

async function placeScreen() {
  const spot = state.spot!;
  const at = observerOf(spot);
  const zone = zoneAt(at);
  let start = Date.now() / 1000;
  if (state.fromDay) {
    const [y, m, d] = state.fromDay.split("-").map(Number);
    // From noon of the first day, so its night counts.
    start = fromWallTime(zone, y, m, d, 12) / 1000;
  }
  const end = start + state.days * 86_400;
  let filter: SubjectId | "all" = "all";
  const chips = [["all", "All"], ...SUBJECTS.map((s) => [s.id, s.name])];
  page.innerHTML = `
    <section class="sheet-page">
      ${head("03", "The shots")}
      <h2 class="title">From ${escape(spot.name ? `near ${spot.name}` : "your place")}</h2>
      <dl class="exif">
        <div><dt>Days</dt><dd>${formatTime(start, zone, "date")} + ${state.days}</dd></div>
        <div><dt>Ground</dt><dd>${Math.round(spot.groundM ?? 0)} m</dd></div>
        <div><dt>Peaks</dt><dd id="peaks">…</dd></div>
      </dl>
      <div class="chips" id="filter">${chips
        .map(
          ([id, n]) =>
            `<button data-f="${id}" class="${id === "all" ? "active" : ""}">${n}</button>`,
        )
        .join("")}</div>
      <div id="status">${developing("Reading the horizon of the place…")}</div>
      <h3 class="section">The sky from here</h3>
      <p class="muted small">Each subject over the real horizon, in a sky that is dark enough for it.</p>
      <ol class="contact" id="sky"></ol>
      <h3 class="section">Behind a peak</h3>
      <p class="muted small">The subjects that pass a summit of your horizon.</p>
      <ol class="contact" id="peaks-list"></ol>
    </section>`;
  const generation = renderGeneration;
  const horizon = await horizonOf(at, (d, t) => {
    if (generation === renderGeneration && d < t) {
      $("status").innerHTML = developing(`Reading the horizon of the place… ${d + 1} of ${t}`);
    }
  });
  if (generation !== renderGeneration) return;
  const targets = peakTargets(horizon);
  $("peaks").textContent = String(targets.length);
  const sky = skyFromPlace(almucantar, horizon, start, end);
  const behind = shotsFromPlace(almucantar, horizon, targets, start, end);
  const select = (id: SubjectId | "all") => {
    filter = id;
    for (const c of document.querySelectorAll<HTMLElement>("#filter button")) {
      c.classList.toggle("active", c.dataset.f === id);
    }
    show();
  };
  const show = () => {
    const keep = (x: Shot) => filter === "all" || x.subject === filter;
    // With all subjects, the sky has the first night of each subject; the others are a tap away.
    const first = new Map<SubjectId, Shot>();
    for (const x of sky) if (!first.has(x.subject)) first.set(x.subject, x);
    const a = filter === "all" ? [...first.values()] : sky.filter(keep);
    const b = behind.filter(keep);
    $("status").innerHTML =
      `<p class="muted">${sky.filter(keep).length + b.length} shots. Tap a photo.</p>`;
    contactSheet($("sky"), a, horizon, (x) =>
      filter === "all"
        ? {
            count: sky.filter((y) => y.subject === x.subject).length - 1,
            show: () => select(x.subject),
          }
        : null,
    );
    contactSheet($("peaks-list"), b, horizon);
    if (!a.length)
      $("sky").innerHTML = `<li class="empty">Nothing over the horizon in these days.</li>`;
    if (!b.length) {
      $("peaks-list").innerHTML =
        `<li class="empty">No subject passes a peak of this horizon in these days.</li>`;
    }
  };
  on("[data-f]", (el) => select(el.dataset.f as SubjectId | "all"));
  show();
}

// --- The shot -----------------------------------------------------------------------------------

async function shotScreen(shot: Shot) {
  const zone = zoneAt(shot.spot);
  const s = subjectOf(shot.subject);
  let focal = shot.eclipse ? 600 : shot.target ? 300 : 24;
  let t = shot.unixUtc;
  const minutes = Math.round((shot.pathEndUtc - shot.pathStartUtc) / 60);
  const offset = Math.round((shot.unixUtc - shot.pathStartUtc) / 60);
  const title = shot.eclipse
    ? `${ECLIPSE_NAMES[shot.eclipse.local.kind]} ${shot.eclipse.event.type} eclipse`
    : shot.target
      ? `${s.name} behind ${shot.target.name.startsWith("Peak") ? shot.target.name : "the landmark"}`
      : `${s.name} over the horizon`;
  page.innerHTML = `
    <section class="shot-page">
      <div class="print"><canvas id="photo"></canvas><div id="photo-status">${developing("Developing the shot…")}</div>
        <p class="hint">Drag to look around · pinch or scroll to zoom · double tap to aim again</p></div>
      <div class="shot-card">
        ${head("◉", shot.eclipse ? "The eclipse" : shot.target ? "The shot" : "The sky")}
        <h2 class="title">${escape(title)}</h2>
        <label class="slider"><span>Time</span><input id="when" type="range" min="0" max="${minutes}" step="1" value="${offset}" aria-label="The time" /><output id="when-label"></output></label>
        <div class="lenses chips">${LENSES.map((l) => `<button data-lens="${l}" class="${l === focal ? "active" : ""}">${l} mm</button>`).join("")}</div>
        <dl class="exif" id="exif"></dl>
        <div class="row">
          <a class="button primary" id="navigate" target="_blank" rel="noopener">Navigate to the spot</a>
          <button class="button" id="on-map">On the map</button>
        </div>
      </div>
    </section>`;
  const canvas = $<HTMLCanvasElement>("photo");
  const exif = $("exif");
  const generation = renderGeneration;
  const lat = shot.spot.latitudeDeg.toFixed(5);
  const lon = shot.spot.longitudeDeg.toFixed(5);
  $<HTMLAnchorElement>("navigate").href =
    `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=15/${lat}/${lon}`;
  on("#on-map", () => go({ kind: "shot-map", shot }));
  const horizon = await horizonOf(shot.spot, (d, n) => {
    if (generation === renderGeneration && d < n) {
      $("photo-status").innerHTML = developing(`Developing the shot… ${d + 1} of ${n}`);
    }
  });
  if (generation !== renderGeneration) return;
  await loadStars();
  if (generation !== renderGeneration) return;
  $("photo-status").innerHTML = "";
  let pan: Pan = { ...NO_PAN };
  const draw = () => {
    if (generation !== renderGeneration) return;
    paint(canvas, frameOf(shot, horizon, t, focal, false, pan));
    $("when-label").textContent = formatTime(t, zone, "time");
    const body = almucantar.bodyPosition(s.body, t, shot.spot);
    const sky = light(almucantar, t, shot.spot);
    const fraction = almucantar.moonIlluminatedFraction(t);
    const rows: [string, string][] = [
      ["When", formatTime(t, zone)],
      ["Light", totality(shot, t) ? "Totality: the sky is dark" : sky.label],
      [
        s.name,
        `${deg(body.azimuthDeg)} · ${deg(body.altitudeDeg)} up${s.id === "moon" ? ` · ${Math.round(fraction * 100)} % lit` : ""}`,
      ],
      ["From", `${lat}°, ${lon}° · ${Math.round(horizon.groundM)} m`],
    ];
    if (shot.target) {
      rows.push([
        "Landmark",
        `${km(shot.target.distanceM)} · ${deg(shot.target.direction.azimuthDeg)} · ${deg(shot.target.direction.altitudeDeg, 2)} up`,
      ]);
    }
    if (shot.eclipse) {
      const e = shot.eclipse;
      const now =
        e.event.type === "solar"
          ? discCoverage(
              angleDeg(almucantar.sunPosition(t, shot.spot), almucantar.moonPosition(t, shot.spot)),
              e.local.sunRadiusDeg,
              e.local.moonRadiusDeg,
            )
          : e.event.lunar
            ? lunarUmbra(e.event.lunar, t)
            : 0;
      rows.push([
        e.event.type === "solar" ? "Covered now" : "In the umbra now",
        `${Math.round(now * 100)} %`,
      ]);
      for (const p of e.local.phases) {
        rows.push([p.label, `${formatTime(p.unixUtc, zone, "time")} · ${deg(p.altitudeDeg)} up`]);
      }
    }
    if (shot.riseUtc) {
      rows.push(["Rises", `${formatTime(shot.riseUtc, zone, "time")} over the terrain`]);
    }
    if (shot.setUtc) {
      rows.push(["Sets", `${formatTime(shot.setUtc, zone, "time")} behind the terrain`]);
    }
    rows.push(["Lens", `${focal} mm · ${deg(lensSpanDeg(focal))} wide`]);
    exif.innerHTML = rows
      .map(([k, v]) => `<div><dt>${escape(k)}</dt><dd>${escape(v)}</dd></div>`)
      .join("");
  };
  $("when").addEventListener("input", (e) => {
    t = shot.pathStartUtc + Number((e.target as HTMLInputElement).value) * 60;
    draw();
  });
  // Look around: a drag turns the view, two fingers or the wheel zoom it, a double tap aims again.
  const degreesPerPixel = () =>
    (lensSpanDeg(focal) * 1.3) / pan.zoom / Math.max(1, canvas.clientWidth);
  const pointers = new Map<number, { x: number; y: number }>();
  let pinch: { distance: number; zoom: number } | null = null;
  canvas.addEventListener("pointerdown", (e) => {
    canvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), zoom: pan.zoom };
    }
  });
  canvas.addEventListener("pointermove", (e) => {
    const last = pointers.get(e.pointerId);
    if (!last) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2 && pinch) {
      const [a, b] = [...pointers.values()];
      const zoom = pinch.zoom * (Math.hypot(a.x - b.x, a.y - b.y) / Math.max(1, pinch.distance));
      pan.zoom = Math.min(4, Math.max(0.25, zoom));
    } else {
      const k = degreesPerPixel();
      pan.azimuthDeg -= (e.clientX - last.x) * k;
      pan.altitudeDeg += (e.clientY - last.y) * k;
    }
    draw();
  });
  const release = (e: PointerEvent) => {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinch = null;
  };
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);
  canvas.addEventListener(
    "wheel",
    (e) => {
      e.preventDefault();
      pan.zoom = Math.min(4, Math.max(0.25, pan.zoom * Math.exp(-e.deltaY / 400)));
      draw();
    },
    { passive: false },
  );
  canvas.addEventListener("dblclick", () => {
    pan = { ...NO_PAN };
    draw();
  });
  on("[data-lens]", (el) => {
    focal = Number(el.dataset.lens);
    for (const b of document.querySelectorAll("[data-lens]")) {
      b.classList.toggle("active", b === el);
    }
    draw();
  });
  new ResizeObserver(() => draw()).observe(canvas);
  draw();
}

/** True in the total phase of a solar eclipse. */
function totality(shot: Shot, t: number): boolean {
  const e = shot.eclipse;
  if (!e || e.event.type !== "solar" || e.local.kind !== 3) return false;
  const covered = discCoverage(
    angleDeg(almucantar.sunPosition(t, shot.spot), almucantar.moonPosition(t, shot.spot)),
    e.local.sunRadiusDeg,
    e.local.moonRadiusDeg,
  );
  return covered >= 0.999;
}

/** The map of a shot: the spot, the landmark, and the direction of the subject. */
function shotMapScreen(shot: Shot) {
  const s = subjectOf(shot.subject);
  const body = almucantar.bodyPosition(s.body, shot.unixUtc, shot.spot);
  showMap({
    spot: shot.spot,
    landmark: shot.target?.place ?? null,
    lines: [
      {
        points: [
          shot.spot,
          destination(shot.spot, body.azimuthDeg, (shot.target?.distanceM ?? 5000) + 3000),
        ],
        color: "rgba(232, 149, 94, 0.95)",
        width: 3,
      },
    ],
  });
  if (shot.target) map.fit(shot.spot, shot.target.place);
  else map.center(shot.spot, 12);
  const zone = zoneAt(shot.spot);
  mapCard.innerHTML = `
    ${head("◉", "On the map")}
    <h2>${escape(s.name)} at ${formatTime(shot.unixUtc, zone, "time")}</h2>
    <p class="muted">The line goes from your spot${shot.target ? " over the landmark" : ""} to the
      ${escape(s.name.toLowerCase())}, at the azimuth ${deg(body.azimuthDeg)}.</p>
    <button class="button wide" id="to-shot">Back to the shot</button>`;
  on("#to-shot", () => history.back(), mapCard);
}

// --- The eclipses -------------------------------------------------------------------------------

/** The name of an eclipse, for example "Total solar eclipse". */
const eclipseName = (e: Eclipse, kind = e.kind) =>
  `${ECLIPSE_NAMES[kind]} ${e.type === "solar" ? "solar" : "lunar"} eclipse`;

function eclipsesScreen() {
  const spot = state.spot;
  const at = spot ? observerOf(spot) : null;
  const zone = zoneAt(at);
  const events = upcomingEclipses(almucantar, Date.now() / 1000);
  const where = spot ? (spot.name ? `near ${spot.name}` : "your place") : "no place yet";
  page.innerHTML = `
    <section class="sheet-page">
      ${head("01", "The eclipses")}
      <h2 class="title">The next eclipses</h2>
      <div class="row"><span class="muted">Seen from</span><b>${escape(where)}</b>
        <button class="button" id="place">${spot ? "Change the place" : "Pick your place"}</button></div>
      <ol class="moments eclipses">
        ${events
          .map((e, i) => {
            const local = at ? localEclipse(almucantar, e, at) : null;
            let here = "Pick a place to see more";
            let good = false;
            if (at && !local) here = "Not visible here";
            else if (local && !local.visible) {
              here = e.type === "solar" ? "The sun is down here" : "The moon is down here";
            } else if (local) {
              good = true;
              const what =
                e.type === "solar"
                  ? `${Math.round(local.coverage * 100)} % of the sun`
                  : ECLIPSE_NAMES[local.kind].toLowerCase();
              here = `${what} · ${deg(local.peak.altitudeDeg, 0)} up at the peak`;
            }
            return `<li><button data-e="${i}">
              <b>${formatTime(e.peakUtc, zone, "date")}</b>
              <span>${eclipseName(e)}</span>
              <small class="${good ? "good" : ""}">${escape(here)}</small>
            </button></li>`;
          })
          .join("")}
      </ol>
    </section>`;
  on("#place", () => go({ kind: "spot", purpose: "eclipse" }));
  on("[data-e]", (el) => go({ kind: "eclipse", event: events[Number(el.dataset.e)] }));
}

/** The point under the moon at a time: the moon is up within 90° of it. */
function subLunarPoint(t: number): Observer {
  let best = { latitudeDeg: 0, longitudeDeg: 0 };
  let high = -Infinity;
  const search = (lat0: number, lon0: number, span: number, step: number) => {
    for (let lat = Math.max(-89, lat0 - span); lat <= Math.min(89, lat0 + span); lat += step) {
      for (let lon = lon0 - span; lon <= lon0 + span; lon += step) {
        const at = { latitudeDeg: lat, longitudeDeg: ((((lon + 180) % 360) + 360) % 360) - 180 };
        const a = almucantar.moonPosition(t, at, { refraction: false }).altitudeDeg;
        if (a > high) {
          high = a;
          best = at;
        }
      }
    }
  };
  search(0, 0, 180, 10);
  search(best.latitudeDeg, best.longitudeDeg, 10, 1);
  return best;
}

async function eclipseScreen(e: Eclipse) {
  const generation = renderGeneration;
  const lines: typeof map.marks.lines = [];
  const dots: typeof map.marks.dots = [];
  let center: Observer = state.spot ? observerOf(state.spot) : { latitudeDeg: 30, longitudeDeg: 0 };
  if (e.type === "solar" && e.peakPlace) {
    const path = almucantar.eclipseCentralLine(e.peakUtc, 60);
    if (path.length > 1) {
      const radius = path.reduce((sum, p) => sum + Math.abs(p.umbraRadiusKm), 0) / path.length;
      const places = path.map((p) => p.place);
      lines.push({
        points: places,
        color: e.kind === 3 ? "rgba(29, 20, 38, 0.32)" : "rgba(138, 74, 85, 0.3)",
        width: 4,
        widthM: 2 * radius * 1000,
      });
      lines.push({ points: places, color: "#2a1f3d", width: 1.5 });
    }
    dots.push({ at: e.peakPlace, full: true, selected: false });
    center = e.peakPlace;
  } else if (e.type === "lunar") {
    // The moon is up in the half of the Earth around the point under the moon.
    const under = subLunarPoint(e.peakUtc);
    const ring = Array.from({ length: 73 }, (_, i) =>
      destination(under, i * 5, (Math.PI / 2) * 6_371_008.8),
    );
    lines.push({ points: ring, color: "rgba(138, 74, 85, 0.75)", width: 2, dash: [6, 5] });
    dots.push({ at: under, full: true, selected: false });
    center = under;
  }
  showMap({ spot: state.spot && observerOf(state.spot), lines, dots });
  map.view = {
    lon: center.longitudeDeg,
    lat: Math.max(-60, Math.min(60, center.latitudeDeg)),
    zoom: 2.4,
  };
  map.draw();

  const draw = async () => {
    const spot = state.spot;
    const at = spot ? observerOf(spot) : null;
    const zone = zoneAt(at);
    const local = at ? localEclipse(almucantar, e, at) : null;
    const path =
      e.type === "solar"
        ? e.peakPlace
          ? `The dark line is the center of the shadow, the band its ${e.kind === 3 ? "total" : "annular"} part.`
          : "No place sees the sun fully covered."
        : "The moon is up inside the dashed line at the peak.";
    let body = `<p class="muted">Tap the map to choose your place.</p>`;
    if (at && !local) body = `<p class="muted">Your place sees no part of this eclipse.</p>`;
    else if (local) {
      body = `<dl class="exif">
          <div><dt>Here</dt><dd>${ECLIPSE_NAMES[local.kind]}${e.type === "solar" ? ` · ${Math.round(local.coverage * 100)} %` : ""}</dd></div>
          <div><dt>Peak</dt><dd>${formatTime(local.peak.unixUtc, zone, "time")} · ${deg(local.peak.altitudeDeg)} up</dd></div>
          <div><dt>Terrain</dt><dd id="terrain">${local.visible ? "checking…" : "below the horizon"}</dd></div>
        </dl>
        <button class="button primary wide" id="see" ${local.visible ? "" : "disabled"}>See the shot</button>`;
    }
    mapCard.innerHTML = `
      ${head("02", formatTime(e.peakUtc, zone, "date"))}
      <h2>${eclipseName(e)}</h2>
      <p class="muted small">${path}</p>
      ${body}`;
    if (!at || !local) return;
    on(
      "#see",
      () => {
        const up = local.phases.filter((p) => p.altitudeDeg > 0);
        const best = local.peak.altitudeDeg > 0 ? local.peak : up[0];
        const s: SubjectId = e.type === "solar" ? "sun" : "moon";
        go({
          kind: "shot",
          shot: {
            subject: s,
            unixUtc: best.unixUtc,
            spot: at,
            target: null,
            separationDeg: 0,
            body: almucantar.bodyPosition(subjectOf(s).body, best.unixUtc, at),
            pathStartUtc: local.phases[0].unixUtc,
            pathEndUtc: local.phases[local.phases.length - 1].unixUtc,
            eclipse: { event: e, local },
          },
        });
      },
      mapCard,
    );
    if (!local.visible) return;
    // Over the real horizon: the peak, or the first phase that is up.
    const horizon = await horizonOf(at);
    if (generation !== renderGeneration || state.spot !== spot) return;
    const s = subjectOf(e.type === "solar" ? "sun" : "moon");
    const free = local.phases.filter((p) => {
      const d = almucantar.bodyPosition(s.body, p.unixUtc, at);
      const ground = profileAltitude(horizon.skyline, d.azimuthDeg);
      return d.altitudeDeg > (Number.isFinite(ground) ? ground : 0);
    });
    const cell = document.getElementById("terrain");
    if (cell) {
      cell.textContent =
        free.length === 0
          ? "behind the terrain"
          : free.length === local.phases.length
            ? "all phases over it"
            : `${free.length} of ${local.phases.length} phases over it`;
    }
  };
  map.onTap = (at) => {
    state.spot = { ...at, name: nameNear(at), groundM: null };
    save();
    map.marks.spot = at;
    map.draw();
    void groundAt(at).then((g) => {
      if (state.spot) state.spot.groundM = g;
      save();
    });
    void draw();
  };
  await draw();
}

// --- The search of the map ----------------------------------------------------------------------

const search = $<HTMLInputElement>("search");
const results = $("results");
search.addEventListener("input", () => {
  const found = baseMap ? searchPlaces(baseMap.places, search.value, 6) : [];
  results.classList.toggle("hidden", found.length === 0);
  results.replaceChildren(
    ...found.map((p) => {
      const b = document.createElement("button");
      b.innerHTML = `${escape(p.name)} <small>${escape(p.country)}</small>`;
      b.addEventListener("click", () => {
        map.center({ latitudeDeg: p.lat, longitudeDeg: p.lon }, 11);
        search.value = "";
        results.classList.add("hidden");
      });
      return b;
    }),
  );
});

// --- Start --------------------------------------------------------------------------------------

if ("serviceWorker" in navigator && location.protocol !== "http:") {
  void navigator.serviceWorker.register("./sw.js");
}

stack = [{ kind: "home" }];
history.replaceState(1, "");
render();
baseMap = decodeBaseMap(await (await fetch(baseMapUrl)).json());
map.baseMap = baseMap;
map.draw();
// The heights of the saved points come back once their tiles are in memory.
for (const p of [state.landmark, state.spot]) {
  if (p && p.groundM === null) void groundAt(observerOf(p)).then((g) => (p.groundM = g));
}
