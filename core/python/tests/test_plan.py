import math

import pytest

import almucantar

VIENNA = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)
# 2026-03-20 12:00 UTC.
EQUINOX = 1774008000
DAY = 86400


def test_body_position():
    assert len(almucantar.Body.__members__) == 10
    moon = almucantar.body_position(almucantar.Body.moon, EQUINOX, VIENNA)
    assert moon.azimuth_deg == almucantar.moon_position(EQUINOX, VIENNA).azimuth_deg
    airless = almucantar.BodyOptions(refraction=False)
    center = almucantar.body_position(
        almucantar.Body.galactic_center, EQUINOX, VIENNA, airless
    )
    assert center.altitude_deg == pytest.approx(
        almucantar.galactic_position(
            0, 0, EQUINOX, VIENNA, almucantar.SkyOptions(refraction=False)
        ).altitude_deg
    )


def test_sight_and_find_alignments():
    tower = almucantar.Site(latitude_deg=48.25, longitude_deg=16.40, elevation_m=600)
    eye = almucantar.Site(latitude_deg=48.2082, longitude_deg=16.3738, elevation_m=170)
    s = almucantar.sight(eye, tower)
    assert 0 < s.direction.azimuth_deg < 90
    assert s.distance_m == pytest.approx(5050, abs=50)
    assert s.direction.altitude_deg > 0
    # The moon of one time is found again at that time.
    t0 = EQUINOX + 3 * DAY
    direction = almucantar.moon_position(t0, VIENNA)
    found = almucantar.find_alignments(
        almucantar.Body.moon,
        VIENNA,
        direction,
        t0 - DAY,
        t0 + DAY,
        almucantar.AlignmentOptions(max_separation_deg=0.1),
    )
    assert len(found) == 1
    assert found[0].unix_utc == pytest.approx(t0, abs=2)
    with pytest.raises(ValueError):
        almucantar.sight(eye, eye)


def test_alignment_line():
    tower = almucantar.Site(latitude_deg=47, longitude_deg=11, elevation_m=300)
    line = almucantar.alignment_line(
        almucantar.Body.sun,
        tower,
        EQUINOX,
        options=almucantar.AlignmentLineOptions(max_distance_m=5000, step_m=500),
    )
    assert len(line.points) == 10
    assert line.spots == []
    assert all(math.isnan(p.ground_elevation_m) for p in line.points)
    first = line.points[0]
    s = almucantar.sight(
        almucantar.Site(
            latitude_deg=first.place.latitude_deg,
            longitude_deg=first.place.longitude_deg,
            elevation_m=first.eye_elevation_m,
        ),
        tower,
    )
    assert s.direction.altitude_deg == pytest.approx(first.body.altitude_deg, abs=1e-6)


def test_line_of_sight_and_dark_windows():
    rows, columns = 61, 61
    grid = almucantar.ElevationGrid(
        north_latitude_deg=47.25,
        west_longitude_deg=10.75,
        latitude_step_deg=1 / 120,
        longitude_step_deg=1 / 120,
        rows=rows,
        columns=columns,
        heights_m=[0.0] * (rows * columns),
    )
    eye = almucantar.Site(latitude_deg=47, longitude_deg=11, elevation_m=2)
    tower = almucantar.Site(latitude_deg=47.1, longitude_deg=11, elevation_m=100)
    assert almucantar.line_of_sight(eye, tower, [grid]).visible
    windows = almucantar.dark_windows(VIENNA, EQUINOX, EQUINOX + 5 * DAY)
    assert windows
    assert all(w.end_utc > w.start_utc for w in windows)
    with pytest.raises(ValueError):
        almucantar.dark_windows(VIENNA, 10, 10)
