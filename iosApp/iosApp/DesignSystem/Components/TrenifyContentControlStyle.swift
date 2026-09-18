import SwiftUI

/// Restrained press confirmation for opaque content controls, retaining native Button semantics.
struct TrenifyContentControlStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.trenifyAccessibility) private var preferences

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(TrenifyMotionRole.quick.animation(reduceMotion: reduceMotion || preferences.reduceMotion),
                       value: configuration.isPressed)
    }
}
