#!/usr/bin/env python3
"""Build the star data of starradar: the stars, their names, and the constellations.

    python3 scripts/stars/build_stars.py [--cache DIR]

The script downloads the Yale Bright Star Catalogue, 5th revised edition (Hoffleit & Warren
1991, VizieR V/50, from the copy at the Harvard-Smithsonian CfA), and the constellation lines,
the constellation names, and the star names of d3-celestial (BSD-3, Olaf Frohn, tag
D3_CELESTIAL_VERSION). It writes assets/stars/stars.json. PLAN.md 14.2 has the sources and their
licenses.

The format (version 1), for the readers in the apps:

- "stars": columns of the same length, sorted by magnitude (the brightest first):
  "ra" and "dec" (degrees, J2000, epoch 2000.0), "pm_ra" and "pm_dec" (arcseconds per year;
  "pm_ra" is d(RA)/dt * cos(dec), as in the catalogue), "mag" (V magnitude), "bv" (B-V color, 0
  if the catalogue has none), and "hr" (the number in the Bright Star Catalogue).
- "names": a list of [index of the star, proper name], for example [0, "Sirius"].
- "constellations": a list of objects: "id" (the IAU abbreviation, for example "Ori"), "name"
  (English), "label" ([ra, dec] in degrees, where the name goes), and "lines" (a list of lines;
  a line is a list of indices of stars).
- "attribution": the text that the app shows.

The stars are all stars of the catalogue down to MAX_MAG, and the fainter stars that a
constellation line uses.
"""

import argparse
import gzip
import json
import math
import pathlib
import urllib.request

BSC_URL = "http://tdc-www.harvard.edu/catalogs/bsc5.dat.gz"
D3_CELESTIAL_VERSION = "v0.7.32"
D3_BASE = f"https://raw.githubusercontent.com/ofrohn/d3-celestial/{D3_CELESTIAL_VERSION}/data/"
OUT = pathlib.Path(__file__).resolve().parents[2] / "assets" / "stars" / "stars.json"

# The faintest stars that the eye sees under a good dark sky are about magnitude 6.
MAX_MAG = 6.0
# A point of a constellation line belongs to the nearest star within this distance.
MATCH_DEG = 0.3

ATTRIBUTION = (
    "Stars: Yale Bright Star Catalogue, 5th ed. (Hoffleit & Warren 1991, NASA ADC). "
    "Constellations and star names: d3-celestial (c) 2015 Olaf Frohn, BSD-3."
)


def fetch(url, name, cache):
    path = cache / name if cache else None
    if path and path.exists():
        return path.read_bytes()
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    if path:
        path.write_bytes(data)
    return data


def number(text):
    text = text.strip()
    return float(text) if text else None


def read_bsc(data):
    """The stars of the catalogue with a J2000 position and a V magnitude."""
    stars = []
    for line in gzip.decompress(data).decode("latin-1").splitlines():
        line = line.ljust(197)
        if not line[75:77].strip() or not line[102:107].strip():
            continue  # a star that was removed from the catalogue: no position
        ra = (int(line[75:77]) + int(line[77:79]) / 60 + float(line[79:83]) / 3600) * 15
        dec = int(line[84:86]) + int(line[86:88]) / 60 + int(line[88:90]) / 3600
        if line[83] == "-":
            dec = -dec
        stars.append(
            {
                "hr": int(line[0:4]),
                "hd": int(line[25:31]) if line[25:31].strip() else None,
                "ra": ra,
                "dec": dec,
                "pm_ra": number(line[148:154]) or 0.0,
                "pm_dec": number(line[154:160]) or 0.0,
                "mag": float(line[102:107]),
                "bv": number(line[109:114]) or 0.0,
            }
        )
    return stars


def unit(ra, dec):
    r, d = math.radians(ra), math.radians(dec)
    return (math.cos(d) * math.cos(r), math.cos(d) * math.sin(r), math.sin(d))


def nearest(stars, vectors, ra, dec):
    """The index of the nearest star within MATCH_DEG, or None. Prefers the brighter star."""
    v = unit(ra, dec)
    limit = math.cos(math.radians(MATCH_DEG))
    best = None
    for i, w in enumerate(vectors):
        c = v[0] * w[0] + v[1] * w[1] + v[2] * w[2]
        if c >= limit and (best is None or stars[i]["mag"] < stars[best]["mag"]):
            best = i
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--cache", type=pathlib.Path, help="keep the downloads in this folder"
    )
    args = parser.parse_args()
    if args.cache:
        args.cache.mkdir(parents=True, exist_ok=True)

    stars = read_bsc(fetch(BSC_URL, "bsc5.dat.gz", args.cache))
    vectors = [unit(s["ra"], s["dec"]) for s in stars]

    def d3(name):
        return json.loads(fetch(D3_BASE + name, name, args.cache))

    lines_json = d3("constellations.lines.json")
    names_json = d3("constellations.json")
    starnames = d3("starnames.json")

    # The lines: d3-celestial gives coordinates, so each point becomes the nearest star.
    used = set()
    # A list for each id, because Serpens has two parts (Caput and Cauda) with the id "Ser".
    lines = {}
    missed = 0
    for feature in lines_json["features"]:
        out = []
        for line in feature["geometry"]["coordinates"]:
            indices = []
            for lon, lat in line:
                i = nearest(stars, vectors, lon % 360, lat)
                if i is None:
                    missed += 1
                    continue
                if not indices or indices[-1] != i:
                    indices.append(i)
            if len(indices) >= 2:
                out.append(indices)
                used.update(indices)
        lines.setdefault(feature["id"], []).append(out)
    if missed:
        print(
            f"{missed} points of the constellation lines have no star within {MATCH_DEG}°"
        )

    # The stars to keep, sorted by magnitude, and the new indices.
    keep = sorted(
        (i for i, s in enumerate(stars) if s["mag"] <= MAX_MAG or i in used),
        key=lambda i: stars[i]["mag"],
    )
    index = {old: new for new, old in enumerate(keep)}

    # The proper names, matched by the HD number.
    by_hd = {s["hd"]: i for i, s in enumerate(stars) if s["hd"]}
    names = []
    for entry in starnames.values():
        name = entry.get("name", "").strip()
        hd = entry.get("hd", "").replace("HD", "").replace(" ", "").strip()
        if name and hd.isdigit() and int(hd) in by_hd and by_hd[int(hd)] in index:
            names.append([index[by_hd[int(hd)]], name])
    names.sort()

    def distance_deg(line_set, lon, lat):
        """The angle from the label to the nearest star of a set of lines."""
        v = unit(lon % 360, lat)
        best = 180.0
        for line in line_set:
            for i in line:
                w = vectors[i]
                c = max(-1.0, min(1.0, v[0] * w[0] + v[1] * w[1] + v[2] * w[2]))
                best = min(best, math.degrees(math.acos(c)))
        return best

    constellations = []
    for feature in names_json["features"]:
        cid = feature["id"]
        lon, lat = feature["geometry"]["coordinates"]
        # The lines of this part: of the sets with this id, the set nearest to the label.
        candidates = lines.get(cid, [])
        chosen = []
        if candidates:
            chosen = min(candidates, key=lambda c: distance_deg(c, lon, lat))
            candidates.remove(chosen)
        constellations.append(
            {
                "id": cid,
                # The IAU name in Latin, as sky maps use it (for example "Pisces", "Serpens Caput").
                "name": feature["properties"]["name"],
                "label": [round(lon % 360, 2), round(lat, 2)],
                "lines": [[index[i] for i in line] for line in chosen],
            }
        )

    columns = {
        "ra": [],
        "dec": [],
        "pm_ra": [],
        "pm_dec": [],
        "mag": [],
        "bv": [],
        "hr": [],
    }
    for i in keep:
        s = stars[i]
        columns["ra"].append(round(s["ra"], 5))
        columns["dec"].append(round(s["dec"], 5))
        columns["pm_ra"].append(round(s["pm_ra"], 3))
        columns["pm_dec"].append(round(s["pm_dec"], 3))
        columns["mag"].append(round(s["mag"], 2))
        columns["bv"].append(round(s["bv"], 2))
        columns["hr"].append(s["hr"])

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(
            {
                "version": 1,
                "stars": columns,
                "names": names,
                "constellations": constellations,
                "attribution": ATTRIBUTION,
            },
            separators=(",", ":"),
            ensure_ascii=False,
        )
        + "\n"
    )
    print(
        f"{len(keep)} stars, {len(names)} names, {len(constellations)} constellations, "
        f"{OUT.stat().st_size // 1024} KB: {OUT}"
    )


if __name__ == "__main__":
    main()
