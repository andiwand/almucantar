# The offline base map

`basemap.json` is the map that the apps draw when they have no network, so the user can drop a
place (`PLAN.md` 13.3). It comes from Natural Earth 1:10m (public domain): the coastlines, the
land borders of the countries, the larger lakes and rivers, and 7342 place names.

Do not edit it by hand. `scripts/basemap/build_basemap.py` makes it, and its docstring describes
the format. The apps show the attribution "Map: Natural Earth (public domain)".
