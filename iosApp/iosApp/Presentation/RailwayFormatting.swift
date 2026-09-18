import Foundation
@preconcurrency import SharedApp

/// Cross-feature railway presentation formatting. Railway wall times always render
/// in Europe/Rome, independent of the device timezone; durations stay in
/// whole minutes from shared state. No business logic lives here.
///
/// T8.14-C2: the hot row formatters are cached shared instances instead of
/// per-call constructions (DateFormatter creation is expensive and these run
/// per row on every list render). Configuration is unchanged on purpose:
/// fixed POSIX month abbreviations stay exactly as before (T8.12 owns
/// locale decisions). All call sites run on the main thread (SwiftUI bodies
/// and main-thread XCTest); the explicit opt-out documents that confinement.
enum RailwayFormatting {
    static func date(forEpochSeconds value: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(value))
    }

    nonisolated(unsafe) private static let romeTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.timeZone = RomeDateConversion.romeTimeZone
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    nonisolated(unsafe) private static let romeDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.timeZone = RomeDateConversion.romeTimeZone
        formatter.dateFormat = "d MMM"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    nonisolated(unsafe) private static let romeDateTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.timeZone = RomeDateConversion.romeTimeZone
        formatter.dateFormat = "d MMM HH:mm"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    static func time(_ epochSeconds: Int64) -> String {
        romeTimeFormatter.string(from: date(forEpochSeconds: epochSeconds))
    }

    static func date(_ epochSeconds: Int64) -> String {
        romeDateFormatter.string(from: date(forEpochSeconds: epochSeconds))
    }

    static func dateTime(_ epochSeconds: Int64) -> String {
        romeDateTimeFormatter.string(from: date(forEpochSeconds: epochSeconds))
    }

    /// Localized service-date label from an ISO-8601 date; locale medium format.
    static func serviceDate(_ iso: String) -> String {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return iso }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = RomeDateConversion.romeTimeZone
        guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) else { return iso }
        let formatter = DateFormatter()
        formatter.timeZone = RomeDateConversion.romeTimeZone
        formatter.locale = .autoupdatingCurrent
        formatter.dateStyle = .medium
        return formatter.string(from: date)
    }

    static func duration(minutes: Int64) -> String {
        let hours = minutes / 60
        let rest = minutes % 60
        if hours > 0 { return "\(hours)h \(String(format: "%02d", rest))m" }
        return "\(rest)m"
    }

    /// Semantic realtime tone for a railway service. Scheduled legs without
    /// correlated enrichment stay unknown; a null delay never implies on-time.
    static func statusTone(status: ModelTrainStatus, delayMinutes: KotlinInt?) -> TrenifyStatusTone {
        switch status {
        case .cancelled, .partiallyCancelled: return .cancelled
        case .arrived: return .arrived
        case .diverted, .rescheduled: return .warning
        case .running, .notDeparted:
            let delay = delayMinutes?.intValue ?? -1
            if delay > 0 { return .delayed }
            if delay == 0 { return .onTime }
            return .unknown
        case .unknown: return .unknown
        @unknown default: return .unknown
        }
    }
}
