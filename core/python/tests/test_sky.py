import pytest

import sunradar

SYDNEY = sunradar.Observer(latitude_deg=-33.8688, longitude_deg=151.2093)
# 2026-06-21 00:00 UTC.
SOLSTICE = 1782000000


def test_planets():
    no_refraction = sunradar.SkyOptions(refraction=False)
    for planet in sunradar.Planet.__members__.values():
        airless = sunradar.planet_position(planet, SOLSTICE, SYDNEY, no_refraction)
        seen = sunradar.planet_position(planet, SOLSTICE, SYDNEY)
        assert seen.azimuth_deg == airless.azimuth_deg
        assert seen.altitude_deg > airless.altitude_deg
    assert sunradar.planet_magnitude(sunradar.Planet.venus, SOLSTICE) < -3.5


def test_milky_way():
    plane = sunradar.galactic_plane(SOLSTICE, SYDNEY, step_deg=10)
    assert len(plane) == 36
    center = sunradar.galactic_position(
        sunradar.GALACTIC_CENTER_LONGITUDE_DEG,
        sunradar.GALACTIC_CENTER_LATITUDE_DEG,
        SOLSTICE,
        SYDNEY,
    )
    assert plane[0].azimuth_deg == pytest.approx(center.azimuth_deg)
    assert plane[0].altitude_deg == pytest.approx(center.altitude_deg)
    with pytest.raises(ValueError, match="step_deg"):
        sunradar.galactic_plane(SOLSTICE, SYDNEY, step_deg=0)
