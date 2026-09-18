#if DEBUG
import SwiftUI

/// Development-only fixtures. No providers, navigation, KMP state or production gallery entry.
struct TrenifyComponentExamples: View {
    @State private var selection = "journey"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: TrenifySpacing.xl) {
                TrenifySectionHeader(title: "Component family", subtitle: "Deterministic railway display fixtures")
                TrenifySegmentedControl(options: [TrenifySegment(id: "journey", title: "Journey"), TrenifySegment(id: "train", title: "Train")],
                                        selection: $selection, accessibilityLabel: "Search mode")
                TrenifyStationField(roleLabel: "From", stationName: "San Benedetto del Tronto Porto d’Ascoli",
                                    placeholder: "Choose station", selected: true, onClear: {}) {}
                TrenifyStationField(roleLabel: "To", stationName: nil, placeholder: "Choose station", selected: false, focused: true) {}
                TrenifyStationField(roleLabel: "Station", stationName: "Roma Termini", placeholder: "Choose station", selected: true, enabled: false) {}
                TrenifyPrimaryAction(title: "Search trains") {}
                TrenifyPrimaryAction(title: "Search trains", loading: true) {}
                TrenifySecondaryAction(title: "Change selection", enabled: false) {}
                TrenifyJourneyCard(trainIdentity: "Trenitalia · Frecciarossa 9516", departureTime: "14:10", arrivalTime: "17:19",
                                   origin: "Milano Centrale", destination: "Roma Termini", durationLabel: "3 h 09 min", changesLabel: "Direct",
                                   statusTone: .onTime, statusLabel: "On time", platformLabel: "Scheduled platform 8") {}
                TrenifyStatusPill(tone: .delayed, label: "Delayed +12 min")
                TrenifyStatusPill(tone: .cancelled, label: "Cancelled")
                TrenifyStatusPill(tone: .arrived, label: "Arrived")
                TrenifyStatusPill(tone: .warning, label: "Service warning")
                TrenifyStatusPill(tone: .unknown, label: "Status unavailable")
            }
            .padding(TrenifySpacing.screenHorizontal)
        }
        .background(TrenifyColors.background)
    }
}

struct TrenifyComponentPreviews: PreviewProvider {
    static var previews: some View {
        Group {
            TrenifyComponentExamples().environment(\.colorScheme, .light).previewDisplayName("Light / component family")
            TrenifyComponentExamples().environment(\.colorScheme, .dark).previewDisplayName("Dark / component family")
            TrenifyComponentExamples().environment(\.dynamicTypeSize, .accessibility3)
                .previewLayout(.fixed(width: 320, height: 1800)).previewDisplayName("Large text / narrow")
            TrenifyComponentExamples().environment(\.colorScheme, .dark)
                .environment(\.trenifyAccessibility, TrenifyAccessibility(reduceMotion: true, reduceTransparency: true))
                .previewDisplayName("Dark / opaque actions / reduced motion")
        }
    }
}
#endif
