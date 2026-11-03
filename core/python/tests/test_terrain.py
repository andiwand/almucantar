import math
import pathlib

import pytest

import almucantar

VIENNA = almucantar.Observer(latitude_deg=48.21, longitude_deg=16.37)
# 2026-06-21 00:00 UTC.
SOLSTICE = 1782000000


def flat_grid(height=0.0):
    rows, columns = 61, 91
    return almucantar.ElevationGrid(
        north_latitude_deg=VIENNA.latitude_deg + 0.25,
        west_longitude_deg=VIENNA.longitude_deg - 0.375,
        latitude_step_deg=1 / 120,
        longitude_step_deg=1 / 120,
        rows=rows,
        columns=columns,
        heights_m=[height] * (rows * columns),
    )


def test_horizon_profile_over_flat_ground():
    profile = almucantar.horizon_profile(
        VIENNA, [flat_grid(150.0)], almucantar.HorizonOptions(azimuth_step_deg=10)
    )
    assert len(profile.altitude_deg) == 36
    assert profile.observer_elevation_m == pytest.approx(150.0)
    for altitude in profile.altitude_deg:
        assert -0.1 < altitude < 0
    assert almucantar.terrain_altitude_deg(profile, 5) == pytest.approx(
        (profile.altitude_deg[0] + profile.altitude_deg[1]) / 2
    )


def test_horizon_profile_without_data_at_the_observer():
    grid = flat_grid()
    grid.heights_m = [math.nan] * len(grid.heights_m)
    with pytest.raises(ValueError, match="no data at the observer"):
        almucantar.horizon_profile(VIENNA, [grid])


def test_terrain_events():
    flat = almucantar.HorizonProfile(
        azimuth_step_deg=1, altitude_deg=[0.0] * 360, distance_m=[1000.0] * 360
    )
    events = almucantar.terrain_events(VIENNA, flat, SOLSTICE - 7200, SOLSTICE + 79200)
    assert events.kind == almucantar.DayKind.normal
    assert [c.rising for c in events.crossings] == [True, False]
    sun = almucantar.sun_events(VIENNA, SOLSTICE - 7200, SOLSTICE + 79200)
    assert events.crossings[0].unix_utc == pytest.approx(
        sun.crossings[0].unix_utc, abs=60
    )

    wall = almucantar.HorizonProfile(
        azimuth_step_deg=1, altitude_deg=[80.0] * 360, distance_m=[1000.0] * 360
    )
    options = almucantar.TerrainEventOptions(limb_deg=0)
    events = almucantar.terrain_events(
        VIENNA, wall, SOLSTICE, SOLSTICE + 86400, options
    )
    assert events.kind == almucantar.DayKind.polar_night
    assert events.crossings == []


def test_read_elevation_geotiff():
    path = (
        pathlib.Path(__file__).parents[2]
        / "test/data/geotiff/float_tiles_deflate_p3.tif"
    )
    grid = almucantar.read_elevation_geotiff(path.read_bytes())
    assert (grid.rows, grid.columns) == (7, 5)
    assert grid.north_latitude_deg == pytest.approx(47.75)
    assert grid.heights_m[0] == pytest.approx(1000.25)
    assert almucantar.read_elevation_geotiff(path.read_bytes(), level=1).rows == 4
    with pytest.raises(ValueError, match="GeoTIFF"):
        almucantar.read_elevation_geotiff(b"II*\0")


def test_elevation_tiles():
    tiles = almucantar.elevation_tiles(VIENNA, 20000)
    assert (tiles[0].latitude_deg, tiles[0].longitude_deg) == (48, 16)


def test_map_tiles_and_terrarium():
    tiles = almucantar.map_tiles(VIENNA, 100000, 10)
    assert (tiles[0].zoom, tiles[0].x, tiles[0].distance_m) == (10, 558, 0)
    # 2 x 2 pixels at 500 m: 129 * 256 + 244 - 32768 = 500.
    grid = almucantar.terrarium_grid(bytes([129, 244, 0, 255] * 4), 2, 2, tiles[0])
    assert grid.heights_m == [500.0] * 4


def test_terrain_events_of_the_moon():
    flat = almucantar.HorizonProfile(
        azimuth_step_deg=1, altitude_deg=[0.0] * 360, distance_m=[1000.0] * 360
    )
    moon = almucantar.TerrainEventOptions(body=almucantar.Body.moon)
    events = almucantar.terrain_events(
        VIENNA, flat, SOLSTICE, SOLSTICE + 3 * 86400, moon
    )
    assert 5 <= len(events.crossings) <= 7
    rise = next(c for c in events.crossings if c.rising)
    seen = almucantar.moon_position(rise.unix_utc + 2, VIENNA)
    assert seen.altitude_deg + 0.2667 > 0


def test_elevation_geotiff_prefix_size():
    path = (
        pathlib.Path(__file__).parents[2]
        / "test/data/geotiff/float_tiles_deflate_p3.tif"
    )
    data = path.read_bytes()
    size = almucantar.elevation_geotiff_prefix_size(data, 1)
    assert 0 < size <= len(data)
    assert almucantar.read_elevation_geotiff(data[:size], 1).rows == 4
    assert almucantar.elevation_geotiff_prefix_size(data[:16], 0) == 0


def test_min_distance_m():
    options = almucantar.HorizonOptions(min_distance_m=2000)
    assert options.min_distance_m == 2000
    assert almucantar.HorizonOptions().min_distance_m == 0
    with pytest.raises(ValueError, match="min_distance_m"):
        almucantar.horizon_profile(
            VIENNA, [flat_grid()], almucantar.HorizonOptions(min_distance_m=-1)
        )
