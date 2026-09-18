import SwiftUI

struct TrenifySectionHeader: View {
    var title: String
    var subtitle: String? = nil
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text(title).font(TrenifyTypography.sectionTitle).foregroundStyle(TrenifyColors.textPrimary)
                .accessibilityAddTraits(.isHeader)
            if let subtitle { Text(subtitle).font(TrenifyTypography.metadata).foregroundStyle(TrenifyColors.textSecondary) }
            if let actionLabel, let action {
                Button(actionLabel, action: action).font(TrenifyTypography.label)
                    .frame(minHeight: 44).tint(TrenifyColors.accent)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
