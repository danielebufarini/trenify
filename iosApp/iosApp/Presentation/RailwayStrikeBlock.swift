import SwiftUI
@preconcurrency import SharedApp

private final class RailwayPresentationBundleToken: NSObject {}

func railwayPresentationString(_ key: String) -> String {
    NSLocalizedString(key, tableName: "RailwayPresentation", bundle: Bundle(for: RailwayPresentationBundleToken.self), comment: "")
}

/// Cross-feature rendering for the shared semantic strike-warning projection.
func RailwayStrikeBlock(
    warnings: [NativeServiceStrikeWarningPresentation],
    stale: Bool,
    failed: Bool,
    unknown: Bool,
    tagPrefix: String
) -> some View {
    VStack(alignment: .leading, spacing: TrenifySpacing.s) {
        ForEach(warnings, id: \.strikeId) { warning in
            VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
                TrenifyStatusPill(tone: .warning, label: RailwayStrikeImpactLabel(warning))
                    .accessibilityIdentifier("\(tagPrefix)-strike-impact-\(warning.strikeId)")
                Text(warning.sector)
                    .font(TrenifyTypography.bodyEmphasized)
                    .foregroundStyle(TrenifyColors.textPrimary)
                Text(String.localizedStringWithFormat(
                    railwayPresentationString("journey.strikeInterval"),
                    RailwayFormatting.dateTime(warning.startEpochSeconds),
                    RailwayFormatting.dateTime(warning.endEpochSeconds)))
                .font(TrenifyTypography.metadata)
                .foregroundStyle(TrenifyColors.textSecondary)
                .accessibilityIdentifier("\(tagPrefix)-strike-interval-\(warning.strikeId)")
                if let url = warning.sourceUrl {
                    let label = [warning.sourceLabel, url].compactMap { $0 }.joined(separator: " · ")
                    Text(String.localizedStringWithFormat(railwayPresentationString("journey.strikeSource"), label))
                        .font(TrenifyTypography.metadata)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("\(tagPrefix)-strike-source-\(warning.strikeId)")
                }
                if warning.partialContext {
                    Text(railwayPresentationString("journey.strikePartial"))
                        .font(TrenifyTypography.caption)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("\(tagPrefix)-strike-partial-\(warning.strikeId)")
                }
            }
            .accessibilityIdentifier("\(tagPrefix)-strike-\(warning.strikeId)")
        }
        // Unknown means no authoritative coverage. Stale also covers failed
        // refreshes with retained content; neither state implies no issue.
        if unknown {
            Text(railwayPresentationString("journey.warningUnknown"))
                .font(TrenifyTypography.status)
                .foregroundStyle(TrenifyColors.statusWarning)
                .accessibilityIdentifier("\(tagPrefix)-strikes-unknown")
        } else if stale || failed {
            Text(railwayPresentationString("journey.warningStale"))
                .font(TrenifyTypography.status)
                .foregroundStyle(TrenifyColors.statusWarning)
                .accessibilityIdentifier("\(tagPrefix)-strikes-stale")
        }
    }
}

func RailwayStrikeImpactLabel(_ warning: NativeServiceStrikeWarningPresentation) -> String {
    if warning.officiallyConfirmed { return railwayPresentationString("journey.warningConfirmed") }
    switch warning.impact {
    case .none: return railwayPresentationString("journey.strikeImpactNone")
    case .potential: return railwayPresentationString("journey.strikeImpactPotential")
    case .likely: return railwayPresentationString("journey.strikeImpactLikely")
    case .confirmedByOperator: return railwayPresentationString("journey.warningConfirmed")
    @unknown default: return railwayPresentationString("journey.strikeImpactNone")
    }
}
