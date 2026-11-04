// The scene of the website: one print on the paper of the page. It shows a photo (the sky, the
// moon, and the terrain in layers, with the marks of a viewfinder), a map (the terrain with the
// line of the spots), or a profile (the ground from the spot to the summit). The core calculates
// all positions; this file only draws them.

import type { Horizontal, LinePoint } from "@almucantar/core";
import type { MilkyWayDust } from "@almucantar/web-shared/milkyway";
import { litOutline, towardSun } from "@almucantar/web-shared/moon";
import { css, mix } from "@almucantar/web-shared/panorama";
import type { Rgb } from "@almucantar/web-shared/panorama";
import { starColor, starRadius } from "@almucantar/web-shared/stars";
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
const bell = (x: number, center: number, width: number) =>
  Math.exp(-((x - center) ** 2) / (2 * width * width));
const wrap180 = (deg: number) => (((deg % 360) + 540) % 360) - 180;
const DEG = Math.PI / 180;
const MONO = 'ui-monospace, "SF Mono", "Cascadia Mono", Menlo, Consolas, monospace';
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

// --- The colors of the sky ----------------------------------------------------------------------

/** The sky at the top and at the horizon, for an altitude of the sun: warmer than a real sky. */
const SKY: [number, Rgb, Rgb][] = [
  [-18, [12, 10, 26], [26, 21, 44]],
  [-12, [22, 20, 48], [56, 44, 80]],
  [-8, [36, 36, 78], [104, 78, 118]],
  [-5, [56, 58, 108], [176, 124, 146]],
  [-2, [82, 94, 140], [226, 164, 148]],
  [2, [112, 138, 182], [242, 196, 156]],
  [10, [128, 168, 214], [244, 222, 196]],
  [30, [112, 160, 214], [214, 230, 240]],
];

function skyPair(sunAltitudeDeg: number): [Rgb, Rgb] {
  if (sunAltitudeDeg <= SKY[0][0]) return [SKY[0][1], SKY[0][2]];
  for (let i = 1; i < SKY.length; i++) {
    if (sunAltitudeDeg <= SKY[i][0]) {
      const t = (sunAltitudeDeg - SKY[i - 1][0]) / (SKY[i][0] - SKY[i - 1][0]);
      return [mix(SKY[i - 1][1], SKY[i][1], t), mix(SKY[i - 1][2], SKY[i][2], t)];
    }
  }
  const last = SKY[SKY.length - 1];
  return [last[1], last[2]];
}

const BELT: Rgb = [236, 168, 172];
const SHADOW: Rgb = [84, 82, 130];

/**
 * The color of the sky at an altitude. Opposite to the sun, in the twilight, the sky has the pink
 * belt of Venus over the blue shadow of the Earth: the light of a moonrise at dusk.
 */
function skyColor(altitudeDeg: number, sunAltitudeDeg: number, opposite: number): Rgb {
  const [top, low] = skyPair(sunAltitudeDeg);
  let c = mix(low, top, Math.pow(clamp(altitudeDeg / 42), 0.55));
  const strength = bell(sunAltitudeDeg, -3.5, 2.6) * opposite;
  if (strength > 0.01) {
    const center = Math.max(2, -sunAltitudeDeg) + 4.5;
    c = mix(c, BELT, 0.5 * strength * bell(altitudeDeg, center, 3.6));
    c = mix(c, SHADOW, 0.45 * strength * clamp((center - 3 - altitudeDeg) / 4));
  }
  return c;
}

/** The colors of the terrain layers: the far ones in the haze, the near ones dark. */
function layerColors(depth: number, sunAltitudeDeg: number, haze: Rgb): [Rgb, Rgb] {
  const night: [Rgb, Rgb] = [
    [58, 50, 84],
    [16, 12, 26],
  ];
  const dusk: [Rgb, Rgb] = [
    [138, 114, 160],
    [34, 24, 50],
  ];
  const day: [Rgb, Rgb] = [
    [168, 176, 198],
    [58, 64, 74],
  ];
  const pick = (pair: [Rgb, Rgb]) => mix(pair[0], pair[1], depth);
  const base =
    sunAltitudeDeg < -4
      ? mix(pick(night), pick(dusk), clamp((sunAltitudeDeg + 13) / 9))
      : mix(pick(dusk), pick(day), clamp((sunAltitudeDeg + 4) / 14));
  const hazed = mix(base, haze, 0.35 * (1 - depth));
  // Snow on the high and far terrain: the crest is lighter than the foot.
  const snow = mix(
    hazed,
    sunAltitudeDeg > -8 ? [236, 228, 240] : [120, 116, 150],
    0.3 * (1 - depth),
  );
  return [snow, mix(hazed, [10, 8, 18], 0.25 * depth)];
}

// --- The scene ----------------------------------------------------------------------------------

export class Scene {
  private readonly ctx: CanvasRenderingContext2D;
  private width = 0;
  private height = 0;
  private rect: Rect = { x: 0, y: 0, w: 1, h: 1 };
  private readonly sprite: HTMLCanvasElement;
  private mapCache: { key: string; canvas: HTMLCanvasElement } | null = null;
  private shadeCanvas: HTMLCanvasElement | null = null;
  private contours: Float32Array | null = null;

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private stars: Stars | null,
    private readonly milkyWay: MilkyWayDust,
  ) {
    this.ctx = canvas.getContext("2d")!;
    // A soft round sprite for the clouds of the Milky Way.
    this.sprite = document.createElement("canvas");
    this.sprite.width = this.sprite.height = 64;
    const s = this.sprite.getContext("2d")!;
    const g = s.createRadialGradient(32, 32, 0, 32, 32, 32);
    g.addColorStop(0, "rgba(255,255,255,1)");
    g.addColorStop(1, "rgba(255,255,255,0)");
    s.fillStyle = g;
    s.fillRect(0, 0, 64, 64);
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

  private photo(f: Frame, nowMs: number) {
    const ctx = this.ctx;
    const r = this.rect;
    const v = f.view;
    const k = r.w / v.spanDeg;
    const cx = r.x + r.w / 2;
    const cy = r.y + r.h / 2;
    const x = (az: number) => cx + wrap180(az - v.azimuthDeg) * k;
    const y = (alt: number) => cy - (alt - v.altitudeDeg) * k;
    const altAt = (py: number) => v.altitudeDeg + (cy - py) / k;
    const sunAlt = f.sun.altitudeDeg;
    const opposite = (1 - Math.cos(wrap180(v.azimuthDeg - f.sun.azimuthDeg) * DEG)) / 2;

    // The sky.
    const sky = ctx.createLinearGradient(0, r.y, 0, r.y + r.h);
    for (let i = 0; i <= 12; i++) {
      const py = r.y + (r.h * i) / 12;
      sky.addColorStop(i / 12, css(skyColor(altAt(py), sunAlt, opposite)));
    }
    ctx.fillStyle = sky;
    ctx.fillRect(r.x, r.y, r.w, r.h);
    const haze = skyColor(1, sunAlt, opposite);

    // The glow of the sun, also when it is just below the horizon.
    const sx = x(f.sun.azimuthDeg);
    const sy = y(f.sun.altitudeDeg);
    if (sunAlt > -8 && Math.abs(wrap180(f.sun.azimuthDeg - v.azimuthDeg)) < v.spanDeg) {
      const radius = Math.max(r.w, r.h) * 0.6;
      const g = ctx.createRadialGradient(sx, sy, 0, sx, sy, radius);
      const a = 0.55 * clamp((sunAlt + 8) / 8);
      g.addColorStop(0, `rgba(255, 226, 180, ${a})`);
      g.addColorStop(0.3, `rgba(250, 190, 140, ${a * 0.35})`);
      g.addColorStop(1, "rgba(250, 190, 140, 0)");
      ctx.fillStyle = g;
      ctx.fillRect(r.x, r.y, r.w, r.h);
    }

    this.starsAndMilkyWay(f, x, y, k, nowMs);

    // The path of a body, dotted, with the hours.
    if (f.track && f.trackAlpha > 0.01) {
      ctx.save();
      ctx.globalAlpha *= f.trackAlpha;
      ctx.strokeStyle = css(CREAM, 0.85);
      ctx.lineWidth = 1.6;
      ctx.setLineDash([1.5, 5]);
      ctx.lineCap = "round";
      ctx.beginPath();
      let started = false;
      let lastX = 0;
      for (const p of f.track.points) {
        const px = x(p.azimuthDeg);
        const py = y(p.altitudeDeg);
        if (started && Math.abs(px - lastX) < r.w) ctx.lineTo(px, py);
        else ctx.moveTo(px, py);
        started = true;
        lastX = px;
      }
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.font = `11px ${MONO}`;
      ctx.textAlign = "center";
      ctx.fillStyle = css(CREAM, 0.9);
      for (const tick of f.track.ticks) {
        const px = x(tick.direction.azimuthDeg);
        const py = y(tick.direction.altitudeDeg);
        ctx.beginPath();
        ctx.arc(px, py, 2.6, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillText(tick.label, px, py - 9);
      }
      ctx.restore();
    }

    if (f.body) this.body(f.body, x(f.body.direction.azimuthDeg), y(f.body.direction.altitudeDeg));
    if (f.moonFraction > 0.01 && f.moon.altitudeDeg > -2) this.moonDisc(f, x, y, k);

    // The terrain, from the far layer to the near one.
    for (let layer = LAYERS - 1; layer >= 0; layer--) {
      const depth = 1 - layer / (LAYERS - 1);
      const [crest, foot] = layerColors(depth, sunAlt, haze);
      let top = Infinity;
      ctx.beginPath();
      ctx.moveTo(r.x - 2, r.y + r.h + 2);
      for (let px = r.x - 2; px <= r.x + r.w + 2; px += 2) {
        const alt = terrainAltitude(layer, v.azimuthDeg + (px - cx) / k);
        const py = y(alt);
        top = Math.min(top, py);
        ctx.lineTo(px, py);
      }
      ctx.lineTo(r.x + r.w + 2, r.y + r.h + 2);
      ctx.closePath();
      const g = ctx.createLinearGradient(0, Math.max(r.y, top), 0, r.y + r.h);
      g.addColorStop(0, css(crest));
      g.addColorStop(Math.min(1, 0.25 + 0.4 * (1 - depth)), css(foot));
      g.addColorStop(1, css(mix(foot, [8, 6, 14], 0.3)));
      ctx.fillStyle = g;
      ctx.fill();
      // A thin light on the crest: the glow of the sky behind the ridge.
      ctx.strokeStyle = css(mix(crest, CREAM, 0.5), 0.35 * (1 - depth * 0.6));
      ctx.lineWidth = 1;
      ctx.stroke();
    }

    // The vignette of a long lens: the corners a little darker.
    const vignette = ctx.createRadialGradient(
      cx,
      cy,
      Math.min(r.w, r.h) * 0.35,
      cx,
      cy,
      Math.hypot(r.w, r.h) * 0.62,
    );
    vignette.addColorStop(0, "rgba(20, 12, 30, 0)");
    vignette.addColorStop(1, "rgba(20, 12, 30, 0.38)");
    ctx.fillStyle = vignette;
    ctx.fillRect(r.x, r.y, r.w, r.h);

    if (f.pin > 0.01) this.pin(f.pin, x(SIGHT.azimuthDeg), y(SIGHT.altitudeDeg));
    if (f.finder > 0.01) this.finder(f, x, y, k);
    this.hud(f);
  }

  private starsAndMilkyWay(
    f: Frame,
    x: (az: number) => number,
    y: (alt: number) => number,
    k: number,
    nowMs: number,
  ) {
    const ctx = this.ctx;
    const r = this.rect;
    if (f.dust && f.milkyWay > 0.01) {
      const { weight, cloud } = this.milkyWay;
      const size = 2.6 * k;
      ctx.save();
      ctx.globalCompositeOperation = "lighter";
      ctx.fillStyle = "rgb(236, 226, 246)";
      for (let i = 0; i < weight.length; i++) {
        const alt = f.dust[2 * i + 1];
        if (alt < 0) continue;
        const px = x(f.dust[2 * i]);
        const py = y(alt);
        if (px < r.x - size || px > r.x + r.w + size || py < r.y - size || py > r.y + r.h) continue;
        const a = f.milkyWay * weight[i] * clamp(alt / 12);
        if (cloud[i]) {
          ctx.globalAlpha = 0.07 * a * f.print;
          const s = size * (0.6 + (0.8 * ((i * 7919) % 100)) / 100);
          ctx.drawImage(this.sprite, px - s / 2, py - s / 2, s, s);
        } else {
          ctx.globalAlpha = 0.5 * a * f.print;
          ctx.fillRect(px, py, 1, 1);
        }
      }
      ctx.restore();
    }
    const s = this.stars;
    if (!s || !f.stars || f.limitMag < 0.5) return;
    let count = 0;
    while (count < s.mag.length && s.mag[count] <= f.limitMag) count++;
    for (let i = count - 1; i >= 0; i--) {
      const alt = f.stars[2 * i + 1];
      if (alt < 0) continue;
      const px = x(f.stars[2 * i]);
      const py = y(alt);
      if (px < r.x || px > r.x + r.w || py < r.y || py > r.y + r.h) continue;
      const mag = s.mag[i];
      const fade = clamp(0.3 + (f.limitMag - mag) / 1.5) * clamp(0.3 + alt / 8);
      const twinkle = mag < 2 ? 0.85 + 0.15 * Math.sin(nowMs / 650 + i) : 1;
      ctx.fillStyle = `rgba(${starColor(s.bv[i])}, ${fade * twinkle})`;
      ctx.beginPath();
      ctx.arc(px, py, starRadius(mag) * 0.8, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  private body(b: Body, px: number, py: number) {
    const ctx = this.ctx;
    if (b.kind === "sun") {
      const g = ctx.createRadialGradient(px, py, 0, px, py, 90);
      g.addColorStop(0, "rgba(255, 246, 220, 0.95)");
      g.addColorStop(0.12, "rgba(255, 220, 160, 0.6)");
      g.addColorStop(1, "rgba(255, 200, 140, 0)");
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(px, py, 90, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#fffaf0";
      ctx.beginPath();
      ctx.arc(px, py, 7, 0, Math.PI * 2);
      ctx.fill();
    } else {
      const g = ctx.createRadialGradient(px, py, 0, px, py, 12);
      g.addColorStop(0, "rgba(255, 248, 230, 0.9)");
      g.addColorStop(1, "rgba(255, 248, 230, 0)");
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(px, py, 12, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#fffdf6";
      ctx.beginPath();
      ctx.arc(px, py, 2.6, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.font = `11px ${MONO}`;
    ctx.textAlign = "left";
    ctx.fillStyle = css(CREAM, 0.95);
    ctx.fillText(b.label.toUpperCase(), px + 12, py - 10);
  }

  private moonDisc(f: Frame, x: (az: number) => number, y: (alt: number) => number, k: number) {
    const ctx = this.ctx;
    const mx = x(f.moon.azimuthDeg);
    const my = y(f.moon.altitudeDeg);
    // The apparent radius of the moon is about 0.26°. In a wide view it stays visible.
    const radius = Math.max(3.5, 0.259 * k);
    const warm = clamp((9 - f.moon.altitudeDeg) / 9);
    const color = mix(CREAM, [255, 200, 150], warm * 0.8);
    const glow = ctx.createRadialGradient(mx, my, radius * 0.8, mx, my, radius * 7);
    glow.addColorStop(0, css(color, 0.38 * f.moonFraction));
    glow.addColorStop(0.35, css(color, 0.1 * f.moonFraction));
    glow.addColorStop(1, css(color, 0));
    ctx.fillStyle = glow;
    ctx.fillRect(mx - radius * 7, my - radius * 7, radius * 14, radius * 14);

    // The dark part: a little earthshine.
    ctx.fillStyle = "rgba(80, 70, 100, 0.35)";
    ctx.beginPath();
    ctx.arc(mx, my, radius, 0, Math.PI * 2);
    ctx.fill();

    // The lit part points to the sun.
    const toward = towardSun(f.moon, f.sun, 1);
    const angle = Math.atan2(y(toward.altitudeDeg) - my, x(toward.azimuthDeg) - mx);
    ctx.save();
    ctx.translate(mx, my);
    ctx.rotate(angle);
    ctx.beginPath();
    litOutline(f.moonFraction, 40).forEach(([px, py], i) => {
      if (i === 0) ctx.moveTo(px * radius, py * radius);
      else ctx.lineTo(px * radius, py * radius);
    });
    ctx.closePath();
    const lit = ctx.createRadialGradient(0, 0, 0, 0, 0, radius);
    lit.addColorStop(0, css(mix(color, [255, 255, 250], 0.4)));
    lit.addColorStop(1, css(color));
    ctx.fillStyle = lit;
    ctx.fill();
    // A few soft seas, so the full moon looks like the moon and not like a disc.
    if (radius > 14) {
      ctx.fillStyle = css(mix(color, [170, 150, 150], 0.5), 0.13);
      for (const [sx, sy, sr] of [
        [-0.25, -0.3, 0.28],
        [0.15, -0.35, 0.2],
        [0.25, 0.05, 0.22],
        [-0.1, 0.25, 0.16],
        [-0.45, 0.05, 0.14],
      ]) {
        ctx.beginPath();
        // The seas turn with the disc, so undo the rotation for them.
        const c = Math.cos(-angle);
        const s = Math.sin(-angle);
        ctx.arc((sx * c - sy * s) * radius, (sx * s + sy * c) * radius, sr * radius, 0, 7);
        ctx.fill();
      }
    }
    ctx.restore();
  }

  /** The label of the landmark: a ring on the summit and a tag above it. */
  private pin(alpha: number, px: number, py: number) {
    const ctx = this.ctx;
    ctx.save();
    ctx.globalAlpha *= alpha;
    const ring = 9 + 10 * (1 - alpha);
    ctx.strokeStyle = css(CREAM, 0.95);
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.arc(px, py, ring, 0, Math.PI * 2);
    ctx.stroke();
    for (const [dx, dy] of [
      [0, -1],
      [0, 1],
      [-1, 0],
      [1, 0],
    ]) {
      ctx.beginPath();
      ctx.moveTo(px + dx * (ring + 3), py + dy * (ring + 3));
      ctx.lineTo(px + dx * (ring + 9), py + dy * (ring + 9));
      ctx.stroke();
    }
    const top = py - ring - 52;
    ctx.beginPath();
    ctx.moveTo(px, py - ring - 9);
    ctx.lineTo(px, top + 30);
    ctx.stroke();
    const title = PEAK.name.toUpperCase();
    const detail = `${PEAK.site.elevationM.toFixed(0)} M · ${(SIGHT.distanceM / 1000).toFixed(2)} KM`;
    ctx.font = `600 12px ${MONO}`;
    const w = Math.max(ctx.measureText(title).width, ctx.measureText(detail).width) + 20;
    ctx.fillStyle = "rgba(255, 249, 238, 0.94)";
    ctx.fillRect(px - w / 2, top - 8, w, 38);
    ctx.fillStyle = css(INK);
    ctx.textAlign = "center";
    ctx.fillText(title, px, top + 8);
    ctx.font = `11px ${MONO}`;
    ctx.fillStyle = css(ROSE);
    ctx.fillText(detail, px, top + 23);
    ctx.restore();
  }

  /** The frame of a lens on a full-frame camera (36 × 24 mm), with the parts outside darker. */
  private finder(f: Frame, x: (az: number) => number, y: (alt: number) => number, k: number) {
    const ctx = this.ctx;
    const r = this.rect;
    const hw = (Math.atan(18 / f.focalMm) / DEG) * k;
    const hh = (Math.atan(12 / f.focalMm) / DEG) * k;
    const cx = x(SIGHT.azimuthDeg);
    const cy = y(SIGHT.altitudeDeg + 0.32);
    ctx.save();
    ctx.globalAlpha *= f.finder;
    ctx.fillStyle = "rgba(14, 10, 22, 0.5)";
    ctx.beginPath();
    ctx.rect(r.x, r.y, r.w, r.h);
    ctx.rect(cx + hw, cy - hh, -2 * hw, 2 * hh);
    ctx.fill("evenodd");
    ctx.strokeStyle = css(CREAM, 0.9);
    ctx.lineWidth = 1.2;
    ctx.strokeRect(cx - hw, cy - hh, 2 * hw, 2 * hh);
    ctx.strokeStyle = css(CREAM, 0.22);
    ctx.lineWidth = 1;
    for (const t of [1 / 3, 2 / 3]) {
      ctx.beginPath();
      ctx.moveTo(cx - hw + 2 * hw * t, cy - hh);
      ctx.lineTo(cx - hw + 2 * hw * t, cy + hh);
      ctx.moveTo(cx - hw, cy - hh + 2 * hh * t);
      ctx.lineTo(cx + hw, cy - hh + 2 * hh * t);
      ctx.stroke();
    }
    ctx.font = `11px ${MONO}`;
    ctx.fillStyle = css(CREAM, 0.9);
    ctx.textAlign = "left";
    ctx.fillText(`${f.focalMm} MM · 36 × 24`, cx - hw, cy + hh + 16);
    ctx.restore();
  }

  /** The marks and the texts of a viewfinder. */
  private hud(f: Frame) {
    const ctx = this.ctx;
    const r = this.rect;
    const inset = 14;
    const arm = 18;
    ctx.save();
    ctx.strokeStyle = css(CREAM, 0.75);
    ctx.lineWidth = 1.5;
    const corners: [number, number, number, number][] = [
      [r.x + inset, r.y + inset, 1, 1],
      [r.x + r.w - inset, r.y + inset, -1, 1],
      [r.x + inset, r.y + r.h - inset, 1, -1],
      [r.x + r.w - inset, r.y + r.h - inset, -1, -1],
    ];
    for (const [px, py, dx, dy] of corners) {
      ctx.beginPath();
      ctx.moveTo(px + dx * arm, py);
      ctx.lineTo(px, py);
      ctx.lineTo(px, py + dy * arm);
      ctx.stroke();
    }
    // The small crosshair in the center.
    const cx = r.x + r.w / 2;
    const cy = r.y + r.h / 2;
    ctx.strokeStyle = css(CREAM, 0.35);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(cx - 7, cy);
    ctx.lineTo(cx + 7, cy);
    ctx.moveTo(cx, cy - 7);
    ctx.lineTo(cx, cy + 7);
    ctx.stroke();

    ctx.font = `11px ${MONO}`;
    ctx.fillStyle = css(CREAM, 0.88);
    ctx.textBaseline = "middle";
    const small = r.w < 420;
    ctx.textAlign = "left";
    ctx.fillText(f.hud.topLeft, r.x + inset + 10, r.y + inset + 10);
    if (!small) {
      ctx.textAlign = "right";
      ctx.fillText(f.hud.topRight, r.x + r.w - inset - 10, r.y + inset + 10);
    }
    // The data strip at the bottom, like the display of a camera.
    ctx.fillStyle = "rgba(14, 10, 22, 0.42)";
    ctx.fillRect(r.x, r.y + r.h - 30, r.w, 30);
    ctx.fillStyle = css(CREAM, 0.92);
    ctx.textAlign = "center";
    ctx.fillText(f.hud.bottom, r.x + r.w / 2, r.y + r.h - 15);
    ctx.restore();
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
