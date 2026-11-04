import math

import almucantar

# 2026-01-01 and 2028-01-01, 00:00 UTC.
START = 1767225600
END = 1830297600


def test_solar_eclipses_and_the_central_line():
    eclipses = almucantar.solar_eclipses(START, END)
    assert len(eclipses) == 4
    total = [e for e in eclipses if e.kind == almucantar.EclipseKind.total]
    assert len(total) == 2
    line = almucantar.eclipse_central_line(total[1].peak_utc, 120)
    assert len(line) > 50
    assert all(p.umbra_radius_km > 0 for p in line)
    vienna = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)
    local = almucantar.local_solar_eclipses(vienna, START, END)
    assert all(e.kind == almucantar.EclipseKind.partial for e in local)
    assert all(math.isnan(e.total_begin_utc) for e in local)


def test_lunar_eclipses():
    eclipses = almucantar.lunar_eclipses(START, END)
    assert eclipses[0].kind == almucantar.EclipseKind.total
    assert eclipses[0].partial_begin_utc < eclipses[0].peak_utc
