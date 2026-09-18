import XCTest
import SwiftUI
import UIKit

@MainActor
final class TrenifyDesignSystemTests: XCTestCase {
    func testUnknownUsesNeutralTreatmentAndAllStatusSymbolsAreDistinct() {
        XCTAssertEqual(Set(TrenifyStatusTone.allCases.map(\.symbol)).count, TrenifyStatusTone.allCases.count)
        XCTAssertEqual(TrenifyStatusTone.unknown.color, TrenifyColors.textSecondary)
        XCTAssertNotEqual(TrenifyStatusTone.unknown.color, TrenifyStatusTone.onTime.color)
    }

    func testGlassAvailabilityAndAccessibilityPolicy() {
        for version in [16, 17, 25, 26, 27] {
            for reduce in [false, true] {
                for contrast in [false, true] {
                    XCTAssertEqual(TrenifyActionMaterialPolicy.usesGlass(osMajor: version, reduceTransparency: reduce, increasedContrast: contrast),
                                   version >= 26 && !reduce && !contrast)
                }
            }
        }
    }

    func testStatusAndBrandContrastInNativeLightAndDarkSurfaces() {
        for style in [UIUserInterfaceStyle.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: style)
            let background = UIColor(TrenifyColors.surfaceMuted).resolvedColor(with: traits)
            for tone in TrenifyStatusTone.allCases {
                XCTAssertGreaterThanOrEqual(contrast(UIColor(tone.color).resolvedColor(with: traits), background), 4.5, "\(style): \(tone)")
            }
            XCTAssertGreaterThanOrEqual(contrast(UIColor(TrenifyColors.accentOn).resolvedColor(with: traits),
                                                 UIColor(TrenifyColors.accent).resolvedColor(with: traits)), 4.5)
        }
    }

    func testReducedMotionRemovesEveryCustomAnimation() {
        for role in TrenifyMotionRole.allCases {
            XCTAssertNil(role.animation(reduceMotion: true))
            XCTAssertNotNil(role.animation(reduceMotion: false))
        }
    }

    func testNativeComponentFamilyRendersGlassAndOpaqueFallbacks() async {
        for dark in [false, true] {
            for opaque in [false, true] {
                let fixture = ComponentFixture()
                    .environment(\.colorScheme, dark ? .dark : .light)
                    .environment(\.trenifyAccessibility, TrenifyAccessibility(reduceMotion: true, reduceTransparency: opaque))
                let host = UIHostingController(rootView: fixture)
                let size = host.sizeThatFits(in: CGSize(width: 390, height: 10000))
                XCTAssertTrue(size.height.isFinite && size.height > 500)
                await attach(host, width: 390, height: size.height, name: "Components-\(dark ? "dark" : "light")-\(opaque ? "opaque" : "native-glass")")
            }
        }
        // Increased Contrast exercises the other opaque system-style branch.
        let contrastHost = UIHostingController(rootView: ComponentFixture().environment(\.trenifyAccessibility, TrenifyAccessibility(increasedContrast: true)))
        XCTAssertGreaterThan(contrastHost.sizeThatFits(in: CGSize(width: 390, height: 10000)).height, 500)
    }

    func testDynamicTypeGrowsStationAndJourneyWithoutFixedHeightClipping() async {
        let normal = UIHostingController(rootView: ComponentFixture().environment(\.dynamicTypeSize, .large))
        let large = UIHostingController(rootView: ComponentFixture().environment(\.dynamicTypeSize, .accessibility3))
        let constraint = CGSize(width: 320, height: 10000)
        let normalSize = normal.sizeThatFits(in: constraint)
        let largeSize = large.sizeThatFits(in: constraint)
        XCTAssertTrue(largeSize.height.isFinite)
        XCTAssertGreaterThan(largeSize.height, normalSize.height * 1.3)
        await attach(large, width: 320, height: largeSize.height, name: "Components-large-text-narrow")
    }

    private func attach<V: View>(_ measuredHost: UIHostingController<V>, width: CGFloat, height: CGFloat, name: String) async {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            XCTFail("Component rendering requires the development gallery's native window scene")
            return
        }
        let appeared = expectation(description: "Native component hierarchy appeared")
        appeared.assertForOverFulfill = false
        let host = UIHostingController(rootView: ScrollView { measuredHost.rootView }.onAppear { appeared.fulfill() })
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: width, height: min(ceil(height), 1500))
        window.rootViewController = host
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        host.view.frame = window.bounds
        host.view.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(host.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        window.isHidden = true
    }

    private func contrast(_ foreground: UIColor, _ background: UIColor) -> Double {
        func rgba(_ color: UIColor) -> [Double] {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.getRed(&r, green: &g, blue: &b, alpha: &a)
            return [Double(r), Double(g), Double(b), Double(a)]
        }
        func luminance(_ channels: [Double]) -> Double {
            let linear = channels.prefix(3).map { $0 <= 0.04045 ? $0 / 12.92 : pow(($0 + 0.055) / 1.055, 2.4) }
            return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
        }
        let bg = rgba(background)
        let fg = rgba(foreground)
        let composed = (0..<3).map { fg[$0] * fg[3] + bg[$0] * (1 - fg[3]) }
        let a = luminance(composed), b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}

private struct ComponentFixture: View {
    @State private var selected = "journey"
    var body: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xl) {
            TrenifySectionHeader(title: "Component family", subtitle: "Deterministic fixtures")
            TrenifySegmentedControl(options: [TrenifySegment(id: "journey", title: "Journey"), TrenifySegment(id: "train", title: "Train"),
                                              TrenifySegment(id: "disabled", title: "Disabled", enabled: false)],
                                    selection: $selected, accessibilityLabel: "Search mode")
            TrenifyStationField(roleLabel: "From", stationName: "San Benedetto del Tronto Porto d’Ascoli", placeholder: "Choose station",
                                selected: true, onClear: {}) {}
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
            TrenifyStatusPill(tone: .unknown, label: "Status unavailable")
        }
        .padding(TrenifySpacing.screenHorizontal)
        .background(TrenifyColors.background)
    }
}
