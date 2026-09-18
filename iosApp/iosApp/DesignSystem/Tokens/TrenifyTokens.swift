import SwiftUI

/// Every font uses a native text style, including route times, so Dynamic Type stays authoritative.
enum TrenifyTypography {
    static let hero = Font.system(.largeTitle, design: .rounded).weight(.bold)
    static let screenTitle = Font.system(.title, design: .rounded).weight(.bold)
    static let sectionTitle = Font.system(.title2).weight(.semibold)
    static let routeStation = Font.system(.title3).weight(.semibold)
    static let routeTime = Font.system(.title, design: .rounded).weight(.bold).monospacedDigit()
    static let trainIdentity = Font.system(.headline)
    static let status = Font.system(.subheadline).weight(.semibold)
    static let body = Font.system(.body)
    static let bodyEmphasized = Font.system(.body).weight(.semibold)
    static let metadata = Font.system(.subheadline)
    static let label = Font.system(.subheadline).weight(.semibold)
    static let caption = Font.system(.caption)
}

enum TrenifySpacing {
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 22
    static let xxl: CGFloat = 30
    static let section: CGFloat = 34
    static let screenHorizontal: CGFloat = 20
}

enum TrenifyShapes {
    static let controlSmall: CGFloat = 10
    static let control: CGFloat = 16
    static let card: CGFloat = 24
    static let cardLarge: CGFloat = 30
    static let sheetContent: CGFloat = 28
    static let statusPill = Capsule()
}

enum TrenifyMotionRole: CaseIterable {
    case quick, standard, emphasized, routeProgress, selection, statusChange

    var duration: Double {
        switch self {
        case .quick: return 0.10
        case .standard: return 0.20
        case .emphasized: return 0.30
        case .routeProgress: return 0.45 // Guidance only; no train-position animation in T8.3.
        case .selection: return 0.16
        case .statusChange: return 0.22
        }
    }

    func animation(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeInOut(duration: duration)
    }
}

/// A visual policy only (iOS 26+ baseline; the osMajor gate is a formality).
/// The bordered styles apply solely as the Reduce Transparency / Increased
/// Contrast accessibility adaptation.
enum TrenifyActionMaterialPolicy {
    static func usesGlass(osMajor: Int, reduceTransparency: Bool, increasedContrast: Bool) -> Bool {
        osMajor >= 26 && !reduceTransparency && !increasedContrast
    }
}
