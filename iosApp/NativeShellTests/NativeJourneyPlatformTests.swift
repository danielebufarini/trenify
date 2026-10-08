import XCTest
import SwiftUI
import UIKit
import Vision
@preconcurrency import SharedApp

@MainActor
final class NativeJourneyPlatformTests: XCTestCase {
    private func poll(_ description: String, _ predicate: @escaping () -> Bool) async {
        let ready = expectation(description: description)
        let task = Task { @MainActor in
            for _ in 0..<100 {
                if predicate() { ready.fulfill(); return }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        await fulfillment(of: [ready], timeout: 6)
        task.cancel()
        await task.value
    }

    private func openDetail(_ fixture: NativeInteropFixture) async -> NativeJourneyDetailModel {
        fixture.selectHome()
        let input = fixture.session.state.value.main!.state.value.journey!.state.value.journeySearch!
        input.stationText(origin: true, text: "Roma")
        await poll("origin suggestions") { !input.state.value.suggestions.isEmpty }
        input.selectStation(station: input.state.value.suggestions.first(where: { $0.name == "Roma Termini" })!)
        input.stationText(origin: false, text: "Milano")
        await poll("destination suggestions") { !input.state.value.suggestions.isEmpty }
        input.selectStation(station: input.state.value.suggestions.first(where: { $0.name == "Milano Centrale" })!)
        input.search()
        fixture.session.state.value.main!.state.value.home!.openJourneySearch()
        let shell = fixture.session.shell
        await poll("results") { shell.state.value.active.journeyResults?.state.value.journeys.isEmpty == false }
        shell.state.value.active.journeyResults!.selectJourney(index: 0)
        await poll("detail") { shell.state.value.active.journeyDetail != nil }
        let model = NativeJourneyDetailModel(session: fixture.session, identity: shell.state.value.active.identity)
        model.start()
        await poll("detail correlation settles") { model.state?.correlating == false && model.state?.legs.count == 1 }
        return model
    }

    private func renderedText(_ window: UIWindow, name: String) throws -> String {
        window.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        // Read the actual rendered SwiftUI rows. XCTest unit hosts cannot
        // query SwiftUI's accessibility runtime tree as XCUITest can.
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["en-US"]
        try VNImageRequestHandler(cgImage: try XCTUnwrap(image.cgImage)).perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
    }

    func testScheduledAndActualPlatformRowsRenderIndependently() async throws {
        let cases: [(String?, String?, Bool)] = [
            ("7", nil, false), ("7", nil, true), ("7", "8", true),
            (nil, "8", true), (nil, nil, false), ("7", "7", true)
        ]
        for (index, entry) in cases.enumerated() {
            let (scheduled, actual, realtime) = entry
            let fixture = NativeInteropFixture()
            fixture.stageJourneyPlatforms(scheduledPlatform: scheduled, actualPlatform: actual, realtimeAvailable: realtime)
            let model = await openDetail(fixture)
            let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
            let appeared = expectation(description: "journey platform rows appear")
            let window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 430, height: 1000)
            window.rootViewController = UIHostingController(rootView:
                NativeJourneyDetailView(model: model).environment(\.locale, Locale(identifier: "en_US"))
                    .onAppear { appeared.fulfill() })
            window.makeKeyAndVisible()
            await fulfillment(of: [appeared], timeout: 5)
            defer { window.isHidden = true; _ = model.close(); fixture.close() }
            XCTAssertEqual(model.state?.legs.first?.scheduledPlatform, scheduled)
            XCTAssertEqual(model.state?.legs.first?.actualPlatform, actual)
            let text = try renderedText(window, name: "ios-journey-platform-case-\(index)")
            if let scheduled {
                XCTAssertTrue(text.contains("Expected platform \(scheduled)"), text)
            } else {
                XCTAssertFalse(text.contains("Expected platform"), text)
            }
            if let actual {
                XCTAssertTrue(text.contains("Actual platform \(actual)"), text)
            } else {
                XCTAssertFalse(text.contains("Actual platform"), text)
            }
            if realtime {
                fixture.stageJourneyRealtimeFailure()
                await poll("realtime failure is observed") { model.state?.legs.first?.realtimeFailed == true }
                XCTAssertEqual(model.state?.legs.first?.scheduledPlatform, scheduled)
                XCTAssertNil(model.state?.legs.first?.actualPlatform)
                let failedText = try renderedText(window, name: "ios-journey-platform-failure-\(index)")
                if let scheduled { XCTAssertTrue(failedText.contains("Expected platform \(scheduled)"), failedText) }
                XCTAssertFalse(failedText.contains("Actual platform"), failedText)
            }
            XCTAssertEqual(fixture.networkRequests, 0)
        }
    }
}
