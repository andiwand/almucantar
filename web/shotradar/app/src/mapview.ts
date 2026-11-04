// The map of shotradar: the shaded relief of the Terrarium tiles with the Natural Earth base map on
// top, the gestures (drag, pinch, wheel, tap), and the marks of the plan (the landmark, the spot,
// the lines, and the spots).

import type { Observer } from "@almucantar/core";
import { drawBaseMap, fromWorld, toWorld } from "@almucantar/web-shared/basemap";
import type { BaseMap, BaseMapStyle, MapView as View } from "@almucantar/web-shared/basemap";
import { shadeTile } from "./relief";
import { decodedTile } from "./tiles";

/** The base map over the light relief: dark lines and names with a light halo. */
const LIGHT_MAP: BaseMapStyle = {
  background: null,
  rivers: "rgba(70, 120, 160, 0.45)",
  lakes: "rgba(70, 120, 160, 0.6)",
  coastline: "rgba(42, 31, 61, 0.55)",
  borders: "rgba(138, 74, 85, 0.55)",
  label: "rgba(42, 31, 61, 0.9)",
  halo: "rgba(255, 250, 242, 0.9)",
};

/** A line on the map, for example the line of sight or the line of the spots. */
export interface MapLine {
  points: Observer[];
  color: string;
  width: number;
  dash?: number[];
}

/** A dot on the map, for example a spot. `full` false draws a ring. */
export interface MapDot {
  at: Observer;
  full: boolean;
  selected: boolean;
}

export interface MapMarks {
  landmark: Observer | null;
  spot: Observer | null;
  lines: MapLine[];
  dots: MapDot[];
}

/** The point at `distance` meters from `from` in the direction `azimuthDeg`, on the sphere. */
export function destination(from: Observer, azimuthDeg: number, distance: number): Observer {
  const rad = Math.PI / 180;
  const d = distance / 6_371_008.8;
  const phi = from.latitudeDeg * rad;
  const theta = azimuthDeg * rad;
  const phi2 = Math.asin(
    Math.sin(phi) * Math.cos(d) + Math.cos(phi) * Math.sin(d) * Math.cos(theta),
  );
  const lambda2 =
    from.longitudeDeg * rad +
    Math.atan2(
      Math.sin(theta) * Math.sin(d) * Math.cos(phi),
      Math.cos(d) - Math.sin(phi) * Math.sin(phi2),
    );
  return { latitudeDeg: phi2 / rad, longitudeDeg: lambda2 / rad };
}

export class MapCanvas {
  view: View;
  baseMap: BaseMap | null = null;
  marks: MapMarks = { landmark: null, spot: null, lines: [], dots: [] };
  /** A tap on the map, in CSS pixels of the canvas, and the place under it. */
  onTap: (place: Observer, x: number, y: number) => void = () => {};
  /** The view changed after a gesture, so the app can store it. */
  onView: (view: View) => void = () => {};

  private readonly ctx: CanvasRenderingContext2D;
  private width = 0;
  private height = 0;
  private readonly relief = new Map<string, ImageBitmap | null>();
  private readonly pointers = new Map<number, { x: number; y: number }>();
  private gesture: { x: number; y: number; moved: boolean; distance: number; zoom: number } | null =
    null;

  constructor(
    private readonly canvas: HTMLCanvasElement,
    view: View,
  ) {
    this.view = { ...view };
    this.ctx = canvas.getContext("2d")!;
    this.listen();
    new ResizeObserver(() => this.resize()).observe(canvas);
  }

  resize() {
    const dpr = window.devicePixelRatio || 1;
    this.width = this.canvas.clientWidth;
    this.height = this.canvas.clientHeight;
    this.canvas.width = Math.round(this.width * dpr);
    this.canvas.height = Math.round(this.height * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.draw();
  }

  /** Centers the map on a place, at a zoom of at least `minZoom`. */
  center(at: Observer, minZoom = this.view.zoom) {
    this.view = {
      lon: at.longitudeDeg,
      lat: at.latitudeDeg,
      zoom: Math.max(this.view.zoom, minZoom),
    };
    this.draw();
    this.onView(this.view);
  }

  /** Shows two places with a margin, for example the spot and the landmark. */
  fit(a: Observer, b: Observer) {
    const z0 = 16;
    const [ax, ay] = toWorld(a.longitudeDeg, a.latitudeDeg, z0);
    const [bx, by] = toWorld(b.longitudeDeg, b.latitudeDeg, z0);
    const span = Math.max(
      Math.abs(ax - bx) / (this.width * 0.6),
      Math.abs(ay - by) / (this.height * 0.5),
      1e-9,
    );
    const [lon, lat] = fromWorld((ax + bx) / 2, (ay + by) / 2, z0);
    this.view = { lon, lat, zoom: Math.max(3, Math.min(14, z0 - Math.log2(span))) };
    this.draw();
    this.onView(this.view);
  }

  /** The screen point of a place. */
  screen(at: Observer): [number, number] {
    const [cx, cy] = toWorld(this.view.lon, this.view.lat, this.view.zoom);
    const [x, y] = toWorld(at.longitudeDeg, at.latitudeDeg, this.view.zoom);
    return [x - cx + this.width / 2, y - cy + this.height / 2];
  }

  placeAt(x: number, y: number): Observer {
    const [cx, cy] = toWorld(this.view.lon, this.view.lat, this.view.zoom);
    const [lon, lat] = fromWorld(cx + x - this.width / 2, cy + y - this.height / 2, this.view.zoom);
    return { latitudeDeg: lat, longitudeDeg: lon };
  }

  private reliefTile(zoom: number, x: number, y: number): ImageBitmap | null {
    const key = `${zoom}/${x}/${y}`;
    if (this.relief.has(key)) return this.relief.get(key)!;
    this.relief.set(key, null);
    void decodedTile(zoom, x, y).then(async (tile) => {
      if (!tile) return;
      const shaded = shadeTile(tile.rgba, tile.width, tile.height, zoom, y);
      this.relief.set(key, await createImageBitmap(new ImageData(shaded, tile.width, tile.height)));
      if (this.relief.size > 400) this.relief.delete(this.relief.keys().next().value!);
      this.draw();
    });
    return null;
  }

  draw() {
    const { ctx, width, height, view } = this;
    if (!width) return;
    ctx.fillStyle = "#f3ece1";
    ctx.fillRect(0, 0, width, height);
    // The relief: the tiles of the nearest whole zoom, scaled to the zoom of the view.
    const z = Math.max(2, Math.min(13, Math.round(view.zoom)));
    const scale = 2 ** (view.zoom - z);
    const n = 2 ** z;
    const [cx, cy] = toWorld(view.lon, view.lat, z);
    const left = cx - width / 2 / scale;
    const top = cy - height / 2 / scale;
    const lastRow = Math.min(n - 1, Math.floor((top + height / scale) / 256));
    for (let ty = Math.max(0, Math.floor(top / 256)); ty <= lastRow; ty++) {
      for (let tx = Math.floor(left / 256); tx <= Math.floor((left + width / scale) / 256); tx++) {
        const wx = ((tx % n) + n) % n;
        const size = 256 * scale;
        const dx = (tx * 256 - left) * scale;
        const dy = (ty * 256 - top) * scale;
        const bitmap = this.reliefTile(z, wx, ty);
        if (bitmap) {
          ctx.drawImage(bitmap, dx, dy, size + 0.5, size + 0.5);
          continue;
        }
        // While the tile loads, a part of a tile of a lower zoom that is in memory.
        for (let k = 1; k <= 4 && z - k >= 0; k++) {
          const parent = this.relief.get(`${z - k}/${wx >> k}/${ty >> k}`);
          if (!parent) continue;
          const part = parent.width / 2 ** k;
          const sx = (wx - ((wx >> k) << k)) * part;
          const sy = (ty - ((ty >> k) << k)) * part;
          ctx.drawImage(parent, sx, sy, part, part, dx, dy, size + 0.5, size + 0.5);
          break;
        }
      }
    }
    if (this.baseMap) drawBaseMap(ctx, this.baseMap, view, width, height, null, LIGHT_MAP);
    this.drawMarks();
  }

  private drawMarks() {
    const { ctx, marks } = this;
    for (const line of marks.lines) {
      if (line.points.length < 2) continue;
      ctx.save();
      ctx.strokeStyle = line.color;
      ctx.lineWidth = line.width;
      ctx.setLineDash(line.dash ?? []);
      ctx.lineCap = "round";
      ctx.beginPath();
      line.points.forEach((p, i) => {
        const [x, y] = this.screen(p);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
      ctx.restore();
    }
    for (const dot of marks.dots) {
      const [x, y] = this.screen(dot.at);
      const r = dot.selected ? 10 : 7.5;
      ctx.lineWidth = 2.2;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, 2 * Math.PI);
      ctx.fillStyle = dot.full
        ? dot.selected
          ? "#2a1f3d"
          : "#e8955e"
        : "rgba(255, 250, 242, 0.85)";
      ctx.strokeStyle = dot.full ? "#fffaf2" : dot.selected ? "#2a1f3d" : "#8a7a80";
      ctx.fill();
      ctx.stroke();
    }
    if (marks.spot) this.drawSpot(marks.spot);
    if (marks.landmark) this.drawLandmark(marks.landmark);
  }

  /** The landmark: a dark peak with a light edge. */
  private drawLandmark(at: Observer) {
    const [x, y] = this.screen(at);
    const ctx = this.ctx;
    ctx.save();
    ctx.fillStyle = "#2a1f3d";
    ctx.strokeStyle = "#fff3d6";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x, y - 13);
    ctx.lineTo(x + 11, y + 6);
    ctx.lineTo(x - 11, y + 6);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  }

  /** The photographer: the corners of a viewfinder around a dot. */
  private drawSpot(at: Observer) {
    const [x, y] = this.screen(at);
    const ctx = this.ctx;
    ctx.save();
    ctx.fillStyle = "rgba(255, 250, 242, 0.9)";
    ctx.beginPath();
    ctx.arc(x, y, 11, 0, 2 * Math.PI);
    ctx.fill();
    ctx.strokeStyle = "#8a4a55";
    ctx.lineWidth = 2;
    const s = 9;
    const a = 4;
    ctx.beginPath();
    for (const [dx, dy] of [
      [-1, -1],
      [1, -1],
      [1, 1],
      [-1, 1],
    ]) {
      ctx.moveTo(x + dx * s, y + dy * (s - a));
      ctx.lineTo(x + dx * s, y + dy * s);
      ctx.lineTo(x + dx * (s - a), y + dy * s);
    }
    ctx.stroke();
    ctx.fillStyle = "#8a4a55";
    ctx.beginPath();
    ctx.arc(x, y, 3.2, 0, 2 * Math.PI);
    ctx.fill();
    ctx.restore();
  }

  private zoomAt(x: number, y: number, zoom: number) {
    const before = this.placeAt(x, y);
    this.view.zoom = Math.max(3, Math.min(15, zoom));
    // Keep the place under the finger where it is.
    const [bx, by] = this.screen(before);
    const [cx, cy] = toWorld(this.view.lon, this.view.lat, this.view.zoom);
    const [lon, lat] = fromWorld(cx + bx - x, cy + by - y, this.view.zoom);
    this.view.lon = lon;
    this.view.lat = lat;
  }

  private local(e: PointerEvent | WheelEvent): [number, number] {
    const box = this.canvas.getBoundingClientRect();
    return [e.clientX - box.left, e.clientY - box.top];
  }

  private listen() {
    const canvas = this.canvas;
    canvas.addEventListener("pointerdown", (e) => {
      canvas.setPointerCapture(e.pointerId);
      const [x, y] = this.local(e);
      this.pointers.set(e.pointerId, { x, y });
      const [a, b] = [...this.pointers.values()];
      this.gesture = {
        x,
        y,
        moved: this.pointers.size > 1,
        distance: b ? Math.hypot(a.x - b.x, a.y - b.y) : 0,
        zoom: this.view.zoom,
      };
    });
    canvas.addEventListener("pointermove", (e) => {
      const last = this.pointers.get(e.pointerId);
      const g = this.gesture;
      if (!last || !g) return;
      const [x, y] = this.local(e);
      this.pointers.set(e.pointerId, { x, y });
      if (this.pointers.size === 2) {
        const [a, b] = [...this.pointers.values()];
        const distance = Math.hypot(a.x - b.x, a.y - b.y);
        if (g.distance > 0) {
          this.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, g.zoom + Math.log2(distance / g.distance));
        }
      } else {
        const [cx, cy] = toWorld(this.view.lon, this.view.lat, this.view.zoom);
        const [lon, lat] = fromWorld(cx - (x - last.x), cy - (y - last.y), this.view.zoom);
        this.view.lon = lon;
        this.view.lat = lat;
      }
      if (Math.hypot(x - g.x, y - g.y) > 6) g.moved = true;
      this.draw();
    });
    const end = (e: PointerEvent) => {
      this.pointers.delete(e.pointerId);
      if (this.gesture && !this.gesture.moved && this.pointers.size === 0) {
        const [x, y] = this.local(e);
        this.onTap(this.placeAt(x, y), x, y);
      }
      if (this.pointers.size === 0) {
        this.gesture = null;
        this.onView(this.view);
      }
    };
    canvas.addEventListener("pointerup", end);
    canvas.addEventListener("pointercancel", (e) => {
      this.pointers.delete(e.pointerId);
      this.gesture = null;
    });
    canvas.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        const [x, y] = this.local(e);
        this.zoomAt(x, y, this.view.zoom - e.deltaY / 300);
        this.draw();
        this.onView(this.view);
      },
      { passive: false },
    );
  }
}
