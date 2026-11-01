import math

import pytest

import sunradar

VIENNA = sunradar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)

# A device held upright in portrait, with the back camera to the north: the device x
# axis points east, y up, and z south. This is a rotation of +90° about the east axis.
NORTH_UPRIGHT = sunradar.Quaternion(w=math.cos(math.pi / 4), x=math.sin(math.pi / 4))


def test_project_and_unproject():
    view = sunradar.View(device_to_enu=NORTH_UPRIGHT)
    f = view.camera.fx
    center = sunradar.project(view, sunradar.Horizontal(0, 0))
    assert center.valid
    assert center.point.x == pytest.approx(500)
    assert center.point.y == pytest.approx(500)

    right = sunradar.project(view, sunradar.Horizontal(10, 0))
    assert right.point.x == pytest.approx(500 + f * math.tan(math.radians(10)))

    d = sunradar.unproject(view, right.point)
    assert d.azimuth_deg == pytest.approx(10)
    assert d.altitude_deg == pytest.approx(0, abs=1e-9)

    assert not sunradar.project(view, sunradar.Horizontal(180, 0)).valid


def test_horizon_line():
    view = sunradar.View(device_to_enu=NORTH_UPRIGHT)
    lines = sunradar.horizon_line(view)
    assert len(lines) == 1
    assert all(p.y == pytest.approx(500) for p in lines[0])


def test_project_path():
    # Pitched up by 40° (a rotation of 130° about the east axis), and turned to the
    # south by the heading offset. In June the sun is about 65° high in the south.
    view = sunradar.View(
        device_to_enu=sunradar.Quaternion(
            w=math.cos(math.radians(65)), x=math.sin(math.radians(65))
        ),
        heading_offset_deg=180,
        camera=sunradar.camera_from_fov(1920, 1080, 70),
    )
    path = sunradar.sun_path(VIENNA, 1781992800, 1781992800 + 86400)
    lines = sunradar.project_path(view, path)
    assert len(lines) == 1
    assert all(isinstance(p, sunradar.ScreenPoint) for p in lines[0])
    # The noon point is above the center of the screen.
    noon = sunradar.project(view, sunradar.sun_position(1782039420, VIENNA))
    assert noon.valid
    assert noon.point.y < 540


def test_camera_from_fov():
    c = sunradar.camera_from_fov(1000, 1000, 60)
    assert c.fx == pytest.approx(sunradar.Camera().fx)
    assert (c.cx, c.cy) == (500, 500)
    with pytest.raises(ValueError):
        sunradar.camera_from_fov(1000, 1000, 180)


def test_magnetic_declination():
    # The WMM2025 test value at height 0 km: 2027.5, 13° S, 59° W: -17.49°.
    t = 1798761600 + 0.5 * 365 * 86400
    observer = sunradar.Observer(latitude_deg=-13, longitude_deg=-59)
    assert sunradar.magnetic_declination_deg(observer, t) == pytest.approx(
        -17.49, abs=0.006
    )


def test_invalid_view_raises_value_error():
    with pytest.raises(ValueError, match="length 0"):
        sunradar.project(
            sunradar.View(device_to_enu=sunradar.Quaternion(0, 0, 0, 0)),
            sunradar.Horizontal(),
        )
    with pytest.raises(ValueError, match="focal length"):
        sunradar.unproject(
            sunradar.View(camera=sunradar.Camera(fx=0)), sunradar.ScreenPoint()
        )
