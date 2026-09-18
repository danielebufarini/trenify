import SwiftUI

/// Semantic option identity; do not pass NativeMainEntry live-instance identities as tab IDs.
struct TrenifySegment<ID: Hashable>: Identifiable {
    var id: ID
    var title: String
    var enabled = true
}

struct TrenifySegmentedControl<ID: Hashable>: View {
    var options: [TrenifySegment<ID>]
    @Binding var selection: ID
    var accessibilityLabel: String
    var enabled = true
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.trenifyAccessibility) private var preferences

    var body: some View {
        // Native buttons preserve keyboard activation and VoiceOver actions. A selected trait,
        // checkmark and outline make selection redundant, including without color.
        ViewThatFits(in: .horizontal) {
            if typeSize < .xxxLarge {
                HStack(spacing: TrenifySpacing.xs) {
                    ForEach(options) { option in segment(option, horizontal: true) }
                }
                .fixedSize(horizontal: true, vertical: false)
            }
            VStack(spacing: TrenifySpacing.xs) {
                ForEach(options) { option in segment(option, horizontal: false) }
            }
        }
        .padding(TrenifySpacing.xs)
        .background(TrenifyColors.surfaceMuted, in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(accessibilityLabel))
        .animation(TrenifyMotionRole.selection.animation(reduceMotion: reduceMotion || preferences.reduceMotion), value: selection)
    }

    private func segment(_ option: TrenifySegment<ID>, horizontal: Bool) -> some View {
        let selected = option.id == selection
        return Button { selection = option.id } label: {
            HStack(spacing: TrenifySpacing.s) {
                if selected { Image(systemName: "checkmark").accessibilityHidden(true) }
                Text(option.title).fixedSize(horizontal: false, vertical: true)
            }
            .font(TrenifyTypography.label)
            .foregroundStyle(enabled && option.enabled ? TrenifyColors.textPrimary : TrenifyColors.textTertiary)
            .frame(maxWidth: horizontal ? nil : .infinity, minHeight: 44)
            .padding(.horizontal, TrenifySpacing.l)
            .padding(.vertical, TrenifySpacing.xs)
            .background(selected ? TrenifyColors.surfaceRaised : TrenifyColors.surfaceMuted,
                        in: RoundedRectangle(cornerRadius: TrenifyShapes.controlSmall))
            .overlay {
                RoundedRectangle(cornerRadius: TrenifyShapes.controlSmall)
                    .strokeBorder(selected ? TrenifyColors.accent : .clear, lineWidth: 2)
            }
            .contentShape(RoundedRectangle(cornerRadius: TrenifyShapes.controlSmall))
        }
        .buttonStyle(TrenifyContentControlStyle())
        .disabled(!enabled || !option.enabled)
        .accessibilityLabel(Text(option.title))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
