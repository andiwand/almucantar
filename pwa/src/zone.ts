// Times in the time zone of the place, not of the browser: a dropped place can be in another
// time zone. Intl gives the offset of any IANA time zone.

/** The time zone of the browser. */
export const browserTimeZone = (): string => Intl.DateTimeFormat().resolvedOptions().timeZone;

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  let f = formatters.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone: zone,
      hourCycle: "h23",
      year: "numeric",
      month: "numeric",
      day: "numeric",
      hour: "numeric",
      minute: "numeric",
      second: "numeric",
    });
    formatters.set(zone, f);
  }
  return f;
}

/** The wall clock in a time zone. month is 1 to 12. */
export interface WallTime {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
}

export function wallTime(zone: string, unixMs: number): WallTime {
  const parts: Record<string, number> = {};
  for (const p of formatter(zone).formatToParts(new Date(unixMs))) {
    if (p.type !== "literal") parts[p.type] = Number(p.value);
  }
  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    hour: parts.hour,
    minute: parts.minute,
  };
}

/** The offset of the time zone from UTC at unixMs, in ms (east is positive). */
export function offsetMs(zone: string, unixMs: number): number {
  const w = wallTime(zone, unixMs);
  const seconds = Math.floor(unixMs / 1000) % 60;
  const asUtc = Date.UTC(w.year, w.month - 1, w.day, w.hour, w.minute, seconds);
  return asUtc - Math.floor(unixMs / 1000) * 1000;
}

/**
 * The Unix time in ms of a wall clock time in a zone. The day can be out of its month range
 * (Date.UTC normalizes it). A time in the gap of a DST change gives the time after the gap, for
 * example 00:30 gives 01:30 on a day that starts at 01:00.
 */
export function fromWallTime(
  zone: string,
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0,
): number {
  const local = Date.UTC(year, month - 1, day, hour, minute);
  // The offset at the guess can differ from the offset at the result, so check the second guess.
  const first = local - offsetMs(zone, local);
  const second = local - offsetMs(zone, first);
  if (second === first || local - offsetMs(zone, second) === second) return second;
  // No guess is stable, so the time is in a gap. The later guess uses the offset before the
  // change, which gives the time after the gap.
  return Math.max(first, second);
}

/** The start of the day `offsetDays` after the day of `nowMs` in the zone, in Unix seconds. */
export function startOfDay(zone: string, nowMs: number, offsetDays: number): number {
  const w = wallTime(zone, nowMs);
  return fromWallTime(zone, w.year, w.month, w.day + offsetDays) / 1000;
}
