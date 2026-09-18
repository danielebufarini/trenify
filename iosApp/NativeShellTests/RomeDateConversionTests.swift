import XCTest
import Foundation

/// Deterministic Europe/Rome wall-time tests (T8.5 corrective). The oracle is
/// an independent ISO-8601 parse with an explicit offset — never the same
/// calendar construction under test — plus round-trips across CET, CEST, a
/// month boundary and both DST transition days.
final class RomeDateConversionTests: XCTestCase {
    private func instant(_ iso: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        guard let date = formatter.date(from: iso) else {
            XCTFail("Bad oracle instant \(iso)")
            return Date(timeIntervalSince1970: 0)
        }
        return date
    }

    func testCetWallTime() {
        let converted = RomeDateConversion.dateTime(year: 2026, month: 1, day: 15, hour: 14, minute: 30)!
        XCTAssertEqual(converted.timeIntervalSince1970, instant("2026-01-15T14:30:00+01:00").timeIntervalSince1970, accuracy: 1)
        let parts = RomeDateConversion.wallParts(of: converted)
        XCTAssertEqual(parts.year, 2026)
        XCTAssertEqual(parts.month, 1)
        XCTAssertEqual(parts.day, 15)
        XCTAssertEqual(parts.hour, 14)
        XCTAssertEqual(parts.minute, 30)
    }

    func testCestWallTime() {
        let converted = RomeDateConversion.dateTime(year: 2026, month: 7, day: 15, hour: 14, minute: 30)!
        XCTAssertEqual(converted.timeIntervalSince1970, instant("2026-07-15T14:30:00+02:00").timeIntervalSince1970, accuracy: 1)
        let parts = RomeDateConversion.wallParts(of: converted)
        XCTAssertEqual(parts.year, 2026)
        XCTAssertEqual(parts.month, 7)
        XCTAssertEqual(parts.day, 15)
        XCTAssertEqual(parts.hour, 14)
        XCTAssertEqual(parts.minute, 30)
    }

    func testMonthBoundaryRoundTripsInBothOffsets() {
        // March opens in CET, September in CEST; Rome midnight is the previous
        // UTC day in both, which is exactly what the conversion must survive.
        for (month, day) in [(3, 1), (9, 1)] {
            let converted = RomeDateConversion.date(year: 2026, month: month, day: day)!
            let parts = RomeDateConversion.wallParts(of: converted)
            XCTAssertEqual(parts.year, 2026)
            XCTAssertEqual(parts.month, month)
            XCTAssertEqual(parts.day, day)
            XCTAssertEqual(parts.hour, 12)
        }
    }

    func testDstTransitionDays() {
        // Spring forward 2026-03-29 and fall back 2026-10-25: midday and
        // afternoon wall times are unambiguous and must round-trip.
        for (month, day) in [(3, 29), (10, 25)] {
            let converted = RomeDateConversion.dateTime(year: 2026, month: month, day: day, hour: 14, minute: 30)!
            let parts = RomeDateConversion.wallParts(of: converted)
            XCTAssertEqual(parts.year, 2026)
            XCTAssertEqual(parts.month, month)
            XCTAssertEqual(parts.day, day)
            XCTAssertEqual(parts.hour, 14)
            XCTAssertEqual(parts.minute, 30)
        }
    }

    func testConversionIgnoresDeviceTimezone() {
        // T8.12: the helpers never read the device timezone, so rendering
        // and editing are identical on any simulator or device worldwide.
        let prior = NSTimeZone.default
        NSTimeZone.default = TimeZone(identifier: "America/New_York")!
        defer { NSTimeZone.default = prior }
        let converted = RomeDateConversion.dateTime(year: 2026, month: 1, day: 15, hour: 14, minute: 30)!
        XCTAssertEqual(converted.timeIntervalSince1970, instant("2026-01-15T14:30:00+01:00").timeIntervalSince1970, accuracy: 1)
        let parts = RomeDateConversion.wallParts(of: converted)
        XCTAssertEqual(parts.year, 2026)
        XCTAssertEqual(parts.month, 1)
        XCTAssertEqual(parts.day, 15)
        XCTAssertEqual(parts.hour, 14)
        XCTAssertEqual(parts.minute, 30)
        // Rome midnight 2026-03-01 is the previous UTC day; the Rome wall
        // date must survive while the device sits far west of Rome.
        let midnight = RomeDateConversion.dateTime(year: 2026, month: 3, day: 1, hour: 0, minute: 0)!
        XCTAssertEqual(midnight.timeIntervalSince1970, instant("2026-03-01T00:00:00+01:00").timeIntervalSince1970, accuracy: 1)
        let midnightParts = RomeDateConversion.wallParts(of: midnight)
        XCTAssertEqual(midnightParts.month, 3)
        XCTAssertEqual(midnightParts.day, 1)
    }

    func testTextSyncRuleNeverFeedsSharedEchoBack() {
        // Free typing forwards (shared clears the endpoint by design).
        var typing = HomeComposerTextState(local: "")
        XCTAssertEqual(typing.userEdited("Roma", shared: ""), "Roma")
        // A shared echo of the same text must not feed back.
        var echo = HomeComposerTextState(local: "Roma")
        XCTAssertNil(echo.userEdited("Roma", shared: "Roma"))
        // Programmatic sync (select/swap/prefill) applies silently...
        var synced = HomeComposerTextState(local: "Roma")
        synced.applyShared("Roma Termini")
        XCTAssertEqual(synced.local, "Roma Termini")
        XCTAssertNil(synced.userEdited("Roma Termini", shared: "Roma Termini"))
        // ...while the next real keystroke still forwards and clears.
        XCTAssertEqual(synced.userEdited("Roma Termini X", shared: "Roma Termini"), "Roma Termini X")
    }
}
