// The place of an app: the location of the device, a place that the user dropped on the map, or a
// default place. An app keeps the last place, so it starts at that place also offline and without
// a location. The times are in the time zone of the place, because a dropped place can be in
// another zone.
import type { Observer } from "@almucantar/core";
import { browserTimeZone } from "./zone";

export interface StoredPlace {
  observer: Observer;
  name: string;
  timeZone: string;
  manual: boolean;
}

/** Vienna: the place before the first location, and without the location. */
export const DEFAULT_PLACE: StoredPlace = {
  observer: { latitudeDeg: 48.2082, longitudeDeg: 16.3738 },
  name: "Vienna · no location",
  timeZone: "Europe/Vienna",
  manual: false,
};

/** The last place in localStorage under `key`, or DEFAULT_PLACE. */
export function loadPlace(key: string): StoredPlace {
  try {
    const stored = JSON.parse(localStorage.getItem(key) ?? "null") as StoredPlace | null;
    if (stored && Number.isFinite(stored.observer?.latitudeDeg)) {
      new Intl.DateTimeFormat("en-US", { timeZone: stored.timeZone }); // throws if not valid
      return stored;
    }
  } catch {
    // A damaged or old entry: use the default.
  }
  return DEFAULT_PLACE;
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
