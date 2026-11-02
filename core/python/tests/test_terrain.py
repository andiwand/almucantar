import math
import pathlib

import pytest

import sunradar

VIENNA = sunradar.Observer(latitude_deg=48.21, longitude_deg=16.37)
# 2026-06-21 00:00 UTC.
SOLSTICE = 1782000000


def flat_grid(height=0.0):
    rows, columns = 61, 91
    return sunradar.ElevationGrid(
        north_latitude_deg=VIENNA.latitude_deg + 0.25,
        west_longitude_deg=VIENNA.longitude_deg - 0.375,
        latitude_step_deg=1 / 120,
        longitude_step_deg=1 / 120,
        rows=rows,
        columns=columns,
        heights_m=[height] * (rows * columns),
    )


def test_horizon_profile_over_flat_ground():
    profile = sunradar.horizon_profile(
        VIENNA, [flat_grid(150.0)], sunradar.HorizonOptions(azimuth_step_deg=10)
    )
    assert len(profile.altitude_deg) == 36
    assert profile.observer_elevation_m == pytest.approx(150.0)
    for altitude in profile.altitude_deg:
        assert -0.1 < altitude < 0
    assert sunradar.terrain_altitude_deg(profile, 5) == pytest.approx(
        (profile.altitude_deg[0] + profile.altitude_deg[1]) / 2
    )


def test_horizon_profile_without_data_at_the_observer():
    grid = flat_grid()
    grid.heights_m = [math.nan] * len(grid.heights_m)
    with pytest.raises(ValueError, match="no data at the observer"):
        sunradar.horizon_profile(VIENNA, [grid])


def test_terrain_events():
    flat = sunradar.HorizonProfile(
        azimuth_step_deg=1, altitude_deg=[0.0] * 360, distance_m=[1000.0] * 360
    )
    events = sunradar.terrain_events(VIENNA, flat, SOLSTICE - 7200, SOLSTICE + 79200)
    assert events.kind == sunradar.DayKind.normal
    assert [c.rising for c in events.crossings] == [True, False]
    sun = sunradar.sun_events(VIENNA, SOLSTICE - 7200, SOLSTICE + 79200)
    assert events.crossings[0].unix_utc == pytest.approx(
        sun.crossings[0].unix_utc, abs=60
    )

    wall = sunradar.HorizonProfile(
        azimuth_step_deg=1, altitude_deg=[80.0] * 360, distance_m=[1000.0] * 360
    )
    options = sunradar.TerrainEventOptions(limb_deg=0)
    events = sunradar.terrain_events(VIENNA, wall, SOLSTICE, SOLSTICE + 86400, options)
    assert events.kind == sunradar.DayKind.polar_night
    assert events.crossings == []


def test_read_elevation_geotiff():
    path = (
        pathlib.Path(__file__).parents[2]
        / "test/data/geotiff/float_tiles_deflate_p3.tif"
    )
    grid = sunradar.read_elevation_geotiff(path.read_bytes())
    assert (grid.rows, grid.columns) == (7, 5)
    assert grid.north_latitude_deg == pytest.approx(47.75)
    assert grid.heights_m[0] == pytest.approx(1000.25)
    assert sunradar.read_elevation_geotiff(path.read_bytes(), level=1).rows == 4
    with pytest.raises(ValueError, match="GeoTIFF"):
        sunradar.read_elevation_geotiff(b"II*\0")


def test_elevation_tiles():
    tiles = sunradar.elevation_tiles(VIENNA, 20000)
    assert (tiles[0].latitude_deg, tiles[0].longitude_deg) == (48, 16)
