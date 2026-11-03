import almucantar

VIENNA = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)


def test_moon():
    t = 1767225600
    airless = almucantar.moon_position(
        t, VIENNA, almucantar.MoonOptions(refraction=False)
    )
    seen = almucantar.moon_position(t, VIENNA)
    assert seen.azimuth_deg == airless.azimuth_deg
    assert seen.altitude_deg >= airless.altitude_deg
    assert almucantar.moon_illuminated_fraction(1767441600) > 0.99


def test_astronomy_engine_model():
    t = 1782039420
    noaa = almucantar.sun_position(t, VIENNA)
    ae = almucantar.sun_position(
        t, VIENNA, almucantar.SunOptions(model=almucantar.SolarModel.astronomy_engine)
    )
    assert abs(noaa.altitude_deg - ae.altitude_deg) < 0.03
    assert abs(noaa.azimuth_deg - ae.azimuth_deg) < 0.1
