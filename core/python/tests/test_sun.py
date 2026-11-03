import math

import pytest

import almucantar

VIENNA = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)
TROMSO = almucantar.Observer(latitude_deg=69.6492, longitude_deg=18.9553)

# 2026-06-21T10:57Z, about the transit of the sun in Vienna.
SOLSTICE_NOON = 1782039420
# 2026-06-20T22:00Z, the local midnight in Vienna (CEST).
SOLSTICE_DAY_START = 1781992800


def test_version():
    assert almucantar.__version__ == almucantar.version()
    assert almucantar.__version__.count(".") >= 2


def test_sun_position_at_noon():
    h = almucantar.sun_position(SOLSTICE_NOON, VIENNA)
    assert 179 < h.azimuth_deg < 181
    assert 65 < h.altitude_deg < 65.5


def test_sun_position_refraction():
    seen = almucantar.sun_position(SOLSTICE_DAY_START + 6 * 3600, VIENNA)
    airless = almucantar.sun_position(
        SOLSTICE_DAY_START + 6 * 3600, VIENNA, almucantar.SunOptions(refraction=False)
    )
    assert seen.azimuth_deg == airless.azimuth_deg
    assert 0 < seen.altitude_deg - airless.altitude_deg < 0.2


def test_sun_events_normal_day():
    day = almucantar.sun_events(VIENNA, SOLSTICE_DAY_START, SOLSTICE_DAY_START + 86400)
    assert day.kind == almucantar.DayKind.normal
    rise, set_ = day.crossings
    assert rise.rising and not set_.rising
    # Sunrise at about 04:53 local time, sunset at about 21:00 local time.
    assert rise.unix_utc - SOLSTICE_DAY_START == pytest.approx(4.9 * 3600, abs=600)
    assert set_.unix_utc - SOLSTICE_DAY_START == pytest.approx(21.0 * 3600, abs=600)
    assert rise.unix_utc < day.culmination_utc < set_.unix_utc


def test_sun_events_polar_day_and_twilight():
    day = almucantar.sun_events(TROMSO, SOLSTICE_DAY_START, SOLSTICE_DAY_START + 86400)
    assert day.kind == almucantar.DayKind.polar_day
    assert day.crossings == []

    civil = almucantar.sun_events(
        VIENNA,
        SOLSTICE_DAY_START,
        SOLSTICE_DAY_START + 86400,
        almucantar.EventOptions(altitude_deg=-6),
    )
    normal = almucantar.sun_events(
        VIENNA, SOLSTICE_DAY_START, SOLSTICE_DAY_START + 86400
    )
    assert civil.crossings[0].unix_utc < normal.crossings[0].unix_utc


def test_sun_path():
    path = almucantar.sun_path(
        VIENNA,
        SOLSTICE_DAY_START,
        SOLSTICE_DAY_START + 86400,
        almucantar.PathOptions(step_s=3600),
    )
    assert len(path) == 25
    assert [p.unix_utc - SOLSTICE_DAY_START for p in path] == [
        k * 3600.0 for k in range(25)
    ]
    assert max(p.position.altitude_deg for p in path) > 60
    assert min(p.position.altitude_deg for p in path) < -10


def test_defaults_mirror_cpp():
    assert almucantar.SUNRISE_ALTITUDE_DEG == pytest.approx(-0.8333)
    assert almucantar.EventOptions().altitude_deg == almucantar.SUNRISE_ALTITUDE_DEG
    assert almucantar.PathOptions().step_s == 300
    assert almucantar.SunOptions().refraction is True
    assert almucantar.Atmosphere().pressure_hpa == 1010
    assert almucantar.SunOptions().model == almucantar.SolarModel.noaa


def test_invalid_arguments_raise_value_error():
    with pytest.raises(ValueError, match="latitude_deg"):
        almucantar.sun_position(0, almucantar.Observer(latitude_deg=91))
    with pytest.raises(ValueError, match="not finite"):
        almucantar.sun_position(math.nan, VIENNA)
    with pytest.raises(ValueError, match="end_utc"):
        almucantar.sun_events(VIENNA, 10, 10)
    with pytest.raises(ValueError, match="step_s"):
        almucantar.sun_path(VIENNA, 0, 10, almucantar.PathOptions(step_s=0))


def test_repr():
    assert repr(VIENNA) == "Observer(latitude_deg=48.2082, longitude_deg=16.3738)"
    assert (
        repr(almucantar.Horizontal(1, 2))
        == "Horizontal(azimuth_deg=1.0, altitude_deg=2.0)"
    )
    day = almucantar.sun_events(VIENNA, SOLSTICE_DAY_START, SOLSTICE_DAY_START + 86400)
    # The full time is visible, not 1.78201e+09.
    assert repr(day.crossings[0]).startswith("Crossing(unix_utc=17820")
