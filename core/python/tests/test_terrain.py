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


def test_map_tiles_and_terrarium():
    tiles = sunradar.map_tiles(VIENNA, 100000, 10)
    assert (tiles[0].zoom, tiles[0].x, tiles[0].distance_m) == (10, 558, 0)
    # 2 x 2 pixels at 500 m: 129 * 256 + 244 - 32768 = 500.
    grid = sunradar.terrarium_grid(bytes([129, 244, 0, 255] * 4), 2, 2, tiles[0])
    assert grid.heights_m == [500.0] * 4


def test_terrain_events_of_the_moon():
    flat = sunradar.HorizonProfile(
        azimuth_step_deg=1, altitude_deg=[0.0] * 360, distance_m=[1000.0] * 360
    )
    moon = sunradar.TerrainEventOptions(body=sunradar.Body.moon)
    events = sunradar.terrain_events(VIENNA, flat, SOLSTICE, SOLSTICE + 3 * 86400, moon)
    assert 5 <= len(events.crossings) <= 7
    rise = next(c for c in events.crossings if c.rising)
    seen = sunradar.moon_position(rise.unix_utc + 2, VIENNA)
    assert seen.altitude_deg + 0.2667 > 0


def test_elevation_geotiff_prefix_size():
    path = (
        pathlib.Path(__file__).parents[2]
        / "test/data/geotiff/float_tiles_deflate_p3.tif"
    )
    data = path.read_bytes()
    size = sunradar.elevation_geotiff_prefix_size(data, 1)
    assert 0 < size <= len(data)
    assert sunradar.read_elevation_geotiff(data[:size], 1).rows == 4
    assert sunradar.elevation_geotiff_prefix_size(data[:16], 0) == 0
