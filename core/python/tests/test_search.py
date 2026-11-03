import pytest

import almucantar

VIENNA = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)
DAY = 86400
YEAR_START = 1767222000  # 2026-01-01T00:00 CET


def test_find_time():
    t = YEAR_START + 120 * DAY + 10 * 3600  # 2026-05-01T10:00 CET
    direction = almucantar.sun_position(t, VIENNA)
    match = almucantar.find_time(
        VIENNA, direction, YEAR_START + 120 * DAY, YEAR_START + 121 * DAY
    )
    assert match.unix_utc == pytest.approx(t, abs=1)
    assert match.separation_deg < 1e-3
    assert match.sun.altitude_deg == pytest.approx(direction.altitude_deg, abs=1e-3)


def test_find_dates():
    t = YEAR_START + 120 * DAY + 10 * 3600
    matches = almucantar.find_dates(
        VIENNA, almucantar.sun_position(t, VIENNA), YEAR_START, YEAR_START + 365 * DAY
    )
    days = [round((m.unix_utc - YEAR_START) / DAY, 1) for m in matches]
    assert days == [120.4, 222.4]  # 2026-05-01 and 2026-08-11


def test_find_dates_threshold():
    north = almucantar.Horizontal(azimuth_deg=0, altitude_deg=30)
    assert (
        almucantar.find_dates(VIENNA, north, YEAR_START, YEAR_START + 365 * DAY) == []
    )
    options = almucantar.SearchOptions(max_separation_deg=90)
    assert almucantar.find_dates(
        VIENNA, north, YEAR_START, YEAR_START + 365 * DAY, options
    )


def test_invalid_arguments():
    with pytest.raises(ValueError, match="shorter than one day"):
        almucantar.find_dates(
            VIENNA, almucantar.Horizontal(), YEAR_START, YEAR_START + 3600
        )
    with pytest.raises(ValueError, match="end_utc"):
        almucantar.find_time(VIENNA, almucantar.Horizontal(), YEAR_START, YEAR_START)


def test_find_azimuth_times():
    day = YEAR_START + 120 * DAY
    t = day + 9.5 * 3600
    sun = almucantar.sun_position(t, VIENNA)
    crossings = almucantar.find_azimuth_times(VIENNA, sun.azimuth_deg, day, day + DAY)
    assert len(crossings) == 1
    assert crossings[0].unix_utc == pytest.approx(t, abs=1)
