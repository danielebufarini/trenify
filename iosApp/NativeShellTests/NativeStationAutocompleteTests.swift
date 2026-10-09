import XCTest
import SwiftUI
import UIKit
import Vision
@preconcurrency import SharedApp

/// Recorded catalog HTTP -> production Kotlin providers/repository/Decompose -> SKIE -> native model/view.
/// This is simulator replay evidence, not live operator availability or physical-device acceptance.
@MainActor
final class NativeStationAutocompleteTests: HomeModelTestCase {
    func testRecordedCatalogReachesNativeSuggestionsAndSelectionKeepsIdentity() async throws {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.useRecordedStationCatalog()
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }

        model.stationText(origin: true, text: "milano")
        await awaitCondition("Milano catalog suggestions") { model.journeyInput?.suggestions.first?.name == "Milano Centrale" }
        model.selectStation(model.journeyInput!.suggestions[0])
        await awaitCondition("Milano selected") { model.journeyInput?.origin.map { NativeSemanticIdentity.shared.station(station: $0) } == "location:italo-journeys:MC_" }

        model.stationText(origin: false, text: "roma")
        await awaitCondition("Roma catalog ranking") {
            model.journeyInput?.suggestions.prefix(2).map(\.name) == ["Roma Termini", "Roma Tiburtina"]
        }
        XCTAssertEqual(model.journeyInput?.suggestions.count, 87)

        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            XCTFail("Native suggestion rendering requires a simulator window scene")
            return
        }
        let appeared = expectation(description: "native Home suggestions render")
        appeared.assertForOverFulfill = false
        let host = UIHostingController(rootView: NativeHomeView(model: model).onAppear { appeared.fulfill() })
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        await fulfillment(of: [appeared], timeout: 5)
        // SwiftUI virtual accessibility rows are not exposed to an in-process UIKit tree walk.
        // Verify the actual rendered labels and vertical order with the system Vision framework.
        window.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "Native Roma suggestions"
        attachment.lifetime = .keepAlways
        add(attachment)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["it-IT", "en-US"]
        try VNImageRequestHandler(cgImage: XCTUnwrap(image.cgImage)).perform([request])
        let rows = request.results ?? []
        let labels = rows.compactMap { $0.topCandidates(1).first?.string }
        print("Rendered native station labels: \(labels)")
        let termini = try XCTUnwrap(rows.first { $0.topCandidates(1).first?.string == "Roma Termini" })
        let tiburtinaRow = try XCTUnwrap(rows.first { $0.topCandidates(1).first?.string == "Roma Tiburtina" })
        XCTAssertGreaterThan(termini.boundingBox.midY, tiburtinaRow.boundingBox.midY)
        XCTAssertFalse(labels.contains("CASIGNANA VILLA ROMANA"))
        let tiburtina = model.journeyInput!.suggestions[1]
        model.selectStation(tiburtina)
        await awaitCondition("Tiburtina resolved") { model.journeyInput?.destination.map { NativeSemanticIdentity.shared.station(station: $0) } == "location:italo-journeys:RTB" }
        XCTAssertEqual(model.journeyInput?.destinationText, "Roma Tiburtina")
        XCTAssertEqual(model.journeyInput?.origin.map { NativeSemanticIdentity.shared.station(station: $0) }, "location:italo-journeys:MC_")
        XCTAssertTrue(model.journeyInput?.suggestions.isEmpty ?? false)

        model.stationText(origin: false, text: "terni")
        await awaitCondition("Terni catalog suggestion") { model.journeyInput?.suggestions.first?.name == "Terni" }
        model.selectStation(model.journeyInput!.suggestions[0])
        await awaitCondition("Terni selected") { model.journeyInput?.destination.map { NativeSemanticIdentity.shared.station(station: $0) } == "location:trenitalia-journeys:830007226" }
        XCTAssertTrue(fixture.sameRoot)
    }

}
