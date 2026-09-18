import SwiftUI

func journeyCardSummaryLabel(duration: String, structure: String) -> String {
    "\(duration) · \(structure)"
}

struct TrenifyJourneyCard: View {
    var trainIdentity: String
    var departureTime: String
    var arrivalTime: String
    var origin: String
    var destination: String
    var durationLabel: String
    var changesLabel: String
    var statusTone: TrenifyStatusTone = .unknown
    /// Nil omits the status pill: neutral/default content carries no badge.
    var statusLabel: String? = nil
    var platformLabel: String? = nil
    var detailsLabel = String(localized: "Details")
    var departureLabel = String(localized: "Departure")
    var arrivalLabel = String(localized: "Arrival")
    var enabled = true
    var onDetails: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Text(trainIdentity).font(TrenifyTypography.trainIdentity).foregroundStyle(TrenifyColors.textSecondary)
            if let statusLabel { TrenifyStatusPill(tone: statusTone, label: statusLabel) }
            ViewThatFits(in: .horizontal) {
                if typeSize < .xxxLarge {
                    HStack(alignment: .top, spacing: TrenifySpacing.xl) {
                        endpoint(departureLabel, time: departureTime, station: origin)
                        endpoint(arrivalLabel, time: arrivalTime, station: destination)
                    }
                    .fixedSize(horizontal: true, vertical: false)
                }
                VStack(alignment: .leading, spacing: TrenifySpacing.l) {
                    endpoint(departureLabel, time: departureTime, station: origin)
                    endpoint(arrivalLabel, time: arrivalTime, station: destination)
                }
            }
            Rectangle().fill(TrenifyColors.divider).frame(height: 1).accessibilityHidden(true)
            HStack(alignment: .center, spacing: TrenifySpacing.s) {
                Text(journeyCardSummaryLabel(duration: durationLabel, structure: changesLabel))
                    .font(TrenifyTypography.bodyEmphasized)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onDetails) {
                    Label(detailsLabel, systemImage: "arrow.right")
                        .font(TrenifyTypography.bodyEmphasized)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(TrenifyContentControlStyle())
                .foregroundStyle(enabled ? TrenifyColors.accent : TrenifyColors.textTertiary)
                .disabled(!enabled)
            }
            if let platformLabel { Text(platformLabel).font(TrenifyTypography.metadata).foregroundStyle(TrenifyColors.textSecondary) }
        }
        .foregroundStyle(TrenifyColors.textPrimary)
        .padding(.horizontal, TrenifySpacing.xl)
        .padding(.vertical, TrenifySpacing.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    private func endpoint(_ role: String, time: String, station: String) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text(role).font(TrenifyTypography.caption).foregroundStyle(TrenifyColors.textTertiary)
            Text(time).font(TrenifyTypography.routeTime)
            Text(station).font(TrenifyTypography.routeStation).fixedSize(horizontal: false, vertical: true)
        }
    }
}
