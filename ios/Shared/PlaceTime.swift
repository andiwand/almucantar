import Foundation

/// The days and the times in the time zone of the place, not of the device: a dropped place can be
/// in another time zone. All the text of a day or a time goes through these functions.
enum PlaceTime {
    /// The Gregorian calendar in the time zone of the place.
    static func calendar(_ timeZone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = .current
        return calendar
    }

    /// The start and the end of the day of `date` in the calendar, in Unix seconds. A day with a
    /// change of the daylight saving time has 23 or 25 hours.
    static func day(of date: Date, calendar: Calendar) -> (start: Double, end: Double) {
        let start = calendar.startOfDay(for: date)
        let end = calendar.date(byAdding: .day, value: 1, to: start)!
        return (start.timeIntervalSince1970, end.timeIntervalSince1970)
    }

    /// The time of day, for example "07:12" or "7:12 AM", in the format of the locale.
    static func time(_ unix: Double, _ timeZone: TimeZone) -> String {
        Date(timeIntervalSince1970: unix).formatted(Date.FormatStyle(date: .omitted, time: .shortened, timeZone: timeZone))
    }

    /// The date, for example "2 Nov 2026".
    static func date(_ date: Date, _ timeZone: TimeZone) -> String {
        date.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: timeZone))
    }

    /// The day, the month, and the time, for example "2 Nov, 07:12".
    static func dayAndTime(_ unix: Double, _ timeZone: TimeZone) -> String {
        Date(timeIntervalSince1970: unix).formatted(
            Date.FormatStyle(timeZone: timeZone).day().month(.abbreviated).hour().minute())
    }
}
