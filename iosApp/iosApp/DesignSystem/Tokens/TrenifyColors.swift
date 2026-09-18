import SwiftUI
import UIKit

/// Native dynamic colors. Content surfaces are opaque; glass belongs to action controls.
enum TrenifyColors {
    static let background = Color(uiColor: .systemGroupedBackground)
    static let surface = Color(uiColor: .secondarySystemGroupedBackground)
    static let surfaceRaised = Color(uiColor: .tertiarySystemBackground)
    static let surfaceMuted = adaptive(0xE8EFEE, 0x29383D)
    static let textPrimary = Color(uiColor: .label)
    // Opaque neutral keeps 4.5:1 on tinted surfaces where secondaryLabel does not.
    static let textSecondary = adaptive(0x465B60, 0xB9CBCF)
    static let textTertiary = adaptive(0x59676E, 0xABBCC2)
    static let accent = adaptive(0x006578, 0x82DBE8)
    static let accentOn = adaptive(0xFFFFFF, 0x00343E)
    static let divider = Color(uiColor: .separator)
    static let statusOnTime = adaptive(0x176343, 0x8CDBB1)
    static let statusDelayed = adaptive(0x855000, 0xF5C16C)
    static let statusCancelled = adaptive(0xB32634, 0xFFADB5)
    static let statusArrived = adaptive(0x315E89, 0xA7CDF2)
    static let statusWarning = adaptive(0x794E10, 0xF0C780)
    static let selection = adaptive(0xD5F0F3, 0x204B55)
    static let focus = accent

    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let rgb = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((rgb >> 16) & 255) / 255,
                           green: CGFloat((rgb >> 8) & 255) / 255,
                           blue: CGFloat(rgb & 255) / 255, alpha: 1)
        })
    }
}
