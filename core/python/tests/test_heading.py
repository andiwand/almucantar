import math

import pytest

import sunradar

VIENNA = sunradar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)


def test_predict_and_update():
    e = sunradar.HeadingEstimate(offset_deg=10, variance_deg2=4)
    assert sunradar.heading_predict(e, 60).variance_deg2 == pytest.approx(5)
    u = sunradar.heading_update(
        e, sunradar.HeadingMeasurement(offset_deg=20, variance_deg2=4)
    )
    assert u.offset_deg == pytest.approx(15)
    assert u.variance_deg2 == pytest.approx(2)
    assert (
        sunradar.HeadingEstimate().variance_deg2
        == sunradar.UNKNOWN_HEADING_VARIANCE_DEG2
    )


def test_heading_from_direction():
    # A device upright in portrait, with the back camera to the north of the frame.
    q = sunradar.Quaternion(w=math.cos(math.pi / 4), x=math.sin(math.pi / 4))
    view = sunradar.View(device_to_enu=q)
    # The center of the screen shows the azimuth 0 of the frame. If it is truly 70°, the
    # offset is 70°.
    m = sunradar.heading_from_direction(view, sunradar.ScreenPoint(500, 500), 70, 0.5)
    assert m.offset_deg == pytest.approx(70)
    assert m.variance_deg2 == pytest.approx(0.25)


def test_heading_from_magnetometer():
    # The same device. A horizontal field to the north of the frame (device -z is north, so
    # the field is along device -z) plus a vertical part (device +y is up).
    q = sunradar.Quaternion(w=math.cos(math.pi / 4), x=math.sin(math.pi / 4))
    t = 1767225600
    m = sunradar.heading_from_magnetometer(q, sunradar.Vector3(0, -40, -20), VIENNA, t)
    # The frame north is magnetic north, so the offset is the declination.
    assert m.offset_deg == pytest.approx(sunradar.magnetic_declination_deg(VIENNA, t))
    with pytest.raises(ValueError, match="is 0"):
        sunradar.heading_from_magnetometer(q, sunradar.Vector3(), VIENNA, t)
