#!/usr/bin/env python3
"""Calculate the scene of the alidade website: a full moon that rises behind a peak.

    python3 scripts/site/build_shot.py OUT [--cache DIR]

The website of alidade tells one story: the full moon of 24 November 2026 rises exactly behind
the summit of the Grossglockner, seen from a ridge above Kals in East Tyrol. A website must not
download the elevation tiles for this, so this script calculates the terrain once, with the Python
binding of the core (PYTHONPATH must contain the build of core/python) and the Terrarium tiles of
the AWS Terrain Tiles, and the website reads the result. The script needs Pillow for the PNG tiles.

The spot comes from a search over all full moons from November 2026 to December 2027 and four
peaks (the Matterhorn, the Grossglockner, the Zugspitze, and the Triglav): `alignment_line()` gave
the spots, `line_of_sight()` kept the visible ones, and the horizon of each spot showed how much
the summit stands out. This script repeats only the last step for the chosen time: it takes the
spot of `alignment_line()` nearest to `SPOT_GUESS`, and `find_alignments()` gives the exact time.

The output (JSON, the angles in degrees, the distances and heights in meters):

- "peak", "spot": the summit and the place of the photographer (the ground and the eye).
- "shot": the time of the alignment (Unix seconds) and the moon there.
- "horizon": the terrain seen from the eye in layers, each layer only up to its distance, so the
  website can draw the near ridges darker than the far ones. "fine" is a window around the summit,
  "wide" is the whole circle. The altitudes are in hundredths of a degree. "skyline_distance_m"
  is the distance of the terrain that makes the skyline (the last layer, "wide"), so the website
  can find the peaks of the skyline (the chapter "From where I am").
- "map": the heights of the area around the line of the spots, as one byte per cell (base64),
  from "min_m" in steps of "step_m". The website draws the map from it and gives it to the core
  as a grid for `alignmentLine()` and `lineOfSight()`.
- "profile": the ground from the spot to the summit, and the result of `line_of_sight()`.
- "world": the coastlines of Europe, Africa, and the west of Asia from the base
  map (assets/basemap/basemap.json, Natural Earth), with fewer points, for the map of the
  eclipses. Each line is [lon0, lat0, dlon1, dlat1, ...] in hundredths of a degree, the first point
  absolute and the next ones as steps.
"""

import argparse
import base64
import datetime
import io
import json
import math
import pathlib
import urllib.request

import almucantar
from PIL import Image

ATTRIBUTION = (
    "Terrain: AWS Terrain Tiles (Terrarium), with data from SRTM, the EU-DEM (produced using "
    "Copernicus data and information funded by the European Union), and others; see "
    "https://github.com/tilezen/joerd/blob/master/docs/attribution.md."
)
URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

PEAK = {
    "name": "Grossglockner",
    "label": "Großglockner",
    "latitude_deg": 47.07457,
    "longitude_deg": 12.69434,
    "elevation_m": 3798.0,
}
# The full moon of 24 November 2026, 16:08 UTC, and the spot that the search found for it.
SHOT_GUESS_UTC = datetime.datetime(2026, 11, 24, 16, 8, tzinfo=datetime.timezone.utc)
SPOT_GUESS = (47.046, 12.607)
EYE_HEIGHT_M = 1.6

# The layers of the horizon: the terrain up to each distance. The summit is at about 7.3 km.
LAYERS_M = [2_500.0, 5_500.0, 12_000.0, 120_000.0]
FINE_HALF_SPAN_DEG = 36.0
FINE_STEP_DEG = 0.05
WIDE_STEP_DEG = 0.5

# The map: a box around the summit, the spot, and the lines of the spots during the chapter.
MAP_BOX = {"north": 47.115, "south": 46.985, "west": 12.47, "east": 12.76}
MAP_CELL_DEG = 0.0008  # about 90 m north to south and 60 m west to east
MAP_MIN_M = 600.0
MAP_STEP_M = 13.0  # 255 steps reach 3915 m

PROFILE_SAMPLES = 360

# The map of the eclipses: the box of the base map, and the smallest step between two points.
BASEMAP = (
    pathlib.Path(__file__).resolve().parents[2] / "assets" / "basemap" / "basemap.json"
)
WORLD_BOX = {"west": -45.0, "east": 100.0, "south": -20.0, "north": 72.0}
WORLD_STEP_DEG = 0.5
# A line smaller than this (a small island) is left out.
WORLD_MIN_SIZE_DEG = 1.5


def fetch(tile, cache):
    path = cache / f"{tile.zoom}_{tile.x}_{tile.y}.png" if cache else None
    if path and path.exists():
        data = path.read_bytes()
    else:
        url = URL.format(z=tile.zoom, x=tile.x, y=tile.y)
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        if path:
            path.write_bytes(data)
    image = Image.open(io.BytesIO(data)).convert("RGBA")
    return almucantar.terrarium_grid(image.tobytes(), image.width, image.height, tile)


def grids(center, distance_m, zoom, cache):
    observer = almucantar.Observer(latitude_deg=center[0], longitude_deg=center[1])
    return [fetch(t, cache) for t in almucantar.map_tiles(observer, distance_m, zoom)]


class Mosaic:
    """The heights of a set of grids of one zoom, with a bilinear sample."""

    def __init__(self, tiles):
        self.tiles = [(g, list(g.heights_m)) for g in tiles]

    def height(self, latitude, longitude):
        # A point between two tiles (in the gap of one cell at their edges) uses the nearest
        # tile, with the row and the column clamped to it.
        best = None
        for g, h in self.tiles:
            r = (g.north_latitude_deg - latitude) / g.latitude_step_deg
            c = (longitude - g.west_longitude_deg) / g.longitude_step_deg
            outside = max(0, -r, r - (g.rows - 1)) + max(0, -c, c - (g.columns - 1))
            if best is None or outside < best[0]:
                best = (outside, g, h, r, c)
            if outside == 0:
                break
        if best is None or best[0] > 2:
            return math.nan
        _, g, h, r, c = best
        r = min(max(r, 0), g.rows - 1)
        c = min(max(c, 0), g.columns - 1)
        r0, c0 = min(int(r), g.rows - 2), min(int(c), g.columns - 2)
        fr, fc = r - r0, c - c0
        i = r0 * g.columns + c0
        top = h[i] * (1 - fc) + h[i + 1] * fc
        bottom = h[i + g.columns] * (1 - fc) + h[i + g.columns + 1] * fc
        return top * (1 - fr) + bottom * fr


def world_lines():
    """The lines of the base map in WORLD_BOX, with points at least WORLD_STEP_DEG apart.

    A coastline smaller than WORLD_MIN_SIZE_DEG is left out, so the file stays small.
    """
    raw = json.loads(BASEMAP.read_text())
    scale = raw["scale"]
    out = {}
    for name in ("coastline",):
        lines = []
        for flat in raw["layers"][name]:
            lon = lat = 0
            points = []
            for i in range(0, len(flat), 2):
                lon += flat[i]
                lat += flat[i + 1]
                points.append((lon / scale, lat / scale))
            kept = [points[0]]
            for p in points[1:-1]:
                q = kept[-1]
                if max(abs(p[0] - q[0]), abs(p[1] - q[1])) >= WORLD_STEP_DEG:
                    kept.append(p)
            if len(points) > 1:
                kept.append(points[-1])
            inside = [
                WORLD_BOX["west"] <= p[0] <= WORLD_BOX["east"]
                and WORLD_BOX["south"] <= p[1] <= WORLD_BOX["north"]
                for p in kept
            ]
            # A line keeps its parts in the box, one point beyond each end of a part.
            part = []
            for i, p in enumerate(kept):
                near = inside[i] or (i > 0 and inside[i - 1])
                near = near or (i + 1 < len(kept) and inside[i + 1])
                if near:
                    part.append(p)
                elif part:
                    lines.append(part)
                    part = []
            if part:
                lines.append(part)
        encoded = []
        for part in lines:
            lons = [p[0] for p in part]
            lats = [p[1] for p in part]
            size = max(max(lons) - min(lons), max(lats) - min(lats))
            if len(part) < 2 or size < WORLD_MIN_SIZE_DEG:
                continue
            values = []
            last = (0, 0)
            for lon, lat in part:
                c = (round(lon * 100), round(lat * 100))
                values += [c[0] - last[0], c[1] - last[1]]
                last = c
            encoded.append(values)
        out[name] = encoded
    out["attribution"] = raw["attribution"]
    return out


def centi(values):
    return [0 if math.isnan(v) else round(v * 100) for v in values]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out", type=pathlib.Path)
    parser.add_argument(
        "--cache", type=pathlib.Path, help="keep the tiles in this folder"
    )
    args = parser.parse_args()
    if args.cache:
        args.cache.mkdir(parents=True, exist_ok=True)

    summit = (PEAK["latitude_deg"], PEAK["longitude_deg"])
    target = almucantar.Site(
        latitude_deg=summit[0],
        longitude_deg=summit[1],
        elevation_m=PEAK["elevation_m"],
    )
    fine = grids(summit, 16_000, 12, args.cache)
    tiles = fine + grids(summit, 45_000, 11, args.cache)

    # The spot: the one of alignment_line() nearest to the guess.
    options = almucantar.AlignmentLineOptions()
    options.min_distance_m = 3_000
    options.max_distance_m = 20_000
    options.step_m = 25
    options.eye_height_m = EYE_HEIGHT_M
    line = almucantar.alignment_line(
        almucantar.Body.moon, target, SHOT_GUESS_UTC.timestamp(), fine, options
    )
    spot = min(
        line.spots,
        key=lambda s: (s.place.latitude_deg - SPOT_GUESS[0]) ** 2
        + (s.place.longitude_deg - SPOT_GUESS[1]) ** 2,
    )
    ground = spot.ground_elevation_m
    eye = almucantar.Site(
        latitude_deg=spot.place.latitude_deg,
        longitude_deg=spot.place.longitude_deg,
        elevation_m=ground + EYE_HEIGHT_M,
    )
    sight = almucantar.sight(eye, target)

    # The exact time: the moon center on the summit, seen from the eye.
    alignment_options = almucantar.AlignmentOptions()
    alignment_options.max_separation_deg = 0.25
    alignment_options.tolerance_s = 0.5
    start = SHOT_GUESS_UTC.timestamp() - 3 * 3600
    found = almucantar.find_alignments(
        almucantar.Body.moon,
        spot.place,
        sight.direction,
        start,
        start + 6 * 3600,
        alignment_options,
    )
    shot = min(found, key=lambda a: a.separation_deg)

    los = almucantar.line_of_sight(eye, target, fine)

    # The horizon in layers, from the eye.
    horizon_tiles = (
        grids(SPOT_GUESS, 14_000, 12, args.cache)
        + tiles
        + grids(SPOT_GUESS, 120_000, 9, args.cache)
    )
    layers = []
    for distance in LAYERS_M:
        for step in (FINE_STEP_DEG, WIDE_STEP_DEG):
            h = almucantar.HorizonOptions()
            h.azimuth_step_deg = step
            h.max_distance_m = distance
            h.observer_elevation_m = ground
            h.eye_height_m = EYE_HEIGHT_M
            h.use_grid_elevation = False
            profile = almucantar.horizon_profile(spot.place, horizon_tiles, h)
            if step == FINE_STEP_DEG:
                first = sight.direction.azimuth_deg - FINE_HALF_SPAN_DEG
                values = [
                    almucantar.terrain_altitude_deg(profile, first + i * step)
                    for i in range(round(2 * FINE_HALF_SPAN_DEG / step) + 1)
                ]
                fine_layer = centi(values)
            else:
                wide_layer = centi(profile.altitude_deg)
                skyline_distance = [
                    0 if math.isnan(d) else int(round(d, -1))
                    for d in profile.distance_m
                ]
        layers.append(
            {"max_distance_m": distance, "fine": fine_layer, "wide": wide_layer}
        )

    # The map.
    mosaic = Mosaic(fine)
    rows = round((MAP_BOX["north"] - MAP_BOX["south"]) / MAP_CELL_DEG) + 1
    columns = round((MAP_BOX["east"] - MAP_BOX["west"]) / MAP_CELL_DEG) + 1
    cells = bytearray()
    for r in range(rows):
        for c in range(columns):
            z = mosaic.height(
                MAP_BOX["north"] - r * MAP_CELL_DEG, MAP_BOX["west"] + c * MAP_CELL_DEG
            )
            z = MAP_MIN_M if math.isnan(z) else z
            cells.append(max(0, min(255, round((z - MAP_MIN_M) / MAP_STEP_M))))

    # The ground from the spot to the summit.
    profile = []
    for i in range(PROFILE_SAMPLES + 1):
        f = i / PROFILE_SAMPLES
        lat = eye.latitude_deg + (summit[0] - eye.latitude_deg) * f
        lon = eye.longitude_deg + (summit[1] - eye.longitude_deg) * f
        profile.append(round(mosaic.height(lat, lon), 1))

    result = {
        "peak": PEAK,
        "spot": {
            "latitude_deg": round(eye.latitude_deg, 6),
            "longitude_deg": round(eye.longitude_deg, 6),
            "ground_m": round(ground, 1),
            "eye_height_m": EYE_HEIGHT_M,
            "zone": "Europe/Vienna",
        },
        "sight": {
            "azimuth_deg": round(sight.direction.azimuth_deg, 4),
            "altitude_deg": round(sight.direction.altitude_deg, 4),
            "distance_m": round(sight.distance_m, 1),
        },
        "shot": {
            "unix_utc": round(shot.unix_utc, 1),
            "separation_deg": round(shot.separation_deg, 4),
            "moon_azimuth_deg": round(shot.body.azimuth_deg, 4),
            "moon_altitude_deg": round(shot.body.altitude_deg, 4),
            "moon_fraction": round(
                almucantar.moon_illuminated_fraction(shot.unix_utc), 4
            ),
        },
        "horizon": {
            "fine_first_azimuth_deg": round(
                sight.direction.azimuth_deg - FINE_HALF_SPAN_DEG, 4
            ),
            "fine_step_deg": FINE_STEP_DEG,
            "wide_step_deg": WIDE_STEP_DEG,
            "layers": layers,
            "skyline_distance_m": skyline_distance,
        },
        "map": {
            "north_latitude_deg": MAP_BOX["north"],
            "west_longitude_deg": MAP_BOX["west"],
            "step_deg": MAP_CELL_DEG,
            "rows": rows,
            "columns": columns,
            "min_m": MAP_MIN_M,
            "step_m": MAP_STEP_M,
            "heights": base64.b64encode(bytes(cells)).decode("ascii"),
        },
        "profile": {
            "ground_m": profile,
            "visible": los.visible,
            "clearance_deg": round(los.clearance_deg, 3),
            "blocking_distance_m": (
                None
                if math.isnan(los.blocking_distance_m)
                else round(los.blocking_distance_m, 1)
            ),
        },
        "world": world_lines(),
        "attribution": ATTRIBUTION,
    }
    args.out.write_text(json.dumps(result, separators=(",", ":")) + "\n")
    when = datetime.datetime.fromtimestamp(shot.unix_utc, datetime.timezone.utc)
    print(
        f"spot {eye.latitude_deg:.5f}, {eye.longitude_deg:.5f} at {ground:.0f} m, "
        f"{sight.distance_m / 1000:.2f} km, moon on the summit at {when:%Y-%m-%d %H:%M:%S} UTC "
        f"(separation {shot.separation_deg:.3f}°), clearance {los.clearance_deg:.2f}°: {args.out}"
    )


if __name__ == "__main__":
    main()
