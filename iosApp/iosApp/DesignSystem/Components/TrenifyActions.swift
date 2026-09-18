import SwiftUI

struct TrenifyPrimaryAction: View {
    var title: String
    var enabled = true
    var loading = false
    var accessibilityLabel: String? = nil
    var accessibilityHint = ""
    var loadingLabel = String(localized: "Loading")
    var action: () -> Void

    var body: some View {
        TrenifyAction(title: title, enabled: enabled, loading: loading, primary: true,
                      accessibilityLabel: accessibilityLabel, accessibilityHint: accessibilityHint,
                      loadingLabel: loadingLabel, action: action)
    }
}

struct TrenifySecondaryAction: View {
    var title: String
    var enabled = true
    var loading = false
    var accessibilityLabel: String? = nil
    var accessibilityHint = ""
    var loadingLabel = String(localized: "Loading")
    var action: () -> Void

    var body: some View {
        TrenifyAction(title: title, enabled: enabled, loading: loading, primary: false,
                      accessibilityLabel: accessibilityLabel, accessibilityHint: accessibilityHint,
                      loadingLabel: loadingLabel, action: action)
    }
}

private struct TrenifyAction: View {
    var title: String
    var enabled: Bool
    var loading: Bool
    var primary: Bool
    var accessibilityLabel: String?
    var accessibilityHint: String
    var loadingLabel: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: TrenifySpacing.m) {
                if loading { ProgressView().accessibilityHidden(true) }
                Text(title).fixedSize(horizontal: false, vertical: true)
            }
            .font(TrenifyTypography.bodyEmphasized)
            .foregroundStyle(enabled && !loading ? (primary ? TrenifyColors.accentOn : TrenifyColors.accent) : TrenifyColors.textTertiary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.horizontal, TrenifySpacing.m)
        }
        .modifier(TrenifyActionStyle(primary: primary))
        .controlSize(.large)
        .buttonBorderShape(.capsule)
        .disabled(!enabled || loading)
        .accessibilityLabel(Text(accessibilityLabel ?? title))
        .accessibilityHint(Text(accessibilityHint))
        .accessibilityValue(Text(loading ? loadingLabel : ""))
    }
}

/// Use the system primitive styles, which preserve native press, focus and glass adaptation.
struct TrenifyActionStyle: ViewModifier {
    var primary: Bool
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.trenifyAccessibility) private var preferences

    @ViewBuilder
    func body(content: Content) -> some View {
        // iOS 26+ only: Liquid Glass is baseline. The opaque bordered styles
        // remain solely as the Reduce Transparency / Increased Contrast
        // accessibility adaptation, never as an OS-version fallback.
        if TrenifyActionMaterialPolicy.usesGlass(
            osMajor: ProcessInfo.processInfo.operatingSystemVersion.majorVersion,
            reduceTransparency: reduceTransparency || preferences.reduceTransparency,
            increasedContrast: contrast == .increased || preferences.increasedContrast
        ) {
            if primary {
                content.tint(TrenifyColors.accent).buttonStyle(.glassProminent)
            } else {
                content.tint(TrenifyColors.accent).buttonStyle(.glass)
            }
        } else {
            if primary {
                content.tint(TrenifyColors.accent).buttonStyle(.borderedProminent)
            } else {
                content.tint(TrenifyColors.accent).buttonStyle(.bordered)
            }
        }
    }
}
