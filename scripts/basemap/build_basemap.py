#!/usr/bin/env python3
"""Build the offline base map of the apps from Natural Earth (public domain).

The apps draw this map when they have no network, so the user can drop a place (PLAN.md 13.3).
Natural Earth at 1:10m is good to about 1 km, which gives an error of the sun position of less
than 0.01°.

    python3 scripts/basemap/build_basemap.py [--cache DIR]

The script downloads the GeoJSON files of Natural Earth (nvkelso/natural-earth-vector, tag
NE_VERSION), simplifies the lines, and writes assets/basemap/basemap.json.

The format (version 1), for the readers in the apps:

- "scale": the coordinates are integers: degrees * scale.
- "layers": an object with the line layers "coastline", "borders", "lakes" (closed rings), and
  "rivers". Each layer is a list of lines. A line is a flat list [lon0, lat0, dlon1, dlat1, ...]:
  the first point is absolute, and each next point is the difference to the point before.
- "places": a list of [name, lon, lat, rank, country, time zone], with lon and lat as integers
  as above. A smaller rank is a larger place (0 to 10, the "scalerank" of Natural Earth). The
  time zone is an IANA name, or "" if Natural Earth has none (about 1 place in 6). An app takes
  the time zone of a dropped place from the nearest place that has one.
- "attribution": the text that the app shows.
"""

import argparse
import json
import math
import pathlib
import urllib.request

NE_VERSION = "v5.1.2"
BASE = f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NE_VERSION}/geojson/"
OUT = (
    pathlib.Path(__file__).resolve().parents[2] / "assets" / "basemap" / "basemap.json"
)
SCALE = 10_000  # 1e-4° is about 11 m
TOLERANCE_DEG = 0.003  # about 300 m: well below the accuracy of 1 km of the source

LAYERS = {
    "coastline": ("ne_10m_coastline", None),
    "borders": ("ne_10m_admin_0_boundary_lines_land", None),
    # Only the larger lakes and rivers: the small ones are noise on a map to pick a place.
    "lakes": ("ne_10m_lakes", lambda p: (p.get("scalerank") or 0) <= 6),
    "rivers": (
        "ne_10m_rivers_lake_centerlines",
        lambda p: (p.get("scalerank") or 0) <= 6,
    ),
}
PLACES = "ne_10m_populated_places"


def fetch(name, cache):
    path = cache / f"{name}.geojson" if cache else None
    if path and path.exists():
        return json.loads(path.read_text())
    with urllib.request.urlopen(BASE + f"{name}.geojson", timeout=120) as response:
        text = response.read().decode()
    if path:
        path.write_text(text)
    return json.loads(text)


def lines_of(geometry):
    kind = geometry["type"]
    coordinates = geometry["coordinates"]
    if kind == "LineString":
        return [coordinates]
    if kind == "MultiLineString":
        return coordinates
    if kind == "Polygon":
        return coordinates
    if kind == "MultiPolygon":
        return [ring for polygon in coordinates for ring in polygon]
    return []


def simplify(points, tolerance):
    """Douglas-Peucker, without recursion, in degrees (sufficient for this tolerance)."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        (x1, y1), (x2, y2) = points[first][:2], points[last][:2]
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy)
        best, index = 0.0, -1
        for i in range(first + 1, last):
            x, y = points[i][:2]
            if length == 0:
                d = math.hypot(x - x1, y - y1)
            else:
                d = abs(dy * x - dx * y + x2 * y1 - y2 * x1) / length
            if d > best:
                best, index = d, i
        if best > tolerance:
            keep[index] = True
            stack += [(first, index), (index, last)]
    return [p for p, k in zip(points, keep) if k]


def encode(points):
    flat, last = [], (0, 0)
    for i, p in enumerate(points):
        q = (round(p[0] * SCALE), round(p[1] * SCALE))
        if i and q == last:
            continue
        flat += [q[0], q[1]] if i == 0 else [q[0] - last[0], q[1] - last[1]]
        last = q
    return flat


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--cache", type=pathlib.Path, help="a directory for the downloads"
    )
    args = parser.parse_args()
    if args.cache:
        args.cache.mkdir(parents=True, exist_ok=True)

    layers, counts = {}, {}
    for layer, (name, keep) in LAYERS.items():
        result = []
        points = 0
        for feature in fetch(name, args.cache)["features"]:
            if keep and not keep(feature["properties"]):
                continue
            for line in lines_of(feature["geometry"]):
                simple = simplify(line, TOLERANCE_DEG)
                if len(simple) >= 2:
                    result.append(encode(simple))
                    points += len(simple)
        layers[layer] = result
        counts[layer] = points

    places = []
    for feature in fetch(PLACES, args.cache)["features"]:
        p = feature["properties"]
        lon, lat = feature["geometry"]["coordinates"][:2]
        places.append(
            [
                p["NAME"],
                round(lon * SCALE),
                round(lat * SCALE),
                int(p.get("SCALERANK") or 0),
                p.get("ADM0NAME") or "",
                p.get("TIMEZONE") or "",
            ]
        )
    places.sort(key=lambda x: (x[3], x[0]))

    data = {
        "version": 1,
        "scale": SCALE,
        "attribution": "Map: Natural Earth (public domain)",
        "source": f"Natural Earth 1:10m, {NE_VERSION}, simplified to {TOLERANCE_DEG}°",
        "layers": layers,
        "places": places,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, separators=(",", ":"), ensure_ascii=False))
    size = OUT.stat().st_size
    print(f"wrote {OUT} ({size / 1e6:.2f} MB)")
    for layer, points in counts.items():
        print(f"  {layer}: {len(layers[layer])} lines, {points} points")
    print(f"  places: {len(places)}")


if __name__ == "__main__":
    main()
