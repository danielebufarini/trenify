import SwiftUI

/// Consumers/previews may request stricter adaptation. System accessibility preferences always win.
struct TrenifyAccessibility {
    var reduceMotion = false
    var reduceTransparency = false
    var increasedContrast = false
}

private struct TrenifyAccessibilityKey: EnvironmentKey {
    static let defaultValue = TrenifyAccessibility()
}

extension EnvironmentValues {
    var trenifyAccessibility: TrenifyAccessibility {
        get { self[TrenifyAccessibilityKey.self] }
        set { self[TrenifyAccessibilityKey.self] = newValue }
    }
}
