// The sky in the background: a panorama of the sky with the path of the sun and the moon. The
// azimuth goes to x and the altitude goes to y, so the whole path of a summer day fits on the
// screen. The core calculates all positions; this file only draws them.

import type { Horizontal } from "@sunradar/core";

export interface HourMark {
  direction: Horizontal;
  label: string;
}

export interface Frame {
  sun: Horizontal;
  /** The path of the sun on the shown day, from midnight to midnight. */
  path: Horizontal[];
  hours: HourMark[];
  /** The paths at the solstices, dashed. */
  guides: Horizontal[][];
  guideAlpha: number;
  moon: Horizontal | null;
  /** The illuminated fraction of the moon, from 0 to 1. */
  moonFraction: number;
  /** How visible the moon is, from 0 to 1. The moon is pale in a bright sky. */
  moonAlpha: number;
  /** The path of the moon in the shown night, or none. */
  moonPath: Horizontal[];
  /** The uncertainty of the heading in degrees: a band around the path. 0 draws no band. */
  bandDeg: number;
  /** A window in the wall of a room, in the sky directions that it shows. */
  window: { azimuth: [number, number]; altitude: [number, number]; alpha: number } | null;
  crosshair: Horizontal | null;
  /** A ring on the sun that shows a tap, from 0 to 1. */
  tap: number;
  /** A dark layer that makes the text easier to read, from 0 to 1. */
  dim: number;
  /** The azimuth in the center of the screen. */
  centerAzimuthDeg: number;
}

type Rgb = [number, number, number];

const DEG = Math.PI / 180;

/** The colors of the sky at the top and at the horizon, for a sun altitude. */
const SKY: [number, Rgb, Rgb][] = [
  [-18, [3, 6, 15], [10, 18, 38]],
  [-8, [10, 20, 46], [52, 40, 86]],
  [-2, [24, 42, 86], [196, 104, 92]],
  [3, [34, 70, 132], [242, 160, 96]],
  [12, [44, 96, 168], [236, 200, 150]],
  [30, [40, 106, 190], [150, 202, 238]],
];

function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

function css([r, g, b]: Rgb, alpha = 1): string {
  return `rgba(${Math.round(r)}, ${Math.round(g)}, ${Math.round(b)}, ${alpha})`;
}

function skyColors(altitudeDeg: number): [Rgb, Rgb] {
  if (altitudeDeg <= SKY[0][0]) return [SKY[0][1], SKY[0][2]];
  for (let i = 1; i < SKY.length; i++) {
    const [a1, top1, low1] = SKY[i];
    const [a0, top0, low0] = SKY[i - 1];
    if (altitudeDeg <= a1) {
      const t = (altitudeDeg - a0) / (a1 - a0);
      return [mix(top0, top1, t), mix(low0, low1, t)];
    }
  }
  const last = SKY[SKY.length - 1];
  return [last[1], last[2]];
}

/** A small random generator with a seed, so the stars and the buildings stay the same. */
function random(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const STARS = (() => {
  const next = random(7);
  return Array.from({ length: 420 }, () => ({
    azimuth: next() * 360,
    // More stars high in the sky: the area of a band of altitude goes with its cosine.
    altitude: Math.asin(next()) / DEG,
    size: next() < 0.92 ? 0.6 + next() * 0.6 : 1.3 + next() * 0.7,
    phase: next() * Math.PI * 2,
  }));
})();

interface Building {
  azimuth: number;
  width: number;
  height: number;
  roof: number;
  lights: { x: number; y: number }[];
}

const BUILDINGS: Building[] = (() => {
  const next = random(42);
  const result: Building[] = [];
  let azimuth = 0;
  while (azimuth < 360) {
    const width = 2 + next() * 7;
    // Lower buildings in the south, so the low winter sun stays visible.
    const south = Math.cos((azimuth - 180) * DEG);
    const height = (0.6 + next() * 4.2) * (1 - 0.45 * Math.max(0, south));
    const lights = [];
    for (let i = 0; i < width * height * 0.5; i++) {
      if (next() < 0.35) lights.push({ x: next(), y: 0.15 + next() * 0.75 });
    }
    result.push({ azimuth, width, height, roof: next(), lights });
    azimuth += width + (next() < 0.25 ? next() * 3 : 0);
  }
  return result;
})();

/** The panorama: the mapping from a direction in the sky to a point on the screen. */
class Panorama {
  readonly pxPerDegX: number;
  readonly pxPerDegY: number;
  readonly horizonY: number;

  constructor(
    readonly width: number,
    readonly height: number,
    readonly centerAzimuthDeg: number,
  ) {
    const span = width < 700 ? 170 : 250;
    this.pxPerDegX = width / span;
    this.horizonY = height * (width < 700 ? 0.5 : 0.74);
    this.pxPerDegY = Math.min((this.horizonY * 0.86) / 68, this.pxPerDegX * 2.4);
  }

  x(azimuthDeg: number): number {
    const d = ((((azimuthDeg - this.centerAzimuthDeg) % 360) + 540) % 360) - 180;
    return this.width / 2 + d * this.pxPerDegX;
  }

  y(altitudeDeg: number): number {
    return this.horizonY - altitudeDeg * this.pxPerDegY;
  }
}

export class Sky {
  private readonly ctx: CanvasRenderingContext2D;
  private width = 0;
  private height = 0;

  constructor(private readonly canvas: HTMLCanvasElement) {
    this.ctx = canvas.getContext("2d")!;
  }

  private fit() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const width = this.canvas.clientWidth;
    const height = this.canvas.clientHeight;
    if (width !== this.width || height !== this.height) {
      this.width = width;
      this.height = height;
      this.canvas.width = Math.round(width * dpr);
      this.canvas.height = Math.round(height * dpr);
    }
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  /** The point of a direction on the screen, for the input of the user. */
  panorama(centerAzimuthDeg: number): Panorama {
    return new Panorama(this.canvas.clientWidth, this.canvas.clientHeight, centerAzimuthDeg);
  }

  draw(frame: Frame, nowMs: number) {
    this.fit();
    const ctx = this.ctx;
    const p = new Panorama(this.width, this.height, frame.centerAzimuthDeg);
    const day = Math.min(1, Math.max(0, (frame.sun.altitudeDeg + 6) / 12));

    // The sky.
    const [top, low] = skyColors(frame.sun.altitudeDeg);
    const gradient = ctx.createLinearGradient(0, 0, 0, p.horizonY);
    gradient.addColorStop(0, css(top));
    gradient.addColorStop(1, css(low));
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, this.width, this.height);

    // The glow around the sun, also when it is a little below the horizon.
    if (frame.sun.altitudeDeg > -12) {
      const sx = p.x(frame.sun.azimuthDeg);
      const sy = p.y(frame.sun.altitudeDeg);
      const strength = Math.min(1, (frame.sun.altitudeDeg + 12) / 14);
      const glow = ctx.createRadialGradient(sx, sy, 0, sx, sy, this.height * 0.7);
      glow.addColorStop(0, `rgba(255, 214, 140, ${0.42 * strength})`);
      glow.addColorStop(0.35, `rgba(255, 170, 90, ${0.12 * strength})`);
      glow.addColorStop(1, "rgba(255, 170, 90, 0)");
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, this.width, this.height);
    }

    // The stars.
    const starAlpha = Math.min(1, Math.max(0, (-frame.sun.altitudeDeg - 4) / 8));
    if (starAlpha > 0) {
      ctx.fillStyle = "#fff";
      for (const star of STARS) {
        const x = p.x(star.azimuth);
        const y = p.y(star.altitude);
        if (x < -2 || x > this.width + 2 || y < -2 || y > p.horizonY) continue;
        const twinkle = 0.7 + 0.3 * Math.sin(nowMs / 900 + star.phase);
        ctx.globalAlpha = starAlpha * twinkle * (star.size > 1.2 ? 0.95 : 0.6);
        ctx.beginPath();
        ctx.arc(x, y, star.size, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
    }

    // The solstice paths and the band of the heading uncertainty.
    if (frame.guideAlpha > 0) {
      for (const guide of frame.guides) {
        this.line(p, guide, `rgba(255, 224, 138, ${0.55 * frame.guideAlpha})`, 1.5, [6, 7]);
      }
    }
    if (frame.bandDeg > 0.05) this.band(p, frame.path, frame.bandDeg);

    // The path of the sun, with the hours.
    const above = frame.path.filter((d) => d.altitudeDeg > -8);
    this.line(p, above, "rgba(255, 200, 61, 0.95)", 2.5);
    ctx.font = "600 12px system-ui, -apple-system, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "bottom";
    for (const hour of frame.hours) {
      if (hour.direction.altitudeDeg < -4) continue;
      const x = p.x(hour.direction.azimuthDeg);
      const y = p.y(hour.direction.altitudeDeg);
      ctx.fillStyle = "rgba(255, 224, 138, 0.95)";
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "rgba(255, 240, 210, 0.85)";
      ctx.fillText(hour.label, x, y - 7);
    }

    // The moon and its path.
    if (frame.moonPath.length) this.line(p, frame.moonPath, "rgba(214, 226, 255, 0.7)", 2, [2, 6]);
    if (frame.moon && frame.moon.altitudeDeg > -2 && frame.moonAlpha > 0.01) {
      this.moon(p, frame.moon, frame.sun, frame.moonFraction, frame.moonAlpha, day);
    }

    // The sun.
    const sx = p.x(frame.sun.azimuthDeg);
    const sy = p.y(frame.sun.altitudeDeg);
    if (frame.sun.altitudeDeg > -2) {
      ctx.fillStyle = "#fff6d8";
      ctx.shadowColor = "rgba(255, 200, 61, 0.9)";
      ctx.shadowBlur = 28;
      ctx.beginPath();
      ctx.arc(sx, sy, 11, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    }
    if (frame.tap > 0 && frame.tap < 1) {
      ctx.strokeStyle = `rgba(255, 255, 255, ${1 - frame.tap})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(sx, sy, 14 + frame.tap * 46, 0, Math.PI * 2);
      ctx.stroke();
    }

    this.skyline(p, day);

    if (frame.window && frame.window.alpha > 0) this.room(p, frame.window, day);
    if (frame.crosshair) this.crosshair(p, frame.crosshair, frame.window?.altitude);

    if (frame.dim > 0) {
      ctx.fillStyle = `rgba(4, 8, 18, ${frame.dim})`;
      ctx.fillRect(0, 0, this.width, this.height);
    }
  }

  /** Draws a line through directions. A jump across the edge of the panorama starts a new line. */
  private line(
    p: Panorama,
    directions: Horizontal[],
    color: string,
    width: number,
    dash?: number[],
  ) {
    const ctx = this.ctx;
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.setLineDash(dash ?? []);
    ctx.beginPath();
    let last: number | null = null;
    for (const d of directions) {
      const x = p.x(d.azimuthDeg);
      const y = p.y(d.altitudeDeg);
      if (last === null || Math.abs(x - last) > this.width / 2) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
      last = x;
    }
    ctx.stroke();
    ctx.setLineDash([]);
  }

  /** The area where the path can be if the heading is wrong by up to `deg`. */
  private band(p: Panorama, path: Horizontal[], deg: number) {
    const ctx = this.ctx;
    const shown = path.filter((d) => d.altitudeDeg > -4);
    ctx.lineWidth = Math.max(3, 2 * deg * p.pxPerDegX);
    ctx.strokeStyle = "rgba(127, 216, 255, 0.16)";
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    let last: number | null = null;
    for (const d of shown) {
      const x = p.x(d.azimuthDeg);
      const y = p.y(d.altitudeDeg);
      if (last === null || Math.abs(x - last) > this.width / 2) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
      last = x;
    }
    ctx.stroke();
  }

  /** The moon with its phase. The bright side faces the sun. */
  private moon(
    p: Panorama,
    moon: Horizontal,
    sun: Horizontal,
    fraction: number,
    alpha: number,
    day: number,
  ) {
    const ctx = this.ctx;
    const x = p.x(moon.azimuthDeg);
    const y = p.y(moon.altitudeDeg);
    const r = 10;
    // The direction to the sun on the screen. Along the panorama, the shorter way round.
    const dAz = ((((sun.azimuthDeg - moon.azimuthDeg) % 360) + 540) % 360) - 180;
    const angle = Math.atan2(
      -(sun.altitudeDeg - moon.altitudeDeg) * p.pxPerDegY,
      dAz * p.pxPerDegX,
    );
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(angle);
    ctx.globalAlpha = alpha;
    // The dark part of the moon is visible only in a dark sky.
    ctx.fillStyle = `rgba(140, 150, 180, ${0.25 * (1 - day)})`;
    ctx.beginPath();
    ctx.arc(0, 0, r, 0, Math.PI * 2);
    ctx.fill();
    // The lit part: the half toward the sun, and an ellipse for the terminator.
    const k = 1 - 2 * fraction;
    ctx.fillStyle = "#eef2ff";
    ctx.shadowColor = "rgba(220, 230, 255, 0.8)";
    ctx.shadowBlur = 16 * (1 - day);
    ctx.beginPath();
    ctx.arc(0, 0, r, -Math.PI / 2, Math.PI / 2);
    ctx.ellipse(0, 0, Math.abs(k) * r, r, 0, Math.PI / 2, -Math.PI / 2, k > 0);
    ctx.fill();
    ctx.restore();
  }

  private skyline(p: Panorama, day: number) {
    const ctx = this.ctx;
    const ground: Rgb = mix([5, 7, 13], [22, 30, 44], day);
    ctx.fillStyle = css(ground);
    ctx.fillRect(0, p.horizonY, this.width, this.height - p.horizonY);
    for (const b of BUILDINGS) {
      const x0 = p.x(b.azimuth);
      const w = b.width * p.pxPerDegX;
      // The building can be at the edge of the panorama: draw it at both edges.
      for (const x of [x0, x0 - 360 * p.pxPerDegX, x0 + 360 * p.pxPerDegX]) {
        if (x + w < 0 || x > this.width) continue;
        const h = b.height * p.pxPerDegY;
        const top = p.horizonY - h;
        ctx.fillStyle = css(ground);
        ctx.beginPath();
        if (b.roof < 0.2) {
          ctx.moveTo(x, top + h * 0.15);
          ctx.lineTo(x + w / 2, top - h * 0.12);
          ctx.lineTo(x + w, top + h * 0.15);
        } else {
          ctx.moveTo(x, top);
          ctx.lineTo(x + w, top);
        }
        ctx.lineTo(x + w, p.horizonY + 1);
        ctx.lineTo(x, p.horizonY + 1);
        ctx.fill();
        if (day < 0.7) {
          ctx.fillStyle = `rgba(255, 206, 120, ${0.75 * (1 - day / 0.7)})`;
          for (const light of b.lights) {
            ctx.fillRect(x + light.x * (w - 3), top + light.y * h, 2, 2.5);
          }
        }
      }
    }
  }

  /** A dark wall with a window: only the sky in the window stays visible. */
  private room(p: Panorama, win: NonNullable<Frame["window"]>, day: number) {
    const ctx = this.ctx;
    const x0 = p.x(win.azimuth[0]);
    const x1 = p.x(win.azimuth[1]);
    const y0 = p.y(win.altitude[1]);
    const y1 = p.y(win.altitude[0]);
    const wall: Rgb = mix([10, 11, 16], [36, 34, 38], day);
    ctx.save();
    ctx.globalAlpha = win.alpha;
    ctx.fillStyle = css(wall);
    ctx.beginPath();
    ctx.rect(0, 0, this.width, this.height);
    ctx.roundRect(x0, y0, x1 - x0, y1 - y0, 6);
    ctx.fill("evenodd");
    // The frame and the cross of the window.
    ctx.strokeStyle = css(mix(wall, [255, 255, 255], 0.25));
    ctx.lineWidth = 10;
    ctx.strokeRect(x0, y0, x1 - x0, y1 - y0);
    ctx.lineWidth = 6;
    ctx.beginPath();
    ctx.moveTo((x0 + x1) / 2, y0);
    ctx.lineTo((x0 + x1) / 2, y1);
    ctx.moveTo(x0, y0 + (y1 - y0) * 0.38);
    ctx.lineTo(x1, y0 + (y1 - y0) * 0.38);
    ctx.stroke();
    // The window sill.
    ctx.fillStyle = css(mix(wall, [255, 255, 255], 0.18));
    ctx.fillRect(x0 - 18, y1 + 5, x1 - x0 + 36, 10);
    ctx.restore();
  }

  /** The crosshair. The vertical line stays in the altitude range `span`, if there is one. */
  private crosshair(p: Panorama, direction: Horizontal, span?: [number, number]) {
    const ctx = this.ctx;
    const x = p.x(direction.azimuthDeg);
    const y = p.y(direction.altitudeDeg);
    ctx.strokeStyle = "rgba(255, 255, 255, 0.95)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(x, y, 16, 0, Math.PI * 2);
    for (const [dx, dy] of [
      [1, 0],
      [-1, 0],
      [0, 1],
      [0, -1],
    ]) {
      ctx.moveTo(x + dx * 10, y + dy * 10);
      ctx.lineTo(x + dx * 24, y + dy * 24);
    }
    ctx.stroke();
    // The vertical line through the crosshair, as in the app.
    ctx.setLineDash([3, 6]);
    ctx.strokeStyle = "rgba(255, 255, 255, 0.4)";
    ctx.beginPath();
    ctx.moveTo(x, span ? p.y(span[1]) : 0);
    ctx.lineTo(x, span ? p.y(span[0]) : p.horizonY);
    ctx.stroke();
    ctx.setLineDash([]);
  }
}
