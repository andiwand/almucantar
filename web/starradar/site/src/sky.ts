// The sky in the background of the website: a panorama of the night sky (azimuth to x, altitude
// to y) with the stars, the constellations, the planets, the moon, the Milky Way, and the ground
// with its hills or mountains. The core calculates all positions; this file only draws them.

import type { Horizontal } from "@almucantar/core";
import type { MilkyWayDust } from "@almucantar/web-shared/milkyway";
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

/** The altitude of the ground for each azimuth step from 0: hills, or the terrain of a place. */
export interface Ground {
  stepDeg: number;
  altitudeDeg: number[];
}

/** The scene of the compass chapter: a phone sees one constellation at a wrong heading. */
export interface Alignment {
  /** The constellation and its brightest star, as indices of the star data. */
  constellation: number;
  star: number;
  /** The error of the heading: the phone draws the constellation this far off, in degrees. */
  offsetDeg: number;
  /** The visibility of the phone frame, from 0 to 1. */
  alpha: number;
  /** A ring that shows the tap on the star, from 0 to 1. */
  tap: number;
}

export interface Frame {
  sun: Horizontal;
  moon: Horizontal;
  /** The illuminated fraction of the moon, from 0 to 1. 0 hides the moon. */
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
  /** The directions of the points of the Milky Way: [azimuth0, altitude0, ...]. */
  dust: Float64Array;
  milkyWayAlpha: number;
  ground: Ground;
  /** The ground is the terrain of a real place: a brighter line along the crest. */
  realTerrain: boolean;
  alignment: Alignment | null;
  /** A dark layer that makes the text easier to read, from 0 to 1. */
  dim: number;
  /** The azimuth in the center of the screen. */
  centerAzimuthDeg: number;
}

/** The layout of the starradar website: higher than the one of sunradar, for the stars. */
function starPanorama(width: number): PanoramaOptions {
  return width < 700
    ? { spanDeg: 130, horizonFraction: 0.68, topAltitudeDeg: 62 }
    : { spanDeg: 200, horizonFraction: 0.8, topAltitudeDeg: 72 };
}

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));

/** A soft round sprite for the clouds of the Milky Way, drawn once. */
function cloudSprite(): HTMLCanvasElement {
  const size = 64;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const c = canvas.getContext("2d")!;
  const g = c.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  g.addColorStop(0, "rgba(214, 220, 255, 1)");
  g.addColorStop(0.35, "rgba(190, 200, 245, 0.45)");
  g.addColorStop(1, "rgba(170, 180, 240, 0)");
  c.fillStyle = g;
  c.fillRect(0, 0, size, size);
  return canvas;
}

export class Sky {
  private readonly ctx: CanvasRenderingContext2D;
  private width = 0;
  private height = 0;
  private readonly sprite = cloudSprite();

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly stars: Stars,
    private readonly milkyWay: MilkyWayDust,
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

  draw(f: Frame, nowMs: number) {
    this.fit();
    const ctx = this.ctx;
    const p = new Panorama(this.width, this.height, f.centerAzimuthDeg, starPanorama(this.width));
    const day = clamp((f.sun.altitudeDeg + 6) / 12);

    // The sky, the glow of the sun in the twilight, and the airglow above the horizon.
    const [top, low] = skyColors(f.sun.altitudeDeg);
    const gradient = ctx.createLinearGradient(0, 0, 0, p.horizonY);
    gradient.addColorStop(0, css(top));
    gradient.addColorStop(1, css(low));
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, this.width, this.height);
    if (f.sun.altitudeDeg > -16) {
      const sx = p.x(f.sun.azimuthDeg);
      const sy = p.y(Math.max(f.sun.altitudeDeg, -6));
      const strength = clamp((f.sun.altitudeDeg + 16) / 16);
      const glow = ctx.createRadialGradient(sx, sy, 0, sx, sy, this.height * 0.8);
      glow.addColorStop(0, `rgba(255, 170, 100, ${0.35 * strength})`);
      glow.addColorStop(0.4, `rgba(200, 110, 120, ${0.1 * strength})`);
      glow.addColorStop(1, "rgba(200, 110, 120, 0)");
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, this.width, this.height);
    }
    const night = 1 - day;
    const airglow = ctx.createLinearGradient(0, p.y(14), 0, p.horizonY);
    airglow.addColorStop(0, "rgba(90, 110, 170, 0)");
    airglow.addColorStop(1, `rgba(90, 110, 170, ${0.22 * night})`);
    ctx.fillStyle = airglow;
    ctx.fillRect(0, p.y(14), this.width, p.horizonY - p.y(14) + 1);

    this.milkyWayDust(p, f);

    // The tracks of the stars (the sky turns) and of the planets (they wander).
    for (const track of f.starTracks) this.line(p, track, "rgba(200, 215, 255, 0.32)", 1.2);
    for (const track of f.planetTracks) {
      this.line(p, track, "rgba(255, 210, 150, 0.65)", 1.5, [2, 5]);
    }

    // The constellation lines.
    if (f.linesAlpha > 0.01) {
      this.constellationLines(p, f, 0, `rgba(150, 175, 255, ${0.38 * f.linesAlpha})`, 1);
      if (f.highlight >= 0 && f.highlightAlpha > 0.01) {
        this.constellationLines(
          p,
          f,
          0,
          `rgba(190, 210, 255, ${0.9 * f.highlightAlpha})`,
          2,
          f.highlight,
        );
      }
    }

    // The stars, the faintest first, with a little twinkle. Near the horizon the air dims them.
    const s = this.stars;
    let count = 0;
    while (count < s.mag.length && s.mag[count] <= f.limitMag) count++;
    for (let i = count - 1; i >= 0; i--) {
      const alt = f.stars[2 * i + 1];
      if (alt < -1) continue;
      const x = p.x(f.stars[2 * i]);
      const y = p.y(alt);
      if (x < -4 || x > this.width + 4 || y < -4 || y > p.horizonY + 2) continue;
      const mag = s.mag[i];
      const extinction = clamp(0.25 + alt / 10);
      const fade = clamp(0.3 + (f.limitMag - mag) / 1.5) * extinction;
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
    // The names of the brightest stars and of the constellations.
    if (f.namesAlpha > 0.01) {
      ctx.font = "12px system-ui, -apple-system, sans-serif";
      ctx.fillStyle = `rgba(220, 228, 255, ${0.8 * f.namesAlpha})`;
      for (let i = 0; i < count && s.mag[i] <= 1.4; i++) {
        const name = s.names.get(i);
        const alt = f.stars[2 * i + 1];
        if (!name || alt < 2) continue;
        ctx.fillText(name, p.x(f.stars[2 * i]), p.y(alt) - 7);
      }
      ctx.font = "300 13px 'Avenir Next', Avenir, system-ui, sans-serif";
      ctx.fillStyle = `rgba(170, 160, 255, ${0.8 * f.namesAlpha})`;
      s.constellations.forEach((c, i) => {
        const alt = f.labels[2 * i + 1];
        if (alt < 4) return;
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

    if (f.moon.altitudeDeg > -1 && f.moonFraction > 0.02) {
      this.moon(p, f, day);
      ctx.font = "12px system-ui, -apple-system, sans-serif";
      ctx.fillStyle = "rgba(230, 236, 255, 0.9)";
      ctx.fillText("Moon", p.x(f.moon.azimuthDeg), p.y(f.moon.altitudeDeg) - 15);
    }
    // The sun, until it goes down behind the ground (the ground is drawn after it).
    if (f.sun.altitudeDeg > -1) {
      ctx.save();
      ctx.fillStyle = "#fff1cc";
      ctx.shadowColor = "rgba(255, 190, 90, 0.9)";
      ctx.shadowBlur = 30;
      ctx.beginPath();
      ctx.arc(p.x(f.sun.azimuthDeg), p.y(f.sun.altitudeDeg), 11, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      ctx.font = "12px system-ui, -apple-system, sans-serif";
      ctx.fillStyle = "rgba(255, 240, 210, 0.9)";
      ctx.fillText("Sun", p.x(f.sun.azimuthDeg), p.y(f.sun.altitudeDeg) - 16);
    }
    if (f.alignment && f.alignment.alpha > 0.01) this.alignment(p, f, f.alignment);

    this.ground(p, f, day);

    if (f.dim > 0) {
      ctx.fillStyle = `rgba(3, 5, 14, ${f.dim})`;
      ctx.fillRect(0, 0, this.width, this.height);
    }
  }

  /**
   * The Milky Way: soft clouds and fine grains, added on top of each other (the light of many
   * stars). The air near the horizon dims it, as in the real sky. Near the zenith the panorama
   * stretches the sky sideways, so the Milky Way fades out above 70°.
   */
  private milkyWayDust(p: Panorama, f: Frame) {
    if (f.milkyWayAlpha < 0.01) return;
    const ctx = this.ctx;
    const { weight, cloud } = this.milkyWay;
    const cloudSize = 2.6 * p.pxPerDegX;
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = "rgb(225, 230, 255)";
    for (let i = 0; i < weight.length; i++) {
      const alt = f.dust[2 * i + 1];
      if (alt < 0 || alt > 80) continue;
      const x = p.x(f.dust[2 * i]);
      const y = p.y(alt);
      if (x < -cloudSize || x > this.width + cloudSize || y < -cloudSize || y > p.horizonY) {
        continue;
      }
      const alpha = f.milkyWayAlpha * weight[i] * clamp(alt / 14) * clamp((80 - alt) / 10);
      if (cloud[i]) {
        ctx.globalAlpha = 0.075 * alpha;
        const size = cloudSize * (0.6 + (0.8 * ((i * 7919) % 100)) / 100);
        ctx.drawImage(this.sprite, x - size / 2, y - size / 2, size, size);
      } else {
        ctx.globalAlpha = 0.55 * alpha;
        ctx.fillRect(x, y, 1, 1);
      }
    }
    ctx.restore();
  }

  /** The lines of all constellations (or of one), turned by `offsetDeg` in azimuth. */
  private constellationLines(
    p: Panorama,
    f: Frame,
    offsetDeg: number,
    color: string,
    width: number,
    only = -1,
    dash: number[] = [],
  ) {
    const ctx = this.ctx;
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.setLineDash(dash);
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
    ctx.setLineDash([]);
  }

  /**
   * The compass chapter: the frame of a phone around one constellation. The phone draws the
   * constellation where its compass says (dashed, off by the error); after a tap on the star it
   * moves onto the real stars.
   */
  private alignment(p: Panorama, f: Frame, a: Alignment) {
    const ctx = this.ctx;
    const sx = p.x(f.stars[2 * a.star]);
    const sy = p.y(f.stars[2 * a.star + 1]);
    // The phone: about 9 by 16 and 32° wide, around the star.
    const w = Math.min(this.width * 0.42, 32 * p.pxPerDegX);
    const h = Math.min(this.height * 0.86, w * 1.9);
    const left = sx - w / 2;
    const top = sy - h * 0.55;
    ctx.save();
    ctx.globalAlpha = a.alpha;
    // The sky outside the phone is darker, so the eye goes to the phone.
    ctx.fillStyle = "rgba(2, 4, 12, 0.45)";
    ctx.beginPath();
    ctx.rect(0, 0, this.width, this.height);
    ctx.roundRect(left, top, w, h, 26);
    ctx.fill("evenodd");
    ctx.strokeStyle = "rgba(200, 210, 255, 0.55)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.roundRect(left, top, w, h, 26);
    ctx.stroke();
    ctx.fillStyle = "rgba(200, 210, 255, 0.45)";
    ctx.beginPath();
    ctx.roundRect(sx - 22, top + 10, 44, 6, 3);
    ctx.fill();
    ctx.beginPath();
    ctx.roundRect(left, top, w, h, 26);
    ctx.clip();
    // The real constellation, and the one of the phone off by the error of the compass.
    this.constellationLines(p, f, 0, "rgba(190, 210, 255, 0.9)", 2, a.constellation);
    if (a.offsetDeg > 0.05) {
      const ghostAlpha = clamp(a.offsetDeg / 3);
      this.constellationLines(
        p,
        f,
        a.offsetDeg,
        `rgba(127, 216, 255, ${0.85 * ghostAlpha})`,
        2,
        a.constellation,
        [6, 5],
      );
      const gx = p.x(f.stars[2 * a.star] + a.offsetDeg);
      ctx.strokeStyle = `rgba(127, 216, 255, ${0.85 * ghostAlpha})`;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(gx, sy, 9, 0, Math.PI * 2);
      ctx.stroke();
      // The arrow from the drawn star to the real star: the correction of the tap.
      if (Math.abs(gx - sx) > 24) {
        const dir = Math.sign(sx - gx);
        ctx.beginPath();
        ctx.moveTo(gx + dir * 14, sy + 22);
        ctx.lineTo(sx - dir * 10, sy + 22);
        ctx.lineTo(sx - dir * 16, sy + 17);
        ctx.moveTo(sx - dir * 10, sy + 22);
        ctx.lineTo(sx - dir * 16, sy + 27);
        ctx.stroke();
      }
    }
    // The crosshair on the real star, and the ring of the tap.
    ctx.strokeStyle = "rgba(255, 255, 255, 0.8)";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(sx - 18, sy);
    ctx.lineTo(sx - 8, sy);
    ctx.moveTo(sx + 8, sy);
    ctx.lineTo(sx + 18, sy);
    ctx.moveTo(sx, sy - 18);
    ctx.lineTo(sx, sy - 8);
    ctx.moveTo(sx, sy + 8);
    ctx.lineTo(sx, sy + 18);
    ctx.stroke();
    if (a.tap > 0 && a.tap < 1) {
      ctx.strokeStyle = `rgba(255, 255, 255, ${1 - a.tap})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(sx, sy, 10 + a.tap * 40, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
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

  /**
   * The ground: a dark silhouette of the hills or the mountains, with a faint light along the
   * crest, so the horizon looks like a landscape at night and not like the edge of a box.
   */
  private ground(p: Panorama, f: Frame, day: number) {
    const ctx = this.ctx;
    const { stepDeg, altitudeDeg } = f.ground;
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
    const crest = Math.min(...points.map(([, y]) => y));
    const near: Rgb = mix([2, 3, 8], [14, 20, 30], day);
    const far: Rgb = mix([9, 12, 24], [30, 40, 56], day);
    const fill = ctx.createLinearGradient(0, crest, 0, this.height);
    fill.addColorStop(0, css(far));
    fill.addColorStop(0.35, css(near));
    fill.addColorStop(1, css(near));
    ctx.fillStyle = fill;
    ctx.beginPath();
    ctx.moveTo(points[0][0], this.height);
    for (const [x, y] of points) ctx.lineTo(x, y);
    ctx.lineTo(points[points.length - 1][0], this.height);
    ctx.closePath();
    ctx.fill();
    ctx.strokeStyle = f.realTerrain ? "rgba(170, 200, 255, 0.55)" : "rgba(150, 170, 230, 0.22)";
    ctx.lineWidth = f.realTerrain ? 1.5 : 1;
    ctx.beginPath();
    points.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.stroke();
  }
}
