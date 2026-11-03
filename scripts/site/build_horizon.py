#!/usr/bin/env python3
"""Calculate the terrain horizon of one place for a website, as a small JSON file.

    python3 scripts/site/build_horizon.py NAME LATITUDE LONGITUDE OUT [--cache DIR]

The website of starradar shows the real horizon of Innsbruck in one chapter. A website must not
download 10 MB of elevation tiles for one chapter, so this script calculates the profile once,
with the Python binding of the core (PYTHONPATH must contain the build of core/python) and the
tiles of Copernicus DEM GLO-90 (the source of the native apps), and the website reads the result.

The output: {"place", "latitude_deg", "longitude_deg", "azimuth_step_deg", "altitude_deg" (a list,
one value for each step from azimuth 0), "attribution"}.
"""

import argparse
import json
import math
import pathlib
import urllib.error
import urllib.request

import almucantar

ATTRIBUTION = (
    "Terrain: Copernicus DEM GLO-90, © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH "
    "2014-2018, provided under COPERNICUS by the European Union and ESA; all rights reserved."
)
# The same values as the apps: a surface model has buildings and forests in its heights, so the
# profile ignores the terrain nearer than 2 km (PLAN.md 14.3).
MAX_DISTANCE_M = 100_000.0
MIN_DISTANCE_M = 2_000.0
AZIMUTH_STEP_DEG = 0.5


def tile_name(latitude, longitude):
    ns = "S" if latitude < 0 else "N"
    ew = "W" if longitude < 0 else "E"
    return f"{ns}{abs(latitude):02d}_00_{ew}{abs(longitude):03d}_00"


def fetch_tile(tile, cache):
    name = tile_name(tile.latitude_deg, tile.longitude_deg)
    path = cache / f"{name}.tif" if cache else None
    if path and path.exists():
        return path.read_bytes()
    tid = f"Copernicus_DSM_COG_30_{name}_DEM"
    url = f"https://copernicus-dem-90m.s3.amazonaws.com/{tid}/{tid}.tif"
    try:
        with urllib.request.urlopen(url, timeout=300) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        if error.code in (403, 404):
            return None  # the sea has no tile
        raise
    if path:
        path.write_bytes(data)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("name")
    parser.add_argument("latitude", type=float)
    parser.add_argument("longitude", type=float)
    parser.add_argument("out", type=pathlib.Path)
    parser.add_argument(
        "--cache", type=pathlib.Path, help="keep the tiles in this folder"
    )
    args = parser.parse_args()
    if args.cache:
        args.cache.mkdir(parents=True, exist_ok=True)

    observer = almucantar.Observer(
        latitude_deg=args.latitude, longitude_deg=args.longitude
    )
    grids = []
    for tile in almucantar.elevation_tiles(observer, MAX_DISTANCE_M):
        data = fetch_tile(tile, args.cache)
        if data is not None:
            grids.append(almucantar.read_elevation_geotiff(data, 0))
    options = almucantar.HorizonOptions(
        azimuth_step_deg=AZIMUTH_STEP_DEG,
        max_distance_m=MAX_DISTANCE_M,
        min_distance_m=MIN_DISTANCE_M,
    )
    profile = almucantar.horizon_profile(observer, grids, options)
    args.out.write_text(
        json.dumps(
            {
                "place": args.name,
                "latitude_deg": args.latitude,
                "longitude_deg": args.longitude,
                "azimuth_step_deg": profile.azimuth_step_deg,
                # No data (NaN) is the astronomical horizon, 0°.
                "altitude_deg": [
                    0.0 if math.isnan(a) else round(a, 2) for a in profile.altitude_deg
                ],
                "attribution": ATTRIBUTION,
            },
            separators=(",", ":"),
            ensure_ascii=False,
        )
        + "\n"
    )
    highest = max(a for a in profile.altitude_deg if not math.isnan(a))
    print(f"{len(grids)} tiles, highest terrain {highest:.1f}°: {args.out}")


if __name__ == "__main__":
    main()
