# Time zone coordinates

`zone.tab` is from the IANA time zone database, version 2026f
(<https://data.iana.org/time-zones/tzdata-latest.tar.gz>, SHA-256
`01b4a91cec5167ed48c11e8ab78f2048cead78ce94b0cafcad0ae8c5a3044d3d`). It is in the
public domain.

The Android apps and the PWAs use the coordinates of the main city of the device time zone as
the place when they have no location and no last place. If the table does not have the zone, they
use the main city of the country of the locale (the first column). The file has one line for each country and zone, also for linked zones
(for example `Europe/Bratislava`), so it covers more device time zones than
`zone1970.tab`.
