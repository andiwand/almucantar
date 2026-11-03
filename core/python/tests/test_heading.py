import math

import pytest

import almucantar

VIENNA = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)


def test_predict_and_update():
    e = almucantar.HeadingEstimate(offset_deg=10, variance_deg2=4)
    assert almucantar.heading_predict(e, 60).variance_deg2 == pytest.approx(5)
    u = almucantar.heading_update(
        e, almucantar.HeadingMeasurement(offset_deg=20, variance_deg2=4)
    )
    assert u.offset_deg == pytest.approx(15)
    assert u.variance_deg2 == pytest.approx(2)
    assert (
        almucantar.HeadingEstimate().variance_deg2
        == almucantar.UNKNOWN_HEADING_VARIANCE_DEG2
    )


def test_heading_from_direction():
    # A device upright in portrait, with the back camera to the north of the frame.
    q = almucantar.Quaternion(w=math.cos(math.pi / 4), x=math.sin(math.pi / 4))
    view = almucantar.View(device_to_enu=q)
    # The center of the screen shows the azimuth 0 of the frame. If it is truly 70°, the
    # offset is 70°.
    m = almucantar.heading_from_direction(
        view, almucantar.ScreenPoint(500, 500), 70, 0.5
    )
    assert m.offset_deg == pytest.approx(70)
    assert m.variance_deg2 == pytest.approx(0.25)


def test_heading_from_magnetometer():
    # The same device. A horizontal field to the north of the frame (device -z is north, so
    # the field is along device -z) plus a vertical part (device +y is up).
    q = almucantar.Quaternion(w=math.cos(math.pi / 4), x=math.sin(math.pi / 4))
    t = 1767225600
    m = almucantar.heading_from_magnetometer(
        q, almucantar.Vector3(0, -40, -20), VIENNA, t
    )
    # The frame north is magnetic north, so the offset is the declination.
    assert m.offset_deg == pytest.approx(almucantar.magnetic_declination_deg(VIENNA, t))
    with pytest.raises(ValueError, match="is 0"):
        almucantar.heading_from_magnetometer(q, almucantar.Vector3(), VIENNA, t)


def test_heading_filter():
    # The device of test_heading_from_magnetometer, with a hard-iron bias in the field.
    q = almucantar.Quaternion(w=math.cos(math.pi / 4), x=math.sin(math.pi / 4))
    t = 1767225600
    f = almucantar.heading_filter_start(almucantar.Vector3(1, 2, 3), 0.5)
    assert f.variance_deg2 == almucantar.UNKNOWN_HEADING_VARIANCE_DEG2
    assert len(f.covariance) == almucantar.HEADING_FILTER_SIZE**2
    assert f.bias_ut.y == 2
    f = almucantar.heading_filter_predict(f, 1)
    f = almucantar.heading_filter_update_magnetometer(
        f, q, almucantar.Vector3(1, -38, -17), VIENNA, t
    )
    # The frame north is magnetic north, so the offset is near the declination.
    declination = almucantar.magnetic_declination_deg(VIENNA, t)
    assert abs(f.offset_deg - declination) < 5
    f = almucantar.heading_filter_update(
        f, almucantar.HeadingMeasurement(offset_deg=10, variance_deg2=0.01)
    )
    assert f.offset_deg == pytest.approx(10, abs=0.1)
    with pytest.raises(ValueError, match="bias_sigma_ut"):
        almucantar.heading_filter_start(almucantar.Vector3(), 0)
    with pytest.raises(ValueError, match="disturbance_time_s"):
        almucantar.heading_filter_predict(
            f, 1, almucantar.HeadingFilterOptions(disturbance_time_s=0)
        )
