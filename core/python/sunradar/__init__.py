"""The Python binding of the sunradar core.

Calculate the position, the path, and the rise and set times of the sun.

Example:
    >>> import sunradar
    >>> vienna = sunradar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)
    >>> sunradar.sun_position(1782039420, vienna)
    Horizontal(azimuth_deg=180..., altitude_deg=65...)
"""

from sunradar import _core
from sunradar._core import *  # noqa: F401,F403

__version__ = _core.version()
