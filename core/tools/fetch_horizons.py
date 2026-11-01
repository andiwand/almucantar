#!/usr/bin/env python3
"""Fetch reference positions of the sun from JPL Horizons for the core tests.

The script writes CSV files to core/tests/data/. Run it again only if you change
the sites or the time spans, because the tests use the committed files.

    python3 core/tools/fetch_horizons.py

Files:

- sun_airless.csv: site, unix_utc, azimuth_deg, altitude_deg. Apparent position of
  the center, without refraction.
- sun_refracted.csv: the same columns and times, with the refraction model of
  Horizons (yellow light, standard atmosphere).
- sun_events.csv: site, kind, unix_utc. kind is rise, transit, or set. Horizons
  defines rise and set as the refracted upper limb on the true horizon.
"""

import csv
import pathlib
import urllib.parse
import urllib.request

API = "https://ssd.jpl.nasa.gov/api/horizons.api"
OUT = pathlib.Path(__file__).resolve().parent.parent / "tests" / "data"

# name: (longitude east, latitude north). Height 0 km.
SITES = {
    "quito": (-78.4678, -0.1807),
    "vienna": (16.3738, 48.2082),
    "sydney": (151.2093, -33.8688),
    "tromso": (18.9553, 69.6492),
    "mcmurdo": (166.6863, -77.8419),
}

# (start, stop, step). Odd steps give samples at many different hours.
POSITION_SPANS = [
    ("1950-01-01", "1951-01-01", "1307 m"),
    ("2026-01-01", "2027-01-01", "437 m"),
    ("2099-06-01", "2100-06-01", "1307 m"),
]

EVENT_SPAN = ("2026-01-01", "2027-01-01", "1 m")

JD_UNIX_EPOCH = 2440587.5


def query(site, start, stop, step, apparent, rts=False):
    lon, lat = SITES[site]
    params = {
        "format": "text",
        "COMMAND": "'10'",
        "OBJ_DATA": "'NO'",
        "MAKE_EPHEM": "'YES'",
        "EPHEM_TYPE": "'OBSERVER'",
        "CENTER": "'coord@399'",
        "COORD_TYPE": "'GEODETIC'",
        "SITE_COORD": f"'{lon},{lat},0'",
        "START_TIME": f"'{start}'",
        "STOP_TIME": f"'{stop}'",
        "STEP_SIZE": f"'{step}'",
        "QUANTITIES": "'4'",
        "APPARENT": f"'{apparent}'",
        "CSV_FORMAT": "'YES'",
        "ANG_FORMAT": "'DEG'",
        "TIME_TYPE": "'UT'",
        "CAL_FORMAT": "'JD'",
        "EXTRA_PREC": "'YES'",
    }
    if rts:
        params["R_T_S_ONLY"] = "'TVH'"
    url = API + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=120) as response:
        text = response.read().decode()
    if "$$SOE" not in text:
        raise RuntimeError(f"Horizons gave no ephemeris for {site} {start}:\n{text[:2000]}")
    body = text.split("$$SOE", 1)[1].split("$$EOE", 1)[0]
    rows = []
    for line in body.strip().splitlines():
        fields = [f.strip() for f in line.split(",")]
        jd = float(fields[0])
        unix = round((jd - JD_UNIX_EPOCH) * 86400.0, 3)
        rows.append((unix, fields[2], float(fields[3]), float(fields[4])))
    return rows


def write_positions():
    for apparent, name in (("AIRLESS", "sun_airless.csv"), ("REFRACTED", "sun_refracted.csv")):
        with open(OUT / name, "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["site", "unix_utc", "azimuth_deg", "altitude_deg"])
            for site in SITES:
                for start, stop, step in POSITION_SPANS:
                    for unix, _, az, alt in query(site, start, stop, step, apparent):
                        w.writerow([site, f"{unix:.3f}", f"{az:.9f}", f"{alt:.9f}"])
        print("wrote", OUT / name)


def write_events():
    kinds = {"r": "rise", "t": "transit", "s": "set"}
    with open(OUT / "sun_events.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["site", "kind", "unix_utc"])
        for site in SITES:
            start, stop, step = EVENT_SPAN
            for unix, flag, _, _ in query(site, start, stop, step, "REFRACTED", rts=True):
                if flag in kinds:
                    w.writerow([site, kinds[flag], f"{unix:.3f}"])
    print("wrote", OUT / "sun_events.csv")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    write_positions()
    write_events()
