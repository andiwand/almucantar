"""The Python binding of the almucantar core.

Calculate the position, the path, and the rise and set times of the sun.

Example:
    >>> import almucantar
    >>> vienna = almucantar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)
    >>> almucantar.sun_position(1782039420, vienna)
    Horizontal(azimuth_deg=180..., altitude_deg=65...)
"""

from almucantar import _core
from almucantar._core import *  # noqa: F401,F403

__version__ = _core.version()
