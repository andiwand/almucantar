import SwiftUI

/// The date and time sliders of the apps. The day is an offset from today, and a nil minute is the
/// time now. All days and times are in the time zone of `calendar`, the time zone of the place.
struct TimePanel: SwiftUI.View {
    let now: Date
    let calendar: Calendar
    @Binding var dayOffset: Double
    @Binding var minuteOfDay: Double?

    var body: some SwiftUI.View {
        VStack(alignment: .leading) {
            HStack {
                Text(PlaceTime.date(calendar.date(byAdding: .day, value: Int(dayOffset), to: now)!, calendar.timeZone))
                    .frame(width: 120, alignment: .leading)
                Slider(value: $dayOffset, in: -183...183, step: 1)
                Button("Today") { dayOffset = 0 }
            }
            HStack {
                let minute = minuteOfDay ?? Double(calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now))
                Text(String(format: "%02d:%02d", Int(minute) / 60, Int(minute) % 60) + (minuteOfDay == nil ? " (now)" : ""))
                    .frame(width: 120, alignment: .leading)
                Slider(value: Binding(get: { minute }, set: { minuteOfDay = $0 }), in: 0...1439, step: 1)
                Button("Now") { minuteOfDay = nil }
            }
        }
    }

    /// The selected time in Unix seconds: now, or the wall clock time `minuteOfDay` on the day
    /// `dayOffset` days from today.
    static func time(now: Date, dayOffset: Double, minuteOfDay: Double?, calendar: Calendar) -> Double {
        if minuteOfDay == nil && dayOffset == 0 { return now.timeIntervalSince1970 }
        let components = calendar.dateComponents([.hour, .minute], from: now)
        let minute = minuteOfDay.map { Int($0) } ?? components.hour! * 60 + components.minute!
        let day = calendar.date(byAdding: .day, value: Int(dayOffset), to: now)!
        let start = calendar.startOfDay(for: day)
        // The wall clock time, not midnight + minutes: a day with a DST change has 23 or 25 hours.
        return (calendar.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: start) ?? start)
            .timeIntervalSince1970
    }
}
