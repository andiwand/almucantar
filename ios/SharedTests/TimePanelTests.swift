import XCTest

@testable import AlmucantarShared

final class TimePanelTests: XCTestCase {
    private let vienna = PlaceTime.calendar(TimeZone(identifier: "Europe/Vienna")!)
    /// 2026-10-24T15:30 in Vienna (CEST), the day before the end of the daylight saving time.
    private let now = Date(timeIntervalSince1970: 1_792_848_600)

    func testNowIsTheTimeOfTheClock() {
        XCTAssertEqual(TimePanel.time(now: now, dayOffset: 0, minuteOfDay: nil, calendar: vienna), now.timeIntervalSince1970)
    }

    func testTheMinuteIsTheWallClockTimeOfThePlace() {
        // 06:00 on the same day is 04:00 UTC in CEST.
        XCTAssertEqual(TimePanel.time(now: now, dayOffset: 0, minuteOfDay: 360, calendar: vienna), 1_792_814_400)
        // The day after has 25 hours: 06:00 CET is 05:00 UTC, so it is 25 hours later, not 24.
        XCTAssertEqual(TimePanel.time(now: now, dayOffset: 1, minuteOfDay: 360, calendar: vienna), 1_792_814_400 + 25 * 3600)
    }

    func testAnotherDayKeepsTheTimeOfTheClock() {
        // 15:30 CET on the day after is 14:30 UTC.
        XCTAssertEqual(
            TimePanel.time(now: now, dayOffset: 1, minuteOfDay: nil, calendar: vienna), now.timeIntervalSince1970 + 25 * 3600)
    }
}
