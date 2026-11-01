import sunradar

VIENNA = sunradar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)


def test_moon():
    t = 1767225600
    airless = sunradar.moon_position(t, VIENNA, sunradar.MoonOptions(refraction=False))
    seen = sunradar.moon_position(t, VIENNA)
    assert seen.azimuth_deg == airless.azimuth_deg
    assert seen.altitude_deg >= airless.altitude_deg
    assert sunradar.moon_illuminated_fraction(1767441600) > 0.99


def test_astronomy_engine_model():
    t = 1782039420
    noaa = sunradar.sun_position(t, VIENNA)
    ae = sunradar.sun_position(
        t, VIENNA, sunradar.SunOptions(model=sunradar.SolarModel.astronomy_engine)
    )
    assert abs(noaa.altitude_deg - ae.altitude_deg) < 0.03
    assert abs(noaa.azimuth_deg - ae.azimuth_deg) < 0.1
