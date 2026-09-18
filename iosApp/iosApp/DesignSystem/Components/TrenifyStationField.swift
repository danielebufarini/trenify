import SwiftUI

/// Selection surface only. The consuming feature owns the native search/text editor.
struct TrenifyStationField: View {
    var roleLabel: String
    var stationName: String?
    var placeholder: String
    var selected: Bool
    var enabled = true
    var focused = false
    var accessibilityLabel: String? = nil
    var accessibilityHint = ""
    var selectionStateLabel: String? = nil
    var clearLabel = String(localized: "Clear station")
    var onClear: (() -> Void)? = nil
    var action: () -> Void
    @FocusState private var keyboardFocused: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            if typeSize >= .xxxLarge {
                VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
                    selectionButton
                    clearButton.frame(maxWidth: .infinity, alignment: .trailing)
                }
            } else {
                HStack(spacing: TrenifySpacing.xs) {
                    selectionButton
                    clearButton
                }
            }
        }
        .background(selected ? TrenifyColors.surface : TrenifyColors.surfaceMuted,
                    in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
        .overlay {
            RoundedRectangle(cornerRadius: TrenifyShapes.control)
                .strokeBorder(focused || keyboardFocused ? TrenifyColors.focus : TrenifyColors.divider,
                              lineWidth: focused || keyboardFocused ? 2 : 1)
        }
        .disabled(!enabled)
    }

    private var selectionButton: some View {
            Button(action: action) {
                VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
                    Text(roleLabel).font(TrenifyTypography.label).foregroundStyle(TrenifyColors.textSecondary)
                    Text(stationName ?? placeholder)
                        .font(TrenifyTypography.routeStation)
                        .foregroundStyle(enabled && stationName != nil ? TrenifyColors.textPrimary : TrenifyColors.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .padding(TrenifySpacing.l)
                .contentShape(Rectangle())
            }
            .buttonStyle(TrenifyContentControlStyle())
            .focused($keyboardFocused)
            .accessibilityLabel(Text(accessibilityLabel ?? "\(roleLabel), \(stationName ?? placeholder)"))
            .accessibilityHint(Text(accessibilityHint))
            .accessibilityValue(Text(selectionStateLabel ?? (selected ? String(localized: "Selected") : String(localized: "No station selected"))))
    }

    @ViewBuilder private var clearButton: some View {
            if let onClear, stationName != nil {
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.body).foregroundStyle(enabled ? TrenifyColors.accent : TrenifyColors.textTertiary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(TrenifyContentControlStyle())
                .accessibilityLabel(Text(clearLabel))
                .padding(.trailing, TrenifySpacing.s)
            }
    }

}
