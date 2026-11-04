// The first-person photo of shotradar: the sky in the light of the sun, the stars and the Milky
// Way, the sun or a planet, the moon in its phase, the terrain in layers from far to near, the label
// of the landmark, the frame of a lens, and the marks of a viewfinder. The website and the PWA of
// shotradar draw the same photo. The core calculates all directions; this module only draws.

import type { Horizontal } from "@almucantar/core";
import type { MilkyWayDust } from "./milkyway";
import { litOutline, towardSun } from "./moon";
import { css, mix } from "./panorama";
import type { Rgb } from "./panorama";
import { starColor, starRadius } from "./stars";
import type { Stars } from "./stars";

/** The direction in the center of the photo, and the azimuth range across its width. */
export interface PhotoView {
  azimuthDeg: number;
  altitudeDeg: number;
  spanDeg: number;
}

/** The terrain in layers: layer 0 is the nearest ground, the last layer the farthest. */
export interface PhotoTerrain {
  layers: number;
  /** The altitude of the terrain of a layer at an azimuth, or NaN where the layer has none. */
  altitude(layer: number, azimuthDeg: number): number;
}

/** The path of a body over some hours, with a label at each full hour. */
export interface PhotoTrack {
  points: Horizontal[];
  ticks: { direction: Horizontal; label: string }[];
}

/** A body in addition to the moon: the sun or a planet. */
export interface PhotoBody {
  kind: "sun" | "planet";
  direction: Horizontal;
  label: string;
}

export interface PhotoFrame {
  view: PhotoView;
  sun: Horizontal;
  moon: Horizontal;
  moonFraction: number;
  terrain: PhotoTerrain;
  track?: PhotoTrack | null;
  trackAlpha?: number;
  body?: PhotoBody | null;
  /** The stars: the catalogue, their directions (azimuth, altitude pairs), and the faintest one. */
  stars?: { stars: Stars; directions: Float64Array; limitMag: number } | null;
  /** The Milky Way: the points, their directions, and its strength from 0 to 1. */
  dust?: { milkyWay: MilkyWayDust; directions: Float64Array; strength: number } | null;
  /** The label of the landmark: a ring and a tag. alpha from 0 to 1. */
  pin?: { direction: Horizontal; title: string; detail: string; alpha: number } | null;
  /** The frame of a lens on a full-frame camera, around `center`. */
  finder?: { center: Horizontal; focalMm: number; alpha: number } | null;
  /** The texts of the viewfinder; null draws no viewfinder. */
  hud?: { topLeft: string; topRight: string; bottom: string } | null;
  /** The opacity of the stars and the Milky Way, for a print that fades in. */
  alpha?: number;
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
export const MONO = 'ui-monospace, "SF Mono", "Cascadia Mono", Menlo, Consolas, monospace';

// The colors of the logo of shotradar: dusk violet, rose, and cream.
const INK: Rgb = [42, 31, 61];
const ROSE: Rgb = [138, 74, 85];
const CREAM: Rgb = [255, 243, 214];

/** The angle of view across the long side of a full-frame camera (36 × 24 mm). */
export function lensSpanDeg(focalMm: number): number {
  return (2 * Math.atan(18 / focalMm)) / DEG;
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
export function skyColor(altitudeDeg: number, sunAltitudeDeg: number, opposite: number): Rgb {
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

// --- The photo ----------------------------------------------------------------------------------

let sprite: HTMLCanvasElement | null = null;

/** A soft round sprite for the clouds of the Milky Way. */
function cloudSprite(): HTMLCanvasElement {
  if (sprite) return sprite;
  sprite = document.createElement("canvas");
  sprite.width = sprite.height = 64;
  const s = sprite.getContext("2d")!;
  const g = s.createRadialGradient(32, 32, 0, 32, 32, 32);
  g.addColorStop(0, "rgba(255,255,255,1)");
  g.addColorStop(1, "rgba(255,255,255,0)");
  s.fillStyle = g;
  s.fillRect(0, 0, 64, 64);
  return sprite;
}

/** The projection of the photo: a direction to a point in `r`, and back. */
export function photoProjection(r: Rect, v: PhotoView) {
  const k = r.w / v.spanDeg;
  const cx = r.x + r.w / 2;
  const cy = r.y + r.h / 2;
  return {
    k,
    x: (az: number) => cx + wrap180(az - v.azimuthDeg) * k,
    y: (alt: number) => cy - (alt - v.altitudeDeg) * k,
    azimuthAt: (px: number) => v.azimuthDeg + (px - cx) / k,
    altitudeAt: (py: number) => v.altitudeDeg + (cy - py) / k,
  };
}

/** Draws the photo of `f` into the rectangle `r` of the context. The caller clips to `r`. */
export function drawPhoto(ctx: CanvasRenderingContext2D, r: Rect, f: PhotoFrame, nowMs = 0): void {
  const v = f.view;
  const { k, x, y, azimuthAt, altitudeAt } = photoProjection(r, v);
  const cx = r.x + r.w / 2;
  const cy = r.y + r.h / 2;
  const sunAlt = f.sun.altitudeDeg;
  const opposite = (1 - Math.cos(wrap180(v.azimuthDeg - f.sun.azimuthDeg) * DEG)) / 2;

  // The sky.
  const sky = ctx.createLinearGradient(0, r.y, 0, r.y + r.h);
  for (let i = 0; i <= 12; i++) {
    const py = r.y + (r.h * i) / 12;
    sky.addColorStop(i / 12, css(skyColor(altitudeAt(py), sunAlt, opposite)));
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

  starsAndMilkyWay(ctx, r, f, x, y, k, nowMs);

  // The path of a body, dotted, with the hours.
  const trackAlpha = f.trackAlpha ?? 1;
  if (f.track && trackAlpha > 0.01) {
    ctx.save();
    ctx.globalAlpha *= trackAlpha;
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

  if (f.body)
    drawBody(ctx, f.body, x(f.body.direction.azimuthDeg), y(f.body.direction.altitudeDeg));
  if (f.moonFraction > 0.01 && f.moon.altitudeDeg > -2) moonDisc(ctx, f, x, y, k);

  // The terrain, from the far layer to the near one.
  const layers = f.terrain.layers;
  for (let layer = layers - 1; layer >= 0; layer--) {
    const depth = layers > 1 ? 1 - layer / (layers - 1) : 1;
    const [crest, foot] = layerColors(depth, sunAlt, haze);
    let top = Infinity;
    ctx.beginPath();
    ctx.moveTo(r.x - 2, r.y + r.h + 2);
    for (let px = r.x - 2; px <= r.x + r.w + 2; px += 2) {
      const alt = f.terrain.altitude(layer, azimuthAt(px));
      // A part without terrain in this layer lies below the photo.
      const py = Number.isFinite(alt) ? y(alt) : r.y + r.h + 2;
      top = Math.min(top, py);
      ctx.lineTo(px, py);
    }
    ctx.lineTo(r.x + r.w + 2, r.y + r.h + 2);
    ctx.closePath();
    const g = ctx.createLinearGradient(
      0,
      Math.max(r.y, Math.min(top, r.y + r.h - 1)),
      0,
      r.y + r.h,
    );
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

  if (f.pin && f.pin.alpha > 0.01) {
    drawPin(ctx, f.pin, x(f.pin.direction.azimuthDeg), y(f.pin.direction.altitudeDeg));
  }
  if (f.finder && f.finder.alpha > 0.01) drawFinder(ctx, r, f.finder, x, y, k);
  if (f.hud) drawHud(ctx, r, f.hud);
}

function starsAndMilkyWay(
  ctx: CanvasRenderingContext2D,
  r: Rect,
  f: PhotoFrame,
  x: (az: number) => number,
  y: (alt: number) => number,
  k: number,
  nowMs: number,
) {
  const alpha = f.alpha ?? 1;
  const dust = f.dust;
  if (dust && dust.strength > 0.01) {
    const { weight, cloud } = dust.milkyWay;
    const size = 2.6 * k;
    const image = cloudSprite();
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = "rgb(236, 226, 246)";
    for (let i = 0; i < weight.length; i++) {
      const alt = dust.directions[2 * i + 1];
      if (alt < 0) continue;
      const px = x(dust.directions[2 * i]);
      const py = y(alt);
      if (px < r.x - size || px > r.x + r.w + size || py < r.y - size || py > r.y + r.h) continue;
      const a = dust.strength * weight[i] * clamp(alt / 12);
      if (cloud[i]) {
        ctx.globalAlpha = 0.07 * a * alpha;
        const s = size * (0.6 + (0.8 * ((i * 7919) % 100)) / 100);
        ctx.drawImage(image, px - s / 2, py - s / 2, s, s);
      } else {
        ctx.globalAlpha = 0.5 * a * alpha;
        ctx.fillRect(px, py, 1, 1);
      }
    }
    ctx.restore();
  }
  const s = f.stars;
  if (!s || s.limitMag < 0.5) return;
  let count = 0;
  while (count < s.stars.mag.length && s.stars.mag[count] <= s.limitMag) count++;
  for (let i = count - 1; i >= 0; i--) {
    const alt = s.directions[2 * i + 1];
    if (alt < 0) continue;
    const px = x(s.directions[2 * i]);
    const py = y(alt);
    if (px < r.x || px > r.x + r.w || py < r.y || py > r.y + r.h) continue;
    const mag = s.stars.mag[i];
    const fade = clamp(0.3 + (s.limitMag - mag) / 1.5) * clamp(0.3 + alt / 8);
    const twinkle = mag < 2 ? 0.85 + 0.15 * Math.sin(nowMs / 650 + i) : 1;
    ctx.fillStyle = `rgba(${starColor(s.stars.bv[i])}, ${fade * twinkle})`;
    ctx.beginPath();
    ctx.arc(px, py, starRadius(mag) * 0.8, 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawBody(ctx: CanvasRenderingContext2D, b: PhotoBody, px: number, py: number) {
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

function moonDisc(
  ctx: CanvasRenderingContext2D,
  f: PhotoFrame,
  x: (az: number) => number,
  y: (alt: number) => number,
  k: number,
) {
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
function drawPin(
  ctx: CanvasRenderingContext2D,
  pin: NonNullable<PhotoFrame["pin"]>,
  px: number,
  py: number,
) {
  ctx.save();
  ctx.globalAlpha *= pin.alpha;
  const ring = 9 + 10 * (1 - pin.alpha);
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
  ctx.font = `600 12px ${MONO}`;
  const w = Math.max(ctx.measureText(pin.title).width, ctx.measureText(pin.detail).width) + 20;
  ctx.fillStyle = "rgba(255, 249, 238, 0.94)";
  ctx.fillRect(px - w / 2, top - 8, w, 38);
  ctx.fillStyle = css(INK);
  ctx.textAlign = "center";
  ctx.fillText(pin.title, px, top + 8);
  ctx.font = `11px ${MONO}`;
  ctx.fillStyle = css(ROSE);
  ctx.fillText(pin.detail, px, top + 23);
  ctx.restore();
}

/** The frame of a lens on a full-frame camera (36 × 24 mm), with the parts outside darker. */
function drawFinder(
  ctx: CanvasRenderingContext2D,
  r: Rect,
  finder: NonNullable<PhotoFrame["finder"]>,
  x: (az: number) => number,
  y: (alt: number) => number,
  k: number,
) {
  const hw = (Math.atan(18 / finder.focalMm) / DEG) * k;
  const hh = (Math.atan(12 / finder.focalMm) / DEG) * k;
  const cx = x(finder.center.azimuthDeg);
  const cy = y(finder.center.altitudeDeg);
  ctx.save();
  ctx.globalAlpha *= finder.alpha;
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
  ctx.fillText(`${finder.focalMm} MM · 36 × 24`, cx - hw, cy + hh + 16);
  ctx.restore();
}

/** The marks and the texts of a viewfinder. */
function drawHud(ctx: CanvasRenderingContext2D, r: Rect, hud: NonNullable<PhotoFrame["hud"]>) {
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
  ctx.fillText(hud.topLeft, r.x + inset + 10, r.y + inset + 10);
  if (!small) {
    ctx.textAlign = "right";
    ctx.fillText(hud.topRight, r.x + r.w - inset - 10, r.y + inset + 10);
  }
  // The data strip at the bottom, like the display of a camera.
  if (hud.bottom) {
    ctx.fillStyle = "rgba(14, 10, 22, 0.42)";
    ctx.fillRect(r.x, r.y + r.h - 30, r.w, 30);
    ctx.fillStyle = css(CREAM, 0.92);
    ctx.textAlign = "center";
    ctx.fillText(hud.bottom, r.x + r.w / 2, r.y + r.h - 15);
  }
  ctx.restore();
}
