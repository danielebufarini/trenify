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
                TrenifyStatusPill(tone: .warning, label: RailwayStrikeLabel(warning))
                    .accessibilityIdentifier("\(tagPrefix)-strike-impact-\(warning.strikeId)")
            }
            .accessibilityIdentifier("\(tagPrefix)-strike-\(warning.strikeId)")
        }
        // Without a warning, retain coverage status rather than imply no strike.
        if warnings.isEmpty && unknown {
            Text(railwayPresentationString("journey.warningUnknown"))
                .font(TrenifyTypography.status)
                .foregroundStyle(TrenifyColors.statusWarning)
                .accessibilityIdentifier("\(tagPrefix)-strikes-unknown")
        } else if warnings.isEmpty && (stale || failed) {
            Text(railwayPresentationString("journey.warningStale"))
                .font(TrenifyTypography.status)
                .foregroundStyle(TrenifyColors.statusWarning)
                .accessibilityIdentifier("\(tagPrefix)-strikes-stale")
        }
    }
}

func RailwayStrikeLabel(_ warning: NativeServiceStrikeWarningPresentation) -> String {
    let interval = String.localizedStringWithFormat(
        railwayPresentationString("journey.strikeInterval"),
        RailwayFormatting.dateTime(warning.startEpochSeconds),
        RailwayFormatting.dateTime(warning.endEpochSeconds))
    return railwayPresentationString("journey.strikeImpactNone") + " · " + interval
}
