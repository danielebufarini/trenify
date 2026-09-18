import Foundation

/// Europe/Rome railway wall-time conversion (T8.5 corrective). The shared
/// truth is a Rome-local date plus hour/minute; these helpers map it to an
/// absolute Date for the system pickers and back, always through an explicit
/// Rome calendar. Nothing here reads the device timezone, so rendering and
/// editing are identical on any simulator or device worldwide.
enum RomeDateConversion {
    static var romeTimeZone: TimeZone {
        // swiftlint:disable:next force_unwrapping
        TimeZone(identifier: "Europe/Rome")!
    }

    static var romeCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = romeTimeZone
        return calendar
    }

    /// Noon on the Rome calendar day avoids DST transition edges (the spring
    /// gap and autumn fold both fall in the early morning hours).
    static func date(year: Int, month: Int, day: Int) -> Date? {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = 12
        components.minute = 0
        return romeCalendar.date(from: components)
    }

    static func dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int) -> Date? {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        return romeCalendar.date(from: components)
    }

    static func wallParts(of date: Date) -> DateComponents {
        romeCalendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    }
}
