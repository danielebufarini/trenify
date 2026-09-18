import SwiftUI

/// Visual treatment, not domain state. Native consumers map shared semantic outputs and supply labels.
enum TrenifyStatusTone: CaseIterable {
    case onTime, delayed, cancelled, arrived, warning, unknown

    var symbol: String {
        switch self {
        case .onTime: return "checkmark.circle.fill"
        case .delayed: return "clock.badge.exclamationmark"
        case .cancelled: return "xmark.octagon.fill"
        case .arrived: return "flag.checkered"
        case .warning: return "exclamationmark.triangle.fill"
        case .unknown: return "questionmark.circle"
        }
    }

    var color: Color {
        switch self {
        case .onTime: return TrenifyColors.statusOnTime
        case .delayed: return TrenifyColors.statusDelayed
        case .cancelled: return TrenifyColors.statusCancelled
        case .arrived: return TrenifyColors.statusArrived
        case .warning: return TrenifyColors.statusWarning
        case .unknown: return TrenifyColors.textSecondary
        }
    }
}

struct TrenifyStatusPill: View {
    var tone: TrenifyStatusTone
    var label: String
    var accessibilityLabel: String? = nil

    var body: some View {
        Label {
            Text(label).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: tone.symbol).accessibilityHidden(true)
        }
        .font(TrenifyTypography.status)
        .foregroundStyle(tone.color)
        .padding(.horizontal, TrenifySpacing.m)
        .padding(.vertical, TrenifySpacing.s)
        .background(TrenifyColors.surfaceMuted, in: TrenifyShapes.statusPill)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityLabel ?? label))
    }
}
