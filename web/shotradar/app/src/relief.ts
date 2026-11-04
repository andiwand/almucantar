// The shaded relief of the map, from the Terrarium elevation tiles (AWS Terrain Tiles), and the
// height at a place. Pure functions, so the tests can check them without a browser.

/** The height of pixel `i` of Terrarium RGBA pixels: red * 256 + green + blue / 256 - 32768. */
export function terrariumHeight(rgba: ArrayLike<number>, i: number): number {
  return rgba[4 * i] * 256 + rgba[4 * i + 1] + rgba[4 * i + 2] / 256 - 32768;
}

/**
 * The height at the pixel position (x, y) of a tile of width x height, with a bilinear
 * interpolation. A position outside the tile uses the edge.
 */
export function heightAt(
  rgba: ArrayLike<number>,
  width: number,
  height: number,
  x: number,
  y: number,
): number {
  const cx = Math.max(0, Math.min(width - 1, x - 0.5));
  const cy = Math.max(0, Math.min(height - 1, y - 0.5));
  const x0 = Math.min(Math.floor(cx), width - 2);
  const y0 = Math.min(Math.floor(cy), height - 2);
  const u = cx - x0;
  const v = cy - y0;
  const h = (xx: number, yy: number) => terrariumHeight(rgba, yy * width + xx);
  return (
    (1 - v) * ((1 - u) * h(x0, y0) + u * h(x0 + 1, y0)) +
    v * ((1 - u) * h(x0, y0 + 1) + u * h(x0 + 1, y0 + 1))
  );
}

/** The latitude of the Web Mercator row coordinate `y` of a tile scheme with 2^zoom rows. */
export function tileLatitude(y: number, zoom: number): number {
  return (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 2 ** zoom))) * 180) / Math.PI;
}

type Rgb = [number, number, number];

/** The colors of the land by height: warm paper in the valleys, darker sand higher, then snow. */
const STOPS: [number, Rgb][] = [
  [0, [238, 230, 214]],
  [600, [229, 214, 189]],
  [1500, [214, 190, 157]],
  [2500, [190, 160, 128]],
  [3200, [236, 232, 226]],
];
const SEA: Rgb = [196, 214, 216];

export function landColor(h: number): Rgb {
  if (h <= STOPS[0][0]) return STOPS[0][1];
  for (let i = 1; i < STOPS.length; i++) {
    const [h1, c1] = STOPS[i];
    if (h <= h1) {
      const [h0, c0] = STOPS[i - 1];
      const t = (h - h0) / (h1 - h0);
      return [0, 1, 2].map((k) => c0[k] + (c1[k] - c0[k]) * t) as Rgb;
    }
  }
  return STOPS[STOPS.length - 1][1];
}

/**
 * The shaded relief of a Terrarium tile (zoom, row y) as RGBA pixels: the color of the height,
 * times the light of a sun in the north-west at 45°. The slopes use the neighbors in the tile, so
 * the edge pixels use the pixels next to them.
 */
export function shadeTile(
  rgba: ArrayLike<number>,
  width: number,
  height: number,
  zoom: number,
  y: number,
): Uint8ClampedArray<ArrayBuffer> {
  const latitude = tileLatitude(y + 0.5, zoom);
  const cellM = (40_075_016.686 * Math.cos((latitude * Math.PI) / 180)) / (width * 2 ** zoom);
  // The light: from the azimuth 315° and 45° high, in east, north, up.
  const az = (315 * Math.PI) / 180;
  const alt = (45 * Math.PI) / 180;
  const light = [Math.sin(az) * Math.cos(alt), Math.cos(az) * Math.cos(alt), Math.sin(alt)];
  const out = new Uint8ClampedArray(width * height * 4);
  const h = (xx: number, yy: number) =>
    terrariumHeight(
      rgba,
      Math.max(0, Math.min(height - 1, yy)) * width + Math.max(0, Math.min(width - 1, xx)),
    );
  for (let r = 0; r < height; r++) {
    for (let c = 0; c < width; c++) {
      const here = h(c, r);
      // The slope: east is +x, north is -y (the rows go south). A small exaggeration makes the
      // relief visible on a phone.
      const dzdx = ((h(c + 1, r) - h(c - 1, r)) / (2 * cellM)) * 1.5;
      const dzdy = ((h(c, r - 1) - h(c, r + 1)) / (2 * cellM)) * 1.5;
      const n = [-dzdx, -dzdy, 1];
      const length = Math.hypot(n[0], n[1], n[2]);
      const lit = (n[0] * light[0] + n[1] * light[1] + n[2] * light[2]) / length;
      // The sea has no slope and a height of 0 or less in Terrarium.
      const sea = here <= 0 && dzdx === 0 && dzdy === 0;
      const base = sea ? SEA : landColor(here);
      const shade = sea ? 1 : 0.55 + 0.6 * Math.max(0, lit);
      const i = 4 * (r * width + c);
      out[i] = base[0] * shade;
      out[i + 1] = base[1] * shade;
      out[i + 2] = base[2] * shade;
      out[i + 3] = 255;
    }
  }
  return out;
}
