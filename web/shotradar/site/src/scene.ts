// The scene of the website: one print on the paper of the page. It shows a photo (the sky, the
// moon, and the terrain in layers, with the marks of a viewfinder: @almucantar/web-shared/photo), a
// map (the terrain with the
// line of the spots), or a profile (the ground from the spot to the summit). The core calculates
// all positions; this file only draws them.

import type { Horizontal, LinePoint } from "@almucantar/core";
import type { MilkyWayDust } from "@almucantar/web-shared/milkyway";
import { css, mix } from "@almucantar/web-shared/panorama";
import type { Rgb } from "@almucantar/web-shared/panorama";
import { MONO, drawPhoto } from "@almucantar/web-shared/photo";
import type { Stars } from "@almucantar/web-shared/stars";
import { LAYERS, MAP, PEAK, PROFILE, SIGHT, SPOT, terrainAltitude } from "./data";

/** The direction in the center of the photo, and the azimuth range across its width. */
export interface View {
  azimuthDeg: number;
  altitudeDeg: number;
  spanDeg: number;
}

/** The path of a body over some hours, with a label at each full hour. */
export interface Track {
  points: Horizontal[];
  ticks: { direction: Horizontal; label: string }[];
}

/** A body that a chapter shows in addition to the moon: the sun or a planet. */
export interface Body {
  kind: "sun" | "planet";
  direction: Horizontal;
  label: string;
}

/** The map: the line of the places from which the moon is behind the summit, and its spots. */
export interface MapState {
  points: LinePoint[];
  spots: LinePoint[];
  moonAzimuthDeg: number;
  label: string;
}

/** One column of the time line of the check: the sky and the moon at a time. */
export interface Moment {
  t: number;
  sunAltitudeDeg: number;
  moonUp: boolean;
}

export interface ProfileState {
  /** The part of the line of sight that is drawn, from 0 to 1. */
  reveal: number;
  /** The highest ground in front of the summit: its index in PROFILE and the clearance. */
  clearance: { index: number; deg: number } | null;
  timeline: Moment[];
  /** The time of the shot in the time line, and the hour labels. */
  shotT: number;
  ticks: { t: number; label: string }[];
}

export interface Frame {
  sun: Horizontal;
  moon: Horizontal;
  moonFraction: number;
  view: View;
  /** The weights of the three pictures, from 0 to 1. */
  photo: number;
  map: number;
  profile: number;
  /** The visibility of the whole print, from 0 to 1. */
  print: number;
  /** The label on the summit, from 0 to 1. */
  pin: number;
  /** The frame of the lens, from 0 to 1, and its focal length. */
  finder: number;
  focalMm: number;
  track: Track | null;
  trackAlpha: number;
  body: Body | null;
  stars: Float64Array | null;
  limitMag: number;
  dust: Float64Array | null;
  milkyWay: number;
  /** The texts of the viewfinder. */
  hud: { topLeft: string; topRight: string; bottom: string };
  mapState: MapState | null;
  profileState: ProfileState | null;
  /** A dark layer between two dates, and a white flash at the shot, from 0 to 1. */
  fade: number;
  flash: number;
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const DEG = Math.PI / 180;
const SERIF = '"Iowan Old Style", "Palatino Linotype", Palatino, "Book Antiqua", Georgia, serif';

// The colors of the logo: dusk violet, rose, apricot, cream, and the night.
const INK: Rgb = [42, 31, 61];
const ROSE: Rgb = [138, 74, 85];
const APRICOT: Rgb = [232, 149, 94];
const CREAM: Rgb = [255, 243, 214];
const PAPER: Rgb = [251, 246, 237];

/** The width of the mat around the picture: the white border of a print. */
const MAT = 10;

/**
 * The place of the print on the screen. The CSS of the cards uses the same values: on a wide
 * screen the cards are in a column on the left, on a narrow screen they are at the bottom.
 */
export function printRect(width: number, height: number): Rect {
  if (width >= 860) {
    const margin = clamp(0.04 * width, 20, 64);
    const column = Math.min(400, 0.34 * width);
    const left = margin + column + clamp(0.045 * width, 32, 80);
    const vertical = Math.max(44, 0.085 * height);
    return { x: left, y: vertical, w: width - 76 - left, h: height - 2 * vertical };
  }
  return { x: 10, y: 10, w: width - 20, h: Math.round(height * 0.47) - 10 };
}

// --- The scene ----------------------------------------------------------------------------------

export class Scene {
  private readonly ctx: CanvasRenderingContext2D;
  private width = 0;
  private height = 0;
  private rect: Rect = { x: 0, y: 0, w: 1, h: 1 };
  private mapCache: { key: string; canvas: HTMLCanvasElement } | null = null;
  private shadeCanvas: HTMLCanvasElement | null = null;
  private contours: Float32Array | null = null;

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private stars: Stars | null,
    private readonly milkyWay: MilkyWayDust,
  ) {
    this.ctx = canvas.getContext("2d")!;
  }

  setStars(stars: Stars) {
    this.stars = stars;
  }

  private resize() {
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = this.canvas.clientWidth;
    const h = this.canvas.clientHeight;
    if (w !== this.width || h !== this.height || this.canvas.width !== Math.round(w * dpr)) {
      this.width = w;
      this.height = h;
      this.canvas.width = Math.round(w * dpr);
      this.canvas.height = Math.round(h * dpr);
      this.mapCache = null;
    }
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const r = printRect(w, h);
    this.rect = { x: r.x + MAT, y: r.y + MAT, w: r.w - 2 * MAT, h: r.h - 2 * MAT };
  }

  draw(f: Frame, nowMs: number) {
    this.resize();
    const ctx = this.ctx;
    ctx.clearRect(0, 0, this.width, this.height);
    if (f.print < 0.01) return;
    const r = this.rect;
    ctx.save();
    ctx.globalAlpha = f.print;

    // The print: a white mat with a soft shadow on the paper.
    ctx.save();
    ctx.shadowColor = "rgba(42, 31, 61, 0.22)";
    ctx.shadowBlur = 36;
    ctx.shadowOffsetY = 14;
    ctx.fillStyle = "#fffcf6";
    ctx.fillRect(r.x - MAT, r.y - MAT, r.w + 2 * MAT, r.h + 2 * MAT);
    ctx.restore();
    ctx.strokeStyle = "rgba(42, 31, 61, 0.08)";
    ctx.lineWidth = 1;
    ctx.strokeRect(r.x - MAT + 0.5, r.y - MAT + 0.5, r.w + 2 * MAT - 1, r.h + 2 * MAT - 1);

    ctx.save();
    ctx.beginPath();
    ctx.rect(r.x, r.y, r.w, r.h);
    ctx.clip();
    if (f.photo > 0.01) {
      ctx.globalAlpha = f.print;
      this.photo(f, nowMs);
    }
    if (f.map > 0.01 && f.mapState) {
      ctx.globalAlpha = f.print * f.map;
      this.map(f.mapState);
    }
    if (f.profile > 0.01 && f.profileState) {
      ctx.globalAlpha = f.print * f.profile;
      this.profile(f.profileState, f);
    }
    ctx.globalAlpha = f.print;
    if (f.fade > 0.01) {
      ctx.fillStyle = `rgba(20, 14, 30, ${f.fade})`;
      ctx.fillRect(r.x, r.y, r.w, r.h);
    }
    if (f.flash > 0.01) {
      ctx.fillStyle = `rgba(255, 250, 240, ${0.85 * f.flash})`;
      ctx.fillRect(r.x, r.y, r.w, r.h);
    }
    ctx.restore();
    ctx.restore();
  }

  // --- The photo --------------------------------------------------------------------------------

  /** The photo of @almucantar/web-shared/photo, with the summit and the spot of the story. */
  private photo(f: Frame, nowMs: number) {
    drawPhoto(
      this.ctx,
      this.rect,
      {
        view: f.view,
        sun: f.sun,
        moon: f.moon,
        moonFraction: f.moonFraction,
        terrain: { layers: LAYERS, altitude: terrainAltitude },
        track: f.track,
        trackAlpha: f.trackAlpha,
        body: f.body,
        stars:
          this.stars && f.stars
            ? { stars: this.stars, directions: f.stars, limitMag: f.limitMag }
            : null,
        dust: f.dust ? { milkyWay: this.milkyWay, directions: f.dust, strength: f.milkyWay } : null,
        pin: {
          direction: { azimuthDeg: SIGHT.azimuthDeg, altitudeDeg: SIGHT.altitudeDeg },
          title: PEAK.name.toUpperCase(),
          detail: `${PEAK.site.elevationM.toFixed(0)} M · ${(SIGHT.distanceM / 1000).toFixed(2)} KM`,
          alpha: f.pin,
        },
        finder: {
          center: { azimuthDeg: SIGHT.azimuthDeg, altitudeDeg: SIGHT.altitudeDeg + 0.32 },
          focalMm: f.focalMm,
          alpha: f.finder,
        },
        hud: f.hud,
        alpha: f.print,
      },
      nowMs,
    );
  }

  // --- The map ----------------------------------------------------------------------------------

  /** The projection of the map: the middle between the spot and the summit in the center. */
  private mapProjection() {
    const r = this.rect;
    const lat0 = (SPOT.observer.latitudeDeg + PEAK.site.latitudeDeg) / 2;
    const lon0 = (SPOT.observer.longitudeDeg + PEAK.site.longitudeDeg) / 2 - 0.012;
    const mPerPx = Math.max(13500 / r.w, 9500 / r.h);
    const kx = (111320 * Math.cos(lat0 * DEG)) / mPerPx;
    const ky = 110574 / mPerPx;
    return {
      mPerPx,
      x: (lon: number) => r.x + r.w / 2 + (lon - lon0) * kx,
      y: (lat: number) => r.y + r.h / 2 - (lat - lat0) * ky,
    };
  }

  /** The shade of each cell of the map, once: the light from the north-west, and the snow. */
  private shade(): HTMLCanvasElement {
    if (this.shadeCanvas) return this.shadeCanvas;
    const { rows, columns, heightsM: z } = MAP;
    const canvas = document.createElement("canvas");
    canvas.width = columns;
    canvas.height = rows;
    const c = canvas.getContext("2d")!;
    const image = c.createImageData(columns, rows);
    const lat = MAP.northLatitudeDeg - (rows / 2) * MAP.latitudeStepDeg;
    const dx = MAP.longitudeStepDeg * 111320 * Math.cos(lat * DEG);
    const dy = MAP.latitudeStepDeg * 110574;
    const light = [-Math.SQRT1_2 * Math.cos(50 * DEG), Math.SQRT1_2 * Math.cos(50 * DEG)];
    const lightZ = Math.sin(50 * DEG);
    for (let row = 0; row < rows; row++) {
      for (let col = 0; col < columns; col++) {
        const i = row * columns + col;
        const e = z[row * columns + Math.min(columns - 1, col + 1)];
        const w = z[row * columns + Math.max(0, col - 1)];
        const n = z[Math.max(0, row - 1) * columns + col];
        const s = z[Math.min(rows - 1, row + 1) * columns + col];
        const gx = (e - w) / (2 * dx);
        const gy = (n - s) / (2 * dy);
        const norm = Math.hypot(gx, gy, 1);
        const lit = clamp((-gx * light[0] - gy * light[1] + lightZ) / norm);
        const height = clamp((z[i] - 1200) / 2600);
        let color = mix([240, 230, 214], [226, 214, 200], height);
        if (z[i] > 2900) color = mix(color, [252, 250, 248], clamp((z[i] - 2900) / 400));
        color = mix(color, [92, 70, 112], 0.62 * (1 - lit));
        image.data[4 * i] = color[0];
        image.data[4 * i + 1] = color[1];
        image.data[4 * i + 2] = color[2];
        image.data[4 * i + 3] = 255;
      }
    }
    c.putImageData(image, 0, 0);
    this.shadeCanvas = canvas;
    return canvas;
  }

  /** The contour lines every 100 m, as segments in cells: [col0, row0, col1, row1, level, ...]. */
  private contourSegments(): Float32Array {
    if (this.contours) return this.contours;
    const { rows, columns, heightsM: z } = MAP;
    const out: number[] = [];
    for (let level = 700; level < 3800; level += 100) {
      for (let row = 0; row < rows - 1; row++) {
        for (let col = 0; col < columns - 1; col++) {
          const a = z[row * columns + col];
          const b = z[row * columns + col + 1];
          const c = z[(row + 1) * columns + col + 1];
          const d = z[(row + 1) * columns + col];
          const cuts: number[] = [];
          const edge = (z0: number, z1: number, x0: number, y0: number, x1: number, y1: number) => {
            if (z0 < level !== z1 < level) {
              const t = (level - z0) / (z1 - z0);
              cuts.push(col + x0 + (x1 - x0) * t, row + y0 + (y1 - y0) * t);
            }
          };
          edge(a, b, 0, 0, 1, 0);
          edge(b, c, 1, 0, 1, 1);
          edge(c, d, 1, 1, 0, 1);
          edge(d, a, 0, 1, 0, 0);
          for (let i = 0; i + 3 < cuts.length; i += 4) {
            out.push(cuts[i], cuts[i + 1], cuts[i + 2], cuts[i + 3], level);
          }
        }
      }
    }
    this.contours = Float32Array.from(out);
    return this.contours;
  }

  /** The static part of the map: the shade, the contours, the summit, and the scale. */
  private mapBase(): HTMLCanvasElement {
    const r = this.rect;
    const key = `${this.width}x${this.height}`;
    if (this.mapCache?.key === key) return this.mapCache.canvas;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(r.w * dpr);
    canvas.height = Math.round(r.h * dpr);
    const c = canvas.getContext("2d")!;
    c.setTransform(dpr, 0, 0, dpr, -r.x * dpr, -r.y * dpr);
    const p = this.mapProjection();
    c.fillStyle = css(PAPER);
    c.fillRect(r.x, r.y, r.w, r.h);
    const west = p.x(MAP.westLongitudeDeg);
    const north = p.y(MAP.northLatitudeDeg);
    const cellX = p.x(MAP.westLongitudeDeg + MAP.longitudeStepDeg) - west;
    const cellY = p.y(MAP.northLatitudeDeg - MAP.latitudeStepDeg) - north;
    c.imageSmoothingQuality = "high";
    c.drawImage(this.shade(), west, north, cellX * MAP.columns, cellY * MAP.rows);
    const seg = this.contourSegments();
    for (const major of [false, true]) {
      c.strokeStyle = major ? "rgba(138, 74, 85, 0.55)" : "rgba(138, 74, 85, 0.22)";
      c.lineWidth = major ? 1 : 0.6;
      c.beginPath();
      for (let i = 0; i < seg.length; i += 5) {
        if ((seg[i + 4] % 500 === 0) !== major) continue;
        c.moveTo(west + seg[i] * cellX, north + seg[i + 1] * cellY);
        c.lineTo(west + seg[i + 2] * cellX, north + seg[i + 3] * cellY);
      }
      c.stroke();
    }
    // The edge of the data fades into the paper.
    const edge = 26;
    for (const [x0, y0, x1, y1] of [
      [r.x, 0, r.x + edge, 0],
      [r.x + r.w, 0, r.x + r.w - edge, 0],
      [0, r.y, 0, r.y + edge],
      [0, r.y + r.h, 0, r.y + r.h - edge],
    ]) {
      const g = c.createLinearGradient(x0, y0, x1, y1);
      g.addColorStop(0, css(PAPER, 0.9));
      g.addColorStop(1, css(PAPER, 0));
      c.fillStyle = g;
      c.fillRect(r.x, r.y, r.w, r.h);
    }
    // The scale bar and the north arrow.
    const bar = 2000 / p.mPerPx;
    const bx = r.x + 22;
    const by = r.y + r.h - 24;
    c.fillStyle = css(INK);
    c.fillRect(bx, by, bar, 3);
    c.fillStyle = css(PAPER);
    c.fillRect(bx + bar / 2, by + 0.5, bar / 2 - 0.5, 2);
    c.font = `10px ${MONO}`;
    c.fillStyle = css(INK, 0.85);
    c.textAlign = "left";
    c.fillText("0", bx, by - 6);
    c.textAlign = "right";
    c.fillText("2 KM", bx + bar, by - 6);
    // The north arrow is at the bottom, because the direction to the moon goes to the top right.
    const nx = r.x + r.w - 30;
    const ny = r.y + r.h - 44;
    c.beginPath();
    c.moveTo(nx, ny - 14);
    c.lineTo(nx + 6, ny + 6);
    c.lineTo(nx, ny + 2);
    c.lineTo(nx - 6, ny + 6);
    c.closePath();
    c.fill();
    c.textAlign = "center";
    c.fillText("N", nx, ny + 20);
    this.mapCache = { key, canvas };
    return canvas;
  }

  private map(m: MapState) {
    const ctx = this.ctx;
    const r = this.rect;
    ctx.drawImage(this.mapBase(), r.x, r.y, r.w, r.h);
    const p = this.mapProjection();
    const px = p.x(PEAK.site.longitudeDeg);
    const py = p.y(PEAK.site.latitudeDeg);

    // The direction to the moon, from the summit.
    const len = Math.min(r.w, r.h) * 0.22;
    const ax = px + Math.sin(m.moonAzimuthDeg * DEG) * len;
    const ay = py - Math.cos(m.moonAzimuthDeg * DEG) * len;
    ctx.strokeStyle = css(APRICOT);
    ctx.lineWidth = 1.5;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(px, py);
    ctx.lineTo(ax, ay);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = css(CREAM);
    ctx.strokeStyle = css(APRICOT);
    ctx.beginPath();
    ctx.arc(ax, ay, 8, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.font = `10px ${MONO}`;
    ctx.fillStyle = css(ROSE);
    ctx.textAlign = "center";
    ctx.fillText("TO THE MOON", ax, ay - 14);

    // The line of the places: rose where the ground is too low, ink where it is too high.
    if (m.points.length > 1) {
      ctx.lineCap = "round";
      ctx.lineWidth = 2.5;
      for (let i = 1; i < m.points.length; i++) {
        const a = m.points[i - 1];
        const b = m.points[i];
        const high = b.groundElevationM + 1.6 > b.eyeElevationM;
        ctx.strokeStyle = high ? "rgba(42, 31, 61, 0.55)" : css(ROSE, 0.95);
        ctx.beginPath();
        ctx.moveTo(p.x(a.place.longitudeDeg), p.y(a.place.latitudeDeg));
        ctx.lineTo(p.x(b.place.longitudeDeg), p.y(b.place.latitudeDeg));
        ctx.stroke();
      }
    }
    // The spots.
    for (const s of m.spots) {
      const sx = p.x(s.place.longitudeDeg);
      const sy = p.y(s.place.latitudeDeg);
      ctx.fillStyle = css(APRICOT);
      ctx.strokeStyle = css(INK);
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(sx, sy, 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    }

    // The summit.
    ctx.fillStyle = css(INK);
    ctx.beginPath();
    ctx.moveTo(px, py - 9);
    ctx.lineTo(px + 8, py + 6);
    ctx.lineTo(px - 8, py + 6);
    ctx.closePath();
    ctx.fill();
    // The name below the summit, because the direction to the moon goes up.
    ctx.font = `600 13px ${SERIF}`;
    ctx.textAlign = "center";
    ctx.fillText(PEAK.name, px, py + 22);
    ctx.font = `10px ${MONO}`;
    ctx.fillStyle = css(ROSE);
    ctx.fillText(`${PEAK.site.elevationM.toFixed(0)} M`, px, py + 35);

    // The spot of the story.
    const ox = p.x(SPOT.observer.longitudeDeg);
    const oy = p.y(SPOT.observer.latitudeDeg);
    ctx.strokeStyle = css(INK);
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.arc(ox, oy, 11, 0, Math.PI * 2);
    ctx.stroke();
    ctx.font = `10px ${MONO}`;
    ctx.fillStyle = css(INK);
    ctx.textAlign = "right";
    ctx.fillText(`YOU · ${SPOT.groundM.toFixed(0)} M`, ox - 16, oy + 4);

    // The legend.
    ctx.textAlign = "left";
    ctx.font = `11px ${MONO}`;
    const lx = r.x + 22;
    let ly = r.y + 30;
    ctx.fillStyle = css(INK, 0.92);
    ctx.fillText(m.label, lx, ly);
    ly += 20;
    ctx.fillStyle = css(ROSE);
    ctx.fillRect(lx, ly - 4, 18, 3);
    ctx.fillStyle = css(INK, 0.8);
    ctx.fillText("GROUND TOO LOW", lx + 26, ly);
    ly += 16;
    ctx.fillStyle = "rgba(42, 31, 61, 0.55)";
    ctx.fillRect(lx, ly - 4, 18, 3);
    ctx.fillStyle = css(INK, 0.8);
    ctx.fillText("GROUND TOO HIGH", lx + 26, ly);
    ly += 16;
    ctx.fillStyle = css(APRICOT);
    ctx.beginPath();
    ctx.arc(lx + 9, ly - 3, 4.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = css(INK, 0.8);
    ctx.fillText("A SPOT", lx + 26, ly);
  }

  // --- The profile ------------------------------------------------------------------------------

  private profile(s: ProfileState, f: Frame) {
    const ctx = this.ctx;
    const r = this.rect;
    ctx.fillStyle = css(PAPER);
    ctx.fillRect(r.x, r.y, r.w, r.h);
    const ground = PROFILE.groundM;
    const n = ground.length - 1;
    const D = SIGHT.distanceM;
    const pad = Math.min(36, r.w * 0.07);
    const timelineH = 62;
    const chartTop = r.y + 54;
    const base = r.y + r.h - timelineH - 34;
    const width = r.w - 2 * pad;
    const s0 = width / (D * 1.16);
    let zMin = Infinity;
    for (const z of ground) zMin = Math.min(zMin, z);
    zMin -= 120;
    const rise = PEAK.site.elevationM - zMin;
    const exaggeration = clamp((0.78 * (base - chartTop)) / (rise * s0), 1, 4);
    const X = (d: number) => r.x + pad + d * s0;
    const Y = (z: number) => base - (z - zMin) * s0 * exaggeration;

    // The grid of the chart.
    ctx.strokeStyle = "rgba(42, 31, 61, 0.07)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let z = Math.ceil(zMin / 500) * 500; z < PEAK.site.elevationM + 400; z += 500) {
      ctx.moveTo(r.x, Y(z));
      ctx.lineTo(r.x + r.w, Y(z));
    }
    for (let d = 0; d < D * 1.2; d += 1000) {
      ctx.moveTo(X(d), chartTop - 20);
      ctx.lineTo(X(d), base);
    }
    ctx.stroke();
    ctx.font = `10px ${MONO}`;
    ctx.fillStyle = css(INK, 0.5);
    ctx.textAlign = "left";
    for (let z = Math.ceil(zMin / 500) * 500; z < PEAK.site.elevationM + 400; z += 500) {
      if (Y(z) > chartTop - 10) ctx.fillText(`${z} M`, r.x + 6, Y(z) - 4);
    }

    // The ground.
    ctx.beginPath();
    ctx.moveTo(X(0), base + 1);
    for (let i = 0; i <= n; i++) ctx.lineTo(X((D * i) / n), Y(ground[i]));
    // Behind the summit the ground falls away.
    for (let i = 1; i <= 12; i++) {
      const d = D * (1 + 0.016 * i);
      ctx.lineTo(X(d), Y(ground[n] - 1400 * Math.sqrt(i / 12)));
    }
    ctx.lineTo(X(D * 1.2), base + 1);
    ctx.closePath();
    const g = ctx.createLinearGradient(0, Y(PEAK.site.elevationM), 0, base);
    g.addColorStop(0, "rgba(92, 70, 112, 0.85)");
    g.addColorStop(1, "rgba(42, 31, 61, 0.95)");
    ctx.fillStyle = g;
    ctx.fill();
    ctx.strokeStyle = css(INK);
    ctx.lineWidth = 1.2;
    ctx.stroke();

    // The line of sight, from the eye over the summit to the moon.
    const ex = X(0);
    const ey = Y(SPOT.eye.elevationM);
    const tx = X(D);
    const ty = Y(PEAK.site.elevationM);
    const far = 1.13;
    const mx = ex + (tx - ex) * far;
    const my = ey + (ty - ey) * far;
    const reveal = clamp(s.reveal);
    ctx.strokeStyle = css(APRICOT);
    ctx.lineWidth = 1.8;
    ctx.setLineDash([6, 5]);
    ctx.beginPath();
    ctx.moveTo(ex, ey);
    ctx.lineTo(ex + (mx - ex) * reveal, ey + (my - ey) * reveal);
    ctx.stroke();
    ctx.setLineDash([]);
    if (reveal > 0.97) {
      ctx.fillStyle = css(CREAM);
      ctx.strokeStyle = css(APRICOT);
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(mx, my, 12, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.font = `10px ${MONO}`;
      ctx.fillStyle = css(ROSE);
      ctx.textAlign = "center";
      ctx.fillText("MOON", mx, my - 18);
    }

    // The clearance over the highest ground in front.
    if (s.clearance && reveal > 0.5) {
      const d = (D * s.clearance.index) / n;
      const gx = X(d);
      const gy = Y(ground[s.clearance.index]);
      const ly = ey + ((ty - ey) * d) / D;
      ctx.strokeStyle = css(ROSE);
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(gx, gy - 2);
      ctx.lineTo(gx, ly + 2);
      ctx.moveTo(gx - 5, gy - 2);
      ctx.lineTo(gx + 5, gy - 2);
      ctx.moveTo(gx - 5, ly + 2);
      ctx.lineTo(gx + 5, ly + 2);
      ctx.stroke();
      ctx.font = `11px ${MONO}`;
      ctx.fillStyle = css(ROSE);
      ctx.textAlign = "center";
      ctx.fillText(`${s.clearance.deg.toFixed(1)}° CLEAR`, gx, ly - 9);
    }

    // The camera on its tripod, and the summit.
    ctx.strokeStyle = css(INK);
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    const foot = Y(SPOT.groundM);
    ctx.moveTo(ex, ey + 3);
    ctx.lineTo(ex - 6, foot + 2);
    ctx.moveTo(ex, ey + 3);
    ctx.lineTo(ex + 6, foot + 2);
    ctx.stroke();
    ctx.fillStyle = css(INK);
    ctx.fillRect(ex - 6, ey - 4, 12, 8);
    ctx.font = `10px ${MONO}`;
    ctx.textAlign = "left";
    ctx.fillText(`YOU · ${SPOT.groundM.toFixed(0)} M`, ex + 10, ey - 10);
    ctx.textAlign = "right";
    ctx.font = `600 12px ${SERIF}`;
    ctx.fillText(PEAK.name, tx - 10, ty - 8);

    // The title and the scale.
    ctx.font = `11px ${MONO}`;
    ctx.fillStyle = css(INK, 0.9);
    ctx.textAlign = "left";
    ctx.fillText(
      `LINE OF SIGHT · ${(D / 1000).toFixed(2)} KM · HEIGHTS × ${exaggeration.toFixed(1)}`,
      r.x + pad,
      r.y + 28,
    );

    this.timeline(s, r.x + pad, r.y + r.h - timelineH - 4, width, 22);
    void f;
  }

  /** The light of the evening as a band: day, the twilights, the night, and the moon. */
  private timeline(s: ProfileState, x0: number, y0: number, width: number, h: number) {
    const ctx = this.ctx;
    const t = s.timeline;
    if (t.length < 2) return;
    const t0 = t[0].t;
    const t1 = t[t.length - 1].t;
    const X = (tt: number) => x0 + ((tt - t0) / (t1 - t0)) * width;
    const color = (sun: number): Rgb => {
      if (sun >= 0) return [244, 210, 160];
      if (sun >= -6) return mix([232, 149, 94], [176, 110, 130], -sun / 6);
      if (sun >= -12) return mix([120, 96, 150], [66, 54, 104], (-sun - 6) / 6);
      if (sun >= -18) return mix([56, 44, 90], [30, 22, 48], (-sun - 12) / 6);
      return [24, 18, 36];
    };
    const g = ctx.createLinearGradient(x0, 0, x0 + width, 0);
    for (const m of t) g.addColorStop((m.t - t0) / (t1 - t0), css(color(m.sunAltitudeDeg)));
    ctx.fillStyle = g;
    ctx.fillRect(x0, y0, width, h);
    // The moon above the ridge: a cream line.
    ctx.strokeStyle = css(CREAM);
    ctx.lineWidth = 3;
    ctx.lineCap = "round";
    ctx.beginPath();
    let on = false;
    for (const m of t) {
      if (m.moonUp && !on) ctx.moveTo(X(m.t), y0 + h / 2);
      else if (m.moonUp) ctx.lineTo(X(m.t), y0 + h / 2);
      on = m.moonUp;
    }
    ctx.stroke();
    ctx.font = `10px ${MONO}`;
    ctx.fillStyle = css(INK, 0.7);
    ctx.textAlign = "center";
    for (const tick of s.ticks) ctx.fillText(tick.label, X(tick.t), y0 + h + 14);
    const sx = X(s.shotT);
    ctx.strokeStyle = css(INK);
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(sx, y0 - 6);
    ctx.lineTo(sx, y0 + h + 3);
    ctx.stroke();
    ctx.fillStyle = css(INK);
    ctx.fillText("SHOT", sx, y0 - 10);
    ctx.textAlign = "left";
    ctx.fillStyle = css(INK, 0.6);
    ctx.fillText("SUN ▸ TWILIGHT ▸ NIGHT · MOON UP", x0, y0 + h + 28);
  }
}
