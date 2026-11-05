// The sky in the background: a panorama of the sky with the path of the sun and the moon. The
// azimuth goes to x and the altitude goes to y, so the whole path of a summer day fits on the
// screen. The core calculates all positions; this file only draws them.

import type { Horizontal } from "@almucantar/core";
import { Panorama, css, mix, random, skyColors } from "@almucantar/web-shared/panorama";
import type { Rgb } from "@almucantar/web-shared/panorama";

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
  /** A ring on the sun that shows a tap, from 0 to 1. */
  tap: number;
  /** A dark layer that makes the text easier to read, from 0 to 1. */
  dim: number;
  /** The azimuth in the center of the screen. */
  centerAzimuthDeg: number;
}

const DEG = Math.PI / 180;

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
    this.hourMarks(p, frame.hours);

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

    this.skyline(p, day, frame.sun);

    if (frame.window && frame.window.alpha > 0) {
      this.room(p, frame.window, day, frame.sun);
      // The hour marks stay on top of the frame and the cross of the window, but only in the
      // window: the wall hides the path, so a mark there would have no path.
      ctx.save();
      ctx.globalAlpha = frame.window.alpha;
      ctx.beginPath();
      const [x0, y0, x1, y1] = windowRect(p, frame.window);
      ctx.rect(x0, y0, x1 - x0, y1 - y0);
      ctx.clip();
      this.hourMarks(p, frame.hours);
      ctx.restore();
    }

    if (frame.dim > 0) {
      // The page below the chapters is bright: the sky fades to the color of the paper.
      ctx.fillStyle = `rgba(250, 246, 238, ${frame.dim})`;
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

  /** The dots and the labels of the hours on the path. */
  private hourMarks(p: Panorama, hours: HourMark[]) {
    const ctx = this.ctx;
    ctx.font = "600 12px system-ui, -apple-system, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "bottom";
    for (const hour of hours) {
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
  }

  /**
   * The area where the path can be if the heading is wrong by up to `deg`. A wrong heading turns
   * the view around the vertical axis, so each point of the path moves left or right (in azimuth)
   * at its altitude, not up or down. The band is the area that the path covers in this move: for
   * each piece of the path, the parallelogram between the piece moved left and moved right.
   */
  private band(p: Panorama, path: Horizontal[], deg: number) {
    const ctx = this.ctx;
    const shown = path.filter((d) => d.altitudeDeg > -4);
    const dx = Math.max(1.5, deg * p.pxPerDegX);
    ctx.fillStyle = "rgba(127, 216, 255, 0.16)";
    // One path for all pieces, with the same orientation, so the overlaps fill only once.
    ctx.beginPath();
    for (let i = 1; i < shown.length; i++) {
      const ax = p.x(shown[i - 1].azimuthDeg);
      const bx = p.x(shown[i].azimuthDeg);
      if (Math.abs(bx - ax) > this.width / 2) continue; // across the edge of the panorama
      const ay = p.y(shown[i - 1].altitudeDeg);
      const by = p.y(shown[i].altitudeDeg);
      // Clockwise on the screen: up the left side, down the right side.
      const up = ay >= by;
      const [lowY, highY] = up ? [ay, by] : [by, ay];
      const [lowX, highX] = up ? [ax, bx] : [bx, ax];
      ctx.moveTo(lowX - dx, lowY);
      ctx.lineTo(highX - dx, highY);
      ctx.lineTo(highX + dx, highY);
      ctx.lineTo(lowX + dx, lowY);
      ctx.closePath();
    }
    ctx.fill("nonzero");
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

  private skyline(p: Panorama, day: number, sun: Horizontal) {
    const ctx = this.ctx;
    // In the day the city is a soft blue-grey in the haze, and the ground is light. At night it is
    // dark, with lit windows.
    const building: Rgb = mix([5, 7, 13], [104, 124, 156], day);
    const ground = ctx.createLinearGradient(0, p.horizonY, 0, this.height);
    ground.addColorStop(0, css(mix([5, 7, 13], [126, 144, 170], day)));
    ground.addColorStop(1, css(mix([8, 10, 18], [226, 216, 200], day)));
    ctx.fillStyle = ground;
    ctx.fillRect(0, p.horizonY, this.width, this.height - p.horizonY);
    const sunX = p.x(sun.azimuthDeg);
    const warm = Math.max(0, Math.min(1, 1 - sun.altitudeDeg / 30));
    const rim = `rgba(255, ${Math.round(236 - 50 * warm)}, ${Math.round(196 - 80 * warm)}, ${0.85 * day})`;
    for (const b of BUILDINGS) {
      const x0 = p.x(b.azimuth);
      const w = b.width * p.pxPerDegX;
      for (const x of [x0, x0 - 360 * p.pxPerDegX, x0 + 360 * p.pxPerDegX]) {
        if (x + w < 0 || x > this.width) continue;
        const h = b.height * p.pxPerDegY;
        const top = p.horizonY - h;
        ctx.fillStyle = css(building);
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
        // The edge toward the sun catches the light.
        if (day > 0.05 && sun.altitudeDeg > -1) {
          ctx.fillStyle = rim;
          const left = sunX < x + w / 2;
          ctx.fillRect(left ? x : x + w - 2, top + (b.roof < 0.2 ? h * 0.15 : 0), 2, h);
          ctx.fillRect(x, b.roof < 0.2 ? top + h * 0.15 : top, w, 1.5);
        }
        if (day < 0.7) {
          ctx.fillStyle = `rgba(255, 206, 120, ${0.75 * (1 - day / 0.7)})`;
          for (const light of b.lights) {
            ctx.fillRect(x + light.x * (w - 3), top + light.y * h, 2, 2.5);
          }
        }
      }
    }
  }

  /**
   * A room with a window: a wall, a floor in perspective, and the sunlight that falls through the
   * four panes onto the floor. The patch of light follows the direction and the height of the sun.
   * Only the sky in the window stays visible.
   */
  private room(p: Panorama, win: NonNullable<Frame["window"]>, day: number, sun: Horizontal) {
    const ctx = this.ctx;
    const [x0, y0, x1, y1] = windowRect(p, win);
    // The room in meters: a wide window, 2.8 m wide, with its sill 0.45 m above the floor. The eye
    // is 1.5 m high and 5.2 m from the wall. The scale comes from the window on the screen.
    const width = 2.8;
    const sill = 0.45;
    const s = (x1 - x0) / width;
    const head = sill + (y1 - y0) / s;
    const eye = 1.5;
    const distance = 5.2;
    const xc = (x0 + x1) / 2;
    const wallFloorY = y1 + sill * s;
    /** A point on the floor at `x` meters from the middle and `z` meters from the wall. */
    const floor = (x: number, z: number): [number, number] => {
      const k = distance / Math.max(0.2, distance - z);
      return [xc + x * s * k, y1 - (eye - sill) * s + eye * s * k];
    };

    ctx.save();
    ctx.globalAlpha = win.alpha;
    // The wall: warm plaster in the day, dark at night. A soft light from the window on it.
    const plaster: Rgb = mix([18, 18, 24], [226, 214, 196], day);
    ctx.fillStyle = css(plaster);
    ctx.beginPath();
    ctx.rect(0, 0, this.width, Math.min(this.height, wallFloorY));
    ctx.roundRect(x0, y0, x1 - x0, y1 - y0, 4);
    ctx.fill("evenodd");
    const glow = ctx.createRadialGradient(xc, (y0 + y1) / 2, 0, xc, (y0 + y1) / 2, (x1 - x0) * 1.6);
    glow.addColorStop(0, `rgba(255, 244, 222, ${0.35 * day})`);
    glow.addColorStop(1, "rgba(255, 244, 222, 0)");
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.rect(0, 0, this.width, Math.min(this.height, wallFloorY));
    ctx.rect(x0, y0, x1 - x0, y1 - y0);
    ctx.fill("evenodd");

    // The floor: wooden boards in perspective.
    if (wallFloorY < this.height) {
      const wood: Rgb = mix([20, 16, 14], [168, 124, 88], day);
      const g = ctx.createLinearGradient(0, wallFloorY, 0, this.height);
      g.addColorStop(0, css(mix(wood, [0, 0, 0], 0.25)));
      g.addColorStop(1, css(wood));
      ctx.fillStyle = g;
      ctx.fillRect(0, wallFloorY, this.width, this.height - wallFloorY);
      ctx.strokeStyle = css(mix(wood, [0, 0, 0], 0.18));
      ctx.lineWidth = 1;
      for (let x = -12; x <= 12; x += 0.25) {
        const [ax, ay] = floor(x, 0);
        const [bx, by] = floor(x, distance - 0.4);
        ctx.beginPath();
        ctx.moveTo(ax, ay);
        ctx.lineTo(bx, by);
        ctx.stroke();
      }
      // The skirting board.
      ctx.fillStyle = css(mix(plaster, [255, 255, 255], 0.35));
      ctx.fillRect(0, wallFloorY - 7, this.width, 7);
    }

    // The sunlight on the floor: each pane of the window gives one patch, so the cross of the
    // window throws its shadow. A ray from the sun goes down by tan(altitude) for each meter into
    // the room, and sideways by the angle between the sun and the wall.
    const facing =
      ((((sun.azimuthDeg - (win.azimuth[0] + win.azimuth[1]) / 2) % 360) + 540) % 360) - 180;
    if (sun.altitudeDeg > 0.5 && Math.abs(facing) < 80) {
      const t = Math.tan(sun.altitudeDeg * DEG);
      const into = Math.cos(facing * DEG) / t;
      const side = -Math.sin(facing * DEG) / t;
      const strength = Math.min(1, sun.altitudeDeg / 4) * day;
      const crossX = 0;
      const crossH = head - (head - sill) * 0.38;
      const panes: [number, number, number, number][] = [
        [-width / 2, crossX - 0.03, crossH + 0.03, head],
        [crossX + 0.03, width / 2, crossH + 0.03, head],
        [-width / 2, crossX - 0.03, sill, crossH - 0.03],
        [crossX + 0.03, width / 2, sill, crossH - 0.03],
      ];
      ctx.save();
      ctx.beginPath();
      ctx.rect(0, wallFloorY, this.width, this.height - wallFloorY);
      ctx.clip();
      ctx.globalCompositeOperation = "lighter";
      ctx.shadowColor = "rgba(255, 210, 140, 0.9)";
      ctx.shadowBlur = 18;
      ctx.fillStyle = `rgba(255, 214, 150, ${0.42 * strength})`;
      for (const [xa, xb, ha, hb] of panes) {
        const corners = [
          [xa, ha],
          [xb, ha],
          [xb, hb],
          [xa, hb],
        ].map(([x, h]) => floor(x + side * h, into * h));
        ctx.beginPath();
        corners.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
        ctx.closePath();
        ctx.fill();
      }
      ctx.restore();
      // The shaft of light in the air, very faint: the hull of the window and its patch.
      const points: [number, number][] = [
        [x0, y0],
        [x1, y0],
        [x1, y1],
        [x0, y1],
        ...[
          [-width / 2, head],
          [width / 2, head],
          [width / 2, sill],
          [-width / 2, sill],
        ].map(([x, h]) => floor(x + side * h, into * h)),
      ];
      const hull = convexHull(points);
      ctx.save();
      ctx.globalCompositeOperation = "lighter";
      const shaft = ctx.createLinearGradient(0, y0, 0, Math.max(...hull.map(([, y]) => y)));
      shaft.addColorStop(0, `rgba(255, 230, 180, ${0.08 * strength})`);
      shaft.addColorStop(1, `rgba(255, 230, 180, ${0.02 * strength})`);
      ctx.fillStyle = shaft;
      ctx.beginPath();
      hull.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      ctx.closePath();
      ctx.fill();
      ctx.restore();
    }

    // The frame and the cross of the window.
    const frameColor: Rgb = mix([40, 40, 46], [250, 248, 242], day);
    ctx.strokeStyle = css(frameColor);
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
    ctx.fillStyle = css(mix(frameColor, [0, 0, 0], 0.06));
    ctx.fillRect(x0 - 18, y1 + 5, x1 - x0 + 36, 10);
    ctx.restore();
  }
}

/** The convex hull of points on the screen (monotone chain). */
function convexHull(points: [number, number][]): [number, number][] {
  const sorted = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cross = (o: number[], a: number[], b: number[]) =>
    (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lower: [number, number][] = [];
  for (const p of sorted) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0)
      lower.pop();
    lower.push(p);
  }
  const upper: [number, number][] = [];
  for (const p of sorted.reverse()) {
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0)
      upper.pop();
    upper.push(p);
  }
  return [...lower.slice(0, -1), ...upper.slice(0, -1)];
}

/** The window on the screen: left, top, right, and bottom. */
function windowRect(
  p: Panorama,
  win: NonNullable<Frame["window"]>,
): [number, number, number, number] {
  return [p.x(win.azimuth[0]), p.y(win.altitude[1]), p.x(win.azimuth[1]), p.y(win.altitude[0])];
}
