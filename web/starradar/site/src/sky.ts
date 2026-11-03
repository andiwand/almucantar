// The sky in the background of the website: a panorama of the night sky (azimuth to x, altitude
// to y) with the stars, the constellations, the planets, the moon, the Milky Way, and the ground or
// the terrain. The core calculates all positions; this file only draws them.

import type { Horizontal } from "@almucantar/core";
import { Panorama, css, mix, skyColors } from "@almucantar/web-shared/panorama";
import type { PanoramaOptions, Rgb } from "@almucantar/web-shared/panorama";
import { starColor, starRadius } from "@almucantar/web-shared/stars";
import type { Stars } from "@almucantar/web-shared/stars";

export interface PlanetAt {
  name: string;
  color: string;
  direction: Horizontal;
  mag: number;
}

export interface Frame {
  sun: Horizontal;
  moon: Horizontal;
  /** The illuminated fraction of the moon, from 0 to 1. */
  moonFraction: number;
  /** The directions of all stars: [azimuth0, altitude0, ...] (starPositions of the core). */
  stars: Float64Array;
  /** The faintest magnitude that the sky shows. */
  limitMag: number;
  /** The directions of the names of the constellations: [azimuth0, altitude0, ...]. */
  labels: Float64Array;
  linesAlpha: number;
  namesAlpha: number;
  /** A constellation in front, with brighter lines, or -1. */
  highlight: number;
  highlightAlpha: number;
  planets: PlanetAt[];
  /** The tracks of the planets over weeks, one line for each planet. */
  planetTracks: Horizontal[][];
  /** The tracks of the bright stars over hours. */
  starTracks: Horizontal[][];
  milkyWay: Horizontal[];
  milkyWayAlpha: number;
  galacticCenter: Horizontal;
  /** The altitude of the terrain for each azimuth step from 0, or null for a flat horizon. */
  terrain: { stepDeg: number; altitudeDeg: number[] } | null;
  /** The heading error of a compass: a ghost of the constellation lines at this offset. */
  ghostDeg: number;
  /** A ring that shows a tap on this direction, from 0 to 1. */
  tap: { direction: Horizontal; progress: number } | null;
  /** A dark layer that makes the text easier to read, from 0 to 1. */
  dim: number;
  /** The azimuth in the center of the screen. */
  centerAzimuthDeg: number;
}

/** The layout of the starradar website: higher than the one of sunradar, for the stars. */
function starPanorama(width: number): PanoramaOptions {
  return width < 700
    ? { spanDeg: 130, horizonFraction: 0.66, topAltitudeDeg: 62 }
    : { spanDeg: 200, horizonFraction: 0.8, topAltitudeDeg: 72 };
}

export class Sky {
  private readonly ctx: CanvasRenderingContext2D;
  private width = 0;
  private height = 0;

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly stars: Stars,
  ) {
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
    const w = this.canvas.clientWidth;
    return new Panorama(w, this.canvas.clientHeight, centerAzimuthDeg, starPanorama(w));
  }

  draw(f: Frame, nowMs: number) {
    this.fit();
    const ctx = this.ctx;
    const p = new Panorama(this.width, this.height, f.centerAzimuthDeg, starPanorama(this.width));
    const day = Math.min(1, Math.max(0, (f.sun.altitudeDeg + 6) / 12));

    // The sky, and the glow of the sun in the twilight.
    const [top, low] = skyColors(f.sun.altitudeDeg);
    const gradient = ctx.createLinearGradient(0, 0, 0, p.horizonY);
    gradient.addColorStop(0, css(top));
    gradient.addColorStop(1, css(low));
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, this.width, this.height);
    if (f.sun.altitudeDeg > -16) {
      const sx = p.x(f.sun.azimuthDeg);
      const sy = p.y(Math.max(f.sun.altitudeDeg, -6));
      const strength = Math.min(1, (f.sun.altitudeDeg + 16) / 16);
      const glow = ctx.createRadialGradient(sx, sy, 0, sx, sy, this.height * 0.8);
      glow.addColorStop(0, `rgba(255, 170, 100, ${0.35 * strength})`);
      glow.addColorStop(0.4, `rgba(200, 110, 120, ${0.1 * strength})`);
      glow.addColorStop(1, "rgba(200, 110, 120, 0)");
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, this.width, this.height);
    }

    // The Milky Way: wide soft strokes on top of each other, so the edges fade.
    if (f.milkyWayAlpha > 0.01) {
      for (const [width, alpha] of [
        [90, 0.035],
        [56, 0.045],
        [28, 0.06],
      ]) {
        this.line(p, f.milkyWay, `rgba(200, 210, 245, ${alpha * f.milkyWayAlpha})`, width);
      }
      const gx = p.x(f.galacticCenter.azimuthDeg);
      const gy = p.y(f.galacticCenter.altitudeDeg);
      const glow = ctx.createRadialGradient(gx, gy, 0, gx, gy, 70);
      glow.addColorStop(0, `rgba(230, 220, 200, ${0.22 * f.milkyWayAlpha})`);
      glow.addColorStop(1, "rgba(230, 220, 200, 0)");
      ctx.fillStyle = glow;
      ctx.fillRect(gx - 70, gy - 70, 140, 140);
    }

    // The tracks of the stars (the sky turns) and of the planets (they wander).
    for (const track of f.starTracks) this.line(p, track, "rgba(200, 215, 255, 0.35)", 1.2);
    for (const track of f.planetTracks)
      this.line(p, track, "rgba(255, 210, 150, 0.6)", 1.5, [3, 4]);

    // The constellation lines, and a ghost of them if the compass is wrong.
    if (f.linesAlpha > 0.01) {
      this.constellationLines(p, f, 0, `rgba(127, 170, 255, ${0.4 * f.linesAlpha})`, 1);
      if (f.highlight >= 0 && f.highlightAlpha > 0.01) {
        this.constellationLines(
          p,
          f,
          0,
          `rgba(170, 205, 255, ${0.85 * f.highlightAlpha})`,
          2,
          f.highlight,
        );
      }
      if (f.ghostDeg > 0.05) {
        this.constellationLines(p, f, f.ghostDeg, `rgba(127, 216, 255, 0.45)`, 1);
        this.constellationLines(p, f, -f.ghostDeg, `rgba(127, 216, 255, 0.45)`, 1);
      }
    }

    // The stars, the faintest first, with a little twinkle.
    const s = this.stars;
    let count = 0;
    while (count < s.mag.length && s.mag[count] <= f.limitMag) count++;
    for (let i = count - 1; i >= 0; i--) {
      const az = f.stars[2 * i];
      const alt = f.stars[2 * i + 1];
      if (alt < -1) continue;
      const x = p.x(az);
      const y = p.y(alt);
      if (x < -4 || x > this.width + 4 || y < -4 || y > p.horizonY + 2) continue;
      const mag = s.mag[i];
      const fade = Math.min(1, 0.3 + (f.limitMag - mag) / 1.5);
      const twinkle = mag < 2.5 ? 0.85 + 0.15 * Math.sin(nowMs / 700 + i) : 1;
      const color = starColor(s.bv[i]);
      const r = starRadius(mag) * 0.9;
      if (mag < 1.2) {
        ctx.fillStyle = `rgba(${color}, ${0.16 * fade})`;
        ctx.beginPath();
        ctx.arc(x, y, r * 3, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.fillStyle = `rgba(${color}, ${fade * twinkle})`;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
    }

    ctx.textAlign = "center";
    ctx.textBaseline = "bottom";
    // The names of the brightest stars.
    if (f.namesAlpha > 0.01) {
      ctx.font = "12px system-ui, -apple-system, sans-serif";
      ctx.fillStyle = `rgba(220, 228, 255, ${0.8 * f.namesAlpha})`;
      for (let i = 0; i < count && s.mag[i] <= 1.4; i++) {
        const name = s.names.get(i);
        const alt = f.stars[2 * i + 1];
        if (!name || alt < 1) continue;
        ctx.fillText(name, p.x(f.stars[2 * i]), p.y(alt) - 7);
      }
      ctx.font = "italic 13px system-ui, -apple-system, sans-serif";
      ctx.fillStyle = `rgba(150, 185, 255, ${0.75 * f.namesAlpha})`;
      s.constellations.forEach((c, i) => {
        const alt = f.labels[2 * i + 1];
        if (alt < 3) return;
        ctx.fillText(c.name, p.x(f.labels[2 * i]), p.y(alt));
      });
    }

    // The planets with their names.
    ctx.font = "600 13px system-ui, -apple-system, sans-serif";
    for (const planet of f.planets) {
      if (planet.mag > f.limitMag + 0.5 || planet.direction.altitudeDeg < -1) continue;
      const x = p.x(planet.direction.azimuthDeg);
      const y = p.y(planet.direction.altitudeDeg);
      ctx.fillStyle = `rgba(${planet.color}, 1)`;
      ctx.beginPath();
      ctx.arc(x, y, starRadius(planet.mag) + 1, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "rgba(255, 220, 160, 0.95)";
      ctx.fillText(planet.name, x, y - 8);
    }

    // The moon with its phase.
    if (f.moon.altitudeDeg > -1 && f.moonFraction > 0.02) this.moon(p, f, day);

    // The ring of a tap.
    if (f.tap && f.tap.progress > 0 && f.tap.progress < 1) {
      ctx.strokeStyle = `rgba(255, 255, 255, ${1 - f.tap.progress})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(
        p.x(f.tap.direction.azimuthDeg),
        p.y(f.tap.direction.altitudeDeg),
        10 + f.tap.progress * 40,
        0,
        Math.PI * 2,
      );
      ctx.stroke();
    }

    this.ground(p, f, day);

    if (f.dim > 0) {
      ctx.fillStyle = `rgba(3, 6, 15, ${f.dim})`;
      ctx.fillRect(0, 0, this.width, this.height);
    }
  }

  /** The lines of all constellations (or of one), turned by `offsetDeg` in azimuth. */
  private constellationLines(
    p: Panorama,
    f: Frame,
    offsetDeg: number,
    color: string,
    width: number,
    only = -1,
  ) {
    const ctx = this.ctx;
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.beginPath();
    this.stars.constellations.forEach((c, k) => {
      if (only >= 0 && k !== only) return;
      for (const line of c.lines) {
        for (let j = 1; j < line.length; j++) {
          const a = line[j - 1];
          const b = line[j];
          const aAlt = f.stars[2 * a + 1];
          const bAlt = f.stars[2 * b + 1];
          if (aAlt < -2 && bAlt < -2) continue;
          const ax = p.x(f.stars[2 * a] + offsetDeg);
          const bx = p.x(f.stars[2 * b] + offsetDeg);
          if (Math.abs(ax - bx) > this.width / 2) continue; // across the edge of the panorama
          ctx.moveTo(ax, p.y(aAlt));
          ctx.lineTo(bx, p.y(bAlt));
        }
      }
    });
    ctx.stroke();
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
      // Near the zenith the azimuth changes fast, so a line there would run across the panorama.
      if (d.altitudeDeg > 80) {
        last = null;
        continue;
      }
      const x = p.x(d.azimuthDeg);
      const y = p.y(d.altitudeDeg);
      if (last === null || Math.abs(x - last) > this.width / 2) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
      last = x;
    }
    ctx.stroke();
    ctx.setLineDash([]);
  }

  /** The moon with its phase. The bright side faces the sun. */
  private moon(p: Panorama, f: Frame, day: number) {
    const ctx = this.ctx;
    const x = p.x(f.moon.azimuthDeg);
    const y = p.y(f.moon.altitudeDeg);
    const r = 10;
    const dAz = ((((f.sun.azimuthDeg - f.moon.azimuthDeg) % 360) + 540) % 360) - 180;
    const angle = Math.atan2(
      -(f.sun.altitudeDeg - f.moon.altitudeDeg) * p.pxPerDegY,
      dAz * p.pxPerDegX,
    );
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(angle);
    ctx.fillStyle = `rgba(140, 150, 180, ${0.25 * (1 - day)})`;
    ctx.beginPath();
    ctx.arc(0, 0, r, 0, Math.PI * 2);
    ctx.fill();
    const k = 1 - 2 * f.moonFraction;
    ctx.fillStyle = "#eef2ff";
    ctx.shadowColor = "rgba(220, 230, 255, 0.8)";
    ctx.shadowBlur = 16 * (1 - day);
    ctx.beginPath();
    ctx.arc(0, 0, r, -Math.PI / 2, Math.PI / 2);
    ctx.ellipse(0, 0, Math.abs(k) * r, r, 0, Math.PI / 2, -Math.PI / 2, k > 0);
    ctx.fill();
    ctx.restore();
  }

  /** The ground below the horizon: flat, or the terrain with its line. */
  private ground(p: Panorama, f: Frame, day: number) {
    const ctx = this.ctx;
    const color: Rgb = mix([4, 6, 12], [20, 28, 40], day);
    ctx.fillStyle = css(color);
    if (!f.terrain) {
      ctx.fillRect(0, p.horizonY, this.width, this.height - p.horizonY);
      ctx.strokeStyle = "rgba(255, 255, 255, 0.25)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, p.horizonY);
      ctx.lineTo(this.width, p.horizonY);
      ctx.stroke();
      return;
    }
    // The terrain from the left edge to the right edge of the screen, in steps of the profile.
    const { stepDeg, altitudeDeg } = f.terrain;
    const span = this.width / p.pxPerDegX;
    const left = f.centerAzimuthDeg - span / 2 - stepDeg;
    const points: [number, number][] = [];
    for (let a = left; a <= left + span + 2 * stepDeg; a += stepDeg) {
      const index = Math.round((((a % 360) + 360) % 360) / stepDeg) % altitudeDeg.length;
      points.push([
        this.width / 2 + (a - f.centerAzimuthDeg) * p.pxPerDegX,
        p.y(altitudeDeg[index]),
      ]);
    }
    ctx.beginPath();
    ctx.moveTo(points[0][0], this.height);
    for (const [x, y] of points) ctx.lineTo(x, y);
    ctx.lineTo(points[points.length - 1][0], this.height);
    ctx.closePath();
    ctx.fill();
    ctx.strokeStyle = "rgba(170, 210, 160, 0.7)";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    points.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.stroke();
  }
}
