// The place of an app: the location of the device, a place that the user dropped on the map, or the
// most probable place. An app keeps the last place, so it starts at that place also offline and
// without a location. The times are in the time zone of the place, because a dropped place can be
// in another zone. The Android apps have the same rules (android/shared, Location.kt).
import type { Observer } from "@almucantar/core";
import { browserTimeZone, offsetMs } from "./zone";

export interface StoredPlace {
  observer: Observer;
  name: string;
  timeZone: string;
  manual: boolean;
  /** True for a place that the app guessed from the time zone or the locale: no location. */
  probable?: boolean;
}

/** A line of zone.tab (IANA): the country code, the coordinates, and the time zone. */
interface ZoneLine {
  country: string;
  coordinates: string;
  zone: string;
}

function zoneLines(zoneTab: string): ZoneLine[] {
  return zoneTab
    .split("\n")
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => line.split("\t"))
    .filter((columns) => columns.length >= 3)
    .map(([country, coordinates, zone]) => ({ country, coordinates, zone }));
}

/** Parses "+4813+01620" or "+404251-0740023" (ISO 6709, degrees, minutes, seconds). */
export function parseIso6709(text: string): Observer | null {
  const match = /^([+-])(\d{2})(\d{2})(\d{2})?([+-])(\d{3})(\d{2})(\d{2})?$/.exec(text);
  if (!match) return null;
  const degrees = (sign: string, d: string, m: string, s: string | undefined) =>
    (sign === "-" ? -1 : 1) * (Number(d) + Number(m) / 60 + Number(s ?? 0) / 3600);
  return {
    latitudeDeg: degrees(match[1], match[2], match[3], match[4]),
    longitudeDeg: degrees(match[5], match[6], match[7], match[8]),
  };
}

/** The country of the locale, also the likely one of a locale without a country ("de" is DE). */
function localeCountry(language: string): string {
  try {
    return new Intl.Locale(language).maximize().region ?? "";
  } catch {
    return "";
  }
}

/**
 * The most probable place without a location: the main city of the time zone `timeZone`, else the
 * main city of the country of the locale `language` (with the offset of the time zone if the
 * country has more zones), else the meridian of the offset of the time zone at the equator.
 * `zoneTab` is the text of assets/tzdata/zone.tab.
 */
export function probablePlace(
  zoneTab: string,
  timeZone = browserTimeZone(),
  language = typeof navigator === "undefined" ? "" : navigator.language,
  now = Date.now(),
): StoredPlace {
  const lines = zoneLines(zoneTab);
  const offset = offsetMs(timeZone, now);
  const city = (line: ZoneLine | undefined) => {
    const observer = line && parseIso6709(line.coordinates);
    if (!line || !observer) return null;
    const name = line.zone.slice(line.zone.lastIndexOf("/") + 1).replace(/_/g, " ");
    return { observer, name: `${name} · no location`, timeZone, manual: false, probable: true };
  };
  const byZone = city(lines.find((l) => l.zone === timeZone));
  if (byZone) return byZone;
  const country = localeCountry(language).toUpperCase();
  const inCountry = lines.filter((l) => country && l.country === country);
  const sameOffset = inCountry.find((l) => {
    try {
      return offsetMs(l.zone, now) === offset;
    } catch {
      return false; // a zone that this browser does not know
    }
  });
  const byCountry = city(sameOffset ?? inCountry[0]);
  if (byCountry) return byCountry;
  const longitudeDeg = Math.max(-180, Math.min(180, offset / 240_000));
  return {
    observer: { latitudeDeg: 0, longitudeDeg },
    name: "No location",
    timeZone,
    manual: false,
    probable: true,
  };
}

/** The last place in localStorage under `key`, or the most probable place (see probablePlace). */
export function loadPlace(key: string, zoneTab: string): StoredPlace {
  try {
    const stored = JSON.parse(localStorage.getItem(key) ?? "null") as StoredPlace | null;
    if (
      stored &&
      Number.isFinite(stored.observer?.latitudeDeg) &&
      Number.isFinite(stored.observer?.longitudeDeg) &&
      typeof stored.name === "string" &&
      typeof stored.timeZone === "string"
    ) {
      new Intl.DateTimeFormat("en-US", { timeZone: stored.timeZone }); // throws if not valid
      return stored;
    }
  } catch {
    // A damaged or old entry: use the most probable place.
  }
  return probablePlace(zoneTab);
}

/** The place of a location of the device. */
export function gpsPlace(position: GeolocationPosition): StoredPlace {
  const { latitude, longitude, accuracy } = position.coords;
  return {
    observer: { latitudeDeg: latitude, longitudeDeg: longitude },
    name: `${latitude.toFixed(3)}°, ${longitude.toFixed(3)}° · ±${Math.round(accuracy)} m`,
    timeZone: browserTimeZone(),
    manual: false,
  };
}
